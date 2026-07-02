# Wi-Fi Aware 工作原理 & Plain App 设计

> 配套 [`wifi-aware-refactor.md`](./wifi-aware-refactor.md) 是按 Client/Server 拆分的 refactor plan。

---

## 1. Wi-Fi Aware 一句话

两台 Android 8+ 设备直接 P2P 通信,不依赖 AP、不依赖 Internet。

- 不在同 Wi-Fi 也能连
- 距离 ~50-200 米
- 比 Bluetooth 快,比 Wi-Fi Direct 省电

Plain App 用它让配对过的设备在 **任意 Wi-Fi 环境** 下直接聊天。

---

## 2. 三层模型

```
Layer 1: Discovery       找对方在不在(拿 PeerHandle)
Layer 2: Provisioning    把 PeerHandle 变成 IP+port(拿 Network)
Layer 3: Application     在 Network 上跑 HTTP(拿 httpClient)
```

每层独立 API,上一层等下一层就绪再干活。

---

## 3. 角色:Client vs Server

P2P link 是 **双向** 的,但发起方(client)和接收方(server)的 API 调用不一样:

| | Client(发起方) | Server(接收方) |
|---|---|---|
| 谁调用 subscribe | ✓ | ✓(也 subscribe,用于检测对方,见 §7) |
| 谁调用 publish(发服务) | ✓(全局) | ✓(全局) |
| requestNetwork 角色 | Client role + SubscribeSession + handle | Server role + PublishSession + handle + port |
| Network 类型 | clientNetwork(对方可达) | serverNetwork(自己监听) |
| 知道对方 IPv6 | ✓(从 onCapabilitiesChanged) | |
| 当前是否 build | **✓ 是** | ✗ 否(反方向走 WebSocket) |

两端各调一次 `requestNetwork`,各拿一个 Network,**这两个 Network 内部就是同一条 P2P link**。

---

## 4. 完整时序(无 ANNOUNCE)

```
P9 (Client 角色)                       P7 (responder)
────────────────────────────────────────────────────────
attach (WifiAwareSession)
publish("plain-peer", svcInfo=clientId_P9)
subscribe("plain-peer")

[Layer 1: Discovery — Client 主动]
subscribe.onServiceDiscovered(handle_P7, svcInfo=clientId_P7)
  match clientId_P7 == target → PeerHandle

[Layer 2: Provisioning]
requestNetwork(client specifier, callback)
  callback.onAvailable → clientNetwork
  callback.onCapabilitiesChanged
    → peerIpv6, peerPort(对方 server 的)

[Layer 3: Application]
httpClient.newCall(POST /peer_graphql)
  via clientNetwork.socketFactory
```

**没有 ANNOUNCE**:server 端不 build,不需要触发信号。

---

## 5. ANNOUNCE 为什么省了

| 用途 | 触发 server 端 `requestNetwork` |
|---|---|
| 我们需要吗 | **不需要**(server 不 build) |
| 原代码 | client `subscribe.sendMessage(ANNOUNCE_PREFIX + clientId)`,server `publish.onMessageReceived` 收 |
| 实际效果 | server 只 log,没人用 |
| 删除后 | client 直接 requestNetwork,少一步 |

将来真要 server,用 `subscribe.onServiceDiscovered` 匹配 pairedPeerIds 触发(详见 `wifi-aware-refactor.md` §6.4)。

---

## 6. 类设计

### 6.1 职责速查

| 类 | 干哪一边 | 行数 |
|---|---|---|
| `AwareSession` | 全局:attach + publish + 生命周期 | ~110 |
| `AwareClient` | Client 端:subscribe + 等 handle + requestNetwork(client) + httpClient | ~110 |
| `AwareServer` | Server 端:subscribe 匹配 + requestNetwork(server)(将来用) | ~70 |
| `AwarePeerLink` | 编排:持 client + 可选 server | ~70 |
| `ClientConnection` | data class:client 端拿到的(NW + ipv6 + port + http) | 11 |

### 6.2 AwareSession — 全局生命周期

**职责**:attach / publish 谁负责?

```
start() / stop()              ← attach + publish 全局 + 关闭
awaitReady()                  ← suspend,attach 完才返回
markStale()                   ← reattach 标记
isAvailable()                 ← 设备是否支持
```

**内部**:
- 持有 `WifiAwareSession` 和 `PublishDiscoverySession`
- **没有 ANNOUNCE 处理**(删除后)
- publish 单纯为了"对方能 subscribe 看到我"

### 6.3 AwareClient — Client 端全套

**职责**:我怎么主动连一个 peer?

```kotlin
class AwareClient(
    session: AwareSession,
    peerId: String,     // 对方 clientId
    peerKey: String,    // HTTP token
)
```

```
suspend fun connect(): ClientConnection   ← 完整流程
suspend fun close()
```

**`connect()` 内部**:

```
session.subscribe("plain-peer", callback)
  callback.onServiceDiscovered(handle, info):
    if info.clientId == peerId:
      handleDeferred.complete(handle)
await handle
requestNetwork(client specifier, callback) {
  onAvailable + onCapabilitiesChanged → 4 字段齐 → ClientConnection
}
return ClientConnection
```

### 6.4 AwareServer — Server 端全套(将来用)

**职责**:我怎么接收一个 peer 的连接?

```kotlin
class AwareServer(
    session: AwareSession,
    pairedPeerIds: Set<String>,  // 服务哪些 clientId
    port: Int,                    // 自己监听
)
```

```
suspend fun connect(): ServerConnection
suspend fun close()
```

**`connect()` 内部**(无 ANNOUNCE 版):

```
session.subscribe("plain-peer", callback)
  callback.onServiceDiscovered(handle, info):
    if info.clientId in pairedPeerIds:
      matchedHandle.complete(handle)
await matchedHandle
requestNetwork(server specifier, callback) {
  setPort(port)
  onAvailable → serverNetwork
}
return ServerConnection(network, port)
```

**为什么目前不调用**:send 方向只走 client。反方向走 WebSocket(`/status`)。Server 是为对称 + 未来扩展用。

### 6.5 AwarePeerLink — 编排

**职责**:持 client(必有)+ server(可选),做编排。

```kotlin
class AwarePeerLink(
    peerId: String,
    client: AwareClient,
    server: AwareServer?,            // 当前 null
    onClose: (peerId, reason) -> Unit,
)
```

```
suspend fun build(peer): ClientConnection   ← 调 client.connect()
fun prewarm()                               ← client.subscribe 不等 handle
fun touch()                                 ← 更新 lastActiveAt
fun close(reason)                           ← 清理 + 通知 Transport
```

**build 3 行**:

```kotlin
suspend fun build(peer): ClientConnection {
    connection.get()?.let { return it }
    return buildLock.withLock {
        connection.get() ?: client.connect().also { connection.set(it) }
    }
}
```

**没有任何协议细节**。读这段代码的人脑里只有一个概念:"调 client 把事办了"。

### 6.6 ClientConnection

```kotlin
data class ClientConnection(network, peerIpv6, peerPort, httpClient)
```

---

## 7. 关键设计决策

### 7.1 为什么按角色拆

用户脑里模型是 **"我是 client 还是 server"**。

| 想知道... | 翻哪个文件 |
|---|---|
| 主动连 peer 怎么连 | AwareClient |
| 接收 peer 怎么接 | AwareServer |
| publish session 怎么管 | AwareSession |
| 单 link 怎么编排 | AwarePeerLink |

### 7.2 为什么 ANNOUNCE 删了

server 不 build,ANNOUNCE 收发都没用。详见 §5。

### 7.3 为什么不只写 Client

Client 和 Server 对称,接口镜像。Server 70 行,代价小。文档里讲"现在不调用",**透明**。

### 7.4 重复代码

| 重复点 | 处理 |
|---|---|
| `requestNetwork` + `NetworkCallback` | Client/Server 各一份(差异是 specifier 和 port) |
| PMK 派生 | 抽成 Session 私有函数 |

Client 和 Server 的 `requestNetwork` 实现有 ~30 行差异,**不强行合并**(重复优于错误的抽象)。

---

## 8. 一图概览

```
WifiAwareTransport (路由)
└─ send(peer)
   └─ linkFor(peer)
      └─ AwarePeerLink
         ├─ AwareClient ──────────► ClientConnection
         │    subscribe → 等 onServiceDiscovered(handle, clientId match)
         │    → requestNetwork(client role)
         │    → httpClient
         │
         └─ AwareServer ──────────► ServerConnection   (当前 null)
              subscribe → 等 onServiceDiscovered(clientId in pairedPeerIds)
              → requestNetwork(server role)
              → serverNetwork

AwareSession (全局)
├─ attach + publish session 管理
└─ (无 onMessageReceived,无 ANNOUNCE)
```

---

## 9. 名词

| 术语 | 含义 |
|---|---|
| NAN | Wi-Fi Aware 标准协议名 |
| publish / subscribe | 服务发现机制,类似 mDNS |
| Service name | service 标识符,Plain App 用 "plain-peer" |
| serviceSpecificInfo | publish 时塞的字节,我们塞 clientId 做配对过滤 |
| PeerHandle | 对方在 Aware link 上的 opaque handle |
| WifiAwareNetworkSpecifier | requestNetwork 参数,描述角色 + 加密 + port |
| Client role | `requestNetwork` 用 SubscribeSession,主动发起 |
| Server role | `requestNetwork` 用 PublishSession + port,接收 |
| WifiAwareNetworkInfo | `caps.transportInfo`,含 peer IPv6 + peer port |
| PMK | Pre-Shared Key,Aware 链路层加密种子 |
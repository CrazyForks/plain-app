# Aware Refactor Plan — Client/Server 拆分(无 ANNOUNCE)

> 把组件按 **角色**(Client vs Server)拆,不用按协议层拆。
> 配套 [`wifi-aware-explained.md`](./wifi-aware-explained.md) 讲协议本身,本文讲代码设计。
>
> **核心思路**:`AwareClient` 干 client 该干的事,`AwareServer` 干 server 该干的事。读代码的人看名字就知道在干哪一边。

---

## 0. 为什么改用 Client/Server 拆分

之前按协议层拆(Discovery / Connector / Link),组件边界和 Aware 协议对齐,但**对人不友好**:
- "我要给 peer 发消息,得用 Discovery + Connector + Link 三件套" —— 不好记
- "我在 build 一个 connection,这中间到底发生了什么" —— 要在 3 个文件之间跳
- 读代码的人脑里模型是 **"我是 client 还是 server"**,不是 "我在哪一层"

按角色拆以后:
- "我要发消息 → 用 `AwareClient`"
- "我要接消息 → 用 `AwareServer`"
- `AwarePeerLink` 持一个 client(必有)+ 一个 server(可选)

新人看代码 **从角色入手**,不迷路。

---

## 1. 角色边界(谁干什么)

| 角色 | 干的事 | 不干的事 |
|---|---|---|
| **AwareClient** | subscribe、匹配 clientId、等 PeerHandle、requestNetwork(client role)、拿 httpClient | 不管 publish、不管 server Network |
| **AwareServer** | requestNetwork(server role)、拿 server Network | 不管 subscribe、不管 client Network |
| **AwareSession** | attach、publish 全局 session、reattach | 不管 per-peer 状态、不管 http |
| **AwarePeerLink** | 持 client(必有)+ server(可选)、编排 build、按需通知 Transport | 不管 Aware API 细节 |

**对称性**:

```
AwareClient                       AwareServer
─────────────────────             ─────────────────────
subscribe →                       (无 subscribe,直接 build)
  onServiceDiscovered(handle, info)
  match clientId
  → PeerHandle
requestNetwork(client role)       requestNetwork(server role, setPort)
  ↓                                  ↓
clientNetwork + peerIpv6 + port   serverNetwork
  ↓
httpClient.newCall(req)
```

两边各拿一个 `Network`,**内部是同一条 P2P link**,socketFactory 双向可达。

---

## 2. ANNOUNCE:可以省

原方案有 ANNOUNCE 协议:client 发 `subscribe.sendMessage(handle, ANNOUNCE_PREFIX + clientId)`,server 在 `publish.onMessageReceived` 收,然后才开始 `requestNetwork(server)`。

**为什么删**:
- ANNOUNCE 的唯一目的是触发 server 端 `requestNetwork`
- 我们当前 **不建 server 端 link**(反方向走 WebSocket)
- ANNOUNCE 接收方(`AwareSession.publishCallback.onMessageReceived`)只 log 一行,**没人用**
- ANNOUNCE 发送方(client)发完也 **没人接**

**省略后**:
- `AwareSession` 不再有 `onMessageReceived` 处理
- `AwareClient.connect()` 砍掉 `sendMessage` 一步
- 没有 `ANNOUNCE.kt`、没有 ANNOUNCE 协议字符串、没有路由

**Server 端将来怎么触发**(写 AwareServer 时):
- 不靠 ANNOUNCE
- server 端 `subscribe.onServiceDiscovered` 看到对端后,用对端 clientId 匹配 paired list
- 命中 → requestNetwork(server role)
- 见 §6.4 详细说明

---

## 3. 目标结构

```
shared/src/androidMain/kotlin/com/ismartcoding/plain/chat/peer/transport/aware/
├── AwareSession.kt          ← 全局生命周期 + publish
├── AwareClient.kt           ← 单 peer 的 client 端全套
├── AwareServer.kt           ← 单 peer 的 server 端全套(目前不 build,留接口)
└── ClientConnection.kt      ← data class,client 端拿到的 connection

shared/.../transport/
├── AwarePeerLink.kt         ← 持 client(必有)+ server(可选),编排
├── AwareLinkPool.kt         ← link map + idle sweep + prewarm
├── AwareHttpClientFactory.kt ← 不变
└── WifiAwareTransport.kt    ← 路由 + session 协调
```

**变化**:之前 4 个组件 → 现在 4 个组件(更少,但职责清晰)。删除 `ANNOUNCE.kt`、`ServerConnection.kt`(Server 不 build 时不需要)。

**注**:server 端不 build 时,`ServerConnection` 可以延后写。等真要 server 时再加。

---

## 4. 类详解

### 4.1 `AwareSession` — 全局生命周期

**答**:attach 谁负责?publish session 谁持有?

```
start()              ← attach + publish 全局 service
stop()               ← 关 session
awaitReady()         ← suspend,attach 完才返回
markStale()          ← 标记 reattach
isAvailable()        ← 设备硬件支持检测
```

**内部**:
- 持有 `WifiAwareSession` 和 `PublishDiscoverySession`
- 没有 ANNOUNCE 处理(简化后)
- `publish` 单纯为了"对方能 subscribe 看到我"

**为什么 publish 还要保留**:对方需要 subscribe 才能看到我,看到我靠 publish 的 serviceSpecificInfo(clientId)。所以 publish 仍然必要,只是不做 ANNOUNCE 处理。

### 4.2 `AwareClient` — Client 端全套

**答**:我怎么主动连一个 peer?

```kotlin
class AwareClient(
    private val session: AwareSession,
    private val peerId: String,           // 对方 clientId
    private val peerKey: String,          // HTTP token
)
```

**方法**:

```
suspend fun connect(): ClientConnection    ← 完整流程
suspend fun close()                        ← 清理
```

**`connect()` 内部**:

```
session.subscribe(service, callback)
  callback.onServiceDiscovered(handle, info):
    if info.clientId == peerId:
      handleDeferred.complete(handle)
await handle
requestNetwork(client specifier, callback)
  onAvailable + onCapabilitiesChanged → 4 字段齐 → ClientConnection
return ClientConnection
```

**比之前少了什么**:没有 `sendMessage(ANNOUNCE)` 一步。流程更短。

### 4.3 `AwareServer` — Server 端全套(对称,目前不调用)

**答**:我怎么接收一个 peer 的连接?

```kotlin
class AwareServer(
    private val session: AwareSession,
    private val pairedPeerIds: Set<String>,  // 我服务哪些 clientId
    private val port: Int,
)
```

**方法**:

```
suspend fun connect(): ServerConnection
suspend fun close()
```

**`connect()` 内部**(没有 ANNOUNCE 的版本):

```
session.subscribe(service, callback)
  callback.onServiceDiscovered(handle, info):
    if info.clientId in pairedPeerIds:
      matchedHandle.complete(handle)
await matchedHandle
requestNetwork(server specifier, callback) { setPort(port) }
  onAvailable → serverNetwork
return ServerConnection(network, port)
```

**为什么需要 `pairedPeerIds`**:server 端 `subscribe.onServiceDiscovered` 看到所有同 service 的 peer,需要过滤哪些是配对的。

**为什么目前不调用**:我们 send 方向只走 client 端,反方向走 WebSocket(`/status`)。Server 端 link 是为对称 + 未来扩展用,接口清晰摆在那里,**目前 transport 里 `server = null`**。

### 4.4 `ClientConnection`

```kotlin
data class ClientConnection(
    val network: Network,
    val peerIpv6: Inet6Address,
    val peerPort: Int,
    val httpClient: OkHttpClient,
)
```

**为什么只有 ClientConnection**(暂时):Server 端不 build 时,`ServerConnection` 用不上。等真要 server 再写。

### 4.5 `AwarePeerLink` — 编排

```kotlin
class AwarePeerLink(
    val peerId: String,
    private val client: AwareClient,
    private val server: AwareServer?,        // 当前 null
    private val onClose: (peerId: String, reason: String) -> Unit,
)
```

**方法**:

```
suspend fun build(peer: DPeer): ClientConnection   ← 走 client 端
fun prewarm()                                       ← client.subscribe 不等 handle
fun touch()                                         ← 更新 lastActiveAt
fun close(reason: String)                           ← client.close + 可选 server.close
```

**`build()` 内部**:

```kotlin
suspend fun build(peer): ClientConnection {
    connection.get()?.let { return it }
    return buildLock.withLock {
        connection.get() ?: client.connect().also { connection.set(it) }
    }
}
```

3 行。**没有任何协议细节**,只是"调 client 把事办了"。

---

## 5. 时序图(无 ANNOUNCE 版)

```
P9 (Client)                            P7 (responder)
─────────────────────────────────────────────────────
attach (WifiAwareSession)
publish("plain-peer", svcInfo=clientId_P9)

subscribe("plain-peer")

[Client 流程]
onServiceDiscovered(handle_P7, svcInfo=clientId_P7)
  match: clientId_P7 == target → PeerHandle
requestNetwork(client specifier, callback)
  onAvailable → clientNetwork
  onCapabilitiesChanged → peerIpv6, peerPort
  → ClientConnection(Network, peerIpv6, peerPort, httpClient)

httpClient.newCall(POST /peer_graphql) → socket via clientNetwork.socketFactory
```

**我们的代码执行**:

```
Transport.send(P9, req)
  → linkFor(P9)
      AwareClient(session, peerId=P7, peerKey)
      AwarePeerLink(client, server=null, onClose)
  → link.build(peer)
      client.connect()
        session.subscribe(...)
        await handle
        requestNetwork(client role)
        → ClientConnection
  → httpClient.newCall(req).execute()
```

比之前少了 `encode + sendMessage(ANNOUNCE)` 一步,3 个组件改为 3 个组件(没有 ANNOUNCE.kt / onMessageReceived)。

---

## 6. 关键设计决策

### 6.1 为什么按角色拆而不是按协议层

参见 §0。简言之:用户脑里模型是"我是 client 还是 server",不是"我在 Aware 哪一层"。

### 6.2 为什么 ANNOUNCE 可以删

参见 §2。简言之:server 端不 build,ANNOUNCE 收发都没人用,删了省 ~30 行 + 一个文件 + 路由逻辑。

### 6.3 为什么不只写 AwareClient

对称性 + 未来扩展:
- AwareServer 是 AwareClient 的镜像,接口对称
- 将来反方向真要走 Aware(不走 WebSocket),加一行 `server = AwareServer(...)` 就能用
- **不写** 的话,要加 server 流程时要重新设计一遍 `requestNetwork` + `pairedPeerIds` 匹配

代码量:Client ~110 行,Server ~70 行,**可接受**。

### 6.4 AwareServer 将来怎么触发(没有 ANNOUNCE)

可选触发机制(目前都不实现):

| 方案 | 触发点 | 需要什么 | 缺点 |
|---|---|---|---|
| A. `subscribe.onServiceDiscovered` 匹配 | server 端 subscribe 看到对端 | `pairedPeerIds: Set<String>` | 需要维护 paired list |
| B. 预建 server link | 配对后立刻 build,不等触发 | 同 A | 浪费 Network 资源 |
| C. ANNOUNCE(原方案) | client 发,server 收 | ANNOUNCE 协议 | 我们刚删了 |

**最可能是 A**:`pairedPeerIds` 已经有了(从 PeerCacher),server 端 subscribe + onServiceDiscovered 命中即建,不需要额外协议。

### 6.5 重复代码在哪

| 重复点 | 处理 |
|---|---|
| `requestNetwork` + `NetworkCallback` | Client 和 Server 各一份完整实现,差异是 specifier 和 port |
| PMK 派生 | 抽成 Session 的私有函数(client/server 都可能用) |

Client 和 Server 的 `requestNetwork` 实现有 ~30 行差异,**不强行合并**(重复优于错误的抽象)。

### 6.6 为什么不需要 awaitConnected()

caller 拿 `ClientConnection` 直接用,要什么直接 `.httpClient`。不需要 polling "link 准备好了吗"。

---

## 7. 迁移步骤

> **原则**:每步独立编译 + 通过现有用例。

| Step | 文件 | 改动 | 验证 |
|---|---|---|---|
| 1 | `aware/ClientConnection.kt` | 新建 data class | 编译 |
| 2 | `aware/AwareSession.kt` | 重写:去掉 ANNOUNCE 路由,保留 attach + publish + lifecycle | session 启动行为不变 |
| 3 | `aware/AwareClient.kt` | 新建:从 `AwareDiscovery` + `AwareNetworkConnector.connectClient` 整合,**无 announceTo** | 单 peer client build 行为不变 |
| 4 | `aware/AwareServer.kt` | 新建:server 端完整实现(无 ANNOUNCE,基于 subscribe.onServiceDiscovered + pairedPeerIds 匹配) | 编译(逻辑不调用) |
| 5 | `AwarePeerLink.kt` | 重写:持 `AwareClient` + 可选 `AwareServer`,`build()` 调 `client.connect()` | build 流程不变 |
| 6 | `AwareLinkPool.kt` `WifiAwareTransport.kt` | 收窄:link map + idle sweep + prewarm 外移到 pool,Transport 只做路由 + session 协调 | send/downloadFile 行为不变 |
| 7 | 删除 | `aware/AwareDiscovery.kt` `aware/AwareNetworkConnector.kt` `LinkConnection.kt` 删除 | 编译 |
| 8 | 联调 | P9 ↔ P7 双向 + idle 60s 重建 | 验收 |

每步跑 `./gradlew :shared:compileAndroidMain`,Step 8 跑联调。

---

## 8. 一图概览(新设计)

```
WifiAwareTransport (路由 + session 协调)
└─ send(peer, req)
   └─ pool.linkFor(peer)
      └─ AwareLinkPool (link map + idle sweep)
         └─ AwarePeerLink
            ├─ AwareClient (干 client 端所有事)
            │    subscribe → 等 onServiceDiscovered(handle, clientId match)
            │    → requestNetwork(client role)
            │    → httpClient
            │    → ClientConnection
            └─ AwareServer (干 server 端所有事,当前 null)
                 subscribe → 等 onServiceDiscovered(clientId in pairedPeerIds)  ← 将来用
                 → requestNetwork(server role, setPort)
                 → serverNetwork

AwareSession (全局,所有 link 共享)
├─ attach + publish session 管理
└─ (无 onMessageReceived,无 ANNOUNCE 路由)
```

---

## 9. 验收

| # | 用例 | 通过标准 |
|---|---|---|
| 1 | 编译 | `./gradlew :shared:compileAndroidMain` BUILD SUCCESSFUL |
| 2 | 冷启动 send | 单条 P9 → P7 成功 |
| 3 | 双向连续 | P9 / P7 交替各 10 条,无 27ms 立即失败 |
| 4 | idle 重建 | 60s idle 后再发,新 connection 建立 |
| 5 | 行数 | `AwareClient.kt` ~110,`AwareServer.kt` ~70,`AwarePeerLink.kt` ≤ 80 |
| 6 | 无重复 | PMK 派生 1 处,`requestNetwork` client/server 各一份完整实现(差异显著不合并) |
| 7 | 无 polling | 全文 grep `while\s*\(.*delay` 应为空 |
| 8 | 无 ANNOUNCE | 全文 grep `ANNOUNCE` 应为空(协议已删除) |

---

## 10. 实施步骤

> 边做边更新

- [x] Plan 文档(本文)
- [ ] Step 1: `ClientConnection.kt`
- [ ] Step 2: `AwareSession.kt` 重写(无 ANNOUNCE)
- [ ] Step 3: `AwareClient.kt` 新建(无 announceTo)
- [ ] Step 4: `AwareServer.kt` 新建(基于 subscribe.onServiceDiscovered 触发,暂不调用)
- [ ] Step 5: `AwarePeerLink.kt` 重写
- [x] Step 6: `AwareLinkPool.kt` 新建 + `WifiAwareTransport.kt` 收窄 — link map / idle sweep / prewarm 已外移,send/downloadFile 改走 pool
- [ ] Step 7: 删除 `AwareDiscovery.kt` `AwareNetworkConnector.kt` `LinkConnection.kt`
- [ ] Step 8: 编译 + 联调
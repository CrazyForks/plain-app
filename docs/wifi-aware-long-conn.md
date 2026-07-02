# Wi-Fi Aware 长连接方案

> 配套 Phase 1.5 — 在 `peer-chat-cross-network.md` Phase 1 基础上,把"每次 send 重建 network"改成"建一次 long-lived socket,60s idle 主动断"。
> 解决上一轮发现的"1 个 session 同时只能 1 个 outstanding requestNetwork"在双向不可靠的问题。

---

## 0. 现状(为什么需要改)

Phase 1 当前实现 (`shared/src/androidMain/.../transport/WifiAwareTransport.kt`):

- 每次 `send()` 都走一遍: `attach → publish → subscribe → ANNOUNCE → requestNetwork(client) → requestNetwork(server) → HTTP POST → 留 callback 不释放`
- 实测发现双向 (`P7 → P9`) 经常失败:
  - 上一轮的 R1 留下的 subscribe + serverNetwork callback 还在,这次 send 又新发起 clientNetwork
  - Aware 栈看到同一 session 同时有 outstanding clientNetwork 和 serverNetwork 时,新的 `requestNetwork` 27ms 立即失败
- 单向 (`P9 → P7`) 稳定,但 `P7 → P9` 不可靠

核心痛点: **网络状态机从来没稳定过**,每次 send 都重建 + 叠加 callback。

---

## 1. 目标

```
P9 --- Aware (一次 publish/subscribe) ---> P7

        ↓
  双向长 socket(可写可读,跟 TCP socket 一样)

        ↓
  send / recv 都走这个 socket

        ↓
  60s 没流量 → 主动关 socket + cancel Network
  下次 send 时再重新建立
```

跟"电话拨号"类比:有流量时挂上,没流量挂起。

---

## 2. 设计

### 2.1 长连接生命周期(per peer)

```
                 subscribe → ANNOUNCE → requestNetwork(client)
                       ↓
                  建立 clientNetwork + serverNetwork (双向 socket)
                       ↓
              ┌──── OK 维持 long-lived ────┐
              ↓                           ↓
   每条 send 复用 socket          60s idle 触发
              ↓                           ↓
   POST /peer_graphql         cancel clientNetwork + serverNetwork
                                       ↓
                              下次 send 时重新 requestNetwork
```

### 2.2 Aware Network 一次建立,长留

| 资源 | 生命周期 | 备注 |
|---|---|---|
| `WifiAwareSession` (attach) | **session 全程**,app 启动到 stop | 跟 PeerStatusManager 同步 |
| `PublishDiscoverySession` | session 全程 | 常驻 publisher |
| `SubscribeDiscoverySession` (per peer) | session 全程 | per-peer subscribe |
| `Network` (client/server specifier) | **per peer 连接期间** | idle 60s → 主动 cancel + unregister |
| `OkHttpClient` | per peer 连接期间 | 复用 client + connection pool |
| TCP socket (OkHttp 连接池内) | 同上 | HTTP/1.1 keep-alive 自动复用 |

### 2.3 谁先成功都行

当前 Aware 协议本身就是双向 publish/subscribe (每端都 publish + subscribe 同一个 service),所以 `P9` 主动发起 send 和 `P7` 主动发起 send 走同一条对称路径:

- A 想给 B 发 → A 走 `requestClientNetwork` 拿到 clientNetwork;B 自动收 ANNOUNCE 后走 `requestServerNetwork` 拿到 serverNetwork
- 两端各自拿到一个 Network,**Network 内部 socket 双向** (Aware 层就是 P2P,两端 socket 都能读写)
- 后续 A 和 B 都可以通过自己侧的 Network.socketFactory 建 socket,双向发消息

### 2.4 Socket 复用层

不引入新协议,继续用 HTTP/1.1 over Aware Network:

- **Client 端**: per-peer 缓存 `OkHttpClient`(绑 Network.socketFactory),OkHttp 默认 keep-alive,socket 复用
- **Server 端**: Netty HttpServer 已经 keep-alive,client 复用 socket 连续发请求
- **Server → Client 主动 push**: 复用现有 `/status` WebSocket,扩展为也能承载 chat push
  - server 端有 chat 要推给 Aware peer 时,通过对应的 WS session 调 `send(json)`
  - 双向对称: Aware 上的两端都既能在自己 Network 上发 HTTP(POST GraphQL),也能在自己侧的 WS 上收 server push

### 2.5 Idle 60s 主动断

- per-peer `lastActiveAt: Long`,send() 和 receive() 都更新
- 后台 sweep coroutine 每 5s 检查一次,> 60s 没流量 → `cancelNetwork` + 关 OkHttp 连接池里的这个 peer 的连接
- 下一条 send 走完整流程重建

---

## 3. 数据结构

```kotlin
// WifiAwareTransport 内部

private data class AwareConn(
    val peerId: String,
    val clientNetwork: Network,      // subscriber 视角,clientNetwork.socketFactory 双向可用
    val serverNetwork: Network?,     // publisher 视角(如果本端是 publisher)
    val clientSocket: Socket?,       // 可选,raw socket 给 server push 监听用
    val httpClient: OkHttpClient,    // 复用,socket connection pool 自动 keep-alive
    var lastActiveAt: Long,          // 每次 send/recv 更新
    var inFlight: Boolean = false,   // 防止并发 send 重复建 Network
)

// per peer
private val conns = ConcurrentHashMap<String, AwareConn>()
private val connReady = ConcurrentHashMap<String, CompletableDeferred<AwareConn>>()
private val connLock = ConcurrentHashMap<String, Mutex>()  // 防止并发 build
```

---

## 4. 流程

### 4.1 启动

```kotlin
fun start() {
    if (session != null) return
    manager?.attach(attachCallback, null)
    // onAttached → publishOwnService() — 不变
    // PeerStatusManager.start() 已经调过,这里幂等
    startIdleSweep()
}
```

### 4.2 第一次 send (建立 conn)

```kotlin
suspend fun send(peer, request, key): GraphQLResponse {
    val conn = getOrBuildConn(peer)        // 长连接,可能复用 or 重建
    conn.lastActiveAt = System.currentTimeMillis()
    val response = conn.httpClient.newCall(buildHttpRequest(peer, request)).execute()
    conn.lastActiveAt = System.currentTimeMillis()
    return parse(response)
}
```

```kotlin
suspend fun getOrBuildConn(peer: DPeer): AwareConn {
    conns[peer.id]?.let { return it }      // 命中复用
    val mutex = connLock.computeIfAbsent(peer.id) { Mutex() }
    return mutex.withLock {
        conns[peer.id]?.let { return@withLock it }
        val ready = connReady.computeIfAbsent(peer.id) { CompletableDeferred() }
        if (!ready.isCompleted) buildConn(peer, ready)
        ready.await()
    }
}
```

```kotlin
suspend fun buildConn(peer: DPeer, ready: CompletableDeferred<AwareConn>) {
    val s = awaitSession() ?: run { ready.completeExceptionally(...); return }
    subscribeToPeer(peer)
    sendAnnounce(peer)
    val (clientNet, serverNet) = awaitBothNetworks(peer)   // 双方都 ready
    val http = buildHttpClient(clientNet, peer.key)
    val conn = AwareConn(peer.id, clientNet, serverNet, ..., http, now())
    conns[peer.id] = conn
    ready.complete(conn)
}
```

### 4.3 idle sweep

```kotlin
private fun startIdleSweep() {
    scope.launch {
        while (isActive) {
            delay(5_000)
            val now = System.currentTimeMillis()
            conns.values.toList().forEach { conn ->
                if (!conn.inFlight && now - conn.lastActiveAt > 60_000L) {
                    closeConn(conn.peerId)   // cancelNetwork + close socket + 清 conns
                }
            }
        }
    }
}
```

### 4.4 server push (P7 → P9)

让 Netty HttpServer 的 `/status` WebSocket 同时承载 chat push:

```kotlin
// WebSocket.kt addWebSocket("/status") 修改:
// 在 authenticated = true 之后:
//   - 把这个 DefaultWebSocketSession 存进一个 per-cid 的 map
//   - 然后保持 WS open (心跳 + 服务端 push)
//
// 加一个对外 API:
//   ServerPushNotifier.notify(peerId, jsonEvent) {
//       val session = wsSessionsByCid[peerId] ?: return
//       session.send(jsonEvent)
//   }
```

Aware 路径上,PeerStatusManager 已经维持了 WS,所以 chat push 直接通过这个 WS 走,**对 Aware client 透明**。

---

## 5. 关键坑

| 坑 | 处理 |
|---|---|
| 1 session 同时 1 个 outstanding requestNetwork (上一轮发现) | idle 60s 主动 cancel → 下一条 send 再 request,**同一时刻只有 1 个** |
| Network 拿到后 socketFactory 建 socket 不能 IPv6 zone ID | OkHttp URL 用 hostname (DNS hook),DNS 解析成 Inet6Address 时**不带 zoneId**(测试过 OkHttp dns hook) |
| OkHttp 默认 keep-alive 5 min,太长 | 设 `keepAliveDuration = 70s`(略大于 60s idle 触发),让 idle sweep 主导断开 |
| Aware session 死掉 (attachFailed / 进程回收) | 在 callback 里 `needsReattach = true`,下一条 send 重 attach |
| subscribe 拿到的 peerHandle 失效 (对方离开范围) | clientNetwork `onLost` → 触发 closeConn + 下一条 send 重建 |
| 服务端 WS 突然断 (对方离开范围) | peerStatusManager 已有 reconnect 逻辑,复用 |

---

## 6. 文件改动清单

| 文件 | 改动 |
|---|---|
| `shared/.../transport/WifiAwareTransport.kt` | 主入口:session 生命周期 + send 路由 + idle sweep (~277 行) |
| `shared/.../transport/AwarePeerLink.kt` | per-peer 状态机:Network + httpClient + 锁 + close (~303 行) |
| `shared/.../transport/AwareHttpClientFactory.kt` | OkHttp 构建 + DNS hook (~33 行) |
| `app/.../web/WebSocket.kt` (`/status`) | ~~加 WS session by-cid map + 服务端 push API~~ (不需要) |
| `shared/.../peer/PeerStatusManager.kt` | 不改 |

---

## 7. 验收

| 用例 | 通过标准 |
|---|---|
| P9 → P7 单条 | send 成功,接收方写入 chat |
| P7 → P9 单条 | send 成功,接收方写入 chat |
| 双向连续发 (P9, P7 交替各 10 条) | 全部送达,无 27ms 立即失败 |
| 60s idle 后再发 | 新 conn 建立,send 成功 |
| Aware 失效回落到 LanTransport | router fallback 正常 |

---

## 8. 实施步骤

> 边做边更新 plan

- [x] Plan 文档 (本文件)
- [x] WifiAwareTransport 重构:per-peer AwareConn + 长留 Network + 复用 OkHttpClient
- [x] idle sweep:60s 主动 cancel + close
- [ ] ~~WebSocket `/status` 加服务端 push API~~ — 不需要,双向靠两端都调 send() 自然成立
- [x] 编译通过 (`./gradlew :app:assembleGithubDebug` BUILD SUCCESSFUL)
- [ ] 集成测试:P9 ↔ P7 双向 + idle 60s 重建
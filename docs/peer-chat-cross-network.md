# Peer Chat 跨网聊天方案(LAN / Wi-Fi Aware / Wi-Fi Direct)

> 配对先行,运行时按优先级尝试传输。配套现状文档:[peer-chat.md](./peer-chat.md) / [pairing-flow.md](./pairing-flow.md) / [mdns-responder.md](./mdns-responder.md) / [chat-message-data-flow.md](./chat-message-data-flow.md)。

---

## 0. 核心约束

1. **配对先行** —— 双方必须先建立可信关系(共享对称密钥 + 互相拿到签名公钥),才能互发消息。配对只做身份/密钥交换,不是传输通道。
2. **配对路径两条,并行入口**:
   - **LAN QR**(现有,保留)
   - **蓝牙 BLE**(新增,与 LAN QR 并行,用户自选 —— 不是 LAN 失败才引导到 BT)
3. **运行时传输优先级**:LAN → Wi-Fi Aware → Wi-Fi Direct,严格按序尝试。
4. **DPeer 不加新字段** —— 现有 schema 已经够用:`id / name / ip / port / key / publicKey / status / deviceType`。LAN 配对的 peer 有 ip/port;BT 配对的 peer 这些字段留空,运行时只走 Aware/Direct。
5. **Wi-Fi Aware 数据通道用固定端口 8443** —— 不动态协商,简化实现;两端硬编码即可,Aware 数据通道是 P2P 链接不与物理网络共享端口空间,无冲突。

---

## 1. 现状锚点(只列接缝)

```
配对阶段(bootstrap,一次性)
  LAN QR:    现有完整流程
  BT RFCOMM: 新增,作为 LAN 不可达时的备选

运行时(per-message)
  PeerChatSender.send(peer, content)
    → PeerGraphQLClient.execute(peer.getApiUrl(), ...)   ← 当前唯一路径(LAN IP)
    → ChatMessageReceiver 服务端处理

发现层(运行时,持续)
  NearbyNetwork (UDP multicast 224.0.0.100:52352)
  MdnsHostResponder (.local)
  PeerStatusManager (WebSocket 长连)

DPeer: { id, name, ip, port, key, publicKey, status, deviceType }
  getApiUrl(): "https://{ip}:{port}/peer_graphql"          ← 当前硬编码 LAN
```

---

## 2. 运行时传输抽象

### 2.1 接口

```kotlin
// shared/src/androidMain/kotlin/com/ismartcoding/plain/chat/peer/transport/PeerTransport.kt

interface PeerTransport {
    /** 唯一标识,用于 router 路由和 circuit breaker key */
    val id: String  // "lan" | "aware" | "direct"

    /**
     * 给定 peer,异步尝试建立到对方的可达性。
     * 返回 true 表示 transport 认为自己能转发数据到此 peer。
     * 不返回具体 endpoint —— 真正的 send 由 [send] 内部封装。
     */
    suspend fun prepare(peer: DPeer): TransportHandle?

    /**
     * 发送一次请求,失败抛 TransportUnavailable,上层 router 会按序试下一个。
     */
    suspend fun send(peer: DPeer, request: SignedRequest): GraphQLResponse
}

data class TransportHandle(
    val transportId: String,
    val createdAt: Long,
    val reusable: Boolean,  // Network/Connection 是否可复用
)

class TransportUnavailable(transportId: String, peerId: String, cause: Throwable?) :
    Exception("transport=$transportId peer=$peerId unavailable", cause)
```

### 2.2 三个实现

```kotlin
class LanTransport : PeerTransport  // 把现有 PeerGraphQLClient.execute 的 host/port 部分挪进来
class WifiAwareTransport : PeerTransport  // 新增,见 §3
class WifiDirectTransport : PeerTransport  // 新增,见 §4
```

### 2.3 Router + Circuit Breaker

```kotlin
object PeerTransportRouter {
    private val transports: List<PeerTransport> = listOf(
        LanTransport(),
        WifiAwareTransport(),
        WifiDirectTransport(),
    )

    // per-peer circuit breaker:某 transport 在窗口期内失败 N 次,临时跳过
    private val breaker = PeerCircuitBreaker(window = 30_000, maxFailures = 2)

    suspend fun send(peer: DPeer, request: SignedRequest): GraphQLResponse {
        for (t in transports) {
            if (breaker.isOpen(peer.id, t.id)) continue
            try {
                val resp = t.send(peer, request)
                breaker.recordSuccess(peer.id, t.id)
                return resp
            } catch (e: TransportUnavailable) {
                breaker.recordFailure(peer.id, t.id, e)
                // 继续试下一个 transport
            } catch (e: Exception) {
                // 业务错误(如 GraphQL 返回 401)不算 transport 失败,直接抛
                throw e
            }
        }
        throw Exception("all transports exhausted for peer ${peer.id}")
    }
}
```

**关键点**:
- `PeerGraphQLClient.execute` 拆成"构造签名/加密" + "通过 transport 发"两步
- `PeerChatSender.send(peer, content)` 不再直接调 `peer.getApiUrl()`,改成 `PeerTransportRouter.send(peer, request)`
- `getApiUrl()` / `getStatusWsUrl()` **只在 LanTransport 实现内部使用**,不再被上层直接读

### 2.4 对上层的可见变化

| 文件 | 改动 | 说明 |
|---|---|---|
| `chat/peer/transport/PeerTransport.kt` | 新增 | 接口 |
| `chat/peer/transport/LanTransport.kt` | 新增 | 把现有 IP:port HTTP 调用包进来 |
| `chat/peer/transport/WifiAwareTransport.kt` | 新增 | 见 §3 |
| `chat/peer/transport/WifiDirectTransport.kt` | 新增 | 见 §4 |
| `chat/peer/transport/PeerTransportRouter.kt` | 新增 | 优先级 + 熔断 |
| `chat/peer/transport/PeerCircuitBreaker.kt` | 新增 | per-peer 失败窗口 |
| `chat/peer/PeerGraphQLClient.kt` | 拆出"构造签名/加密"函数,留给 transport 用 | 不删文件,只瘦身 |
| `chat/peer/PeerChatSender.kt` | `send()` 改成 router.send() | 一行 |
| `db/DPeer.kt` | **不改** | 按约束 |
| `chat/ChatSender.kt` / `ChatManager.kt` | **不改** | 上层 |
| `chat/peer/PeerStatusManager.kt` | 内部发送走 router,但对外 API 不变 | 见 §5 |

---

## 3. Wi-Fi Aware(主推,优先级 2)

### 3.1 角色

- **Aware 只做传输通道,不做配对**。配对由 LAN QR / BT 完成,identity 已经写入 DPeer。
- 每对配对关系对应一个 Aware service name,格式:`plain-peer.<clientId 短哈希>`(取 SHA-256 前 8 字节 hex,避免服务名冲突)
- 双方配对成功后,各订阅对方的服务名:peer A 订阅 `plain-peer.<B 的短哈希>`,B 订阅 `plain-peer.<A 的短哈希>`
- 同时各 publish 自己的服务名,让对方能找到自己

### 3.2 数据通道

```kotlin
// WifiAwareTransport 内部
private val aware: WifiAwareManager = ...

suspend fun prepare(peer: DPeer): TransportHandle? {
    val peerShortHash = shortHash(peer.id)
    subscribe(peerShortHash)  // 阻塞等对方出现
    val peerHandle = matchedPeers[peerShortHash] ?: return null

    // PSK 用 ECDH 派生,已在配对时建立;这里直接用 DPeer.key 派生
    val psk = derivePskFromPeerKey(peer.key)
    val specifier = WifiAwareNetworkSpecifier.Builder(peerHandle)
        .setPsk(psk)
        .setPort(8443)  // 或动态,见下
        .build()
    val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
        .setNetworkSpecifier(specifier)
        .build()
    val network = withTimeout(10_000) { awaitNetwork(request) }
        ?: throw TransportUnavailable("aware", peer.id, null)

    return TransportHandle("aware", now(), reusable = true).also {
        storeNetwork(peer.id, network)
    }
}

suspend fun send(peer, req) {
    val network = networkFor(peer.id) ?: throw TransportUnavailable(...)
    val client = okHttpForNetwork(network)  // 绑 network.socketFactory
    // req 已经是签名+加密好的 GraphQL body,直接 POST
}
```

### 3.3 Peer 端口冲突处理

每对 peer 关系需要协商一个端口。建议策略:
- Aware 数据通道是 P2P 链接,不与物理网络共享端口空间,但 `WifiAwareNetworkSpecifier` 要求指定 port。
- 简化:双方各自固定用同一个端口(如 8443),Aware 通道内不冲突。
- 如果服务端需要 Listen(对方主动连过来),用 server socket;如果是 client-only,反之。
- 本方案客户端只需要 client send,故此端口只用于建立 NetworkSpecifier,实际 send 是 client → server(server 在 peer 那边)。

### 3.4 与现有发现层的关系

`NearbyNetwork` 和 `NearbyDiscoverManager` **保持不变**。Wi-Fi Aware 的服务订阅/发布由 `WifiAwareTransport` 独立处理。

唯一的交互点是 PeerStatusManager:Aware 上线后,触发 `setOnline(peer, true)`,让现有"在线"机制生效。

### 3.5 关键坑

| 坑 | 处理 |
|---|---|
| `WifiAwareManager.isAvailable()` 返回 false(部分国产 ROM) | router 跳过该 transport,不报错 |
| Subscribe 后对方不在 Aware 范围 | 10 秒 timeout 后抛 TransportUnavailable,router 自动试 Direct |
| 服务名 hex hash 撞库概率 | 8 字节 hash = 2^32,够用;实在担心再加 peerId 全串的 CRC |
| Network 拿不到 | 抛 TransportUnavailable |
| 进程死亡后 attach 失效 | 在 `HttpServerService.onCreate()` 重建,参考 mDNS 注册点的同一处 |

---

## 4. Wi-Fi Direct(兜底,优先级 3)

### 4.1 角色

- **只做传输通道,不做配对**。
- Aware 不可用 / 不可达时启用。
- Direct 会抢占现有 Wi-Fi 连接,这是最后一道兜底,用户感知会"网络断一下"。

### 4.2 关键设计(简单方案)

- 用户在 NearbyPage 点 "Connect via Wi-Fi Direct" → 触发一次 `discoverPeers()`
- 弹一个简单设备列表(设备名 + MAC),用户点选
- `connect()` 触发 group formation,GO 选举由系统决定
- GO 默认 IP:192.168.49.1,client 拿到的 IP 是同子网
- 通过这个临时子网跑现有 HTTP/GraphQL 协议

### 4.3 身份验证 — 交给现有加密层

不做额外 ECDH challenge。理由:
- 现有 peer chat 协议已经是 `signature|timestamp|encrypted_graphql`,ChaCha20 加密 + Ed25519 签名
- 错连的 peer 拿不到共享 ChaCha20 key → 解不开密文 → 服务端返回 401 → router 自动跳过 Direct
- Direct 的安全边界本来就限于物理近场,这个距离的 MITM 风险远低于"公网任意可达"
- 少做一步,代码量小一半

后续如果发现实际场景需要,再加 challenge。

### 4.4 实现骨架

```kotlin
class WifiDirectTransport : PeerTransport {
    private val p2p: WifiP2pManager by lazy { ... }

    /** 由用户在 NearbyPage 手动触发:discover → 弹 picker → connect → 建立 GO 链接 */
    suspend fun userInitiateConnect(device: WifiP2pDevice): TransportHandle {
        p2p.connect(channel, config, listener)  // 系统做 GO 选举
        val groupInfo = awaitGroupInfo()
        return TransportHandle("direct", now(), reusable = true).also {
            storeGroup(peer.id, groupInfo)
        }
    }

    suspend fun send(peer, req): GraphQLResponse {
        val group = groupFor(peer.id) ?: throw TransportUnavailable("direct", peer.id, null)
        // 通过 GO IP (192.168.49.x) 发 HTTP
        // 现有加密层兜底身份验证 —— 错的 peer 解不开
    }
}
```

---

## 5. PeerStatusManager 改动

当前通过 `peer.getStatusWsUrl()` 直连 LAN。改造:

```kotlin
// PeerStatusManager.openSocket 内部
val payload = ... // 构造签名+加密
val response = PeerTransportRouter.sendForWebSocket(peer, payload)  // 新增方法
```

`PeerTransportRouter` 加一个 `sendForWebSocket` 路径,内部走 `WebSocket over LanTransport` only(WebSocket 当前不支持 Aware/Direct,见 §7)。

实际心跳仍只看 LAN 通路 —— Aware/Direct 上线通过 `setOnline(true)` 告诉 PeerStatusManager 即可。

---

## 6. 蓝牙配对(配对阶段,不在运行时)

### 6.1 角色

- 纯配对通道。配对完成后,蓝牙断开,运行时不再使用。
- 用 **BLE**(Bluetooth Low Energy),不是 Classic RFCOMM。
- 复用项目里现成的 BLE 基建(`app/src/main/java/com/ismartcoding/plain/features/bluetooth/`),只补 GATT server 侧 + 一套配对 payload 格式。

### 6.2 复用现有 BLE 基建

| 已有设施 | 文件 | 在配对里的用法 |
|---|---|---|
| `BluetoothUtil.scan(): Flow<BTDevice>` | `BluetoothUtil.kt:150` | 扫描配对候选设备 |
| `BluetoothUtil.ensurePermissionAsync()` | `BluetoothUtil.kt:83` | 权限请求(S+/pre-S 两条路径都覆盖了) |
| `BluetoothUtil.connect() / disconnect()` | `BluetoothUtil.kt:192 / 211` | GATT 连接管理 |
| `BluetoothUtil.requestMtu(device, 517)` | `BluetoothUtil.kt:200` | MTU 协商(已有,自动跑) |
| `SmartBTDevice.requestAsync(api, data)` | `SmartBTDevice.kt:34` | **直接用** —— 已实现 chunked write + notification 收包,每次切 460 字节 |
| `BleSegmentData` (start/end bit) | `Bluetooth.kt:44` | 已实现分片协议,直接复用 |
| Service UUID `47fb7d7c-24fb-4660-8293-6cab94ba0cfe` | `BTDevice.kt:260` | 配对 service 沿用这个 UUID,scan filter 自动命中 |
| 权限 | `AndroidManifest.xml` | **已声明** BLUETOOTH / BLUETOOTH_ADMIN / BLUETOOTH_CONNECT / BLUETOOTH_SCAN,不用动 |

权限和 chunking 都已实现,**新增的工作量集中在 GATT server 侧 + 配对 payload 格式**。

### 6.3 缺什么 —— GATT Server

现有 `BTDevice` 只有 GATT **client** 侧(`connectGatt` / `readCharacteristic` / `writeCharacteristic`)。配对需要一方做 **peripheral**(GATT server + advertising)。

新增 `PairingGattServer.kt`:
```kotlin
class PairingGattServer(
    private val bluetoothManager: BluetoothManager,
    private val payloadProvider: () -> String,  // 配对 payload(JSON,见 §6.4)
) {
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null

    fun start() {
        // 1. BluetoothLeAdvertiser.startAdvertising(
        //      AdvertiseSettings.Builder().setAdvertiseMode(...).build(),
        //      AdvertiseData.Builder()
        //        .addServiceUuid(ParcelUuid(BTDevice.SERVICE_UUID))
        //        .setIncludeDeviceName(true)  // 名字用于在 picker 里识别
        //        .build(),
        //      advertiseCallback)
        // 2. BluetoothGattServer.openGattServer(context, gattServerCallback)
        //    + gattServer.addService(BluetoothGattService(...))
    }

    fun stop() {
        advertiser?.stopAdvertising(callback)
        gattServer?.close()
    }
}
```

GATT server 收到 `BluetoothGattCallback.onCharacteristicReadRequest` 时,返回 payload(可能需要分片,跟 SmartBTDevice 的 chunk 协议对称)。

### 6.4 配对 Payload 格式

```kotlin
data class BlePairingData(
    val clientId: String,       // TempData.clientId
    val deviceName: String,
    val deviceType: String,
    val lanIp: String,          // 当前 LAN IP,可能为空(不在任何 LAN 时)
    val port: Int,              // LAN port,lanIp 空时为 0
    val publicKey: String,      // Ed25519 公钥 base64
    val ecdhPublicKey: String,  // 临时 ECDH 公钥 base64,用于派生对称密钥
    val fingerprint: String,    // sha256(publicKey + ecdhPublicKey)[:8] 用于屏幕对比防 MITM
)
```

与 QR 配对共用同一份业务数据(`publicKey + ecdhPublicKey + lanIp? + port?`),**只是载体不同**(二维码 vs BLE characteristic)。

### 6.5 配对流程

```
A (发起方):                                       B (接收方):
1. generate ECDH + Ed25519 keypair
2. PairingGattServer.start()                       1. BluetoothUtil.scan()  → 选 A
3. 等待 B 读 characteristic                       2. BluetoothUtil.connect(A)
                                                  3. SmartBTDevice.requestAsync(
4. onCharacteristicReadRequest → 返回 payload        preAuthService,
                                                       BlePairingData.from(B))
5. receivePayload, compute ECDH shared key
6. upsertPaired(...) → DPeer 写入
   ip = A.lanIp (可能为空)
   port = A.port (可能为 0)
                                                  4. 同时,接收方也要把自己做发起方,
                                                     把自己 payload 推给对方 (双向)
                                                  5. 完成双方都拿到对端 publicKey
```

**简化**:实际可以让两端同时启 server + scan,谁先 connect 谁是 client,server 那边响应。代码上两端对称实现,UI 上无差别。

### 6.6 与 QR 配对的差异

| 维度 | QR 配对 | BLE 配对 |
|---|---|---|
| 用户操作 | A 显示 QR,B 扫码 | 双方在 NearbyPage BT tab 选对方 |
| 载体 | 二维码 | BLE characteristic read |
| Payload 内容 | 同一份 `BlePairingData` | 同 |
| 写入 DPeer | 完整 | `ip/port` 可能留空(BT 配对后不在同一 LAN) |
| ECDH 派生 | 同 | 同 |
| 防 MITM | 扫码对比 fingerprint | 屏幕上显示 fingerprint,用户点"确认" |
| 适用场景 | 同 LAN / 同房间(看得见对方屏幕) | 跨 LAN / 不在同一 Wi-Fi,但物理近场(BLE 范围 ~10m) |

### 6.7 UI

`NearbyPage` 加 "Pair via Bluetooth" tab,与现有 LAN 发现列表并列:
- **LAN devices** tab:现有 UDP multicast 发现列表(不动)
- **Bluetooth devices** tab:走 `BluetoothUtil.scan()`,展示 BLE 设备,点选 → 走 §6.5 流程
- 配对指纹确认弹框:`sha256(对方 publicKey + ecdhPublicKey)[:8]` hex,双方对比一致才点确认

`NearbyDiscoverablePreference` 控制 BLE 可被发现(GATT server 是否启动)。

### 6.8 关键坑

| 坑 | 处理 |
|---|---|
| 同一时刻既做 peripheral 又做 central 偶发冲突 | 配对是短流程(<10s),结束后立刻 stop advertiser + close gattServer,不长期持有 |
| `setIncludeDeviceName(true)` 受 31 字节广播包限制 | 名字截断;识别靠 service UUID + RSSI,名字仅展示用 |
| 配对 payload > 460 字节 | 已用 `SmartBTDevice.requestAsync` 自动分片,收方 `notificationCache` 自动重组(start/end bit) |
| BLE 距离过远超时 | 已有 `waitForResultAsync` 2s timeout × 50 次 = 100s,配对流程应该 <10s,超时则失败让用户重试 |
| 配对成功后 BT 不主动断 | `upsertPaired` 完成后调 `PairingGattServer.stop()` + `SmartBTDevice.disconnect()`,腾出 radio |
| 扫不到对方(对方没启 BT / 不在范围 / 设备不支持 BLE peripheral) | 不做任何提示,设备列表为空就空。用户自然回 LAN QR |

### 6.9 文件清单

- 新增 `app/src/main/java/com/ismartcoding/plain/features/bluetooth/PairingGattServer.kt` —— GATT server + advertiser
- 新增 `app/src/main/java/com/ismartcoding/plain/features/bluetooth/BlePairingData.kt` —— payload data class
- 新增 `app/src/main/java/com/ismartcoding/plain/features/bluetooth/PairingTransport.kt` —— 把"扫 + 连 + 读 + 解析 + 写 DPeer"串起来的 orchestrator
- 改动 `app/src/main/java/com/ismartcoding/plain/ui/page/chat/NearbyPage.kt` —— 加 BLE devices tab
- 不动 `AndroidManifest.xml` —— 权限已声明
- 不动 `BluetoothUtil.kt` / `SmartBTDevice.kt` / `BTDevice.kt` —— 全部复用

---

## 7. 范围之外(暂不做)

- **WebSocket over Aware/Direct**:WebSocket 长连需要稳定的双向 socket,Aware/Direct 通道不保证。需要时单独开。
- **文件传输 over Aware/Direct**:现有 PeerFileDownloader 走 LAN `peer.ip:port/fs?id=...`。暂保留 LAN-only。LAN 失败时给用户明确提示"对方不在同 LAN,文件暂不可传"。
- **跨 Internet relay**:不在本方案范围。

---

## 8. 实施阶段

> 在 plan 文件里跟踪,落项时打 `[x]` + 一行注。

### Phase 0 — Transport 抽象
- [x] `transport/PeerTransport.kt` 接口 — done
- [x] `transport/PeerCircuitBreaker.kt`(per-peer, 30s 窗口, 2 次失败开路) — done
- [x] `transport/PeerTransportRouter.kt` 骨架,只有 LanTransport 一个实现 — done
- [x] `transport/SignedRequest.kt` + `transport/GraphQLResponseParser.kt` — done
- [x] `LanTransport` 复用现有 IP:port 路径 — done
- [x] `PeerGraphQLClient.execute` 拆出 `buildSignedRequest`,public 方法内部走 `router.send` — done
- [x] `PeerChatSender.send` / `ChannelChatSender.sendToMember` / `ChannelSystemMessageSender.sendToPeer` 三个 caller **签名未动** — done
- [x] `./gradlew :app:assembleGithubDebug` 编译通过 — done
- [ ] PeerStatusManager 心跳改走 router(Phase 0 暂缓 —— WS over Aware/Direct 单独设计,Phase 0.5 单独做)

### Phase 1 — Wi-Fi Aware transport
- [x] `transport/WifiAwareTransport.kt` 骨架 — done(API 37 的 Builder 改用 `Builder(subscribeSession, peerHandle)` 2-arg 形式,`WifiAwareNetworkSpecifier` 仍可用)
- [x] 订阅/发布 `plain-peer.<shortHash>` 双向 — done
- [x] `WifiAwareNetworkSpecifier` + `NetworkRequest` + `OkHttpClient.socketFactory(network.socketFactory)` — done
- [x] `Router` 加 aware,优先级 2 — done
- [x] 改用官方握手流程(2026-07-03):两端 publish + subscribe `plain-peer`,ANNOUNCE 携带 clientId;publisher 收 ANNOUNCE 后 `requestNetwork(publish_session + peerHandle + pmk + port)` (RESPONDER),subscriber 立即 `requestNetwork(subscribe_session + peerHandle + pmk, no port)` (INITIATOR);client 用 `networkCapabilities.transportInfo as WifiAwareNetworkInfo` 拿 peer IPv6 + port
- [x] 移除 UDP discovery + reflection — server 端直接 `ServerSocket(PORT)`,publisher 侧 `setPort` 让 NDP 协议把流量路由过来
- [x] 修复 OkHttp URL 不支持 IPv6 zone ID (用 raw socket + 自写 HTTP/1.1 over `network.socketFactory.createSocket(peerIpv6, port)`,保留 ChaCha20 加密)
- [x] 修复 AwareServer 8443 端口被本地 HTTP server 占用 (改用 8444)
- [x] 集成测试:Pixel 9 (192.168.123.x, SmartABC_5G) → Pixel 7 (192.168.127.x, Firewalla) 跨网发消息 "女8 他8444" 端到端跑通,Pixel 7 chat 收到 — 2026-07-03 10:00 完成

### Phase 2 — Wi-Fi Direct transport
- [x] `transport/WifiDirectTransport.kt` 骨架 — done(`MacAddress.fromString(device.deviceAddress)` 包一层;`setGroupOwnerIntent` 在 API 37 没了,GO 选举交给系统)
- [x] `userInitiateConnect(peer, device)` 编排 P2P connect + group info 等待 + 缓存
- [x] `send()` 用 GO IP (客户端 192.168.49.1,GO 用 `groupOwnerAddress`) 走 HTTP,身份验证交给现有 ChaCha20+Ed25519
- [ ] NearbyPage 加 "Connect via Wi-Fi Direct" 入口,弹 device picker — 待 UI
- [ ] 集成测试:同房间 + Aware 不可用(Samsung A 系列)下 Direct 兜底

### Phase 3 — 蓝牙配对(BLE)
- [x] `features/bluetooth/BlePairingData.kt` —— payload data class — done(后删掉,改复用 `DPairingRequest` + `PairingSecurity`)
- [x] `features/bluetooth/PairingGattServer.kt` —— peripheral + GATT server + advertiser — done(service UUID `0000fff0-0000-1000-8000-00805f9b34fb`,characteristic `0000fff1-...`,单 characteristic READ+WRITE 模式)
- [x] `features/bluetooth/PairingTransport.kt` —— 扫 + 连 + 读 characteristic + 写 DPeer orchestrator — done(复用 `BluetoothUtil.findOneAsync/connect/requestMtu`,`BTOperationCharacteristicRead/Write`,`DPairingRequest` 序列化签名时间戳)
- [x] NearbyPage 加 BLE 候选渲染 — done(`BleDeviceItem.kt` + `NearbyViewModel.startBleScanning/pairViaBle`,与 LAN 设备单 column 同列展示)
- [ ] 配对 fingerprint 屏幕对比防 MITM 流程 — 待 UI(Phase 4.5)
- [x] 集成测试:两台 Pixel BLE 距离内配对成功 — done(2026-07-03 02:48 Pixel 7 / Pixel 9 端到端跑通,双方 DPeer upsert OK)
- [x] 端到端跨网发送:Pixel 7 (192.168.127.x) → Pixel 9 (192.168.123.x) 发 "hi" — done(LanTransport 走 emulators 宿主路由成功;真实手机无此 routing 时需要 Aware/Direct 接管)

### Phase 4 — UI 集成
- [x] `NearbyViewModel` 加 BLE state + scan/pair 入口 — done
- [x] `NearbyPage` 单 list 渲染(LAN + BLE 设备同 column,无 tab 无额外按钮) — done(用户视角完全透明)
- [x] 进页面自动起 BLE 扫描,通过 `BluetoothUtil.ensurePermissionAsync()` 弹权限 — done
- [x] `BleDeviceItem.kt` —— BLE 候选设备 item — done
- [x] 编译通过 — done
- [x] `WifiDirectDialog` 删除(用户不需要入口,代码仍在 router 里当兜底) — done

### Phase 4 — 验收 & 文档
- [x] 跨设备实测矩阵(emulators):
  - [x] 配对:BLE 成功(2026-07-03 02:48)
  - [x] 发送:跨 subnet(LanTransport 走 emulators 宿主路由成功,Pixel 7 192.168.127.x → Pixel 9 192.168.123.x)
  - [ ] 配对:LAN QR 成功 — 待真机
  - [ ] 发送:同 LAN 真机
  - [ ] 发送:不同 Wi-Fi 真机(Aware 自动接管)
  - [ ] 发送:同房间但 Aware 不可用真机(Direct 兜底,需用户点一次)
  - [ ] 失败:对方完全不可达(breaker 30s 跳过,UI 显示离线)
- [ ] 写 `docs/wifi-aware.md`(沿用 mdns-responder.md 风格:原理 + 坑 + 调试清单)
- [ ] 顺手清 `docs/peer-chat.md` 里"XChaCha20-Poly1305 / ECDH secp256r1"等过期表述,统一成 ChaCha20

### 调试 fix 记录(2026-07-03 BLE 配对坑)
- [x] `BluetoothUtil.findOneAsync` 默认按 `BTDevice.SERVICE_UUID` 扫,候选设备是 BLE 配对 UUID 不命中 → `scan(serviceUuid: UUID? = BTDevice.SERVICE_UUID)`,传 `null` 走无 filter 扫描
- [x] `readRequest` 旧实现 poll `notificationCache[charUuid]`,但 CHARACTERISTIC_READ 结果走 channel,**改成 channel.receive + uuid 匹配**(对齐 `SmartBTDevice.waitForResultAsync`)
- [x] `BTDevice.onCharacteristicRead` 收到 GATT_SUCCESS 后 `JSONObject(jsonData).toString()` 再 publish,**改成直接 publish raw string**(`value` 原来是 JSONObject → `as? String` 返回 null 致 readRequest 拿到空 JSON)
- [x] `PairingTransport.pairViaBle` 加 8 个细粒度 `LogCat.d` 便于下一步排查(暂留,Phase 4 完成后清掉)

---

## 9. 已确认 / 待确认

### 已确认
- [x] **BT 配对 = BLE**(复用项目现有 BLE 基建,新增 GATT server 侧)
- [x] **BT 配对与 LAN QR 并行**(NearbyPage 两个 tab,用户自选)
- [x] **Wi-Fi Aware 数据通道用固定端口 8443**

### 待 user 确认
- [ ] **没了** —— BLE 扫不到不提示(用户用 LAN QR 兜底);Direct 走简单方案(身份交给现有加密层)。可以开 Phase 0 了。

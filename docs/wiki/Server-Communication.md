# 服务器通信

> 适用：本地 `0.1.0-SNAPSHOT`，Protobuf wire v2。协议定义和运行时已从 JSON 迁移到 Protobuf；与旧 wire v1 不兼容。尚未发布稳定协议，生产服务器业务联调未完成。

## 协议源文件与生成

**`.proto` 是字段格式的唯一来源**；客户端和服务端应固定同一个提交的协议文件，再用 `protoc` 生成各自语言的消息类。不要依据本文的字段表另外手写一套 JSON 编解码器。

| 仓库内路径 | 内容 |
|---|---|
| `src/main/proto/kizuna/inventoryui/v2/wire.proto` | `Envelope`、`Kind`、`Direction`、`Capability`；统一信封与模块协商 |
| `src/main/proto/kizuna/inventoryui/v2/inventory.proto` | 库存快照、增量、容器、物品、占格和移动意图；不包含仓库等业务事务 |

在仓库根目录运行（Java 17）：

```bash
./gradlew generateProto protocolZip
./gradlew test build
```

Gradle 固定 `protoc` 和 `protobuf-java` 为 `4.36.2`，按系统下载编译器，无需全局安装。生成 Java 位于 `build/generated/sources/proto/main/java/`，包名为 `dev.kizuna.inventoryui.protocol.pb`，外层类为 `WireProtocol` 和 `InventoryProtocol`。生成代码不手改、不提交；改 `.proto` 后重新构建。

`build/distributions/kizuna-inventory-ui-framework-0.1.0-SNAPSHOT-proto.zip` 包含两份公共 `.proto`、MIT 许可证和 `descriptors/inventory-ui-v2.desc`。服务端只需协议源文件与该语言的 Protobuf 运行库，不必加载 Minecraft 客户端 JAR。描述符也单独生成在 `build/descriptors/inventory-ui-v2.desc`。这些是本地构建产物，尚无已发布下载资产。

其他语言可以使用兼容的 `protoc`；以下命令在已安装编译器的仓库根目录生成 Python 文件。Python 运行库须与所用编译器兼容，仓库不会代接入方安装它：

```bash
mkdir -p "build/generated/python"
protoc -I "src/main/proto" \
  --python_out="build/generated/python" \
  "src/main/proto/kizuna/inventoryui/v2/wire.proto" \
  "src/main/proto/kizuna/inventoryui/v2/inventory.proto"
```

Java 子 JAR 复用框架提供的生成类及运行库；不要把公共 proto 再生成一份同包类塞入子 JAR。自定义业务 proto 由子 JAR 自己生成，可 `import "kizuna/inventoryui/v2/inventory.proto"` 引用公共类型；公共协议目录只作为 import 路径。Fabric 框架产物内嵌 Protobuf Java 运行库，使用本地文件依赖开发时还需声明 `compileOnly 'com.google.protobuf:protobuf-java:4.36.2'`，不要再次内嵌另一份运行库。

## 职责与入口

框架接收统一信封，确认所属模块及消息契约后，只调用对应子 JAR 的处理器。处理器更新共享状态，多个窗口和 HUD 订阅同一份状态。客户端操作反向生成模块请求，通过框架发送并跟踪结果。

框架不内置“装备”“使用技能”“制作”等业务消息。每个子 JAR 注册自己的 `UiWire.Contract<T>`：

```java
bindings.message(new UiWire.Contract<>(
    new UiMessageRouter.Contract<>(
        "example:storage", "example:snapshot", 1,
        UiMessageRouter.Direction.SERVER_TO_CLIENT,
        InventorySnapshot.class, inventoryState::replace),
    InventoryWire.SNAPSHOT,
    true));
```

这是 `ClientModule.register` 内的示例片段，`inventoryState` 由该模块创建，初始流身份必须与服务器约定。`UiMessageRouter`、`UiWire` 和 `InventoryWire` 位于 `dev.kizuna.inventoryui.protocol`。入站消息必须有处理器，出站消息处理器必须为 null；命名空间和模块 ID 必须属于注册者。`required` 表示此消息能力在服务器协商时不可省略。

`InventoryWire.SNAPSHOT`、`DELTA`、`MOVE` 将现有显示模型与生成类显式互转，网络输入仍通过模型／适配器校验。自定义业务消息使用 `UiWire.Codec.protobuf(GeneratedMessage.getDefaultInstance())`；需要转换到领域对象时，使用三参数重载 `protobuf(defaultInstance, toProto, fromProto)`，在转换函数中检查必填字段、数值与引用。`GeneratedMessage` 仅表示接入方自己生成的消息类型，不是框架提供的类名。

编解码接口为 `ByteString encode(T)`、`T decode(ByteString)` 和 `String payloadType()`。标准工厂从生成类描述符取得 Protobuf 全名用于协商。Protobuf 能解析不表示业务合法：空字符串、零值及缺失子消息仍须校验。直接消费生成类时，业务处理器承担领域校验；希望把领域错误报告为 `INVALID_PAYLOAD` 时，在转换函数中拒绝。

## Fabric 传输格式

通道为 **`kizuna_inventory_ui:message`**，双向 CustomPayload。body 是一个 `kizuna.inventoryui.v2.Envelope` 的 Protobuf 二进制，**没有额外的字符串／字节数组长度前缀**；不要使用 `writeDelimitedTo`。Minecraft 外层包仍按原协议编码。这不引入 HTTP 或 gRPC。框架单包上限 `UiWire.MAX_PACKET_BYTES`（262144 字节），信封及模块载荷分别限制消息递归深度为 32；接入方还必须遵守底层传输自身的大小限制。

信封字段：

| 字段 | 类型 | 含义 |
|---|---|---|
| `protocol` | `uint32` | 框架协议，当前 `UiWire.VERSION` 为 2 |
| `kind` | `Kind` 枚举 | `HELLO`、`ACCEPT`、`EVENT`、`REQUEST`、`RESULT`、`ERROR` |
| `module` | 字符串 | 目标模块 ID，业务信封必须匹配已安装模块 |
| `message` | 字符串 | 注册的消息 ID |
| `version` | `uint32` | 该条消息的兼容版本；Java 实现要求正数且不超过 `Integer.MAX_VALUE` |
| `request` | 字符串 | 客户端生成的 UUID；结果原样回传 |
| `status` | 字符串 | 结果的 `ACCEPTED`／`REJECTED` 或错误代码 |
| `data` | `bytes` | 注册的 Protobuf 消息序列化字节；控制信封使用空字节 |
| `capabilities` | `map<string, Capability>` | 消息 ID → 模块、版本、方向、必需标记和 `payload_type`；仅用于协商 |

字段编号、枚举数值和字段类型以 proto 为准。`direction` 为 `SERVER_TO_CLIENT` 或 `CLIENT_TO_SERVER`；`*_UNSPECIFIED` 和未知枚举数值会被运行时拒绝。协商不使用的字符串字段为空串、版本字段为 0。`UiWire.encode/decode` 提供对称编码实现；服务端可直接使用生成类的序列化方法。

`data` 保持为字节，让第三方模块可以独立增加 `.proto`，无需向框架的 `oneof` 清单添加每一种业务类型。该字节必须是协商的 `payload_type` 所指消息，不是任意 JSON。框架先检查目标模块、消息、版本与方向，再调用对应 codec；类型名不是权限证明，也不能替代双方固定 schema 版本。

例如服务端发送完整库存：先构造 `InventoryProtocol.InventorySnapshot`，把 `toByteString()` 放入 `Envelope.data`，设置 `kind=EVENT`、注册的 `module/message/version`，最后发送 `Envelope.toByteArray()`。服务端回复 `RESULT` 时 `data` 留空，原样回传请求身份，库存更新另发事件。

## 连接与能力协商

1. 服务器必须先通过 Fabric 通道注册声明支持此通道；不支持通道时保留纯本地 UI，框架不擅自发送自定义包。
2. 客户端发送 `HELLO`，列出已安装模块的消息能力。
3. 服务器返回 `ACCEPT`，列出接受的能力。每项模块、版本、方向及 `payload_type` 必须与客户端一致；可选能力可省略，必需能力不可省略。服务器要求客户端没有的能力也导致明确失败。相同类型名不证明 schema 一致，双方仍须固定协议文件及消息版本。
4. 只有状态进入 `READY` 才允许请求。`ClientModule.protocolReady(runtime)` 在就绪后调用，模块在这里订阅或申请初始状态；`connected` 不表示协商完成。
5. 协商超过 `HANDSHAKE_TIMEOUT_MS`（10 秒）进入 `FAILED`。断线清理所有请求并使旧代数失效。

服务器可以返回 `ERROR` 说明拒绝原因。能力声明是兼容信息，不能作为玩家权限证明。

宿主已有网络协议时，可调用 `runtime.transport(packet -> ...)` 替换出站适配；连接调用 `runtime.protocol().connect(nowMs)`，入站在客户端线程调用 `runtime.receive(generation, packet)`。收到数据时关联原连接代数，切勿为旧包补用新代数。默认 Fabric 适配器通过 network handler 身份屏蔽旧连接排队任务。

`UiMessageRouter` 仍可独立用于已经解码的 Java 对象；它的 `ACCEPTED` 是本地交付结果。带 wire、协商和结果跟踪时使用 `UiProtocolSession`，无需同时手动分发两次。

## 输入、输出与错误

| 方向 | kind | 行为 |
|---|---|---|
| C2S | `HELLO` | 声明消息能力 |
| S2C | `ACCEPT` | 接受兼容能力 |
| S2C | `EVENT` | 解码并向所属模块投递状态或通知 |
| C2S | `REQUEST` | 带唯一请求 ID 的模块操作 |
| S2C | `RESULT` | 对拍模块、消息、版本和请求 ID，完成请求结果 |
| 双向 | `ERROR` | 结构化失败；不会回复另一条 ERROR 形成回声 |

已协商连接收到缺失子 JAR 的事件会回复 `MODULE_MISSING`；消息未声明为 `UNKNOWN_MESSAGE`；不兼容版本／方向为 `PROTOCOL_MISMATCH`；无效载荷为 `INVALID_PAYLOAD`；模块处理异常为 `HANDLER_FAILURE`。所有错误均进入诊断，`runtime.protocolError()` 可查询最近错误。协商尚未完成时业务包返回 `NOT_READY`。

## 发起请求

```java
runtime.protocol().request("example:move", moveIntent, System.currentTimeMillis())
    .thenAccept(outcome -> {
        // 仅处理请求结果；库存位置仍等待独立的权威快照或增量。
        if (!outcome.accepted()) {
            showFailure(outcome.code());
        }
    });
```

以上为模块内片段，`example:move` 必须先注册 C2S 契约，`showFailure` 为模块的表现逻辑。未来对象在客户端线程由入站结果、tick 或断线完成。请求上限 128，超时为 `REQUEST_TIMEOUT_MS`（10 秒）。

结果区分 `ACCEPTED`、`REJECTED`、`TIMEOUT`、`DISCONNECTED`、`TRANSPORT_FAILURE`、`NOT_READY` 等。超时不证明服务器没有执行；框架不会自动重试有副作用的请求。服务端结果可能先于或后于状态更新，模块必须明确自身契约，不可用成功结果虚构库存状态。

服务器应回传原请求的 `module/message/version/request`，并设置 `kind=RESULT` 和 `status=ACCEPTED` 或 `REJECTED`。拒绝的业务详情可以另发模块事件；当前通用 `Outcome` 只提供布尔结果、状态码与请求 ID。

## 库存状态示例

库存模块通常注册完整快照、增量、移动／分堆意图和完整重同步请求。公共 `InventorySnapshot`、`InventoryDelta`、`InventoryGrid.MoveIntent` 可作为载荷，但消息 ID 和服务器事务由模块决定。

对应使用 `InventoryWire.SNAPSHOT`、`InventoryWire.DELTA`、`InventoryWire.MOVE`。坐标与尺寸单位为格，revision 使用非负 `int64`。`InventoryDelta.containers` 使用 `ContainerDirectory` 子消息的存在性区分：不设置代表沿用旧目录，设置为空消息代表完整替换成空目录。不能把这两种情况都当成空数组。

`InventoryState.applyDelta(delta, requestFull)` 只接受精确衔接的 base revision，缺口触发完整同步回调，旧快照保持不变。完整快照更新后主屏、装备投影和 HUD 同步读取。不要让每个窗口重复应用同一增量。

服务器独立校验玩家身份、归属、会话、距离、容量和业务规则。框架的本地接受条件只用于反馈。

## 验证边界

单测通过编码后的内存传输验证 HELLO／ACCEPT、事件、请求、拒绝、缺失模块、坏载荷、超时、断线与旧连接隔离。真实 Fabric 服务端与客户端已验证 HELLO／ACCEPT、状态事件路由、请求接受／拒绝，以及客户端对缺失模块事件回复 MODULE_MISSING。生产业务权限、真实库存事务和多端兼容仍需接入项目验收。

相关文档：[库存与状态](Inventory-and-State.md)、[框架边界](Framework-Boundaries.md)、[故障排查](Troubleshooting.md)。

Protobuf 回归另覆盖独立生成类与运行时互通、类型协商不匹配、未知字段兼容、未知枚举拒绝、大小／递归限制、截断消息、库存字段校验和增量目录存在性。测试与实机夹具的 proto 分别放在 `src/test/proto/`、`src/wireSmoke/proto/`，不会进入公共框架、demo 或协议分发包。

## 从 JSON wire v1 迁移

1. 客户端与服务端同时切换到本次 `.proto` 并重新生成代码；`protocol` 改为 2。旧 JSON 与 v2 二进制不能混用，没有自动降级或双格式猜测。
2. `UiWire.Packet.data` 从 `JsonElement` 改为不可变 `ByteString`；空载荷使用 `ByteString.EMPTY`。
3. 删除 `Codec.record(...)` 的使用；公共库存改用 `InventoryWire`，自定义载荷定义 proto 后用 `Codec.protobuf(...)`。
4. `Capability` 增加 `payloadType`；服务端 `ACCEPT` 回传匹配的 `payload_type`。
5. 保留请求关联、权威状态、超时重同步和服务端去重规则；Protobuf 本身不提供这些事务保证。

schema 演进必须保留已有字段编号和含义；删除字段时将编号及名称标为 `reserved`，不可复用。新增可选字段可兼容演进，改变字段含义或业务语义则升级消息版本并重新协商。未识别的新增字段可被解析器忽略；框架适配器不承诺保留未知字段后原样转发。

## 复现实机通信验收

在 Java 17 与 Linux 图形环境（或已安装 Xvfb）下运行 `python3 scripts/wire_smoke.py`。脚本只使用 `build/wire-server/`、`build/wire-client/`，监听 `127.0.0.1:25576`，建立离线测试世界；对应端口需空闲。测试夹具位于 `src/wireSmoke/`，不进入框架或 demo JAR。

成功后两端分别生成 `wire-server-ok.txt`、`wire-client-ok.txt`，客户端另存游戏内固定窗口截图 `screenshots/pinned-hud.png`。脚本每次清除旧成功标记并检查本轮进程退出状态；服务端在测试客户端断开后关闭。日志为 `build/wire-server.log` 与 `build/wire-client.log`。

# 服务器通信

> 适用：本地 `0.1.0-SNAPSHOT`。框架 wire v1、默认 Fabric 通道、能力协商、按模块路由、请求结果／超时和断线隔离已实现。尚未发布稳定协议，生产服务器业务联调未完成。

## 职责与入口

框架接收统一信封，确认所属模块及消息契约后，只调用对应子 JAR 的处理器。处理器更新共享状态，多个窗口和 HUD 订阅同一份状态。客户端操作反向生成模块请求，通过框架发送并跟踪结果。

框架不内置“装备”“使用技能”“制作”等业务消息。每个子 JAR 注册自己的 `UiWire.Contract<T>`：

```java
bindings.message(new UiWire.Contract<>(
    new UiMessageRouter.Contract<>(
        "example:storage", "example:snapshot", 1,
        UiMessageRouter.Direction.SERVER_TO_CLIENT,
        InventorySnapshot.class, inventoryState::replace),
    UiWire.Codec.record(InventorySnapshot.class),
    true));
```

这是 `ClientModule.register` 内的示例片段，`inventoryState` 由该模块创建，初始流身份必须与服务器约定。`UiMessageRouter` 位于 `dev.kizuna.inventoryui.protocol`。入站消息必须有处理器，出站消息处理器必须为 null；命名空间和模块 ID 必须属于注册者。`required` 表示此消息能力在服务器协商时不可省略。

`UiWire.Codec.record(Type.class)` 适用于构造器已校验字段的 Java record，解码会调用规范构造器。复杂载荷实现 `UiWire.Codec<T>`，在 `decode(JsonElement)` 内验证必填字段、数值与引用约束。框架不会替模块推断业务 schema。

## Fabric 传输格式

通道为 **`kizuna_inventory_ui:message`**，双向 CustomPayload。body 是 UTF-8 JSON 原始字节，**没有额外的字符串／字节数组长度前缀**；Minecraft 外层包仍按原协议编码。单包上限 `UiWire.MAX_PACKET_BYTES`（262144 字节），JSON 嵌套上限 32 层。

信封字段：

| 字段 | 类型 | 含义 |
|---|---|---|
| `protocol` | 整数 | 框架协议，当前 `UiWire.VERSION` 为 1 |
| `kind` | 字符串 | `HELLO`、`ACCEPT`、`EVENT`、`REQUEST`、`RESULT`、`ERROR` |
| `module` | 字符串 | 目标模块 ID，业务信封必须匹配已安装模块 |
| `message` | 字符串 | 注册的消息 ID |
| `version` | 整数 | 该条消息的兼容版本 |
| `request` | 字符串 | 客户端生成的 UUID；结果原样回传 |
| `status` | 字符串 | 结果的 `ACCEPTED`／`REJECTED` 或错误代码 |
| `data` | JSON | 模块声明的载荷，控制信封可为 null |
| `capabilities` | 对象 | 消息 ID → `{module, version, direction, required}`；仅用于协商 |

字段名与枚举值区分大小写。`direction` 为 `SERVER_TO_CLIENT` 或 `CLIENT_TO_SERVER`。协商不使用的字符串字段为空串、版本字段为 0。`UiWire.encode/decode` 提供对称编码实现，服务端可用任意语言实现同一格式。

## 连接与能力协商

1. 服务器必须先通过 Fabric 通道注册声明支持此通道；不支持通道时保留纯本地 UI，框架不擅自发送自定义包。
2. 客户端发送 `HELLO`，列出已安装模块的消息能力。
3. 服务器返回 `ACCEPT`，列出接受的能力。每项模块、版本、方向必须与客户端一致；可选能力可省略，必需能力不可省略。服务器要求客户端没有的能力也导致明确失败。
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

`InventoryState.applyDelta(delta, requestFull)` 只接受精确衔接的 base revision，缺口触发完整同步回调，旧快照保持不变。完整快照更新后主屏、装备投影和 HUD 同步读取。不要让每个窗口重复应用同一增量。

服务器独立校验玩家身份、归属、会话、距离、容量和业务规则。框架的本地接受条件只用于反馈。

## 验证边界

单测通过编码后的内存传输验证 HELLO／ACCEPT、事件、请求、拒绝、缺失模块、坏载荷、超时、断线与旧连接隔离。真实 Fabric 服务端与客户端已验证 HELLO／ACCEPT、状态事件路由、请求接受／拒绝，以及客户端对缺失模块事件回复 MODULE_MISSING。生产业务权限、真实库存事务和多端兼容仍需接入项目验收。

相关文档：[库存与状态](Inventory-and-State.md)、[框架边界](Framework-Boundaries.md)、[故障排查](Troubleshooting.md)。

## 复现实机通信验收

在 Java 17 与 Linux 图形环境（或已安装 Xvfb）下运行 `python3 scripts/wire_smoke.py`。脚本只使用 `build/wire-server/`、`build/wire-client/`，监听 `127.0.0.1:25576`，建立离线测试世界；对应端口需空闲。测试夹具位于 `src/wireSmoke/`，不进入框架或 demo JAR。

成功后两端分别生成 `wire-server-ok.txt`、`wire-client-ok.txt`，客户端另存游戏内固定窗口截图 `screenshots/pinned-hud.png`。脚本每次清除旧成功标记并检查本轮进程退出状态；服务端在测试客户端断开后关闭。日志为 `build/wire-server.log` 与 `build/wire-client.log`。

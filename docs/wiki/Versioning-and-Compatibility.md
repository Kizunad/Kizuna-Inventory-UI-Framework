# 版本与兼容性

## 当前发布状态

| 项目 | 状态 |
|---|---|
| 框架 JAR / Maven 坐标 | 本地可构建 Fabric 框架／示例 JAR；尚未发布 Maven 或版本资产 |
| 稳定 Java API | 纯逻辑 API 与 Fabric 客户端 API 已实现，尚未承诺兼容稳定性 |
| 服务器 wire 协议 | `UiWire.VERSION=2`，Protobuf 二进制；已验证编解码及真实 Fabric 往返，与 JSON v1 不兼容，尚无生产业务联调 |
| 协议定义／生成 | `src/main/proto/`；`protoc` 与 `protobuf-java` 固定 `4.36.2`；本地可构建独立 proto ZIP 和描述符 |
| 可编译接入示例 | `src/demo/`，通过公开入口依赖框架，单独输出 JAR |
| 游戏适配 | Minecraft 1.20.1 / Fabric Loader 0.16.10 / Fabric API 0.92.3+1.20.1 / Java 17 / owo-lib 0.11.2+1.20 |

“目标支持”不等于已验证兼容。正式版本必须提供依赖范围、构建和运行证据，本文随发布更新。

## 分开记录的版本

- **框架版本**：决定公开 Java API 与运行行为。
- **模块版本**：由扩展模块自行发布，声明可消费的框架版本。
- **协议版本**：按消息契约与能力协商，不能直接用 JAR 文件名推断。
- **偏好格式版本**：用于窗口、HUD 和外观设置升级，独立于服务器业务状态。

## 拟定升级规则

### 本次预发布迁移：JSON wire v1 → Protobuf wire v2

客户端和服务端必须同时升级；保留通道 ID，但内容从 JSON 改为 `.proto` 生成的二进制。`UiWire.Packet.data` 改为 `ByteString`，`Codec.record(...)` 替换为 `Codec.protobuf(...)` 或公共 `InventoryWire` 适配器；能力声明新增 `payload_type` 并参与协商。框架 JAR 版本仍为未发布的 `0.1.0-SNAPSHOT`，不能仅靠此版本串判断新旧 wire，接入方须固定提交。

协议源文件、生成命令和完整迁移步骤见[服务器通信](Server-Communication.md)。本地偏好仍为原有 JSON 格式，不受此网络协议变更影响。新增字段不得复用旧字段号；业务语义不兼容时升级消息版本。

首个稳定版之前，公开 API 可能调整；每次调整都要提供影响范围和升级步骤。稳定版发布后，破坏公开契约的修改应明确提升主版本，弃用接口需给出替代方案和移除计划。

接入方固定经过验证的依赖版本。升级前检查 API、模块依赖、服务器协议与本地偏好格式，运行库存操作、窗口生命周期、HUD 状态同步和断线重连回归。

Wiki 首页反映主分支当前状态。每个正式版本的接入文档应随该版本 Git tag 保存，发布说明链接到对应 tag 中的 `docs/wiki/`，避免较新 Wiki 示例被误用于旧 JAR。

维护方式见[文档维护](Documentation-Maintenance.md)。

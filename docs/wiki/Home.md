# Kizuna's Inventory UI Framework Wiki

面向模组开发者的接入与扩展指南。框架提供库存界面、窗口工作台、HUD 布局、主题注册及模块通信基础设施，接入方负责业务数据与服务端规则。

> 当前已有 Java 17 基础层、Fabric/owo 工作台及独立演示 JAR，可本地构建运行；尚无已发布版本、Maven 坐标或稳定 API。文档将已实现代码和后续设计分开说明。

## 从哪里开始

| 目标 | 文档 |
|---|---|
| 了解环境、依赖与最小接入步骤 | [接入指南](Getting-Started.md) |
| 添加子 JAR、功能入口或窗口 | [模块与窗口扩展](Modules-and-Windows.md) |
| 接入库存、拖放和共享状态 | [库存与状态](Inventory-and-State.md) |
| 添加 HUD、快捷栏和动态槽位 | [HUD 与槽位栏](HUD-and-Slot-Bars.md) |
| 注册主题、背景和资源 | [主题与背景](Themes-and-Backgrounds.md) |
| 定义服务器输入和客户端请求 | [服务器通信](Server-Communication.md) |
| 排查缺失模块、版本和状态问题 | [故障排查](Troubleshooting.md) |
| 查询发布状态与升级规则 | [版本与兼容性](Versioning-and-Compatibility.md) |
| 更新文档与发布 Wiki | [文档维护](Documentation-Maintenance.md) |

## 核心概念

- **宿主应用**：使用框架的客户端模组，初始化框架并提供数据和网络适配。
- **扩展模块**：通过公开 API 提供窗口、HUD、库存能力或外观；可以与宿主一起发布，也可以放在独立子 JAR 中。
- **工作台**：提供功能目录和窗口操作的主屏。关闭它不会自动销毁全部 HUD 或业务会话。
- **状态源**：模块持有的共享数据入口，供多个窗口与 HUD 订阅。
- **操作意图**：用户希望执行的动作；发送成功不等于服务端已经执行。

当前构建使用 Minecraft 1.20.1、Fabric Loader 0.16.10、Java 17 和 owo-lib 0.11.2+1.20。模块注册与网络握手属于不同阶段：Fabric 先加载 JAR，框架再检查模块依赖，连接服务端后再按每条消息的版本和方向协商能力。

架构决议与实施范围见主仓库的[架构设计](https://github.com/Kizunad/Kizuna-Inventory-UI-Framework/blob/main/docs/architecture.md)、[协议设计](https://github.com/Kizunad/Kizuna-Inventory-UI-Framework/blob/main/docs/protocol.md)和[路线图](https://github.com/Kizunad/Kizuna-Inventory-UI-Framework/blob/main/docs/roadmap.md)。

- [框架边界与迁移清单](Framework-Boundaries.md)：公共能力、子 JAR 职责及偏好存储。

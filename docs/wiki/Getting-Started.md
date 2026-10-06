# 接入指南

> 状态：首版实现，尚未正式发布。可构建 Fabric 框架 JAR 和独立示例 JAR。

## 环境与依赖

| 项目 | 当前构建版本 |
|---|---|
| Minecraft / Java | 1.20.1 / 17 |
| Fabric Loader | 0.16.10 |
| Fabric API | 0.92.3+1.20.1 |
| owo-lib | 0.11.2+1.20 |

在仓库根目录运行：

```bash
./gradlew test build
./gradlew runDemo
```

请先确保 `java -version` 为 17。`runDemo` 从标题页自动打开示例工作台；普通框架使用者进入世界后按 **I** 打开，键位可在游戏设置中修改。

`build/libs/kizuna-inventory-ui-framework-0.1.0-SNAPSHOT.jar` 为可加载的框架 JAR；`-demo.jar` 是独立示例模组，两者分别打包。框架不包含示例源码或模拟服务器。安装时同时提供 Fabric API 和 owo-lib；`-sources.jar` 和 `-demo-dev.jar` 不是运行产物。

Linux 无窗口环境可用 `xvfb-run -a ./gradlew runSmoke`。演示检查通过后生成 `build/smoke/smoke-ok.txt` 和 `build/smoke/screenshots/workspace.png`。这是客户端模拟数据验收；真实 Fabric 通道验收另运行 `python3 scripts/wire_smoke.py`，详见[服务器通信](Server-Communication.md)。新增外框与背景截图为 `window-chrome.png`、`background-gallery.png`。

尚无发布的 Maven 坐标。扩展开发暂时使用本地构建产物，例如在 Fabric Loom 项目中声明 `modImplementation files("libs/kizuna-inventory-ui-framework-0.1.0-SNAPSHOT.jar")`，同时声明上述运行依赖。框架只用于客户端。

## 最小接入流程

1. 根据发布版本的兼容表配置游戏、加载器和框架依赖，使用声明支持的 Java 版本。
2. 为扩展模块选择独立命名空间，例如 `example:inventory`，在 Fabric 清单约束版本，在框架目录声明必需依赖。
3. 提供一个只读库存快照和订阅入口，先使用模拟数据验证界面。
4. 注册窗口内容及功能入口，验证点击入口会创建或聚焦同一窗口。
5. 注册一个读取同一份快照的 HUD，验证关闭工作台后 HUD 仍能更新。
6. 接入类型化操作出口和网络适配器，验证请求被服务端接受与拒绝的行为。
7. 验证断线重连、模块缺失和资源重载，再分发给使用者。

## 宿主需要提供什么

| 接入点 | 职责 |
|---|---|
| 模块初始化 | 提交模块声明与注册项，处理缺失依赖或重复 ID |
| 客户端执行器 | 在规定线程提交状态、通知订阅者并操作 UI |
| 状态源 | 提供不可变快照及可关闭的订阅句柄 |
| 操作出口 | 接收用户意图，关联请求结果，不把发送成功视为业务成功 |
| 网络适配 | 注册模块消息编解码与处理器；默认 Fabric 传输已由框架提供，可替换宿主适配器 |
| 资源 | 按模块命名空间提供图标、主题和背景 |

纯本地显示模块可以使用本地数据源而不声明服务器消息。需要服务端交互的模块则必须先通过能力检查；不能在连接不兼容时继续展示可执行的旧操作。

后续阅读：[模块与窗口扩展](Modules-and-Windows.md)、[库存与状态](Inventory-and-State.md)、[服务器通信](Server-Communication.md)。

开始扩展前请先阅读[框架边界与迁移清单](Framework-Boundaries.md)。

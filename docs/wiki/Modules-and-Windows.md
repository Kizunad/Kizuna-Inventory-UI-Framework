# 模块与窗口扩展

> 状态：部分实现。`FrameworkCatalog`、`WorkspaceController` 和迁入的 `UiWindowManager` 可调用；`ClientModule`、`ClientBindings`、`ClientRuntime` 与 `WorkspaceScreen` 已接入 Fabric/owo。

当前 Java 类型位于 `dev.kizuna.inventoryui.registry.FrameworkCatalog`、`dev.kizuna.inventoryui.workspace.WorkspaceController` 和 `dev.kizuna.inventoryui.window`。一个模块用 `FrameworkCatalog.Module.of(id, dependencies, entries...)` 声明。条目可为 `Window`、`Feature`、`Hud`、`SlotBar`、`Theme` 、`Background` 或 `SvgAsset`；`FrameworkCatalog.build(modules)` 按依赖顺序校验并生成不可变目录。缺失依赖、环、重复 ID、越界命名空间或功能引用不存在的窗口都会在构建目录时抛出异常，不会返回半成品。

## 定义一个模块

模块 ID 使用自己的命名空间。模块 ID、显示标题和 JAR 文件名各有用途：ID 用于依赖、路由及偏好关联；标题可以本地化；文件名不作为运行时身份。

概念示例，下面是设计记录，不是框架已经支持的配置格式：

```text
模块 ID：example:storage
功能入口：example:storage_browser
窗口类型：example:container
窗口实例身份：容器的稳定 ID
必需能力：库存状态读取、库存移动请求
可选内容：容量 HUD
```

当前模块声明仅包含 ID、必需依赖和注册项。框架 API 兼容范围由子 JAR 的 Fabric `depends` 约束，通信兼容由每条消息版本协商；目录不重复提供另一套模组版本系统。注册目录一次性构建成功后整体可见；运行时增删模块不受支持。

## 注册功能入口与窗口

功能入口声明标题、图标 ID、分类及关联窗口类型。目录按分类／标题排序，业务操作是否可用由模块处理。窗口描述内容工厂、最小尺寸、实例策略和支持的窗口操作。两者分别注册，使同一窗口可以由目录、物品点击或键位打开。

| 实例策略 | 适用场景 | 再次打开 |
|---|---|---|
| 模块单例 | 设置、功能目录 | 恢复并聚焦现有窗口 |
| 按对象 ID | 容器、物品详情 | 同对象聚焦，不同对象分别创建 |
| 按服务端会话 | 交易、制作工作站 | 校验会话有效性，再显示内容 |

主屏从注册项生成目录和窗口栏。扩展模块不修改全局 tab 常量，也不直接接管其他窗口的输入。窗口的可见入口不是业务授权；服务端仍需检查每个操作。

已实现的 `WorkspaceController.features(query)` 按分类和标题返回目录，`openFeature(featureId, objectId, bounds)` 使用 `SINGLETON` 或 `BY_OBJECT` 窗口策略创建或聚焦窗口，`windowBar()` 返回当前窗口，`pinFeature()` 管理固定入口。`disconnect()` 清理当前窗口但保留固定入口。Fabric 适配层提供目录搜索／分页、窗口拖动、关闭、最小化／恢复及动态窗口栏。`BY_OBJECT` 功能由模块通过 `ClientRuntime.open(featureId, objectId)` 打开；目录无法替模块选择业务对象。目录提供固定／取消固定入口；右下角拖动可调整窗口尺寸；Ctrl+Tab 循环窗口。固定入口、窗口外框及 HUD／外观偏好可持久化，外观菜单可恢复窗口布局。

## 子 JAR 的公开入口

子模组实现 `dev.kizuna.inventoryui.client.ClientModule`，在自己的 `fabric.mod.json` 中声明：

```json
{
  "entrypoints": {
    "kizuna_inventory_ui": ["example.client.StorageModule"]
  },
  "depends": {
    "kizuna_inventory_ui": "*"
  }
}
```

以上是需要合并到完整模组清单中的片段。生产接入应在框架发布后把 `*` 换成验证过的版本范围。

`definition()` 返回模块声明；`register(ClientBindings)` 按已声明 ID 注册窗口工厂、HUD 绘制器及槽位内容提供者。声明了可绘制条目却没有绑定实现、绑定他人模块 ID、重复绑定或注册完成后继续修改，都会报错。

窗口工厂签名为 `Component create(ClientRuntime runtime, UiWindowManager.WindowState state)`，返回独立的 owo 组件树。订阅用 `state.scope().addCleanup(subscription::close)` 清理，或使用 `UiStateBinder.bind(...)`。每个窗口实例只建一次组件树，最小化或关闭工作台保留窗口；明确关窗和断线释放 scope 与渲染资源。

扩展回调均在客户端线程运行；回调异常不会被当成成功忽略。模块收到 `connected(runtime)` 与 `disconnected(runtime)` 后管理自身状态；网络能力协商成功后收到 `protocolReady(runtime)`，应在这里申请初始状态。消息通过 `bindings.message(...)` 注册，详情见通信页。完整可编译示例见仓库 `src/demo/java/dev/kizuna/inventoryui/demo/DemoModule.java`。

## 生命周期

| 原因 | 模块应该处理什么 |
|---|---|
| 打开或恢复 | 读取当前状态，建立本窗口拥有的订阅 |
| 最小化、隐藏工作台 | 暂停必要的交互，保留按契约存活的业务状态 |
| 明确关窗 | 清理窗口订阅；是否结束业务会话由模块契约决定 |
| 对象失效 | 禁用旧动作，关闭或显示失效状态 |
| 断线、切服 | 清理旧连接会话，取消待处理 UI 状态与订阅 |

状态源可以由模块共享，订阅句柄归各视图所有。重建窗口不能反复累积监听器，也不能靠关闭某个窗口清空其他视图使用的数据。

## 扩展验证

新增模块应只使用公开 API。至少验证重复打开的实例行为、关闭后的订阅清理、必需模块缺失，以及服务端会话失效后的操作禁用。扩展 JAR 的安装和移除需要重启客户端，首版不承诺运行时卸载。

相关文档：[接入指南](Getting-Started.md)、[HUD 与槽位栏](HUD-and-Slot-Bars.md)、[版本与兼容性](Versioning-and-Compatibility.md)。

完整职责清单及偏好行为见[框架边界](Framework-Boundaries.md)。

## 窗口管理操作

标题栏从左至右为尺寸、最小化、固定、关闭。尺寸编辑接受正整数，非法输入标红并保留当前尺寸；Enter 应用，Escape 取消。最小尺寸和视口约束由窗口管理器统一执行。

普通窗口可用标题栏锁定按钮或 `runtime.pinWindow(window.key(), true)` 固定到 HUD。关闭工作台后，同一组件树继续绘制，隐藏管理按钮并停用交互；不会重新调用工厂或重复建立订阅。最小化暂时隐藏固定窗口，恢复保留固定状态。明确关窗和断线释放该实例；重连不会自动重开业务会话。工位 `STATION` 不允许固定，系统窗口不允许用户修改布局。

固定偏好按窗口类型保存；模块下次主动打开该类型窗口时恢复偏好。多个对象实例仍保持独立内容与 scope。转场期间按当帧可见外框命中，抓住窗口后立即接续拖动。

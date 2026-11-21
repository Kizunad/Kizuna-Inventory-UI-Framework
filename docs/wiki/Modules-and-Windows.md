# 模块与窗口扩展

`ClientRuntime.open(feature, objectId)` 打开或聚焦窗口时同步工作区键盘焦点；重复打开同一窗口不清除
当前控件焦点。`openWorkspace()` 在工作区已显示时复用原屏幕，重新显示时聚焦最高层可见窗口。
模块无需操作 `WorkspaceScreen` 内部字段即可实现公开定向打开接口。

`ClientBindings.featureOpened(featureId, Consumer<WindowState>)` 为本模块已声明的功能登记打开回调。
多个功能可以指向同一个单例窗口；每次通过 `ClientRuntime.open` 新开、恢复或聚焦都会在组件树就绪后调用
相应功能的回调，可用于选择页签或定位内容。回调不创建额外窗口；权限检查仍在打开之前完成。
重复绑定、绑定其他模块功能以及冻结后登记均被拒绝，异常不会被忽略。

模块可通过 `ClientBindings.featureAccess(featureId, BooleanSupplier)` 绑定已声明功能的动态业务权限。工作台菜单查询和 `openFeature` 共用当前判定；返回 false 时隐藏菜单并拒绝直接打开。原有 `visibleInMenu=false` 仍只表示详情入口不进入菜单，不等同于权限。模块应在权威权限撤销时关闭自己的活动窗口并清理订阅；框架不解释具体玩法。

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

主屏从注册项生成功能目录，登记了摘要的窗口可以就地收小。扩展模块不修改全局 tab 常量，也不直接接管其他窗口的输入。窗口的可见入口不是业务授权；服务端仍需检查每个操作。

已实现的 `WorkspaceController.features(query)` 先将固定入口置顶，再按分类和标题返回目录，`openFeature(featureId, objectId, bounds)` 使用 `SINGLETON` 或 `BY_OBJECT` 窗口策略创建或聚焦窗口，`windowBar()` 返回当前窗口，`pinFeature()` 管理固定入口。`disconnect()` 清理当前窗口但保留固定入口。Fabric 适配层提供目录搜索／分页、窗口拖动、关闭、收小／展开及小窗锁定。`BY_OBJECT` 功能由模块通过 `ClientRuntime.open(featureId, objectId)` 打开；目录无法替模块选择业务对象。功能菜单每项只提供打开入口，已移除“固定／取消固定”按钮；既有 `pinFeature()` API 和保存的排序偏好保留。窗口标题栏的 HUD 固定功能独立保留。右下角拖动可调整窗口尺寸；Ctrl+Tab 循环窗口。固定入口、窗口外框及 HUD／外观偏好可持久化，外观菜单可恢复窗口布局。

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

宿主布局完成后调用 `ClientRuntime.attach(screen, viewport)` 取得
`dev.kizuna.inventoryui.client.WindowLayer`。同一屏重复初始化复用绑定。视口用
`UiWindowManager.Rect` 表示，以屏幕逻辑像素为单位，含左上原点，右、下边界不含。
布局变动后及开窗前调用 `layer.viewport(rect)`。窗口管理器保存局部坐标，宿主提交原始屏幕坐标。

宿主画完前景后调用 `layer.extract(graphics, x, y, delta)`。悬停前可查询 `blocksPointer(x, y)`，
排位用 `blockedAreas()` 取得当帧占位。鼠标、滚轮、键盘和字符事件先交给层对应的同名方法；
返回 true 表示本次事件已消费。宿主自己发起的拖动仍由宿主收尾。
`focus(key)` 将窗口置顶；失焦调用 `cancelInput()`，宿主移除时调用 `detach()`。
`runtime.layer(screen)` 只返回该屏的活动绑定。解绑保留同一连接内的窗口、内容树和订阅，
明确关窗及断线通过已有窗口 scope 清理。

窗口工厂返回的根组件可实现 `dev.kizuna.inventoryui.client.WindowInputHandler`，用于引用拖动、
窗口快捷键和长按去重：

- `keyPressed(KeyEvent)` 仅接收活动窗口中未被 owo 聚焦控件消费的按键，包含 Escape。
  返回 `true` 阻止工作台继续处理；菜单和尺寸编辑模态占用输入时不调用窗口处理器。
  模块仍须检查内部编辑控件焦点，不能将输入框的数字字符当成快捷键。
- `keyReleased(KeyEvent)` 通知所有存活窗口，仅用于释放按键锁存，不得执行修改操作。
- `cancelInput()` 在窗口切换、点击工作台空白、打开菜单、关闭工作台及窗口释放时调用。
  模块应取消临时捕获，不提交尚未确认的引用拖动。重复调用须安全。

补充输入不接入库存 `InventoryDragSession`，没有物品移动或服务器权限语义。
窗口关闭后生命周期 scope 负责解除业务订阅；框架不替模块持久化业务状态。

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

详情或操作浮窗可用六参数 `Feature(id, title, category, iconId, windowType, false)` 注册。最后一个参数 `visibleInMenu` 仅控制功能菜单显示；模块仍通过 `ClientRuntime.open(featureId, objectId)` 打开，标题、实例策略、尺寸偏好和 scope 清理与普通入口一致。原五参数构造器保持菜单可见。隐藏入口不是权限控制，业务请求仍由服务端鉴权。

标题栏管理收小、锁和关闭。右下角拖动调整展开矩形。只有登记摘要的类型才有收小与锁的入口。

### 自动尺寸与内容排布

`UiWindowDefinition(windowType, templateId, minimumWidth, minimumHeight, preferredWidth, preferredHeight, capabilities)` 分别声明紧凑下限和推荐尺寸，单位均为 Minecraft GUI 逻辑像素。推荐尺寸不能低于最小尺寸。原五参数构造器保留，推荐尺寸默认为宽 `max(280, minimumWidth)`、高 `max(160, minimumHeight)`。

没有已保存布局时，`ClientRuntime.open` 使用自动模式；直接调用 `openOrFocus` 或 `openFeature` 时传入 `null` 外框也采用该模式。窗口在视口允许范围内接近推荐尺寸，为顶部入口与边缘留位；改变游戏窗口大小或 GUI 缩放后重新计算。视口连最小尺寸也容不下时，优先保证外框可见。框架不会修改玩家的全局 GUI 缩放选项。

玩家拖动或调整尺寸后进入手动模式，小屏暂时夹紧，大屏恢复期望尺寸。自动计算出的外框不写成固定偏好，手动布局才持久化；外观菜单“恢复窗口布局”或 `ClientRuntime.resetWindowLayout()` 恢复自动模式，并保留现有组件与订阅。旧版本保存的外框按手动偏好处理，需要重置后才恢复自动尺寸。

外框适配和内容排布各有职责。模块可用 `dev.kizuna.inventoryui.client.component.ResponsiveLayout` 包装 XML 组件树：构造参数为原组件与 `BiConsumer<Integer, Integer>`，内容区逻辑宽高变化时，在子树布局前回调模块。模块按实际余量安排换行、滚动及列宽，回调中修改原有组件，不重建选择、镜头或业务会话。owo 的 `fill(百分比)` 依据父空间计算，不会自动扣掉固定宽度的兄弟组件；混合固定侧栏时须先扣侧栏和间距，再分配列宽。

### 两版界面

框架同时带两版界面，玩家在「外观」菜单里的「界面：切换到…」或用客户端命令 `/kiui version classic|modern` 切换，偏好里的 `uiVersion` 记下选的是哪一版，没有这一项时是 `classic`。

| | 旧版 `classic` | 新版 `modern` |
|---|---|---|
| 整屏界面 | `WorkspaceScreen`：顶栏「功能／HUD 布局／外观」、底栏卡片、尺寸输入 | `ModernWorkspaceScreen`：顶栏「功能／外观」，收小的窗口就地变成小窗 |
| 外框 | `WindowChrome`，标题栏 22 像素 | `ModernChrome`，标题栏 14 像素 |
| 锁到游戏画面 | 整个展开窗，按窗口类型记在 `pinnedWindows` | 小窗摘要，按功能记在 `modern.compact` |
| 主题 | 墨玉、宣纸、玄黄、深色、浅色，选择记在 `theme` | 琉璃 |
| 窗口矩形 | `windows` | `modern.windows` |

每一版有自己的目录、窗口模型和窗口工厂（`UiEdition` 的两个实现），`ClientRuntime.catalog()`、`workspace()`、`theme()` 返回当前这一版的。模块默认两版共用同一份声明和绑定；某个模块在新版里窗口不一样时，实现 `ClientModule.modernDefinition()` 给出新版的声明（模块 ID 不变，功能和窗口类型 ID 可以沿用），并在 `registerModern(bindings)` 里绑新增或换了内容的窗口、入口权限、摘要和只读恢复；没重新绑的沿用 `register` 里的。协议消息、网络接收器、按键和 tick 回调只在 `register` 里登记一次，两版共用同一份业务状态。

切换时先存下当前版的布局，关掉它开着的窗口（锁和位置保留），再换到另一版；连接不断，模块的业务状态不动。新版锁着的小窗切回来后自动恢复；旧版锁过的整窗和重进游戏时一样，等模块下次打开那个窗口才回到游戏画面。第一次进新版时，旧版锁过的窗口类型各迁成一个锁着的小窗（记在 `modern.migratedPins`，只迁一次），旧版自己的字段不改。

下面两节（小窗、只读恢复）只对新版生效；旧版不读 `ClientBindings.compact` 和 `restore` 的登记。

### 小窗和只读恢复

模块用 `ClientBindings.compact(windowType, provider)` 登记摘要，或用 `compact(windowType, slot, provider)` 同时声明它没被拖动时排在哪一列、列内第几个（`CompactSlot(column, order)`）。`CompactProvider.variants()` 返回有序、非空的样式：`CompactVariant(id, nameKey, width, height)`，`id` 是写进配置的稳定英文 ID，`nameKey` 是右键菜单里显示名的翻译键，尺寸是缩放前的 GUI 像素。`draw(g, state, variant, delta)` 在已经缩放、裁剪的局部坐标绘制，只读当前连接数据。宿主内可拖动透明区域，悬停显示展开、锁、关闭。右键选择样式、缩放和底色，拖过的小窗还有「自动排位」。

持久锁需用 `ClientBindings.restore(featureId, binding)` 登记单例及只读数据源。`readiness()` 检查本连接的玩家、业务通道、数据和权限。`observe(state, status)` 为新请求登记 scope 清理，只在收到匹配请求的首份权威快照后回报 LIVE。手动开窗和自动恢复共用观察；恢复时不创建展开组件，不调用 featureOpened。等待或拒绝时小窗不显示数字。模块可在数据变化时调用 `recheckRestores()`；失败可从功能入口重试。按对象开的窗只能锁在本次连接。

同一窗口形态变化保留 key、scope 和展开矩形。关宿主只解绑定，游戏仅绘制就绪的锁窗摘要；显式关窗撤销固定意图。配置 v2 的 compact 按功能保存 pinned、position、corner、dx、dy、scale、backdropAlpha、variantId。v1 的 pinnedWindows 只在唯一可恢复单例登记后迁移，未消费的类型留给后续加载。

小窗的位置有两种状态。`FLOW`（默认）：框架按各小窗声明的列和顺序，从可用区左上角往下排，到底放不下就在右边另起一列；工作台和游戏画面用同一套排法，只差各自可用区的起点。`PLACED`：玩家拖过之后，只按留下的角和偏距解算，工作台对顶栏以下的可用区、游戏画面对快捷栏以上的安全区各算一次，所以拖到哪个角、离边多远，两处一样。游戏画面里压住准星或原版状态栏的小窗贴到最近的一条边外；右侧不给状态图标预留，图标按小窗的矩形往下让。自动排开的小窗绕开玩家放好的。整个可用区都排不下时退回夹取后的位置，允许重叠。「恢复布局」和右键「自动排位」把位置交还给框架。

验收和其他模块可以用 `ClientRuntime.hudCompacts(width, height)`（游戏画面此刻的小窗：功能、样式、矩形）、`compactBounds(featureId)`（工作台里的矩形）、`pinnedCompacts()` 和 `compactVariant(featureId, variantId)`（换样式）查询和切换，不需要进框架的包。摘要嵌在另一页时，提供者可通过 `CompactProvider.expandedFeature()` 给出展开目标，点开会定位到该功能，原摘要继续保有自己的锁和位置。

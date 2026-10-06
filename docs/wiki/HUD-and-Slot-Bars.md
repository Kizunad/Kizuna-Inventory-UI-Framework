# HUD 与槽位栏

> 状态：HUD、槽位注册、拖放投递、同源 HUD 投影和本地布局保存已实现。绑定含义及服务器权限由扩展模块决定。

## 注册 HUD

HUD 注册项描述稳定 ID、标题、内容工厂、数据源、默认锚点和尺寸，以及是否允许移动、缩放或隐藏。框架据此生成 HUD 管理列表，不要求修改公共枚举。

`FrameworkCatalog.Hud(id, title, anchor, width, height)` 声明元数据，`ClientBindings.hud(id, renderer)` 提供绘制实现。回调接收已经移动到 HUD 左上角并缩放的 `DrawContext`、逻辑宽高和 tick delta。工作台关闭时 Fabric HUD 回调继续绘制；工作台的“HUD 布局”入口提供预览、显隐、位置、缩放和复位。游戏 F1 隐藏 HUD 时也隐藏这些部件。

`ClientRuntime.hudLayout()` 返回 `HudLayout`。`place(id, new HudLayout.Placement(visible, offsetX, offsetY, scale))` 设置相对锚点的布局，缩放范围 0.25–4；`bounds()` 在视口变化时重新约束到屏幕内。偏好保存在本地版本化 JSON；布局面板菜单外可直接拖动 HUD，菜单也提供位置、缩放和复位操作。

`SlotBar(id, slots)` 声明任意数量、具有稳定 ID 的 `Slot(id, kind)`；`ClientBindings.slotBar(id, provider)` 为每个槽返回 `SlotContent(label, activate)`。工作台每帧读取内容，底栏横向滚动，点击调用模块动作。模块如需投影同一栏位，再声明一个 HUD 并调用 `bindings.slotBarHud(hudId, barId)`；框架复用同一内容供应器绘制，无需自行复制绑定状态。一个 HUD 只能选择自定义绘制器或槽位栏投影之一。

概念示例：一个 `example:capacity` HUD 读取库存负重快照，在右下角显示容量。默认位置只是初始值；用户保存的位置按 HUD ID 恢复。模块暂时缺失时保留偏好，不套用到其他部件。

HUD 与工作台窗口可以订阅同一个状态源，但具有独立生命周期。关闭工作台后，HUD 仍按自己的显示条件更新。普通可编辑窗口还可通过标题栏固定按钮把同一组件树投影到 HUD；独立 HUD 注册项则继续使用自己的绘制器和布局。工位窗口禁止固定。

## 三种栏位分别处理

| 对象 | 数据来自哪里 | 点击或拖放的含义 |
|---|---|---|
| 窗口栏 | 已打开窗口及用户固定的入口 | 打开、恢复或聚焦窗口 |
| 游戏槽位栏 | 模块提供的槽位与绑定快照 | 提交物品或动作绑定意图 |
| HUD 部件 | 模块状态源 | 显示数据，按明确能力提供交互 |

槽位以稳定 ID 标识，不依赖全局数组下标。当前槽位列表在启动注册时固定，内容可随数据源动态变化。拖放绑定由 `SlotContent` 回调接入；服务器解锁规则由模块通过内容及接受条件表达，运行中改变注册槽位列表不在本版范围。

注册项决定“这种栏位如何显示与交互”，快照决定“当前有哪些可用槽位及绑定”。用户调整主题、位置或缩放不能增加服务器允许的容量。

## 绑定与反馈

完整槽位内容构造方法为 `SlotContent(label, activate, item, accepts, drop, clear)`。`item` 可为空；`accepts` 接收 `InventoryDragSession.Payload`，`drop` 收到一次投递，右键调用 `clear`。兼容的两参数构造方法是纯动作槽，默认拒绝拖放。

同一契约也用于 `ItemSlotComponent`，可组合出业务装备面板。接受条件只做本地反馈，`drop`／`clear` 应向服务器提交意图并等待权威状态，不直接虚构装备或物品移动。

用户将物品或技能拖到槽位后，模块提交请求并显示等待反馈，框架跟踪结果与超时。HUD 和工作台展示同一份绑定快照；收到拒绝、超时或断线通知后清理临时状态，不保留只存在于某个界面的假绑定。

HUD 的显示条件与用户偏好共同决定可见性。全屏遮罩等内容应明确禁止普通部件的拖动与缩放能力，避免错误地缩成小窗口。

## 验证建议

验证新增 HUD 自动进入管理列表；工作台关闭后仍能更新；绑定被拒绝后两处显示一致；GUI scale 和视口变化后布局与命中一致；缺失模块不会覆盖其他 HUD 的偏好。

相关文档：[库存与状态](Inventory-and-State.md)、[模块与窗口扩展](Modules-and-Windows.md)、[主题与背景](Themes-and-Backgrounds.md)。

## 注册 SVG 绘制资源

模块声明中添加 `new FrameworkCatalog.SvgAsset("example:badge", "example:svg/hud/badge.svg")`，绘制器调用：

```java
runtime.svg().draw(context, "example:badge", x, y, width, height,
        runtime.color(ThemeTokens.ACCENT));
```

以上为已有 `runtime`、绘制上下文和尺寸的回调片段。注册 ID 必须属于模块命名空间；资源由资源包管理器提供。返回 `false` 表示已注册资源加载失败或没有有效绘制面积，未注册 ID 则抛出接入错误。

支持带 SVG 命名空间的 `rect`、`circle`、`ellipse`、`polygon` 和无属性 `g`；支持填充色和透明度、原点为零的 viewBox。当前不支持 path、stroke、文字、渐变、滤镜、外链或脚本。凹多边形使用耳切，自交和越界几何拒绝加载。最大输入 1 MiB、256 图元、512 多边形顶点和 4096 三角形。成功网格和失败结果均缓存，F3+T 清空重载；连续绘制无需每帧解析 XML。

通用 SVG 后端不内置业务 HUD 图形或固定 HUD 层枚举。示例资源仅位于 demo JAR。

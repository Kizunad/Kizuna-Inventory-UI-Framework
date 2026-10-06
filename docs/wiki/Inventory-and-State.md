# 库存与状态

> 状态：公共库存框架已实现，尚未发布稳定版。支持共享快照、增量、跨窗口／容器拖放、旋转、分堆及独立槽位来源；服务器事务由子 JAR 接入。

## 提供显示模型

框架需要物品实例身份、显示名称、图标引用、占位尺寸、数量、容器身份、容器尺寸和槽位信息。业务扩展属性由模块提供，不把角色数值或制作会话塞进通用库存模型。

物品模板 ID 表示种类，物品实例 ID 表示具体对象。移动、分堆和消费操作必须使用契约要求的实例身份，不能用显示名称或模板 ID 替代。

宿主负责将服务端快照转换为显示模型。转换后的快照不可由窗口直接修改；背包、装备窗口和 HUD 消费同一份已提交状态。

当前 `dev.kizuna.inventoryui.inventory.InventorySnapshot` 含 `streamId`、`revision`、动态容器列表与物品占格列表。`Container` 声明 ID、标题、行列数；`Item` 声明实例 ID、种类 ID、名称、图标 ID、占格宽高和数量；`Placement` 声明容器、行列、旋转及物品。构造时检查重复实例、越界和占格冲突。没有固定背包尺寸或快捷栏长度。

阅读模型时可以把它分成三层：

| 类型 | 表达的内容 | 关键约束 |
|---|---|---|
| `Container` | 容器身份、标题、网格尺寸 | `rows` 为纵向行数，`columns` 为横向列数，均大于零 |
| `Item` | 物品身份与显示数据 | `instanceId` 标识具体对象；`itemId` 标识种类；宽高以未旋转时的格子数表示 |
| `Placement` | 物品在哪个容器的哪个位置 | `row`、`column` 从零开始，指向占位矩形左上角；`rotated` 为真时交换物品宽高计算占格 |

例如一个宽 2、高 1 的物品，放在 `row=1, column=3` 且未旋转时，占第 1 行的第 3、4 列；旋转后占第 1、2 行的第 3 列。旋转只改变 `Placement`，不改写 `Item` 中的原始尺寸。

快照构造依次冻结输入列表、建立容器索引、检查实例身份和容器引用，再按物品矩形检查边界与占格重叠；不按容器尺寸分配稠密二维占位表。`revision` 在模型内只要求非负；同一状态流中的版本递增约束由 `InventoryState.replace()` 执行。构造快照和生成移动意图都不会自动发送网络请求。

`InventoryState` 实现 `UiStateSource<InventorySnapshot>`，`replace()` 只接受同一状态流且 revision 递增的完整快照。窗口和 HUD 各自订阅同一实例，并在生命周期结束时关闭句柄。状态回调在调用 `replace()` 的线程执行；宿主应先通过客户端执行器提交状态，不应在网络线程直接触碰控件。

`InventoryGrid.canPlace()` 根据动态容器尺寸、物品占格和旋转计算本地落点；`move()` 仅生成含状态流、基础 revision、来源、目标、位置、方向与数量的 `MoveIntent`，不修改快照也不发送请求。服务端仍需重新校验权限、容量、数量和当前 revision。

## owo 库存组件

`dev.kizuna.inventoryui.client.component.InventoryGridComponent(source, containerId, cellSize, moves)` 接收状态源、容器 ID、格子尺寸和 `Consumer<InventoryGrid.MoveIntent>`。它绘制物品贴图、数量及落点预览，鼠标拖动、R 旋转、失去焦点时取消。它仅在释放到有效落点时调用 `moves`，不会修改权威快照。拖动期间 revision 变化也会取消旧交互。

需要跟随工作台主题时，使用五参数构造方法 `InventoryGridComponent(source, containerId, cellSize, moves, runtime::color)`。第五个参数是 `ToIntFunction<String>` 颜色提供器，组件在绘制时读取网格、物品及落点令牌，切换主题无需重建组件；四参数构造方法保留内置深色外观。令牌清单见[主题与背景](Themes-and-Backgrounds.md)。

组件每个实例对应一个容器，容量更新会同步组件尺寸。`Item.iconId` 使用 Minecraft 纹理资源 ID，缺失时回退名称。工作台中应传入第六个参数 `runtime.drag()`，让多个窗口共用一个会话；四／五参数构造方法保留独立网格内拖放。

示例子 JAR 模拟服务端更新并提供“拒绝下一次移动”按钮。拒绝后原库存位置保留；请求结果展示由模块负责。

## 跨窗拖放与槽位扩展

```java
var grid = new InventoryGridComponent(
    state, containerId, cellSize, this::submitMove, runtime::color, runtime.drag());
```

左键拖动整堆，中键抓取半堆，抓取期间滚轮调整请求数量；R 旋转，Escape／右键取消。释放按最上层窗口的实际落点分发，遮挡窗口、菜单或底栏不会穿透。服务端版本变化、失焦、关窗、离开工作台会撤销旧输入。部分分堆的原位置仍有剩余数量，因此不能与来源占格重叠。

`ItemSlotComponent(size, content, runtime.drag(), runtime::color)` 可组成任意装备布局。它复用 `ClientBindings.SlotContent` 的物品、激活、接受、投递与清除回调。框架不预置装备枚举或规则。

槽位作为来源时设置 `dragSource(...)`；回调调用 `runtime.drag().beginSlot(slotComponent, streamId, revision, slotId, item, count, stillValid)`。第一参数以该组件为 owner，失焦时只取消本槽的交互；`stillValid` 必须对拍权威来源是否仍有效。通用载荷的 `origin()` 区分 `GridOrigin` 与 `SlotOrigin`。

外部槽位或独立库存状态流落入网格，必须显式调用 `grid.externalDrops(accepts, submit)`。框架先检查网格占位，再将 `InventoryDragSession.GridDrop` 交给模块；它携带来源载荷、目标状态流及版本、目标容器和坐标。默认拒绝外部来源，不能误当成普通 `MoveIntent`。自定义画布落点实现 `ItemDropTarget`，屏幕坐标以逻辑像素计。

## 物品展示和公共组件

`grid.presentation(provider, runtime.contextMenu())` 接入 `ItemPresentation`，可提供多行 tooltip、主题边框令牌、右键操作列表、双击详情和 Shift 快速操作。`ItemAction` 定义 ID、标题、启用状态、禁用原因及动作；菜单支持方向键、Enter、Escape 和滚轮。权威快照更新使旧菜单失效。业务详情窗口始终由模块注册。

`ModelPreviewComponent(width, height, modelSupplier, colors)` 接收 `Model`，实现 `bounds()` 与 `render(matrices, buffers, tickDelta)`，可选覆盖 `yUp()`。画布按包围盒适配模型，左键拖动旋转、滚轮缩放，渲染结束恢复上下文。模型资源及玩家实体适配属于子 JAR。

`StatusBarComponent` 接收 `Value(label, current, maximum, fillToken, tooltip)`；`EffectStripComponent` 接收动态 `List<Effect>`，空列表收起，多项可滚动。两者都不内置角色属性或状态规则。

## 交互契约

```text
读取库存快照 → 开始拖动 → 计算落点并显示预览
→ 提交移动意图 → 等待请求结果及权威状态
→ 提交新快照 → 所有视图同步更新
```

本地占位检查可以改善交互，但不能替代服务端的归属、容量和装备限制校验。框架维护拖动及落点预览，`UiProtocolSession` 负责请求结果和超时；模块负责把意图转换为已注册请求并展示业务结果，服务端负责最终事务。

| 结果 | UI 行为 |
|---|---|
| 本地落点无效 | 不发送，显示不可放置原因 |
| 服务端拒绝 | 清理临时态，显示原因，以权威库存为准 |
| 服务端接受 | 根据该请求约定等待或消费对应状态更新 |
| 请求超时 | 显示未确认并同步状态，不自动再次移动或消费 |
| 容器失效或断线 | 取消旧交互，禁止继续使用旧物品身份 |

## 快照、增量与线程

`InventoryDelta(streamId, baseRevision, revision, containers, removedInstances, upserts)` 按实例删除或替换物品；`containers=null` 沿用当前目录，否则提供完整新目录。`state.applyDelta(delta, requestFullSnapshot)` 在基础版本精确匹配时原子应用；不匹配返回 false 并调用完整同步回调，无效占位抛错且不修改原状态。

每条状态流应有清晰的身份和 revision。完整快照原子替换对应状态；增量仅在 base revision 匹配时应用，发现缺口先获取完整快照。窗口和 HUD 不分别消费同一条增量来维护各自的库存副本。

网络消息解码后，状态提交和 UI 通知通过客户端执行器串行执行。排队任务携带连接代数，旧连接消息不能写入新连接状态。

每个窗口持有自己的订阅句柄，在生命周期结束时释放。布局重建只重建视图，不重新执行消费、移动或会话创建动作。

相关文档：[服务器通信](Server-Communication.md)、[HUD 与槽位栏](HUD-and-Slot-Bars.md)、[故障排查](Troubleshooting.md)。

## 模型相机与物品外观

`ModelPreviewComponent.camera()` 返回 `ModelPreviewCamera`：左键拖动控制水平角和俯仰并接管自动旋转，右键切换自动旋转，中键复位，滚轮缩放。模块也可调用 `focus(normalizedPoint, zoom, yawDegrees, pitchDegrees, entrance)` 平滑聚焦；焦点基于模型包围盒归一化，角度单位为度。普通过渡 0.65 秒、入场 1.35 秒，`settle()` 直接到达目标。相机只控制表现，不修改模型或领域状态。

物品网格调用 `ItemPresentation.draw(context, item, count, rotated, x, y, width, height, colors)`，默认绘制会让图标跟随放置方向旋转，数量保持水平。模块可覆盖该方法，使用 `ItemDrawing.drawStack(context, stack, x, y, width, height)` 绘制原版模型、光泽和数量／耐久叠加；框架不会替模块决定物品与 ItemStack 的对应关系。

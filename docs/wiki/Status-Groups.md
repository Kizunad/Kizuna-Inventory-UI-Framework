# 状态效果分类与合并

适用于当前未发布的 `0.1.0-SNAPSHOT`。分类表、纯计算合并规则和客户端资源重载已实现；图标排在哪、怎么展开见 [状态效果图标](Status-Icons.md)。

## 分类文件

在资源包或模组资源中放置 `assets/<命名空间>/status_groups/<路径>.json`。类 ID 由文件位置决定，例如 `assets/example/status_groups/common.json` 对应 `example:common`，子目录也属于路径。

```json
{
  "effects": ["example:effect_a", "example:effect_b"],
  "group": {
    "name": "status_group.example.common",
    "icon": "example:status_group/common",
    "merge_order": 40,
    "display_order": 20
  }
}
```

`effects` 声明效果 ID，不是效果实例或翻译键。`group.name` 是类名称的翻译键；`group.icon` 是 GUI 图集中的 sprite ID，不写 `.png` 文件路径。翻译和图标资源由声明方提供。

`merge_order` 决定超过容量时先合并哪一类，`display_order` 决定类在结果列表中的位置，二者独立，都是 int 范围内的整数，数值小的在前。优先级相同时按类 ID 的字典序。

`effects` 和 `group` 可以各自省略，但不能同时省略。提供 `group` 时必须给齐其中四个字段。只追加成员的文件可以写成：

```json
{
  "effects": ["another_mod:effect_c"]
}
```

追加文件放在同一个类的位置，即 `assets/example/status_groups/common.json`。客户端会读取该位置所有资源包里的文件，按底层包到顶层包合并成员，而不是只读最上面的一份；同类重复成员只算一次。客户端资源重载时重读分类。

## 坏声明与冲突

每个问题都记录类 ID 和原因，并逐条写警告日志，其余有效文件继续使用。

- 字段类型错误、JSON 损坏或类／效果／图标 ID 不合法：跳过整份文件，不保留其中一部分成员或定义。ID 必须显式写成小写的 `命名空间:路径`。
- 类有成员却没有任何有效定义：不登记这些成员的归属，按效果自身的好坏属性兜底；若另一个有效类也声明了该效果，则仍可归入那个类。
- 多份文件定义同一类：使用最底层的有效定义，所有有效文件的成员仍然累加。
- 多个有效类声明同一效果：归入类 ID 字典序最小的类，不看资源包、合并或显示优先级。

未声明效果使用以下内置类，名称键为 `status_group.kizuna_inventory_ui.<路径>`，sprite 为 `kizuna_inventory_ui:status_group/<路径>`：

| 效果属性 | 类 ID | 合并顺序 | 显示顺序 |
|---|---|---|---|
| 减益 | `kizuna_inventory_ui:harmful` | 70 | 40 |
| 其他 | `kizuna_inventory_ui:neutral` | 65 | 85 |
| 增益 | `kizuna_inventory_ui:beneficial` | 60 | 90 |

## 纯计算入口

公共类型都在 `dev.kizuna.inventoryui.status`。`StatusEffectRef(String id, StatusCategory category)` 只描述正在生效的效果身份和兜底属性，不持有 Minecraft 对象。属性可选 `BENEFICIAL`、`HARMFUL`、`NEUTRAL`。

`StatusGroupTable.parse(Map<String, List<String>> sources)` 接收“类 ID → 该位置从底层包到顶层包的文件全文列表”，返回 `ParseResult`；`table()` 是不可变分类表，`problems()` 是问题列表，每条有 `groupId()` 和 `reason()`。`StatusGroupTable.empty()` 返回只使用兜底类的空表，`table.groupOf(effect)` 查询有效类或兜底类。

客户端通过 `StatusGroups.current()` 获取最近一次重载完成的表，首次重载前是空表。可以缓存一次表引用完成整次计算，避免计算中混用不同重载版本。

调用 `StatusArrangement.arrange(List<StatusEffectRef> effects, int capacity, StatusGroupTable table)` 返回不可变的 `List<StatusArrangement.Slot>`：

- `Single(effect)`：一个效果，调用方按 ID 查回实际实例和剩余时间。
- `Merged(group, effects)`：一个类及其成员，成员顺序与传入顺序一致。
- `Overflow(slots)`：最后一格收纳的其余格子，保留其中的单项和已合并类。

## 合并规则

先按类的显示顺序排列，同类效果保持输入顺序。效果总数小于或等于容量时全部返回 `Single`，刚好等于容量也不合并。

超过容量时，按合并顺序逐类尝试，每合并一类便重新计数，够放就立即停止。只有至少两个成员的类才会合并；只有一个成员的类始终保留为 `Single`。结果仍按显示顺序排列。

所有能合的类都合完仍超出容量时，保留前 `capacity - 1` 个格子，最后一格返回 `Overflow`，装下全部剩余格子。容量为 1 时可能只返回一个 `Single`、一个 `Merged`，或一个收纳全部结果的 `Overflow`。容量小于或等于零返回空列表。

同一次调用不能传入重复效果 ID；即使类别不同或容量为零，也会抛出 `IllegalArgumentException`。调用方负责把游戏里的效果实例转换为唯一 ID 列表，并根据实际可用空间计算容量。

相关文档：[HUD 与槽位栏](HUD-and-Slot-Bars.md)、[库存与状态](Inventory-and-State.md)。

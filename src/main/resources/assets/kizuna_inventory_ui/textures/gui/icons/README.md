# 新版界面的窗口按钮图标

三个目录对应三种图标风格（`item` 物品、`glass_line` 琉璃细线、`ink` 水墨），每套五张 28×28 PNG，以 7×7 逻辑像素原色显示：

| 文件 | 按钮 |
| --- | --- |
| `collapse.png` | 收小 |
| `expand.png` | 小窗展开；由同一套的 `collapse.png` 上下翻转得到 |
| `lock.png` | 已锁到游戏画面 |
| `unlock.png` | 未锁 |
| `close.png` | 关闭 |

运行时按「风格 + 图标名」找 `textures/gui/icons/<风格>/<图标名>.png`；某套缺图时报错，不换成别的风格。

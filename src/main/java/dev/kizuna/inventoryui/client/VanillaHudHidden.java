package dev.kizuna.inventoryui.client;

/**
 * 屏幕标记：实现此接口的 Minecraft Screen 打开时，框架跳过原版 HUD 绘制。
 *
 * <p>包括物品栏、准星、生命值、经验、聊天、计分板及状态提示；不修改玩家的 F1 设置。 离开该屏幕后按当前游戏设置正常绘制 HUD。扩展自己的全屏库存页面可实现此接口。
 */
public interface VanillaHudHidden {}

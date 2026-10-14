package dev.kizuna.inventoryui.wiresmoke;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.client.VanillaHudHidden;
import dev.kizuna.inventoryui.theme.ThemeTokens;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;

/** 用真实游戏世界和帧缓冲验证整个 HUD 绘制被跳过，防止只隐藏准星而遗漏物品栏。 */
final class HudVisibilityProbe extends Screen implements VanillaHudHidden {
    private boolean verified;

    HudVisibilityProbe() {
        super(Text.literal("HUD visibility probe"));
    }

    boolean verified() {
        return verified;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 先填满同一画布，直接调用真实 InGameHud；被标记屏幕下不得改变任何像素。
        if (verified) {
            return;
        }
        if (client.options.hudHidden) {
            throw new IllegalStateException("HUD probe requires F1 to be off");
        }
        context.fill(0, 0, width, height, InventoryUiClient.runtime().color(ThemeTokens.PANEL));
        context.draw();
        try (var before = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
            client.inGameHud.render(context, delta);
            context.draw();
            try (var hidden = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
                if (different(before, hidden)) {
                    throw new IllegalStateException(
                            "vanilla HUD rendered through inventory screen");
                }
            }
            // 仅在验收期间临时改变屏幕引用，避免触发切屏生命周期；finally 保证异常也恢复。
            Screen original = client.currentScreen;
            try {
                client.currentScreen = null;
                client.inGameHud.render(context, delta);
                context.draw();
                try (var visible = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
                    if (!different(before, visible)) {
                        throw new IllegalStateException(
                                "vanilla HUD did not return after leaving screen");
                    }
                }
            } finally {
                client.currentScreen = original;
            }
        }
        if (client.options.hudHidden) {
            throw new IllegalStateException("inventory screen changed the F1 setting");
        }
        verified = true;
    }

    private static boolean different(NativeImage left, NativeImage right) {
        // 比较整帧而不是固定准星坐标，覆盖不同 GUI 缩放下的所有原版 HUD 元素。
        for (int y = 0; y < left.getHeight(); y++) {
            for (int x = 0; x < left.getWidth(); x++) {
                if (left.getColor(x, y) != right.getColor(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }
}

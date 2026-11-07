package dev.kizuna.inventoryui.demo;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.theme.BuiltinThemes;
import dev.kizuna.inventoryui.workspace.WorkspacePreferences;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** 真实外观菜单选择、持久化恢复与主题按钮画面。 */
final class ThemeJourney {
    private static final int THEME_TICKS = 10;
    private static final int SCREENSHOT_TICK = 5;
    private static final List<FrameworkCatalog.Theme> THEMES = List.of(
            BuiltinThemes.INK_JADE, BuiltinThemes.XUAN_PAPER, BuiltinThemes.XUAN_HUANG);
    private final Path preferences;

    ThemeJourney(Minecraft client) {
        // 偏好文件仅位于隔离的 smoke 目录，不读写玩家配置。
        preferences = client.gameDirectory.toPath().resolve("theme-smoke.json");
        InventoryUiClient.runtime().loadPreferences(preferences);
        DemoClient.clickButton(client.gui.screen(), "外观");
    }

    void tick(Minecraft client, int elapsed) {
        int scenario = elapsed / THEME_TICKS;
        if (scenario >= THEMES.size()) {
            return;
        }
        var theme = THEMES.get(scenario);
        if (elapsed % THEME_TICKS == 0) {
            DemoClient.clickButton(client.gui.screen(), "主题：" + theme.title());
            // 工作台关闭时保存整份表现偏好，与玩家正常离开 UI 的生命周期一致。
            client.gui.setScreen(null);
            verifySavedTheme(theme.id());
            InventoryUiClient.runtime().openWorkspace();
            DemoClient.clickButton(client.gui.screen(), "外观");
        }
        if (elapsed % THEME_TICKS == SCREENSHOT_TICK) {
            Screenshot.grab(client.gameDirectory, "theme-menu-" + scenario + ".png",
                    client.gameRenderer.mainRenderTarget(), 1, message -> {});
        }
    }

    private void verifySavedTheme(String expected) {
        // 从磁盘重读，再重载客户端偏好；只检查内存选中值不能证明重启可恢复。
        try {
            String saved = new WorkspacePreferences(preferences).load().theme();
            if (!expected.equals(saved)) {
                throw new IllegalStateException("主题未保存：" + saved);
            }
            var runtime = InventoryUiClient.runtime();
            runtime.loadPreferences(preferences);
            if (!expected.equals(runtime.theme())) {
                throw new IllegalStateException("主题未恢复：" + runtime.theme());
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}

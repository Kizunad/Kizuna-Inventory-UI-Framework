package dev.kizuna.inventoryui.client;

import static dev.kizuna.inventoryui.client.WorkspaceMetrics.*;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.theme.BuiltinThemes;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.inventoryui.window.UiWindowManager;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.inventoryui.workspace.UiVersion;
import dev.kizuna.inventoryui.workspace.WorkspaceController;
import dev.kizuna.inventoryui.workspace.WorkspacePreferences.Snapshot;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/** 旧版界面：整屏工作台、底栏卡片、整窗锁到游戏画面。布局用偏好里原有的 windows 和 pinnedWindows。 */
final class ClassicEdition extends UiEdition {
    private final Set<String> pinnedWindows = new LinkedHashSet<>();

    ClassicEdition(ClientRuntime runtime, FrameworkCatalog catalog, WorkspaceController workspace) {
        super(runtime, catalog, workspace, BuiltinThemes.INK_JADE.id());
    }

    @Override
    UiVersion version() {
        return UiVersion.CLASSIC;
    }

    @Override
    WindowLayer layer(Screen host, Rect viewport) {
        return new WindowLayer(runtime, host, viewport);
    }

    @Override
    Screen workspaceScreen() {
        return new WorkspaceScreen(runtime);
    }

    @Override
    boolean ownsScreen(Screen screen) {
        return screen instanceof WorkspaceScreen;
    }

    @Override
    int headerHeight() {
        return WINDOW_HEADER_HEIGHT;
    }

    @Override
    int reservedBottom() {
        return runtime.bottomBarHeight();
    }

    @Override
    void load(Snapshot saved) {
        pinnedWindows.addAll(saved.pinnedWindows());
        if (catalog.themes().containsKey(saved.theme())) {
            theme = saved.theme();
        }
    }

    @Override
    Map<String, Rect> savedWindows(Snapshot saved) {
        return saved.windows();
    }

    @Override
    Snapshot store(Snapshot saved, Map<String, Rect> windows) {
        return saved.withWindows(windows).withPinnedWindows(pinnedWindows);
    }

    @Override
    boolean opened(String feature, WindowState state) {
        return true;
    }

    @Override
    void viewCreated(WindowState state) {
        workspace
                .windows()
                .pin(state.key(), pinnedWindows.contains(state.key().windowType()));
    }

    @Override
    boolean pin(WindowKey key, boolean pinned) {
        // 保存窗口类型的显示偏好，不持久化服务器对象或跨连接窗口实例。
        if (!workspace.windows().pin(key, pinned)) {
            return false;
        }
        if (pinned) {
            pinnedWindows.add(key.windowType());
        } else {
            pinnedWindows.remove(key.windowType());
        }
        return true;
    }

    @Override
    void renderWindow(
            GuiGraphicsExtractor context,
            UiWindowManager.WindowState state,
            UiWindowManager.Rect bounds,
            boolean controls,
            int mouseX,
            int mouseY,
            float delta) {
        // 外框和内容共用窗口生命周期；HUD 路径隐藏按钮，并禁用悬停输入。
        // 每个窗口独占一个绘制层，后绘制的窗口整体压在先前窗口及其 tooltip 之上。
        context.nextStratum();
        WindowChrome.render(context, runtime, state, bounds, controls, mouseX, mouseY);
        var view = runtime.view(state.key());
        if (view != null && bounds.height() > WINDOW_HEADER_HEIGHT + WINDOW_BORDER) {
            view.render(context, bounds, mouseX, mouseY, delta);
        }
        if (controls) {
            // 角标与拖动命中使用同一尺寸，内容铺满时仍明确展示缩放入口。
            context.text(
                    Minecraft.getInstance().font,
                    "◢",
                    bounds.x() + bounds.width() - RESIZE_GRIP_SIZE,
                    bounds.y() + bounds.height() - RESIZE_GRIP_SIZE,
                    runtime.color(ThemeTokens.ACCENT),
                    false);
        }
    }

    @Override
    void renderHudWindows(GuiGraphicsExtractor context, int width, int height, float delta) {
        // 工作台已经绘制窗口；普通游戏 HUD 才渲染固定实例，不创建第二棵组件树。
        if (runtime.layer(Minecraft.getInstance().gui.screen()).isEmpty()) {
            for (var state : workspace.windowBar()) {
                if (state.hudVisible()) {
                    var source = state.bounds();
                    int w = Math.min(width, source.width());
                    int h = Math.min(height, source.height());
                    var bounds =
                            new UiWindowManager.Rect(
                                    Math.max(0, Math.min(source.x(), width - w)),
                                    Math.max(0, Math.min(source.y(), height - h)),
                                    w,
                                    h);
                    renderWindow(context, state, bounds, false, -1, -1, delta);
                }
            }
        }
    }
}

package dev.kizuna.inventoryui.client;

import static dev.kizuna.inventoryui.client.ModernMetrics.*;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.theme.BuiltinThemes;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.inventoryui.workspace.IconStyle;
import dev.kizuna.inventoryui.workspace.UiVersion;
import dev.kizuna.inventoryui.workspace.WorkspaceController;
import dev.kizuna.inventoryui.workspace.WorkspacePreferences.Snapshot;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/** 新版界面：琉璃外框，登记了摘要的窗口可以收成小窗并锁到游戏画面。布局存在偏好的 modern 一项里。 */
final class ModernEdition extends UiEdition {
    final CompactWindows compact;
    private final Set<String> migratedPins = new LinkedHashSet<>();
    private IconStyle icons = IconStyle.DEFAULT;
    private Snapshot loaded = Snapshot.empty();
    private List<CompactWindows.Projection> hudFrame = List.of();

    ModernEdition(ClientRuntime runtime, FrameworkCatalog catalog, WorkspaceController workspace) {
        super(runtime, catalog, workspace, BuiltinThemes.GLASS.id());
        compact = new CompactWindows(this);
        // 没登记摘要的窗口收小后无处可画，窗口模型直接拒绝。
        workspace.windows().restrictMinimize(compact.providers::containsKey);
    }

    @Override
    UiVersion version() {
        return UiVersion.MODERN;
    }

    @Override
    WindowLayer layer(Screen host, Rect viewport) {
        return new ModernWindowLayer(runtime, host, viewport);
    }

    @Override
    Screen workspaceScreen() {
        return new ModernWorkspaceScreen(runtime);
    }

    @Override
    boolean ownsScreen(Screen screen) {
        return screen instanceof ModernWorkspaceScreen;
    }

    @Override
    int headerHeight() {
        return WINDOW_HEADER_HEIGHT;
    }

    @Override
    int reservedBottom() {
        return 0;
    }

    @Override
    void load(Snapshot saved) {
        loaded = saved;
        compact.placements.putAll(saved.modern().compact());
        migratedPins.addAll(saved.modern().migratedPins());
        icons = saved.modern().icons();
    }

    @Override
    Map<String, Rect> savedWindows(Snapshot saved) {
        return saved.modern().windows();
    }

    @Override
    Snapshot store(Snapshot saved, Map<String, Rect> windows) {
        return saved.withModern(new Snapshot.Modern(windows, compact.placements, migratedPins, icons));
    }

    @Override
    boolean opened(String feature, WindowState state) {
        // 手动打开也走只读观察；权限被拒时窗口随即关闭。
        compact.track(feature, state);
        compact.retry(feature);
        compact.tick(System.nanoTime());
        return !state.closed();
    }

    @Override
    void viewCreated(WindowState state) { }

    @Override
    boolean pin(WindowKey key, boolean pinned) {
        if (!workspace.windows().pin(key, pinned)) {
            return false;
        }
        workspace.windowBar().stream()
                .filter(state -> state.key().equals(key))
                .findFirst()
                .ifPresent(compact::pin);
        return true;
    }

    IconStyle icons() {
        return icons;
    }

    void icons(IconStyle style) {
        icons = style;
    }

    /** 这个窗口能不能收小和锁：登记了摘要，并且是玩家可以改布局的窗口。 */
    boolean canCompact(WindowState state) {
        return compact.providers.containsKey(state.key().windowType()) && state.canPin() && !state.closed();
    }

    /** 后台恢复只开窗口模型，放在层序最底下；不建展开组件，也不调用入口动作。 */
    WindowState restoreModel(String feature) {
        var definition = catalog.windows().get(catalog.features().get(feature).windowType()).definition();
        var key = workspace.windows().key(definition.windowType(), "singleton");
        return workspace.windows().openBehind(definition, key, savedWindows(loaded).get(definition.windowType()));
    }

    @Override
    void renderWindow(
            GuiGraphicsExtractor context,
            WindowState state,
            Rect bounds,
            boolean controls,
            int mouseX,
            int mouseY,
            float delta) {
        // 每个窗口独占一个绘制层，后绘制的窗口整体压在先前窗口及其 tooltip 之上。
        context.nextStratum();
        ModernChrome.render(context, runtime, state, bounds, controls, mouseX, mouseY);
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
        for (var projection : hudFrame) {
            context.nextStratum();
            compact.draw(context, projection.state(), projection.bounds(), delta);
        }
    }

    @Override
    List<Rect> beginHudFrame(int width, int height) {
        // 每次 HUD 提交前解算一次；状态图标和摘要绘制读取同一份不可变矩形。
        hudFrame = hudProjections(width, height);
        return hudFrame.stream().map(CompactWindows.Projection::bounds).toList();
    }

    List<CompactWindows.Projection> hudProjections(int width, int height) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.gui.screen() != null || client.gui.hud.isHidden()) {
            return List.of();
        }
        return compact.hud(width, height);
    }

    @Override
    void resetLayout() {
        compact.resetLayout();
    }

    @Override
    void viewport(Rect viewport) {
        // 旧版锁过的窗口类型各迁一次；旧版自己的 pinnedWindows 不动，之后两边各管各的。
        compact.migrate(loaded.pinnedWindows(), migratedPins, loaded.windows(), viewport);
    }

    @Override
    void connected() {
        compact.connected();
    }

    @Override
    void released() {
        // 先作废观察身份，随后关窗的清理就不会把锁一起撤掉。
        hudFrame = List.of();
        compact.disconnect();
    }

    @Override
    void tick(long now) {
        compact.tick(now);
    }
}

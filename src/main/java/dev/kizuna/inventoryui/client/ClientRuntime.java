package dev.kizuna.inventoryui.client;

import static dev.kizuna.inventoryui.client.WorkspaceMetrics.*;

import dev.kizuna.inventoryui.client.component.ItemDrawing;
import dev.kizuna.inventoryui.contract.DefaultUiScreenScope;
import dev.kizuna.inventoryui.contract.UiViewport;
import dev.kizuna.inventoryui.hud.HudLayout;
import dev.kizuna.inventoryui.inventory.InventoryDragSession;
import dev.kizuna.inventoryui.protocol.UiProtocolSession;
import dev.kizuna.inventoryui.protocol.UiWire;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.theme.BuiltinThemes;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.inventoryui.window.UiWindowManager;
import dev.kizuna.inventoryui.workspace.WorkspaceController;
import dev.kizuna.inventoryui.workspace.WorkspacePreferences;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Fabric 客户端的组合根；状态、内容和业务动作由子模组提供。所有调用限定客户端线程。 */
public final class ClientRuntime {
    private final FrameworkCatalog catalog;
    private final WorkspaceController workspace;
    private final HudLayout hudLayout;
    private final List<ClientModule> modules;
    private final Map<String, ClientBindings.WindowFactory> factories = new LinkedHashMap<>();
    private final Map<String, ClientBindings.HudRenderer> renderers = new LinkedHashMap<>();
    private final Map<String, Function<FrameworkCatalog.Slot, ClientBindings.SlotContent>> bars =
            new LinkedHashMap<>();
    private final Map<UiWindowManager.WindowKey, OwoWindowView> views = new LinkedHashMap<>();
    private String theme = BuiltinThemes.DARK.id();
    private String background = "kiui:cosmos";
    private boolean motion = true;
    private final Set<String> pinnedWindows = new LinkedHashSet<>();
    private final InventoryDragSession drag = new InventoryDragSession();
    private final ItemContextMenu contextMenu = new ItemContextMenu();
    private WorkspacePreferences preferences;
    private WorkspacePreferences.Snapshot saved = WorkspacePreferences.Snapshot.empty();
    private String preferenceError = "";
    private boolean themeChanged;
    private boolean backgroundChanged;
    private final UiProtocolSession protocol;
    private final dev.kizuna.inventoryui.client.svg.SvgRenderer svg;
    private Consumer<UiWire.Packet> transport =
            packet -> {
                throw new IllegalStateException("UI transport is not connected");
            };
    private String protocolError = "";
    private LocalBackgrounds localBackgrounds;

    public dev.kizuna.inventoryui.client.svg.SvgRenderer svg() {
        return svg;
    }

    public UiProtocolSession protocol() {
        return protocol;
    }

    public String protocolError() {
        return protocolError;
    }

    public void receive(long generation, UiWire.Packet packet) {
        // 只在协商状态首次转为就绪时通知模块，重复 ACCEPT 不会重复订阅状态。
        var before = protocol.state();
        protocol.receive(generation, packet);
        if (before != UiProtocolSession.State.READY
                && protocol.state() == UiProtocolSession.State.READY) {
            modules.forEach(module -> module.protocolReady(this));
        }
    }

    public void transport(Consumer<UiWire.Packet> transport) {
        // 宿主适配器可替换 Fabric 传输；所有入站状态处理仍在客户端线程完成。
        this.transport = Objects.requireNonNull(transport);
    }

    public InventoryDragSession drag() {
        return drag;
    }

    public ItemContextMenu contextMenu() {
        return contextMenu;
    }

    public String preferenceError() {
        return preferenceError;
    }

    public void loadPreferences(Path path) {
        // 偏好加载失败只降级为默认外观，同时保留错误和原文件供用户修复。
        configureBackgroundDirectory(
                path.toAbsolutePath().getParent().resolve("kizuna-inventory-ui/backgrounds"));
        preferences = new WorkspacePreferences(path);
        try {
            saved = preferences.load();
            motion = saved.motion();
            pinnedWindows.addAll(saved.pinnedWindows());
            if (catalog.themes().containsKey(saved.theme())) {
                theme = saved.theme();
            }
            if (java.nio.file.Files.exists(path)
                    && (saved.background() == null
                            || catalog.backgrounds().containsKey(saved.background()))) {
                background = saved.background();
            }
            if (saved.background() != null
                    && saved.background().startsWith("local:")
                    && localBackgrounds.select(saved.background())) {
                background = saved.background();
            }
            saved.huds()
                    .forEach(
                            (id, placement) -> {
                                if (catalog.huds().containsKey(id)) {
                                    hudLayout.place(id, placement);
                                }
                            });
            saved.pinnedFeatures()
                    .forEach(
                            id -> {
                                if (catalog.features().containsKey(id)) {
                                    workspace.pinFeature(id, true);
                                }
                            });
        } catch (IOException failure) {
            preferenceError = failure.getMessage();
            System.getLogger(ClientRuntime.class.getName())
                    .log(System.Logger.Level.WARNING, preferenceError);
        }
    }

    public void savePreferences() {
        // 保留暂时缺失模块的偏好；已安装模块的固定入口则以用户当前选择为准。
        var windows = new LinkedHashMap<>(saved.windows());
        workspace
                .windowBar()
                .forEach(state -> windows.put(state.key().windowType(), state.desiredBounds()));
        var huds = new LinkedHashMap<>(saved.huds());
        catalog.huds().keySet().forEach(id -> huds.put(id, hudLayout.placement(id)));
        var pins = new LinkedHashSet<>(saved.pinnedFeatures());
        pins.removeAll(catalog.features().keySet());
        pins.addAll(workspace.pinnedFeatures());
        String themePreference =
                !themeChanged
                                && saved.theme() != null
                                && !catalog.themes().containsKey(saved.theme())
                        ? saved.theme()
                        : theme;
        String backgroundPreference =
                !backgroundChanged
                                && saved.background() != null
                                && !catalog.backgrounds().containsKey(saved.background())
                        ? saved.background()
                        : background;
        saved =
                new WorkspacePreferences.Snapshot(
                        themePreference,
                        backgroundPreference,
                        windows,
                        huds,
                        pins,
                        motion,
                        pinnedWindows);
        if (preferences != null) {
            try {
                preferences.save(saved);
                preferenceError = "";
            } catch (IOException failure) {
                preferenceError = failure.getMessage();
                System.getLogger(ClientRuntime.class.getName())
                        .log(System.Logger.Level.WARNING, preferenceError);
            }
        }
    }

    public ClientRuntime(List<ClientModule> modules) {
        // 先构建完整声明目录，再按依赖顺序绑定实现，模块不能依赖调用方的传入顺序。
        this.modules = List.copyOf(modules);
        List<FrameworkCatalog.Module> definitions = new ArrayList<>();
        definitions.add(
                FrameworkCatalog.Module.of(
                        "kiui:core",
                        Set.of(),
                        BuiltinThemes.DARK,
                        BuiltinThemes.LIGHT,
                        new FrameworkCatalog.Background(
                                "kiui:cosmos",
                                "kizuna_inventory_ui:textures/gui/workspace/cosmos.png",
                                FrameworkCatalog.Fit.COVER),
                        new FrameworkCatalog.Background(
                                "kiui:terrain",
                                "kizuna_inventory_ui:textures/gui/workspace/terrain.png",
                                FrameworkCatalog.Fit.COVER)));
        this.modules.forEach(
                module -> definitions.add(Objects.requireNonNull(module.definition())));
        catalog = FrameworkCatalog.build(definitions);
        svg = new dev.kizuna.inventoryui.client.svg.SvgRenderer(catalog);
        workspace =
                new WorkspaceController(
                        catalog, UiViewport.MIN_SUPPORTED_WIDTH, UiViewport.MIN_SUPPORTED_HEIGHT);
        hudLayout = new HudLayout(catalog);
        Map<String, ClientModule> byId = new LinkedHashMap<>();
        var messages = new ArrayList<UiWire.Contract<?>>();
        for (int i = 0; i < this.modules.size(); i++) {
            byId.put(definitions.get(i + 1).id(), this.modules.get(i));
        }
        for (String id : catalog.moduleOrder()) {
            ClientModule module = byId.get(id);
            if (module == null) {
                continue;
            }
            var bindings = new ClientBindings(catalog.modules().get(id));
            module.register(bindings);
            // 缺失实现会在发布绑定前失败，避免界面打开后才遇到空绘制器。
            bindings.freeze();
            messages.addAll(bindings.messages);
            factories.putAll(bindings.windows);
            renderers.putAll(bindings.huds);
            bars.putAll(bindings.bars);
            bindings.slotBarHuds.forEach(
                    (hud, bar) ->
                            renderers.put(
                                    hud,
                                    (context, width, height, delta) ->
                                            renderSlotBar(context, bar, width, height)));
        }
        protocol =
                new UiProtocolSession(
                        catalog,
                        messages,
                        packet -> transport.accept(packet),
                        error -> {
                            protocolError = error;
                            System.getLogger(ClientRuntime.class.getName())
                                    .log(System.Logger.Level.WARNING, error);
                        });
    }

    public FrameworkCatalog catalog() {
        return catalog;
    }

    public WorkspaceController workspace() {
        return workspace;
    }

    public HudLayout hudLayout() {
        return hudLayout;
    }

    public String theme() {
        return theme;
    }

    public boolean motion() {
        return motion;
    }

    public void motion(boolean enabled) {
        motion = enabled;
        savePreferences();
    }

    public boolean pinWindow(UiWindowManager.WindowKey key, boolean pinned) {
        // 保存窗口类型的显示偏好，不持久化服务器对象或跨连接窗口实例。
        if (!workspace.windows().pin(key, pinned)) {
            return false;
        }
        if (pinned) {
            pinnedWindows.add(key.windowType());
        } else {
            pinnedWindows.remove(key.windowType());
        }
        savePreferences();
        return true;
    }

    public String background() {
        return background;
    }

    public void theme(String id) {
        // 只切换到已登记主题，拼写错误应在调用处暴露而非静默忽略。
        if (!catalog.themes().containsKey(id)) {
            throw new IllegalArgumentException("unknown theme: " + id);
        }
        theme = id;
        themeChanged = true;
    }

    /** null 表示使用主题底色。 */
    public void background(String id) {
        // null 是明确的关闭背景选择；非空 ID 必须事先注册。
        if (id != null && id.startsWith("local:")) {
            if (localBackgrounds == null || !localBackgrounds.select(id)) {
                throw new IllegalArgumentException(
                        localBackgrounds == null
                                ? "local background directory is not configured"
                                : localBackgrounds.error());
            }
        } else if (id != null && !catalog.backgrounds().containsKey(id)) {
            throw new IllegalArgumentException("unknown background: " + id);
        }
        background = id;
        backgroundChanged = true;
    }

    public void configureBackgroundDirectory(Path directory) {
        // 宿主可为演示或多配置实例提供不同目录，替换目录时释放旧动态纹理。
        if (localBackgrounds != null) {
            localBackgrounds.close();
        }
        localBackgrounds = new LocalBackgrounds(directory);
        localBackgrounds.refresh();
    }

    LocalBackgrounds localBackgrounds() {
        return localBackgrounds;
    }

    void renderBackground(DrawContext context, int width, int height) {
        // 本地图片与模块资源共用选择入口，文件名不进入模块注册表。
        if (background != null && background.startsWith("local:") && localBackgrounds != null) {
            localBackgrounds.render(context, width, height);
        } else {
            WorkspaceBackground.render(
                    context, catalog.backgrounds().get(background), width, height);
        }
    }

    void reloadBackgrounds() {
        // 资源重载失效缩略图；当前图片重新加载失败时仍保留旧纹理。
        WorkspaceBackground.clearCache();
        svg.reload();
        if (localBackgrounds != null) {
            localBackgrounds.refresh();
            if (background != null && background.startsWith("local:")) {
                localBackgrounds.select(background);
            }
        }
    }

    public int color(String token) {
        // 自定义主题可以只覆盖部分令牌，缺项逐级回退到内置深色主题与白色。
        return BuiltinThemes.color(catalog.themes().get(theme), token);
    }

    public UiWindowManager.WindowState open(String feature, String objectId) {
        // 预留下方栏位空间，并错开新窗口的初始位置，减少完全重叠。
        var client = MinecraftClient.getInstance();
        if (!catalog.features().containsKey(feature)) {
            throw new IllegalArgumentException("unknown feature: " + feature);
        }
        workspace
                .windows()
                .resizeViewport(
                        client.getWindow().getScaledWidth(),
                        Math.max(1, client.getWindow().getScaledHeight() - BOTTOM_BAR_HEIGHT));
        int offset = workspace.windowBar().size() % CASCADE_POSITION_COUNT * CASCADE_STEP;
        var state =
                workspace.openFeature(
                        feature,
                        objectId,
                        saved.windows()
                                .getOrDefault(
                                        catalog.features().get(feature).windowType(),
                                        new UiWindowManager.Rect(
                                                INITIAL_WINDOW_X + offset,
                                                INITIAL_WINDOW_Y + offset,
                                                INITIAL_WINDOW_WIDTH,
                                                INITIAL_WINDOW_HEIGHT)));
        if (!views.containsKey(state.key())) {
            // 一个窗口实例只创建一棵组件树；关窗时由同一个 scope 释放视图和订阅。
            try {
                var view = new OwoWindowView(this, state, factories.get(state.key().windowType()));
                views.put(state.key(), view);
                workspace
                        .windows()
                        .pin(state.key(), pinnedWindows.contains(state.key().windowType()));
                state.scope()
                        .addCleanup(
                                () -> {
                                    views.remove(state.key());
                                    contextMenu.close();
                                    view.close();
                                });
            } catch (RuntimeException | Error failure) {
                // 组件构建失败时撤销刚打开的窗口，避免留下无法渲染的空壳。
                workspace.windows().close(state.key());
                throw failure;
            }
        }
        return state;
    }

    public void openWorkspace() {
        MinecraftClient.getInstance().setScreen(new WorkspaceScreen(this));
    }

    public void resetWindowLayout() {
        // 重置表现布局保留窗口与业务订阅，所有已保存的窗口类型尺寸也一并回到默认。
        saved =
                new WorkspacePreferences.Snapshot(
                        saved.theme(),
                        saved.background(),
                        Map.of(),
                        saved.huds(),
                        saved.pinnedFeatures(),
                        motion,
                        pinnedWindows);
        int index = 0;
        for (var window : workspace.windowBar()) {
            int offset = index++ % CASCADE_POSITION_COUNT * CASCADE_STEP;
            workspace
                    .windows()
                    .settleAt(
                            window.key(),
                            new UiWindowManager.Rect(
                                    INITIAL_WINDOW_X + offset,
                                    INITIAL_WINDOW_Y + offset,
                                    INITIAL_WINDOW_WIDTH,
                                    INITIAL_WINDOW_HEIGHT));
        }
        savePreferences();
    }

    public String title(UiWindowManager.WindowState state) {
        // 优先使用关联功能的展示名称，没有入口时使用稳定类型 ID 便于排查。
        return catalog.features().values().stream()
                .filter(feature -> feature.windowType().equals(state.key().windowType()))
                .map(FrameworkCatalog.Feature::title)
                .findFirst()
                .orElse(state.key().windowType());
    }

    OwoWindowView view(UiWindowManager.WindowKey key) {
        return views.get(key);
    }

    void cancelInput() {
        // 窗口拖动和组件内部拖动分别持有状态，两层都要取消。
        workspace.windows().cancelCapture();
        drag.cancel();
        contextMenu.close();
        views.values().forEach(OwoWindowView::cancelInput);
    }

    public ClientBindings.SlotContent slotContent(String barId, FrameworkCatalog.Slot slot) {
        // 先确认槽位属于目标栏，再向模块索取当前内容，防止跨栏错误绑定。
        var bar = catalog.slotBars().get(barId);
        if (bar == null || !bar.slots().contains(slot)) {
            throw new IllegalArgumentException("unknown slot binding: " + barId);
        }
        return Objects.requireNonNull(bars.get(barId).apply(slot), "slot content must not be null");
    }

    void renderWindow(
            DrawContext context,
            UiWindowManager.WindowState state,
            UiWindowManager.Rect bounds,
            boolean controls,
            int mouseX,
            int mouseY,
            float delta) {
        // 外框和内容共用窗口生命周期；HUD 路径隐藏按钮，并禁用悬停输入。
        context.draw();
        com.mojang.blaze3d.systems.RenderSystem.clear(
                org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
        WindowChrome.render(context, this, state, bounds, controls, mouseX, mouseY);
        var view = views.get(state.key());
        if (view != null && bounds.height() > WINDOW_HEADER_HEIGHT + WINDOW_BORDER) {
            view.render(context, bounds, mouseX, mouseY, delta);
        }
        if (controls) {
            // 角标与拖动命中使用同一尺寸，内容铺满时仍明确展示缩放入口。
            context.drawText(
                    MinecraftClient.getInstance().textRenderer,
                    "◢",
                    bounds.x() + bounds.width() - RESIZE_GRIP_SIZE,
                    bounds.y() + bounds.height() - RESIZE_GRIP_SIZE,
                    color(ThemeTokens.ACCENT),
                    false);
        }
        context.draw();
    }

    public void renderHuds(DrawContext context, int width, int height, float delta) {
        // 工作台已经绘制窗口；普通游戏 HUD 才渲染固定实例，不创建第二棵组件树。
        if (!(MinecraftClient.getInstance().currentScreen instanceof WorkspaceScreen)) {
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
        // 固定窗口的 owo 子树可能留下深度，独立 HUD 层必须从清空后的深度开始。
        context.draw();
        com.mojang.blaze3d.systems.RenderSystem.clear(
                org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
        // 每个 HUD 使用独立变换和裁剪，模块只需要在自己的逻辑坐标内绘制。
        for (var hud : catalog.huds().values()) {
            if (!hudLayout.placement(hud.id()).visible()) {
                continue;
            }
            var bounds = hudLayout.bounds(hud.id(), width, height);
            var matrices = context.getMatrices();
            context.enableScissor(
                    bounds.x(),
                    bounds.y(),
                    bounds.x() + bounds.width(),
                    bounds.y() + bounds.height());
            matrices.push();
            try {
                matrices.translate(bounds.x(), bounds.y(), 0);
                float scale = hudLayout.placement(hud.id()).scale();
                matrices.scale(scale, scale, 1);
                renderers.get(hud.id()).render(context, hud.width(), hud.height(), delta);
            } finally {
                // 模块绘制失败也必须还原上下文，避免影响后续 HUD 和游戏界面。
                matrices.pop();
                context.disableScissor();
            }
        }
    }

    public void renderSlotBar(DrawContext context, String barId, int width, int height) {
        // 用注册数量分配格宽；HUD 和工作台读取同一供应器，绑定变化会同时显示。
        var bar = catalog.slotBars().get(barId);
        if (bar == null) {
            throw new IllegalArgumentException("unknown slot bar: " + barId);
        }
        if (bar.slots().isEmpty()) {
            return;
        }
        int cellWidth = Math.max(1, width / bar.slots().size());
        int index = 0;
        var text = MinecraftClient.getInstance().textRenderer;
        for (var slot : bar.slots()) {
            int x = index++ * cellWidth;
            var content = slotContent(barId, slot);
            context.fill(
                    x, 0, x + cellWidth - WINDOW_BORDER, height, color(ThemeTokens.HUD_BACKGROUND));
            if (content.item() != null) {
                ItemDrawing.draw(
                        context,
                        content.item(),
                        content.item().count(),
                        x,
                        0,
                        cellWidth,
                        height,
                        this::color);
            } else {
                context.drawTextWithShadow(
                        text,
                        text.trimToWidth(
                                content.label(), Math.max(1, cellWidth - 2 * WINDOW_BORDER)),
                        x + WINDOW_BORDER,
                        (height - text.fontHeight) / 2,
                        color(ThemeTokens.HUD_TEXT));
            }
        }
    }

    void connected() {
        modules.forEach(module -> module.connected(this));
    }

    void disconnected() {
        savePreferences();
        cancelInput();
        protocol.disconnect();
        // scope 按后进先出清理：先通知模块断线，再清除工作台；某项失败不跳过其余清理。
        var cleanup = new DefaultUiScreenScope();
        cleanup.addCleanup(workspace::disconnect);
        modules.forEach(module -> cleanup.addCleanup(() -> module.disconnected(this)));
        cleanup.close();
    }
}

package dev.kizuna.inventoryui.client;

import static dev.kizuna.inventoryui.client.WorkspaceMetrics.*;

import dev.kizuna.inventoryui.client.component.ItemDrawing;
import dev.kizuna.inventoryui.client.status.StatusLayout.Area;
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
import dev.kizuna.inventoryui.workspace.IconStyle;
import dev.kizuna.inventoryui.workspace.UiVersion;
import dev.kizuna.inventoryui.workspace.WorkspaceController;
import dev.kizuna.inventoryui.workspace.WorkspacePreferences;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Fabric 客户端的组合根；状态、内容和业务动作由子模组提供。所有调用限定客户端线程。 */
public final class ClientRuntime {
    private final ClassicEdition classic;
    private final ModernEdition modern;
    private UiEdition edition;
    private final HudLayout hudLayout;
    private final List<ClientModule> modules;
    private final Map<String, ClientBindings.HudRenderer> renderers = new LinkedHashMap<>();
    private final Map<String, Function<FrameworkCatalog.Slot, ClientBindings.SlotContent>> bars =
            new LinkedHashMap<>();
    private final Map<UiWindowManager.WindowKey, OwoWindowView> views = new LinkedHashMap<>();
    private String background;
    private boolean motion = true;
    private boolean blur;
    private boolean online;
    private List<Area> hudBlocked = List.of();
    private final InventoryDragSession drag = new InventoryDragSession();
    private final ItemContextMenu contextMenu = new ItemContextMenu();
    private final StyledIcons icons = new StyledIcons(ClientRuntime::resourceExists, ClientRuntime::reportMissingIcon);
    private WorkspacePreferences preferences;
    private WorkspacePreferences.Snapshot saved = WorkspacePreferences.Snapshot.empty();
    private String preferenceError = "";
    private boolean backgroundChanged;
    private final UiProtocolSession protocol;
    private final dev.kizuna.inventoryui.client.svg.SvgRenderer svg;
    private static final List<String> CORE_BACKGROUNDS = List.of("night_meridian", "scroll_paper", "alchemy_room",
            "star_meridian", "cosmos", "terrain");
    private Consumer<UiWire.Packet> transport =
            packet -> {
                throw new IllegalStateException("UI transport is not connected");
            };
    private String protocolError = "";
    private LocalBackgrounds localBackgrounds;
    private WindowLayer activeLayer;
    private Screen activeHost;

    /** 在宿主完成布局后绑定；同一屏幕 resize 只更新视口。 */
    public WindowLayer attach(Screen host, UiWindowManager.Rect viewport) {
        Objects.requireNonNull(host);
        Objects.requireNonNull(viewport);
        if (host != activeHost) {
            // 切屏只撤销输入绑定，窗口和业务订阅留在本连接。
            if (activeLayer != null) {
                activeLayer.detach();
            }
            activeLayer = edition.layer(host, viewport);
            activeHost = host;
        } else {
            activeLayer.viewport(viewport);
        }
        edition.viewport(viewport);
        return activeLayer;
    }

    public Optional<WindowLayer> layer(Screen host) {
        if (host == activeHost) {
            return Optional.ofNullable(activeLayer);
        }
        return Optional.empty();
    }

    void unbind(WindowLayer layer) {
        if (layer == activeLayer) {
            activeLayer = null;
            activeHost = null;
            savePreferences();
        }
    }

    void updateHost(Minecraft client) {
        // 宿主可被第三方替换；失焦时保留绑定，切屏时彻底解绑。
        if (activeLayer != null && activeHost != client.gui.screen()) {
            activeLayer.detach();
        } else if (activeLayer != null && !client.isWindowActive()) {
            activeLayer.cancelInput();
        }
    }

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
            blur = saved.blur();
            classic.load(saved);
            modern.load(saved);
            if (java.nio.file.Files.exists(path)) {
                // 还没有偏好文件时不改当前的界面版本。
                activate(saved.uiVersion());
            }
            if (java.nio.file.Files.exists(path)
                    && (saved.background() == null
                            || catalog().backgrounds().containsKey(saved.background()))) {
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
                                if (catalog().huds().containsKey(id)) {
                                    hudLayout.place(id, placement);
                                }
                            });
            saved.pinnedFeatures()
                    .forEach(
                            id -> {
                                for (var each : List.of(classic, modern)) {
                                    if (each.catalog.features().containsKey(id)) {
                                        each.workspace.pinFeature(id, true);
                                    }
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
        var windows = new LinkedHashMap<>(edition.savedWindows(saved));
        for (var state : workspace().windowBar()) {
            // 自动窗口不保存临时小屏尺寸；只有玩家明确调整过的外框才成为固定偏好。
            if (state.automaticBounds()) {
                windows.remove(state.key().windowType());
            } else {
                windows.put(state.key().windowType(), state.savedBounds());
            }
        }
        var huds = new LinkedHashMap<>(saved.huds());
        catalog().huds().keySet().forEach(id -> huds.put(id, hudLayout.placement(id)));
        var pins = new LinkedHashSet<>(saved.pinnedFeatures());
        pins.removeAll(catalog().features().keySet());
        pins.addAll(workspace().pinnedFeatures());
        // 偏好里的 theme 是旧版界面的主题；新版只有自己的一套，不写进去。
        String themePreference =
                !classic.themeChanged
                                && saved.theme() != null
                                && !classic.catalog.themes().containsKey(saved.theme())
                        ? saved.theme()
                        : classic.theme;
        String backgroundPreference =
                !backgroundChanged
                                && saved.background() != null
                                && !catalog().backgrounds().containsKey(saved.background())
                        ? saved.background()
                        : background;
        var shared =
                new WorkspacePreferences.Snapshot(
                        themePreference,
                        backgroundPreference,
                        saved.windows(),
                        huds,
                        pins,
                        motion,
                        saved.pinnedWindows(),
                        edition.version(),
                        saved.modern(),
                        blur);
        // 两版各写各的布局：先带上不在用的那一版原有的字段，再让在用的这一版写入当前窗口。
        saved = edition.store(inactive().store(shared, inactive().savedWindows(saved)), windows);
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
        // 两版界面各有一份目录：模块没有另给新版声明时，两版共用同一份声明和同一套绑定。
        this.modules = List.copyOf(modules);
        List<FrameworkCatalog.Module> definitions = new ArrayList<>();
        List<FrameworkCatalog.Module> modernDefinitions = new ArrayList<>();
        definitions.add(coreModule(BuiltinThemes.INK_JADE, BuiltinThemes.XUAN_PAPER, BuiltinThemes.XUAN_HUANG,
                BuiltinThemes.DARK, BuiltinThemes.LIGHT));
        modernDefinitions.add(coreModule(BuiltinThemes.GLASS));
        for (var module : this.modules) {
            var definition = Objects.requireNonNull(module.definition());
            definitions.add(definition);
            modernDefinitions.add(module.modernDefinition().orElse(definition));
        }
        var catalog = FrameworkCatalog.build(definitions);
        var modernCatalog = FrameworkCatalog.build(modernDefinitions);
        svg = new dev.kizuna.inventoryui.client.svg.SvgRenderer(catalog);
        classic = new ClassicEdition(this, catalog, newWorkspace(catalog));
        modern = new ModernEdition(this, modernCatalog, newWorkspace(modernCatalog));
        edition = edition(UiVersion.DEFAULT);
        hudLayout = new HudLayout(catalog);
        Map<String, ClientModule> byId = new LinkedHashMap<>();
        var messages = new ArrayList<UiWire.Contract<?>>();
        for (int i = 0; i < this.modules.size(); i++) {
            byId.put(definitions.get(i + 1).id(), this.modules.get(i));
        }
        Map<String, ClientBindings> bound = new LinkedHashMap<>();
        for (String id : catalog.moduleOrder()) {
            ClientModule module = byId.get(id);
            if (module == null) {
                continue;
            }
            var bindings = new ClientBindings(catalog.modules().get(id));
            module.register(bindings);
            // 缺失实现会在发布绑定前失败，避免界面打开后才遇到空绘制器。
            bindings.freeze();
            publish(classic, bindings);
            messages.addAll(bindings.messages);
            bound.put(id, bindings);
        }
        for (String id : modernCatalog.moduleOrder()) {
            ClientModule module = byId.get(id);
            if (module == null) {
                continue;
            }
            var bindings = bound.get(id);
            if (module.modernDefinition().isPresent()) {
                var classicBindings = bindings;
                bindings = new ClientBindings(modernCatalog.modules().get(id));
                module.registerModern(bindings);
                bindings.inherit(classicBindings);
                bindings.freeze();
                if (!bindings.messages.isEmpty()) {
                    // 协议消息两版共用，只在 register 里登记一次。
                    throw new IllegalArgumentException("modern bindings must not declare messages: " + id);
                }
            }
            publish(modern, bindings);
            modern.compact.providers.putAll(bindings.summaries);
            modern.compact.slots.putAll(bindings.summarySlots);
            modern.compact.bindings.putAll(bindings.observers);
            modern.windowMenus.putAll(bindings.windowMenus);
        }
        modern.compact.validate();
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

    private static FrameworkCatalog.Module coreModule(FrameworkCatalog.Theme... themes) {
        var entries = new ArrayList<FrameworkCatalog.Entry>(List.of(themes));
        for (String background : CORE_BACKGROUNDS) {
            entries.add(new FrameworkCatalog.Background(
                    "kiui:" + background,
                    "kizuna_inventory_ui:textures/gui/workspace/" + background + ".png",
                    FrameworkCatalog.Fit.COVER));
        }
        return FrameworkCatalog.Module.of("kiui:core", Set.of(), entries.toArray(FrameworkCatalog.Entry[]::new));
    }

    private static WorkspaceController newWorkspace(FrameworkCatalog catalog) {
        return new WorkspaceController(catalog, UiViewport.MIN_SUPPORTED_WIDTH, UiViewport.MIN_SUPPORTED_HEIGHT);
    }

    private void publish(UiEdition target, ClientBindings bindings) {
        // 窗口工厂、入口权限和入口动作按版本分开；独立 HUD 和槽位栏不分版本。
        bindings.featureAccess.forEach(target.workspace::featureAccess);
        target.factories.putAll(bindings.windows);
        target.featureOpened.putAll(bindings.featureOpened);
        renderers.putAll(bindings.huds);
        bars.putAll(bindings.bars);
        bindings.slotBarHuds.forEach(
                (hud, bar) ->
                        renderers.put(
                                hud,
                                (context, width, height, delta) ->
                                        renderSlotBar(context, bar, width, height)));
    }

    private UiEdition edition(UiVersion version) {
        return switch (version) {
            case CLASSIC -> classic;
            case MODERN -> modern;
        };
    }

    private UiEdition inactive() {
        if (edition == classic) {
            return modern;
        }
        return classic;
    }

    /** 新版界面的表现；小窗的登记和排位都在它下面。 */
    ModernEdition modern() {
        return modern;
    }

    public UiVersion uiVersion() {
        return edition.version();
    }

    /**
     * 切到另一版界面。当前版的布局先存下，它开着的窗口全部关闭（锁和位置保留），再换成目标版自己的目录、
     * 窗口模型和主题；连接和模块的业务状态不动。整屏界面开着时换成目标版的整屏界面。
     */
    public void uiVersion(UiVersion target) {
        if (target == edition.version()) {
            return;
        }
        boolean workspaceOpen = activeHost != null && edition.ownsScreen(activeHost);
        savePreferences();
        if (activeLayer != null) {
            activeLayer.detach();
        }
        cancelInput();
        activate(target);
        if (online) {
            edition.viewport(gameViewport());
            edition.connected();
        }
        savePreferences();
        if (workspaceOpen) {
            Minecraft.getInstance().gui.setScreen(edition.workspaceScreen());
        }
    }

    private void closeWindows() {
        edition.released();
        for (var state : List.copyOf(workspace().windowBar())) {
            workspace().windows().close(state.key());
        }
    }

    private void activate(UiVersion version) {
        if (edition.version() != version) {
            closeWindows();
            edition = edition(version);
        }
    }

    /** 这块屏幕是不是某一版自己的整屏界面。 */
    boolean ownsWorkspace(Screen screen) {
        return classic.ownsScreen(screen) || modern.ownsScreen(screen);
    }

    /** 当前版标题栏的高度；窗口内容区从它下面开始。 */
    int headerHeight() {
        return edition.headerHeight();
    }

    /** 窗口正文要这么大的地方：加上标题栏和边框交给窗口管理器，规则见 UiWindowManager.fit。 */
    public boolean fitContent(UiWindowManager.WindowKey key, int width, int height) {
        return workspace().windows().fit(key, width + 2 * WINDOW_BORDER, height + headerHeight() + WINDOW_BORDER);
    }

    /** 新版界面的图标风格；旧版界面不读它。 */
    public IconStyle iconStyle() {
        return modern.icons();
    }

    public void iconStyle(IconStyle style) {
        modern.icons(Objects.requireNonNull(style));
        savePreferences();
    }

    /**
     * 当前图标风格下某个图标的贴图。图标 ID 的命名空间是出图的模块，路径是图标名；
     * 这种风格没有这张图时报错并照样返回它的路径，画出来是缺图的格子。
     */
    public Identifier icon(Identifier icon) {
        return icons.texture(modern.icons(), icon);
    }

    /** 当前资源里查过确实没有的图标贴图。 */
    public Set<Identifier> missingIcons() {
        return icons.missing();
    }

    void reloadIcons() {
        icons.reload();
    }

    private static boolean resourceExists(Identifier texture) {
        return Minecraft.getInstance().getResourceManager().getResource(texture).isPresent();
    }

    private static void reportMissingIcon(String message) {
        // 日志里留一条错误；人在游戏里时聊天栏也提示一次。
        System.getLogger(ClientRuntime.class.getName()).log(System.Logger.Level.ERROR, message);
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }

    public boolean blur() {
        return blur;
    }

    public void blur(boolean enabled) {
        blur = enabled;
        savePreferences();
    }

    /**
     * 模块发现连接断开时先调用：之后模块自己关窗不算玩家撤销，新版锁着的小窗留到下次登录恢复。
     * 运行时自己的断线清理也会做同一件事，重复调用无妨。
     */
    public void connectionClosing() {
        edition.released();
    }

    /** 模块在数据或权限变化后调用，让等着的只读恢复重新检查。 */
    public void recheckRestores() {
        edition.tick(System.nanoTime());
    }

    public boolean restoreRetryable(String feature) {
        return modern.compact.retryable(feature);
    }

    public void retryRestore(String feature) {
        modern.compact.retry(feature);
        recheckRestores();
    }

    public FrameworkCatalog catalog() {
        return edition.catalog;
    }

    public WorkspaceController workspace() {
        return edition.workspace;
    }

    public HudLayout hudLayout() {
        return hudLayout;
    }

    public String theme() {
        return edition.theme;
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
        if (!edition.pin(key, pinned)) {
            return false;
        }
        savePreferences();
        return true;
    }

    public String background() {
        return background;
    }

    public void theme(String id) {
        // 只切换到已登记主题，拼写错误应在调用处暴露而非静默忽略。
        if (!catalog().themes().containsKey(id)) {
            throw new IllegalArgumentException("unknown theme: " + id);
        }
        edition.theme = id;
        edition.themeChanged = true;
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
        } else if (id != null && !catalog().backgrounds().containsKey(id)) {
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

    void renderBackground(GuiGraphicsExtractor context, int width, int height) {
        // 本地图片与模块资源共用选择入口，文件名不进入模块注册表。
        if (background != null && background.startsWith("local:") && localBackgrounds != null) {
            localBackgrounds.render(context, width, height);
        } else {
            WorkspaceBackground.render(
                    context, catalog().backgrounds().get(background), width, height);
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
        return BuiltinThemes.color(catalog().themes().get(edition.theme), token);
    }

    int bottomBarHeight() {
        // 只有最小化窗口需要底部恢复入口；已展开窗口和注册槽位不占这块空间。
        boolean hasMinimized =
                workspace().windowBar().stream().anyMatch(UiWindowManager.WindowState::minimized);
        return hasMinimized ? MINIMIZED_BAR_HEIGHT : 0;
    }

    public UiWindowManager.WindowState open(String feature, String objectId) {
        // 为已有最小化窗口留位；新窗口展开显示，不再创建额外的底部页签。
        var client = Minecraft.getInstance();
        if (!catalog().features().containsKey(feature)) {
            throw new IllegalArgumentException("unknown feature: " + feature);
        }
        var binding = layer(client.gui.screen());
        if (binding.isEmpty()) {
            workspace().windows().resizeViewport(client.getWindow().getGuiScaledWidth(),
                    Math.max(1, client.getWindow().getGuiScaledHeight() - edition.reservedBottom()));
        }
        var state =
                workspace().openFeature(
                        feature,
                        objectId,
                        edition.savedWindows(saved)
                                .get(catalog().features().get(feature).windowType()));
        if (!edition.opened(feature, state)) {
            return state;
        }
        ensureView(state);
        var opened = edition.featureOpened.get(feature);
        if (opened != null) {
            opened.accept(state);
        }
        layer(client.gui.screen()).ifPresent(layer -> layer.focus(state.key()));
        return state;
    }

    void ensureView(UiWindowManager.WindowState state) {
        if (!views.containsKey(state.key())) {
            // 一个窗口实例只创建一棵组件树；关窗时由同一个 scope 释放视图和订阅。
            try {
                var view = new OwoWindowView(this, state, edition.factories.get(state.key().windowType()));
                views.put(state.key(), view);
                edition.viewCreated(state);
                state.scope()
                        .addCleanup(
                                () -> {
                                    views.remove(state.key());
                                    contextMenu.close();
                                    view.close();
                                });
            } catch (RuntimeException | Error failure) {
                // 组件构建失败时撤销刚打开的窗口，避免留下无法渲染的空壳。
                workspace().windows().close(state.key());
                throw failure;
            }
        }
    }

    public void openWorkspace() {
        // 已显示工作区时保留其屏幕和输入会话，只让 open 负责聚焦目标窗口。
        if (layer(Minecraft.getInstance().gui.screen()).isPresent()) {
            return;
        }
        Minecraft.getInstance().gui.setScreen(edition.workspaceScreen());
    }

    public void resetWindowLayout() {
        // 重置表现布局保留窗口与业务订阅，所有已保存的窗口类型尺寸也一并回到默认。
        saved = edition.store(saved, Map.of());
        edition.resetLayout();
        for (var window : workspace().windowBar()) {
            workspace().windows().restorePreferredBounds(window.key());
        }
        savePreferences();
    }

    public String title(UiWindowManager.WindowState state) {
        // 优先使用关联功能的展示名称，没有入口时使用稳定类型 ID 便于排查。
        return catalog().features().values().stream()
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
        workspace().windows().cancelCapture();
        drag.cancel();
        contextMenu.close();
        views.values().forEach(OwoWindowView::cancelInput);
    }

    void keyReleased(net.minecraft.client.input.KeyEvent event) {
        // 释放只通知现有窗口，不改变窗口焦点或打开新的视图。
        views.values().forEach(view -> view.keyReleased(event));
    }

    public ClientBindings.SlotContent slotContent(String barId, FrameworkCatalog.Slot slot) {
        // 先确认槽位属于目标栏，再向模块索取当前内容，防止跨栏错误绑定。
        var bar = catalog().slotBars().get(barId);
        if (bar == null || !bar.slots().contains(slot)) {
            throw new IllegalArgumentException("unknown slot binding: " + barId);
        }
        return Objects.requireNonNull(bars.get(barId).apply(slot), "slot content must not be null");
    }

    void renderWindow(
            GuiGraphicsExtractor context,
            UiWindowManager.WindowState state,
            UiWindowManager.Rect bounds,
            boolean controls,
            int mouseX,
            int mouseY,
            float delta) {
        edition.renderWindow(context, state, bounds, controls, mouseX, mouseY, delta);
    }

    public void renderHuds(GuiGraphicsExtractor context, int width, int height, float delta) {
        edition.renderHudWindows(context, width, height, delta);
        // 固定窗口之后另起一层，独立 HUD 整体压在窗口之上。
        context.nextStratum();
        // 每个 HUD 使用独立变换和裁剪，模块只需要在自己的逻辑坐标内绘制。
        for (var hud : catalog().huds().values()) {
            if (!hudLayout.placement(hud.id()).visible()) {
                continue;
            }
            var bounds = hudLayout.bounds(hud.id(), width, height);
            var matrices = context.pose();
            context.enableScissor(
                    bounds.x(),
                    bounds.y(),
                    bounds.x() + bounds.width(),
                    bounds.y() + bounds.height());
            matrices.pushMatrix();
            try {
                matrices.translate(bounds.x(), bounds.y());
                float scale = hudLayout.placement(hud.id()).scale();
                matrices.scale(scale, scale);
                renderers.get(hud.id()).render(context, hud.width(), hud.height(), delta);
            } finally {
                // 模块绘制失败也必须还原上下文，避免影响后续 HUD 和游戏界面。
                matrices.popMatrix();
                context.disableScissor();
            }
        }
    }

    public void renderSlotBar(GuiGraphicsExtractor context, String barId, int width, int height) {
        // 用注册数量分配格宽；HUD 和工作台读取同一供应器，绑定变化会同时显示。
        var bar = catalog().slotBars().get(barId);
        if (bar == null) {
            throw new IllegalArgumentException("unknown slot bar: " + barId);
        }
        if (bar.slots().isEmpty()) {
            return;
        }
        int cellWidth = Math.max(1, width / bar.slots().size());
        int index = 0;
        var text = Minecraft.getInstance().font;
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
                context.text(
                        text,
                        text.plainSubstrByWidth(
                                content.label(), Math.max(1, cellWidth - 2 * WINDOW_BORDER)),
                        x + WINDOW_BORDER,
                        (height - text.lineHeight) / 2,
                        color(ThemeTokens.HUD_TEXT));
            }
        }
    }

    void beginHudFrame(int width, int height) {
        hudBlocked = edition.beginHudFrame(width, height).stream()
                .map(bounds -> new Area(bounds.x(), bounds.y(), bounds.width(), bounds.height()))
                .toList();
    }

    List<Area> hudBlockedAreas() {
        return hudBlocked;
    }

    /** 游戏画面此刻可画的小窗；旧版界面、没有玩家、开着界面或隐藏 HUD 时为空。 */
    public List<CompactView> hudCompacts(int width, int height) {
        if (edition != modern) {
            return List.of();
        }
        return modern.hudProjections(width, height).stream()
                .map(projection -> new CompactView(projection.feature(), projection.variant(), projection.bounds()))
                .toList();
    }

    /** 新版工作台里某功能收小后的屏幕矩形；没收小或没有活动宿主时为空。 */
    public Optional<UiWindowManager.Rect> compactBounds(String feature) {
        if (activeLayer instanceof ModernWindowLayer layer) {
            return layer.compactBounds(feature);
        }
        return Optional.empty();
    }

    /** 新版里已锁到游戏画面的小窗所属功能。 */
    public Set<String> pinnedCompacts() {
        var pinned = new LinkedHashSet<String>();
        modern.compact.placements.forEach((feature, placement) -> {
            if (placement.pinned()) {
                pinned.add(feature);
            }
        });
        return Collections.unmodifiableSet(pinned);
    }

    /** 切换某功能小窗的样式；未登记的样式在绘制时回退到首项。 */
    public void compactVariant(String feature, String variantId) {
        modern.compact.variant(feature, variantId);
        savePreferences();
    }

    void connected() {
        online = true;
        edition.viewport(gameViewport());
        edition.connected();
        modules.forEach(module -> module.connected(this));
    }

    private static UiWindowManager.Rect gameViewport() {
        var window = Minecraft.getInstance().getWindow();
        return new UiWindowManager.Rect(0, 0, window.getGuiScaledWidth(), window.getGuiScaledHeight());
    }

    void disconnected() {
        online = false;
        hudBlocked = List.of();
        edition.released();
        if (activeLayer != null) {
            activeLayer.detach();
        } else {
            savePreferences();
            cancelInput();
        }
        protocol.disconnect();
        // scope 按后进先出清理：先通知模块断线，再清除工作台；某项失败不跳过其余清理。
        var cleanup = new DefaultUiScreenScope();
        cleanup.addCleanup(inactive().workspace::disconnect);
        cleanup.addCleanup(edition.workspace::disconnect);
        modules.forEach(module -> cleanup.addCleanup(() -> module.disconnected(this)));
        cleanup.close();
    }
}

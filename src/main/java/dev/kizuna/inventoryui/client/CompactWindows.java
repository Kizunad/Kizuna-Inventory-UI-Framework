package dev.kizuna.inventoryui.client;

import dev.kizuna.inventoryui.client.status.StatusLayout;
import dev.kizuna.inventoryui.client.status.StatusLayout.Area;
import dev.kizuna.inventoryui.inventory.ItemAction;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.inventoryui.workspace.CompactPlacement;
import dev.kizuna.inventoryui.workspace.WorkspaceController;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import static dev.kizuna.inventoryui.theme.ThemeTokens.BORDER;
import static dev.kizuna.inventoryui.theme.ThemeTokens.COMPACT_BACKDROP;

/** 当前连接的小窗实例及其只读观察。偏好只用功能 ID，回执只认原来的观察对象。 */
final class CompactWindows {
    private static final int PERCENT = 100;
    private static final int STYLE_STEP_PERCENT = 25;
    private static final int MIN_SCALE_PERCENT = Math.round(CompactPlacement.MIN_SCALE * PERCENT);
    private static final int MAX_SCALE_PERCENT = Math.round(CompactPlacement.MAX_SCALE * PERCENT);
    private static final int CROSSHAIR_HALF = 8;
    private static final int HOTBAR_HEIGHT = 24;
    private static final int STATUS_CLEARANCE = 2;
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(15);
    private static final String LANG = "kizuna_inventory_ui.compact.";
    final Map<String, CompactProvider> providers = new LinkedHashMap<>();
    final Map<String, CompactSlot> slots = new LinkedHashMap<>();
    final Map<String, RestoreBinding> bindings = new LinkedHashMap<>();
    final Map<String, CompactPlacement> placements = new LinkedHashMap<>();
    private final Map<WindowState, Entry> entries = new LinkedHashMap<>();
    private final Map<String, RestoreReadiness> failed = new LinkedHashMap<>();
    private final Map<String, Long> waiting = new LinkedHashMap<>();
    private final ModernEdition edition;
    private final ClientRuntime runtime;
    private final WorkspaceController workspace;
    private boolean connected;

    private static final class Entry {
        final String feature;
        final WindowState state;
        final CompactProvider provider;
        RestoreStatus status = RestoreStatus.WAITING;
        boolean observing;
        long deadline;

        Entry(String feature, WindowState state, CompactProvider provider) {
            this.feature = feature;
            this.state = state;
            this.provider = provider;
        }
    }

    CompactWindows(ModernEdition edition) {
        this.edition = edition;
        this.runtime = edition.runtime;
        this.workspace = edition.workspace;
    }

    void validate() {
        // 只读恢复必须唯一对应已登记摘要的单例窗口，不能猜对象身份。
        var catalog = edition.catalog;
        var types = new HashSet<String>();
        for (String feature : bindings.keySet()) {
            String type = catalog.features().get(feature).windowType();
            if (!providers.containsKey(type) || !types.add(type)
                    || catalog.windows().get(type).instancePolicy() != FrameworkCatalog.InstancePolicy.SINGLETON) {
                throw new IllegalArgumentException("invalid compact restore binding: " + feature);
            }
        }
    }

    void migrate(Set<String> legacyPins, Set<String> migrated, Map<String, Rect> legacyWindows, Rect viewport) {
        // 每个旧锁类型只迁一次，之后的解锁不会被旧字段复活；还没接入的类型留到它登记以后。
        for (var id : bindings.keySet()) {
            String type = edition.catalog.features().get(id).windowType();
            if (!legacyPins.contains(type) || !migrated.add(type) || placements.containsKey(id)) {
                continue;
            }
            var placement = initial(type);
            Rect original = legacyWindows.get(type);
            if (original != null) {
                placement = placement.anchor(original, viewport);
            }
            placements.put(id, placement.pin());
        }
    }

    private CompactPlacement initial(String type) {
        return CompactPlacement.initial(providers.get(type).variants().getFirst().id());
    }

    void track(String feature, WindowState state) {
        var provider = providers.get(state.key().windowType());
        if (provider == null || entries.containsKey(state)) {
            return;
        }
        // 多入口共享同一单例时，持久身份取只读绑定所属的功能。
        String owner = bindings.keySet().stream().filter(id -> edition.catalog.features().get(id)
                .windowType().equals(state.key().windowType())).findFirst().orElse(feature);
        placements.computeIfAbsent(owner, ignored -> initial(state.key().windowType()));
        var entry = new Entry(owner, state, provider);
        entries.put(state, entry);
        state.scope().addCleanup(() -> {
            // 明确关窗即撤销固定意图；断线和拒绝会在关闭前先移除观察。
            if (entries.remove(state) == entry) {
                placements.computeIfPresent(owner, (id, placement) -> placement.unpin());
                runtime.savePreferences();
            }
        });
        if (!bindings.containsKey(owner)) {
            entry.status = RestoreStatus.LIVE;
        }
        if (placements.get(owner).pinned() && bindings.containsKey(owner)) {
            workspace.windows().pin(state.key(), true /* pinned */);
        }
    }

    void resetLayout() {
        // 位置和缩放回到自动排开，锁、样式和底色保留。
        placements.replaceAll((id, placement) -> placement.flow()
                .style(placement.variantId(), CompactPlacement.DEFAULT_SCALE, placement.backdropAlpha()));
    }

    void connected() {
        connected = true;
        failed.clear();
        waiting.clear();
    }

    void disconnect() {
        // 先作废回执身份，再让运行时释放所有窗口 scope。
        connected = false;
        entries.clear();
        failed.clear();
        waiting.clear();
    }

    void tick(long now) {
        if (!connected) {
            return;
        }
        // 使用稳定副本；订阅可能同步回报拒绝并清理自己的窗口。
        for (String id : bindings.keySet()) {
            var entry = entries.values().stream()
                    .filter(candidate -> candidate.feature.equals(id))
                    .findFirst()
                    .orElse(null);
            var placement = placements.get(id);
            if (entry == null && (placement == null || !placement.pinned())) {
                continue;
            }
            RestoreReadiness ready = bindings.get(id).readiness();
            if (!workspace.canOpen(id)) {
                ready = RestoreReadiness.DENIED;
            }
            var previous = failed.get(id);
            if (previous != null) {
                failed.put(id, ready);
                if (ready != RestoreReadiness.READY || previous == RestoreReadiness.READY) {
                    continue;
                }
                failed.remove(id);
            }
            advance(id, entry, ready, now);
        }
    }

    private void advance(String id, Entry entry, RestoreReadiness ready, long now) {
        if (ready == RestoreReadiness.DENIED) {
            // 手动打开的未授权页可展示解锁提示，后台恢复和已有观察仍要立即释放。
            if (entry != null && !entry.observing && !entry.state.minimized() && !entry.state.pinned()) {
                return;
            }
            fail(id, entry, ready);
            return;
        }
        if (ready == RestoreReadiness.WAITING) {
            long since = waiting.computeIfAbsent(id, ignored -> now);
            if ((entry != null && entry.status == RestoreStatus.LIVE) || now - since >= TIMEOUT) {
                fail(id, entry, ready);
            }
            return;
        }
        waiting.remove(id);
        if (entry == null) {
            // 恢复只开模型，既不建展开组件也不调用 featureOpened。
            var state = edition.restoreModel(id);
            track(id, state);
            workspace.windows().minimize(state.key());
            entry = entries.get(state);
        }
        if (!entry.observing) {
            observe(entry, now);
        } else if (entry.status == RestoreStatus.WAITING && now - entry.deadline >= 0) {
            fail(id, entry, ready);
        }
    }

    private void observe(Entry entry, long now) {
        entry.observing = true;
        entry.deadline = now + TIMEOUT;
        try {
            bindings.get(entry.feature).observe(entry.state, status -> {
                // 对象身份同时区分连接和同连接内的重试，每次请求都有独立的回执入口。
                if (entries.get(entry.state) == entry) {
                    entry.status = Objects.requireNonNull(status);
                    if (status == RestoreStatus.DENIED) {
                        fail(entry.feature, entry, bindings.get(entry.feature).readiness());
                    }
                }
            });
        } catch (RuntimeException failure) {
            fail(entry.feature, entry, RestoreReadiness.READY);
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Compact subscription", failure);
        }
    }

    private void fail(String id, Entry entry, RestoreReadiness reason) {
        failed.put(id, reason);
        waiting.remove(id);
        if (entry != null) {
            entries.remove(entry.state);
            workspace.windows().close(entry.state.key());
        }
    }

    void retry(String id) {
        failed.remove(id);
        waiting.remove(id);
    }

    boolean retryable(String id) {
        return failed.containsKey(id);
    }

    void pin(WindowState state) {
        var entry = entries.get(state);
        if (entry != null && bindings.containsKey(entry.feature)) {
            if (state.pinned()) {
                placements.computeIfPresent(entry.feature, (id, placement) -> placement.pin());
            } else {
                placements.computeIfPresent(entry.feature, (id, placement) -> placement.unpin());
            }
        }
    }

    private static CompactVariant variant(CompactProvider provider, CompactPlacement placement) {
        var variants = provider.variants();
        return variants.stream()
                .filter(candidate -> candidate.id().equals(placement.variantId()))
                .findFirst()
                .orElse(variants.getFirst());
    }

    private CompactLayout.Item<WindowState> item(Entry entry) {
        var placement = placements.get(entry.feature);
        var slot = slots.getOrDefault(entry.state.key().windowType(), CompactSlot.UNORDERED);
        return new CompactLayout.Item<>(entry.state, placement, slot, variant(entry.provider, placement));
    }

    /** 工作台里收小的窗口在可用区内的矩形；和游戏画面使用同一套排法。 */
    private Map<WindowState, Rect> workspaceLayout(Rect frame) {
        var items = entries.values().stream()
                .filter(entry -> entry.state.minimized() && !entry.state.closed())
                .map(this::item)
                .toList();
        return CompactLayout.arrange(frame, items, List.of());
    }

    Rect bounds(WindowState state, Rect frame) {
        return workspaceLayout(frame).get(state);
    }

    Optional<Rect> bounds(String feature, Rect frame) {
        return workspaceLayout(frame).entrySet().stream()
                .filter(row -> entries.get(row.getKey()).feature.equals(feature))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    CompactPlacement placement(WindowState state) {
        return placements.get(entries.get(state).feature);
    }

    void place(WindowState state, CompactPlacement placement) {
        var entry = entries.get(state);
        if (entry != null) {
            placements.put(entry.feature, placement);
        }
    }

    void move(WindowState state, Rect outer, Rect frame) {
        var entry = entries.get(state);
        if (entry != null) {
            placements.computeIfPresent(entry.feature, (id, placement) -> placement.anchor(outer, frame));
        }
    }

    void variant(String feature, String variantId) {
        placements.computeIfPresent(feature, (id, placement) ->
                placement.style(variantId, placement.scale(), placement.backdropAlpha()));
    }

    void menu(WindowState state, int x, int y) {
        var entry = entries.get(state);
        var placement = placements.get(entry.feature);
        var actions = new ArrayList<ItemAction>();
        for (var variant : entry.provider.variants()) {
            var styled = placement.style(variant.id(), placement.scale(), placement.backdropAlpha());
            actions.add(action(entry, "variant:" + variant.id(), Component.translatable(variant.nameKey()), styled));
        }
        if (placement.position() == CompactPlacement.Position.PLACED) {
            actions.add(action(entry, "flow", Component.translatable(LANG + "flow"), placement.flow()));
        }
        for (int percent = MIN_SCALE_PERCENT; percent <= MAX_SCALE_PERCENT; percent += STYLE_STEP_PERCENT) {
            var scaled = placement.style(placement.variantId(), (float) percent / PERCENT, placement.backdropAlpha());
            actions.add(action(entry, "scale:" + percent, Component.translatable(LANG + "scale", percent), scaled));
        }
        for (int percent = 0; percent <= PERCENT; percent += STYLE_STEP_PERCENT) {
            var tinted = placement.style(placement.variantId(), placement.scale(), (float) percent / PERCENT);
            actions.add(action(entry, "backdrop:" + percent, Component.translatable(LANG + "backdrop", percent),
                    tinted));
        }
        runtime.contextMenu().open(x, y, actions, () -> !state.closed() && state.minimized());
    }

    private ItemAction action(Entry entry, String id, Component label, CompactPlacement value) {
        return new ItemAction(id, label.getString(), true /* enabled */, "", () -> {
            placements.put(entry.feature, value);
            runtime.savePreferences();
        });
    }

    /** 底色只在玩家调出不透明度后才有；透明小窗不画底，也不带边框。 */
    OptionalInt backdrop(CompactPlacement placement) {
        if (placement.backdropAlpha() <= 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(ARGB.color(placement.backdropAlpha(), runtime.color(COMPACT_BACKDROP)));
    }

    void draw(GuiGraphicsExtractor g, WindowState state, Rect bounds, float delta) {
        var entry = entries.get(state);
        var placement = placements.get(entry.feature);
        int right = bounds.x() + bounds.width();
        int bottom = bounds.y() + bounds.height();
        backdrop(placement).ifPresent(color -> g.fill(bounds.x(), bounds.y(), right, bottom, color));
        if (entry.status != RestoreStatus.LIVE) {
            return;
        }
        g.enableScissor(bounds.x(), bounds.y(), right, bottom);
        var pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate(bounds.x(), bounds.y());
            pose.scale(placement.scale(), placement.scale());
            entry.provider.draw(g, state, variant(entry.provider, placement).id(), delta);
        } finally {
            pose.popMatrix();
            g.disableScissor();
        }
    }

    /** 工作台里悬停或拖动时标出透明小窗的范围；游戏画面不调用。 */
    void outline(GuiGraphicsExtractor g, Rect bounds) {
        g.outline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), runtime.color(BORDER));
    }

    record Projection(String feature, String variant, WindowState state, Rect bounds) {
        Area area() {
            return new Area(bounds.x(), bounds.y(), bounds.width(), bounds.height());
        }
    }

    List<Projection> hud(int width, int height) {
        // 底边留给快捷栏；准星和原版状态栏是障碍，右侧不预留，状态图标按这批矩形让开。
        int safeHeight = height - HOTBAR_HEIGHT;
        if (width < 1 || safeHeight < 1) {
            return List.of();
        }
        var items = new ArrayList<CompactLayout.Item<WindowState>>();
        for (var state : workspace.windowBar()) {
            var entry = entries.get(state);
            if (state.pinned() && !state.closed() && entry != null && entry.status == RestoreStatus.LIVE) {
                items.add(item(entry));
            }
        }
        var frame = new Rect(0, 0, width, safeHeight);
        var layout = CompactLayout.arrange(frame, items, hudObstacles(width, height));
        var result = new ArrayList<Projection>();
        for (var item : items) {
            String feature = entries.get(item.key()).feature;
            result.add(new Projection(feature, item.variant().id(), item.key(), layout.get(item.key())));
        }
        return List.copyOf(result);
    }

    private static List<Rect> hudObstacles(int width, int height) {
        var crosshair = new Rect(width / 2 - CROSSHAIR_HALF, height / 2 - CROSSHAIR_HALF,
                CROSSHAIR_HALF * 2, CROSSHAIR_HALF * 2);
        Area status = StatusLayout.vanillaStatusBarArea(width, height);
        var statusBar = new Rect(status.left() - STATUS_CLEARANCE, status.top() - STATUS_CLEARANCE,
                status.width() + STATUS_CLEARANCE * 2, status.height() + STATUS_CLEARANCE * 2);
        return List.of(crosshair, statusBar);
    }
}

package dev.kizuna.inventoryui.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.inventoryui.client.status.StatusLayout;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.inventoryui.window.UiWindowDefinition;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.inventoryui.workspace.CompactPlacement;
import dev.kizuna.inventoryui.workspace.UiVersion;
import dev.kizuna.inventoryui.workspace.WorkspacePreferences;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.ARGB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompactWindowsTest implements ClientModule, CompactProvider, RestoreBinding {
    private static final String FEATURE = "fixture:sample";
    private static final String TYPE = "fixture:window";
    private static final Rect FRAME = new Rect(0, 0, 400, 240);
    private static final int WIDTH = 400;
    private static final int HEIGHT = 240;
    private static final int HOTBAR = 24;
    private static final Rect WORKSPACE = new Rect(0, ModernMetrics.TOOLBAR_HEIGHT, WIDTH,
            HEIGHT - ModernMetrics.TOOLBAR_HEIGHT);
    private static final Rect GAME = new Rect(0, 0, WIDTH, HEIGHT - HOTBAR);
    private static final int VALUE_WIDTH = 36;
    private static final int VALUE_HEIGHT = 12;
    private static final int WIDE_WIDTH = 194;
    private static final int WIDE_HEIGHT = 60;
    private static final int COMPACT_COUNT = 7;
    private static final int TINY_WIDTH = 213;
    private static final int TINY_HEIGHT = 120;
    private final List<Consumer<RestoreStatus>> receipts = new ArrayList<>();
    private RestoreReadiness readiness = RestoreReadiness.READY;
    private int releases;
    private final ClientRuntime runtime = new ClientRuntime(List.of(this));
    private final CompactWindows compact = runtime.modern().compact;

    CompactWindowsTest() {
        // 小窗只在新版界面里有；缺省是旧版，先切过去。
        runtime.uiVersion(UiVersion.MODERN);
    }

    @Override
    public FrameworkCatalog.Module definition() {
        // 首项沿用无后缀的 ID，其余各带序号，合起来是七个可收小的单例。
        var entries = new ArrayList<FrameworkCatalog.Entry>();
        for (int index = 0; index < COMPACT_COUNT; index++) {
            String type = type(index);
            var definition = new UiWindowDefinition(type, type, 80, 60, Set.of(UiWindowDefinition.Capability.WINDOW));
            entries.add(new FrameworkCatalog.Window(definition));
            entries.add(new FrameworkCatalog.Feature(feature(index), "示例", "测试", type, type));
        }
        return FrameworkCatalog.Module.of("fixture:module", Set.of(), entries.toArray(FrameworkCatalog.Entry[]::new));
    }

    private static String feature(int index) {
        if (index == 0) {
            return FEATURE;
        }
        return FEATURE + index;
    }

    private static String type(int index) {
        if (index == 0) {
            return TYPE;
        }
        return TYPE + index;
    }

    @Override
    public void register(ClientBindings bindings) {
        bindings.featureOpened(FEATURE, s -> fail("恢复重放了入口动作"));
        for (int index = 0; index < COMPACT_COUNT; index++) {
            bindings.window(type(index), (r, s) -> fail("恢复执行了展开工厂"));
            bindings.compact(type(index), new CompactSlot(0, index), this);
            bindings.restore(feature(index), this);
        }
    }

    public List<CompactVariant> variants() {
        return List.of(new CompactVariant("value", "fixture.compact.value", VALUE_WIDTH, VALUE_HEIGHT),
                new CompactVariant("wide", "fixture.compact.wide", WIDE_WIDTH, WIDE_HEIGHT));
    }

    public void draw(GuiGraphicsExtractor g, WindowState state, String variant, float delta) {}

    public RestoreReadiness readiness() {
        return readiness;
    }

    public void observe(WindowState state, Consumer<RestoreStatus> status) {
        receipts.add(status);
        state.scope().addCleanup(() -> releases++);
    }

    private void joinPinned() {
        compact.placements.put(FEATURE, CompactPlacement.initial("value").pin());
        compact.connected();
        compact.tick(0);
    }

    @Test
    void hudWaitsForNewReceiptAndIgnoresOldConnection() {
        joinPinned();
        var first = runtime.workspace().windowBar().getFirst();
        assertTrue(compact.hud(400, 240).isEmpty());
        receipts.getFirst().accept(RestoreStatus.LIVE);
        assertSame(first, compact.hud(400, 240).getFirst().state());
        runtime.disconnected();
        assertEquals(1, releases);
        compact.connected();
        compact.tick(1);
        receipts.getFirst().accept(RestoreStatus.LIVE);
        assertTrue(compact.hud(400, 240).isEmpty());
        var second = runtime.workspace().windowBar().getFirst();
        assertNotEquals(first.key(), second.key());
        receipts.getLast().accept(RestoreStatus.LIVE);
        assertSame(second, compact.hud(400, 240).getFirst().state());
    }

    @Test
    void shapesAndLocksKeepScopeAndCloseCancelsRestore() {
        joinPinned();
        var w = runtime.workspace().windowBar().getFirst();
        var key = w.key();
        var manager = runtime.workspace().windows();
        var expanded = w.bounds();
        var original = w.scope();
        for (boolean small : List.of(true, false, true)) {
            if (small) {
                assertTrue(manager.minimize(key));
            } else {
                assertTrue(manager.restore(key));
            }
            assertEquals(small, w.minimized());
            assertTrue(w.pinned());
            assertTrue(runtime.pinWindow(key, false /* pinned */));
            assertEquals(small, w.minimized());
            assertFalse(w.pinned());
            assertTrue(runtime.pinWindow(key, true /* pinned */));
            assertEquals(expanded, w.bounds());
            assertSame(original, w.scope());
            assertEquals(1, receipts.size());
        }
        manager.close(key);
        receipts.getFirst().accept(RestoreStatus.LIVE);
        compact.tick(1);
        assertTrue(w.closed());
        assertTrue(compact.hud(WIDTH, HEIGHT).isEmpty());
        assertTrue(original.isClosed());
        assertEquals(1, releases);
        assertTrue(runtime.workspace().windowBar().isEmpty());
        assertFalse(compact.placements.get(FEATURE).pinned());
    }

    @Test
    void failureAndTimeoutReleaseScopeAndRetryOnce() {
        joinPinned();
        receipts.getFirst().accept(RestoreStatus.DENIED);
        compact.tick(1);
        assertEquals(1, releases);
        assertEquals(1, receipts.size());
        assertTrue(runtime.restoreRetryable(FEATURE));
        compact.retry(FEATURE);
        compact.tick(2);
        receipts.getFirst().accept(RestoreStatus.LIVE);
        assertTrue(compact.hud(400, 240).isEmpty());
        compact.tick(TimeUnit.MINUTES.toNanos(1));
        assertEquals(2, releases);
        assertEquals(2, receipts.size());
        readiness = RestoreReadiness.WAITING;
        compact.tick(TimeUnit.MINUTES.toNanos(2));
        readiness = RestoreReadiness.READY;
        compact.tick(TimeUnit.MINUTES.toNanos(3));
        compact.tick(TimeUnit.MINUTES.toNanos(3));
        assertEquals(3, receipts.size());
    }

    @Test
    void fourCornersRetainOffsetsAcrossGuiScales() {
        var p = CompactPlacement.initial("value");
        var resized = new Rect(13, 17, 640, 360);
        for (var corner : CompactPlacement.Corner.values()) {
            boolean right = corner.name().endsWith("RIGHT");
            boolean bottom = corner.name().startsWith("BOTTOM");
            int x = right ? 354 : 10;
            int y = bottom ? 217 : 11;
            var moved = p.anchor(new Rect(x, y, 36, 12), FRAME);
            assertEquals(corner, moved.corner());
            int nextX = right ? 607 : 23;
            int nextY = bottom ? 354 : 28;
            assertEquals(new Rect(nextX, nextY, 36, 12), moved.resolve(resized, 36, 12));
            assertEquals(new Rect(x, y, 36, 12), moved.resolve(FRAME, 36, 12));
        }
    }

    @Test
    void legacyPinMigratesOnceAndLeavesClassicFieldsAlone(@TempDir Path dir) throws Exception {
        var path = dir.resolve("ui.json");
        Files.writeString(path, """
                {"version":1,"theme":"kiui:xuan_paper","pinnedWindows":["fixture:window","future:type"],
                 "windows":{"fixture:window":{"x":300,"y":140,"width":90,"height":80,"extra":"留"}}}
                """);
        runtime.loadPreferences(path);
        // 旧配置读进来是旧版；玩家第一次切到新版、宿主给出可用区时才迁。
        assertEquals(UiVersion.CLASSIC, runtime.uiVersion());
        assertTrue(compact.placements.isEmpty());
        runtime.uiVersion(UiVersion.MODERN);
        var host = new net.minecraft.client.gui.screens.Screen(null, null, null) {};
        runtime.attach(host, FRAME);
        assertTrue(compact.placements.get(FEATURE).pinned());
        assertEquals(CompactPlacement.Corner.BOTTOM_RIGHT, compact.placements.get(FEATURE).corner());
        assertEquals(CompactPlacement.Position.PLACED, compact.placements.get(FEATURE).position());
        compact.placements.computeIfPresent(FEATURE, (id, placement) -> placement.unpin());
        runtime.savePreferences();
        var read = new WorkspacePreferences(path).load();
        // 旧版自己的锁、矩形和主题原样留着；新版记下迁过的类型，还没登记的类型留到以后。
        assertEquals(Set.of(TYPE, "future:type"), read.pinnedWindows());
        assertEquals(new Rect(300, 140, 90, 80), read.windows().get(TYPE));
        assertEquals("kiui:xuan_paper", read.theme());
        assertEquals(Set.of(TYPE), read.modern().migratedPins());
        assertFalse(read.modern().compact().get(FEATURE).pinned());
        assertTrue(Files.readString(path).contains("留"));
        // 再读一遍、再给一次可用区：解了的锁不会被旧字段复活。
        runtime.loadPreferences(path);
        runtime.attach(host, new Rect(0, 0, 500, 300));
        assertFalse(compact.placements.get(FEATURE).pinned());
        assertEquals(read, new WorkspacePreferences(path).load());
    }

    private WindowState liveCompact() {
        joinPinned();
        receipts.getFirst().accept(RestoreStatus.LIVE);
        return runtime.workspace().windowBar().getFirst();
    }

    @Test
    void draggedCompactKeepsCornerAndOffsetsOnHud() {
        // 工作台可用区在顶栏以下，游戏安全区在快捷栏以上；同一组偏距分别对各自的边解算。
        var state = liveCompact();
        int dx = 13;
        int dy = 9;
        for (var corner : CompactPlacement.Corner.values()) {
            boolean right = corner.name().endsWith("RIGHT");
            boolean bottom = corner.name().startsWith("BOTTOM");
            int x = dx;
            int editY = dy;
            int hudY = dy;
            if (right) {
                x = WIDTH - VALUE_WIDTH - dx;
            }
            if (bottom) {
                editY = WORKSPACE.height() - VALUE_HEIGHT - dy;
                hudY = GAME.height() - VALUE_HEIGHT - dy;
            }
            var dropped = new Rect(x, WORKSPACE.y() + editY, VALUE_WIDTH, VALUE_HEIGHT);
            compact.move(state, dropped, WORKSPACE);
            runtime.pinWindow(state.key(), true /* pinned */);
            assertEquals(corner, compact.placement(state).corner());
            assertEquals(dropped, compact.bounds(state, WORKSPACE));
            var hud = compact.hud(WIDTH, HEIGHT).getFirst().bounds();
            assertEquals(new Rect(x, hudY, VALUE_WIDTH, VALUE_HEIGHT), hud, corner.name());
        }
    }

    @Test
    void statusIconsYieldToCompactDraggedOntoTheirColumns() {
        // 小窗留在玩家放的右上角，原本占这里的图标格位往下让。
        var state = liveCompact();
        var free = StatusLayout.hudCells(WIDTH, HEIGHT);
        int left = WIDTH - VALUE_WIDTH;
        int top = free.getFirst().top();
        compact.move(state, new Rect(left, WORKSPACE.y() + top, VALUE_WIDTH, VALUE_HEIGHT), WORKSPACE);
        var projections = compact.hud(WIDTH, HEIGHT);
        var hud = projections.getFirst().bounds();
        assertEquals(new Rect(left, top, VALUE_WIDTH, VALUE_HEIGHT), hud);
        var blocked = projections.stream().map(CompactWindows.Projection::area).toList();
        var cells = StatusLayout.hudCells(WIDTH, HEIGHT, blocked);
        assertNotEquals(free, cells);
        assertFalse(cells.isEmpty());
        for (var cell : cells) {
            boolean apart = cell.right() <= hud.x() || hud.x() + hud.width() <= cell.left()
                    || cell.bottom() <= hud.y() || hud.y() + hud.height() <= cell.top();
            assertTrue(apart, "状态格位压住小窗 " + cell);
        }
    }

    @Test
    void sevenCompactsOnTinyViewportNeverThrow() {
        // GUI 比例调大后的逻辑视口放不下七个宽摘要，原版状态栏上方也没有整块空位：退回夹取位置，允许重叠。
        runtime.workspace().windows().resizeViewport(TINY_WIDTH, TINY_HEIGHT);
        for (int index = 0; index < COMPACT_COUNT; index++) {
            compact.placements.put(feature(index), CompactPlacement.initial("wide").pin());
        }
        compact.connected();
        compact.tick(0);
        receipts.forEach(receipt -> receipt.accept(RestoreStatus.LIVE));
        var projections = compact.hud(TINY_WIDTH, TINY_HEIGHT);
        assertEquals(COMPACT_COUNT, projections.size());
        var workspace = new Rect(0, ModernMetrics.TOOLBAR_HEIGHT, TINY_WIDTH,
                TINY_HEIGHT - ModernMetrics.TOOLBAR_HEIGHT);
        for (var projection : projections) {
            var hud = projection.bounds();
            assertTrue(hud.x() >= 0 && hud.y() >= 0 && hud.x() + hud.width() <= TINY_WIDTH
                    && hud.y() + hud.height() <= TINY_HEIGHT - HOTBAR, "小窗越出游戏安全区 " + hud);
            var edit = compact.bounds(projection.state(), workspace);
            assertTrue(edit.y() >= workspace.y() && edit.y() + edit.height() <= TINY_HEIGHT, "小窗越出工作台 " + edit);
        }
    }

    @Test
    void automaticCompactsUseTheSameOrderInWorkspaceAndHud() {
        // 没拖过的小窗两处都从左上按声明顺序往下排，只差各自可用区的起点。
        compact.placements.put(feature(1), CompactPlacement.initial("value").pin());
        compact.placements.put(FEATURE, CompactPlacement.initial("value").pin());
        compact.connected();
        compact.tick(0);
        receipts.forEach(receipt -> receipt.accept(RestoreStatus.LIVE));
        var projections = compact.hud(WIDTH, HEIGHT);
        assertEquals(2, projections.size());
        int previousBottom = 0;
        for (int index = 0; index < projections.size(); index++) {
            int order = index;
            var projection = projections.stream()
                    .filter(candidate -> candidate.feature().equals(feature(order)))
                    .findFirst()
                    .orElseThrow();
            var hud = projection.bounds();
            var edit = compact.bounds(projection.state(), WORKSPACE);
            assertEquals(hud.x(), edit.x());
            assertEquals(hud.y(), edit.y() - WORKSPACE.y());
            assertTrue(hud.y() > previousBottom, "声明顺序没有从上往下排");
            previousBottom = hud.y() + hud.height();
        }
    }

    @Test
    void transparentCompactDrawsNeitherBackdropNorFrame() {
        // 画法只有底色一项由偏好决定；边框由工作台在悬停或拖动时另画，游戏画面没有那条路。
        var transparent = CompactPlacement.initial("value");
        assertEquals(0f, transparent.backdropAlpha());
        assertTrue(compact.backdrop(transparent).isEmpty());
        float half = 0.5f;
        var tinted = compact.backdrop(transparent.style("value", 1, half));
        int backdrop = runtime.color(ThemeTokens.COMPACT_BACKDROP);
        assertEquals(ARGB.color(half, backdrop), tinted.getAsInt());
        assertNotEquals(0, ARGB.alpha(tinted.getAsInt()));
    }

    @Test
    void resetReturnsDraggedCompactToAutomaticFlowAndKeepsLock() {
        var state = liveCompact();
        var automatic = compact.bounds(state, WORKSPACE);
        compact.move(state, new Rect(200, 100, VALUE_WIDTH, VALUE_HEIGHT), WORKSPACE);
        assertNotEquals(automatic, compact.bounds(state, WORKSPACE));
        compact.resetLayout();
        assertEquals(automatic, compact.bounds(state, WORKSPACE));
        assertTrue(compact.placement(state).pinned());
    }
}

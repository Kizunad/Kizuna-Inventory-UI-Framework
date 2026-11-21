package dev.kizuna.inventoryui.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowDefinition;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.inventoryui.workspace.CompactPlacement;
import dev.kizuna.inventoryui.workspace.UiVersion;
import dev.kizuna.inventoryui.workspace.WorkspacePreferences;
import io.wispforest.owo.ui.core.UIComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 一个模块给两版各一份声明：同一个功能和窗口类型，旧版窗口大、新版窗口小并带摘要。 */
class UiVersionTest implements ClientModule, CompactProvider, RestoreBinding {
    private static final String FEATURE = "fixture:body";
    private static final String TYPE = "fixture:body_window";
    private static final int CLASSIC_MIN_WIDTH = 280;
    private static final int MODERN_MIN_WIDTH = 150;
    private static final int MIN_HEIGHT = 120;
    private static final int SWITCH_ROUNDS = 3;
    private static final Rect CLASSIC_BOUNDS = new Rect(31, 47, 300, 200);
    private static final Rect WORKSPACE = new Rect(0, ModernMetrics.TOOLBAR_HEIGHT, 400,
            240 - ModernMetrics.TOOLBAR_HEIGHT);
    private static final Rect DROPPED = new Rect(350, 200, 36, 12);
    private final List<Consumer<RestoreStatus>> receipts = new ArrayList<>();
    private final ClientRuntime runtime = new ClientRuntime(List.of(this));
    private final CompactWindows compact = runtime.modern().compact;

    private static FrameworkCatalog.Module module(int minWidth, String title) {
        var window = new UiWindowDefinition(TYPE, TYPE, minWidth, MIN_HEIGHT,
                Set.of(UiWindowDefinition.Capability.WINDOW));
        return FrameworkCatalog.Module.of("fixture:module", Set.of(), new FrameworkCatalog.Window(window),
                new FrameworkCatalog.Feature(FEATURE, title, "测试", TYPE, TYPE));
    }

    @Override
    public FrameworkCatalog.Module definition() {
        return module(CLASSIC_MIN_WIDTH, "检视");
    }

    @Override
    public Optional<FrameworkCatalog.Module> modernDefinition() {
        return Optional.of(module(MODERN_MIN_WIDTH, "体表"));
    }

    @Override
    public void register(ClientBindings bindings) {
        bindings.window(TYPE, UiVersionTest::unused);
    }

    @Override
    public void registerModern(ClientBindings bindings) {
        bindings.window(TYPE, UiVersionTest::unused);
        bindings.compact(TYPE, this);
        bindings.restore(FEATURE, this);
    }

    private static UIComponent unused(ClientRuntime runtime, WindowState state) {
        return fail("这组用例不建展开组件");
    }

    @Override
    public List<CompactVariant> variants() {
        return List.of(new CompactVariant("value", "fixture.compact.value", DROPPED.width(), DROPPED.height()));
    }

    @Override
    public void draw(GuiGraphicsExtractor g, WindowState state, String variant, float delta) { }

    @Override
    public RestoreReadiness readiness() {
        return RestoreReadiness.READY;
    }

    @Override
    public void observe(WindowState state, Consumer<RestoreStatus> status) {
        receipts.add(status);
    }

    private WindowState restoredCompact() {
        compact.connected();
        compact.tick(0);
        receipts.getLast().accept(RestoreStatus.LIVE);
        return runtime.workspace().windowBar().getFirst();
    }

    private WindowState openClassic() {
        return runtime.workspace().openFeature(FEATURE, null, CLASSIC_BOUNDS);
    }

    @Test
    void newPreferencesStartInClassicAndEachVersionHasItsOwnCatalog() {
        assertEquals(UiVersion.CLASSIC, runtime.uiVersion());
        assertEquals("检视", runtime.catalog().features().get(FEATURE).title());
        assertEquals(CLASSIC_MIN_WIDTH, runtime.catalog().windows().get(TYPE).definition().minimumWidth());
        assertFalse(runtime.catalog().themes().containsKey("kiui:glass"));
        assertEquals("kiui:ink_jade", runtime.theme());
        runtime.uiVersion(UiVersion.MODERN);
        assertEquals("体表", runtime.catalog().features().get(FEATURE).title());
        assertEquals(MODERN_MIN_WIDTH, runtime.catalog().windows().get(TYPE).definition().minimumWidth());
        assertEquals(List.of("kiui:glass"), List.copyOf(runtime.catalog().themes().keySet()));
    }

    @Test
    void switchingBackAndForthKeepsBothLayouts(@TempDir Path dir) throws Exception {
        var path = dir.resolve("ui.json");
        runtime.loadPreferences(path);
        runtime.uiVersion(UiVersion.MODERN);
        compact.placements.put(FEATURE, CompactPlacement.initial("value").pin());
        compact.move(restoredCompact(), DROPPED, WORKSPACE);
        var modernPlacement = compact.placements.get(FEATURE);

        runtime.uiVersion(UiVersion.CLASSIC);
        assertTrue(runtime.workspace().windowBar().isEmpty());
        var classicWindow = openClassic();
        assertTrue(runtime.pinWindow(classicWindow.key(), true /* pinned */));
        runtime.theme("kiui:xuan_paper");

        for (int round = 0; round < SWITCH_ROUNDS; round++) {
            runtime.uiVersion(UiVersion.MODERN);
            assertTrue(runtime.workspace().windowBar().isEmpty(), "旧版的窗口应当随切换关闭");
            assertEquals("kiui:glass", runtime.theme());
            assertEquals(modernPlacement, compact.placements.get(FEATURE));
            var compactWindow = restoredCompact();
            assertTrue(compactWindow.pinned() && compactWindow.minimized());
            assertEquals(DROPPED, compact.bounds(compactWindow, WORKSPACE));

            runtime.uiVersion(UiVersion.CLASSIC);
            assertTrue(compactWindow.closed(), "新版的窗口应当随切换关闭");
            assertTrue(compact.placements.get(FEATURE).pinned(), "切换不是玩家关窗，锁要留着");
            assertEquals("kiui:xuan_paper", runtime.theme());
            var saved = new WorkspacePreferences(path).load();
            assertEquals(UiVersion.CLASSIC, saved.uiVersion());
            assertEquals(CLASSIC_BOUNDS, saved.windows().get(TYPE));
            assertEquals(Set.of(TYPE), saved.pinnedWindows());
            assertEquals("kiui:xuan_paper", saved.theme());
            assertEquals(modernPlacement, saved.modern().compact().get(FEATURE));
        }
    }

    @Test
    void savedVersionIsRestoredOnLoad(@TempDir Path dir) throws Exception {
        var path = dir.resolve("ui.json");
        runtime.loadPreferences(path);
        runtime.uiVersion(UiVersion.MODERN);
        var reloaded = new ClientRuntime(List.of(new UiVersionTest()));
        assertEquals(UiVersion.CLASSIC, reloaded.uiVersion());
        reloaded.loadPreferences(path);
        assertEquals(UiVersion.MODERN, reloaded.uiVersion());
    }
}

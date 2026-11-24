package dev.kizuna.inventoryui.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowDefinition;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.inventoryui.workspace.IconStyle;
import dev.kizuna.inventoryui.workspace.UiVersion;
import dev.kizuna.inventoryui.workspace.WorkspacePreferences;
import io.wispforest.owo.ui.core.UIComponent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 新版标题栏的按钮按图标风格取图：三套各有自己的贴图，缺图报错且不换成别的风格。 */
class IconStyleTest implements ClientModule, CompactProvider {
    private static final String FEATURE = "fixture:body";
    private static final String TYPE = "fixture:body_window";
    private static final List<String> TITLE_BAR_ICONS = List.of("collapse", "expand", "lock", "unlock", "close");
    private static final Rect BOUNDS = new Rect(20, 40, 150, 120);
    private final List<String> reports = new ArrayList<>();
    private final StyledIcons shipped = new StyledIcons(IconStyleTest::shipped, reports::add);
    private final ClientRuntime runtime = new ClientRuntime(List.of(this));

    @Override
    public FrameworkCatalog.Module definition() {
        var window = new UiWindowDefinition(TYPE, TYPE, 150, 120, Set.of(UiWindowDefinition.Capability.WINDOW));
        return FrameworkCatalog.Module.of("fixture:module", Set.of(), new FrameworkCatalog.Window(window),
                new FrameworkCatalog.Feature(FEATURE, "体表", "测试", TYPE, TYPE));
    }

    @Override
    public void register(ClientBindings bindings) {
        bindings.window(TYPE, IconStyleTest::unused);
        bindings.compact(TYPE, this);
    }

    private static UIComponent unused(ClientRuntime runtime, WindowState state) {
        return fail("这组用例不建展开组件");
    }

    @Override
    public List<CompactVariant> variants() {
        return List.of(new CompactVariant("value", "fixture.compact.value", 36, 12));
    }

    @Override
    public void draw(GuiGraphicsExtractor g, WindowState state, String variant, float delta) { }

    private static String resource(Identifier texture) {
        return "/assets/" + texture.getNamespace() + "/" + texture.getPath();
    }

    private static boolean shipped(Identifier texture) {
        return IconStyleTest.class.getResource(resource(texture)) != null;
    }

    private static byte[] bytes(Identifier texture) throws IOException {
        try (var stream = IconStyleTest.class.getResourceAsStream(resource(texture))) {
            assertNotNull(stream, texture.toString());
            return stream.readAllBytes();
        }
    }

    private static Identifier titleBarIcon(String name) {
        return Identifier.fromNamespaceAndPath("kizuna_inventory_ui", name);
    }

    @Test
    void everyStyleShipsItsOwnTitleBarIcons() throws IOException {
        for (String name : TITLE_BAR_ICONS) {
            var distinct = new HashSet<String>();
            for (var style : IconStyle.values()) {
                var texture = shipped.texture(style, titleBarIcon(name));
                assertTrue(texture.getPath().contains("/" + style.id() + "/"), texture.toString());
                distinct.add(Arrays.toString(bytes(texture)));
            }
            assertEquals(IconStyle.values().length, distinct.size(), name + " 三套不能是同一张图");
        }
        assertEquals(List.of(), reports);
        assertEquals(Set.of(), shipped.missing());
    }

    @Test
    void titleBarButtonsFollowTheChosenStyle() {
        runtime.uiVersion(UiVersion.MODERN);
        var state = runtime.workspace().openFeature(FEATURE, null, BOUNDS);
        for (var style : IconStyle.values()) {
            runtime.iconStyle(style);
            assertEquals(style, runtime.iconStyle());
            for (var action : ModernChrome.Action.values()) {
                var texture = StyledIcons.path(runtime.iconStyle(), ModernChrome.icon(action, state));
                assertTrue(shipped(texture), texture + " 应随框架带出");
                String directory = "textures/gui/icons/" + style.id() + "/";
                assertTrue(texture.getPath().startsWith(directory), texture.toString());
            }
        }
    }

    @Test
    void lockButtonShowsWhetherTheWindowIsPinned() {
        runtime.uiVersion(UiVersion.MODERN);
        var state = runtime.workspace().openFeature(FEATURE, null, BOUNDS);
        assertEquals(titleBarIcon("unlock"), ModernChrome.icon(ModernChrome.Action.PIN, state));
        assertTrue(runtime.pinWindow(state.key(), true /* pinned */));
        assertEquals(titleBarIcon("lock"), ModernChrome.icon(ModernChrome.Action.PIN, state));
        assertEquals(titleBarIcon("collapse"), ModernChrome.icon(ModernChrome.Action.MINIMIZE, state));
        assertEquals(titleBarIcon("expand"), ModernChrome.icon(ModernChrome.Action.RESTORE, state));
        assertEquals(titleBarIcon("close"), ModernChrome.icon(ModernChrome.Action.CLOSE, state));
    }

    @Test
    void missingIconIsReportedOnceAndNeverBorrowedFromAnotherStyle() {
        // 只有琉璃细线这一套有图：换到水墨后照样指向水墨的路径，并报出缺的是哪一张。
        var glassOnly = new StyledIcons(texture -> texture.getPath().contains("/glass_line/"), reports::add);
        var close = titleBarIcon("close");
        assertEquals(StyledIcons.path(IconStyle.GLASS_LINE, close), glassOnly.texture(IconStyle.GLASS_LINE, close));
        assertEquals(List.of(), reports);
        var ink = glassOnly.texture(IconStyle.INK, close);
        assertEquals("kizuna_inventory_ui:textures/gui/icons/ink/close.png", ink.toString());
        glassOnly.texture(IconStyle.INK, close);
        assertEquals(List.of("图标风格 ink 缺少图标 kizuna_inventory_ui:close，"
                + "应有贴图 kizuna_inventory_ui:textures/gui/icons/ink/close.png"), reports);
        assertEquals(Set.of(ink), glassOnly.missing());
        glassOnly.reload();
        glassOnly.texture(IconStyle.INK, close);
        assertEquals(2, reports.size(), "资源重新加载后再查一次");
    }

    @Test
    void chosenStyleSurvivesRestartAndDefaultsToGlassLine(@TempDir Path directory) throws IOException {
        var path = directory.resolve("ui.json");
        assertEquals(IconStyle.GLASS_LINE, runtime.iconStyle());
        runtime.loadPreferences(path);
        runtime.uiVersion(UiVersion.CLASSIC);
        runtime.iconStyle(IconStyle.INK);
        assertEquals(IconStyle.INK, new WorkspacePreferences(path).load().modern().icons());
        var restarted = new ClientRuntime(List.of(new IconStyleTest()));
        restarted.loadPreferences(path);
        assertEquals(IconStyle.INK, restarted.iconStyle());
        assertEquals(UiVersion.CLASSIC, restarted.uiVersion(), "换图标风格不改界面版本");
        Files.writeString(path, "{\"version\":2,\"modern\":{\"icons\":\"watercolor\"}}");
        assertEquals(IconStyle.GLASS_LINE, new WorkspacePreferences(path).load().modern().icons());
    }
}

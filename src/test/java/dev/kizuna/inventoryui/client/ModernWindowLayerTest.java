package dev.kizuna.inventoryui.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.inventoryui.client.status.StatusLayout.Area;
import dev.kizuna.inventoryui.inventory.ItemAction;
import dev.kizuna.inventoryui.window.UiWindowDefinition;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.inventoryui.workspace.UiVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class ModernWindowLayerTest {
    private static final Rect VIEWPORT = new Rect(30, 20, 500, 300);
    private static final UiWindowDefinition WINDOW = new UiWindowDefinition(
            "test:window", "test:template", 40, 40, Set.of(UiWindowDefinition.Capability.WINDOW));
    private final ClientRuntime runtime = modernRuntime();
    private final Screen host = host();
    private final WindowLayer layer = runtime.attach(host, VIEWPORT);
    private final WindowState back = open("back", new Rect(10, 10, 180, 110));
    private final WindowState front = open("front", new Rect(60, 60, 200, 140));

    @Test
    void clickSelectsTopmostWindowAtScreenCoordinates() {
        assertTrue(layer.mouseClicked(left(115, 115), false /* doubleClick */));
        assertEquals(front.key(), top());
        assertTrue(layer.mouseClicked(left(45, 65), false /* doubleClick */));
        assertEquals(back.key(), top());
        assertFalse(layer.blocksPointer(290, 220));
        assertFalse(layer.mouseClicked(left(290, 220), false /* doubleClick */));
    }

    @Test
    void ctrlTabCyclesVisibleWindowsInDrawingOrder() {
        var hidden = open("hidden", new Rect(10, 10, 100, 60));
        runtime.workspace().windows().minimize(hidden.key());
        var cycle = new KeyEvent(GLFW.GLFW_KEY_TAB, 0, GLFW.GLFW_MOD_CONTROL);
        assertTrue(layer.keyPressed(cycle));
        assertEquals(back.key(), top());
        assertTrue(layer.keyPressed(cycle));
        assertEquals(front.key(), top());
    }

    @Test
    void capturedTitleDragsAcrossWindowsAndReleasesOutside() {
        var other = front.bounds();
        assertTrue(layer.mouseClicked(left(45, 33), false /* doubleClick */));
        assertEquals(back.key(), runtime.workspace().windows().capturedKey());
        assertTrue(layer.mouseDragged(left(147, 125), 102, 92));
        assertEquals(new Rect(112, 102, 180, 110), back.bounds());
        assertEquals(other, front.bounds());
        assertTrue(layer.mouseReleased(left(600, 450)));
        assertNull(runtime.workspace().windows().capturedKey());
        assertFalse(layer.mouseDragged(left(145, 123), -2, -2));
    }

    @Test
    void escapeEndsCapturedTitleBeforeHostCloses() {
        assertTrue(layer.mouseClicked(left(45, 33), false /* doubleClick */));
        assertFalse(layer.keyInWindow(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0)));
        assertTrue(layer.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0)));
        assertNull(runtime.workspace().windows().capturedKey());
        assertFalse(layer.mouseReleased(left(45, 33)));
    }

    @Test
    void modalMenuConsumesRightClickAndEscapeBeforeHost() {
        openMenu();
        assertTrue(layer.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0)));
        assertFalse(runtime.contextMenu().isOpen());
        assertFalse(layer.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0)));
        openMenu();
        var right = new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 0);
        assertTrue(layer.mouseClicked(new MouseButtonEvent(540, 25, right), false /* doubleClick */));
        assertEquals(front.key(), top());
        assertFalse(runtime.contextMenu().isOpen());
    }

    @Test
    void unsupportedWindowOnlyHasCloseAndStillResizesByGrip() {
        assertNull(ModernChrome.actionBounds(runtime, front, front.bounds(), ModernChrome.Action.MINIMIZE));
        assertNull(ModernChrome.actionBounds(runtime, front, front.bounds(), ModernChrome.Action.PIN));
        assertFalse(runtime.workspace().windows().minimize(front.key()));
        assertTrue(layer.mouseClicked(left(289, 219), false /* doubleClick */));
        assertTrue(layer.mouseDragged(left(310, 230), 21, 11));
        assertTrue(layer.mouseReleased(left(310, 230)));
        assertEquals(220, front.bounds().width());
    }

    @Test
    void attachResizeAndDetachPreserveScopesUntilDisconnect() {
        assertSame(layer, runtime.attach(host, new Rect(50, 40, 500, 300)));
        assertTrue(layer.blockedAreas().contains(new Area(60, 50, 180, 110)));
        assertFalse(layer.blocksPointer(45, 35));
        var replacement = host();
        var next = runtime.attach(replacement, VIEWPORT);
        assertTrue(runtime.layer(host).isEmpty());
        assertSame(next, runtime.layer(replacement).orElseThrow());
        assertTrue(next.mouseClicked(left(45, 33), false /* doubleClick */));
        layer.detach();
        assertEquals(back.key(), runtime.workspace().windows().capturedKey());
        assertFalse(back.closed());
        runtime.disconnected();
        assertTrue(back.closed());
        assertTrue(front.closed());
        assertTrue(runtime.layer(replacement).isEmpty());
        assertTrue(next.blockedAreas().isEmpty());
        assertFalse(next.mouseReleased(left(45, 33)));
    }

    @Test
    void rightClickOpensTheRegisteredWindowMenuAndRunsTheChosenAction() {
        // 登记了菜单的窗口：标题栏和正文上按右键都弹出菜单，点菜单项执行它；窗口关掉以后菜单失效。
        var chosen = new ArrayList<String>();
        runtime.modern().windowMenus.put(WINDOW.windowType(), state -> List.of(
                new ItemAction("layer", "骨架", true /* enabled */, "", () -> chosen.add(state.key().toString()))));
        assertTrue(layer.mouseClicked(right(200, 150), false /* doubleClick */));
        assertTrue(runtime.contextMenu().isOpen());
        assertTrue(runtime.contextMenu().row("missing", host.width, host.height).isEmpty());
        var row = runtime.contextMenu().row("layer", host.width, host.height).orElseThrow();
        assertEquals(new Area(200, 150, row.width(), row.height()), row);
        assertTrue(layer.mouseClicked(left(row.left() + 1, row.top() + 1), false /* doubleClick */));
        assertEquals(List.of(front.key().toString()), chosen);
        assertTrue(runtime.contextMenu().row("layer", host.width, host.height).isEmpty());
        assertFalse(runtime.contextMenu().isOpen());
        assertTrue(layer.mouseClicked(right(45, 33), false /* doubleClick */));
        assertTrue(runtime.contextMenu().isOpen());
        runtime.workspace().windows().close(back.key());
        assertFalse(runtime.contextMenu().isOpen());
    }

    @Test
    void rightClickGoesToTheContentWhenNoMenuIsRegistered() {
        assertTrue(layer.mouseClicked(right(200, 150), false /* doubleClick */));
        assertFalse(runtime.contextMenu().isOpen());
        runtime.modern().windowMenus.put(WINDOW.windowType(), state -> List.of());
        assertTrue(layer.mouseClicked(right(200, 150), false /* doubleClick */));
        assertFalse(runtime.contextMenu().isOpen());
    }

    private static MouseButtonEvent right(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 0));
    }

    @Test
    void expandedWindowStaysBelowToolbarAfterFitDragAndResize() {
        // 小屏容不下声明的最小高度，打开和以后每一次布局都要为标题和顶栏留位。
        var windows = runtime.workspace().windows();
        int floor = ModernMetrics.TOOLBAR_HEIGHT;
        windows.resizeViewport(427, 240, floor);
        var tall = new UiWindowDefinition("test:tall", "test:panel", 360, 280, WINDOW.capabilities());
        var key = windows.key(tall.windowType(), "tall");
        var state = windows.openOrFocus(tall, key, null);
        assertTrue(state.bounds().y() >= floor, "打开时标题压住顶栏");
        assertTrue(windows.fit(key, 600, 480));
        assertTrue(state.bounds().y() >= floor, "按内容长高后标题压住顶栏");
        var bounds = state.bounds();
        assertTrue(windows.beginDrag(key, bounds.x() + 1, bounds.y() + 1));
        assertTrue(windows.dragTo(0, 0));
        assertTrue(windows.endDrag());
        assertEquals(floor, state.bounds().y());
        windows.resizeViewport(320, 180, floor);
        assertEquals(new Rect(0, floor, 320, 180 - floor), state.bounds());
    }

    @Test
    void defaultWindowsOpenSideBySideAndSavedPositionsTakePrecedence() {
        // 两个自动窗在窄屏也放得下；玩家主动存下的重叠位置必须保留。
        var windows = runtime.workspace().windows();
        windows.close(front.key());
        windows.close(back.key());
        windows.resizeViewport(427, 240, ModernMetrics.TOOLBAR_HEIGHT);
        var first = new UiWindowDefinition("test:breath", "test:panel", 150, 120, 170, 167, WINDOW.capabilities());
        var second = new UiWindowDefinition("test:detail", "test:panel", 170, 150, 210, 180, WINDOW.capabilities());
        var parent = windows.openOrFocus(first, windows.key(first.windowType(), "parent"), null);
        Rect original = parent.bounds();
        var child = windows.openOrFocus(second, windows.key(second.windowType(), "child"), null);
        assertTrue(child.bounds().x() >= original.x() + original.width(), "新窗遮住已开的窗");
        assertEquals(original, parent.bounds());
        assertTrue(child.automaticBounds());
        var saved = windows.openOrFocus(first, windows.key(first.windowType(), "saved"), original);
        assertEquals(original, saved.bounds());
        assertFalse(saved.automaticBounds());
    }

    private void openMenu() {
        var action = new ItemAction("inspect", "查看", true /* enabled */, "", () -> fail("意外执行菜单项"));
        runtime.contextMenu().open(100, 100, List.of(action), () -> true);
    }

    private WindowState open(String identity, Rect bounds) {
        var windows = runtime.workspace().windows();
        return windows.openOrFocus(WINDOW, windows.key(WINDOW.windowType(), identity), bounds);
    }

    @Test
    void crowdedViewportExposesBothTitleBars() {
        // 两个满宽窗口没有并排空间；后开的窗可缩短，但要留出前一扇窗的完整标题。
        var frame = new Rect(0, 28, 320, 152);
        Rect placed = ModernWindowPlacement.place(frame, frame, List.of(frame));
        assertEquals(frame.y() + ModernMetrics.WINDOW_HEADER_HEIGHT, placed.y());
        assertEquals(frame.y() + frame.height(), placed.y() + placed.height());
    }

    private WindowKey top() {
        return runtime.workspace().windowBar().getLast().key();
    }

    private static MouseButtonEvent left(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
    }

    private static Screen host() {
        // 输入和布局不访问字体或客户端；无需 OpenGL 上下文。
        var screen = new Screen(null, null, null) {};
        screen.width = 600;
        screen.height = 400;
        return screen;
    }

    private static ClientRuntime modernRuntime() {
        var created = new ClientRuntime(List.of());
        created.uiVersion(UiVersion.MODERN);
        return created;
    }
}

package dev.kizuna.inventoryui.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.inventoryui.client.status.StatusLayout.Area;
import dev.kizuna.inventoryui.inventory.ItemAction;
import dev.kizuna.inventoryui.window.UiWindowDefinition;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
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

class WindowLayerTest {
    private static final Rect VIEWPORT = new Rect(30, 20, 500, 300);
    private static final UiWindowDefinition WINDOW = new UiWindowDefinition(
            "test:window", "test:template", 40, 40, Set.of(UiWindowDefinition.Capability.WINDOW));
    private final ClientRuntime runtime = new ClientRuntime(List.of());
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
    void sizeButtonSelectsClickedWindowForTextEntry() {
        var target = new AtomicReference<WindowKey>();
        layer.workspaceControls(Map.of(), target::set, 0);
        var size = WindowChrome.actionBounds(front, front.bounds(), WindowChrome.Action.SIZE);
        var click = left(VIEWPORT.x() + size.x() + 1, VIEWPORT.y() + size.y() + 1);
        assertTrue(layer.mouseClicked(click, false /* doubleClick */));
        var windows = runtime.workspace().windows();
        assertEquals(front.key(), target.get());
        assertFalse(windows.resize(target.get(), "abc", "100"));
        assertTrue(windows.resize(target.get(), "201", "100"));
        assertEquals(201, front.bounds().width());
        assertEquals(180, back.bounds().width());
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

    private void openMenu() {
        var action = new ItemAction("inspect", "查看", true /* enabled */, "", () -> fail("意外执行菜单项"));
        runtime.contextMenu().open(100, 100, List.of(action), () -> true);
    }

    private WindowState open(String identity, Rect bounds) {
        var windows = runtime.workspace().windows();
        return windows.openOrFocus(WINDOW, windows.key(WINDOW.windowType(), identity), bounds);
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
}

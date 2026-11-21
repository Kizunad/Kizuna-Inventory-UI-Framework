package dev.kizuna.inventoryui.demo;

import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.inventoryui.workspace.UiVersion;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/** 新版界面的演示冒烟：琉璃窗口、收成小窗并拖到别处、上锁，再和旧版来回切换，核对两边的布局都还在。 */
final class ModernSmoke {
    private static final String OK = "KIUI_MODERN_SMOKE_OK";
    private static final String INVENTORY = "demo:inventory";
    private static final String STATUS = "demo:status";
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;
    private static final int GUI_SCALE = 2;
    private static final int SETTLE_TICKS = 10;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int SWITCH_ROUNDS = 3;
    private static final int MODERN_X = 150;
    private static final int MODERN_Y = 60;
    private static final int CLASSIC_X = 300;
    private static final int CLASSIC_Y = 120;
    private static final int COMPACT_DRAG_X = 420;
    private static final int COMPACT_DRAG_Y = 250;

    private enum Stage { PREPARE, OPEN, COMPACT, TO_CLASSIC, TO_MODERN, DONE }

    private Stage stage = Stage.PREPARE;
    private int ticks;
    private int since;
    private int round;
    private boolean resized;
    private Rect modernBounds;
    private Rect classicBounds;
    private Rect compactBounds;

    static void register() {
        var smoke = new ModernSmoke();
        ClientTickEvents.END_CLIENT_TICK.register(smoke::tick);
    }

    private void tick(Minecraft client) {
        if (stage == Stage.DONE) {
            return;
        }
        require(++ticks < TIMEOUT_TICKS, "新版演示超时：" + stage);
        if (client.gui.screen() instanceof AccessibilityOnboardingScreen) {
            client.gui.setScreen(new TitleScreen());
        }
        if (client.gui.overlay() != null || ticks - since < SETTLE_TICKS) {
            return;
        }
        var runtime = InventoryUiClient.runtime();
        switch (stage) {
            case PREPARE -> prepare(client);
            case OPEN -> open(client, runtime);
            case COMPACT -> compact(client, runtime);
            case TO_CLASSIC -> toClassic(client, runtime);
            case TO_MODERN -> toModern(client, runtime);
            default -> { }
        }
    }

    private void prepare(Minecraft client) {
        if (!(client.gui.screen() instanceof TitleScreen)) {
            return;
        }
        if (!resized) {
            GLFW.glfwSetWindowSize(client.getWindow().handle(), WINDOW_WIDTH, WINDOW_HEIGHT);
            client.options.guiScale().set(GUI_SCALE);
            client.resizeGui();
            resized = true;
            return;
        }
        if (client.getWindow().getGuiScaledWidth() == WINDOW_WIDTH / GUI_SCALE) {
            next(Stage.OPEN);
        }
    }

    private void open(Minecraft client, ClientRuntime runtime) {
        // 不透明背景让截图不受标题画面全景图转动的影响。
        runtime.uiVersion(UiVersion.MODERN);
        runtime.background("kiui:night_meridian");
        runtime.openWorkspace();
        require(runtime.theme().equals("kiui:glass"), "新版没有用琉璃主题");
        var inventory = runtime.open(INVENTORY, null);
        move(runtime, inventory, MODERN_X, MODERN_Y);
        modernBounds = inventory.bounds();
        runtime.open(STATUS, null);
        next(Stage.COMPACT);
    }

    private void compact(Minecraft client, ClientRuntime runtime) {
        if (compactBounds == null) {
            capture(client, "modern-expanded.png");
            var windows = runtime.workspace().windows();
            require(!windows.minimize(window(runtime, INVENTORY).key()), "没登记摘要的窗口不该能收小");
            var status = window(runtime, STATUS);
            require(windows.minimize(status.key()), "登记了摘要的窗口收不成小窗");
            // 这个演示窗口没有登记只读恢复，锁只在本次连接里有效；跨切换保住的是位置和排位，锁由单测覆盖。
            require(runtime.pinWindow(status.key(), true /* pinned */), "小窗锁不上");
            var automatic = runtime.compactBounds(STATUS).orElseThrow();
            drag(client, automatic, COMPACT_DRAG_X, COMPACT_DRAG_Y);
            compactBounds = runtime.compactBounds(STATUS).orElseThrow();
            require(!compactBounds.equals(automatic), "小窗没有被拖动");
            next(Stage.COMPACT);
            return;
        }
        capture(client, "modern-compact.png");
        next(Stage.TO_CLASSIC);
    }

    private void toClassic(Minecraft client, ClientRuntime runtime) {
        if (runtime.uiVersion() == UiVersion.MODERN) {
            runtime.uiVersion(UiVersion.CLASSIC);
            require(runtime.workspace().windowBar().isEmpty(), "切到旧版后新版的窗口还开着");
            require(runtime.theme().equals("kiui:ink_jade"), "旧版没有用回它自己的主题");
            var inventory = runtime.open(INVENTORY, null);
            if (classicBounds == null) {
                move(runtime, inventory, CLASSIC_X, CLASSIC_Y);
                classicBounds = inventory.bounds();
                runtime.savePreferences();
            }
            require(inventory.bounds().equals(classicBounds), "旧版窗口位置丢了：" + inventory.bounds());
            next(Stage.TO_CLASSIC);
            return;
        }
        capture(client, "switch-" + round + "-classic.png");
        next(Stage.TO_MODERN);
    }

    private void toModern(Minecraft client, ClientRuntime runtime) {
        if (runtime.uiVersion() == UiVersion.CLASSIC) {
            runtime.uiVersion(UiVersion.MODERN);
            require(runtime.workspace().windowBar().isEmpty(), "切到新版后旧版的窗口还开着");
            var inventory = runtime.open(INVENTORY, null);
            require(inventory.bounds().equals(modernBounds), "新版窗口位置丢了：" + inventory.bounds());
            var status = runtime.open(STATUS, null);
            require(runtime.workspace().windows().minimize(status.key()), "切回新版后收不成小窗");
            var restored = runtime.compactBounds(STATUS).orElseThrow();
            require(restored.equals(compactBounds), "小窗排位丢了：" + restored);
            next(Stage.TO_MODERN);
            return;
        }
        capture(client, "switch-" + round + "-modern.png");
        round++;
        if (round < SWITCH_ROUNDS) {
            next(Stage.TO_CLASSIC);
            return;
        }
        System.out.println(OK);
        stage = Stage.DONE;
        client.stop();
    }

    private static WindowState window(ClientRuntime runtime, String feature) {
        String type = runtime.catalog().features().get(feature).windowType();
        return runtime.workspace().windowBar().stream()
                .filter(state -> state.key().windowType().equals(type))
                .findFirst()
                .orElseThrow();
    }

    private static void move(ClientRuntime runtime, WindowState window, int x, int y) {
        var windows = runtime.workspace().windows();
        var bounds = window.bounds();
        windows.beginDrag(window.key(), bounds.x(), bounds.y());
        windows.dragTo(x, y);
        windows.endDrag();
    }

    private static void drag(Minecraft client, Rect from, int x, int y) {
        // 走宿主屏的真实鼠标路径：按在小窗左上角以内一点，拖到目标再松开。
        var screen = client.gui.screen();
        var press = mouse(from.x() + 1, from.y() + from.height() - 1);
        var release = mouse(x, y);
        screen.mouseClicked(press, false /* doubleClick */);
        screen.mouseDragged(release, x - press.x(), y - press.y());
        screen.mouseReleased(release);
    }

    private static MouseButtonEvent mouse(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
    }

    private static void capture(Minecraft client, String name) {
        Screenshot.grab(client.gameDirectory, name, client.gameRenderer.mainRenderTarget(), 1, message -> { });
    }

    private void next(Stage target) {
        stage = target;
        since = ticks;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}

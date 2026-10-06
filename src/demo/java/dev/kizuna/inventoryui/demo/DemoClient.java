package dev.kizuna.inventoryui.demo;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.BuiltinThemes;
import dev.kizuna.inventoryui.window.UiWindowManager;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;

import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 开发演示启动后打开工作台；smoke 模式截屏并退出，不连接业务服务器。 */
public final class DemoClient implements ClientModInitializer {
    // 演示窗口使用物理像素尺寸，点击坐标则按固定 GUI scale 的逻辑像素书写。
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;
    private static final int GUI_SCALE = 2;
    private static final int STATUS_WINDOW_X = 306;
    private static final int STATUS_WINDOW_Y = 60;

    // smoke 时间线按客户端 tick 编排，预留资源加载和界面绘制时间。
    private static final int SMOKE_TIMEOUT_TICKS = 1200;
    private static final int ACCEPT_MOVE_TICK = 20;
    private static final int REJECT_MOVE_TICK = 30;
    private static final int SWITCH_THEME_TICK = 40;
    private static final int CAPTURE_LIGHT_THEME_TICK = 45;
    private static final int OPEN_HUD_TICK = 50;
    private static final int CAPTURE_HUD_TICK = 55;
    private static final int RESTORE_THEME_TICK = 60;
    private static final int CANCEL_DRAG_TICK = 65;
    private static final int CAPTURE_WORKSPACE_TICK = 80;
    private static final int OPEN_CASE_TICK = 90;
    private static final int TRANSFER_TICK = 100;
    private static final int SPLIT_TICK = 110;
    private static final int BIND_TICK = 120;
    private static final int CAPTURE_FRAMEWORK_TICK = 130;
    private static final int OPEN_COMPONENTS_TICK = 132;
    private static final int CAPTURE_COMPONENTS_TICK = 145;
    private static final int OPEN_SIZE_TICK = 155;
    private static final int APPLY_SIZE_TICK = 160;
    private static final int PIN_WINDOW_TICK = 170;
    private static final int MINIMIZE_WINDOW_TICK = 180;
    private static final int RESTORE_WINDOW_TICK = 190;
    private static final int CAPTURE_CHROME_TICK = 200;
    private static final int OPEN_BACKGROUNDS_TICK = 210;
    private static final int CAPTURE_BACKGROUNDS_TICK = 220;
    private static final int FINISH_SMOKE_TICK = 230;
    private static final int SIZE_ACTION_OFFSET = 75;
    private static final int PIN_ACTION_OFFSET = 31;
    private static final int MINIMIZE_ACTION_OFFSET = 53;
    private static final int HEADER_CLICK_Y = 10;
    private static final int CASE_WINDOW_X = 330;
    private static final int CASE_WINDOW_Y = 60;
    private static final int FRUIT_COLUMN = 3;
    private static final int FRUIT_ROW = 1;
    private static final int SLOT_BIND_X = 12;
    private static final int SLOT_BIND_BOTTOM_OFFSET = 40;
    private static final int COMPONENT_WINDOW_WIDTH = 245;
    private static final int COMPONENT_WINDOW_HEIGHT = 245;

    // 相对库存窗口左上角的测试命中点，移动距离由示例网格格子尺寸推导。
    private static final int GRID_GRAB_X = 10;
    private static final int GRID_GRAB_Y = 30;
    private static final int ACCEPT_MOVE_COLUMNS = 3;
    private static final int REJECT_MOVE_COLUMNS = 2;
    private static final int ACCEPT_MOVE_DISTANCE = ACCEPT_MOVE_COLUMNS * DemoModule.CELL_SIZE;
    private static final int REJECT_MOVE_DISTANCE = REJECT_MOVE_COLUMNS * DemoModule.CELL_SIZE;
    private static final int MOVED_ITEM_GRAB_X = GRID_GRAB_X + ACCEPT_MOVE_DISTANCE;
    private static final int REJECT_BUTTON_X = 14;
    private static final int REJECT_BUTTON_Y = 118;
    private static final int HUD_MENU_X = 100;
    private static final int HUD_MENU_Y = 12;

    private boolean opened;
    private int ticks;
    private int totalTicks;
    private UiWindowManager.WindowState inventory;
    private UiWindowManager.WindowState status;
    private UiWindowManager.WindowState storage;

    @Override
    public void onInitializeClient() {
        // 普通演示停留在可交互工作台；只有显式启用 smoke 才自动操作、截屏并退出。
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    boolean smoke = Boolean.getBoolean("kiui.smoke");
                    if (smoke && ++totalTicks > SMOKE_TIMEOUT_TICKS) {
                        throw new IllegalStateException("demo smoke timed out");
                    }
                    if (client.currentScreen instanceof AccessibilityOnboardingScreen) {
                        client.setScreen(new TitleScreen());
                    }
                    if (!opened
                            && client.currentScreen instanceof TitleScreen
                            && client.getOverlay() == null) {
                        // 等待资源加载遮罩消失再打开窗口，确保 owo 组件能够正确挂载和测量。
                        GLFW.glfwSetWindowSize(
                                client.getWindow().getHandle(), WINDOW_WIDTH, WINDOW_HEIGHT);
                        client.options.getGuiScale().setValue(GUI_SCALE);
                        client.onResolutionChanged();
                        var runtime = InventoryUiClient.runtime();
                        runtime.openWorkspace();
                        inventory = runtime.open("demo:inventory", null);
                        status = runtime.open("demo:status", null);
                        runtime.workspace()
                                .windows()
                                .beginDrag(status.key(), status.bounds().x(), status.bounds().y());
                        runtime.workspace().windows().dragTo(STATUS_WINDOW_X, STATUS_WINDOW_Y);
                        runtime.workspace().windows().endDrag();
                        opened = true;
                    }
                    if (opened && smoke && ticks == ACCEPT_MOVE_TICK) {
                        // 从真实组件的鼠标入口发起移动，确认回调产生新的模拟权威版本。
                        var screen = client.currentScreen;
                        var bounds = inventory.bounds();
                        double x = bounds.x() + GRID_GRAB_X;
                        double y = bounds.y() + GRID_GRAB_Y;
                        screen.mouseClicked(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        screen.mouseDragged(
                                x + ACCEPT_MOVE_DISTANCE,
                                y,
                                GLFW.GLFW_MOUSE_BUTTON_LEFT,
                                ACCEPT_MOVE_DISTANCE,
                                0);
                        screen.mouseReleased(
                                x + ACCEPT_MOVE_DISTANCE, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        require(
                                demo().revision() == 1,
                                "drag must submit an accepted move through the component");
                    }
                    if (opened && smoke && ticks == REJECT_MOVE_TICK) {
                        // 同一拖动路径被模拟端拒绝后，库存版本和原位置应保持不变。
                        var screen = client.currentScreen;
                        var bounds = inventory.bounds();
                        screen.mouseClicked(
                                bounds.x() + REJECT_BUTTON_X,
                                bounds.y() + REJECT_BUTTON_Y,
                                GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        screen.mouseReleased(
                                bounds.x() + REJECT_BUTTON_X,
                                bounds.y() + REJECT_BUTTON_Y,
                                GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        double x = bounds.x() + MOVED_ITEM_GRAB_X;
                        double y = bounds.y() + GRID_GRAB_Y;
                        screen.mouseClicked(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        screen.mouseDragged(
                                x + REJECT_MOVE_DISTANCE,
                                y,
                                GLFW.GLFW_MOUSE_BUTTON_LEFT,
                                REJECT_MOVE_DISTANCE,
                                0);
                        screen.mouseReleased(
                                x + REJECT_MOVE_DISTANCE, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        require(
                                demo().revision() == 1,
                                "rejected move must preserve the authoritative snapshot");
                    }
                    if (opened && smoke && ticks == SWITCH_THEME_TICK) {
                        // 验证恢复单例不会重新创建窗口，并切换主题和背景触发真实绘制。
                        var runtime = InventoryUiClient.runtime();
                        runtime.workspace().windows().minimize(status.key());
                        require(
                                runtime.open("demo:status", null) == status && !status.minimized(),
                                "opening a singleton must restore the same window");
                        runtime.theme(BuiltinThemes.LIGHT.id());
                        runtime.background("demo:stone");
                    }
                    if (opened && smoke && ticks == CAPTURE_LIGHT_THEME_TICK) {
                        // 在菜单覆盖网格前记录浅色主题，验证同一网格实例随主题切换更新颜色。
                        ScreenshotRecorder.saveScreenshot(
                                client.runDirectory,
                                "theme-light.png",
                                client.getFramebuffer(),
                                message -> {});
                    }
                    if (opened && smoke && ticks == RESTORE_THEME_TICK) {
                        InventoryUiClient.runtime().theme(BuiltinThemes.DARK.id());
                        InventoryUiClient.runtime().background(null);
                    }
                    if (opened && smoke && ticks == OPEN_HUD_TICK) {
                        // 打开 HUD 管理入口，使真实绘制器在没有游戏世界时也参与验收。
                        client.currentScreen.mouseClicked(
                                HUD_MENU_X, HUD_MENU_Y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        client.currentScreen.mouseReleased(
                                HUD_MENU_X, HUD_MENU_Y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    }
                    if (opened && smoke && ticks == CAPTURE_HUD_TICK) {
                        ScreenshotRecorder.saveScreenshot(
                                client.runDirectory,
                                "hud-preview.png",
                                client.getFramebuffer(),
                                message -> {});
                    }
                    if (opened && smoke && ticks == CANCEL_DRAG_TICK) {
                        // 在拖动中关闭工作台，再重开并释放鼠标，旧交互不得补发移动请求。
                        client.currentScreen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                        InventoryUiClient.runtime().open("demo:inventory", null);
                        var bounds = inventory.bounds();
                        client.currentScreen.mouseClicked(
                                bounds.x() + MOVED_ITEM_GRAB_X,
                                bounds.y() + GRID_GRAB_Y,
                                GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        client.currentScreen.close();
                        InventoryUiClient.runtime().openWorkspace();
                        client.currentScreen.mouseReleased(
                                bounds.x() + MOVED_ITEM_GRAB_X + REJECT_MOVE_DISTANCE,
                                bounds.y() + GRID_GRAB_Y,
                                GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        require(
                                demo().revision() == 1,
                                "closing a workspace must cancel an in-progress drag");
                    }
                    if (opened && smoke && ++ticks == CAPTURE_WORKSPACE_TICK) {
                        ScreenshotRecorder.saveScreenshot(
                                client.runDirectory,
                                "workspace.png",
                                client.getFramebuffer(),
                                message -> {});
                    }
                    if (opened && smoke && ticks == OPEN_CASE_TICK) {
                        var runtime = InventoryUiClient.runtime();
                        runtime.workspace().windows().minimize(status.key());
                        storage = runtime.open("demo:case", null);
                        runtime.workspace()
                                .windows()
                                .beginDrag(
                                        storage.key(), storage.bounds().x(), storage.bounds().y());
                        runtime.workspace().windows().dragTo(CASE_WINDOW_X, CASE_WINDOW_Y);
                        runtime.workspace().windows().endDrag();
                    }
                    if (opened && smoke && ticks == TRANSFER_TICK) {
                        // 跨窗口释放必须到达鼠标下的容器，而不是回到来源窗口组件树。
                        var screen = client.currentScreen;
                        screen.mouseClicked(
                                inventory.bounds().x() + MOVED_ITEM_GRAB_X,
                                inventory.bounds().y() + GRID_GRAB_Y,
                                GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        screen.mouseReleased(
                                storage.bounds().x() + GRID_GRAB_X,
                                storage.bounds().y() + GRID_GRAB_Y,
                                GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        require(
                                demo().snapshot().placements().stream()
                                        .anyMatch(
                                                value ->
                                                        value.item().instanceId().equals("tool-1")
                                                                && value.containerId()
                                                                        .equals("case")),
                                "cross-window release must move to the target container");
                    }
                    if (opened && smoke && ticks == SPLIT_TICK) {
                        // 中键拆半堆并跨窗落下，源堆数量与服务器分配的新实例同时更新。
                        var screen = client.currentScreen;
                        screen.mouseClicked(
                                inventory.bounds().x()
                                        + GRID_GRAB_X
                                        + FRUIT_COLUMN * DemoModule.CELL_SIZE,
                                inventory.bounds().y()
                                        + GRID_GRAB_Y
                                        + FRUIT_ROW * DemoModule.CELL_SIZE,
                                GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
                        screen.mouseReleased(
                                storage.bounds().x() + GRID_GRAB_X,
                                storage.bounds().y() + GRID_GRAB_Y + DemoModule.CELL_SIZE,
                                GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
                        require(
                                demo().snapshot().placements().stream()
                                                .filter(
                                                        value ->
                                                                value.item()
                                                                        .itemId()
                                                                        .equals("demo:fruit"))
                                                .mapToInt(value -> value.item().count())
                                                .sum()
                                        == 8,
                                "split must conserve total count");
                        require(
                                demo().snapshot().placements().stream()
                                                .filter(
                                                        value ->
                                                                value.item()
                                                                        .itemId()
                                                                        .equals("demo:fruit"))
                                                .count()
                                        == 2,
                                "split must create two authoritative stacks");
                    }
                    if (opened && smoke && ticks == BIND_TICK) {
                        // 底栏消费的是绑定意图，库存数量与位置不随本地绑定改变。
                        var before = demo().snapshot();
                        var screen = client.currentScreen;
                        screen.mouseClicked(
                                storage.bounds().x() + GRID_GRAB_X,
                                storage.bounds().y() + GRID_GRAB_Y,
                                GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        screen.mouseReleased(
                                SLOT_BIND_X,
                                client.getWindow().getScaledHeight() - SLOT_BIND_BOTTOM_OFFSET,
                                GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        require(
                                demo().hasBinding(),
                                "registered bottom slot must receive drag payload");
                        require(
                                demo().snapshot().equals(before),
                                "binding must not move inventory items");
                    }
                    if (opened && smoke && ticks == CAPTURE_FRAMEWORK_TICK) {
                        ScreenshotRecorder.saveScreenshot(
                                client.runDirectory,
                                "framework.png",
                                client.getFramebuffer(),
                                message -> {});
                    }
                    if (opened && smoke && ticks == OPEN_COMPONENTS_TICK) {
                        var runtime = InventoryUiClient.runtime();
                        var components = runtime.open("demo:components", null);
                        runtime.workspace()
                                .windows()
                                .resize(
                                        components.key(),
                                        Integer.toString(COMPONENT_WINDOW_WIDTH),
                                        Integer.toString(COMPONENT_WINDOW_HEIGHT));
                    }
                    if (opened && smoke && ticks == CAPTURE_COMPONENTS_TICK) {
                        ScreenshotRecorder.saveScreenshot(
                                client.runDirectory,
                                "components.png",
                                client.getFramebuffer(),
                                message -> {});
                    }
                    if (opened && smoke && ticks == OPEN_SIZE_TICK) {
                        // 从真实标题栏进入宽高输入，覆盖外框动作与文本焦点接线。
                        var runtime = InventoryUiClient.runtime();
                        runtime.open("demo:inventory", null);
                        click(
                                client.currentScreen,
                                inventory.bounds().x()
                                        + inventory.bounds().width()
                                        - SIZE_ACTION_OFFSET,
                                inventory.bounds().y() + HEADER_CLICK_Y);
                    }
                    if (opened && smoke && ticks == APPLY_SIZE_TICK) {
                        var screen = client.currentScreen;
                        for (int i = 0; i < 10; i++) {
                            screen.keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0);
                        }
                        for (char digit : "320".toCharArray()) {
                            screen.charTyped(digit, 0);
                        }
                        screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
                        require(
                                inventory.desiredBounds().width() == 320,
                                "size editor must apply typed width");
                    }
                    if (opened && smoke && ticks == PIN_WINDOW_TICK) {
                        click(
                                client.currentScreen,
                                inventory.bounds().x()
                                        + inventory.bounds().width()
                                        - PIN_ACTION_OFFSET,
                                inventory.bounds().y() + HEADER_CLICK_Y);
                        require(
                                inventory.hudVisible(),
                                "title pin action must expose the existing window to HUD");
                    }
                    if (opened && smoke && ticks == MINIMIZE_WINDOW_TICK) {
                        click(
                                client.currentScreen,
                                inventory.bounds().x()
                                        + inventory.bounds().width()
                                        - MINIMIZE_ACTION_OFFSET,
                                inventory.bounds().y() + HEADER_CLICK_Y);
                        require(
                                inventory.minimized() && !inventory.hudVisible(),
                                "minimize must also hide pinned HUD");
                    }
                    if (opened && smoke && ticks == RESTORE_WINDOW_TICK) {
                        var runtime = InventoryUiClient.runtime();
                        require(
                                runtime.open("demo:inventory", null) == inventory,
                                "restore must retain the same component instance");
                        require(inventory.hudVisible(), "restore must retain the HUD pin");
                        verifyLocalBackground(client);
                    }
                    if (opened && smoke && ticks == CAPTURE_CHROME_TICK) {
                        ScreenshotRecorder.saveScreenshot(
                                client.runDirectory,
                                "window-chrome.png",
                                client.getFramebuffer(),
                                message -> {});
                    }
                    if (opened && smoke && ticks == OPEN_BACKGROUNDS_TICK) {
                        // 外观列表第六项是缩略图入口，打开后由真实资源绘制卡片。
                        click(client.currentScreen, 185, 12);
                        click(client.currentScreen, 70, 162);
                    }
                    if (opened && smoke && ticks == CAPTURE_BACKGROUNDS_TICK) {
                        ScreenshotRecorder.saveScreenshot(
                                client.runDirectory,
                                "background-gallery.png",
                                client.getFramebuffer(),
                                message -> {});
                    }
                    if (opened && smoke && ticks == FINISH_SMOKE_TICK) {
                        // 截图存在且窗口生命周期清理通过后才写成功标记，最后正常退出客户端。
                        if (!Files.isRegularFile(
                                client.runDirectory
                                        .toPath()
                                        .resolve("screenshots/workspace.png"))) {
                            throw new IllegalStateException("smoke screenshot missing");
                        }
                        InventoryUiClient.runtime().workspace().disconnect();
                        require(
                                inventory.scope().isClosed()
                                        && status.scope().isClosed()
                                        && storage.scope().isClosed(),
                                "disconnect must close window scopes");
                        require(
                                InventoryUiClient.runtime().workspace().windowBar().isEmpty(),
                                "disconnect must clear window identities");
                        try {
                            Files.writeString(
                                    Path.of(client.runDirectory.toString(), "smoke-ok.txt"),
                                    "client initialized; drag accepted; rejection preserved"
                                        + " snapshot; singleton restored; themes, background and"
                                        + " HUD rendered; closing cancels drag; disconnect clears"
                                        + " scopes; cross-window transfer; stack split; slot"
                                        + " binding\n");
                        } catch (IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                        client.scheduleStop();
                    }
                });
    }

    private static void verifyLocalBackground(net.minecraft.client.MinecraftClient client) {
        // 用现有资源构造隔离的本地文件，覆盖真实纹理上传、失败回退和缩略图路径。
        var directory = client.runDirectory.toPath().resolve("backgrounds");
        try {
            Files.createDirectories(directory);
            try (var source =
                    client.getResourceManager()
                            .open(
                                    new net.minecraft.util.Identifier(
                                            "kizuna_inventory_ui",
                                            "textures/gui/workspace/cosmos.png"))) {
                Files.copy(
                        source,
                        directory.resolve("fixture.png"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(directory.resolve("broken.png"), "not an image");
            var runtime = InventoryUiClient.runtime();
            runtime.configureBackgroundDirectory(directory);
            runtime.background("local:fixture.png");
            try {
                runtime.background("local:broken.png");
                throw new IllegalStateException("broken local background must be rejected");
            } catch (IllegalArgumentException expected) {
                require(
                        "local:fixture.png".equals(runtime.background()),
                        "failed replacement must retain the selected background");
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void click(net.minecraft.client.gui.screen.Screen screen, double x, double y) {
        // 完整点击包含释放，避免下个验收步骤继承鼠标按下捕获。
        screen.mouseClicked(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        screen.mouseReleased(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    private static DemoModule demo() {
        // 从 Fabric 的已加载入口取同一个示例模块，避免另建实例导致验收读错状态。
        return FabricLoader.getInstance()
                .getEntrypoints("kizuna_inventory_ui", ClientModule.class)
                .stream()
                .filter(DemoModule.class::isInstance)
                .map(DemoModule.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static void require(boolean condition, String message) {
        // smoke 失败必须中断并保留具体契约提示，不能仍然生成成功标记。
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}

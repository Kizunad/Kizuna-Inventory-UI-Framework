package dev.kizuna.inventoryui.client;

import dev.kizuna.inventoryui.client.status.StatusIcons;
import dev.kizuna.inventoryui.client.status.StatusLayout;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowDefinition;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import io.wispforest.owo.ui.component.UIComponents;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.lwjgl.glfw.GLFW;

/** 隔离世界中的只读时钟。每次订阅从本次整合服务器的线程上读取新快照。 */
public final class CompactClientJourney implements ClientModule, CompactProvider, RestoreBinding {
    private static final String FEATURE = "sample:clock";
    private static final String TYPE = "sample:clock_window";
    private static final int WIDTH = 86;
    private static final int HEIGHT = 22;
    private static final int SETTLE_TICKS = 30;
    private static final int MAX_TICKS = 2400;
    private static final int EFFECT_TICKS = 2400;
    private enum Stage { OPEN, LOCK, EDIT, EXPAND, HUD, HIDDEN, DISCONNECT, REJOIN, DONE }
    private Stage stage = Stage.OPEN;
    private int ticks;
    private int since;
    private int draws;
    private int beforeHidden;
    private int observed;
    private int released;
    private long value;
    private long previous;
    private WindowState window;
    private Rect expanded;

    public FrameworkCatalog.Module definition() {
        var def = new UiWindowDefinition(TYPE, TYPE, 140, 80, Set.of(UiWindowDefinition.Capability.WINDOW));
        return FrameworkCatalog.Module.of("sample:module", Set.of(), new FrameworkCatalog.Window(def),
                new FrameworkCatalog.Feature(FEATURE, "时钟", "测试", TYPE, TYPE));
    }

    public void register(ClientBindings b) {
        b.window(TYPE, (r, s) -> UIComponents.label(Component.literal("时钟")));
        b.compact(TYPE, this);
        b.restore(FEATURE, this);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    public List<CompactVariant> variants() {
        return List.of(new CompactVariant("time", "sample.compact.time", WIDTH, HEIGHT));
    }

    public void draw(GuiGraphicsExtractor g, WindowState state, String variant, float delta) {
        require(value > 0, "绘制早于快照");
        draws++;
        g.text(Minecraft.getInstance().font, "T " + value, 1, 1, net.minecraft.util.CommonColors.WHITE);
    }

    public RestoreReadiness readiness() {
        var c = Minecraft.getInstance();
        if (c.player == null || c.getSingleplayerServer() == null) {
            return RestoreReadiness.WAITING;
        }
        return RestoreReadiness.READY;
    }

    public void observe(WindowState state, Consumer<RestoreStatus> status) {
        var client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        observed++;
        var sample = CompletableFuture.supplyAsync(() -> server.overworld().getGameTime(), server);
        state.scope().addCleanup(() -> {
            released++;
            value = 0;
            sample.cancel(false /* mayInterruptIfRunning */);
        });
        sample.thenAcceptAsync(time -> state.scope().runIfOpen(() -> {
            value = time;
            status.accept(RestoreStatus.LIVE);
        }), client);
    }

    private void tick(Minecraft c) {
        if (stage == Stage.DONE) {
            return;
        }
        try {
            require(++ticks < MAX_TICKS, "超时 " + stage);
            if (ticks - since < SETTLE_TICKS || c.gui.overlay() != null) {
                return;
            }
            switch (stage) {
                case OPEN -> open(c);
                case LOCK -> lock(c);
                case EDIT -> edit(c);
                case EXPAND -> expand(c);
                case HUD -> hud(c);
                case HIDDEN -> hidden(c);
                case DISCONNECT -> reconnect(c);
                case REJOIN -> rejoined(c);
                default -> { }
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(c, failure.toString());
        }
    }

    private void open(Minecraft c) {
        if (c.player == null || c.getSingleplayerServer() == null || c.gui.screen() != null) {
            return;
        }
        c.options.guiScale().set(2);
        GLFW.glfwSetWindowSize(c.getWindow().handle(), Integer.getInteger("sample.width", 854),
                Integer.getInteger("sample.height", 480));
        c.resizeGui();
        var server = c.getSingleplayerServer();
        var player = c.player.getUUID();
        server.execute(() -> server.getPlayerList().getPlayer(player)
                .addEffect(new MobEffectInstance(MobEffects.SPEED, EFFECT_TICKS)));
        var runtime = InventoryUiClient.runtime();
        // 小窗是新版界面的功能，缺省是旧版。
        runtime.uiVersion(dev.kizuna.inventoryui.workspace.UiVersion.MODERN);
        runtime.openWorkspace();
        window = runtime.open(FEATURE, null);
        next(Stage.LOCK);
    }

    private void lock(Minecraft c) {
        if (value <= 0 || window.closed()) {
            return;
        }
        var layer = InventoryUiClient.runtime().layer(c.gui.screen()).orElseThrow();
        pressHeader(layer, ModernChrome.Action.PIN);
        require(window.pinned(), "展开锁定失败");
        next(Stage.EDIT);
    }

    private void edit(Minecraft c) {
        if (value <= 0 || window.closed()) {
            return;
        }
        var host = c.gui.screen();
        var runtime = InventoryUiClient.runtime();
        var layer = runtime.layer(host).orElseThrow();
        capture(c, "expanded");
        expanded = window.bounds();
        pressHeader(layer, ModernChrome.Action.MINIMIZE);
        require(window.minimized(), "收小失败");
        var outer = layer.blockedAreas().getLast();
        int x = outer.left() + 1;
        int y = outer.bottom() - 1;
        int targetX = host.width - outer.width() + 1;
        int targetY = ModernMetrics.TOOLBAR_HEIGHT + outer.height() - 1;
        require(layer.mouseClicked(left(x, y), false /* doubleClick */), "漏接拖动");
        layer.mouseDragged(left(targetX, targetY), targetX - x, targetY - y);
        layer.mouseReleased(left(targetX, targetY));
        var moved = layer.blockedAreas().getLast();
        require(moved.left() > host.width / 2, "未移至右侧");
        next(Stage.EXPAND);
    }

    private void expand(Minecraft c) {
        capture(c, "compact");
        var layer = InventoryUiClient.runtime().layer(c.gui.screen()).orElseThrow();
        var moved = layer.blockedAreas().getLast();
        var right = new Rect(moved.left(), moved.top(), moved.width(), moved.height());
        var expand = ModernChrome.actionBounds(InventoryUiClient.runtime(), window, right, ModernChrome.Action.RESTORE);
        layer.mouseClicked(left(expand.x() + 1, expand.y() + 1), false /* doubleClick */);
        require(!window.minimized() && window.bounds().equals(expanded), "展开矩形丢失");
        previous = value;
        c.gui.setScreen(null);
        next(Stage.HUD);
    }

    private void pressHeader(WindowLayer layer, ModernChrome.Action action) {
        var b = ModernChrome.actionBounds(InventoryUiClient.runtime(), window, window.bounds(), action);
        layer.mouseClicked(left(b.x() + 1, b.y() + 1), false /* doubleClick */);
    }

    private void hud(Minecraft c) {
        var w = c.getWindow().getGuiScaledWidth();
        var h = c.getWindow().getGuiScaledHeight();
        var frame = StatusIcons.lastLayout();
        var projection = InventoryUiClient.runtime().modern().hudProjections(w, h).getFirst();
        var area = projection.area();
        require(draws > 0 && frame.blocked().contains(area), "HUD 未共用小窗外框");
        require(!frame.cells().isEmpty(), "缺少用于验证避让的状态图标");
        require(!frame.cells().getFirst().equals(StatusLayout.hudCells(w, h).getFirst()), "状态图标仍在原位");
        require(frame.cells().stream().noneMatch(cell -> overlaps(cell, area)), "状态图标压住小窗");
        require(!area.contains(w / 2, h / 2) && area.bottom() <= h - StatusLayout.BOTTOM_RESERVE_PIXELS,
                "遮住准星或快捷栏");
        capture(c, "hud");
        System.out.println("COMPACT_HUD " + w + "x" + h + " " + area + " cells=" + frame.cells());
        c.gui.hud.toggle();
        beforeHidden = draws;
        next(Stage.HIDDEN);
    }

    private static boolean overlaps(StatusLayout.Area a, StatusLayout.Area b) {
        return a.left() < b.right() && b.left() < a.right() && a.top() < b.bottom() && b.top() < a.bottom();
    }

    private void hidden(Minecraft c) {
        require(c.gui.hud.isHidden() && beforeHidden == draws, "F1 期间仍绘制小窗");
        capture(c, "hidden");
        c.gui.hud.toggle();
        c.disconnectWithSavingScreen();
        next(Stage.DISCONNECT);
    }

    private void reconnect(Minecraft c) {
        if (c.getSingleplayerServer() != null) {
            return;
        }
        require(released == 1 && window.closed(), "断线未释放观察");
        c.createWorldOpenFlows().openWorld("inner-vision-test", () -> {});
        next(Stage.REJOIN);
    }

    private void rejoined(Minecraft c) {
        if (c.player == null || c.gui.screen() != null || value <= previous) {
            return;
        }
        var w = c.getWindow();
        var hud = InventoryUiClient.runtime().modern().hudProjections(w.getGuiScaledWidth(), w.getGuiScaledHeight());
        require(observed == 2 && hud.size() == 1 && hud.getFirst().state() != window, "恢复次数或身份错误");
        if (ticks - since < SETTLE_TICKS * 2) {
            return;
        }
        capture(c, "rejoined");
        System.out.println("COMPACT_REJOIN " + previous + " -> " + value + " observed=" + observed);
        finish(c, null);
    }

    private static MouseButtonEvent left(int x, int y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
    }

    private static void capture(Minecraft c, String scene) {
        String name = "s3-" + c.getWindow().getWidth() + "-" + scene + ".png";
        Screenshot.grab(c.gameDirectory, name, c.gameRenderer.mainRenderTarget(), 1, msg -> {});
    }

    private void next(Stage next) {
        stage = next;
        since = ticks;
    }

    private static void require(boolean ok, String reason) {
        if (!ok) {
            throw new IllegalStateException(reason);
        }
    }

    private void finish(Minecraft c, String failure) {
        stage = Stage.DONE;
        try {
            Files.writeString(Path.of("../inner-vision-results.txt"),
                    "success=" + (failure == null) + "\nfailure=" + failure + "\n");
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
        c.stop();
    }
}

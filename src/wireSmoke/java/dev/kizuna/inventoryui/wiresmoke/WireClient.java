package dev.kizuna.inventoryui.wiresmoke;

import dev.kizuna.inventoryui.client.*;
import dev.kizuna.inventoryui.protocol.*;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowDefinition;

import io.wispforest.owo.ui.component.Components;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.*;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Set;

/** 实际网络验收与固定 HUD 截图；仅测试源集存在，不打入框架和演示 JAR。 */
public final class WireClient implements ClientModInitializer, ClientModule {
    private static final String ADDRESS = "127.0.0.1:25576";
    private static final long TIMEOUT_MS = 90_000;
    private static final int CAPTURE_DELAY_TICKS = 30;
    private static int state;
    private static boolean accepted;
    private static boolean rejected;
    private boolean connecting;
    private long started;
    private int readyTicks;

    public record Value(int value) {}

    @Override
    public FrameworkCatalog.Module definition() {
        return FrameworkCatalog.Module.of(
                "wire:fixture",
                Set.of("kiui:core"),
                new FrameworkCatalog.Window(
                        new UiWindowDefinition(
                                "wire:window",
                                "wire:window",
                                180,
                                60,
                                Set.of(UiWindowDefinition.Capability.WINDOW))),
                new FrameworkCatalog.Feature(
                        "wire:feature", "网络状态", "测试", "wire:icon", "wire:window"));
    }

    @Override
    public void register(ClientBindings bindings) {
        // 两条有方向的契约分别证明入站路由与出站关联；状态字段由真实服务器写入。
        bindings.message(
                new UiWire.Contract<>(
                        new UiMessageRouter.Contract<>(
                                "wire:fixture",
                                "wire:state",
                                1,
                                UiMessageRouter.Direction.SERVER_TO_CLIENT,
                                Value.class,
                                value -> state = value.value()),
                        UiWire.Codec.record(Value.class),
                        true));
        bindings.message(
                new UiWire.Contract<>(
                        new UiMessageRouter.Contract<>(
                                "wire:fixture",
                                "wire:change",
                                1,
                                UiMessageRouter.Direction.CLIENT_TO_SERVER,
                                Value.class,
                                null),
                        UiWire.Codec.record(Value.class),
                        true));
        bindings.window(
                "wire:window",
                (runtime, window) -> Components.label(Text.literal("服务器状态：" + state)));
    }

    @Override
    public void protocolReady(ClientRuntime runtime) {
        // 一个接受和一个拒绝请求走真实服务端，不能只验证写入客户端发送队列。
        runtime.protocol()
                .request("wire:change", new Value(1), System.currentTimeMillis())
                .thenAccept(outcome -> accepted = outcome.accepted());
        runtime.protocol()
                .request("wire:change", new Value(0), System.currentTimeMillis())
                .thenAccept(
                        outcome ->
                                rejected =
                                        !outcome.accepted() && outcome.code().equals("REJECTED"));
    }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    // 资源加载完成后连接隔离的本机端口，整个验收有明确超时。
                    if (!Boolean.getBoolean("kiui.wireSmoke")) {
                        return;
                    }
                    if (started == 0) {
                        started = System.currentTimeMillis();
                    }
                    if (System.currentTimeMillis() - started > TIMEOUT_MS) {
                        throw new IllegalStateException(
                                "wire smoke timed out: "
                                        + InventoryUiClient.runtime().protocolError());
                    }
                    if (client.currentScreen instanceof AccessibilityOnboardingScreen) {
                        client.setScreen(new TitleScreen());
                    }
                    if (!connecting
                            && client.currentScreen instanceof TitleScreen
                            && client.getOverlay() == null) {
                        connecting = true;
                        ConnectScreen.connect(
                                new TitleScreen(),
                                client,
                                ServerAddress.parse(ADDRESS),
                                new ServerInfo("Local wire fixture", ADDRESS, false),
                                false);
                    }
                    if (client.player != null && state == 7 && accepted && rejected) {
                        var runtime = InventoryUiClient.runtime();
                        if (readyTicks++ == 0) {
                            // 工作台打开窗口后固定，再关闭主屏，实际 HudRenderCallback 必须继续绘制同一实例。
                            runtime.openWorkspace();
                            var window = runtime.open("wire:feature", null);
                            if (!runtime.pinWindow(window.key(), true)) {
                                throw new IllegalStateException("fixture window cannot pin");
                            }
                            client.setScreen(null);
                        }
                        if (readyTicks == CAPTURE_DELAY_TICKS) {
                            ScreenshotRecorder.saveScreenshot(
                                    client.runDirectory,
                                    "pinned-hud.png",
                                    client.getFramebuffer(),
                                    message -> {});
                            try {
                                Files.writeString(
                                        client.runDirectory.toPath().resolve("wire-client-ok.txt"),
                                        "state=7; accepted=true; rejected=true; pinned HUD"
                                            + " rendered\n");
                            } catch (IOException failure) {
                                throw new UncheckedIOException(failure);
                            }
                            client.scheduleStop();
                        }
                    }
                });
    }
}

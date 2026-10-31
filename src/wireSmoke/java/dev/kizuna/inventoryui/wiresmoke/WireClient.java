package dev.kizuna.inventoryui.wiresmoke;

import dev.kizuna.inventoryui.client.*;
import dev.kizuna.inventoryui.protocol.*;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowDefinition;
import dev.kizuna.inventoryui.wiresmoke.pb.SmokeProtocol;

import io.wispforest.owo.ui.component.UIComponents;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

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
    private HudVisibilityProbe hudProbe;

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
                                SmokeProtocol.Value.class,
                                value -> state = value.getValue()),
                        UiWire.Codec.protobuf(SmokeProtocol.Value.getDefaultInstance()),
                        true));
        bindings.message(
                new UiWire.Contract<>(
                        new UiMessageRouter.Contract<>(
                                "wire:fixture",
                                "wire:change",
                                1,
                                UiMessageRouter.Direction.CLIENT_TO_SERVER,
                                SmokeProtocol.Value.class,
                                null),
                        UiWire.Codec.protobuf(SmokeProtocol.Value.getDefaultInstance()),
                        true));
        bindings.window(
                "wire:window",
                (runtime, window) -> UIComponents.label(Component.literal("服务器状态：" + state)));
    }

    @Override
    public void protocolReady(ClientRuntime runtime) {
        // 一个接受和一个拒绝请求走真实服务端，不能只验证写入客户端发送队列。
        runtime.protocol()
                .request(
                        "wire:change",
                        SmokeProtocol.Value.newBuilder().setValue(1).build(),
                        System.currentTimeMillis())
                .thenAccept(outcome -> accepted = outcome.accepted());
        runtime.protocol()
                .request(
                        "wire:change",
                        SmokeProtocol.Value.getDefaultInstance(),
                        System.currentTimeMillis())
                .thenAccept(
                        outcome ->
                                rejected =
                                        !outcome.accepted() && outcome.code().equals("REJECTED"));
    }

    @Override
    public void onInitializeClient() {
        // HUD 探针的第二阶段挂在 HUD 最后：探针关闭后原版 HUD 应先于本元素提交内容。
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("kiui_wire_smoke", "hud_probe"),
                (context, deltaTracker) -> {
                    if (hudProbe != null) {
                        hudProbe.observeHud(context);
                    }
                });
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
                    if (client.gui.screen() instanceof AccessibilityOnboardingScreen) {
                        client.gui.setScreen(new TitleScreen());
                    }
                    if (!connecting
                            && client.gui.screen() instanceof TitleScreen
                            && client.gui.overlay() == null) {
                        connecting = true;
                        ConnectScreen.startConnecting(
                                new TitleScreen(),
                                client,
                                ServerAddress.parseString(ADDRESS),
                                new ServerData("Local wire fixture", ADDRESS, ServerData.Type.OTHER),
                                false,
                                null);
                    }
                    if (client.player != null && state == 7 && accepted && rejected) {
                        var runtime = InventoryUiClient.runtime();
                        if (readyTicks++ == 0) {
                            // 工作台打开窗口后固定，再关闭主屏，实际 HUD 元素回调必须继续绘制同一实例。
                            runtime.openWorkspace();
                            var window = runtime.open("wire:feature", null);
                            if (!runtime.pinWindow(window.key(), true)) {
                                throw new IllegalStateException("fixture window cannot pin");
                            }
                            client.gui.setScreen(null);
                        }
                        if (readyTicks == CAPTURE_DELAY_TICKS) {
                            Screenshot.grab(
                                    client.gameDirectory,
                                    "pinned-hud.png",
                                    client.gameRenderer.mainRenderTarget(),
                                    1,
                                    message -> {});
                            // 取消固定窗口后，像素对照只检查原版 HUD，不混入测试模块自身的 HUD。
                            runtime.workspace().disconnect();
                            hudProbe = new HudVisibilityProbe();
                            client.gui.setScreen(hudProbe);
                        }
                        if (hudProbe != null && hudProbe == client.gui.screen() && hudProbe.hiddenVerified()) {
                            // 屏幕下的隐藏检查完成后关闭探针，由 HUD 末尾元素确认原版 HUD 恢复绘制。
                            client.gui.setScreen(null);
                        }
                        if (hudProbe != null && hudProbe.verified()) {
                            try {
                                Files.writeString(
                                        client.gameDirectory.toPath().resolve("wire-client-ok.txt"),
                                        "state=7; accepted=true; rejected=true; pinned HUD"
                                            + " rendered; vanilla HUD hidden and restored; F1"
                                            + " unchanged\n");
                            } catch (IOException failure) {
                                throw new UncheckedIOException(failure);
                            }
                            client.stop();
                        }
                    }
                });
    }
}

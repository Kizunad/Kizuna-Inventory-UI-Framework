package dev.kizuna.inventoryui.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kizuna.inventoryui.client.component.ModelPreviewRenderer;
import dev.kizuna.inventoryui.client.status.StatusBlockers;
import dev.kizuna.inventoryui.client.status.StatusIcons;
import dev.kizuna.inventoryui.status.StatusGroups;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.lwjgl.glfw.GLFW;

/** 框架入口只负责加载、工作台快捷键、HUD 绘制及连接生命周期。 */
public final class InventoryUiClient implements ClientModInitializer {
    private static ClientRuntime runtime;
    private ClientPacketListener workspaceConnection;

    private void registerConnections() {
        // 窗口属于一次游戏连接。退服会丢弃客户端任务队列，因此也在客户端刻首检查实际通道。
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (workspaceConnection != null) {
                closeWorkspace(client);
            }
            workspaceConnection = handler;
            runtime.connected();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            if (workspaceConnection == handler) {
                closeWorkspace(client);
            }
        }));
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (workspaceConnection != null && !workspaceConnection.getConnection().isConnected()) {
                closeWorkspace(client);
            }
        });
    }

    private void closeWorkspace(Minecraft client) {
        // 先释放连接身份，晚到的旧回调不能重复清理或关掉下一次连接的窗口。
        workspaceConnection = null;
        if (runtime.ownsWorkspace(client.gui.screen())) {
            client.gui.setScreen(null);
        }
        runtime.disconnected();
    }

    public static ClientRuntime runtime() {
        // 扩展必须在框架初始化完成后访问运行时，避免隐式创建两份目录或状态。
        if (runtime == null) {
            throw new IllegalStateException("inventory UI is not initialized");
        }
        return runtime;
    }

    @Override
    public void onInitializeClient() {
        // 子 JAR 通过 Fabric 入口发现，主框架无需硬编码扩展类名。
        runtime =
                new ClientRuntime(
                        FabricLoader.getInstance()
                                .getEntrypoints("kizuna_inventory_ui", ClientModule.class));
        // 自动测试使用隔离的内存偏好，正式客户端在 Fabric 配置目录保存本地布局。
        if (!Boolean.getBoolean("kiui.smoke")) {
            runtime.loadPreferences(
                    FabricLoader.getInstance().getConfigDir().resolve("kizuna-inventory-ui.json"));
        }
        if (Boolean.getBoolean("kiui.smoke")) {
            runtime.configureBackgroundDirectory(
                    FabricLoader.getInstance().getGameDir().resolve("backgrounds"));
        }
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING
                .register(
                        client -> {
                            if (runtime.localBackgrounds() != null) {
                                runtime.localBackgrounds().close();
                            }
                        });
        FabricUiTransport.install(runtime);
        // GUI 渲染器初始化时冻结画中画注册表，必须在客户端入口登记。
        PictureInPictureRendererRegistry.register(context -> new ModelPreviewRenderer());
        // 本框架的按键归在同一个分类下；分类只能登记一次，所以先取出来共用。
        KeyMapping.Category keyCategory =
                KeyMapping.Category.register(
                        Identifier.fromNamespaceAndPath("kizuna_inventory_ui", "general"));
        var key =
                KeyMappingHelper.registerKeyMapping(
                        new KeyMapping(
                                "key.kizuna_inventory_ui.workspace",
                                InputConstants.Type.KEYSYM,
                                GLFW.GLFW_KEY_I,
                                keyCategory));
        StatusIcons.register(keyCategory);
        UiVersionCommand.register(runtime);
        IconStyleCommand.register(runtime);
        StatusBlockers.register(screen -> runtime.layer(screen).map(WindowLayer::blockedAreas).orElseGet(List::of));
        StatusBlockers.registerHud((guiWidth, guiHeight) -> runtime.hudBlockedAreas());
        HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath("kizuna_inventory_ui", "compact_layout"),
                (graphics, delta) -> runtime.beginHudFrame(graphics.guiWidth(), graphics.guiHeight()));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            // Fabric 的逐屏事件在 init 时重新建立，同帧的宿主与窗口共用动画时刻。
            ScreenEvents.beforeExtract(screen).register((host, graphics, x, y, delta) ->
                    runtime.layer(host).ifPresent(WindowLayer::beginFrame));
            ScreenEvents.remove(screen).register(host -> runtime.layer(host).ifPresent(WindowLayer::detach));
        });
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    // 消费按键队列但只在游戏内空闲界面打开，避免按键穿透到聊天或其他屏幕。
                    while (key.consumeClick()) {
                        if (client.player != null && client.gui.screen() == null) {
                            runtime.openWorkspace();
                        }
                    }
                    runtime.updateHost(client);
                    if (client.player != null) {
                        runtime.recheckRestores();
                    }
                    runtime.workspace().windows().tick(System.currentTimeMillis());
                    runtime.protocol().tick(System.currentTimeMillis());
                });
        registerConnections();
        // 挂在原版 HUD 最后，与旧 HudRenderCallback 一样叠在所有原版 HUD 元素之上。
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("kizuna_inventory_ui", "huds"),
                (context, deltaTracker) -> {
                    // 库存页面只绘制自己的内容；工作台在布局面板内显式绘制 HUD 预览。
                    var client = Minecraft.getInstance();
                    if (client.gui.hud.isHidden()
                            || client.gui.screen() instanceof VanillaHudHidden) {
                        return;
                    }
                    runtime.renderHuds(
                            context,
                            client.getWindow().getGuiScaledWidth(),
                            client.getWindow().getGuiScaledHeight(),
                            deltaTracker.getGameTimeDeltaPartialTick(false));
                });
        ResourceLoader.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(
                        Identifier.fromNamespaceAndPath("kizuna_inventory_ui", "backgrounds"),
                        (ResourceManagerReloadListener) manager -> runtime.reloadBackgrounds());
        ResourceLoader.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(
                        Identifier.fromNamespaceAndPath("kizuna_inventory_ui", "styled_icons"),
                        (ResourceManagerReloadListener) manager -> runtime.reloadIcons());
        ResourceLoader.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(
                        Identifier.fromNamespaceAndPath("kizuna_inventory_ui", "status_groups"),
                        (ResourceManagerReloadListener) StatusGroups::reload);
    }
}

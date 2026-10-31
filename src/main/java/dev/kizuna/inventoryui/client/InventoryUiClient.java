package dev.kizuna.inventoryui.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kizuna.inventoryui.client.component.ModelPreviewRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.lwjgl.glfw.GLFW;

/** 框架入口只负责加载、工作台快捷键、HUD 绘制及连接生命周期。 */
public final class InventoryUiClient implements ClientModInitializer {
    private static ClientRuntime runtime;

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
        var key =
                KeyMappingHelper.registerKeyMapping(
                        new KeyMapping(
                                "key.kizuna_inventory_ui.workspace",
                                InputConstants.Type.KEYSYM,
                                GLFW.GLFW_KEY_I,
                                KeyMapping.Category.register(
                                        Identifier.fromNamespaceAndPath(
                                                "kizuna_inventory_ui", "general"))));
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    // 消费按键队列但只在游戏内空闲界面打开，避免按键穿透到聊天或其他屏幕。
                    while (key.consumeClick()) {
                        if (client.player != null && client.gui.screen() == null) {
                            runtime.openWorkspace();
                        }
                    }
                    if (!(client.gui.screen() instanceof WorkspaceScreen)
                            || !client.isWindowActive()) {
                        // 切屏或窗口失焦后撤销拖动，旧的鼠标释放事件不能再提交操作。
                        runtime.cancelInput();
                    }
                    runtime.workspace().windows().tick(System.currentTimeMillis());
                    runtime.protocol().tick(System.currentTimeMillis());
                });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> runtime.connected());
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> client.execute(() -> {
                    // Netty 断线也可能触发回调；窗口释放和模块通知必须与渲染串行。
                    if (client.getConnection() != null && client.getConnection() != handler) {
                        return;
                    }
                    if (client.gui.screen() instanceof WorkspaceScreen) {
                        client.gui.setScreen(null);
                    }
                    runtime.disconnected();
                }));
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
    }
}

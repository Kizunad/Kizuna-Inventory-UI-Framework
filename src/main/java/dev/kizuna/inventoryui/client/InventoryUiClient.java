package dev.kizuna.inventoryui.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

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
        var key =
                KeyBindingHelper.registerKeyBinding(
                        new KeyBinding(
                                "key.kizuna_inventory_ui.workspace",
                                InputUtil.Type.KEYSYM,
                                GLFW.GLFW_KEY_I,
                                "category.kizuna_inventory_ui"));
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    // 消费按键队列但只在游戏内空闲界面打开，避免按键穿透到聊天或其他屏幕。
                    while (key.wasPressed()) {
                        if (client.player != null && client.currentScreen == null) {
                            runtime.openWorkspace();
                        }
                    }
                    if (!(client.currentScreen instanceof WorkspaceScreen)
                            || !client.isWindowFocused()) {
                        // 切屏或窗口失焦后撤销拖动，旧的鼠标释放事件不能再提交操作。
                        runtime.cancelInput();
                    }
                    runtime.workspace().windows().tick(System.currentTimeMillis());
                    runtime.protocol().tick(System.currentTimeMillis());
                });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> runtime.connected());
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    if (client.currentScreen instanceof WorkspaceScreen) {
                        client.setScreen(null);
                    }
                    runtime.disconnected();
                });
        HudRenderCallback.EVENT.register(
                (context, delta) -> {
                    // 工作台自行绘制 HUD 预览，避免普通 HUD 回调叠加绘制两次。
                    var client = MinecraftClient.getInstance();
                    if (client.options.hudHidden
                            || client.currentScreen instanceof WorkspaceScreen) {
                        return;
                    }
                    runtime.renderHuds(
                            context,
                            client.getWindow().getScaledWidth(),
                            client.getWindow().getScaledHeight(),
                            delta);
                });
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES)
                .registerReloadListener(
                        new SimpleSynchronousResourceReloadListener() {
                            @Override
                            public Identifier getFabricId() {
                                return new Identifier("kizuna_inventory_ui", "backgrounds");
                            }

                            @Override
                            public void reload(ResourceManager manager) {
                                runtime.reloadBackgrounds();
                            }
                        });
    }
}

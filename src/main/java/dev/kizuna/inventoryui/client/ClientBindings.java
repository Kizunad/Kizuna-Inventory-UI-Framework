package dev.kizuna.inventoryui.client;

import dev.kizuna.inventoryui.inventory.InventoryDragSession;
import dev.kizuna.inventoryui.inventory.InventorySnapshot;
import dev.kizuna.inventoryui.protocol.UiWire;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowManager;

import io.wispforest.owo.ui.core.Component;

import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** 一次性、按模块隔离的渲染注册。初始化结束后拒绝继续修改。 */
public final class ClientBindings {
    private final FrameworkCatalog.Module module;
    final Map<String, WindowFactory> windows = new LinkedHashMap<>();
    final Map<String, HudRenderer> huds = new LinkedHashMap<>();
    final Map<String, String> slotBarHuds = new LinkedHashMap<>();
    final Map<String, Function<FrameworkCatalog.Slot, SlotContent>> bars = new LinkedHashMap<>();
    private boolean frozen;
    final List<UiWire.Contract<?>> messages = new ArrayList<>();

    public void message(UiWire.Contract<?> contract) {
        // 消息归属精确到模块，不允许同命名空间的另一个模块接管接收器。
        if (frozen || !contract.route().moduleId().equals(module.id())) {
            throw new IllegalArgumentException(
                    "message registration is closed or outside module: " + module.id());
        }
        messages.add(Objects.requireNonNull(contract));
    }

    ClientBindings(FrameworkCatalog.Module module) {
        this.module = module;
    }

    public void window(String id, WindowFactory factory) {
        add(id, FrameworkCatalog.Window.class, windows, factory);
    }

    public void hud(String id, HudRenderer renderer) {
        if (slotBarHuds.containsKey(id)) {
            throw new IllegalArgumentException("duplicate HUD binding: " + id);
        }
        add(id, FrameworkCatalog.Hud.class, huds, renderer);
    }

    public void slotBarHud(String hudId, String barId) {
        // HUD 必须独立声明，投影复用同一栏位供应器，不复制业务绑定状态。
        if (huds.containsKey(hudId)
                || module.entries().stream()
                        .noneMatch(
                                entry ->
                                        entry instanceof FrameworkCatalog.SlotBar
                                                && entry.id().equals(barId))) {
            throw new IllegalArgumentException("duplicate HUD or undeclared slot bar: " + barId);
        }
        add(hudId, FrameworkCatalog.Hud.class, slotBarHuds, barId);
    }

    public void slotBar(String id, Function<FrameworkCatalog.Slot, SlotContent> content) {
        add(id, FrameworkCatalog.SlotBar.class, bars, content);
    }

    private <T> void add(String id, Class<?> kind, Map<String, T> target, T value) {
        // 只允许给本模块已声明的同类型扩展绑定实现，不能接管其他模块的注册项。
        if (frozen) {
            throw new IllegalStateException("module registration is closed: " + module.id());
        }
        if (module.entries().stream()
                .noneMatch(entry -> entry.id().equals(id) && kind.isInstance(entry))) {
            throw new IllegalArgumentException("undeclared binding in " + module.id() + ": " + id);
        }
        if (target.putIfAbsent(id, Objects.requireNonNull(value)) != null) {
            throw new IllegalArgumentException("duplicate binding: " + id);
        }
    }

    void freeze() {
        // 需要运行时实现的声明必须全部有绑定；初始化结束后关闭注册，防止目录与实现漂移。
        for (var entry : module.entries()) {
            boolean present =
                    !(entry instanceof FrameworkCatalog.Window) || windows.containsKey(entry.id());
            present &=
                    !(entry instanceof FrameworkCatalog.Hud)
                            || huds.containsKey(entry.id())
                            || slotBarHuds.containsKey(entry.id());
            present &= !(entry instanceof FrameworkCatalog.SlotBar) || bars.containsKey(entry.id());
            if (!present) {
                throw new IllegalArgumentException(
                        "missing renderer in " + module.id() + ": " + entry.id());
            }
        }
        frozen = true;
    }

    @FunctionalInterface
    public interface WindowFactory {
        /** 订阅清理注册到 state.scope()；每个窗口实例使用独立的组件树。 */
        Component create(ClientRuntime runtime, UiWindowManager.WindowState state);
    }

    @FunctionalInterface
    public interface HudRenderer {
        /** 坐标原点已移到 HUD 左上角，尺寸为定义中的逻辑尺寸。 */
        void render(DrawContext context, int width, int height, float tickDelta);
    }

    public record SlotContent(
            String label,
            Runnable activate,
            InventorySnapshot.Item item,
            Predicate<InventoryDragSession.Payload> accepts,
            Consumer<InventoryDragSession.Payload> drop,
            Runnable clear) {
        public SlotContent(String label, Runnable activate) {
            // 旧的动作槽没有库存落点能力，默认拒绝拖放，避免把绑定误当成移动。
            this(label, activate, null, payload -> false, payload -> {}, () -> {});
        }

        public SlotContent {
            // 槽位始终提供可显示内容与激活动作，空槽行为由模块显式定义。
            Objects.requireNonNull(label);
            Objects.requireNonNull(activate);
            Objects.requireNonNull(accepts);
            Objects.requireNonNull(drop);
            Objects.requireNonNull(clear);
        }
    }
}

package dev.kizuna.inventoryui.demo;

import dev.kizuna.inventoryui.client.ClientBindings;
import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.client.component.EffectStripComponent;
import dev.kizuna.inventoryui.client.component.InventoryGridComponent;
import dev.kizuna.inventoryui.client.component.ItemPresentation;
import dev.kizuna.inventoryui.client.component.ItemSlotComponent;
import dev.kizuna.inventoryui.client.component.ModelPreviewComponent;
import dev.kizuna.inventoryui.client.component.StatusBarComponent;
import dev.kizuna.inventoryui.inventory.InventoryGrid;
import dev.kizuna.inventoryui.inventory.InventorySnapshot;
import dev.kizuna.inventoryui.inventory.InventoryState;
import dev.kizuna.inventoryui.inventory.ItemAction;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.inventoryui.window.UiWindowDefinition;

import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.container.Containers;
import io.wispforest.owo.ui.core.Sizing;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/** 模拟权威状态只存在于示例子 JAR；生产框架没有演示物品或业务依赖。 */
public final class DemoModule implements ClientModule {
    // 演示布局的逻辑像素尺寸；格子尺寸供 smoke 的移动距离计算复用。
    static final int CELL_SIZE = 28;
    private static final int INVENTORY_WINDOW_WIDTH = 260;
    private static final int INVENTORY_WINDOW_HEIGHT = 140;
    private static final int STATUS_WINDOW_WIDTH = 240;
    private static final int STATUS_WINDOW_HEIGHT = 120;
    private static final int HUD_WIDTH = 150;
    private static final int HUD_HEIGHT = 28;
    private static final int HUD_TEXT_X = 24;
    private static final int HUD_BADGE_SIZE = 14;
    private static final int HUD_BADGE_INSET = 5;
    private static final int HUD_TEXT_Y = 10;
    private static final int FILL_PERCENT = 100;
    private static final int TOOL_SLOT_COUNT = 3;
    private static final int QUICK_SLOT_COUNT = 5;
    private static final int PREVIEW_WIDTH = 170;
    private static final int PREVIEW_HEIGHT = 105;
    private static final int STATUS_BAR_HEIGHT = 16;

    private final InventoryState state = new InventoryState(initial());
    private String lastResult = "拖动物品，R 旋转；模拟服务器负责确认";
    private boolean rejectNext;

    private static InventorySnapshot initial() {
        // 固定样例只存在于演示子 JAR，用多格物品和堆叠物品展示通用网格能力。
        return new InventorySnapshot(
                "demo-session",
                0,
                List.of(
                        new InventorySnapshot.Container("pack", "背包", 3, 8),
                        new InventorySnapshot.Container("case", "容器", 3, 4)),
                List.of(
                        new InventorySnapshot.Placement(
                                "pack",
                                0,
                                0,
                                false,
                                new InventorySnapshot.Item(
                                        "tool-1",
                                        "demo:tool",
                                        "工具",
                                        "minecraft:textures/item/iron_pickaxe.png",
                                        2,
                                        1,
                                        1)),
                        new InventorySnapshot.Placement(
                                "pack",
                                1,
                                3,
                                false,
                                new InventorySnapshot.Item(
                                        "fruit-1",
                                        "demo:fruit",
                                        "果实",
                                        "minecraft:textures/item/apple.png",
                                        1,
                                        1,
                                        8))));
    }

    @Override
    public FrameworkCatalog.Module definition() {
        // 声明窗口、功能入口、HUD、两种长度栏位和背景，框架无需知道这些演示 ID。
        return FrameworkCatalog.Module.of(
                "demo:storage",
                Set.of("kiui:core"),
                window("demo:inventory_window", INVENTORY_WINDOW_WIDTH, INVENTORY_WINDOW_HEIGHT),
                window("demo:status_window", STATUS_WINDOW_WIDTH, STATUS_WINDOW_HEIGHT),
                window("demo:case_window", STATUS_WINDOW_WIDTH, INVENTORY_WINDOW_HEIGHT),
                window("demo:components_window", STATUS_WINDOW_WIDTH, STATUS_WINDOW_HEIGHT),
                new FrameworkCatalog.Feature(
                        "demo:components",
                        "公共组件",
                        "工具",
                        "demo:components_icon",
                        "demo:components_window"),
                new FrameworkCatalog.Feature(
                        "demo:case", "示例容器", "物品", "demo:case_icon", "demo:case_window"),
                new FrameworkCatalog.Feature(
                        "demo:inventory", "示例库存", "物品", "demo:pack", "demo:inventory_window"),
                new FrameworkCatalog.Feature(
                        "demo:status", "共享状态", "工具", "demo:status_icon", "demo:status_window"),
                new FrameworkCatalog.SvgAsset("demo:capacity_badge", "kiui_demo:svg/capacity.svg"),
                new FrameworkCatalog.Hud(
                        "demo:capacity",
                        "库存状态",
                        FrameworkCatalog.Anchor.TOP_RIGHT,
                        HUD_WIDTH,
                        HUD_HEIGHT),
                bar("demo:tools", TOOL_SLOT_COUNT),
                new FrameworkCatalog.Hud(
                        "demo:tools_hud",
                        "工具栏",
                        FrameworkCatalog.Anchor.BOTTOM_LEFT,
                        TOOL_SLOT_COUNT * CELL_SIZE,
                        CELL_SIZE),
                bar("demo:quick", QUICK_SLOT_COUNT),
                new FrameworkCatalog.Background(
                        "demo:stone",
                        "minecraft:textures/block/stone.png",
                        FrameworkCatalog.Fit.TILE));
    }

    private static FrameworkCatalog.Window window(String id, int width, int height) {
        // 演示窗口只声明普通窗口能力，业务类型和最小尺寸由此模块决定。
        return new FrameworkCatalog.Window(
                new UiWindowDefinition(
                        id, id, width, height, Set.of(UiWindowDefinition.Capability.WINDOW)));
    }

    private static FrameworkCatalog.SlotBar bar(String id, int count) {
        // 槽位数量来自模块参数，用所属栏位 ID 派生每个槽的稳定身份。
        return new FrameworkCatalog.SlotBar(
                id,
                IntStream.range(0, count)
                        .mapToObj(i -> new FrameworkCatalog.Slot(id + "/" + i, "action"))
                        .toList());
    }

    @Override
    public void register(ClientBindings bindings) {
        // 窗口和 HUD 都读取同一 state；窗口订阅必须随窗口关闭释放。
        bindings.window(
                "demo:inventory_window",
                (runtime, window) -> {
                    var root = Containers.verticalFlow(Sizing.fill(FILL_PERCENT), Sizing.content());
                    root.child(
                            new InventoryGridComponent(
                                            state,
                                            "pack",
                                            CELL_SIZE,
                                            this::move,
                                            runtime::color,
                                            runtime.drag())
                                    .presentation(
                                            new ItemPresentation() {
                                                @Override
                                                public List<ItemAction> actions(
                                                        InventorySnapshot.Item item) {
                                                    // 示例提供操作，主框架不保存这些业务标签或回调。
                                                    return List.of(
                                                            new ItemAction(
                                                                    "demo:inspect",
                                                                    "查看共享状态",
                                                                    true,
                                                                    "",
                                                                    () ->
                                                                            runtime.open(
                                                                                    "demo:status",
                                                                                    null)));
                                                }

                                                @Override
                                                public void inspect(InventorySnapshot.Item item) {
                                                    runtime.open("demo:status", null);
                                                }
                                            },
                                            runtime.contextMenu()));
                    root.child(
                            Components.button(
                                    Text.literal("让下一次移动被拒绝"), button -> rejectNext = true));
                    return root;
                });
        bindings.window(
                "demo:case_window",
                (runtime, window) ->
                        new InventoryGridComponent(
                                state,
                                "case",
                                CELL_SIZE,
                                this::move,
                                runtime::color,
                                runtime.drag()));
        bindings.window(
                "demo:components_window",
                (runtime, window) -> {
                    // 单独演示通用组件组合，模型、槽位内容和状态含义均留在示例子 JAR。
                    var root = Containers.verticalFlow(Sizing.fill(FILL_PERCENT), Sizing.content());
                    var bar =
                            definition().entries().stream()
                                    .filter(entry -> entry instanceof FrameworkCatalog.SlotBar)
                                    .map(FrameworkCatalog.SlotBar.class::cast)
                                    .findFirst()
                                    .orElseThrow();
                    var slotId = bar.slots().get(0);
                    var slot =
                            new ItemSlotComponent(
                                    CELL_SIZE,
                                    () -> runtime.slotContent(bar.id(), slotId),
                                    runtime.drag(),
                                    runtime::color);
                    slot.dragSource(
                            () -> {
                                var item = boundItems.get(slotId.id());
                                if (item != null) {
                                    runtime.drag()
                                            .beginSlot(
                                                    slot,
                                                    "demo-loadout",
                                                    state.snapshot().revision(),
                                                    slotId.id(),
                                                    item,
                                                    item.count(),
                                                    () -> boundItems.get(slotId.id()) == item);
                                }
                            });
                    root.child(slot);
                    root.child(
                            new StatusBarComponent(
                                    PREVIEW_WIDTH,
                                    STATUS_BAR_HEIGHT,
                                    () ->
                                            new StatusBarComponent.Value(
                                                    "示例容量",
                                                    state.snapshot().placements().size(),
                                                    initial().containers().get(0).rows()
                                                            * initial()
                                                                    .containers()
                                                                    .get(0)
                                                                    .columns(),
                                                    ThemeTokens.PROGRESS_FILL,
                                                    List.of(Text.literal("模块提供数值"))),
                                    runtime::color));
                    root.child(
                            new EffectStripComponent(
                                    PREVIEW_WIDTH,
                                    () ->
                                            List.of(
                                                    new EffectStripComponent.Effect(
                                                            "demo:effect",
                                                            "minecraft:textures/item/apple.png",
                                                            "1",
                                                            List.of(Text.literal("示例状态")))),
                                    runtime::color));
                    root.child(
                            new ModelPreviewComponent(
                                    PREVIEW_WIDTH,
                                    PREVIEW_HEIGHT,
                                    () ->
                                            new ModelPreviewComponent.Model() {
                                                @Override
                                                public Box bounds() {
                                                    // 原版物品模型以中心为原点，边长为一个模型单位。
                                                    final double halfExtent = 0.5;
                                                    return new Box(
                                                            -halfExtent,
                                                            -halfExtent,
                                                            -halfExtent,
                                                            halfExtent,
                                                            halfExtent,
                                                            halfExtent);
                                                }

                                                @Override
                                                public void render(
                                                        MatrixStack matrices,
                                                        VertexConsumerProvider buffers,
                                                        float tickDelta) {
                                                    var client = MinecraftClient.getInstance();
                                                    client.getItemRenderer()
                                                            .renderItem(
                                                                    new ItemStack(Items.IRON_SWORD),
                                                                    ModelTransformationMode.FIXED,
                                                                    LightmapTextureManager
                                                                            .MAX_LIGHT_COORDINATE,
                                                                    OverlayTexture.DEFAULT_UV,
                                                                    matrices,
                                                                    buffers,
                                                                    client.world,
                                                                    0);
                                                }
                                            },
                                    runtime::color));
                    return root;
                });
        bindings.window(
                "demo:status_window",
                (runtime, window) -> {
                    var root = Containers.verticalFlow(Sizing.fill(FILL_PERCENT), Sizing.content());
                    var label = Components.label(Text.literal(status()));
                    var subscription = state.subscribe(value -> label.text(Text.literal(status())));
                    window.scope().addCleanup(subscription::close);
                    root.child(label);
                    root.child(
                            Components.button(
                                    Text.literal("打开库存"),
                                    button -> runtime.open("demo:inventory", null)));
                    root.child(
                            Components.button(
                                    Text.literal("查看最近结果"),
                                    button ->
                                            label.text(
                                                    Text.literal(status() + "\n" + lastResult))));
                    return root;
                });
        bindings.hud(
                "demo:capacity",
                (context, width, height, delta) -> {
                    // 绘制时才读取当前主题，已有 HUD 无需重新注册就能跟随外观切换。
                    var runtime = InventoryUiClient.runtime();
                    context.fill(0, 0, width, height, runtime.color(ThemeTokens.HUD_BACKGROUND));
                    runtime.svg()
                            .draw(
                                    context,
                                    "demo:capacity_badge",
                                    HUD_BADGE_INSET,
                                    (height - HUD_BADGE_SIZE) / 2,
                                    HUD_BADGE_SIZE,
                                    HUD_BADGE_SIZE,
                                    runtime.color(ThemeTokens.ACCENT));
                    context.drawTextWithShadow(
                            MinecraftClient.getInstance().textRenderer,
                            status(),
                            HUD_TEXT_X,
                            HUD_TEXT_Y,
                            runtime.color(ThemeTokens.HUD_TEXT));
                });
        for (String id : List.of("demo:tools", "demo:quick")) {
            bindings.slotBar(
                    id,
                    slot ->
                            new ClientBindings.SlotContent(
                                    slot.id().substring(slot.id().lastIndexOf('/') + 1),
                                    () -> lastResult = "激活槽位 " + slot.id(),
                                    boundItems.get(slot.id()),
                                    payload -> true,
                                    payload -> boundItems.put(slot.id(), payload.item()),
                                    () -> boundItems.remove(slot.id())));
        }
        bindings.slotBarHud("demo:tools_hud", "demo:tools");
    }

    private String status() {
        return "库存 "
                + state.snapshot().placements().size()
                + " 项 / revision "
                + state.snapshot().revision();
    }

    long revision() {
        return state.snapshot().revision();
    }

    private void move(InventoryGrid.MoveIntent intent) {
        // 仅在演示中模拟服务端判定；拒绝请求时保持权威快照不变。
        if (rejectNext) {
            rejectNext = false;
            lastResult = "模拟服务器拒绝移动；库存未变化";
            return;
        }
        var snapshot = state.snapshot();
        // 模拟端也校验状态流和版本，不能把过期拖动套用到新库存。
        if (!snapshot.streamId().equals(intent.streamId())
                || snapshot.revision() != intent.baseRevision()) {
            lastResult = "库存版本已更新";
            return;
        }
        var placements =
                new ArrayList<>(
                        snapshot.placements().stream()
                                .map(
                                        placement ->
                                                placement
                                                                .item()
                                                                .instanceId()
                                                                .equals(intent.instanceId())
                                                        ? new InventorySnapshot.Placement(
                                                                intent.destinationContainerId(),
                                                                intent.row(),
                                                                intent.column(),
                                                                intent.rotated(),
                                                                withCount(
                                                                        placement.item(),
                                                                        intent.count()))
                                                        : placement)
                                .toList());
        var source =
                snapshot.placements().stream()
                        .filter(value -> value.item().instanceId().equals(intent.instanceId()))
                        .findFirst()
                        .orElseThrow();
        if (intent.count() < source.item().count()) {
            // 分堆模拟服务端分配新实例 ID，生产框架不实现物品生成或库存事务。
            placements.removeIf(value -> value.item().instanceId().equals(intent.instanceId()));
            placements.add(
                    new InventorySnapshot.Placement(
                            source.containerId(),
                            source.row(),
                            source.column(),
                            source.rotated(),
                            withCount(source.item(), source.item().count() - intent.count())));
            var item = source.item();
            placements.add(
                    new InventorySnapshot.Placement(
                            intent.destinationContainerId(),
                            intent.row(),
                            intent.column(),
                            intent.rotated(),
                            new InventorySnapshot.Item(
                                    item.instanceId() + "-split-" + snapshot.revision(),
                                    item.itemId(),
                                    item.name(),
                                    item.iconId(),
                                    item.width(),
                                    item.height(),
                                    intent.count())));
        }
        // 通过版本递增的完整快照提交结果，让所有窗口和 HUD 一起读取新状态。
        state.replace(
                new InventorySnapshot(
                        snapshot.streamId(),
                        snapshot.revision() + 1,
                        snapshot.containers(),
                        placements));
        lastResult = "模拟服务器已接受移动";
    }

    private final Map<String, InventorySnapshot.Item> boundItems = new LinkedHashMap<>();

    private static InventorySnapshot.Item withCount(InventorySnapshot.Item item, int count) {
        return new InventorySnapshot.Item(
                item.instanceId(),
                item.itemId(),
                item.name(),
                item.iconId(),
                item.width(),
                item.height(),
                count);
    }

    InventorySnapshot snapshot() {
        return state.snapshot();
    }

    boolean hasBinding() {
        return !boundItems.isEmpty();
    }

    @Override
    public void disconnected(ClientRuntime runtime) {
        // 演示复用固定状态流并递增版本来复位；生产连接切换应由宿主管理状态流身份。
        var initial = initial();
        state.replace(
                new InventorySnapshot(
                        initial.streamId(),
                        state.snapshot().revision() + 1,
                        initial.containers(),
                        initial.placements()));
        rejectNext = false;
        boundItems.clear();
        lastResult = "连接已清理";
    }
}

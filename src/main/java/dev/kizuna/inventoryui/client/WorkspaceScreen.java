package dev.kizuna.inventoryui.client;

import static dev.kizuna.inventoryui.client.WorkspaceMetrics.*;
import static dev.kizuna.inventoryui.theme.ThemeTokens.*;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.kizuna.inventoryui.client.component.ItemDrawing;
import dev.kizuna.inventoryui.hud.HudLayout;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowManager;
import dev.kizuna.inventoryui.window.WindowMotion;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工作台外壳：目录与外观是明确入口，业务窗口由注册目录生成。 */
public final class WorkspaceScreen extends Screen {
    // 菜单尺寸均为逻辑像素；坐标由尺寸和间距推导，不在绘制及输入中重复填写偏移。
    private static final int TOOLBAR_PADDING = 4;
    private static final int TOOLBAR_BUTTON_WIDTH = 74;
    private static final int APPEARANCE_BUTTON_WIDTH = 56;
    private static final int BUTTON_HEIGHT = 20;
    private static final int MENU_WIDTH = 290;
    private static final int MENU_INSET = 8;
    private static final int MENU_ROW_GAP = 4;
    private static final int MENU_ROW_PITCH = BUTTON_HEIGHT + MENU_ROW_GAP;
    private static final int MENU_START_Y = TOOLBAR_HEIGHT + TOOLBAR_PADDING;
    private static final int SEARCH_RESULTS_GAP = 6;
    private static final int SEARCH_MAX_LENGTH = 128;
    private static final int PAGE_BUTTON_WIDTH = 86;
    private static final int PAGE_BUTTON_GAP = 6;
    private static final int PAGE_BOTTOM_GAP = 6;
    private static final int PAGE_TOP_GAP = 8;
    private static final int MENU_BOTTOM_GAP = 2;
    private static final int NOTICE_INSET = 6;
    private static final int NOTICE_BOTTOM_GAP = 12;

    // 底部两行分别承载槽位与窗口入口，命中区域直接复用绘制时的行坐标和尺寸。
    private static final int BAR_PADDING_X = 4;
    private static final int SLOT_ROW_PADDING_TOP = 4;
    private static final int SLOT_WIDTH = 32;
    private static final int SLOT_HEIGHT = 26;
    private static final int SLOT_GAP = 4;
    private static final int SLOT_PITCH = SLOT_WIDTH + SLOT_GAP;
    private static final int SLOT_TEXT_PADDING_X = 2;
    private static final int SLOT_TEXT_PADDING_Y = 9;
    private static final int SLOT_GROUP_GAP = 8;
    private static final int WINDOW_BAR_BOTTOM_GAP = 2;
    private static final int WINDOW_BAR_BUTTON_HEIGHT = 20;
    private static final int WINDOW_BAR_MAX_BUTTON_WIDTH = 150;
    private static final int WINDOW_BAR_TITLE_EXTRA_WIDTH = 20;
    private static final int WINDOW_BAR_BUTTON_GAP = 4;
    private static final int WINDOW_BAR_TEXT_PADDING_X = 4;
    private static final int WINDOW_BAR_TEXT_PADDING_Y = 6;
    private static final int WINDOW_BAR_TEXT_RESERVED_WIDTH = 12;

    // HUD 设置按钮的操作步长，与 HudLayout 接受的完整缩放范围区分开。
    private static final int HUD_MOVE_STEP = 16;
    private static final float HUD_SCALE_STEP = 0.25f;
    private static final float HUD_SCALE_CYCLE_MIN = 0.5f;
    private static final float HUD_SCALE_CYCLE_MAX = 2f;
    private static final int PERCENT_FACTOR = 100;
    private static final int DRAG_ICON_SIZE = 32;
    private static final int DRAG_POINTER_GAP = 8;

    private final ClientRuntime runtime;
    private final List<Hit> bottomHits = new ArrayList<>();
    private UiWindowManager.WindowKey focused;
    private UiWindowManager.WindowKey pressed;
    private Panel panel = Panel.NONE;
    private TextFieldWidget search;
    private String query = "";
    private int page;
    private int bottomOffset;
    private String notice = "";
    private UiWindowManager.WindowKey resizing;
    private final List<SlotHit> slotHits = new ArrayList<>();
    private String draggingHud;
    private final Map<UiWindowManager.WindowKey, WindowMotion> motions = new LinkedHashMap<>();
    private final Map<UiWindowManager.WindowKey, UiWindowManager.Rect> displayed =
            new LinkedHashMap<>();
    private final Map<UiWindowManager.WindowKey, UiWindowManager.Rect> anchors =
            new LinkedHashMap<>();
    private UiWindowManager.WindowKey sizeTarget;
    private TextFieldWidget widthInput;
    private TextFieldWidget heightInput;
    private static final int SIZE_INPUT_WIDTH = 66;
    private static final int SIZE_INPUT_MAX_LENGTH = 10;
    private static final int SIZE_PANEL_HEIGHT = 100;
    private static final int RESTORE_ANCHOR_WIDTH = 112;

    public WorkspaceScreen(ClientRuntime runtime) {
        super(Text.literal("Inventory Workspace"));
        this.runtime = runtime;
    }

    @Override
    protected void init() {
        // 主屏尺寸是逻辑像素；下方栏位保留固定高度，不作为可拖动窗口的布局区域。
        runtime.workspace()
                .windows()
                .resizeViewport(width, Math.max(1, height - BOTTOM_BAR_HEIGHT));
        rebuildControls();
    }

    private void rebuildControls() {
        // 菜单控件随面板状态重建，业务窗口及其订阅仍由独立运行时持有。
        clearChildren();
        search = null;
        int toolbarX = TOOLBAR_PADDING;
        button(
                "功能目录",
                toolbarX,
                TOOLBAR_PADDING,
                TOOLBAR_BUTTON_WIDTH,
                () -> toggle(Panel.FEATURES));
        toolbarX += TOOLBAR_BUTTON_WIDTH + TOOLBAR_PADDING;
        button("HUD 布局", toolbarX, TOOLBAR_PADDING, TOOLBAR_BUTTON_WIDTH, () -> toggle(Panel.HUD));
        toolbarX += TOOLBAR_BUTTON_WIDTH + TOOLBAR_PADDING;
        button(
                "外观",
                toolbarX,
                TOOLBAR_PADDING,
                APPEARANCE_BUTTON_WIDTH,
                () -> toggle(Panel.APPEARANCE));
        if (sizeTarget != null) {
            buildSizeControls();
            return;
        }
        if (panel == Panel.NONE) {
            return;
        }
        int panelWidth = Math.min(width - 2 * TOOLBAR_PADDING, MENU_WIDTH);
        List<Row> rows = new ArrayList<>();
        if (panel == Panel.FEATURES) {
            search =
                    new TextFieldWidget(
                            textRenderer,
                            MENU_INSET,
                            MENU_START_Y,
                            panelWidth - 2 * MENU_INSET,
                            BUTTON_HEIGHT,
                            Text.literal("搜索功能"));
            search.setMaxLength(SEARCH_MAX_LENGTH);
            search.setText(query);
            search.setChangedListener(
                    value -> {
                        query = value;
                        page = 0;
                        rebuildControls();
                        setFocused(search);
                    });
            addDrawableChild(search);
            for (var feature : runtime.workspace().features(query)) {
                // 普通目录没有具体对象上下文，按对象分实例的入口需要模块提供对象 ID。
                var policy = runtime.catalog().windows().get(feature.windowType()).instancePolicy();
                rows.add(
                        new Row(
                                feature.category() + " / " + feature.title(),
                                () -> {
                                    if (policy == FrameworkCatalog.InstancePolicy.BY_OBJECT) {
                                        notice = "该入口需要对象 ID，请由模块的物品或容器操作打开";
                                        return;
                                    }
                                    focused = runtime.open(feature.id(), null).key();
                                    panel = Panel.NONE;
                                    rebuildControls();
                                }));
                boolean pinned = runtime.workspace().pinnedFeatures().contains(feature.id());
                rows.add(
                        new Row(
                                (pinned ? "取消固定 " : "固定 ") + feature.title(),
                                () -> {
                                    runtime.workspace().pinFeature(feature.id(), !pinned);
                                    runtime.savePreferences();
                                    rebuildControls();
                                }));
            }
        } else if (panel == Panel.HUD) {
            for (var hud : runtime.catalog().huds().values()) {
                var placement = runtime.hudLayout().placement(hud.id());
                rows.add(
                        new Row(
                                (placement.visible() ? "隐藏 " : "显示 ") + hud.title(),
                                () -> {
                                    runtime.hudLayout()
                                            .place(
                                                    hud.id(),
                                                    new HudLayout.Placement(
                                                            !placement.visible(),
                                                            placement.offsetX(),
                                                            placement.offsetY(),
                                                            placement.scale()));
                                    rebuildControls();
                                }));
                rows.add(
                        new Row(
                                hud.title()
                                        + " 缩放 "
                                        + Math.round(placement.scale() * PERCENT_FACTOR)
                                        + "%",
                                () -> {
                                    float next =
                                            placement.scale() >= HUD_SCALE_CYCLE_MAX
                                                    ? HUD_SCALE_CYCLE_MIN
                                                    : placement.scale() + HUD_SCALE_STEP;
                                    runtime.hudLayout()
                                            .place(
                                                    hud.id(),
                                                    new HudLayout.Placement(
                                                            placement.visible(),
                                                            placement.offsetX(),
                                                            placement.offsetY(),
                                                            next));
                                    rebuildControls();
                                }));
                rows.add(
                        new Row(
                                "移动 " + hud.title() + " ←",
                                () -> moveHud(hud.id(), -HUD_MOVE_STEP, 0)));
                rows.add(
                        new Row(
                                "移动 " + hud.title() + " →",
                                () -> moveHud(hud.id(), HUD_MOVE_STEP, 0)));
                rows.add(
                        new Row(
                                "移动 " + hud.title() + " ↑",
                                () -> moveHud(hud.id(), 0, -HUD_MOVE_STEP)));
                rows.add(
                        new Row(
                                "移动 " + hud.title() + " ↓",
                                () -> moveHud(hud.id(), 0, HUD_MOVE_STEP)));
                rows.add(
                        new Row(
                                "恢复 " + hud.title(),
                                () -> {
                                    runtime.hudLayout().reset(hud.id());
                                    rebuildControls();
                                }));
            }
        } else if (panel == Panel.BACKGROUNDS) {
            runtime.catalog()
                    .backgrounds()
                    .keySet()
                    .forEach(id -> rows.add(new Row(id, () -> chooseBackground(id), id)));
            if (runtime.localBackgrounds() != null) {
                runtime.localBackgrounds()
                        .entries()
                        .forEach(
                                id ->
                                        rows.add(
                                                new Row(
                                                        id.substring("local:".length()),
                                                        () -> chooseBackground(id),
                                                        id)));
            }
        } else {
            rows.add(new Row("恢复窗口布局", runtime::resetWindowLayout));
            rows.add(
                    new Row(
                            "窗口动画：" + (runtime.motion() ? "开启" : "关闭"),
                            () -> {
                                runtime.motion(!runtime.motion());
                                rebuildControls();
                            }));
            runtime.catalog()
                    .themes()
                    .keySet()
                    .forEach(id -> rows.add(new Row("主题 " + id, () -> runtime.theme(id))));
            rows.add(new Row("背景：主题底色", () -> runtime.background(null)));
            rows.add(new Row("背景缩略图", () -> toggle(Panel.BACKGROUNDS)));
            if (runtime.localBackgrounds() != null) {
                rows.add(
                        new Row(
                                "打开背景目录",
                                () ->
                                        net.minecraft.util.Util.getOperatingSystem()
                                                .open(
                                                        runtime.localBackgrounds()
                                                                .directory()
                                                                .toFile())));
                rows.add(
                        new Row(
                                "刷新背景",
                                () -> {
                                    runtime.reloadBackgrounds();
                                    notice = runtime.localBackgrounds().error();
                                    rebuildControls();
                                }));
            }
        }
        int startY =
                panel == Panel.FEATURES
                        ? MENU_START_Y + BUTTON_HEIGHT + SEARCH_RESULTS_GAP
                        : MENU_START_Y;
        int pageButtonY = height - BOTTOM_BAR_HEIGHT - PAGE_BOTTOM_GAP - BUTTON_HEIGHT;
        // 按可用高度分页，避免注册项较多时按钮伸入下方栏位。
        int rowHeight = panel == Panel.BACKGROUNDS ? BackgroundButton.HEIGHT : BUTTON_HEIGHT;
        int rowPitch = rowHeight + MENU_ROW_GAP;
        int count = Math.max(1, (pageButtonY - PAGE_TOP_GAP - startY) / rowPitch);
        int maxPage = Math.max(0, (rows.size() - 1) / count);
        page = Math.min(page, maxPage);
        for (int i = page * count; i < Math.min(rows.size(), (page + 1) * count); i++) {
            var row = rows.get(i);
            int rowY = startY + (i - page * count) * rowPitch;
            if (row.background != null) {
                addDrawableChild(
                        new BackgroundButton(
                                runtime,
                                row.background,
                                row.label,
                                MENU_INSET,
                                rowY,
                                panelWidth - 2 * MENU_INSET,
                                row.action));
            } else {
                button(row.label, MENU_INSET, rowY, panelWidth - 2 * MENU_INSET, row.action);
            }
        }
        if (maxPage > 0) {
            button(
                    "上一页",
                    MENU_INSET,
                    pageButtonY,
                    PAGE_BUTTON_WIDTH,
                    () -> {
                        page = Math.max(0, page - 1);
                        rebuildControls();
                    });
            button(
                    "下一页",
                    MENU_INSET + PAGE_BUTTON_WIDTH + PAGE_BUTTON_GAP,
                    pageButtonY,
                    PAGE_BUTTON_WIDTH,
                    () -> {
                        page = Math.min(maxPage, page + 1);
                        rebuildControls();
                    });
        }
    }

    private void chooseBackground(String id) {
        // 失败仍保持旧背景，并把原因显示在工作台提示区。
        try {
            runtime.background(id);
            runtime.savePreferences();
        } catch (IllegalArgumentException failure) {
            notice = failure.getMessage();
        }
    }

    private void button(String text, int x, int y, int w, Runnable action) {
        // 统一菜单按钮的高度和回调转接，调用点只提供布局与动作。
        addDrawableChild(new ThemedButton(runtime, text, x, y, w, BUTTON_HEIGHT, action));
    }

    private UiWindowManager.WindowState hitWindow(double x, double y) {
        // 动画中的外框也是输入边界，最小化的转场只展示而不接收业务输入。
        var states = new ArrayList<>(runtime.workspace().windowBar());
        Collections.reverse(states);
        for (var state : states) {
            if (!state.minimized()
                    && displayed.getOrDefault(state.key(), state.bounds()).contains(x, y)) {
                return state;
            }
        }
        return null;
    }

    private void buildSizeControls() {
        // 文本尺寸与拖动缩放走同一个管理器；错误输入只标红，不改变当前窗口。
        var state =
                runtime.workspace().windowBar().stream()
                        .filter(value -> value.key().equals(sizeTarget))
                        .findFirst()
                        .orElse(null);
        if (state == null) {
            sizeTarget = null;
            return;
        }
        widthInput = sizeInput(MENU_INSET, "宽度", state.desiredBounds().width());
        heightInput =
                sizeInput(
                        MENU_INSET + SIZE_INPUT_WIDTH + MENU_ROW_GAP,
                        "高度",
                        state.desiredBounds().height());
        button(
                "应用尺寸",
                MENU_INSET,
                MENU_START_Y + MENU_ROW_PITCH,
                PAGE_BUTTON_WIDTH,
                this::submitSize);
        button(
                "取消",
                MENU_INSET + PAGE_BUTTON_WIDTH + PAGE_BUTTON_GAP,
                MENU_START_Y + MENU_ROW_PITCH,
                PAGE_BUTTON_WIDTH,
                () -> {
                    sizeTarget = null;
                    rebuildControls();
                });
        setFocused(widthInput);
    }

    private TextFieldWidget sizeInput(int x, String label, int value) {
        var input =
                new TextFieldWidget(
                        textRenderer,
                        x,
                        MENU_START_Y,
                        SIZE_INPUT_WIDTH,
                        BUTTON_HEIGHT,
                        Text.literal(label));
        input.setMaxLength(SIZE_INPUT_MAX_LENGTH);
        input.setText(Integer.toString(value));
        input.setEditableColor(runtime.color(TEXT));
        addDrawableChild(input);
        return input;
    }

    private void submitSize() {
        // 管理器拒绝非法文本时保留草稿，用户可以继续编辑而不丢失输入。
        if (!runtime.workspace()
                .windows()
                .resize(sizeTarget, widthInput.getText(), heightInput.getText())) {
            widthInput.setEditableColor(runtime.color(INPUT_ERROR));
            heightInput.setEditableColor(runtime.color(INPUT_ERROR));
            return;
        }
        sizeTarget = null;
        runtime.savePreferences();
        rebuildControls();
    }

    private void moveHud(String id, int dx, int dy) {
        // 累加相对锚点的偏移，其余显示和缩放偏好保持原值。
        var placement = runtime.hudLayout().placement(id);
        runtime.hudLayout()
                .place(
                        id,
                        new HudLayout.Placement(
                                placement.visible(),
                                placement.offsetX() + dx,
                                placement.offsetY() + dy,
                                placement.scale()));
        rebuildControls();
    }

    private void toggle(Panel target) {
        // 切换菜单会中断窗口交互，防止遮罩后的旧拖动仍收到释放事件。
        sizeTarget = null;
        panel = panel == target ? Panel.NONE : target;
        page = 0;
        runtime.cancelInput();
        pressed = null;
        rebuildControls();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 绘制顺序固定为背景、业务窗口、HUD 预览和菜单，后绘制的层接收优先输入。
        context.fill(0, 0, width, height, runtime.color(BACKGROUND));
        runtime.renderBackground(context, width, height);
        if (runtime.background() != null) {
            context.fill(0, 0, width, height, runtime.color(BACKGROUND_OVERLAY));
        }
        long now = System.nanoTime();
        var states = runtime.workspace().windowBar();
        motions.keySet().removeIf(key -> !runtime.workspace().windows().contains(key));
        displayed.clear();
        for (var state : states) {
            var motion =
                    motions.computeIfAbsent(state.key(), key -> new WindowMotion(state.bounds()));
            var anchor =
                    anchors.getOrDefault(
                            state.key(),
                            new UiWindowManager.Rect(
                                    Math.max(0, width / 2 - RESTORE_ANCHOR_WIDTH / 2),
                                    height - WINDOW_BAR_BOTTOM_GAP - WINDOW_BAR_BUTTON_HEIGHT,
                                    RESTORE_ANCHOR_WIDTH,
                                    WINDOW_BAR_BUTTON_HEIGHT));
            boolean immediate =
                    state.key().equals(resizing)
                            || state.key().equals(runtime.workspace().windows().capturedKey());
            motion.target(
                    state.minimized() ? anchor : state.bounds(),
                    now,
                    runtime.motion() && !immediate);
            if (state.minimized() && motion.settled(now)) {
                continue;
            }
            var bounds = motion.sample(now);
            displayed.put(state.key(), bounds);
            runtime.renderWindow(context, state, bounds, !state.minimized(), mouseX, mouseY, delta);
        }
        clearDepth(context);
        if (panel == Panel.HUD) {
            runtime.renderHuds(context, width, height, delta);
            for (var hud : runtime.catalog().huds().values()) {
                if (runtime.hudLayout().placement(hud.id()).visible()) {
                    var bounds = runtime.hudLayout().bounds(hud.id(), width, height);
                    context.drawBorder(
                            bounds.x(),
                            bounds.y(),
                            bounds.width(),
                            bounds.height(),
                            runtime.color(ACCENT));
                }
            }
        }
        clearDepth(context);
        drawBottom(context);
        context.fill(0, 0, width, TOOLBAR_HEIGHT, runtime.color(TOOLBAR));
        context.drawBorder(0, 0, width, TOOLBAR_HEIGHT, runtime.color(TOOLBAR_BORDER));
        if (panel != Panel.NONE) {
            context.fill(
                    TOOLBAR_PADDING,
                    TOOLBAR_HEIGHT,
                    Math.min(width - TOOLBAR_PADDING, TOOLBAR_PADDING + MENU_WIDTH),
                    height - BOTTOM_BAR_HEIGHT - MENU_BOTTOM_GAP,
                    runtime.color(MENU));
            context.drawBorder(
                    TOOLBAR_PADDING,
                    TOOLBAR_HEIGHT,
                    Math.min(width - 2 * TOOLBAR_PADDING, MENU_WIDTH),
                    Math.max(1, height - BOTTOM_BAR_HEIGHT - MENU_BOTTOM_GAP - TOOLBAR_HEIGHT),
                    runtime.color(MENU_BORDER));
        }
        if (sizeTarget != null) {
            context.fill(
                    TOOLBAR_PADDING,
                    TOOLBAR_HEIGHT,
                    MENU_WIDTH,
                    SIZE_PANEL_HEIGHT,
                    runtime.color(MENU));
        }
        super.render(context, mouseX, mouseY, delta);
        runtime.contextMenu().render(context, width, height, mouseX, mouseY, runtime::color);
        var payload = runtime.drag().current();
        if (payload != null) {
            // 拖动物品浮层最后绘制，不受来源窗口边界裁剪；它只是预览，不移走权威物品。
            ItemDrawing.draw(
                    context,
                    payload.item(),
                    payload.count(),
                    mouseX + DRAG_POINTER_GAP,
                    mouseY + DRAG_POINTER_GAP,
                    DRAG_ICON_SIZE,
                    DRAG_ICON_SIZE,
                    runtime::color);
        }
        if (!notice.isEmpty()) {
            context.drawTextWithShadow(
                    textRenderer,
                    textRenderer.trimToWidth(notice, width - 2 * NOTICE_INSET),
                    NOTICE_INSET,
                    height - BOTTOM_BAR_HEIGHT - NOTICE_BOTTOM_GAP,
                    runtime.color(ACCENT));
        }
    }

    private static void clearDepth(DrawContext context) {
        // 先提交已排队的绘制再清深度；否则 owo 局部深度会错误遮住后续窗口。
        context.draw();
        RenderSystem.clear(
                org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
    }

    private void drawBottom(DrawContext context) {
        // 每帧按注册内容重建可点击区域；槽位栏和窗口栏共用横向滚动偏移。
        anchors.clear();
        bottomHits.clear();
        slotHits.clear();
        context.fill(0, height - BOTTOM_BAR_HEIGHT, width, height, runtime.color(TOOLBAR));
        context.enableScissor(0, height - BOTTOM_BAR_HEIGHT, width, height);
        int slotY = height - BOTTOM_BAR_HEIGHT + SLOT_ROW_PADDING_TOP;
        int windowY = height - WINDOW_BAR_BOTTOM_GAP - WINDOW_BAR_BUTTON_HEIGHT;
        int x = BAR_PADDING_X - bottomOffset;
        for (var bar : runtime.catalog().slotBars().values()) {
            for (var slot : bar.slots()) {
                var content = runtime.slotContent(bar.id(), slot);
                int sx = x;
                context.fill(x, slotY, x + SLOT_WIDTH, slotY + SLOT_HEIGHT, runtime.color(HEADER));
                context.drawTextWithShadow(
                        textRenderer,
                        textRenderer.trimToWidth(
                                content.label(), SLOT_WIDTH - 2 * SLOT_TEXT_PADDING_X),
                        x + SLOT_TEXT_PADDING_X,
                        slotY + SLOT_TEXT_PADDING_Y,
                        runtime.color(TEXT));
                if (content.item() != null) {
                    ItemDrawing.draw(
                            context,
                            content.item(),
                            content.item().count(),
                            x,
                            slotY,
                            SLOT_WIDTH,
                            SLOT_HEIGHT,
                            runtime::color);
                }
                slotHits.add(
                        new SlotHit(
                                new UiWindowManager.Rect(x, slotY, SLOT_WIDTH, SLOT_HEIGHT),
                                bar.id(),
                                slot));
                bottomHits.add(
                        new Hit(
                                new UiWindowManager.Rect(sx, slotY, SLOT_WIDTH, SLOT_HEIGHT),
                                content.activate()));
                x += SLOT_PITCH;
            }
            x += SLOT_GROUP_GAP;
        }
        int barWidth = x + bottomOffset;
        x = BAR_PADDING_X - bottomOffset;
        for (String featureId : runtime.workspace().pinnedFeatures()) {
            var feature = runtime.catalog().features().get(featureId);
            int buttonWidth =
                    Math.min(
                            WINDOW_BAR_MAX_BUTTON_WIDTH,
                            textRenderer.getWidth(feature.title()) + WINDOW_BAR_TITLE_EXTRA_WIDTH);
            context.fill(
                    x,
                    windowY,
                    x + buttonWidth - WINDOW_BAR_BUTTON_GAP,
                    windowY + WINDOW_BAR_BUTTON_HEIGHT,
                    runtime.color(HEADER));
            context.drawTextWithShadow(
                    textRenderer,
                    "★ " + feature.title(),
                    x + WINDOW_BAR_TEXT_PADDING_X,
                    windowY + WINDOW_BAR_TEXT_PADDING_Y,
                    runtime.color(ACCENT));
            bottomHits.add(
                    new Hit(
                            new UiWindowManager.Rect(
                                    x, windowY, buttonWidth, WINDOW_BAR_BUTTON_HEIGHT),
                            () -> {
                                if (runtime.catalog()
                                                .windows()
                                                .get(feature.windowType())
                                                .instancePolicy()
                                        == FrameworkCatalog.InstancePolicy.BY_OBJECT) {
                                    notice = "该入口需要对象 ID，请由模块操作打开";
                                } else {
                                    focused = runtime.open(featureId, null).key();
                                }
                            }));
            x += buttonWidth;
        }
        for (var state : runtime.workspace().windowBar()) {
            int buttonWidth =
                    Math.min(
                            WINDOW_BAR_MAX_BUTTON_WIDTH,
                            textRenderer.getWidth(runtime.title(state))
                                    + WINDOW_BAR_TITLE_EXTRA_WIDTH);
            context.fill(
                    x,
                    windowY,
                    x + buttonWidth - WINDOW_BAR_BUTTON_GAP,
                    windowY + WINDOW_BAR_BUTTON_HEIGHT,
                    runtime.color(HEADER));
            context.drawTextWithShadow(
                    textRenderer,
                    textRenderer.trimToWidth(
                            runtime.title(state), buttonWidth - WINDOW_BAR_TEXT_RESERVED_WIDTH),
                    x + WINDOW_BAR_TEXT_PADDING_X,
                    windowY + WINDOW_BAR_TEXT_PADDING_Y,
                    runtime.color(TEXT));
            anchors.put(
                    state.key(),
                    new UiWindowManager.Rect(
                            Math.max(0, Math.min(x, width - buttonWidth)),
                            windowY,
                            buttonWidth,
                            WINDOW_BAR_BUTTON_HEIGHT));
            bottomHits.add(
                    new Hit(
                            new UiWindowManager.Rect(
                                    x, windowY, buttonWidth, WINDOW_BAR_BUTTON_HEIGHT),
                            () -> {
                                runtime.workspace().windows().restore(state.key());
                                focused = state.key();
                            }));
            x += buttonWidth;
        }
        context.disableScissor();
        bottomOffset =
                Math.min(bottomOffset, Math.max(0, Math.max(barWidth, x + bottomOffset) - width));
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        // 菜单打开时拦截底层交互，其余依次处理栏位、标题栏和窗口内容。
        notice = "";
        if (runtime.contextMenu().click(x, y, button)) {
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && runtime.drag().current() != null) {
            runtime.drag().cancel();
            return true;
        }
        if (panel == Panel.HUD
                && button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && x >= TOOLBAR_PADDING + MENU_WIDTH
                && y >= TOOLBAR_HEIGHT) {
            // 菜单外的 HUD 预览可直接拖动；按绘制逆序命中，重叠部件只选最上层。
            var huds = new ArrayList<>(runtime.catalog().huds().values());
            Collections.reverse(huds);
            for (var hud : huds) {
                if (runtime.hudLayout().placement(hud.id()).visible()
                        && runtime.hudLayout().bounds(hud.id(), width, height).contains(x, y)) {
                    draggingHud = hud.id();
                    return true;
                }
            }
        }
        if (y < TOOLBAR_HEIGHT || panel != Panel.NONE || sizeTarget != null) {
            return super.mouseClicked(x, y, button);
        }
        if (y >= height - BOTTOM_BAR_HEIGHT) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                bottomHits.stream()
                        .filter(hit -> hit.bounds.contains(x, y))
                        .findFirst()
                        .ifPresent(hit -> hit.action.run());
            } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                slotHits.stream()
                        .filter(hit -> hit.bounds().contains(x, y))
                        .findFirst()
                        .ifPresent(hit -> runtime.slotContent(hit.bar(), hit.slot()).clear().run());
            }
            return true;
        }
        var state = hitWindow(x, y);
        if (state == null) {
            focused = null;
            return false;
        }
        if (!state.key().equals(focused)) {
            runtime.cancelInput();
        }
        focused = state.key();
        runtime.workspace().windows().focus(focused);
        var bounds = displayed.getOrDefault(state.key(), state.bounds());
        // 抓住动画中的窗口即接续当帧位置，绘制矩形和后续输入不发生跳变。
        if (!bounds.equals(state.bounds())) {
            runtime.workspace().windows().settleAt(state.key(), bounds);
            motions.get(state.key()).target(state.bounds(), System.nanoTime(), false);
        }
        if (y < bounds.y() + WINDOW_HEADER_HEIGHT && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            var action = WindowChrome.hit(state, bounds, x, y);
            if (action == null) {
                runtime.workspace().windows().beginDrag(focused, x, y);
            } else {
                runtime.cancelInput();
                switch (action) {
                    case CLOSE -> {
                        runtime.savePreferences();
                        runtime.workspace().windows().close(focused);
                        focused = null;
                    }
                    case MINIMIZE -> {
                        runtime.workspace().windows().minimize(focused);
                        focused = null;
                    }
                    case PIN -> runtime.pinWindow(focused, !state.pinned());
                    case SIZE -> {
                        sizeTarget = state.key();
                        rebuildControls();
                    }
                }
            }
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && x >= bounds.x() + bounds.width() - RESIZE_GRIP_SIZE
                && y >= bounds.y() + bounds.height() - RESIZE_GRIP_SIZE) {
            runtime.cancelInput();
            resizing = focused;
            return true;
        }
        pressed = focused;
        // 保存按下时的目标，后续拖动和释放继续交给同一组件树；坐标转为适配器局部值。
        var view = runtime.view(focused);
        if (view != null) {
            view.adapter.mouseClicked(x - view.adapter.x(), y - view.adapter.y(), button);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (draggingHud != null) {
            moveHud(draggingHud, (int) dx, (int) dy);
            return true;
        }
        if (resizing != null) {
            // 右下角尺寸控制复用管理器的最小尺寸和视口裁切规则。
            var state =
                    runtime.workspace().windowBar().stream()
                            .filter(value -> value.key().equals(resizing))
                            .findFirst()
                            .orElse(null);
            if (state != null) {
                runtime.workspace()
                        .windows()
                        .resize(
                                resizing,
                                Integer.toString(Math.max(1, (int) x - state.bounds().x())),
                                Integer.toString(Math.max(1, (int) y - state.bounds().y())));
            }
            return true;
        }
        // 标题栏拖动由窗口管理器捕获，内容拖动则交给最初按下的窗口。
        if (runtime.workspace().windows().dragTo(x, Math.max(TOOLBAR_HEIGHT, y))) {
            return true;
        }
        var view = pressed == null ? null : runtime.view(pressed);
        return view != null
                ? view.adapter.mouseDragged(
                        x - view.adapter.x(), y - view.adapter.y(), button, dx, dy)
                : super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (draggingHud != null) {
            draggingHud = null;
            runtime.savePreferences();
            return true;
        }
        if (resizing != null) {
            resizing = null;
            runtime.savePreferences();
            return true;
        }
        if (runtime.drag().current() != null
                && (button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                        || button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE)) {
            // 释放按当前位置分发而不是按来源窗口分发，窗口遮挡和底栏命中都只有一个落点。
            pressed = null;
            try {
                if (panel == Panel.NONE && y >= height - BOTTOM_BAR_HEIGHT) {
                    slotHits.stream()
                            .filter(hit -> hit.bounds().contains(x, y))
                            .findFirst()
                            .ifPresent(
                                    hit -> {
                                        var content = runtime.slotContent(hit.bar(), hit.slot());
                                        if (content.accepts().test(runtime.drag().current())) {
                                            runtime.drag().consume(content.drop());
                                        }
                                    });
                } else if (panel == Panel.NONE && y >= TOOLBAR_HEIGHT) {
                    var target = hitWindow(x, y);
                    var targetView = target == null ? null : runtime.view(target.key());
                    if (targetView != null) {
                        targetView.dropItem(x, y);
                    }
                }
            } finally {
                runtime.drag().cancel();
            }
            return true;
        }
        // 先结束外框拖动并清除按下目标，组件回调即使重入也不会复用这次输入。
        boolean dragged = runtime.workspace().windows().endDrag();
        if (dragged) {
            runtime.savePreferences();
        }
        var view = pressed == null ? null : runtime.view(pressed);
        pressed = null;
        if (view != null) {
            return view.adapter.mouseReleased(x - view.adapter.x(), y - view.adapter.y(), button);
        }
        return dragged || super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        if (runtime.contextMenu().isOpen()) {
            runtime.contextMenu().scroll(amount);
            return true;
        }
        var payload = runtime.drag().current();
        if (payload != null) {
            runtime.drag().count(payload.count() + (int) amount);
            return true;
        }
        // 下方区域横向滚动注册栏位；窗口内容滚动按鼠标所在的最上层窗口分发。
        if (panel != Panel.NONE || sizeTarget != null) {
            return super.mouseScrolled(x, y, amount);
        }
        if (y >= height - BOTTOM_BAR_HEIGHT) {
            bottomOffset = Math.max(0, bottomOffset - (int) (amount * SLOT_PITCH));
            return true;
        }
        var state = hitWindow(x, y);
        var view = state == null ? null : runtime.view(state.key());
        return view != null
                && view.adapter.mouseScrolled(x - view.adapter.x(), y - view.adapter.y(), amount);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (sizeTarget != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                sizeTarget = null;
                rebuildControls();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                submitSize();
                return true;
            }
            return super.keyPressed(key, scan, modifiers);
        }
        if (runtime.contextMenu().key(key)) {
            return true;
        }
        if (panel == Panel.NONE
                && key == GLFW.GLFW_KEY_TAB
                && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            // Ctrl+Tab 循环可见窗口；普通 Tab 继续交给窗口内部的键盘焦点系统。
            var windows =
                    runtime.workspace().windowBar().stream()
                            .filter(state -> !state.minimized())
                            .toList();
            if (!windows.isEmpty()) {
                runtime.cancelInput();
                focused = windows.get(0).key();
                runtime.workspace().windows().focus(focused);
            }
            return true;
        }
        if (runtime.drag().current() != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                runtime.drag().cancel();
                return true;
            }
            if (key == GLFW.GLFW_KEY_R) {
                runtime.drag().rotate();
                return true;
            }
        }
        // Escape 先收起菜单；非退出按键优先交给聚焦窗口，例如库存的旋转操作。
        if (key == GLFW.GLFW_KEY_ESCAPE && panel != Panel.NONE) {
            panel = Panel.NONE;
            rebuildControls();
            return true;
        }
        var view =
                focused == null || panel != Panel.NONE || sizeTarget != null
                        ? null
                        : runtime.view(focused);
        if (key != GLFW.GLFW_KEY_ESCAPE
                && view != null
                && view.adapter.keyPressed(key, scan, modifiers)) {
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        // 菜单搜索与业务输入互斥，防止一个字符同时进入两个输入框。
        var view =
                focused == null || panel != Panel.NONE || sizeTarget != null
                        ? null
                        : runtime.view(focused);
        return view != null
                ? view.adapter.charTyped(chr, modifiers)
                : super.charTyped(chr, modifiers);
    }

    @Override
    public void removed() {
        // 暂时离开工作台只取消交互，窗口实例及订阅由各自生命周期决定是否关闭。
        runtime.cancelInput();
        runtime.savePreferences();
        resizing = null;
        draggingHud = null;
        sizeTarget = null;
        pressed = null;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private enum Panel {
        NONE,
        FEATURES,
        HUD,
        APPEARANCE,
        BACKGROUNDS
    }

    private record Row(String label, Runnable action, String background) {
        Row(String label, Runnable action) {
            this(label, action, null);
        }
    }

    private record Hit(UiWindowManager.Rect bounds, Runnable action) {}

    private record SlotHit(UiWindowManager.Rect bounds, String bar, FrameworkCatalog.Slot slot) {}
}

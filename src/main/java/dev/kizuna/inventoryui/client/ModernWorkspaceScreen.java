package dev.kizuna.inventoryui.client;

import static dev.kizuna.inventoryui.client.ModernMetrics.*;
import static dev.kizuna.inventoryui.theme.ThemeTokens.*;


import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowManager;
import dev.kizuna.inventoryui.workspace.UiVersion;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** 工作台外壳：目录与外观是明确入口，业务窗口由注册目录生成。 */
public final class ModernWorkspaceScreen extends Screen implements VanillaHudHidden {
    // 菜单尺寸均为逻辑像素；坐标由尺寸和间距推导，不在绘制及输入中重复填写偏移。
    private static final int TOOLBAR_PADDING = 4;
    private static final int FEATURE_BUTTON_WIDTH = 44;
    private static final int APPEARANCE_BUTTON_WIDTH = 44;
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

    private final ClientRuntime runtime;
    private ModernWindowLayer layer;
    private Panel panel = Panel.NONE;
    private EditBox search;
    private String query = "";
    private int page;
    private int menuBottom;
    private String notice = "";
    public ModernWorkspaceScreen(ClientRuntime runtime) {
        // 重开工作区时把最高层可见窗口作为键盘目标，保持程序打开的聚焦语义。
        super(Component.literal("Inventory Workspace"));
        this.runtime = runtime;
    }

    @Override
    protected void init() {
        // 宿主提供 GUI 逻辑像素，缩放后重新计算可用区。
        updateViewport();
        rebuildControls();
    }

    private void updateViewport() {
        var viewport = new UiWindowManager.Rect(0, 0, width, height);
        layer = (ModernWindowLayer) runtime.attach(this, viewport);
        layer.toolbarFloor(TOOLBAR_HEIGHT);
    }

    private void rebuildControls() {
        // 菜单控件随面板状态重建，业务窗口及其订阅仍由独立运行时持有。
        clearWidgets();
        search = null;
        int toolbarX = TOOLBAR_PADDING;
        button("功能", toolbarX, TOOLBAR_PADDING, FEATURE_BUTTON_WIDTH, () -> toggle(Panel.FEATURES));
        toolbarX += FEATURE_BUTTON_WIDTH + TOOLBAR_PADDING;
        button(
                "外观",
                toolbarX,
                TOOLBAR_PADDING,
                APPEARANCE_BUTTON_WIDTH,
                () -> toggle(Panel.APPEARANCE));
        if (panel == Panel.NONE) {
            return;
        }
        int panelWidth = Math.min(width - 2 * TOOLBAR_PADDING, MENU_WIDTH);
        List<Row> rows = new ArrayList<>();
        if (panel == Panel.FEATURES) {
            search =
                    new EditBox(
                            font,
                            MENU_INSET,
                            MENU_START_Y,
                            panelWidth - 2 * MENU_INSET,
                            BUTTON_HEIGHT,
                            Component.literal("搜索功能"));
            search.setMaxLength(SEARCH_MAX_LENGTH);
            search.setValue(query);
            search.setResponder(
                    value -> {
                        query = value;
                        page = 0;
                        rebuildControls();
                        setFocused(search);
                    });
            addRenderableWidget(search);
            for (var feature : runtime.workspace().features(query)) {
                // 每个功能只提供打开入口；按对象分实例的功能仍需模块提供具体对象 ID。
                var policy = runtime.catalog().windows().get(feature.windowType()).instancePolicy();
                rows.add(
                        new Row(
                                feature.category() + " / " + feature.title() + retryHint(feature.id()),
                                () -> {
                                    if (policy == FrameworkCatalog.InstancePolicy.BY_OBJECT) {
                                        notice = "该入口需要对象 ID，请由模块的物品或容器操作打开";
                                        return;
                                    }
                                    runtime.open(feature.id(), null);
                                    panel = Panel.NONE;
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
            rows.add(new Row("界面：切换到旧版", () -> runtime.uiVersion(UiVersion.CLASSIC)));
            rows.add(
                    new Row(
                            "窗口动画：" + (runtime.motion() ? "开启" : "关闭"),
                            () -> {
                                runtime.motion(!runtime.motion());
                                rebuildControls();
                            }));
            runtime.catalog()
                    .themes()
                    .values()
                    .forEach(
                            theme ->
                                    rows.add(
                                            new Row(
                                                    "主题：" + theme.title(),
                                                    () -> runtime.theme(theme.id()),
                                                    null,
                                                    theme.id())));
            rows.add(new Row("背景：主题底色（默认透明）", () -> runtime.background(null)));
            rows.add(new Row("背景缩略图", () -> toggle(Panel.BACKGROUNDS)));
            if (runtime.localBackgrounds() != null) {
                rows.add(
                        new Row(
                                "打开背景目录",
                                () ->
                                        net.minecraft.util.Util.getPlatform()
                                                .openFile(
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
        int pageButtonY = height - PAGE_BOTTOM_GAP - BUTTON_HEIGHT;
        // 按可用高度分页，避免注册项较多时按钮伸入下方栏位。
        int rowHeight = panel == Panel.BACKGROUNDS ? BackgroundButton.HEIGHT : BUTTON_HEIGHT;
        int rowPitch = rowHeight + MENU_ROW_GAP;
        int count = Math.max(1, (pageButtonY - PAGE_TOP_GAP - startY) / rowPitch);
        int maxPage = Math.max(0, (rows.size() - 1) / count);
        page = Math.min(page, maxPage);
        int visibleRows = Math.min(count, rows.size() - page * count);
        // 菜单随内容收边；只有确实需要分页时才使用底部的翻页区域。
        menuBottom = startY + visibleRows * rowPitch + MENU_INSET;
        if (maxPage > 0) {
            menuBottom = pageButtonY + BUTTON_HEIGHT + MENU_INSET;
        }
        menuBottom = Math.min(menuBottom, height - MENU_BOTTOM_GAP);
        for (int i = page * count; i < Math.min(rows.size(), (page + 1) * count); i++) {
            var row = rows.get(i);
            int rowY = startY + (i - page * count) * rowPitch;
            if (row.background != null) {
                addRenderableWidget(
                        new BackgroundButton(
                                runtime,
                                row.background,
                                row.label,
                                MENU_INSET,
                                rowY,
                                panelWidth - 2 * MENU_INSET,
                                row.action));
            } else if (row.theme != null) {
                var themeBounds = new UiWindowManager.Rect(
                        MENU_INSET, rowY, panelWidth - 2 * MENU_INSET, BUTTON_HEIGHT);
                addRenderableWidget(
                        new ThemeButton(
                                runtime,
                                row.theme,
                                row.label,
                                themeBounds,
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

    private String retryHint(String id) {
        if (runtime.restoreRetryable(id)) {
            return Component.translatable("kizuna_inventory_ui.compact.retry").getString();
        }
        return "";
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
        addRenderableWidget(new ThemedButton(runtime, text, x, y, w, BUTTON_HEIGHT, action));
    }

    private void toggle(Panel target) {
        // 切换菜单会中断窗口交互，防止遮罩后的旧拖动仍收到释放事件。
        panel = panel == target ? Panel.NONE : target;
        page = 0;
        layer.cancelInput();
        rebuildControls();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // 游戏内直接透出已绘制的世界，不叠加原版模糊和暗幕，也不修改玩家视角；字幕仍按原版排在背景层。
        // 无世界的独立演示沿用原版菜单背景，避免透明区域没有底色。
        if (minecraft.level == null) {
            super.extractBackground(context, mouseX, mouseY, delta);
        } else {
            minecraft.gui.hud.extractDeferredSubtitles();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, runtime.color(BACKGROUND));
        runtime.renderBackground(context, width, height);
        if (runtime.background() != null) {
            context.fill(0, 0, width, height, runtime.color(BACKGROUND_OVERLAY));
        }
        layer.extractWindows(context, mouseX, mouseY, delta);
        context.nextStratum();
        if (panel != Panel.NONE) {
            context.fill(
                    TOOLBAR_PADDING,
                    TOOLBAR_HEIGHT,
                    Math.min(width - TOOLBAR_PADDING, TOOLBAR_PADDING + MENU_WIDTH),
                    menuBottom,
                    runtime.color(MENU));
            context.outline(
                    TOOLBAR_PADDING,
                    TOOLBAR_HEIGHT,
                    Math.min(width - 2 * TOOLBAR_PADDING, MENU_WIDTH),
                    Math.max(1, menuBottom - TOOLBAR_HEIGHT),
                    runtime.color(MENU_BORDER));
        }
        super.extractRenderState(context, mouseX, mouseY, delta);
        layer.extractOverlay(context, mouseX, mouseY);
        if (!notice.isEmpty()) {
            context.text(
                    font,
                    font.plainSubstrByWidth(notice, width - 2 * NOTICE_INSET),
                    NOTICE_INSET,
                    height - NOTICE_BOTTOM_GAP,
                    runtime.color(ACCENT));
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // 菜单打开时拦截底层交互，其余依次处理栏位、标题栏和窗口内容。
        double y = event.y();
        notice = "";
        if (layer.clickOverlay(event)) {
            return true;
        }
        if (y < TOOLBAR_HEIGHT || panel != Panel.NONE) {
            return super.mouseClicked(event, doubleClick);
        }
        return layer.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return layer.mouseDragged(event, dx, dy) || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        // 宿主菜单和底栏盖住的区域只结束拖放，不向窗内发送物品。
        if (runtime.drag().current() != null && (panel != Panel.NONE
                || event.y() < TOOLBAR_HEIGHT || event.y() >= height)) {
            layer.cancelInput();
            return true;
        }
        return layer.mouseReleased(event) || super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        // 与旧版一致只按纵向滚动量处理；owo 适配器同样只把纵向量交给组件。
        if (layer.scrollOverlay(scrollY)) {
            return true;
        }
        // 展开菜单独占滚轮输入。
        if (panel != Panel.NONE) {
            return super.mouseScrolled(x, y, scrollX, scrollY);
        }
        return layer.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // 模态控件与菜单先处理，业务窗口只接收工作台明确路由的剩余事件。
        int key = event.key();
        if (layer.keyOverlay(event)) {
            return true;
        }
        // Escape 先收起菜单；非退出按键优先交给聚焦窗口，例如库存的旋转操作。
        if (key == GLFW.GLFW_KEY_ESCAPE && panel != Panel.NONE) {
            panel = Panel.NONE;
            rebuildControls();
            return true;
        }
        return (panel == Panel.NONE && layer.keyInWindow(event)) || super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        return layer.keyReleased(event) || super.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (panel != Panel.NONE) {
            return super.charTyped(event);
        }
        return layer.charTyped(event) || super.charTyped(event);
    }

    @Override
    public void removed() {
        // 只解绑这个屏幕；晚到的 removed 不能撤销新宿主的输入。
        if (layer != null) {
            layer.detach();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private enum Panel {
        NONE,
        FEATURES,
        APPEARANCE,
        BACKGROUNDS
    }

    /** background、theme 至多一个非空，分别对应缩略图卡片与带色块的主题行。 */
    private record Row(String label, Runnable action, String background, String theme) {
        Row(String label, Runnable action) {
            this(label, action, null, null);
        }

        Row(String label, Runnable action, String background) {
            this(label, action, background, null);
        }
    }
}

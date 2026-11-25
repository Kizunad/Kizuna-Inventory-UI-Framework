package dev.kizuna.inventoryui.client;

import static dev.kizuna.inventoryui.client.ModernMetrics.*;
import static dev.kizuna.inventoryui.theme.ThemeTokens.*;


import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowManager;
import dev.kizuna.inventoryui.workspace.IconStyle;
import dev.kizuna.inventoryui.workspace.UiVersion;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.client.resources.language.I18n;

/** 工作台外壳：目录与外观是明确入口，业务窗口由注册目录生成。 */
public final class ModernWorkspaceScreen extends Screen implements VanillaHudHidden {
    // 菜单尺寸均为逻辑像素；坐标由尺寸和间距推导，不在绘制及输入中重复填写偏移。
    private static final int TOOLBAR_PADDING = 4;
    private static final int MIN_TOOLBAR_BUTTON_WIDTH = 44;
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
        super(Component.translatable("kizuna_inventory_ui.workspace.title"));
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
    }

    private void rebuildControls() {
        // 菜单控件随面板状态重建，业务窗口及其订阅仍由独立运行时持有。
        clearWidgets();
        search = null;
        String features = label("features");
        int featuresWidth = toolbarButtonWidth(features);
        button(features, TOOLBAR_PADDING, TOOLBAR_PADDING, featuresWidth, () -> toggle(Panel.FEATURES));
        int appearanceX = TOOLBAR_PADDING * 2 + featuresWidth;
        String appearance = label("appearance");
        button(appearance, appearanceX, TOOLBAR_PADDING, toolbarButtonWidth(appearance),
                () -> toggle(Panel.APPEARANCE));
        if (panel == Panel.NONE) {
            return;
        }
        int panelWidth = Math.min(width - 2 * TOOLBAR_PADDING, MENU_WIDTH);
        int rowWidth = panelWidth - 2 * MENU_INSET;
        List<Row> rows = new ArrayList<>();
        int startY = MENU_START_Y;
        if (panel == Panel.FEATURES) {
            addSearch(rowWidth);
            addFeatures(rows);
            startY += BUTTON_HEIGHT + SEARCH_RESULTS_GAP;
        } else if (panel == Panel.BACKGROUNDS) {
            addBackgrounds(rows);
        } else {
            addAppearance(rows);
        }
        showPage(rows, startY, rowWidth);
    }

    private int toolbarButtonWidth(String label) {
        // 标题按游戏字体的实际宽度排；中文保留原有的最小按钮宽度。
        return Math.max(MIN_TOOLBAR_BUTTON_WIDTH, font.width(label) + ThemedButton.TEXT_INSET * 2);
    }

    private void addSearch(int width) {
        // 重新过滤后把输入焦点交还新建的搜索框。
        search = new EditBox(font, MENU_INSET, MENU_START_Y, width, BUTTON_HEIGHT,
                Component.translatable("kizuna_inventory_ui.workspace.search"));
        search.setMaxLength(SEARCH_MAX_LENGTH);
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            page = 0;
            rebuildControls();
            setFocused(search);
        });
        addRenderableWidget(search);
    }

    private void addFeatures(List<Row> rows) {
        // 目录按稳定身份登记，搜索和菜单使用当前语言显示的标题和分类。
        String needle = query.strip().toLowerCase(Locale.ROOT);
        for (var feature : runtime.workspace().features("")) {
            String title = ModernEdition.featureLabel(feature, "title", feature.title());
            String category = ModernEdition.featureLabel(feature, "category", feature.category());
            if (title.toLowerCase(Locale.ROOT).contains(needle) || category.toLowerCase(Locale.ROOT).contains(needle)) {
                rows.add(new Row(category + " / " + title + retryHint(feature.id()), () -> openFeature(feature)));
            }
        }
    }

    private void openFeature(FrameworkCatalog.Feature feature) {
        // 按对象分实例的功能需要模块从物品或容器提供具体身份。
        var policy = runtime.catalog().windows().get(feature.windowType()).instancePolicy();
        if (policy == FrameworkCatalog.InstancePolicy.BY_OBJECT) {
            notice = label("requires_object");
            return;
        }
        runtime.open(feature.id(), null);
        panel = Panel.NONE;
        rebuildControls();
    }

    private void addBackgrounds(List<Row> rows) {
        // 文件名来自本地目录，内置背景按资源 ID 查当前语言的名称。
        var language = net.minecraft.locale.Language.getInstance();
        for (String id : runtime.catalog().backgrounds().keySet()) {
            String name = language.getOrDefault("background." + id.replace(':', '.'), id);
            rows.add(new Row(name, () -> chooseBackground(id), id));
        }
        var local = runtime.localBackgrounds();
        if (local != null) {
            for (String id : local.entries()) {
                rows.add(new Row(id.substring("local:".length()), () -> chooseBackground(id), id));
            }
        }
    }

    private void addAppearance(List<Row> rows) {
        // 主题和图标由目录决定，本地背景目录可用时才有相应操作。
        rows.add(new Row(label("reset"), runtime::resetWindowLayout));
        rows.add(new Row(label("classic"), () -> runtime.uiVersion(UiVersion.CLASSIC)));
        rows.add(new Row(label(runtime.motion() ? "motion_on" : "motion_off"), () -> {
            runtime.motion(!runtime.motion());
            rebuildControls();
        }));
        var language = net.minecraft.locale.Language.getInstance();
        for (var theme : runtime.catalog().themes().values()) {
            String id = theme.id();
            String name = language.getOrDefault("theme." + id.replace(':', '.'), theme.title());
            rows.add(new Row(label("theme", name), () -> runtime.theme(id), null, id));
        }
        for (var style : IconStyle.values()) {
            rows.add(new Row(iconRowLabel(style), () -> chooseIcons(style)));
        }
        rows.add(new Row(label("background_default"), () -> runtime.background(null)));
        rows.add(new Row(label("backgrounds"), () -> toggle(Panel.BACKGROUNDS)));
        var local = runtime.localBackgrounds();
        if (local != null) {
            rows.add(new Row(label("open_folder"), () -> net.minecraft.util.Util.getPlatform()
                    .openFile(local.directory().toFile())));
            rows.add(new Row(label("refresh"), () -> {
                runtime.reloadBackgrounds();
                notice = local.localizedError();
                rebuildControls();
            }));
        }
    }

    private void showPage(List<Row> rows, int startY, int rowWidth) {
        // 按可用高度分页，菜单底边跟随实际可见行数。
        int pageButtonY = height - PAGE_BOTTOM_GAP - BUTTON_HEIGHT;
        int rowHeight = panel == Panel.BACKGROUNDS ? BackgroundButton.HEIGHT : BUTTON_HEIGHT;
        int rowPitch = rowHeight + MENU_ROW_GAP;
        int count = Math.max(1, (pageButtonY - PAGE_TOP_GAP - startY) / rowPitch);
        int size = rows.size();
        int maxPage = Math.max(0, (size - 1) / count);
        page = Math.min(page, maxPage);
        int first = page * count;
        int end = Math.min(size, first + count);
        for (int i = first; i < end; i++) {
            showRow(rows.get(i), startY + (i - first) * rowPitch, rowWidth);
        }
        int bottom = startY + (end - first) * rowPitch + MENU_INSET;
        if (maxPage > 0) {
            addPageButtons(pageButtonY, maxPage);
            bottom = pageButtonY + BUTTON_HEIGHT + MENU_INSET;
        }
        menuBottom = Math.min(bottom, height - MENU_BOTTOM_GAP);
    }

    private void showRow(Row row, int y, int width) {
        // 缩略图、主题色块和普通按钮使用同一行宽及点击动作。
        if (row.background != null) {
            addRenderableWidget(new BackgroundButton(runtime, row.background, row.label,
                    MENU_INSET, y, width, row.action));
        } else if (row.theme != null) {
            var bounds = new UiWindowManager.Rect(MENU_INSET, y, width, BUTTON_HEIGHT);
            addRenderableWidget(new ThemeButton(runtime, row.theme, row.label, bounds, row.action));
        } else {
            button(row.label, MENU_INSET, y, width, row.action);
        }
    }

    private void addPageButtons(int y, int maxPage) {
        // 切页后的页号始终留在当前过滤结果允许的范围内。
        button(label("previous"), MENU_INSET, y, PAGE_BUTTON_WIDTH, () -> {
            page = Math.max(0, page - 1);
            rebuildControls();
        });
        button(label("next"), MENU_INSET + PAGE_BUTTON_WIDTH + PAGE_BUTTON_GAP, y, PAGE_BUTTON_WIDTH, () -> {
            page = Math.min(maxPage, page + 1);
            rebuildControls();
        });
    }

    private String retryHint(String id) {
        if (runtime.restoreRetryable(id)) {
            return Component.translatable("kizuna_inventory_ui.compact.retry").getString();
        }
        return "";
    }

    private static String label(String key, Object... args) {
        return I18n.get("kizuna_inventory_ui.workspace." + key, args);
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

    private String iconRowLabel(IconStyle style) {
        String key = "kizuna_inventory_ui.icons.row";
        if (style == runtime.iconStyle()) {
            key = "kizuna_inventory_ui.icons.row_current";
        }
        return Component.translatable(key, Component.translatable(style.nameKey())).getString();
    }

    private void chooseIcons(IconStyle style) {
        runtime.iconStyle(style);
        rebuildControls();
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

package dev.kizuna.inventoryui.theme;

/** 框架绘制使用的稳定颜色令牌；主题按令牌覆盖 ARGB 值，不依赖具体组件实现。 */
public final class ThemeTokens {
    /** 展开窗面板上下两端的底色；主题没写时取 PANEL 和 PANEL_BOTTOM。 */
    public static final String EXPANDED_PANEL_TOP = "expanded.panelTop";

    public static final String EXPANDED_PANEL_BOTTOM = "expanded.panelBottom";

    /** 展开窗背后的模糊半径，单位 GUI 像素，不是颜色；0 或没写即不模糊。 */
    public static final String EXPANDED_BLUR_RADIUS = "expanded.blurRadius";

    /** 小窗底色的 RGB；不透明度由每个小窗自己的偏好决定。 */
    public static final String COMPACT_BACKDROP = "compact.backdrop";

    /** 小窗直接画在游戏画面上时的正文色、次要文字色和进度条槽色；主题没写时取窗口里对应的三项。 */
    public static final String COMPACT_TEXT = "compact.text";

    public static final String COMPACT_MUTED_TEXT = "compact.text.muted";
    public static final String COMPACT_TRACK = "compact.track";

    public static final String BACKGROUND = "background";
    public static final String PANEL = "panel";
    public static final String HEADER = "header";
    public static final String TEXT = "text";
    public static final String ACCENT = "accent";
    public static final String MUTED_TEXT = "text.muted";
    public static final String PROGRESS_TRACK = "progress.track";
    public static final String PROGRESS_FILL = "progress.fill";
    public static final String PREVIEW_BACKGROUND = "preview.background";

    /** 棋盘网格中行列之和为偶数的格子底色。 */
    public static final String INVENTORY_CELL_EVEN = "inventory.cellEven";

    /** 棋盘网格中行列之和为奇数的格子底色。 */
    public static final String INVENTORY_CELL_ODD = "inventory.cellOdd";

    public static final String INVENTORY_BORDER = "inventory.border";
    public static final String INVENTORY_HOVER_BORDER = "inventory.hoverBorder";
    public static final String INVENTORY_ITEM_BACKGROUND = "inventory.itemBackground";
    public static final String INVENTORY_ITEM_TEXT = "inventory.itemText";

    /** 拖动落点的半透明覆盖色，保留底层物品和格子的可见性。 */
    public static final String INVENTORY_DROP_VALID = "inventory.dropValid";

    public static final String INVENTORY_DROP_INVALID = "inventory.dropInvalid";

    public static final String HUD_BACKGROUND = "hud.background";
    public static final String HUD_TEXT = "hud.text";
    public static final String PANEL_BOTTOM = "panel.bottom";
    public static final String HEADER_BOTTOM = "header.bottom";
    public static final String BORDER = "border";
    public static final String EDGE_LIGHT = "edge.light";
    public static final String EDGE_SIDE = "edge.side";
    public static final String EDGE_DARK = "edge.dark";
    public static final String HEADER_RULE = "header.rule";
    public static final String HEADER_ACCENT = "header.accent";
    public static final String SHADOW = "shadow";

    /** 紧贴窗口下沿的一条平投影；没写即不画。 */
    public static final String SHADOW_DROP = "shadow.drop";

    public static final String CONTROL = "control";
    public static final String CONTROL_HOVER = "control.hover";
    public static final String CONTROL_CLOSE_HOVER = "control.closeHover";
    public static final String TOOLBAR = "toolbar";
    public static final String TOOLBAR_BORDER = "toolbar.border";
    public static final String MENU = "menu";
    public static final String MENU_BORDER = "menu.border";
    public static final String INPUT_ERROR = "input.error";
    public static final String BACKGROUND_OVERLAY = "background.overlay";

    private ThemeTokens() {}
}

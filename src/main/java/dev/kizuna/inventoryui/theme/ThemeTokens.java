package dev.kizuna.inventoryui.theme;

/** 框架绘制使用的稳定颜色令牌；主题按令牌覆盖 ARGB 值，不依赖具体组件实现。 */
public final class ThemeTokens {
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

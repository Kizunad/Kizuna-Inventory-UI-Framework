package dev.kizuna.inventoryui.theme;

import static dev.kizuna.inventoryui.theme.ThemeTokens.*;

import static java.util.Map.entry;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;

import java.util.Map;
import java.util.Objects;

/** 内置调色板；颜色字面量仅在主题定义中出现，绘制代码只读取语义令牌。 */
public final class BuiltinThemes {
    /** 未知扩展令牌的最终回退色，保持可见而不抛出渲染异常。 */
    private static final int UNKNOWN_TOKEN_COLOR = 0xFFFFFFFF;

    /** 原灰绿深色主题；也是局部主题缺项与未绑定主题组件的回退调色板。 */
    public static final FrameworkCatalog.Theme DARK =
            new FrameworkCatalog.Theme(
                    "kiui:dark",
                    "深色",
                    Map.ofEntries(
                            entry(BACKGROUND, 0x00000000),
                            entry(PANEL, 0xFF292C2D),
                            entry(HEADER, 0xFF484D48),
                            entry(TEXT, 0xFFE2EAE5),
                            entry(ACCENT, 0xFFDBBF7B),
                            entry(MUTED_TEXT, 0xFFA6AFA8),
                            entry(PROGRESS_TRACK, 0xFF2A2A2A),
                            entry(PROGRESS_FILL, 0xFF5588BB),
                            entry(PREVIEW_BACKGROUND, 0xFF17191B),
                            entry(INVENTORY_CELL_EVEN, 0xFF1E1E1E),
                            entry(INVENTORY_CELL_ODD, 0xFF232323),
                            entry(INVENTORY_ITEM_BACKGROUND, 0x00000000),
                            entry(INVENTORY_BORDER, 0xFF3A3A3A),
                            entry(INVENTORY_HOVER_BORDER, 0xFF888888),
                            entry(INVENTORY_ITEM_TEXT, 0xFFFFFFFF),
                            entry(INVENTORY_DROP_VALID, 0x3300CC44),
                            entry(INVENTORY_DROP_INVALID, 0x33CC2222),
                            entry(HUD_BACKGROUND, 0xCC111819),
                            entry(HUD_TEXT, 0xFFE1E8DC),
                            entry(PANEL_BOTTOM, 0xFF17191B),
                            entry(HEADER_BOTTOM, 0xFF292E2C),
                            entry(BORDER, 0xFF626762),
                            entry(EDGE_LIGHT, 0xFF9C9D8C),
                            entry(EDGE_SIDE, 0xFF494E49),
                            entry(EDGE_DARK, 0xFF090B0C),
                            entry(HEADER_RULE, 0xFF0D1110),
                            entry(HEADER_ACCENT, 0xFFB4A77C),
                            entry(SHADOW, 0x08000000),
                            entry(CONTROL, 0xFF303D37),
                            entry(CONTROL_HOVER, 0xFF485A51),
                            entry(CONTROL_CLOSE_HOVER, 0xFF824548),
                            entry(TOOLBAR, 0xFF171E20),
                            entry(TOOLBAR_BORDER, 0xFF4D5B55),
                            entry(MENU, 0xFF212A2A),
                            entry(MENU_BORDER, 0xFF819187),
                            entry(INPUT_ERROR, 0xFFEE8F8F),
                            entry(BACKGROUND_OVERLAY, 0x35080C0D)));

    public static final FrameworkCatalog.Theme LIGHT =
            new FrameworkCatalog.Theme(
                    "kiui:light",
                    "浅色",
                    Map.ofEntries(
                            entry(BACKGROUND, 0xEEE5E9EF),
                            entry(PANEL, 0xFFF5F7FA),
                            entry(HEADER, 0xFFBECDE0),
                            entry(TEXT, 0xFF192535),
                            entry(ACCENT, 0xFF325F96),
                            entry(MUTED_TEXT, 0xFF657085),
                            entry(PROGRESS_TRACK, 0xFFCAD3E1),
                            entry(PROGRESS_FILL, 0xFF4F83AC),
                            entry(PREVIEW_BACKGROUND, 0xFFE1E8F1),
                            entry(INVENTORY_CELL_EVEN, 0xFFDCE4EF),
                            entry(INVENTORY_CELL_ODD, 0xFFCDD8E6),
                            entry(INVENTORY_ITEM_BACKGROUND, 0x00000000),
                            entry(INVENTORY_BORDER, 0xFFA6AFA8),
                            entry(INVENTORY_HOVER_BORDER, 0xFF65736B),
                            entry(INVENTORY_ITEM_TEXT, 0xFF192535),
                            entry(INVENTORY_DROP_VALID, 0x3300CC44),
                            entry(INVENTORY_DROP_INVALID, 0x33CC2222),
                            entry(HUD_BACKGROUND, 0xCCF5F7FA),
                            entry(HUD_TEXT, 0xFF192535),
                            entry(PANEL_BOTTOM, 0xFFE1E8E2),
                            entry(HEADER_BOTTOM, 0xFFC3CFC5),
                            entry(BORDER, 0xFF819187),
                            entry(EDGE_LIGHT, 0xFFFFFFFF),
                            entry(EDGE_SIDE, 0xFFA6AFA8),
                            entry(EDGE_DARK, 0xFF65736B),
                            entry(HEADER_RULE, 0xFF819187),
                            entry(HEADER_ACCENT, 0xFF90763F),
                            entry(SHADOW, 0x08000000),
                            entry(CONTROL, 0xFFD4DED6),
                            entry(CONTROL_HOVER, 0xFFB3C8B9),
                            entry(CONTROL_CLOSE_HOVER, 0xFFE5B6B7),
                            entry(TOOLBAR, 0xFFE1E8E2),
                            entry(TOOLBAR_BORDER, 0xFF819187),
                            entry(MENU, 0xFFF5F7FA),
                            entry(MENU_BORDER, 0xFF819187),
                            entry(INPUT_ERROR, 0xFFAA3038),
                            entry(BACKGROUND_OVERLAY, 0x10FFFFFF)));

    /** 墨玉：墨色底、玉色强调与朱砂标题短线，默认工作台主题。 */
    public static final FrameworkCatalog.Theme INK_JADE =
            new FrameworkCatalog.Theme(
                    "kiui:ink_jade",
                    "墨玉",
                    Map.ofEntries(
                            entry(BACKGROUND, 0x00000000),
                            entry(PANEL, 0xFF1B2220),
                            entry(HEADER, 0xFF2C3A35),
                            entry(TEXT, 0xFFE6E2D3),
                            entry(ACCENT, 0xFF7FC8A9),
                            entry(MUTED_TEXT, 0xFF9AA69D),
                            entry(PROGRESS_TRACK, 0xFF17201D),
                            entry(PROGRESS_FILL, 0xFF5FB08F),
                            entry(PREVIEW_BACKGROUND, 0xFF0E1312),
                            entry(INVENTORY_CELL_EVEN, 0xFF151B19),
                            entry(INVENTORY_CELL_ODD, 0xFF191F1D),
                            entry(INVENTORY_ITEM_BACKGROUND, 0x00000000),
                            entry(INVENTORY_BORDER, 0xFF2E3A35),
                            entry(INVENTORY_HOVER_BORDER, 0xFF7FC8A9),
                            entry(INVENTORY_ITEM_TEXT, 0xFFFFFFFF),
                            entry(INVENTORY_DROP_VALID, 0x337FC8A9),
                            entry(INVENTORY_DROP_INVALID, 0x33C8553D),
                            entry(HUD_BACKGROUND, 0xCC0F1513),
                            entry(HUD_TEXT, 0xFFE6E2D3),
                            entry(PANEL_BOTTOM, 0xFF121716),
                            entry(HEADER_BOTTOM, 0xFF1E2825),
                            entry(BORDER, 0xFF4A5A54),
                            entry(EDGE_LIGHT, 0xFF8FA59B),
                            entry(EDGE_SIDE, 0xFF36423E),
                            entry(EDGE_DARK, 0xFF07090A),
                            entry(HEADER_RULE, 0xFF0B0F0E),
                            entry(HEADER_ACCENT, 0xFFC8553D),
                            entry(SHADOW, 0x08000000),
                            entry(CONTROL, 0xFF243430),
                            entry(CONTROL_HOVER, 0xFF345047),
                            entry(CONTROL_CLOSE_HOVER, 0xFF7A3A32),
                            entry(TOOLBAR, 0xFF121716),
                            entry(TOOLBAR_BORDER, 0xFF4A5A54),
                            entry(MENU, 0xFF1E2826),
                            entry(MENU_BORDER, 0xFF6E8A7E),
                            entry(INPUT_ERROR, 0xFFE58F7F),
                            entry(BACKGROUND_OVERLAY, 0x35070A09)));

    /** 宣纸：浅色纸底、墨字、朱砂强调与铜锈绿标题短线。 */
    public static final FrameworkCatalog.Theme XUAN_PAPER =
            new FrameworkCatalog.Theme(
                    "kiui:xuan_paper",
                    "宣纸",
                    Map.ofEntries(
                            entry(BACKGROUND, 0xEEEFE6D2),
                            entry(PANEL, 0xFFF3EBDA),
                            entry(HEADER, 0xFFD9C9A5),
                            entry(TEXT, 0xFF2A2622),
                            entry(ACCENT, 0xFFB6412E),
                            entry(MUTED_TEXT, 0xFF7A6F63),
                            entry(PROGRESS_TRACK, 0xFFE1D6BF),
                            entry(PROGRESS_FILL, 0xFFB6412E),
                            entry(PREVIEW_BACKGROUND, 0xFFEFE6D2),
                            entry(INVENTORY_CELL_EVEN, 0xFFEADFC8),
                            entry(INVENTORY_CELL_ODD, 0xFFE2D5BB),
                            entry(INVENTORY_ITEM_BACKGROUND, 0x00000000),
                            entry(INVENTORY_BORDER, 0xFFB8A98A),
                            entry(INVENTORY_HOVER_BORDER, 0xFFB6412E),
                            entry(INVENTORY_ITEM_TEXT, 0xFF2A2622),
                            entry(INVENTORY_DROP_VALID, 0x334F8C7A),
                            entry(INVENTORY_DROP_INVALID, 0x33B6412E),
                            entry(HUD_BACKGROUND, 0xCCF3EBDA),
                            entry(HUD_TEXT, 0xFF2A2622),
                            entry(PANEL_BOTTOM, 0xFFE9DFC9),
                            entry(HEADER_BOTTOM, 0xFFC9B88F),
                            entry(BORDER, 0xFF9C8D6E),
                            entry(EDGE_LIGHT, 0xFFFFFBF0),
                            entry(EDGE_SIDE, 0xFFB8A98A),
                            entry(EDGE_DARK, 0xFF6E6150),
                            entry(HEADER_RULE, 0xFF9C8D6E),
                            entry(HEADER_ACCENT, 0xFF4F8C7A),
                            entry(SHADOW, 0x08000000),
                            entry(CONTROL, 0xFFE3D7BE),
                            entry(CONTROL_HOVER, 0xFFD2C2A0),
                            entry(CONTROL_CLOSE_HOVER, 0xFFD99A8C),
                            entry(TOOLBAR, 0xFFE9DFC9),
                            entry(TOOLBAR_BORDER, 0xFF9C8D6E),
                            entry(MENU, 0xFFF3EBDA),
                            entry(MENU_BORDER, 0xFF9C8D6E),
                            entry(INPUT_ERROR, 0xFFA8322A),
                            entry(BACKGROUND_OVERLAY, 0x14FFFFFF)));

    /** 玄黄：玄色暖底、赭石强调与铜色标题短线。 */
    public static final FrameworkCatalog.Theme XUAN_HUANG =
            new FrameworkCatalog.Theme(
                    "kiui:xuan_huang",
                    "玄黄",
                    Map.ofEntries(
                            entry(BACKGROUND, 0x00000000),
                            entry(PANEL, 0xFF241F1A),
                            entry(HEADER, 0xFF3A2F24),
                            entry(TEXT, 0xFFEAE0CC),
                            entry(ACCENT, 0xFFD08A4A),
                            entry(MUTED_TEXT, 0xFFA79A86),
                            entry(PROGRESS_TRACK, 0xFF1E1915),
                            entry(PROGRESS_FILL, 0xFFD08A4A),
                            entry(PREVIEW_BACKGROUND, 0xFF120F0C),
                            entry(INVENTORY_CELL_EVEN, 0xFF1B1714),
                            entry(INVENTORY_CELL_ODD, 0xFF201B17),
                            entry(INVENTORY_ITEM_BACKGROUND, 0x00000000),
                            entry(INVENTORY_BORDER, 0xFF3A3028),
                            entry(INVENTORY_HOVER_BORDER, 0xFFD08A4A),
                            entry(INVENTORY_ITEM_TEXT, 0xFFFFFFFF),
                            entry(INVENTORY_DROP_VALID, 0x33D08A4A),
                            entry(INVENTORY_DROP_INVALID, 0x33C8553D),
                            entry(HUD_BACKGROUND, 0xCC17140F),
                            entry(HUD_TEXT, 0xFFEAE0CC),
                            entry(PANEL_BOTTOM, 0xFF17140F),
                            entry(HEADER_BOTTOM, 0xFF2A2219),
                            entry(BORDER, 0xFF5A4A3A),
                            entry(EDGE_LIGHT, 0xFFA9906F),
                            entry(EDGE_SIDE, 0xFF3E342B),
                            entry(EDGE_DARK, 0xFF0A0806),
                            entry(HEADER_RULE, 0xFF0E0B08),
                            entry(HEADER_ACCENT, 0xFFB08D57),
                            entry(SHADOW, 0x08000000),
                            entry(CONTROL, 0xFF3A2F24),
                            entry(CONTROL_HOVER, 0xFF55443A),
                            entry(CONTROL_CLOSE_HOVER, 0xFF7A3A32),
                            entry(TOOLBAR, 0xFF17140F),
                            entry(TOOLBAR_BORDER, 0xFF5A4A3A),
                            entry(MENU, 0xFF2A2219),
                            entry(MENU_BORDER, 0xFF8C7355),
                            entry(INPUT_ERROR, 0xFFE58F7F),
                            entry(BACKGROUND_OVERLAY, 0x35090705)));

    private BuiltinThemes() {}

    public static int color(FrameworkCatalog.Theme theme, String token) {
        // 局部主题只需覆盖关心的令牌，缺项逐级回退；透明色零值也是有效覆盖。
        Objects.requireNonNull(theme, "theme must not be null");
        Objects.requireNonNull(token, "token must not be null");
        return theme.colors().getOrDefault(token, defaultColor(token));
    }

    public static int defaultColor(String token) {
        // 独立于客户端运行时，未绑定主题提供器的组件也能使用完整默认调色板。
        Objects.requireNonNull(token, "token must not be null");
        return DARK.colors().getOrDefault(token, UNKNOWN_TOKEN_COLOR);
    }
}

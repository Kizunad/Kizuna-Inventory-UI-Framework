package dev.kizuna.inventoryui.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class BuiltinThemesTest {
    private static final int TRANSPARENT_COLOR = 0x00000000;
    private static final int CUSTOM_TEXT_COLOR = 0xFF765432;

    @Test
    void partialThemeKeepsOverridesIncludingTransparentColorsAndInheritsMissingTokens() {
        // 子模块可只覆盖少数令牌，透明值必须被视为有意覆盖而不是“缺省颜色”。
        var theme =
                new FrameworkCatalog.Theme(
                        "example:custom",
                        Map.of(
                                ThemeTokens.INVENTORY_CELL_EVEN, TRANSPARENT_COLOR,
                                ThemeTokens.INVENTORY_ITEM_TEXT, CUSTOM_TEXT_COLOR));

        assertEquals(
                TRANSPARENT_COLOR, BuiltinThemes.color(theme, ThemeTokens.INVENTORY_CELL_EVEN));
        assertEquals(
                CUSTOM_TEXT_COLOR, BuiltinThemes.color(theme, ThemeTokens.INVENTORY_ITEM_TEXT));
        assertEquals(
                BuiltinThemes.DARK.colors().get(ThemeTokens.INVENTORY_CELL_ODD).intValue(),
                BuiltinThemes.color(theme, ThemeTokens.INVENTORY_CELL_ODD),
                "局部覆盖主题仍须为未声明的网格颜色提供默认值");
    }

    @Test
    void everyBuiltinThemeDefinesEveryToken() throws IllegalAccessException {
        // 内置主题都是完整调色板，不靠回退补色；新增令牌或主题时漏填任何一项都在这里失败。
        Set<String> tokens = new HashSet<>();
        for (Field field : ThemeTokens.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                tokens.add((String) field.get(null));
            }
        }
        var themes =
                List.of(
                        BuiltinThemes.INK_JADE,
                        BuiltinThemes.XUAN_PAPER,
                        BuiltinThemes.XUAN_HUANG,
                        BuiltinThemes.DARK,
                        BuiltinThemes.LIGHT);
        for (var theme : themes) {
            assertEquals(tokens, theme.colors().keySet(), theme.id());
        }
    }
}

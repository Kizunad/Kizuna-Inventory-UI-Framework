package dev.kizuna.inventoryui.hud;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class HudLayoutTest {
    private HudLayout layout() {
        return new HudLayout(
                FrameworkCatalog.build(
                        List.of(
                                FrameworkCatalog.Module.of(
                                        "test:module",
                                        Set.of(),
                                        new FrameworkCatalog.Hud(
                                                "test:meter",
                                                "Meter",
                                                FrameworkCatalog.Anchor.BOTTOM_RIGHT,
                                                100,
                                                30)))));
    }

    @Test
    void resizedViewportPreservesAnchorAndClampsScaledHud() {
        var layout = layout();
        layout.place("test:meter", new HudLayout.Placement(true, -10, -8, 2));
        var large = layout.bounds("test:meter", 800, 600);
        assertEquals(590, large.x(), "right anchor keeps its offset after scaling");
        assertEquals(532, large.y());
        var small = layout.bounds("test:meter", 120, 40);
        assertEquals(0, small.x(), "HUD stays inside a smaller viewport");
        assertEquals(0, small.y());
        assertEquals(120, small.width());
        assertEquals(40, small.height());
    }

    @Test
    void invalidOrMissingHudDoesNotOverwriteExistingPreference() {
        var layout = layout();
        layout.place("test:meter", new HudLayout.Placement(false, 3, 4, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> layout.place("test:missing", new HudLayout.Placement(true, 0, 0, 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HudLayout.Placement(true, 0, 0, Float.NaN));
        assertFalse(layout.placement("test:meter").visible());
        layout.reset("test:meter");
        assertTrue(layout.placement("test:meter").visible());
    }
}

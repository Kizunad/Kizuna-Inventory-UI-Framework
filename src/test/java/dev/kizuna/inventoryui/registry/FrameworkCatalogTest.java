package dev.kizuna.inventoryui.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kizuna.inventoryui.window.UiWindowDefinition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

class FrameworkCatalogTest {
    @Test
    void buildsDefinitionsInDependencyOrderWithoutFixedSlotCount() {
        var window =
                new FrameworkCatalog.Window(
                        new UiWindowDefinition(
                                "example:storage",
                                "example:storage_template",
                                180,
                                120,
                                Set.of(UiWindowDefinition.Capability.WINDOW)));
        var storage =
                FrameworkCatalog.Module.of(
                        "example:storage_module",
                        Set.of("example:core"),
                        window,
                        new FrameworkCatalog.Feature(
                                "example:open_storage",
                                "Storage",
                                "Inventory",
                                "example:storage_icon",
                                "example:storage"),
                        new FrameworkCatalog.SlotBar(
                                "example:quickbar",
                                List.of(
                                        new FrameworkCatalog.Slot("example:slot_a", "item"),
                                        new FrameworkCatalog.Slot("example:slot_b", "item"))));
        var core =
                FrameworkCatalog.Module.of(
                        "example:core",
                        Set.of(),
                        new FrameworkCatalog.Hud(
                                "example:status",
                                "Status",
                                FrameworkCatalog.Anchor.BOTTOM_LEFT,
                                80,
                                20),
                        new FrameworkCatalog.Theme(
                                "example:default", Map.of("surface", 0xff202020)),
                        new FrameworkCatalog.Background(
                                "example:paper",
                                "example:textures/paper.png",
                                FrameworkCatalog.Fit.COVER));

        var catalog = FrameworkCatalog.build(List.of(storage, core));
        assertEquals(List.of("example:core", "example:storage_module"), catalog.moduleOrder());
        assertEquals(2, catalog.slotBars().get("example:quickbar").slots().size());
        assertTrue(catalog.features().containsKey("example:open_storage"));
        assertTrue(catalog.huds().containsKey("example:status"));
        assertThrows(UnsupportedOperationException.class, () -> catalog.features().clear());
    }

    @Test
    void rejectsMissingModulesDuplicateIdsAndBrokenWindowReferences() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FrameworkCatalog.build(
                                List.of(
                                        FrameworkCatalog.Module.of(
                                                "example:child", Set.of("example:missing")))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FrameworkCatalog.build(
                                List.of(
                                        FrameworkCatalog.Module.of(
                                                "example:core",
                                                Set.of(),
                                                new FrameworkCatalog.Hud(
                                                        "example:same",
                                                        "One",
                                                        FrameworkCatalog.Anchor.TOP_LEFT,
                                                        1,
                                                        1),
                                                new FrameworkCatalog.Theme(
                                                        "example:same", Map.of())))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FrameworkCatalog.build(
                                List.of(
                                        FrameworkCatalog.Module.of(
                                                "example:core",
                                                Set.of(),
                                                new FrameworkCatalog.Feature(
                                                        "example:open",
                                                        "Open",
                                                        "Inventory",
                                                        "example:icon",
                                                        "example:missing")))));
    }
}

package dev.kizuna.inventoryui.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowDefinition;
import dev.kizuna.inventoryui.window.UiWindowManager;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class WorkspaceControllerTest {
    @Test
    void directoryOpensAndFocusesSingletonAndObjectWindows() {
        var singleton =
                new FrameworkCatalog.Window(
                        new UiWindowDefinition(
                                "example:settings",
                                "example:settings_template",
                                100,
                                80,
                                Set.of(UiWindowDefinition.Capability.WINDOW)));
        var object =
                new FrameworkCatalog.Window(
                        new UiWindowDefinition(
                                "example:container",
                                "example:container_template",
                                100,
                                80,
                                Set.of(UiWindowDefinition.Capability.WINDOW)),
                        FrameworkCatalog.InstancePolicy.BY_OBJECT);
        var catalog =
                FrameworkCatalog.build(
                        List.of(
                                FrameworkCatalog.Module.of(
                                        "example:core",
                                        Set.of(),
                                        singleton,
                                        object,
                                        new FrameworkCatalog.Feature(
                                                "example:settings_entry",
                                                "Settings",
                                                "System",
                                                "example:settings_icon",
                                                "example:settings"),
                                        new FrameworkCatalog.Feature(
                                                "example:container_entry",
                                                "Container",
                                                "Inventory",
                                                "example:container_icon",
                                                "example:container"))));
        var workspace = new WorkspaceController(catalog, 600, 400);
        var bounds = new UiWindowManager.Rect(10, 10, 200, 120);
        assertEquals("example:container_entry", workspace.features("invent").get(0).id());
        var first = workspace.openFeature("example:settings_entry", null, bounds);
        assertSame(first, workspace.openFeature("example:settings_entry", "ignored", bounds));
        var containerA = workspace.openFeature("example:container_entry", "a", bounds);
        assertSame(containerA, workspace.openFeature("example:container_entry", "a", bounds));
        assertNotSame(containerA, workspace.openFeature("example:container_entry", "b", bounds));
        workspace.pinFeature("example:container_entry", true);
        workspace.disconnect();
        assertEquals(0, workspace.windowBar().size());
        assertEquals(List.of("example:container_entry"), workspace.pinnedFeatures());
    }
}

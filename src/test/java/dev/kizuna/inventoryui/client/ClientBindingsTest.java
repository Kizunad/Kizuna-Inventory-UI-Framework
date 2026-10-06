package dev.kizuna.inventoryui.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class ClientBindingsTest {
    private ClientBindings bindings() {
        return new ClientBindings(
                FrameworkCatalog.Module.of(
                        "example:module",
                        Set.of(),
                        new FrameworkCatalog.SlotBar(
                                "example:quick",
                                List.of(new FrameworkCatalog.Slot("example:one", "action")))));
    }

    @Test
    void requiresDeclaredRendererAndRejectsCrossModuleBinding() {
        var bindings = bindings();
        assertThrows(
                IllegalArgumentException.class,
                bindings::freeze,
                "declaring a bar without renderer must fail at startup");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        bindings.slotBar(
                                "other:quick",
                                slot -> new ClientBindings.SlotContent("x", () -> {})),
                "a module may not replace another module's binding");
    }

    @Test
    void registrationFreezesAndRejectsDuplicates() {
        var bindings = bindings();
        bindings.slotBar("example:quick", slot -> new ClientBindings.SlotContent("one", () -> {}));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        bindings.slotBar(
                                "example:quick",
                                slot -> new ClientBindings.SlotContent("two", () -> {})));
        bindings.freeze();
        assertThrows(
                IllegalStateException.class,
                () ->
                        bindings.slotBar(
                                "example:quick",
                                slot -> new ClientBindings.SlotContent("three", () -> {})));
    }
}

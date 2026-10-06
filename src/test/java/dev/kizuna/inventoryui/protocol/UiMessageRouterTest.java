package dev.kizuna.inventoryui.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

class UiMessageRouterTest {
    @Test
    void routesOnlyKnownTypedMessagesAndDropsOldConnectionTasks() {
        var catalog =
                FrameworkCatalog.build(
                        List.of(FrameworkCatalog.Module.of("example:inventory", Set.of())));
        List<Runnable> queued = new ArrayList<>();
        List<String> received = new ArrayList<>();
        List<UiMessageRouter.Outbound> sent = new ArrayList<>();
        var router =
                new UiMessageRouter(
                        catalog,
                        List.of(
                                new UiMessageRouter.Contract<>(
                                        "example:inventory",
                                        "example:snapshot",
                                        1,
                                        UiMessageRouter.Direction.SERVER_TO_CLIENT,
                                        String.class,
                                        received::add),
                                new UiMessageRouter.Contract<>(
                                        "example:inventory",
                                        "example:move",
                                        1,
                                        UiMessageRouter.Direction.CLIENT_TO_SERVER,
                                        String.class,
                                        null)),
                        queued::add,
                        sent::add);
        long first = router.connect();
        assertEquals(
                UiMessageRouter.Code.MODULE_MISSING,
                router.receive(first, "missing:module", "example:snapshot", 1, "state").code());
        assertEquals(
                UiMessageRouter.Code.INVALID_PAYLOAD,
                router.receive(first, "example:inventory", "example:snapshot", 1, 3).code());
        assertEquals(
                UiMessageRouter.Code.PROTOCOL_MISMATCH,
                router.receive(first, "example:inventory", "example:snapshot", 2, "state").code());
        assertTrue(router.receive(first, "example:inventory", "example:snapshot", 1, "old").ok());
        router.disconnect();
        long second = router.connect();
        queued.get(0).run();
        assertTrue(received.isEmpty());
        assertEquals(
                UiMessageRouter.Code.STALE_CONNECTION,
                router.send(first, "example:inventory", "example:move", 1, "move", "r1").code());
        assertTrue(router.receive(second, "example:inventory", "example:snapshot", 1, "new").ok());
        queued.get(1).run();
        assertEquals(List.of("new"), received);
        assertTrue(router.send(second, "example:inventory", "example:move", 1, "move", "r2").ok());
        assertEquals("r2", sent.get(0).requestId());
    }
}

package dev.kizuna.inventoryui.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class InventoryStateTest {
    @Test
    void oneAuthoritativeSnapshotFeedsMultipleViewsAndRejectsOldRevision() {
        var first = new InventorySnapshot("player:inventory", 1, List.of(), List.of());
        var second = new InventorySnapshot("player:inventory", 2, List.of(), List.of());
        var state = new InventoryState(first);
        List<Long> window = new ArrayList<>();
        List<Long> hud = new ArrayList<>();
        var windowSubscription = state.subscribe(snapshot -> window.add(snapshot.revision()));
        state.subscribe(snapshot -> hud.add(snapshot.revision()));
        state.replace(second);
        windowSubscription.close();
        state.replace(new InventorySnapshot("player:inventory", 3, List.of(), List.of()));
        assertEquals(List.of(2L), window);
        assertEquals(List.of(2L, 3L), hud);
        assertThrows(IllegalArgumentException.class, () -> state.replace(first));
        assertEquals(3, state.snapshot().revision());
    }
}

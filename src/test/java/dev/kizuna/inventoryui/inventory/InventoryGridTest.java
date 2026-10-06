package dev.kizuna.inventoryui.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

class InventoryGridTest {
    @Test
    void moveChecksRotationCollisionAndCountWithoutMutatingSnapshot() {
        var item = new InventorySnapshot.Item("a", "long_item", "Long", "example:long", 2, 1, 3);
        var blocker =
                new InventorySnapshot.Item("b", "blocker", "Blocker", "example:blocker", 1, 1, 1);
        var snapshot =
                new InventorySnapshot(
                        "inventory",
                        4,
                        List.of(new InventorySnapshot.Container("pack", "Pack", 2, 3)),
                        List.of(
                                new InventorySnapshot.Placement("pack", 0, 0, false, item),
                                new InventorySnapshot.Placement("pack", 1, 2, false, blocker)));
        assertTrue(InventoryGrid.canPlace(snapshot, "a", "pack", 0, 0, false));
        assertFalse(InventoryGrid.canPlace(snapshot, "a", "pack", 0, 2, true));
        assertFalse(InventoryGrid.canPlace(snapshot, "a", "pack", 1, 1, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> InventoryGrid.move(snapshot, "a", "pack", 0, 1, true, 2),
                "部分分堆不能与原位置剩余数量重叠");
        var intent = InventoryGrid.move(snapshot, "a", "pack", 1, 0, false, 2);
        assertEquals(4, intent.baseRevision());
        assertEquals(2, intent.count());
        assertEquals(2, snapshot.placements().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> InventoryGrid.move(snapshot, "a", "pack", 0, 1, true, 4));
    }
}

package dev.kizuna.inventoryui.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;

class InventorySnapshotTest {
    @Test
    void sparseContainerValidationDoesNotAllocateByRemoteGridDimensions() {
        // 超大空容器是稀疏快照的边界，校验不应尝试按维度创建内存矩阵。
        var snapshot =
                new InventorySnapshot(
                        "sparse",
                        0,
                        List.of(
                                new InventorySnapshot.Container(
                                        "large", "稀疏容器", Integer.MAX_VALUE, Integer.MAX_VALUE)),
                        List.of());
        assertEquals(1, snapshot.containers().size());
    }

    @Test
    void acceptsRotatedItemInsideDynamicContainerAndRejectsOverlap() {
        var container = new InventorySnapshot.Container("pack", "Pack", 2, 3);
        var longItem = new InventorySnapshot.Item("a", "item_a", "A", "example:a", 2, 1, 1);
        var smallItem = new InventorySnapshot.Item("b", "item_b", "B", "example:b", 1, 1, 2);
        var rotated = new InventorySnapshot.Placement("pack", 0, 1, true, longItem);
        var free = new InventorySnapshot.Placement("pack", 1, 2, false, smallItem);
        var snapshot =
                new InventorySnapshot("inventory", 7, List.of(container), List.of(rotated, free));
        assertEquals(7, snapshot.revision());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new InventorySnapshot(
                                "inventory",
                                8,
                                List.of(container),
                                List.of(
                                        rotated,
                                        new InventorySnapshot.Placement(
                                                "pack", 1, 1, false, smallItem))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new InventorySnapshot(
                                "inventory",
                                8,
                                List.of(container),
                                List.of(
                                        new InventorySnapshot.Placement(
                                                "pack", 0, 2, false, longItem))));
    }
}

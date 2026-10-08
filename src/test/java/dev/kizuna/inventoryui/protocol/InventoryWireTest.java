package dev.kizuna.inventoryui.protocol;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.inventoryui.inventory.InventoryDelta;
import dev.kizuna.inventoryui.inventory.InventoryGrid;
import dev.kizuna.inventoryui.inventory.InventorySnapshot;
import dev.kizuna.inventoryui.protocol.pb.InventoryProtocol;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class InventoryWireTest {
    private InventorySnapshot snapshot() {
        return new InventorySnapshot(
                "session-1",
                12,
                List.of(new InventorySnapshot.Container("bag", "背包", 4, 6)),
                List.of(
                        new InventorySnapshot.Placement(
                                "bag",
                                1,
                                2,
                                true,
                                new InventorySnapshot.Item(
                                        "stack-1",
                                        "minecraft:apple",
                                        "苹果",
                                        "minecraft:textures/item/apple.png",
                                        2,
                                        1,
                                        5))));
    }

    @Test
    void snapshotRoundTripRetainsIdentitiesCoordinatesRotationAndUnicode() throws Exception {
        // 公共显示模型无需切换成生成类，编解码仍保持每个字段的身份和单位。
        var original = snapshot();
        var bytes = InventoryWire.SNAPSHOT.encode(original);
        var peer = InventoryProtocol.InventorySnapshot.parseFrom(bytes);
        assertEquals("苹果", peer.getPlacements(0).getItem().getName());
        assertEquals(12, peer.getRevision());
        assertEquals(original, InventoryWire.SNAPSHOT.decode(peer.toByteString()));
    }

    @Test
    void deltaDistinguishesUnchangedDirectoryFromExplicitlyEmptyDirectory() throws Exception {
        // 沿用和清空有不同业务含义，二进制往返后不能都变成 repeated 的空列表。
        var preserve = new InventoryDelta("session-1", 12, 13, null, Set.of(), List.of());
        var clear =
                new InventoryDelta("session-1", 12, 13, List.of(), Set.of("stack-1"), List.of());
        var preserveBytes = InventoryWire.DELTA.encode(preserve);
        var clearBytes = InventoryWire.DELTA.encode(clear);
        assertFalse(InventoryProtocol.InventoryDelta.parseFrom(preserveBytes).hasContainers());
        assertTrue(InventoryProtocol.InventoryDelta.parseFrom(clearBytes).hasContainers());
        assertNull(InventoryWire.DELTA.decode(preserveBytes).containers());
        assertEquals(List.of(), InventoryWire.DELTA.decode(clearBytes).containers());
        assertEquals(
                snapshot().placements(),
                InventoryWire.DELTA.decode(preserveBytes).apply(snapshot()).placements());
        assertTrue(InventoryWire.DELTA.decode(clearBytes).apply(snapshot()).containers().isEmpty());
    }

    @Test
    void validatesRemoteSnapshotAndMoveInsteadOfTrustingProtoDefaults() throws Exception {
        // 生成类允许省略字段和构造重叠占格，转换到领域模型时必须拒绝。
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        InventoryWire.SNAPSHOT.decode(
                                InventoryProtocol.InventorySnapshot.getDefaultInstance()
                                        .toByteString()));
        var valid =
                InventoryProtocol.InventorySnapshot.parseFrom(
                        InventoryWire.SNAPSHOT.encode(snapshot()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        InventoryWire.SNAPSHOT.decode(
                                valid.toBuilder()
                                        .addPlacements(valid.getPlacements(0))
                                        .build()
                                        .toByteString()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        InventoryWire.SNAPSHOT.decode(
                                valid.toBuilder()
                                        .setPlacements(
                                                0, valid.getPlacements(0).toBuilder().clearItem())
                                        .build()
                                        .toByteString()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        InventoryWire.MOVE.decode(
                                InventoryProtocol.MoveIntent.getDefaultInstance().toByteString()));
        var move =
                new InventoryGrid.MoveIntent(
                        "session-1", 12, "stack-1", "bag", "warehouse", 0, 2, false, 3);
        assertEquals(move, InventoryWire.MOVE.decode(InventoryWire.MOVE.encode(move)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        InventoryWire.MOVE.encode(
                                new InventoryGrid.MoveIntent(
                                        "session-1",
                                        12,
                                        "stack-1",
                                        "bag",
                                        "warehouse",
                                        -1,
                                        2,
                                        false,
                                        3)));
    }

    @Test
    void rejectsDuplicateDeletedInstancesAndStaleDeltaBase() {
        // 网络集合不静默去重；应用增量仍检查当前权威版本，避免跳过缺失事务。
        var duplicate =
                InventoryProtocol.InventoryDelta.newBuilder()
                        .setStreamId("session-1")
                        .setBaseRevision(12)
                        .setRevision(13)
                        .addRemovedInstances("stack-1")
                        .addRemovedInstances("stack-1")
                        .build();
        assertThrows(
                IllegalArgumentException.class,
                () -> InventoryWire.DELTA.decode(duplicate.toByteString()));
        var stale = new InventoryDelta("session-1", 11, 13, null, Set.of(), List.of());
        var decoded = InventoryWire.DELTA.decode(InventoryWire.DELTA.encode(stale));
        assertThrows(IllegalArgumentException.class, () -> decoded.apply(snapshot()));
    }
}

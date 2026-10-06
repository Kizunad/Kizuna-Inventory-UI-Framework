package dev.kizuna.inventoryui.inventory;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

class InventoryDragSessionTest {
    private static InventorySnapshot initial() {
        return new InventorySnapshot(
                "session",
                0,
                List.of(
                        new InventorySnapshot.Container("a", "来源", 2, 3),
                        new InventorySnapshot.Container("b", "目标", 3, 2)),
                List.of(
                        new InventorySnapshot.Placement(
                                "a",
                                0,
                                0,
                                false,
                                new InventorySnapshot.Item(
                                        "item", "test:stack", "堆叠", "test:icon", 2, 1, 8))));
    }

    @Test
    void crossContainerSplitUsesOneIntentAndDoesNotOptimisticallyMutate() {
        var state = new InventoryState(initial());
        var session = new InventoryDragSession();
        var intents = new ArrayList<InventoryGrid.MoveIntent>();
        session.begin(this, state, "item", 4, 0, 1);
        assertFalse(session.canMove(state, "a", 0, 0), "分堆不释放来源占格");
        session.rotate();
        assertTrue(session.move(state, "b", 0, 0, intents::add));
        assertEquals(4, intents.get(0).count());
        assertEquals("b", intents.get(0).destinationContainerId());
        assertTrue(intents.get(0).rotated());
        assertEquals(initial(), state.snapshot(), "等待服务器期间保留完整来源状态");
        assertFalse(session.move(state, "b", 0, 0, intents::add));
        assertEquals(1, intents.size());
        session.begin(this, state, "item", 8, 0, 0);
        assertTrue(session.move(state, "a", 0, 0, intents::add));
        assertEquals(1, intents.size(), "单击物品或放回原位不能产生服务器移动请求");
    }

    @Test
    void updatesIndependentSourcesAndCancellationInvalidateOldInput() {
        var state = new InventoryState(initial());
        var session = new InventoryDragSession();
        session.begin(this, state, "item", 8, 0, 0);
        assertFalse(session.canMove(new InventoryState(initial()), "b", 0, 0), "同 ID 不代表同一权威状态源");
        session.cancel(new Object());
        assertNotNull(session.current(), "无关窗口失焦不能取消来源拖动");
        var old = state.snapshot();
        state.replace(new InventorySnapshot(old.streamId(), 1, old.containers(), old.placements()));
        assertNull(session.current(), "快照更新后释放旧输入不得提交");
        session.begin(this, state, "item", 8, 0, 0);
        session.cancel(this);
        assertNull(session.current());
    }

    @Test
    void deltasApplyAtomicallyAndRejectMissingRevisionOrOverlappingResult() {
        var initial = initial();
        var item = initial.placements().get(0).item();
        var delta =
                new InventoryDelta(
                        "session",
                        0,
                        1,
                        null,
                        Set.of(),
                        List.of(new InventorySnapshot.Placement("b", 0, 0, false, item)));
        assertEquals("b", delta.apply(initial).placements().get(0).containerId());
        assertThrows(
                IllegalArgumentException.class,
                () -> delta.apply(delta.apply(initial)),
                "重复增量必须触发重同步路径");
        var bad = new InventoryDelta("session", 0, 1, List.of(), Set.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> bad.apply(initial));
        assertEquals("a", initial.placements().get(0).containerId(), "无效增量不能留下半更新");
        var state = new InventoryState(initial);
        var sync = new java.util.concurrent.atomic.AtomicInteger();
        assertTrue(state.applyDelta(delta, sync::incrementAndGet));
        assertFalse(state.applyDelta(delta, sync::incrementAndGet));
        assertEquals(1, sync.get(), "缺失或重复版本不能继续应用，必须转完整同步");
    }

    @Test
    void externalSlotPreservesSourceIdentityAndRevokesOnAuthoritativeChange() {
        var drag = new InventoryDragSession();
        var valid = new java.util.concurrent.atomic.AtomicBoolean(true);
        var item = initial().placements().get(0).item();
        drag.beginSlot(this, "loadout", 3, "module:hand", item, item.count(), valid::get);
        assertEquals(new InventoryDragSession.SlotOrigin("module:hand"), drag.current().origin());
        assertFalse(drag.belongsTo(new InventoryState(initial())), "独立装备状态不得冒充普通网格移动");
        assertTrue(InventoryGrid.canPlaceExternal(initial(), item, "b", 0, 0, true));
        valid.set(false);
        var consumed = new ArrayList<InventoryDragSession.Payload>();
        drag.consume(consumed::add);
        assertTrue(consumed.isEmpty(), "装备状态失效后不能投递旧来源");
    }
}

package dev.kizuna.inventoryui.status;

import static dev.kizuna.inventoryui.status.StatusTestData.effect;
import static dev.kizuna.inventoryui.status.StatusTestData.file;
import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.inventoryui.status.StatusArrangement.Merged;
import dev.kizuna.inventoryui.status.StatusArrangement.Overflow;
import dev.kizuna.inventoryui.status.StatusArrangement.Single;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class StatusArrangementTest {
    private final StatusGroupTable table = StatusGroupTable.parse(Map.of(
            "example:a", List.of(file(10, 30, List.of("example:a1", "example:a2"))),
            "example:b", List.of(file(20, 10, List.of("example:b1", "example:b2", "example:b3"))),
            "example:c", List.of(file(30, 20, List.of("example:c1", "example:c2"))),
            "example:single", List.of(file(0, 0, List.of("example:single"))))).table();

    @Test
    void exactlyAtCapacityKeepsEveryEffectSingleAndPreservesMemberOrder() {
        var effects = List.of(effect("a2"), effect("b2"), effect("a1"), effect("b1"), effect("b3"));
        var slots = StatusArrangement.arrange(effects, 5, table);
        assertEquals(List.of(
                new Single(effect("b2")), new Single(effect("b1")), new Single(effect("b3")),
                new Single(effect("a2")), new Single(effect("a1"))), slots);
    }

    @Test
    void oneOverCapacityMergesFirstPriorityRatherThanLargestGroup() {
        var effects = List.of(effect("a1"), effect("a2"), effect("b1"), effect("b2"), effect("b3"));
        var slots = StatusArrangement.arrange(effects, 4, table);
        assertEquals(List.of(
                new Single(effect("b1")), new Single(effect("b2")), new Single(effect("b3")),
                merged("a", "a1", "a2")), slots);
    }

    @Test
    void mergesNextGroupWhenNeededAndStopsImmediatelyWhenItFits() {
        var effects = List.of(effect("a1"), effect("b1"), effect("c2"), effect("a2"),
                effect("b2"), effect("c1"), effect("b3"));
        var slots = StatusArrangement.arrange(effects, 4, table);
        assertEquals(List.of(merged("b", "b1", "b2", "b3"),
                new Single(effect("c2")), new Single(effect("c1")), merged("a", "a1", "a2")), slots);
    }

    @Test
    void singletonStaysSingleEvenWithEarliestMergePriority() {
        var effects = List.of(effect("a1"), effect("single"), effect("a2"),
                effect("b1"), effect("b2"), effect("b3"));
        var slots = StatusArrangement.arrange(effects, 5, table);
        assertEquals(List.of(new Single(effect("single")), new Single(effect("b1")),
                new Single(effect("b2")), new Single(effect("b3")), merged("a", "a1", "a2")), slots);
    }

    @Test
    void overflowKeepsAllRemainingSlotsAfterEveryPossibleMerge() {
        var harmful = new StatusEffectRef("example:unknown_harmful", StatusCategory.HARMFUL);
        var beneficial = new StatusEffectRef("example:unknown_beneficial", StatusCategory.BENEFICIAL);
        var neutral = effect("unknown_neutral");
        var effects = List.of(beneficial, effect("a1"), harmful, effect("b1"), neutral,
                effect("a2"), effect("b2"), effect("b3"));
        var slots = StatusArrangement.arrange(effects, 2, table);
        var overflow = new Overflow(List.of(merged("a", "a1", "a2"),
                new Single(harmful), new Single(neutral), new Single(beneficial)));
        assertEquals(List.of(merged("b", "b1", "b2", "b3"), overflow), slots);
    }

    @Test
    void nonPositiveCapacityReturnsNoSlots() {
        var effects = List.of(effect("a1"), effect("a2"));
        assertEquals(List.of(), StatusArrangement.arrange(effects, 0, table));
        assertEquals(List.of(), StatusArrangement.arrange(effects, -1, table));
        assertEquals(List.of(), StatusArrangement.arrange(List.of(), 1, table));
    }

    @Test
    void capacityOneCanHoldSingleMergedOrOverflow() {
        assertEquals(List.of(new Single(effect("a1"))),
                StatusArrangement.arrange(List.of(effect("a1")), 1, table));
        assertEquals(List.of(merged("a", "a1", "a2")),
                StatusArrangement.arrange(List.of(effect("a1"), effect("a2")), 1, table));
        assertEquals(List.of(new Overflow(List.of(merged("b", "b1", "b2"), merged("a", "a1", "a2")))),
                StatusArrangement.arrange(List.of(effect("a1"), effect("b1"), effect("a2"), effect("b2")),
                        1, table));
    }

    @Test
    void equalPrioritiesUseGroupIdForMergingAndDisplay() {
        var tied = StatusGroupTable.parse(Map.of(
                "example:b", List.of(file(10, 10, List.of("example:b1", "example:b2"))),
                "example:a", List.of(file(10, 10, List.of("example:a1", "example:a2"))))).table();
        var effects = List.of(effect("b2"), effect("a2"), effect("b1"), effect("a1"));
        var mergedA = new Merged(tied.groupOf(effect("a1")), List.of(effect("a2"), effect("a1")));
        assertEquals(List.of(mergedA, new Single(effect("b2")), new Single(effect("b1"))),
                StatusArrangement.arrange(effects, 3, tied));
    }

    @Test
    void duplicateIdsAreRejectedEvenWhenCapacityIsZeroOrCategoriesDiffer() {
        var duplicate = new StatusEffectRef("example:a1", StatusCategory.HARMFUL);
        var effects = List.of(effect("a1"), duplicate);
        assertThrows(IllegalArgumentException.class, () -> StatusArrangement.arrange(effects, 3, table));
        assertThrows(IllegalArgumentException.class, () -> StatusArrangement.arrange(effects, 0, table));
    }

    @Test
    void resultsAndNestedListsAreImmutable() {
        var slots = StatusArrangement.arrange(List.of(effect("a1"), effect("a2")), 1, table);
        var merged = assertInstanceOf(Merged.class, slots.getFirst());
        assertThrows(UnsupportedOperationException.class, slots::clear);
        assertThrows(UnsupportedOperationException.class, () -> merged.effects().clear());
        var overflowSlots = StatusArrangement.arrange(List.of(effect("a1"), effect("b1")), 1, table);
        var overflow = assertInstanceOf(Overflow.class, overflowSlots.getFirst());
        assertThrows(UnsupportedOperationException.class, () -> overflow.slots().clear());
    }

    private Merged merged(String groupPath, String... effectPaths) {
        // 预期成员沿用测试里明确给定的顺序，不通过被测排列方法生成。
        var members = java.util.Arrays.stream(effectPaths).map(StatusTestData::effect).toList();
        var group = table.groupOf(members.getFirst());
        assertEquals("example:" + groupPath, group.id());
        return new Merged(group, members);
    }
}

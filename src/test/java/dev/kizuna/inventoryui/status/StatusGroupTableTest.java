package dev.kizuna.inventoryui.status;

import static dev.kizuna.inventoryui.status.StatusTestData.effect;
import static dev.kizuna.inventoryui.status.StatusTestData.file;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

class StatusGroupTableTest {
    @Test
    void undeclaredEffectsUseAllThreeBuiltInFallbacks() {
        var table = StatusGroupTable.empty();
        assertSame(StatusGroup.HARMFUL,
                table.groupOf(new StatusEffectRef("example:bad", StatusCategory.HARMFUL)));
        assertSame(StatusGroup.NEUTRAL, table.groupOf(effect("other")));
        assertSame(StatusGroup.BENEFICIAL,
                table.groupOf(new StatusEffectRef("example:good", StatusCategory.BENEFICIAL)));
        assertEquals(new StatusGroup("kizuna_inventory_ui:harmful", "status_group.kizuna_inventory_ui.harmful",
                "kizuna_inventory_ui:status_group/harmful", 70, 40), StatusGroup.HARMFUL);
        assertEquals(new StatusGroup("kizuna_inventory_ui:neutral", "status_group.kizuna_inventory_ui.neutral",
                "kizuna_inventory_ui:status_group/neutral", 65, 85), StatusGroup.NEUTRAL);
        assertEquals(new StatusGroup("kizuna_inventory_ui:beneficial", "status_group.kizuna_inventory_ui.beneficial",
                "kizuna_inventory_ui:status_group/beneficial", 60, 90), StatusGroup.BENEFICIAL);
        assertTrue(StatusGroupTable.parse(Map.of()).problems().isEmpty());
    }

    @Test
    void allPacksAppendMembersEvenWhenDefinitionIsInAnotherFile() {
        var result = StatusGroupTable.parse(Map.of("example:nested/group", List.of(
                "{\"effects\":[\"example:one\"]}",
                file(20, 30, List.of()),
                "{\"effects\":[\"example:two\",\"example:one\"]}")));
        assertTrue(result.problems().isEmpty());
        var group = result.table().groupOf(effect("one"));
        assertEquals("example:nested/group", group.id());
        assertEquals(group, result.table().groupOf(effect("two")));
        assertEquals(20, group.mergeOrder());
        assertEquals(30, group.displayOrder());
    }

    @Test
    void membersWithoutAnyDefinitionFallBackByTheirOwnCategories() {
        var result = StatusGroupTable.parse(Map.of("example:missing", List.of(
                "{\"effects\":[\"example:one\",\"example:two\",\"example:three\"]}")));
        assertSame(StatusGroup.NEUTRAL, result.table().groupOf(effect("one")));
        assertSame(StatusGroup.HARMFUL,
                result.table().groupOf(new StatusEffectRef("example:two", StatusCategory.HARMFUL)));
        assertSame(StatusGroup.BENEFICIAL,
                result.table().groupOf(new StatusEffectRef("example:three", StatusCategory.BENEFICIAL)));
        assertProblem(result, "example:missing", "没有定义");
    }

    @Test
    void duplicateDefinitionsKeepBottomDefinitionAndBothSetsOfMembers() {
        var result = StatusGroupTable.parse(Map.of("example:group", List.of(
                file(20, 30, List.of("example:one")),
                file(5, 7, List.of("example:two")).replace("status_group/group", "status_group/other"))));
        var group = result.table().groupOf(effect("two"));
        assertEquals(20, group.mergeOrder());
        assertEquals(30, group.displayOrder());
        assertEquals("example:status_group/group", group.icon());
        assertEquals(group, result.table().groupOf(effect("one")));
        assertProblem(result, "example:group", "多份定义");
    }

    @Test
    void conflictingEffectUsesLexicographicallySmallestGroupRegardlessOfInputOrder() {
        var sources = new LinkedHashMap<String, List<String>>();
        sources.put("example:z", List.of(file(1, 1, List.of("example:shared"))));
        sources.put("example:a", List.of(file(90, 90, List.of("example:shared"))));
        var result = StatusGroupTable.parse(sources);
        assertEquals("example:a", result.table().groupOf(effect("shared")).id());
        assertProblem(result, "example:z", "example:a");
        assertTrue(result.problems().getFirst().reason().contains("example:shared"));
    }

    @Test
    void undefinedSmallerGroupDoesNotClaimEffectsFromAValidGroup() {
        var result = StatusGroupTable.parse(Map.of(
                "example:a", List.of("{\"effects\":[\"example:shared\"]}"),
                "example:z", List.of(file(1, 1, List.of("example:shared")))));
        assertEquals("example:z", result.table().groupOf(effect("shared")).id());
        assertProblem(result, "example:a", "没有定义");
    }

    @ParameterizedTest
    @MethodSource("invalidFiles")
    void invalidFileIsSkippedAsAWholeWithoutDiscardingOtherFiles(String invalid) {
        var result = StatusGroupTable.parse(Map.of("example:group", List.of(
                invalid, file(10, 20, List.of("example:valid")))));
        assertEquals("example:group", result.table().groupOf(effect("valid")).id());
        assertSame(StatusGroup.NEUTRAL, result.table().groupOf(effect("leaked")));
        assertEquals(1, result.problems().size());
        var problem = result.problems().getFirst();
        assertEquals("example:group", problem.groupId());
        assertTrue(problem.reason().contains("第 1 份文件"));
    }

    static Stream<String> invalidFiles() {
        return Stream.of(
                "{", "null", "[]", "true", "{}", "{\"effects\":null}", "{\"effects\":42}",
                "{\"effects\":[\"example:leaked\",true]}",
                "{\"effects\":[\"example:leaked\",\"no_namespace\"]}",
                "{\"effects\":[\"example:leaked\"],\"group\":null}",
                "{\"effects\":[\"example:leaked\"],\"group\":[]}",
                "{\"effects\":[\"example:leaked\"],\"group\":{}}",
                file(1, 1, List.of("example:leaked")).replace("\"name\":\"status_group.example.group\"", "\"name\":1"),
                file(1, 1, List.of("example:leaked")).replace("status_group.example.group", " "),
                file(1, 1, List.of("example:leaked")).replace("example:status_group/group", "bad_icon"),
                file(1, 1, List.of("example:leaked")).replace("\"merge_order\":1", "\"merge_order\":\"1\""),
                file(1, 1, List.of("example:leaked")).replace("\"merge_order\":1", "\"merge_order\":1.5"),
                file(1, 1, List.of("example:leaked")).replace("\"merge_order\":1", "\"merge_order\":2147483648"),
                file(1, 1, List.of("example:leaked")).replace("\"display_order\":1", "\"display_order\":false"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing_namespace", "Upper:case", ":empty", "example:", "example:has space"})
    void invalidGroupIdIsReportedWithoutAffectingOtherGroups(String groupId) {
        var result = StatusGroupTable.parse(Map.of(
                groupId, List.of(file(1, 1, List.of("example:leaked"))),
                "example:valid", List.of(file(1, 1, List.of("example:valid")))));
        assertEquals("example:valid", result.table().groupOf(effect("valid")).id());
        assertSame(StatusGroup.NEUTRAL, result.table().groupOf(effect("leaked")));
        assertProblem(result, groupId, "命名空间:路径");
    }

    @Test
    void validDefinitionSurvivesAnInvalidHigherPackWithoutLeakingItsMembers() {
        var invalid = file(1, 1, List.of("example:leaked")).replace("example:status_group/group", "bad");
        var result = StatusGroupTable.parse(Map.of("example:group", List.of(
                file(10, 20, List.of("example:valid")), invalid)));
        assertEquals(10, result.table().groupOf(effect("valid")).mergeOrder());
        assertSame(StatusGroup.NEUTRAL, result.table().groupOf(effect("leaked")));
        assertEquals(1, result.problems().size());
    }

    @Test
    void publicDataRejectsInvalidIdsAndMissingMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> new StatusEffectRef("no_namespace", StatusCategory.NEUTRAL));
        assertThrows(NullPointerException.class, () -> new StatusEffectRef("example:effect", null));
        assertThrows(IllegalArgumentException.class,
                () -> new StatusGroup("example:group", "name", "bad_icon", 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new StatusGroup("example:group", " ", "example:icon", 0, 0));
    }

    private static void assertProblem(StatusGroupTable.ParseResult result, String groupId, String reasonPart) {
        // 同时核对资源身份和原因，避免只有泛化警告而无法定位坏声明。
        assertEquals(1, result.problems().size());
        var problem = result.problems().getFirst();
        assertEquals(groupId, problem.groupId());
        assertTrue(problem.reason().contains(reasonPart), problem.reason());
    }
}

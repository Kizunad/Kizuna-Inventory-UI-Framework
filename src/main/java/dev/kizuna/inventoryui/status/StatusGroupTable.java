package dev.kizuna.inventoryui.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** 不可变的效果分类表；解析输入按每个资源位置从底层包到顶层包排列。 */
public final class StatusGroupTable {
    private static final StatusGroupTable EMPTY = new StatusGroupTable(Map.of());
    private final Map<String, StatusGroup> groupsByEffect;

    private StatusGroupTable(Map<String, StatusGroup> groupsByEffect) {
        this.groupsByEffect = Map.copyOf(groupsByEffect);
    }

    public static StatusGroupTable empty() {
        return EMPTY;
    }

    public StatusGroup groupOf(StatusEffectRef effect) {
        // 无定义和未声明的效果都按自身属性兜底，不创建半个类。
        Objects.requireNonNull(effect, "effect must not be null");
        var declared = groupsByEffect.get(effect.id());
        if (declared != null) {
            return declared;
        }
        return StatusGroup.fallback(effect.category());
    }

    public static ParseResult parse(Map<String, List<String>> sources) {
        // 类先按 ID 排序，冲突归属就不会受到资源管理器遍历顺序的影响。
        var problems = new ArrayList<Problem>();
        var groupsByEffect = new LinkedHashMap<String, StatusGroup>();
        for (var entry : new TreeMap<>(sources).entrySet()) {
            var stack = readStack(entry.getKey(), entry.getValue(), problems);
            addMembers(entry.getKey(), stack, groupsByEffect, problems);
        }
        return new ParseResult(new StatusGroupTable(groupsByEffect), problems);
    }

    private static List<StatusGroupParser.File> readStack(
            String groupId, List<String> files, List<Problem> problems) {
        // 每份文件先完整校验再参加合并，后半段出错不能泄漏前半段的成员。
        var parsed = new ArrayList<StatusGroupParser.File>();
        for (int index = 0; index < files.size(); index++) {
            try {
                parsed.add(StatusGroupParser.parse(groupId, files.get(index)));
            } catch (IllegalArgumentException failure) {
                problems.add(new Problem(groupId, "第 " + (index + 1) + " 份文件：" + failure.getMessage()));
            }
        }
        return parsed;
    }

    private static void addMembers(
            String groupId,
            List<StatusGroupParser.File> files,
            Map<String, StatusGroup> groupsByEffect,
            List<Problem> problems) {
        // 成员可由整摞文件累加；定义只认从底层开始遇到的第一份有效定义。
        var members = new LinkedHashSet<String>();
        StatusGroup definition = null;
        for (var file : files) {
            members.addAll(file.effects());
            var group = file.group();
            if (group != null) {
                if (definition == null) {
                    definition = group;
                } else {
                    problems.add(new Problem(groupId, "类有多份定义，使用最底层的有效定义"));
                }
            }
        }
        if (definition == null) {
            if (!members.isEmpty()) {
                problems.add(new Problem(groupId, "类有成员但没有定义，成员按自身属性归入兜底类"));
            }
            return;
        }
        assignMembers(definition, members, groupsByEffect, problems);
    }

    private static void assignMembers(
            StatusGroup group,
            Iterable<String> members,
            Map<String, StatusGroup> groupsByEffect,
            List<Problem> problems) {
        // 字典序较小的有效类已经先登记；较大的类只报告冲突，不覆盖已有归属。
        for (String effectId : members) {
            var previous = groupsByEffect.putIfAbsent(effectId, group);
            if (previous != null) {
                problems.add(new Problem(group.id(), "效果 " + effectId + " 被多个类声明，归入 " + previous.id()));
            }
        }
    }

    /** 单个问题保留资源位置身份和可直接写日志的原因。 */
    public record Problem(String groupId, String reason) {}

    /** 坏文件不使整表失效；调用方分别消费有效表和逐条问题。 */
    public record ParseResult(StatusGroupTable table, List<Problem> problems) {
        public ParseResult {
            // 防止调用方事后修改已完成的诊断列表。
            problems = List.copyOf(problems);
        }
    }
}

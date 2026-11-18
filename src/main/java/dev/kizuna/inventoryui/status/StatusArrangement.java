package dev.kizuna.inventoryui.status;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** 只计算格子内容与顺序，容量由调用方根据实际可用区域提供。 */
public final class StatusArrangement {
    private static final int MIN_MERGE_MEMBERS = 2;
    private static final Comparator<Bucket> DISPLAY_ORDER =
            Comparator.comparingInt((Bucket bucket) -> bucket.group().displayOrder())
                    .thenComparing(bucket -> bucket.group().id());
    private static final Comparator<Bucket> MERGE_ORDER =
            Comparator.comparingInt((Bucket bucket) -> bucket.group().mergeOrder())
                    .thenComparing(bucket -> bucket.group().id());

    private StatusArrangement() {}

    public static List<Slot> arrange(List<StatusEffectRef> effects, int capacity, StatusGroupTable table) {
        // 重复效果是调用错误，即使容量为零也要报告，不能隐藏无效输入。
        validateUnique(effects);
        if (capacity <= 0) {
            return List.of();
        }
        var buckets = buckets(effects, table);
        var mergedIds = mergeUntilFit(buckets, effects.size(), capacity);
        var slots = slots(buckets, mergedIds);
        if (slots.size() <= capacity) {
            return slots;
        }
        // 最后一格收纳剩余格子，保留其中单项和已合并类的结构及显示顺序。
        int visibleCount = capacity - 1;
        var visible = new ArrayList<Slot>(slots.subList(0, visibleCount));
        visible.add(new Overflow(slots.subList(visibleCount, slots.size())));
        return List.copyOf(visible);
    }

    private static void validateUnique(List<StatusEffectRef> effects) {
        // 身份去重只用于校验，不能静默丢掉调用方传入的效果。
        var ids = new HashSet<String>();
        for (var effect : effects) {
            if (!ids.add(effect.id())) {
                throw new IllegalArgumentException("duplicate status effect: " + effect.id());
            }
        }
    }

    private static List<Bucket> buckets(List<StatusEffectRef> effects, StatusGroupTable table) {
        // 类内使用输入顺序；只对整类排序，不重新排列同类效果。
        var byGroup = new LinkedHashMap<String, Bucket>();
        for (var effect : effects) {
            var group = table.groupOf(effect);
            byGroup.computeIfAbsent(group.id(), id -> new Bucket(group, new ArrayList<>()))
                    .effects().add(effect);
        }
        return byGroup.values().stream().sorted(DISPLAY_ORDER).toList();
    }

    private static Set<String> mergeUntilFit(List<Bucket> buckets, int count, int capacity) {
        // 达到容量仍保持逐项展示，只有超出时才按独立的合并优先级逐类收缩。
        var mergedIds = new HashSet<String>();
        for (var bucket : buckets.stream().sorted(MERGE_ORDER).toList()) {
            if (count <= capacity) {
                break;
            }
            int members = bucket.effects().size();
            if (members >= MIN_MERGE_MEMBERS) {
                mergedIds.add(bucket.group().id());
                count -= members - 1;
            }
        }
        return mergedIds;
    }

    private static List<Slot> slots(List<Bucket> buckets, Set<String> mergedIds) {
        // 合并只改变本类的格子数量，不改变类之间或成员之间的显示顺序。
        var slots = new ArrayList<Slot>();
        for (var bucket : buckets) {
            if (mergedIds.contains(bucket.group().id())) {
                slots.add(new Merged(bucket.group(), bucket.effects()));
            } else {
                for (var effect : bucket.effects()) {
                    slots.add(new Single(effect));
                }
            }
        }
        return List.copyOf(slots);
    }

    /** 一个可绘制位置的内容，不包含像素位置、剩余时间或交互状态。 */
    public sealed interface Slot permits Single, Merged, Overflow {}

    /** 单个效果的身份；显示数据由调用方按 ID 查回实际效果实例。 */
    public record Single(StatusEffectRef effect) implements Slot {}

    /** 合并类及其成员；成员保持调用方原始顺序。 */
    public record Merged(StatusGroup group, List<StatusEffectRef> effects) implements Slot {
        public Merged {
            // 结果不能随着分组阶段的可变列表一起变化。
            effects = List.copyOf(effects);
        }
    }

    /** 最后一格收纳的剩余格子，保留各个合并类及单个效果的结构。 */
    public record Overflow(List<Slot> slots) implements Slot {
        public Overflow {
            // 截取原列表后立即复制，避免对外暴露 subList 视图。
            slots = List.copyOf(slots);
        }
    }

    private record Bucket(StatusGroup group, List<StatusEffectRef> effects) {}
}

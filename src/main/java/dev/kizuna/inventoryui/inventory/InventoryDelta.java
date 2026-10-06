package dev.kizuna.inventoryui.inventory;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 同一状态流的原子增量；容器变更使用完整容器目录，物品按实例删除或替换。 */
public record InventoryDelta(
        // 同一库存来源的稳定流身份。
        String streamId,
        // 必须精确匹配当前版本，不能跳过缺失的增量。
        long baseRevision,
        // 应用成功后的版本，严格大于基础版本。
        long revision,
        // null 沿用旧目录；非 null 是完整新目录，不是容器差量。
        List<InventorySnapshot.Container> containers,
        // 删除的物品实例 ID，与种类 ID 区分。
        Set<String> removedInstances,
        // 按实例覆盖后的完整物品及占格，删除先于覆盖执行。
        List<InventorySnapshot.Placement> upserts) {
    public InventoryDelta {
        // 容器目录可省略以沿用上一版；其余集合冻结，避免校验之后被调用者修改。
        Objects.requireNonNull(streamId);
        if (baseRevision < 0 || revision <= baseRevision) {
            throw new IllegalArgumentException("delta revision must advance");
        }
        containers = containers == null ? null : List.copyOf(containers);
        removedInstances = Set.copyOf(removedInstances);
        upserts = List.copyOf(upserts);
    }

    public InventorySnapshot apply(InventorySnapshot base) {
        // 必须精确衔接基础版本，跳版交给宿主请求完整快照，绝不猜测丢失的更新。
        if (!streamId.equals(base.streamId()) || baseRevision != base.revision()) {
            throw new IllegalArgumentException("delta base mismatch; full snapshot required");
        }
        var items = new LinkedHashMap<String, InventorySnapshot.Placement>();
        base.placements().forEach(value -> items.put(value.item().instanceId(), value));
        removedInstances.forEach(items::remove);
        var seen = new HashSet<String>();
        for (var value : upserts) {
            if (!seen.add(value.item().instanceId())) {
                throw new IllegalArgumentException("duplicate delta instance");
            }
            items.put(value.item().instanceId(), value);
        }
        // 新快照统一校验占格和引用，失败时调用方仍持有原快照，不会产生半更新。
        return new InventorySnapshot(
                streamId,
                revision,
                containers == null ? base.containers() : containers,
                List.copyOf(items.values()));
    }
}

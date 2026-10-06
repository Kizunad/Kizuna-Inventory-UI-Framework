package dev.kizuna.inventoryui.hud;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** HUD 布局与视口大小分离；偏移始终相对于声明的锚点。 */
public final class HudLayout {
    /** 允许的逻辑缩放倍数；默认值保持声明尺寸。 */
    public static final float MIN_SCALE = 0.25f;

    public static final float MAX_SCALE = 4f;
    public static final float DEFAULT_SCALE = 1f;
    private final FrameworkCatalog catalog;
    private final Map<String, Placement> placements = new LinkedHashMap<>();

    public HudLayout(FrameworkCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog);
    }

    public Placement placement(String id) {
        // 没有用户偏好时使用注册锚点、原始尺寸并默认显示。
        require(id);
        return placements.getOrDefault(id, new Placement(true, 0, 0, DEFAULT_SCALE));
    }

    public void place(String id, Placement placement) {
        // 布局偏好只覆盖已注册 HUD，当前保存在内存中，不写入服务端状态。
        require(id);
        placements.put(id, Objects.requireNonNull(placement));
    }

    public void reset(String id) {
        // 删除覆盖值即可恢复默认，不需要再保存一份默认布局副本。
        require(id);
        placements.remove(id);
    }

    public Rect bounds(String id, int viewportWidth, int viewportHeight) {
        // 先按缩放计算占屏尺寸，再按锚点定位；视口变化时无需修改已保存的相对偏移。
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            throw new IllegalArgumentException("invalid viewport");
        }
        var definition = require(id);
        var placement = placement(id);
        int w =
                Math.min(
                        viewportWidth,
                        Math.max(1, Math.round(definition.width() * placement.scale())));
        int h =
                Math.min(
                        viewportHeight,
                        Math.max(1, Math.round(definition.height() * placement.scale())));
        int x =
                switch (definition.anchor()) {
                    case TOP_RIGHT, BOTTOM_RIGHT -> viewportWidth - w;
                    case CENTER -> (viewportWidth - w) / 2;
                    default -> 0;
                };
        int y =
                switch (definition.anchor()) {
                    case BOTTOM_LEFT, BOTTOM_RIGHT -> viewportHeight - h;
                    case CENTER -> (viewportHeight - h) / 2;
                    default -> 0;
                };
        // 最后把偏移约束回可见区域，使用 long 相加防止极端偏移发生整数溢出。
        return new Rect(
                (int) Math.max(0, Math.min((long) x + placement.offsetX(), viewportWidth - w)),
                (int) Math.max(0, Math.min((long) y + placement.offsetY(), viewportHeight - h)),
                w,
                h);
    }

    private FrameworkCatalog.Hud require(String id) {
        // 未注册的 ID 是接入错误，不静默创建没有绘制器的空 HUD。
        var definition = catalog.huds().get(id);
        if (definition == null) {
            throw new IllegalArgumentException("unknown HUD: " + id);
        }
        return definition;
    }

    public record Placement(boolean visible, int offsetX, int offsetY, float scale) {
        public Placement {
            // 排除 NaN、无穷值和过大缩放，确保渲染矩阵及命中区域可以正常计算。
            if (!Float.isFinite(scale) || scale < MIN_SCALE || scale > MAX_SCALE) {
                throw new IllegalArgumentException(
                        "HUD scale must be between " + MIN_SCALE + " and " + MAX_SCALE);
            }
        }
    }
}

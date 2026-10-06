package dev.kizuna.inventoryui.window;

import java.util.Objects;
import java.util.Set;

/** 普通窗口的稳定声明，隔离窗口策略与 owo/Fabric 细节。 */
public record UiWindowDefinition(
        String windowType,
        String templateId,
        int minimumWidth,
        int minimumHeight,
        Set<Capability> capabilities) {
    public UiWindowDefinition {
        // 固定窗口身份和最小尺寸；能力集合复制后不受模块后续修改影响。
        windowType = requireText(windowType, "windowType");
        templateId = requireText(templateId, "templateId");
        if (minimumWidth <= 0 || minimumHeight <= 0) {
            throw new IllegalArgumentException("minimum window size must be positive");
        }
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    public boolean supports(Capability capability) {
        // 能力由声明决定，不能根据窗口标题或组件类型推断。
        return capabilities.contains(Objects.requireNonNull(capability, "capability"));
    }

    private static String requireText(String value, String name) {
        // 声明与实例采用相同的首尾空白处理，确保窗口类型可以精确匹配。
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    public enum Capability {
        WINDOW,
        /** 工位窗口可以管理布局，但不能固定到 HUD。 */
        STATION,
        OFFER,
        SYSTEM
    }
}

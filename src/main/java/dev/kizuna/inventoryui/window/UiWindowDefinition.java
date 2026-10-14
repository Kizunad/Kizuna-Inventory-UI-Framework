package dev.kizuna.inventoryui.window;

import java.util.Objects;
import java.util.Set;

/** 普通窗口的稳定声明，隔离窗口策略与 owo/Fabric 细节。 */
public record UiWindowDefinition(
        String windowType,
        String templateId,
        int minimumWidth,
        int minimumHeight,
        int preferredWidth,
        int preferredHeight,
        Set<Capability> capabilities) {
    private static final int DEFAULT_WIDTH = 280;
    private static final int DEFAULT_HEIGHT = 160;

    public UiWindowDefinition(String windowType, String templateId, int minimumWidth,
                              int minimumHeight, Set<Capability> capabilities) {
        // 旧声明保留原来的初始尺寸基线；新模块可分别声明紧凑下限与舒适尺寸。
        this(windowType, templateId, minimumWidth, minimumHeight,
                Math.max(DEFAULT_WIDTH, minimumWidth), Math.max(DEFAULT_HEIGHT, minimumHeight), capabilities);
    }

    public UiWindowDefinition {
        // 尺寸均为 GUI 逻辑像素；舒适尺寸不能比模块承诺的紧凑下限更小。
        windowType = requireText(windowType, "windowType");
        templateId = requireText(templateId, "templateId");
        if (minimumWidth <= 0 || minimumHeight <= 0) {
            throw new IllegalArgumentException("minimum window size must be positive");
        }
        if (preferredWidth < minimumWidth || preferredHeight < minimumHeight) {
            throw new IllegalArgumentException("preferred window size must meet minimum size");
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

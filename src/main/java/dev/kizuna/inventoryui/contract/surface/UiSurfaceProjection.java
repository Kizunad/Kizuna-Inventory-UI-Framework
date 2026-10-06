package dev.kizuna.inventoryui.contract.surface;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 不携带渲染器、DOM、XML 或像素坐标的不可变语义界面。 */
public record UiSurfaceProjection(
        String surfaceId,
        String templateId,
        String sessionId,
        long revision,
        long expiresAtMs,
        String closeReason,
        Map<String, String> viewData,
        Map<String, String> collectionIdentity,
        Map<String, UiActionSpec> allowedActions) {
    public static final long NO_EXPIRY = -1L;

    public UiSurfaceProjection {
        // 语义快照独立于渲染器；身份、版本及动作集合在构造时冻结，供不同视图共享。
        surfaceId = requireId(surfaceId, "surfaceId");
        templateId = requireId(templateId, "templateId");
        sessionId = requireId(sessionId, "sessionId");
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        if (expiresAtMs < NO_EXPIRY) {
            throw new IllegalArgumentException("expiresAtMs must be -1 or non-negative");
        }
        closeReason = normalize(closeReason);
        viewData = copyStringMap(viewData, "viewData");
        collectionIdentity = copyStringMap(collectionIdentity, "collectionIdentity");
        allowedActions = copyActions(allowedActions);
    }

    public boolean isClosed() {
        return closeReason != null;
    }

    public boolean isExpired(long nowMs) {
        // -1 表示没有期限，其余时间在到达边界的当刻即视为过期。
        return expiresAtMs != NO_EXPIRY && nowMs >= expiresAtMs;
    }

    public UiActionSpec action(String actionId) {
        return allowedActions.get(actionId);
    }

    private static Map<String, String> copyStringMap(Map<String, String> source, String name) {
        // 验证并复制键值，同时保留宿主给出的顺序，避免显示顺序因包装而变化。
        Objects.requireNonNull(source, name + " must not be null");
        Map<String, String> copy = new LinkedHashMap<>();
        source.forEach(
                (key, value) ->
                        copy.put(
                                requireId(key, name + " key"),
                                Objects.requireNonNull(value, name + " value must not be null")));
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, UiActionSpec> copyActions(Map<String, UiActionSpec> source) {
        // 映射键必须与动作自身身份一致，否则按 ID 查找到的动作会与提交目标不符。
        Objects.requireNonNull(source, "allowedActions must not be null");
        Map<String, UiActionSpec> copy = new LinkedHashMap<>();
        source.forEach(
                (key, action) -> {
                    String actionId = requireId(key, "allowedActions key");
                    UiActionSpec checked =
                            Objects.requireNonNull(action, "action must not be null");
                    if (!actionId.equals(checked.actionId())) {
                        throw new IllegalArgumentException(
                                "action map key must match actionId: " + actionId);
                    }
                    copy.put(actionId, checked);
                });
        return Collections.unmodifiableMap(copy);
    }

    private static String requireId(String value, String name) {
        // 稳定身份不能缺失，也不在此悄悄规范化，以免改变宿主协议中的对象引用。
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String normalize(String value) {
        // 没有关闭原因统一表示为 null，避免空白字符串被误当作已关闭状态。
        return value == null || value.isBlank() ? null : value;
    }
}

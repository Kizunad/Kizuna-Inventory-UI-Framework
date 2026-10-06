package dev.kizuna.inventoryui.intent;

import java.util.Objects;

/** 只表示本地传输结果；权威结果仍通过状态到达。 */
public record UiIntentResult(Kind kind, String reason, String requestId) {
    public UiIntentResult {
        // 这里仅描述本地提交；拒绝和异常需要可展示原因，服务端结果由后续状态表达。
        Objects.requireNonNull(kind, "kind must not be null");
        reason = normalize(reason);
        requestId = normalize(requestId);
        if (kind != Kind.LOCAL_ACCEPTED && reason == null) {
            throw new IllegalArgumentException("rejected and error results require a reason");
        }
    }

    public static UiIntentResult accepted() {
        return new UiIntentResult(Kind.LOCAL_ACCEPTED, null, null);
    }

    public static UiIntentResult accepted(String requestId) {
        // 请求身份用于后续结果关联，当前接受仅说明本地交付成功。
        return new UiIntentResult(Kind.LOCAL_ACCEPTED, null, requestId);
    }

    public static UiIntentResult rejected(String reason) {
        return new UiIntentResult(Kind.LOCAL_REJECTED, reason, null);
    }

    public static UiIntentResult error(String reason) {
        return new UiIntentResult(Kind.LOCAL_ERROR, reason, null);
    }

    private static String normalize(String value) {
        // 可选字段用 null 表示缺失，避免接入方分别处理空串与空白串。
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    public enum Kind {
        LOCAL_ACCEPTED,
        LOCAL_REJECTED,
        LOCAL_ERROR
    }
}

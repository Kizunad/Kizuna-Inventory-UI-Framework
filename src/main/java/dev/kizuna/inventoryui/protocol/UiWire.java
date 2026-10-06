package dev.kizuna.inventoryui.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** 框架公共传输信封，UTF-8 JSON；模块自行定义 data 的字段契约。 */
public final class UiWire {
    public static final int VERSION = 1;
    public static final int MAX_PACKET_BYTES = 262_144;
    private static final int MAX_JSON_DEPTH = 32;
    private static final Gson JSON = new Gson();

    private UiWire() {}

    public static byte[] encode(Packet packet) {
        // 出站也执行同样的大小限制，避免模块生成服务器无法接收的超大载荷。
        byte[] bytes = JSON.toJson(packet).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("packet exceeds size limit");
        }
        return bytes;
    }

    public static Packet decode(byte[] bytes) {
        // 在 JSON 递归解析之前限制字节与嵌套深度，恶意载荷不能耗尽栈空间。
        if (bytes.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("packet exceeds size limit");
        }
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            int depth = 0;
            boolean string = false;
            boolean escaped = false;
            for (char ch : text.toCharArray()) {
                if (escaped) {
                    escaped = false;
                } else if (string && ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    string = !string;
                } else if (!string && (ch == '{' || ch == '[')) {
                    if (++depth > MAX_JSON_DEPTH) {
                        throw new IllegalArgumentException("JSON nesting exceeds limit");
                    }
                } else if (!string && (ch == '}' || ch == ']')) {
                    depth--;
                }
            }
            // Gson 对 record 调用规范构造器，信封版本及必填字段在这里再次验证。
            return Objects.requireNonNull(
                    JSON.fromJson(JsonParser.parseString(text), Packet.class));
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("invalid UTF-8", failure);
        }
    }

    public enum Kind {
        HELLO,
        ACCEPT,
        EVENT,
        REQUEST,
        RESULT,
        ERROR
    }

    public record Capability(
            String module, int version, UiMessageRouter.Direction direction, boolean required) {
        public Capability {
            requireId(module);
            Objects.requireNonNull(direction);
            if (version <= 0) {
                throw new IllegalArgumentException("message version must be positive");
            }
        }
    }

    /** data 是模块载荷；request 是请求身份；capabilities 仅用于 HELLO/ACCEPT。 */
    public record Packet(
            int protocol,
            Kind kind,
            String module,
            String message,
            int version,
            String request,
            String status,
            JsonElement data,
            Map<String, Capability> capabilities) {
        public Packet {
            // 框架字段只描述路由和结果，不夹带装备部位、技能或业务库存类型。
            if (protocol != VERSION) {
                throw new IllegalArgumentException("unsupported framework protocol");
            }
            Objects.requireNonNull(kind);
            module = Objects.requireNonNullElse(module, "");
            message = Objects.requireNonNullElse(message, "");
            request = Objects.requireNonNullElse(request, "");
            status = Objects.requireNonNullElse(status, "");
            data = data == null ? JsonNull.INSTANCE : data.deepCopy();
            capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
        }

        public static Packet negotiation(Kind kind, Map<String, Capability> capabilities) {
            return new Packet(VERSION, kind, "", "", 0, "", "", JsonNull.INSTANCE, capabilities);
        }
    }

    public static String requireId(String id) {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid namespaced id: " + id);
        }
        return id;
    }

    /** 自定义编解码必须验证字段；record 的便捷编解码器仍依赖其构造器验证领域约束。 */
    public interface Codec<T> {
        JsonElement encode(T value);

        T decode(JsonElement value);

        static <T extends Record> Codec<T> record(Class<T> type) {
            return new Codec<>() {
                @Override
                public JsonElement encode(T value) {
                    return JSON.toJsonTree(type.cast(Objects.requireNonNull(value)));
                }

                @Override
                public T decode(JsonElement value) {
                    return Objects.requireNonNull(
                            JSON.fromJson(value, type), "payload must not be null");
                }
            };
        }
    }

    public record Contract<T>(UiMessageRouter.Contract<T> route, Codec<T> codec, boolean required) {
        public Contract {
            Objects.requireNonNull(route);
            Objects.requireNonNull(codec);
            requireId(route.moduleId());
            requireId(route.id());
        }
    }
}

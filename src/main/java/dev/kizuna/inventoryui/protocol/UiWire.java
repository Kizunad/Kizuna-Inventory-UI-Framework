package dev.kizuna.inventoryui.protocol;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

import dev.kizuna.inventoryui.protocol.pb.WireProtocol;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** 框架 Protobuf 信封；wire.proto 是字段契约，子模块用自己的 proto 定义 data。 */
public final class UiWire {
    public static final int VERSION = 2;

    /** 单个信封或模块载荷的最大字节数；信封总量仍须满足此限制。 */
    public static final int MAX_PACKET_BYTES = 262_144;

    /** 信封和模块载荷分别允许的最大消息嵌套层数。 */
    private static final int MAX_MESSAGE_DEPTH = 32;

    private UiWire() {}

    public static byte[] encode(Packet packet) {
        // 业务对象先转成 protoc 生成的信封，序列化前检查总大小，不能只限制 data。
        var envelope =
                WireProtocol.Envelope.newBuilder()
                        .setProtocol(packet.protocol())
                        .setKind(WireProtocol.Kind.valueOf(packet.kind().name()))
                        .setModule(packet.module())
                        .setMessage(packet.message())
                        .setVersion(packet.version())
                        .setRequest(packet.request())
                        .setStatus(packet.status())
                        .setData(packet.data());
        packet.capabilities()
                .forEach(
                        (id, capability) ->
                                envelope.putCapabilities(
                                        id,
                                        WireProtocol.Capability.newBuilder()
                                                .setModule(capability.module())
                                                .setVersion(capability.version())
                                                .setDirection(
                                                        WireProtocol.Direction.valueOf(
                                                                capability.direction().name()))
                                                .setRequired(capability.required())
                                                .setPayloadType(capability.payloadType())
                                                .build()));
        var value = envelope.build();
        requireSize(value.getSerializedSize());
        return value.toByteArray();
    }

    public static Packet decode(byte[] bytes) {
        // 先限制输入再解码；未知 kind/direction 不映射成默认值，直接拒绝。
        requireSize(bytes.length);
        var envelope = parse(WireProtocol.Envelope.parser(), ByteString.copyFrom(bytes));
        var capabilities = new LinkedHashMap<String, Capability>();
        envelope.getCapabilitiesMap()
                .forEach(
                        (id, capability) ->
                                capabilities.put(
                                        requireId(id),
                                        new Capability(
                                                capability.getModule(),
                                                capability.getVersion(),
                                                UiMessageRouter.Direction.valueOf(
                                                        capability.getDirection().name()),
                                                capability.getRequired(),
                                                capability.getPayloadType())));
        return new Packet(
                envelope.getProtocol(),
                Kind.valueOf(envelope.getKind().name()),
                envelope.getModule(),
                envelope.getMessage(),
                envelope.getVersion(),
                envelope.getRequest(),
                envelope.getStatus(),
                envelope.getData(),
                capabilities);
    }

    private static void requireSize(int bytes) {
        // 对数组解析器显式检查长度，不能依赖仅适用于流输入的 sizeLimit。
        if (bytes > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("packet exceeds size limit");
        }
    }

    private static <T extends MessageLite> T parse(Parser<T> parser, ByteString bytes) {
        // 模块 payload 是第二层独立编码，也必须限制大小、递归并消费完整输入。
        requireSize(bytes.size());
        var input = bytes.newCodedInput();
        input.setRecursionLimit(MAX_MESSAGE_DEPTH);
        try {
            T value = parser.parseFrom(input);
            input.checkLastTagWas(0);
            if (!input.isAtEnd()) {
                throw new IllegalArgumentException("unconsumed protobuf input");
            }
            return value;
        } catch (IOException failure) {
            throw new IllegalArgumentException("invalid protobuf payload", failure);
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
            // 注册的模块身份，不是 JAR 名称或玩家身份。
            String module,
            // 此消息的正整数契约版本，与框架协议版本独立。
            int version,
            // 以客户端为参照的唯一允许方向。
            UiMessageRouter.Direction direction,
            // 服务端协商时能否省略此能力。
            boolean required,
            // proto 描述符全名，跨语言不使用生成类的语言特有名称。
            String payloadType) {
        public Capability {
            // 类型使用 proto 描述符全名，跨语言生成类名不同也能协商同一契约。
            requireId(module);
            Objects.requireNonNull(direction);
            Objects.requireNonNull(payloadType);
            if (payloadType.isBlank()) {
                throw new IllegalArgumentException("payload type must not be blank");
            }
            if (version <= 0) {
                throw new IllegalArgumentException("message version must be positive");
            }
        }
    }

    /** data 是模块载荷；request 是请求身份；capabilities 仅用于 HELLO/ACCEPT。 */
    public record Packet(
            // 框架 wire 版本，必须等于 VERSION。
            int protocol,
            // 控制、业务事件、请求、结果或错误类别。
            Kind kind,
            // 目标模块及其注册消息；协商消息使用空字符串。
            String module,
            String message,
            // 具体消息版本；协商消息使用零。
            int version,
            // 关联请求 UUID；无关联的事件和握手使用空字符串。
            String request,
            // RESULT 的接受／拒绝标志，或 ERROR 的结构化错误码。
            String status,
            // 模块 proto 消息的不可变字节，不包含信封或额外长度前缀。
            ByteString data,
            // 消息 ID 到能力的映射，仅握手使用。
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
            data = data == null ? ByteString.EMPTY : data;
            capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
        }

        public static Packet negotiation(Kind kind, Map<String, Capability> capabilities) {
            // 协商不承载业务状态；空字节使用 Protobuf 的默认值表示。
            return new Packet(VERSION, kind, "", "", 0, "", "", ByteString.EMPTY, capabilities);
        }
    }

    public static String requireId(String id) {
        // 路由身份统一使用命名空间，防止模块把展示名称误注册为消息 ID。
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid namespaced id: " + id);
        }
        return id;
    }

    /** 模块使用生成的消息，或在生成消息与领域模型之间做显式转换和校验。 */
    public interface Codec<T> {
        String payloadType();

        ByteString encode(T value);

        T decode(ByteString value);

        static <P extends Message> Codec<P> protobuf(P defaultInstance) {
            // 直接消费生成类时不推断业务合法性；模块处理器仍须校验领域约束。
            return protobuf(defaultInstance, Function.identity(), Function.identity());
        }

        static <T, P extends Message> Codec<T> protobuf(
                P defaultInstance, Function<T, P> toProto, Function<P, T> fromProto) {
            // 类型名与 parser 均来自同一个生成类，避免手写 schema 名称与解码器不一致。
            Objects.requireNonNull(defaultInstance);
            Objects.requireNonNull(toProto);
            Objects.requireNonNull(fromProto);
            @SuppressWarnings("unchecked")
            Parser<P> parser = (Parser<P>) defaultInstance.getParserForType();
            return new Codec<>() {
                @Override
                public String payloadType() {
                    return defaultInstance.getDescriptorForType().getFullName();
                }

                @Override
                public ByteString encode(T value) {
                    // 先转换并校验模型，再检查序列化大小，最后生成不可变字节。
                    P encoded =
                            Objects.requireNonNull(toProto.apply(Objects.requireNonNull(value)));
                    requireSize(encoded.getSerializedSize());
                    return encoded.toByteString();
                }

                @Override
                public T decode(ByteString value) {
                    // protobuf 能解析不代表业务有效，转换器必须落实构造器或领域校验。
                    return Objects.requireNonNull(fromProto.apply(parse(parser, value)));
                }
            };
        }
    }

    public record Contract<T>(UiMessageRouter.Contract<T> route, Codec<T> codec, boolean required) {
        public Contract {
            // 每份消息契约都必须有领域路由、protobuf 类型和明确的编解码器。
            Objects.requireNonNull(route);
            Objects.requireNonNull(codec);
            requireId(route.moduleId());
            requireId(route.id());
        }
    }
}

package dev.kizuna.inventoryui.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.UnknownFieldSet;

import dev.kizuna.inventoryui.protocol.fixture.TestProtocol;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Map;

class UiWireTest {
    @Test
    void independentGeneratedEnvelopeInteroperatesWithRuntimeCodec() throws Exception {
        // 对端只依赖生成类，不使用 UiWire 编码，避免自编码自解码掩盖字段漂移。
        var peer =
                WireProtocol.Envelope.newBuilder()
                        .setProtocol(2)
                        .setKind(WireProtocol.Kind.EVENT)
                        .setModule("test:module")
                        .setMessage("test:state")
                        .setVersion(1)
                        .setData(TestProtocol.Value.newBuilder().setCount(7).build().toByteString())
                        .build();
        var packet = UiWire.decode(peer.toByteArray());
        assertEquals(UiWire.Kind.EVENT, packet.kind());
        assertEquals(
                7,
                UiWire.Codec.protobuf(TestProtocol.Value.getDefaultInstance())
                        .decode(packet.data())
                        .getCount());
        assertEquals(peer, WireProtocol.Envelope.parseFrom(UiWire.encode(packet)));
    }

    @Test
    void capabilitiesCarrySchemaNamesAndDirections() throws Exception {
        // 协商必须保留消息、模块、方向、版本、必需标记及跨语言类型全名。
        var capability =
                new UiWire.Capability(
                        "test:module",
                        1,
                        UiMessageRouter.Direction.CLIENT_TO_SERVER,
                        true,
                        "kiui.test.v1.Value");
        var hello = UiWire.Packet.negotiation(UiWire.Kind.HELLO, Map.of("test:move", capability));
        var peer = WireProtocol.Envelope.parseFrom(UiWire.encode(hello));
        var declared = peer.getCapabilitiesOrThrow("test:move");
        assertEquals("kiui.test.v1.Value", declared.getPayloadType());
        assertEquals(WireProtocol.Direction.CLIENT_TO_SERVER, declared.getDirection());
        assertTrue(declared.getRequired());
        assertEquals(hello, UiWire.decode(peer.toByteArray()));
    }

    @Test
    void rejectsUnsupportedVersionDefaultOrUnknownEnumsAndMissingPayloadType() {
        // proto3 可解码默认值和未知枚举数值，但它们不能成为有效协议身份。
        var builder =
                WireProtocol.Envelope.newBuilder().setProtocol(2).setKind(WireProtocol.Kind.HELLO);
        assertThrows(IllegalArgumentException.class, () -> UiWire.decode(new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> UiWire.decode(builder.clone().setProtocol(1).build().toByteArray()));
        assertThrows(
                IllegalArgumentException.class,
                () -> UiWire.decode(builder.clone().setKindValue(99).build().toByteArray()));
        assertThrows(
                IllegalArgumentException.class,
                () -> UiWire.decode(builder.clone().clearKind().build().toByteArray()));
        var capability =
                WireProtocol.Capability.newBuilder()
                        .setModule("test:module")
                        .setVersion(1)
                        .setDirection(WireProtocol.Direction.CLIENT_TO_SERVER)
                        .setPayloadType("kiui.test.v1.Value");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UiWire.decode(
                                builder.clone()
                                        .putCapabilities(
                                                "test:move",
                                                capability.clone().setDirectionValue(99).build())
                                        .build()
                                        .toByteArray()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UiWire.decode(
                                builder.clone()
                                        .putCapabilities(
                                                "test:move",
                                                capability.clone().clearPayloadType().build())
                                        .build()
                                        .toByteArray()));
    }

    @Test
    void acceptsUnknownFieldsForAdditiveSchemaEvolution() {
        // 新字段可被旧实现忽略，但路由和已有字段的语义不能随之改变。
        var peer =
                WireProtocol.Envelope.newBuilder()
                        .setProtocol(2)
                        .setKind(WireProtocol.Kind.HELLO)
                        .setUnknownFields(
                                UnknownFieldSet.newBuilder()
                                        .addField(
                                                100,
                                                UnknownFieldSet.Field.newBuilder()
                                                        .addVarint(42)
                                                        .build())
                                        .build())
                        .build();
        assertEquals(UiWire.Kind.HELLO, UiWire.decode(peer.toByteArray()).kind());
    }

    @Test
    void limitsWholeEnvelopeAndPayloadAndRejectsTruncatedFields() {
        // 不能只限制 payload 长度；信封元数据叠加后超限也不得发送。
        var packet =
                new UiWire.Packet(
                        2,
                        UiWire.Kind.EVENT,
                        "test:module",
                        "test:state",
                        1,
                        "",
                        "",
                        ByteString.copyFrom(new byte[UiWire.MAX_PACKET_BYTES]),
                        Map.of());
        assertThrows(IllegalArgumentException.class, () -> UiWire.encode(packet));
        var codec = UiWire.Codec.protobuf(TestProtocol.Value.getDefaultInstance());
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(ByteString.copyFrom(new byte[UiWire.MAX_PACKET_BYTES + 1])));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(ByteString.copyFrom(new byte[] {8})));
        assertThrows(
                IllegalArgumentException.class, () -> UiWire.decode(new byte[] {8, 2, 26, 2, 65}));
        assertThrows(
                IllegalArgumentException.class,
                () -> UiWire.decode(new byte[] {8, 2, 26, 1, (byte) 255}));
    }

    @Test
    void recursionLimitAlsoAppliesToUnknownGroupsInModulePayloads() throws Exception {
        // 未知字段仍由 protobuf 解析器遍历，恶意深层 group 不能绕过模块边界限制。
        var bytes = new ByteArrayOutputStream();
        var output = CodedOutputStream.newInstance(bytes);
        for (int i = 0; i < 100; i++) {
            output.writeTag(100, com.google.protobuf.WireFormat.WIRETYPE_START_GROUP);
        }
        for (int i = 0; i < 100; i++) {
            output.writeTag(100, com.google.protobuf.WireFormat.WIRETYPE_END_GROUP);
        }
        output.flush();
        var codec = UiWire.Codec.protobuf(TestProtocol.Value.getDefaultInstance());
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(ByteString.copyFrom(bytes.toByteArray())));
    }
}

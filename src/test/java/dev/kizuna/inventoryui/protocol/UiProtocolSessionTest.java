package dev.kizuna.inventoryui.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;

import dev.kizuna.inventoryui.protocol.fixture.TestProtocol;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

class UiProtocolSessionTest {
    record Value(int count) {
        Value {
            // protobuf 允许 count 的默认零值，领域转换必须拒绝无效业务数量。
            if (count <= 0) {
                throw new IllegalArgumentException("count must be positive");
            }
        }
    }

    private static final UiWire.Codec<Value> VALUE_CODEC =
            UiWire.Codec.protobuf(
                    TestProtocol.Value.getDefaultInstance(),
                    value -> TestProtocol.Value.newBuilder().setCount(value.count()).build(),
                    value -> new Value(value.getCount()));

    private final List<UiWire.Packet> outbound = new ArrayList<>();
    private final List<String> diagnostics = new ArrayList<>();
    private final List<Value> received = new ArrayList<>();

    private UiProtocolSession session() {
        var catalog =
                FrameworkCatalog.build(
                        List.of(FrameworkCatalog.Module.of("test:module", Set.of())));
        var event =
                new UiWire.Contract<>(
                        new UiMessageRouter.Contract<>(
                                "test:module",
                                "test:state",
                                1,
                                UiMessageRouter.Direction.SERVER_TO_CLIENT,
                                Value.class,
                                received::add),
                        VALUE_CODEC,
                        true);
        var request =
                new UiWire.Contract<>(
                        new UiMessageRouter.Contract<>(
                                "test:module",
                                "test:move",
                                1,
                                UiMessageRouter.Direction.CLIENT_TO_SERVER,
                                Value.class,
                                null),
                        VALUE_CODEC,
                        false);
        return new UiProtocolSession(
                catalog,
                List.of(event, request),
                packet -> outbound.add(UiWire.decode(UiWire.encode(packet))),
                diagnostics::add);
    }

    private void ready(UiProtocolSession session) {
        session.connect(0);
        session.receive(
                session.generation(),
                UiWire.decode(
                        UiWire.encode(
                                UiWire.Packet.negotiation(
                                        UiWire.Kind.ACCEPT, session.capabilities()))));
        assertEquals(UiProtocolSession.State.READY, session.state());
    }

    @Test
    void encodedLoopbackNegotiatesRoutesAndCorrelatesAuthoritativeResults() {
        var session = session();
        assertEquals("NOT_READY", session.request("test:move", new Value(3), 0).join().code());
        ready(session);
        var event =
                new UiWire.Packet(
                        UiWire.VERSION,
                        UiWire.Kind.EVENT,
                        "test:module",
                        "test:state",
                        1,
                        "",
                        "",
                        VALUE_CODEC.encode(new Value(7)),
                        Map.of());
        session.receive(session.generation(), UiWire.decode(UiWire.encode(event)));
        assertEquals(List.of(new Value(7)), received, "一个事件只应用一次，多个 UI 订阅同一状态");
        var result = session.request("test:move", new Value(3), 1);
        var sent = outbound.get(outbound.size() - 1);
        session.receive(
                session.generation(),
                new UiWire.Packet(
                        UiWire.VERSION,
                        UiWire.Kind.RESULT,
                        "other:module",
                        sent.message(),
                        1,
                        sent.request(),
                        "ACCEPTED",
                        ByteString.EMPTY,
                        Map.of()));
        assertFalse(result.isDone(), "其他模块不能结算请求");
        session.receive(
                session.generation(),
                new UiWire.Packet(
                        UiWire.VERSION,
                        UiWire.Kind.RESULT,
                        sent.module(),
                        sent.message(),
                        1,
                        sent.request(),
                        "REJECTED",
                        ByteString.EMPTY,
                        Map.of()));
        assertFalse(result.join().accepted());
        assertEquals("REJECTED", result.join().code());
        assertEquals(1, received.size(), "结果不是权威库存快照");
    }

    @Test
    void missingModuleAndMalformedPayloadReturnStructuredErrorsWithoutCallingHandlers() {
        var session = session();
        ready(session);
        session.receive(
                session.generation(),
                new UiWire.Packet(
                        UiWire.VERSION,
                        UiWire.Kind.EVENT,
                        "missing:module",
                        "missing:state",
                        1,
                        "",
                        "",
                        ByteString.EMPTY,
                        Map.of()));
        assertEquals("MODULE_MISSING", outbound.get(outbound.size() - 1).status());
        session.receive(
                session.generation(),
                new UiWire.Packet(
                        UiWire.VERSION,
                        UiWire.Kind.EVENT,
                        "test:module",
                        "test:state",
                        1,
                        "",
                        "",
                        TestProtocol.Value.getDefaultInstance().toByteString(),
                        Map.of()));
        assertEquals("INVALID_PAYLOAD", outbound.get(outbound.size() - 1).status());
        assertTrue(received.isEmpty());
    }

    @Test
    void disconnectAndTimeoutCompleteWaitersAndOldGenerationCannotApply() {
        var session = session();
        ready(session);
        var timeout = session.request("test:move", new Value(1), 0);
        int sent = outbound.size();
        session.tick(UiProtocolSession.REQUEST_TIMEOUT_MS);
        assertEquals("TIMEOUT", timeout.join().code());
        assertEquals(sent, outbound.size(), "超时不能自动重试有副作用的请求");
        var pending = session.request("test:move", new Value(1), 0);
        long oldGeneration = session.generation();
        session.disconnect();
        assertEquals("DISCONNECTED", pending.join().code());
        ready(session);
        session.receive(
                oldGeneration,
                new UiWire.Packet(
                        UiWire.VERSION,
                        UiWire.Kind.EVENT,
                        "test:module",
                        "test:state",
                        1,
                        "",
                        "",
                        VALUE_CODEC.encode(new Value(9)),
                        Map.of()));
        assertTrue(received.isEmpty());
    }

    @Test
    void requiredCapabilitiesAndHandshakeTimeoutFailClosed() {
        var session = session();
        session.connect(0);
        session.receive(
                session.generation(), UiWire.Packet.negotiation(UiWire.Kind.ACCEPT, Map.of()));
        assertEquals(UiProtocolSession.State.FAILED, session.state());
        session.connect(0);
        session.tick(UiProtocolSession.HANDSHAKE_TIMEOUT_MS);
        assertEquals(UiProtocolSession.State.FAILED, session.state());
    }

    @Test
    void wireRejectsOversizeMalformedProtobufAndLegacyJson() {
        assertThrows(
                IllegalArgumentException.class,
                () -> UiWire.decode(new byte[UiWire.MAX_PACKET_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> UiWire.decode(new byte[] {(byte) 0xff}));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UiWire.decode(
                                "{\"protocol\":1,\"kind\":\"HELLO\"}"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void mismatchedProtobufTypeCannotCompleteNegotiation() {
        // 相同消息 ID/版本却绑定不同生成类型时，必须在交付任何业务数据前失败。
        var session = session();
        session.connect(0);
        var capabilities = new java.util.LinkedHashMap<>(session.capabilities());
        var original = capabilities.get("test:state");
        capabilities.put(
                "test:state",
                new UiWire.Capability(
                        original.module(),
                        original.version(),
                        original.direction(),
                        original.required(),
                        "other.module.State"));
        session.receive(
                session.generation(),
                UiWire.decode(
                        UiWire.encode(
                                UiWire.Packet.negotiation(UiWire.Kind.ACCEPT, capabilities))));
        assertEquals(UiProtocolSession.State.FAILED, session.state());
        assertEquals("PROTOCOL_MISMATCH", outbound.get(outbound.size() - 1).status());
        assertTrue(received.isEmpty());
    }
}

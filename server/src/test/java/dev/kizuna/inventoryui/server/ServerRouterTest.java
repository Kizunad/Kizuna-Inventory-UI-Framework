package dev.kizuna.inventoryui.server;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import com.google.protobuf.StringValue;
import dev.kizuna.inventoryui.protocol.fixture.TestProtocol.Value;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class ServerRouterTest {
    private final List<Envelope> replies = new ArrayList<>();
    private final List<String> handled = new ArrayList<>();
    private final List<String> closed = new ArrayList<>();
    private final Map<String, ServerConnection<String>> views = new HashMap<>();
    private final AtomicReference<String> player = new AtomicReference<>("authenticated-player");
    private final List<String> failures = new ArrayList<>();

    @Test
    void oneHandshakeRoutesTwoModulesAndCorrelatesResults() throws Exception {
        var registry = modules(true, true);
        var router = router(registry);
        receive(router, hello(registry.capabilities()));
        var first = request("alpha", 7);
        var second = request("beta", -1);
        receive(router, first);
        receive(router, second);
        assertEquals(List.of("alpha:authenticated-player:7", "beta:authenticated-player:-1"), handled);
        assertEquals(5, replies.size());
        assertEquals(registry.capabilities(), replies.get(0).getCapabilitiesMap());
        assertEquals("alpha:state", replies.get(1).getMessage());
        assertEquals(7, Value.parseFrom(replies.get(1).getData()).getCount());
        assertResult(replies.get(2), first, "ACCEPTED");
        assertEquals("beta:state", replies.get(3).getMessage());
        assertResult(replies.get(4), second, "REJECTED");
    }

    @Test
    void optionalModulesAreNotCreatedWhenPeerDoesNotInstallThem() {
        var registry = modules(true, false);
        var router = router(registry);
        receive(router, hello(contracts("alpha", true)));
        assertEquals(Kind.ACCEPT, last().getKind());
        assertEquals(contracts("alpha", true), last().getCapabilitiesMap());
        assertEquals(List.of("alpha"), views.keySet().stream().toList());
        receive(router, request("beta", 1));
        assertEquals("PROTOCOL_MISMATCH", last().getStatus());
        assertTrue(handled.isEmpty());
    }

    @Test
    void absentRequiredClientModuleFailsWithoutPartialSessions() {
        var registry = modules(true, true);
        var router = router(registry);
        receive(router, hello(contracts("alpha", true)));
        assertEquals("REQUIRED_CAPABILITY_MISSING", last().getStatus());
        assertEquals("beta:module", last().getModule());
        assertTrue(views.isEmpty());
        receive(router, request("alpha", 1));
        assertEquals("NOT_READY", last().getStatus());
    }

    @Test
    void absentRequiredServerModuleIsExplicitButOptionalOfferIsOmitted() {
        var registry = modules(true, false);
        var offered = new HashMap<>(contracts("alpha", true));
        offered.putAll(contracts("absent", true));
        receive(router(registry), hello(offered));
        assertEquals("MODULE_MISSING", last().getStatus());
        offered.putAll(contracts("absent", false));
        receive(router(registry), hello(offered));
        assertEquals(Kind.ACCEPT, last().getKind());
        assertEquals(contracts("alpha", true), last().getCapabilitiesMap());
    }

    @Test
    void handshakeChecksTypeVersionDirectionAndModule() {
        for (var invalid : List.of(
                contracts("alpha", true).get("alpha:request").toBuilder().setPayloadType("wrong.Type").build(),
                contracts("alpha", true).get("alpha:request").toBuilder().setVersion(2).build(),
                contracts("alpha", true).get("alpha:request").toBuilder().setDirection(Direction.SERVER_TO_CLIENT).build(),
                contracts("alpha", true).get("alpha:request").toBuilder().setModule("alpha:other").build())) {
            var offered = new HashMap<>(contracts("alpha", true));
            offered.put("alpha:request", invalid);
            receive(router(modules(true, false)), hello(offered));
            assertEquals("REQUIRED_CAPABILITY_MISSING", last().getStatus());
        }
    }

    @Test
    void duplicateAndForeignRegistrationsCannotReplaceAnExistingHandler() {
        var registry = modules(true, false);
        assertThrows(IllegalArgumentException.class, () -> add(registry, "alpha", true));
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                "alpha:other", contracts("alpha", true), connection -> request -> true));
        var malformed = contracts("invalid", true).get("invalid:request").toBuilder()
                .setDirectionValue(99).build();
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                "invalid:module", Map.of("invalid:request", malformed), connection -> request -> true));
        registry.freeze();
        assertThrows(IllegalArgumentException.class, () -> add(registry, "late", true));
        assertEquals(4, registry.capabilities().size());
    }

    @Test
    void requestIdentityAndDirectionAreValidatedBeforeDelivery() {
        var registry = modules(true, true);
        var router = router(registry);
        receive(router, request("alpha", 1));
        assertEquals("NOT_READY", last().getStatus());
        receive(router, hello(registry.capabilities()));
        receive(router, request("alpha", 1).toBuilder().setModule("beta:module").build());
        assertEquals("UNKNOWN_MESSAGE", last().getStatus());
        receive(router, request("alpha", 1).toBuilder().setMessage("alpha:state").build());
        assertEquals("INVALID_DIRECTION", last().getStatus());
        receive(router, request("alpha", 1).toBuilder().setVersion(2).build());
        assertEquals("PROTOCOL_MISMATCH", last().getStatus());
        receive(router, request("alpha", 1).toBuilder().setRequest("1-1-1-1-1").build());
        assertEquals("INVALID_PAYLOAD", last().getStatus());
        receive(router, request("alpha", 1).toBuilder().setStatus("ACCEPTED").build());
        assertEquals("INVALID_PAYLOAD", last().getStatus());
        receive(router, request("missing", 1));
        assertEquals("MODULE_MISSING", last().getStatus());
        assertTrue(handled.isEmpty());
    }

    @Test
    void malformedPayloadAndFailingHandlerDoNotBreakOtherModules() {
        var registry = modules(true, true);
        registry.register("broken:module", contracts("broken", true), connection -> request -> {
            throw new IllegalStateException("test failure");
        });
        var router = router(registry);
        receive(router, hello(registry.capabilities()));
        receive(router, request("alpha", 1).toBuilder().setData(ByteString.copyFrom(new byte[] {-1})).build());
        assertEquals("INVALID_PAYLOAD", last().getStatus());
        receive(router, request("broken", 1));
        assertEquals("HANDLER_FAILURE", last().getStatus());
        receive(router, request("beta", 2));
        assertEquals("ACCEPTED", last().getStatus());
        assertEquals(List.of("beta:authenticated-player:2"), handled);
        assertEquals(1, failures.size());
    }

    @Test
    void outputIsScopedTypedAndBoundedAndCanBePushedOutsideRequests() {
        var registry = modules(true, true);
        var router = router(registry);
        receive(router, hello(registry.capabilities()));
        var alpha = views.get("alpha");
        assertTrue(alpha.supports("alpha:state"));
        assertFalse(alpha.supports("beta:state"));
        assertThrows(IllegalStateException.class, () -> alpha.send("beta:state", Value.getDefaultInstance()));
        assertThrows(IllegalStateException.class, () -> alpha.send("alpha:request", Value.getDefaultInstance()));
        assertThrows(IllegalStateException.class, () -> alpha.send("alpha:state", StringValue.of("wrong")));
        alpha.send("alpha:state", Value.newBuilder().setCount(42).build());
        assertEquals(Kind.EVENT, last().getKind());
        assertEquals("alpha:state", last().getMessage());
        assertEquals(2, replies.size());
    }

    @Test
    void oversizedOutputIsRejectedBeforeTransport() {
        var registry = new ServerModuleRegistry<String>();
        var contract = capability("large", Direction.SERVER_TO_CLIENT, true).toBuilder()
                .setPayloadType(StringValue.getDescriptor().getFullName()).build();
        registry.register("large:module", Map.of("large:state", contract), connection -> {
            views.put("large", connection);
            return request -> true;
        });
        var router = router(registry);
        receive(router, hello(registry.capabilities()));
        assertThrows(IllegalStateException.class, () -> views.get("large").send(
                "large:state", StringValue.of("x".repeat(ServerProtocol.MAX_PACKET_BYTES))));
        assertEquals(1, replies.size());
    }

    @Test
    void disconnectInvalidatesViewsAndReconnectionHasNewSessionIdentity() {
        var registry = modules(true, true);
        var first = router(registry);
        receive(first, hello(registry.capabilities()));
        var old = views.get("alpha");
        player.set("respawned-player");
        receive(first, request("alpha", 1));
        assertEquals(List.of("alpha:respawned-player:1"), handled);
        first.close();
        first.close();
        int sent = replies.size();
        receive(first, request("alpha", 1));
        assertEquals(sent, replies.size());
        assertEquals(2, closed.size());
        assertFalse(old.supports("alpha:state"));
        assertThrows(IllegalStateException.class, old::context);
        assertThrows(IllegalStateException.class, () -> old.send("alpha:state", Value.getDefaultInstance()));
        receive(router(registry), hello(registry.capabilities()));
        assertNotEquals(old.id(), views.get("alpha").id());
    }

    @Test
    void duplicateHandshakeCannotResetSessionsAndErrorNeverEchoes() {
        var registry = modules(true, true);
        var router = router(registry);
        receive(router, hello(registry.capabilities()));
        var before = views.get("alpha");
        receive(router, hello(registry.capabilities()));
        assertEquals("UNEXPECTED_HANDSHAKE", last().getStatus());
        assertSame(before, views.get("alpha"));
        int count = replies.size();
        receive(router, Envelope.newBuilder().setKind(Kind.ERROR).setProtocol(99).build());
        assertEquals(count, replies.size());
        receive(router, request("alpha", 1));
        assertEquals("ACCEPTED", last().getStatus());
    }

    @Test
    void malformedAndOversizedEnvelopeNeverReachesModules() {
        var router = router(modules(true, true));
        router.receive(new byte[] {-1});
        router.receive(new byte[ServerProtocol.MAX_REQUEST_BYTES + 1]);
        assertTrue(replies.isEmpty());
        receive(router, hello(Map.of()).toBuilder().setProtocol(99).build());
        assertEquals("PROTOCOL_MISMATCH", last().getStatus());
        receive(router, hello(Map.of()).toBuilder().setData(ByteString.copyFromUtf8("unexpected")).build());
        assertEquals("INVALID_PAYLOAD", last().getStatus());
        assertTrue(handled.isEmpty());
    }

    @Test
    void wrongHostThreadCannotDeliverOrPush() throws Exception {
        var registry = modules(true, true);
        var router = router(registry);
        receive(router, hello(registry.capabilities()));
        var failures = new AtomicInteger();
        var thread = new Thread(() -> {
            try {
                receive(router, request("alpha", 1));
            } catch (IllegalStateException expected) {
                failures.incrementAndGet();
            }
            try {
                views.get("alpha").send("alpha:state", Value.getDefaultInstance());
            } catch (IllegalStateException expected) {
                failures.incrementAndGet();
            }
        });
        thread.start();
        thread.join();
        assertEquals(2, failures.get());
        assertTrue(handled.isEmpty());
    }

    @Test
    void partialInitializationClosesCreatedSessionsAndNeverAccepts() {
        var registry = new ServerModuleRegistry<String>();
        var created = new AtomicInteger();
        var released = new AtomicInteger();
        for (String module : List.of("one", "two")) {
            // 无论目录遍历顺序如何，第二个工厂失败都必须回收第一个会话。
            registry.register(module + ":module", contracts(module, true), connection -> {
                if (created.incrementAndGet() == 2) {
                    throw new IllegalStateException("factory failed");
                }
                return new ServerModuleSession<>() {
                    public boolean handle(ServerRequest<String> request) {
                        return true;
                    }

                    public void close() {
                        released.incrementAndGet();
                    }
                };
            });
        }
        var router = router(registry);
        receive(router, hello(registry.capabilities()));
        assertEquals("HANDLER_FAILURE", last().getStatus());
        assertEquals(1, released.get());
        assertFalse(replies.stream().anyMatch(reply -> reply.getKind() == Kind.ACCEPT));
        router.close();
        assertEquals(1, released.get());
    }

    @Test
    void failedCleanupCannotSkipOtherModules() {
        var registry = modules(true, true);
        registry.register("broken:module", contracts("broken", true), connection -> new ServerModuleSession<>() {
            public boolean handle(ServerRequest<String> request) {
                return true;
            }

            public void close() {
                throw new IllegalStateException("cleanup failed");
            }
        });
        var router = router(registry);
        receive(router, hello(registry.capabilities()));
        router.close();
        assertEquals(2, closed.size());
        assertEquals(1, failures.size());
    }

    @Test
    void payloadRecursionAndUnexpectedEndGroupAreRejected() {
        var registry = modules(true, true);
        var router = router(registry);
        receive(router, hello(registry.capabilities()));
        byte[] nested = new byte[80];
        for (int index = 0; index < 40; index++) {
            // 未知 group 字段仍会消耗解析深度，不能绕过内层递归限制。
            nested[index] = 0x13;
            nested[nested.length - index - 1] = 0x14;
        }
        receive(router, request("alpha", 1).toBuilder().setData(ByteString.copyFrom(nested)).build());
        assertEquals("INVALID_PAYLOAD", last().getStatus());
        receive(router, request("alpha", 1).toBuilder().setData(ByteString.copyFrom(new byte[] {0x14})).build());
        assertEquals("INVALID_PAYLOAD", last().getStatus());
        assertTrue(handled.isEmpty());
    }

    @Test
    void twoConnectionsOwnIndependentModuleSessions() throws Exception {
        var registry = new ServerModuleRegistry<String>();
        registry.register("counter:module", contracts("counter", true), connection -> {
            var count = new AtomicInteger();
            return request -> {
                // 会话内计数器不能被另一名玩家继承或修改。
                connection.send("counter:state", Value.newBuilder().setCount(count.incrementAndGet()).build());
                return true;
            };
        });
        var first = router(registry);
        var second = router(registry);
        receive(first, hello(registry.capabilities()));
        receive(second, hello(registry.capabilities()));
        receive(first, request("counter", 0));
        receive(second, request("counter", 0));
        receive(first, request("counter", 0));
        var counts = new ArrayList<Integer>();
        for (var reply : replies) {
            if (reply.getKind() == Kind.EVENT) {
                counts.add(Value.parseFrom(reply.getData()).getCount());
            }
        }
        assertEquals(List.of(1, 1, 2), counts);
    }

    private ServerModuleRegistry<String> modules(boolean alphaRequired, boolean betaRequired) {
        var registry = new ServerModuleRegistry<String>();
        add(registry, "alpha", alphaRequired);
        add(registry, "beta", betaRequired);
        return registry;
    }

    private void add(ServerModuleRegistry<String> registry, String name, boolean required) {
        registry.register(name + ":module", contracts(name, required), connection -> {
            views.put(name, connection);
            return new ServerModuleSession<>() {
                @Override
                public boolean handle(ServerRequest<String> request) {
                    // 实际解码后记录认证上下文，测试模块不能从 payload 选择玩家或另一个模块。
                    int value = request.payload(Value.getDefaultInstance()).getCount();
                    handled.add(name + ":" + connection.context() + ":" + value);
                    connection.send(name + ":state", Value.newBuilder().setCount(value).build());
                    return value >= 0;
                }

                @Override
                public void close() {
                    closed.add(name);
                }
            };
        });
    }

    private ServerRouter<String> router(ServerModuleRegistry<String> registry) {
        registry.freeze();
        return new ServerRouter<>(registry, player::get, replies::add,
                (message, failure) -> failures.add(message));
    }

    private static Map<String, Capability> contracts(String name, boolean required) {
        return Map.of(name + ":request", capability(name, Direction.CLIENT_TO_SERVER, required),
                name + ":state", capability(name, Direction.SERVER_TO_CLIENT, required));
    }

    private static Capability capability(String name, Direction direction, boolean required) {
        return Capability.newBuilder().setModule(name + ":module").setVersion(1)
                .setDirection(direction).setRequired(required)
                .setPayloadType(Value.getDescriptor().getFullName()).build();
    }

    private static Envelope hello(Map<String, Capability> capabilities) {
        return Envelope.newBuilder().setProtocol(ServerProtocol.VERSION).setKind(Kind.HELLO)
                .putAllCapabilities(capabilities).build();
    }

    private static Envelope request(String name, int value) {
        return Envelope.newBuilder().setProtocol(ServerProtocol.VERSION).setKind(Kind.REQUEST)
                .setModule(name + ":module").setMessage(name + ":request").setVersion(1)
                .setRequest(UUID.randomUUID().toString())
                .setData(Value.newBuilder().setCount(value).build().toByteString()).build();
    }

    private void receive(ServerRouter<String> router, Envelope packet) {
        router.receive(packet.toByteArray());
    }

    private Envelope last() {
        return replies.get(replies.size() - 1);
    }

    private static void assertResult(Envelope result, Envelope request, String status) {
        assertEquals(Kind.RESULT, result.getKind());
        assertEquals(request.getModule(), result.getModule());
        assertEquals(request.getMessage(), result.getMessage());
        assertEquals(request.getVersion(), result.getVersion());
        assertEquals(request.getRequest(), result.getRequest());
        assertEquals(status, result.getStatus());
        assertTrue(result.getData().isEmpty());
    }
}

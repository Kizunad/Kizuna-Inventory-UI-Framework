# 服务端模块与统一路由

> 适用：增加统一路由后的 `0.1.0-SNAPSHOT` 源码版本。Java API 尚未冻结；wire 保持 v2，公共 `.proto` 字段未改变。两端分别安装，接入时固定同一源码版本。

## 安装与职责

`./gradlew test build` 同时构建客户端框架与 `server/build/libs/kizuna-inventory-ui-framework-server-0.1.0-SNAPSHOT.jar`。专用服务器安装后者、Fabric API 和业务服务端 JAR。服务端框架不包含 UI、owo、业务物品、业务存档或测试夹具。

公共生成类及 Protobuf 运行库由每端的框架产物提供。业务子 JAR 只生成自己的 `.proto`；公共协议目录用作 import，不重新打包 `dev.kizuna.inventoryui.protocol.pb` 或另一份 Protobuf。开发时对服务端框架使用 Loom `modImplementation files(...)`，编译期另声明 `compileOnly 'com.google.protobuf:protobuf-java:4.36.2'`。

2026-10-14 源码起，客户端框架 JAR 同时包含内置服务器路由，在 `main` 初始化服务器事件；单机不额外安装专用服务端 JAR。业务客户端 JAR 也需包含逻辑服务端，并登记同一个 `kizuna_inventory_ui_server` 入口。两种环境共用服务端源码，每次启动世界重新建立模块目录，关闭世界清理连接。

公共入口只注册一次 `kizuna_inventory_ui:message`。多个业务模块共享 HELLO／ACCEPT，框架按模块、消息、版本分发，并检查方向及描述符。身份对应的数据访问、权限、事务、幂等和权威状态由模块实现。请求 UUID 不是业务幂等键。

## Fabric 注册入口

业务模组的 `fabric.mod.json` 增加以下片段，保留自身其他字段：

```json
{
  "environment": "server",
  "entrypoints": {
    "kizuna_inventory_ui_server": ["example.ExampleServerModule"]
  },
  "depends": {
    "kizuna_inventory_ui_server": "0.1.0-SNAPSHOT"
  }
}
```

以下入口使用 Protobuf 已有的 `Int32Value` 演示往返；真实业务换成自己的生成类，客户端注册相同 ID、方向、版本和描述符：

```java
package example;

import com.google.protobuf.Int32Value;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol.Capability;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol.Direction;
import dev.kizuna.inventoryui.server.ServerModuleRegistry;
import dev.kizuna.inventoryui.server.fabric.FabricServerModule;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.Map;

public final class ExampleServerModule implements FabricServerModule {
    private static final String MODULE = "example:echo";
    private static final String REQUEST = "example:request";
    private static final String STATE = "example:state";

    @Override
    public void register(MinecraftServer server, ServerModuleRegistry<ServerPlayerEntity> registry) {
        // 每条连接创建独立会话，服务级依赖可在此创建并由闭包共享。
        registry.register(MODULE, Map.of(
                REQUEST, capability(Direction.CLIENT_TO_SERVER),
                STATE, capability(Direction.SERVER_TO_CLIENT)), connection -> request -> {
            // 当前玩家来自认证连接，不能由请求载荷指定。
            if (!connection.context().isAlive()) {
                return false;
            }
            connection.send(STATE, request.payload(Int32Value.getDefaultInstance()));
            return true;
        });
    }

    private static Capability capability(Direction direction) {
        return Capability.newBuilder().setModule(MODULE).setVersion(1)
                .setDirection(direction).setRequired(true)
                .setPayloadType(Int32Value.getDescriptor().getFullName()).build();
    }
}
```

入口在 `SERVER_STARTING` 执行，全部入口执行后冻结目录。重复模块、重复消息、错误归属或非法契约直接阻止启动，不按加载顺序覆盖。消息和模块必须使用同一命名空间；同一命名空间可有多个模块，但消息 ID 仍须唯一。

## 协商与会话

核心 API 位于 `dev.kizuna.inventoryui.server`：`ServerModuleRegistry<C>`、`ServerRouter<C>`、`ServerConnection<C>`、`ServerRequest<C>`、`ServerModuleSession<C>`。核心不依赖 Minecraft；`.server.fabric` 提供 Fabric 适配，认证上下文 `C` 为 `ServerPlayerEntity`。

每连接只接受一次 HELLO。两端的 `required` 分别表示自身不可省略的能力：服务端必需而客户端缺少时为 `REQUIRED_CAPABILITY_MISSING`；客户端必需而服务端缺模块为 `MODULE_MISSING`，缺消息为 `UNKNOWN_MESSAGE`，不兼容为 `PROTOCOL_MISMATCH`。错误带失败模块和消息 ID。可选能力只取双方兼容交集。

只有协商成功的模块才创建会话；可选模块全部缺席时不调用工厂。部分可选消息未接受时，使用 `connection.supports(message)` 查询。工厂只构造会话，不在 ACCEPT 之前发送事件或读取上下文。

每名玩家、每个模块分别持有会话。`connection.id()` 是连接 UUID，重连后变化，不是玩家身份。`connection.context()` 每次读取当前玩家，重生后不会持有旧实体。

`handle(request)` 在服务器主线程执行。`request.payload(Generated.getDefaultInstance())` 检查描述符并有界解码，领域约束仍由模块校验。返回 true／false 后框架自动发送关联的 ACCEPTED／REJECTED RESULT，数据为空；详情与状态用 `connection.send(message, generatedMessage)` 发送 EVENT。只允许本模块已协商的 S2C 类型。

模块可保存连接视图供服务器事件主动推送，但必须切回主线程。断线后发送被拒绝、`supports` 返回 false。断线、停服及部分初始化失败都会调用已创建会话的 `close()`，单个关闭异常不会阻止其他模块清理。不要在 `close()` 发消息；普通请求异常只拒绝该请求，其他模块继续服务。

## 限制与其他宿主

Fabric 每连接每秒最多处理 20 个包，所有模块共享预算；完整 C2S 上限 16 KiB，S2C 上限 256 KiB，信封与载荷分别限制 32 层递归。超限 C2S 不执行，业务仍需提供超时重同步与幂等。当前源码支持专用服务器和内置服务器；业务仍须独立适配自己的初始化与存档。

非 Fabric Java 宿主可冻结注册表后，为每条认证连接创建 `ServerRouter<C>(registry, contextSupplier, outbound, diagnostics)`，调用 `receive(byte[])`，断线时 `close()`。构造、接收、发送与关闭在同一宿主线程执行，上下文由认证连接提供。其他语言按 [Protobuf 通信契约](Server-Communication.md) 实现统一宿主。

## 从业务独占通道迁移

1. 安装服务端框架 JAR，为业务清单增加依赖与模块入口。
2. 移除业务中的公共通道接收器、HELLO／ACCEPT 及通用 RESULT 处理。
3. 会话改为 `ServerModuleSession`，保留业务 `.proto`、权限、事务、幂等和存档。
4. 公共生成类和运行库改由框架提供，只打包业务生成类。
5. 同时加载两个模块，验收协商、请求、状态、缺模块、断线与重连。

本次改变 Java 接入与服务器安装依赖，不改变 wire v2 字段，也不代表业务存档迁移或 API 稳定承诺。

## 验证记录

2026-10-10 本地 Java 17 验证：18 项服务端路由测试通过，覆盖双模块分发、双连接隔离、必需／可选能力、重复注册、契约不匹配、坏包与递归边界、越模块输出、异常隔离及资源回收。客户端原有测试继续通过。

Linux Xvfb 下真实 Fabric 客户端和专用服务器完成两阶段联调：同一连接同时运行库存业务与独立探针模块，完整重启后再次协商与分别处理请求，两个阶段均验证主线程会话回收。修复了 Fabric 断线回调可能来自网络线程的问题；适配器现在将关闭操作提交到服务器线程。此记录不代表 Windows Native 或集成服务器已验证。

2026-10-14：补充客户端产物中的内置服务器路由。真实 Fabric 单机已验证同连接多个业务模块协商、请求和状态推送，退出后重新打开同一世界获得新会话；独立服务器联调和原 wire 验收也通过。测试使用 Linux Xvfb，未在本轮启动 Windows Native。

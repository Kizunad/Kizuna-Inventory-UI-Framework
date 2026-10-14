package dev.kizuna.inventoryui.server;

/** 单个模块在一条已协商连接上的业务会话；所有调用均在宿主线程串行执行。 */
public interface ServerModuleSession<C> extends AutoCloseable {
    /** 返回是否接受请求；框架自动回传关联 RESULT，业务详情由模块发送 EVENT。 */
    boolean handle(ServerRequest<C> request);

    /** 断线、服务器关闭或会话初始化失败时释放资源；不在此处发送网络消息。 */
    @Override
    default void close() {}
}

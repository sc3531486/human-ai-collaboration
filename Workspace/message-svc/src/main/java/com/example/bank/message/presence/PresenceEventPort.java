package com.example.bank.message.presence;

public interface PresenceEventPort {

    /**
     * 上报坐席连接断开事实。
     *
     * @throws PresenceEventDeliveryException 下游（business-svc）不可达或返回失败；
     *                                       调用方须显式处置，端口内部不允许静默降级
     */
    void disconnected(String agentId, String reason, String source, String channel);
}

package com.example.bank.message.presence;

/**
 * 坐席掉线事件上报 business-svc 失败。
 *
 * <p>适配器不再把下游失败吞成 warn 日志：失败必须以带原因的异常浮出端口边界，
 * 由调用方（事件监听器）按契约决定最终处置（记日志、不重试），而不是在适配器内部被静默消化。</p>
 */
public class PresenceEventDeliveryException extends RuntimeException {

    private final String agentId;
    private final String endpoint;

    public PresenceEventDeliveryException(String agentId, String endpoint, Throwable cause) {
        super("坐席掉线事件上报失败：agentId=" + agentId + "，endpoint=" + endpoint + "，原因=" + cause.getMessage(), cause);
        this.agentId = agentId;
        this.endpoint = endpoint;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getEndpoint() {
        return endpoint;
    }
}

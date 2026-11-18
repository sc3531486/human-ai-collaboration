package com.example.bank.business.port;

import java.util.Map;

/**
 * 业务服务的消息出口。
 *
 * <p>业务服务是唯一的实时消息编排入口：其他领域服务只把业务事件回调给
 * business-svc，不直接依赖 message-svc。message-svc 只负责 STOMP/WebSocket
 * 的连接管理与投递。</p>
 */
public interface MessagePort {

    Map<String, Object> token(String agentId, String customerId);

    void publishAgent(String agentId, Map<String, Object> event);

    void publishCustomer(String customerId, Map<String, Object> event);
}

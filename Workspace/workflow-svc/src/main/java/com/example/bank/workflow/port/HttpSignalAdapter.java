package com.example.bank.workflow.port;

import com.example.bank.workflow.events.NodeNotificationOutbox;
import org.springframework.stereotype.Component;
import java.util.Map;

/** 流程只声明事件目标，消息出站经持久化发件箱交给 business 编排。 */
@Component
public class HttpSignalAdapter implements SignalPort {
    private final NodeNotificationOutbox notifications;
    public HttpSignalAdapter(NodeNotificationOutbox notifications) { this.notifications=notifications; }
    public void notifyAgent(String agentId,Map<String,Object> event) { notifications.enqueue("agent",agentId,event); }
    public void notifyCustomer(String customerId,Map<String,Object> event) { notifications.enqueue("customer",customerId,event); }
}

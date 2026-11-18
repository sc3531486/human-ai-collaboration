package com.example.bank.workflow.port;

import java.util.Map;

/** workflow-svc 回调 business-svc 的领域事件出口。 */
public interface BusinessEventPort {

    void publishAgent(String agentId, Map<String, Object> event);

    void publishCustomer(String customerId, Map<String, Object> event);
}

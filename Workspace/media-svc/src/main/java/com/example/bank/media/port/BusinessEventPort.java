package com.example.bank.media.port;

import java.util.Map;

/** media-svc 回调 business-svc 的媒体领域事件出口。 */
public interface BusinessEventPort {

    void publishAgent(String agentId, Map<String, Object> event);

    void publishCustomer(String customerId, Map<String, Object> event);
}

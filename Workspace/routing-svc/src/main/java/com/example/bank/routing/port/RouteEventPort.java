package com.example.bank.routing.port;

import java.util.Map;

/** routing-svc 到 business-svc 的路由结果事件出口。 */
public interface RouteEventPort {

    void publishToBusiness(String targetAgentId, Map<String, Object> event);
}

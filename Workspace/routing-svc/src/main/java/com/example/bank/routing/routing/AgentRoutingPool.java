package com.example.bank.routing.routing;

import com.example.bank.routing.routing.AgentRegistry;
import com.example.bank.routing.routing.RoutingResource;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 人工坐席资源池：直接复用 {@link AgentRegistry} 的匹配与占用逻辑，不含额外策略。 */
@Component
public class AgentRoutingPool implements RoutingPool {

    private final AgentRegistry agents;

    public AgentRoutingPool(AgentRegistry agents) { this.agents = agents; }

    public String handlerType() { return HandlerType.HUMAN; }

    public RoutingResource match(String skillGroup, Set<String> excluded) {
        return agents.match(skillGroup, excluded);
    }

    public RoutingResource find(String id) {
        AgentRegistry.Agent agent = id == null ? null : agents.get(id);
        return agent != null && "ONLINE".equals(agent.status) ? agent : null;
    }

    public void acquire(String id) { agents.addServing(id, 1); }

    public void release(String id) { agents.addServing(id, -1); }
}

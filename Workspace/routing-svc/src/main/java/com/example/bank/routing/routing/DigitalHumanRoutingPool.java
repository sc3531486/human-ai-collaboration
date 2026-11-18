package com.example.bank.routing.routing;

import com.example.bank.routing.routing.DigitalHumanRegistry;
import com.example.bank.routing.routing.RoutingResource;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 数字人资源池：匹配"空闲数字人"，与坐席池共用同一套技能组 / 最闲优先判定。 */
@Component
public class DigitalHumanRoutingPool implements RoutingPool {

    private final DigitalHumanRegistry digitalHumans;

    public DigitalHumanRoutingPool(DigitalHumanRegistry digitalHumans) { this.digitalHumans = digitalHumans; }

    public String handlerType() { return HandlerType.DIGITAL_HUMAN; }

    public RoutingResource match(String skillGroup, Set<String> excluded) {
        return digitalHumans.match(skillGroup, excluded);
    }

    public RoutingResource find(String id) {
        DigitalHumanRegistry.DigitalHuman digitalHuman = id == null ? null : digitalHumans.get(id);
        return digitalHuman != null && "ONLINE".equals(digitalHuman.status) ? digitalHuman : null;
    }

    public void acquire(String id) { digitalHumans.addServing(id, 1); }

    public void release(String id) { digitalHumans.addServing(id, -1); }
}

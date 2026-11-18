package com.example.bank.routing.routing;

import java.util.Set;

/**
 * 接待方资源池：队列路由服务通过本接口统一处理人工坐席与数字人，
 * 路由主循环不含任何类别分支，新增接待方类别只需再提供一个实现。
 */
public interface RoutingPool {

    /** 本池对应的接待方类别，取值见 {@link HandlerType}。 */
    String handlerType();

    /** 按技能组匹配可用资源；excluded 中的资源本轮不参与。 */
    RoutingResource match(String skillGroup, Set<String> excluded);

    /** 按标识取可用资源；不可用（下线 / 忙）返回 null，用于交还时复用原接待方。 */
    RoutingResource find(String id);

    /** 受理：占用一个资源位。 */
    void acquire(String id);

    /** 释放：归还一个资源位。 */
    void release(String id);
}

package com.example.bank.routing.routing;

import java.util.Set;

/**
 * 可被队列路由调度的接待资源：人工坐席或数字人。
 *
 * <p>资源池的匹配语义（{@code ONLINE} 优先、技能命中、服务量少者优先）只依赖本接口，
 * 因此路由主循环不含任何接待方类别的分支。</p>
 */
public interface RoutingResource {

    /** 接待方唯一标识：坐席号或数字人编号。 */
    String id();

    /** 技能组集合，与客户等级识别输出的技能组对齐。 */
    Set<String> skills();

    /** ONLINE / BUSY / OFFLINE；仅 ONLINE 参与路由匹配。 */
    String status();

    /** 当前并发服务量，用于"最闲优先"排序。 */
    int serving();

    /** 最近活跃时间戳，用于同服务量时"最早空闲优先"。 */
    long lastActive();
}

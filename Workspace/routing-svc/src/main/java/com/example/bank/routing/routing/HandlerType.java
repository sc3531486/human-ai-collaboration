package com.example.bank.routing.routing;

/** 接待方类别：队列路由服务按此值把工单分派给对应资源池。 */
public final class HandlerType {

    /** 人工坐席。 */
    public static final String HUMAN = "HUMAN";

    /** 数字人。 */
    public static final String DIGITAL_HUMAN = "DIGITAL_HUMAN";

    private HandlerType() { }
}

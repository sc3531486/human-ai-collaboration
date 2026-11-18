package com.example.bank.business.contract;

/** 接待方类别。取值必须与 routing-svc 的 {@code HandlerType} 一致。 */
public final class HandlerType {

    /** 人工坐席。 */
    public static final String HUMAN = "HUMAN";

    /** 数字人。 */
    public static final String DIGITAL_HUMAN = "DIGITAL_HUMAN";

    private HandlerType() { }
}

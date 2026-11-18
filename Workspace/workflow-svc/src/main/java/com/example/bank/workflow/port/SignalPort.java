package com.example.bank.workflow.port;

import java.util.Map;

/**
 * 下行推送端口 —— 由<b>本服务</b>定义，signaling-svc 实现。
 *
 * <p>本服务只声明"把这条事件给某个坐席/某个客户"，走 Centrifugo 还是别的通道是信令服务的实现细节。
 * 频道命名（{@code agent:<id>} / {@code customer:<id>}）因此也留在信令服务，这里不出现频道概念。</p>
 */
public interface SignalPort {

    /** 投递到坐席个人频道。 */
    void notifyAgent(String agentId, Map<String, Object> event);

    /** 投递到客户个人频道。 */
    void notifyCustomer(String customerId, Map<String, Object> event);
}

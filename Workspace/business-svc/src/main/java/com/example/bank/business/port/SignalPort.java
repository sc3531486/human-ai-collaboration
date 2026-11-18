package com.example.bank.business.port;

import java.util.Map;

/**
 * 出站端口：信令令牌签发。
 *
 * <p>由消息推送服务实现。<b>令牌密钥只在对端</b>：本服务对外提供
 * {@code /api/centrifugo/token} 时向这里换取，全程不接触密钥 ——
 * 密钥散落到每个需要发令牌的服务里，轮换时就得同时改多处。</p>
 */
public interface SignalPort {

    /**
     * 签发连接令牌。入参二选一：坐席传 {@code agentId}，客户传 {@code customerId}。
     *
     * <p>返回 {@code {token, url, channel, subject, role}} 原样交给浏览器 ——
     * 这些字段的语义由信令服务定义，本服务不解读、不加工。两边都为空时对端返回 400，
     * 原样上抛。</p>
     */
    Map<String, Object> token(String agentId, String customerId);

    /** 向坐席个人频道投递事件；HTTP 失败如实上抛，由产品事件 outbox 重试。 */
    void publishAgent(String agentId, Map<String, Object> event);

    /** 向客户个人频道投递事件；HTTP 失败如实上抛。 */
    void publishCustomer(String customerId, Map<String, Object> event);
}

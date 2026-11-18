package com.example.bank.signaling.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 出站 HTTP 客户端的超时配置。
 *
 * <p>{@link RestTemplate} 默认<b>不设任何超时</b>，Centrifugo 或业务服务无响应时会把调用线程永久挂住。
 * 信令服务的调用方是 Spring 的调度线程，一旦挂住，后续所有 presence 扫描都会停摆 —— 因此超时必须显式设置。</p>
 */
@Configuration
public class OutboundConfig {

    /** 连接层事件上报的超时，必须短于扫描节拍，避免上一次扫描还没结束下一次就到期。 */
    @Bean
    public RestTemplate presenceEventRestTemplate(
            @Value("${presence.connect-timeout-ms:1000}") int connectTimeoutMs,
            @Value("${presence.read-timeout-ms:2000}") int readTimeoutMs) {
        // 启动期强校验：0 会被框架当成"无超时"（负值则由框架直接拒绝），
        // 等于悄悄取消了这里要防的挂死保护；配置写错必须在启动时以明确原因失败。
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalStateException("presence 出站超时必须为正数（0 等于无超时，负值不合法），当前 connect="
                    + connectTimeoutMs + ", read=" + readTimeoutMs);
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }
}

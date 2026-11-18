package com.example.bank.media.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 出站 HTTP 客户端。
 *
 * <p>超时必须显式设置：默认值是无超时，一个挂死的下游会把开房请求永久挂住，
 * 而坐席侧正在等这次接听的结果 —— 这类故障比"调用失败"更难排查。</p>
 */
@Configuration
public class OutboundConfig {

    @Bean
    public RestTemplate outboundRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(2))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
    }
}

package com.example.bank.business.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 出站 HTTP 客户端。
 *
 * <p>读超时给到 15 秒，比路由/媒体服务的出站超时宽：本服务唯一会发起长调用的是
 * {@code /process/start} —— 流程启动会同步跑完 BPMN 的委托节点，那些节点再各自
 * 出站到路由、媒体、存证服务。这条链路的耗时是它们的总和，卡在 5 秒会让
 * 冷启动的第一笔进线稳定超时。</p>
 *
 * <p>超时必须显式设置：默认值是无超时，一个挂死的下游会把进线请求永久挂住，
 * 而客户正对着一个没有任何反馈的页面。</p>
 */
@Configuration
public class OutboundConfig {

    @Bean
    public RestTemplate outboundRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(2))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();
    }
}

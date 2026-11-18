package com.example.bank.workflow.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 出站 HTTP 客户端。
 *
 * <p>超时必须显式设置：默认值是无超时。本服务的出站调用跑在 <b>Flowable 的 delegate 事务里</b>，
 * 一个挂死的下游不只是慢 —— 它会一直占着流程事务，把该流程实例的后续所有推进一并堵住。</p>
 *
 * <p>读超时取 5s：排队路由是同步等待坐席派单结果的动作，坐席端的接听按钮等着这次返回。
 * 再长不如直接失败让客户重试。</p>
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

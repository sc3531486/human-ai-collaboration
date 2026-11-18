package com.example.bank.message.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class OutboundConfig {

    @Bean
    @Qualifier("presenceEventRestTemplate")
    public RestTemplate presenceEventRestTemplate(MessageProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getPresence().getConnectTimeoutMs());
        factory.setReadTimeout(properties.getPresence().getReadTimeoutMs());
        return new RestTemplate(factory);
    }
}

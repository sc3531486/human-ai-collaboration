package com.example.bank.message.config;

import com.example.bank.message.auth.StompAuthChannelInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(MessageProperties.class)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final MessageProperties properties;
    private final StompAuthChannelInterceptor authInterceptor;

    public WebSocketConfig(MessageProperties properties, StompAuthChannelInterceptor authInterceptor) {
        this.properties = properties;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(properties.getWebsocketEndpoint())
                .setAllowedOriginPatterns(origins(properties.getAllowedOriginPatterns()));
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    /**
     * 解析允许的跨域来源。
     *
     * <p>配置缺失或解析不出任何来源时直接失败：回退成通配符 {@code "*"} 等于把
     * "配置错了"悄悄变成"对任意来源开放"，属于必须 fail-fast 的静默降级。</p>
     */
    static String[] origins(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("message.allowed-origin-patterns未配置：拒绝以通配符\"*\"兜底");
        }
        String[] origins = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toArray(String[]::new);
        if (origins.length == 0) {
            throw new IllegalStateException("message.allowed-origin-patterns未解析出任何来源：" + value);
        }
        return origins;
    }
}

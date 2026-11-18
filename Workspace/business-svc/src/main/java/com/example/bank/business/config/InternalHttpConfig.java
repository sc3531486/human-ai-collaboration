package com.example.bank.business.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 本机服务间 HTTP 入口；浏览器仍使用 HTTPS，内部端口仅绑定回环地址。 */
@Configuration
public class InternalHttpConfig {
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> internalHttpConnector(
            @Value("${business.internal-http-port:8082}") int port) {
        return factory -> {
            if (port <= 0) return;
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(port);
            connector.setScheme("http");
            connector.setSecure(false);
            connector.setProperty("address", "127.0.0.1");
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}

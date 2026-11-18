package com.example.bank.signaling;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 信令服务：连接层的事实来源。
 *
 * <p>唯一持有 Centrifugo 通道与订阅令牌签发权，并把"连接消失"翻译成
 * {@code AGENT_DISCONNECTED} 抛给业务逻辑服务登记。</p>
 */
@SpringBootApplication
@EnableScheduling
public class SignalingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SignalingServiceApplication.class, args);
    }
}

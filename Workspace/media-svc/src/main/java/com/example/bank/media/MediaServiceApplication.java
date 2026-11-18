package com.example.bank.media;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 多媒体服务：房间分配与开房/关房事件。
 *
 * <p>媒体流本身是 PeerJS P2P，不经服务端；本服务负责房间/用户/录制会话登记，
 * 事件经 outbox 交给 business 决定推送。</p>
 */
@SpringBootApplication
@EnableScheduling
public class MediaServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MediaServiceApplication.class, args);
    }
}

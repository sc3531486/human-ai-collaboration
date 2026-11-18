package com.example.bank.business;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 业务逻辑服务：对外唯一 REST 入口。
 *
 * <p>客户进线、余额业务、双录存证、坐席状态登记与心跳都在这里；路由、流程、信令、多媒体
 * 由本服务经 HTTP 调用，不共享进程。</p>
 */
@SpringBootApplication
@EnableScheduling
public class BusinessServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BusinessServiceApplication.class, args);
    }
}

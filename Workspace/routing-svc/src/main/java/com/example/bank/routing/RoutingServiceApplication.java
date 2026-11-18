package com.example.bank.routing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 排队路由服务：坐席与数字人双资源池的调度方。
 *
 * <p>坐席状态变更、工单与资源属性落库；启动时恢复活动工单与资源，
 * 终态工单保留在表里供审计与绩效取数。</p>
 */
@SpringBootApplication
@EnableScheduling
public class RoutingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RoutingServiceApplication.class, args);
    }
}

package com.example.bank.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 流程引擎服务：唯一持有 Flowable 与 BPMN 资源的一方。
 *
 * <p>BPMN 的 delegate 需要外部能力（排队路由、开房、存证、播报）时，经本服务定义的出站端口
 * 走 HTTP，不直接依赖对方的实现。</p>
 */
@SpringBootApplication
public class WorkflowServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkflowServiceApplication.class, args);
    }
}

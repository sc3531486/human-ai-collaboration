package com.example.bank.routing.port;

import java.util.Map;

/**
 * 产品事件出口：路由只声明"发生了什么"，由 business 决定推给谁、怎么推。
 *
 * <p>不再直接调用信令服务；事件先落 outbox，再异步投递到
 * {@code /internal/events/routing}，投递失败保留重试。</p>
 */
public interface ProductEventPublisher {

    void publish(String type, Map<String, Object> payload);

    /** 单元测试/无库环境的空实现。 */
    ProductEventPublisher NOOP = new ProductEventPublisher() {
        @Override
        public void publish(String type, Map<String, Object> payload) {
            // 不持久化时不影响路由主流程
        }
    };
}

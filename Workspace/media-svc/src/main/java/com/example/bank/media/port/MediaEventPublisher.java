package com.example.bank.media.port;

import java.util.Map;

/**
 * 媒体产品事件出口：只声明"房间发生了什么"，由 business 决定推给谁。
 *
 * <p>不再直接调用信令服务；事件先落 outbox，再异步投递到
 * {@code /internal/events/media}，失败保留重试。</p>
 */
public interface MediaEventPublisher {

    void publish(String type, Map<String, Object> payload);

    MediaEventPublisher NOOP = new MediaEventPublisher() {
        @Override
        public void publish(String type, Map<String, Object> payload) {
            // 无持久化环境不影响媒体主流程
        }
    };
}

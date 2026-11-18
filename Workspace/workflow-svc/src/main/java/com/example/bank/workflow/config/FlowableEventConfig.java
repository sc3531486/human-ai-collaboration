package com.example.bank.workflow.config;

import com.example.bank.workflow.events.NodeAdvanceListener;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEventType;
import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把节点推进监听器挂到流程引擎上。
 *
 * <p>用 {@link ProcessEngineConfigurationConfigurer} 而不是在 BPMN 里逐个节点声明
 * {@code executionListener}：后者要求每个新流程、每个新节点都记得加，
 * 漏一个就是静默失去实时推送。挂在引擎上则一次生效、后续流程自动覆盖。</p>
 *
 * <p>Spring Boot 通过 {@code BaseEngineConfigurationWithConfigurers} 收集本类型的 Bean
 * 并在引擎初始化前逐个调用 {@code configure}。</p>
 *
 * <p>{@code setTypedEventListeners} 是纯赋值（反汇编确认：仅 {@code putfield}，
 * 不触碰 {@code eventListeners} 字段），因此不会覆盖引擎自带的监听器。
 * 但它整体替换 {@code typedEventListeners}，将来若有第二处也注册类型化监听器，
 * 后注册的会盖掉先注册的 —— 目前只有本处，故不做合并。</p>
 */
@Configuration
public class FlowableEventConfig {

    private static final Logger log = LoggerFactory.getLogger(FlowableEventConfig.class);

    @Bean
    public ProcessEngineConfigurationConfigurer nodeAdvanceConfigurer(NodeAdvanceListener listener, com.example.bank.workflow.events.ProcessOutcomeOutbox outbox) {
        return configuration -> {
            Map<String, List<FlowableEventListener>> typed = new LinkedHashMap<String, List<FlowableEventListener>>();
            for (FlowableEventType type : listener.getTypes()) {
                typed.put(type.name(), new java.util.ArrayList<>(Collections.singletonList(listener)));
            }
            for (FlowableEventType type : outbox.getTypes()) typed.computeIfAbsent(type.name(), key -> new java.util.ArrayList<>()).add(outbox);
            configuration.setTypedEventListeners(typed);
            log.info("【流程事件】节点推进监听器已挂载，订阅事件={}", typed.keySet());
        };
    }
}

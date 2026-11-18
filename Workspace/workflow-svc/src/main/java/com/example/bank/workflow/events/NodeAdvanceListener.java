package com.example.bank.workflow.events;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEventType;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * 流程推进时把节点状态推给客户端，取代原先 {@code NodeEventService} 里 700ms 一轮的扫描。
 *
 * <h3>为什么挂在引擎上而不是逐个节点声明</h3>
 * <p>本监听器由 {@code FlowableEngineEventType} 触发，挂在引擎的事件派发器上，
 * 因此 <b>BPMN 里一行都不用加</b>：以后新增流程、新增节点，推送自动生效。
 * 若改成在每个节点的 {@code <extensionElements>} 里声明 {@code executionListener}，
 * 新流程漏声明就会静默失去推送 —— 页面看着正常，只是永远收不到实时事件，
 * 和 {@code HumanQueueDelegate} 里写死 {@code HANDLED_NODE} 是同一类病。</p>
 *
 * <h3>提交后再读快照</h3>
 * <p>{@link #isFireOnTransactionLifecycleEvent()} 为真且 {@link #getOnTransaction()} 返回
 * {@code COMMITTED}，Flowable 会把这个监听器注册成事务提交后的回调
 * （{@code FlowableEventSupport} 反汇编确认：逐个 {@code equalsIgnoreCase} 比对
 * {@code TransactionState} 的名字，<b>对不上会静默落到 ROLLED_BACK 分支</b>，所以这里必须大写）。
 * 提交前读快照拿到的是旧状态，先推送再提交会让客户端拿着通知回来查到脏数据。</p>
 *
 * <h3>失败不影响业务</h3>
 * <p>{@link #isFailOnException()} 为假：事务此时已提交，推送失败抛异常既回滚不了业务，
 * 又会污染调用线程。信令服务不可达只是丢一次实时通知，客户端重连后拉一次
 * {@code GET /process/{id}/state} 即可校准。</p>
 */
@Component
public class NodeAdvanceListener implements FlowableEventListener {

    /**
     * 只订阅会改变"当前停在哪个节点"的事件。
     *
     * <p>不订阅 {@code VARIABLE_*}：流程变量只承载网关判定参数，它的变化若伴随节点变化，
     * 已由 {@code ACTIVITY_COMPLETED} 覆盖；单独订阅会让每个变量写一次就多读一遍快照。</p>
     */
    private static final List<FlowableEventType> TYPES = Arrays.<FlowableEventType>asList(
            FlowableEngineEventType.PROCESS_STARTED,
            FlowableEngineEventType.ACTIVITY_COMPLETED,
            FlowableEngineEventType.TASK_CREATED,
            FlowableEngineEventType.TASK_COMPLETED,
            FlowableEngineEventType.PROCESS_COMPLETED,
            FlowableEngineEventType.PROCESS_COMPLETED_WITH_TERMINATE_END_EVENT,
            FlowableEngineEventType.PROCESS_CANCELLED);

    private final NodeEventService events;

    /**
     * 延迟解析 {@code NodeEventService}：本监听器与业务链之间不能有构造期强依赖。
     *
     * <p>{@code FlowableEventConfig} 用 {@code ProcessEngineConfigurationConfigurer} 把本监听器挂上引擎，
     * 而该类型的 Bean 必须在 {@code RuntimeService} 之前就绪。若这里要求构造期拿到真实的
     * {@code NodeEventService}，链条就是
     * {@code RuntimeService → nodeAdvanceConfigurer → NodeAdvanceListener → NodeEventService
     * → SopProcessService → RuntimeService} —— 引擎配置阶段被迫先造出整条业务链，形成循环，
     * Spring 默认直接拒绝启动。</p>
     *
     * <p>更要紧的是这个循环<strong>不是每次都会报</strong>：能否解开取决于 bean 的扫描顺序，
     * 实测同一份代码重编译后（class 文件时间戳变化）才暴露出来。所以不能靠"上次启动是好的"
     * 判断它不存在。</p>
     *
     * <p>延迟在这里不是权宜之计，而是事实：配置阶段只会调用本类的 {@link #getTypes()}，
     * 真正转发事件发生在流程推进时，那必然在引擎启动之后。代理只是把"用到才解析"写明。</p>
     */
    public NodeAdvanceListener(@Lazy NodeEventService events) { this.events = events; }

    @Override
    public void onEvent(FlowableEvent event) {
        if (!(event instanceof FlowableEngineEvent)) return;
        String processId = ((FlowableEngineEvent) event).getProcessInstanceId();
        if (processId == null || processId.isEmpty()) return;
        if (isTerminal(event.getType())) events.onProcessEnded(processId);
        else events.onNodeAdvance(processId);
    }

    /** 流程已终结：推最后一次，并转入存证归档的等待队列（见 {@code NodeEventService#onProcessEnded}）。 */
    private static boolean isTerminal(FlowableEventType type) {
        return type == FlowableEngineEventType.PROCESS_COMPLETED
                || type == FlowableEngineEventType.PROCESS_COMPLETED_WITH_TERMINATE_END_EVENT
                || type == FlowableEngineEventType.PROCESS_CANCELLED;
    }

    @Override public boolean isFailOnException() { return false; }

    @Override public boolean isFireOnTransactionLifecycleEvent() { return true; }
    @Override public String getOnTransaction() { return "COMMITTED"; }

    @Override public Collection<? extends FlowableEventType> getTypes() { return TYPES; }
}

package com.example.bank.workflow.events;

import com.example.bank.workflow.port.ArchiveUnavailableException;
import com.example.bank.workflow.port.BusinessEventPort;
import com.example.bank.workflow.port.SignalPort;
import com.example.bank.workflow.sop.SopProcessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点状态推送：快照变化时才发布，<b>投递细节交给信令服务</b>。
 *
 * <p>本服务不维护任何客户端连接，也不知道频道名 —— 只声明"这条事件给哪个坐席、哪个客户"。
 * 连接管理、断线重连与投递由信令服务承担；推送通道不回放历史消息，
 * 因此订阅方连上后必须先拉一次 HTTP 快照校准，之后的增量由本服务推送。</p>
 *
 * <h3>驱动方式：引擎事件，不是轮询</h3>
 * <p>节点状态由 {@link NodeAdvanceListener} 在流程推进时回调驱动
 * （{@link #onNodeAdvance}/{@link #onProcessEnded}），不再有定时扫描。
 * 原先 {@code @Scheduled(fixedDelay = 700)} 的做法有两个问题：平均 350ms、最坏 700ms 的推送延迟；
 * 以及每一轮都对每个跟踪中的流程<b>无条件</b>调一次 {@code SopProcessService#snapshot} ——
 * 该调用对已完成的流程会跨服务发一次 HTTP，于是「状态早已稳定」的流程仍会每 700ms
 * 白发一次请求，30 分钟 TTL 内累计约 2571 次。</p>
 *
 * <h3>为什么仍留一个定时任务</h3>
 * <p>只剩存证归档一处（{@link #refreshArchive}），因为它是<b>外部异步产出</b>：
 * 归档由 business-svc 在流程结束后另行生成，Flowable 无从知道它何时变化，事件驱动覆盖不到。
 * 因此这里保留等待队列，但范围窄得多 —— 只跟踪刚刚结束的流程、间隔 5s、拿到终态即出队，
 * 而不是把所有在跑的流程每 700ms 扫一遍。</p>
 */
@Service
@EnableScheduling
public class NodeEventService {

    private static final Logger log = LoggerFactory.getLogger(NodeEventService.class);

    /** 节点状态事件类型，与坐席端 AgentRealtime.dispatch 的分发契约一致。 */
    private static final String NODE_STATE = "NODE_STATE";

    /** 交接完成的释放事件：一个流程实例只向坐席投递一次，之后不再推送该流程的 NODE_STATE。 */
    private static final String AGENT_RELEASED = "AGENT_RELEASED";

    /** 存证归档的轮询间隔。归档在流程结束后数秒到数十秒内产出，5s 足够且不再压垮下游。 */
    private static final long ARCHIVE_POLL_MS = 5000L;

    /** 归档等待上限：超过仍未拿到终态即放弃跟踪，避免归档服务长期不可用时队列无限增长。 */
    private static final long ARCHIVE_GIVE_UP_MS = 30 * 60 * 1000L;

    private final SopProcessService sop;
    private final SignalPort signals;
    private final BusinessEventPort businessEvents;
    private final String epoch = UUID.randomUUID().toString();
    private final Map<String, Channel> channels = new ConcurrentHashMap<String, Channel>();

    /** processId → 放弃等待的时刻。只登记已结束的流程，见类注释。 */
    private final Map<String, Long> awaitingArchive = new ConcurrentHashMap<String, Long>();

    private static class Channel {
        Map<String, Object> source;
        Map<String, Object> event;
        long sequence;
        boolean released;
    }

    public NodeEventService(SopProcessService sop, SignalPort signals, BusinessEventPort businessEvents) {
        this.sop = sop; this.signals = signals; this.businessEvents = businessEvents;
    }

    /** 取当前节点快照；内容有变化时向接待方与客户频道各发布一次，返回最新事件。 */
    public Map<String, Object> snapshot(String processId) {
        Channel channel = channels.computeIfAbsent(processId, key -> new Channel());
        synchronized (channel) {
            Map<String, Object> source = sop.snapshot(processId);
            if (source.equals(channel.source)) return new LinkedHashMap<String, Object>(channel.event);
            channel.source = source;
            Map<String, Object> event = new LinkedHashMap<String, Object>(source);
            event.put("domain", "process");
            event.put("type", NODE_STATE);
            long now = System.currentTimeMillis();
            event.put("at", now);
            event.put("sequence", ++channel.sequence);
            event.put("epoch", epoch);
            event.put("eventId", epoch + ":" + processId + ":" + channel.sequence);
            channel.event = event;
            publish(channel, event);
            return new LinkedHashMap<String, Object>(event);
        }
    }

    /**
     * 流程推进到某个节点之后由引擎回调。
     *
     * <p>失败只记日志：此时业务事务已提交，抛出去既回滚不了业务，又会污染调用线程
     * （{@code NodeAdvanceListener} 已声明 {@code isFailOnException=false}，
     * 这里再兜一层是为了给出带 processId 的日志）。</p>
     */
    public void onNodeAdvance(String processId) {
        try {
            snapshot(processId);
        } catch (RuntimeException error) {
            log.warn("节点推进后刷新快照失败，processId={}，cause={}", processId, error.getMessage(), error);
        }
    }

    /**
     * 流程终结（正常完成或作废）后由引擎回调：推最后一次，并转入存证归档等待。
     *
     * <p>作废的流程同样入队 —— 已双录的分段仍需归档，与流程是否走完无关。</p>
     */
    public void onProcessEnded(String processId) {
        onNodeAdvance(processId);
        awaitingArchive.put(processId, System.currentTimeMillis() + ARCHIVE_GIVE_UP_MS);
    }

    /**
     * 轮询等待归档的流程。这是本服务唯一的定时任务，范围仅限刚结束的流程。
     *
     * <p>拿到终态 {@code COMPLETED} 时，本轮 {@code snapshot} 已因内容变化推送过一次，
     * 随后出队以免继续空转。</p>
     */
    @Scheduled(fixedDelay = ARCHIVE_POLL_MS)
    public void refreshArchive() {
        long now = System.currentTimeMillis();
        awaitingArchive.forEach((processId, deadline) -> {
            if (now > deadline) {
                awaitingArchive.remove(processId);
                log.warn("存证归档等待超时，停止跟踪，processId={}", processId);
                return;
            }
            try {
                Map<String, Object> state = snapshot(processId);
                if ("COMPLETED".equals(state.get("archiveStatus"))) {
                    awaitingArchive.remove(processId);
                    channels.remove(processId);
                }
            } catch (ArchiveUnavailableException error) {
                // 存证服务暂时不可达：保留等待，下个周期重试。
                // 若在这里出队，已结束流程的存证状态会永远停在最后一次成功查询到的值上 ——
                // 而"已结束流程还要继续跟踪"的唯一目的就是等这个状态变化。
                log.warn("查询存证状态失败，保留等待，processId={}，cause={}", processId, error.getMessage());
            } catch (RuntimeException error) {
                // 仅"流程历史已不可查"（404）属于停止跟踪的合法条件：不向订阅方伪造完成状态。
                // 其余异常（下游接口失败、快照组装错误等）如实向外抛出：
                // 调度框架记录 ERROR 供告警采集，下个周期继续重试，不再静默丢弃跟踪。
                if (error instanceof org.springframework.web.server.ResponseStatusException
                        && ((org.springframework.web.server.ResponseStatusException) error).getStatus()
                           == org.springframework.http.HttpStatus.NOT_FOUND) {
                    awaitingArchive.remove(processId);
                    channels.remove(processId);
                    log.warn("流程历史已不可查，停止归档跟踪，processId={}", processId);
                    return;
                }
                log.error("刷新存证归档状态失败，processId={}", processId, error);
                throw error;
            }
        });
    }

    /**
     * 按流程变量决定投递目标：坐席归属写自接听认领，客户标识写自进线申请。
     * 两者都可能缺失（如数字人纯自助业务），缺失即不投递 —— 判空由端口实现负责。
     *
     * <p>坐席侧遵守改造方案 §14.1：{@code agent_work_complete} 之后向坐席只投递一次
     * {@code AGENT_RELEASED} 释放事件，此后该流程不再向坐席推送 NODE_STATE ——
     * 坐席已退出交易页，继续分页只会把已结束的人工业务再唤回。</p>
     */
    @SuppressWarnings("unchecked")
    private void publish(Channel channel, Map<String, Object> event) {
        Object vars = event.get("vars");
        if (!(vars instanceof Map)) throw new IllegalStateException("快照缺少流程变量，processId=" + event.get("processId"));
        Map<String, Object> values = (Map<String, Object>) vars;
        Object targetsValue = event.get("notificationTargets");
        if (!(targetsValue instanceof List)) throw new IllegalStateException("快照缺少通知目标，processId=" + event.get("processId"));
        List<?> targets = (List<?>) targetsValue;
        String agent = text(values.get("agent_session_id"));
        if (agent.isEmpty()) agent = text(values.get("last_agent_id"));
        if (Boolean.TRUE.equals(values.get("agent_work_complete"))) {
            if (!channel.released) {
                channel.released = true;
                Map<String, Object> release = new LinkedHashMap<String, Object>(event);
                release.put("type", AGENT_RELEASED);
                signals.notifyAgent(agent, release);
            }
        } else if (!channel.released && targets.contains("AGENT")) {
            signals.notifyAgent(agent, event);
        }
        if (targets.contains("CUSTOMER")) {
            // 通知使用客户业务身份；mobile_peer_id 只属于 RTC 和提交会话校验。
            String customerId = text(values.get("customer_id"));
            if (customerId.isEmpty()) throw new IllegalStateException("客户节点通知缺少 customer_id，processId=" + event.get("processId"));
            signals.notifyCustomer(customerId, event);
            businessEvents.publishCustomer(customerId, event);
        }
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}

package com.example.bank.routing.routing;

import com.example.bank.routing.persistence.RoutingDailyStatsRepository;
import com.example.bank.routing.persistence.RoutingTicketEventRepository;
import com.example.bank.routing.persistence.RoutingTicketRepository;
import com.example.bank.routing.port.ProcessPort;
import com.example.bank.routing.port.ProductEventPublisher;
import com.example.bank.routing.profile.CustomerProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 排队路由服务：<b>统一管理人工坐席与数字人两类接待方</b>。
 *
 * <p>同一通会话只维护一张工单，接待方的每一次交接（数字人 → 人工 → 数字人）都是对同一张工单的
 * {@link #route(QueueRequest) 重新排队路由}，而不是新建工单。</p>
 *
 * <p>本服务不含任何接待方类别的分支逻辑：按工单的 {@code handlerType} 从
 * {@link RoutingPool} 资源池中取对应实现完成匹配与占用，新增接待方类别只需再提供一个资源池实现。</p>
 *
 * <p><b>跨服务依赖全部经端口出站</b>：振铃/接听/队列变更走 {@link ProductEventPublisher}
 * 交给 business 决定推送；会话判活与接听动作走业务中枢实现的 {@link ProcessPort}。
 * 路由不认识 Flowable、信令或媒体实现，因此可以脱离这些服务单独测试。</p>
 */
@Service
public class QueueRoutingService {

    private static final long TICKET_TTL_MS = 30 * 60 * 1000L;

    /** 资源占用不变量：接待方仅在工单处于 ANSWERED 时被占用，释放必须与此严格对应。 */
    private static final String ANSWERED = "ANSWERED";

    private final Map<String, QueueTicket> tickets = new ConcurrentHashMap<String, QueueTicket>();
    private final Map<String, RoutingPool> pools = new LinkedHashMap<String, RoutingPool>();
    private final ProductEventPublisher events;
    private final ProcessPort process;
    private final AgentRegistry agents;
    private final long ringTimeoutMs;

    /** 持久化仓储：单元测试/无库环境为空，不阻断路由主流程；Spring 启动时注入。 */
    private RoutingTicketRepository ticketRepository;
    private RoutingTicketEventRepository ticketEventRepository;
    private RoutingDailyStatsRepository dailyStatsRepository;

    @Autowired(required = false)
    public void setTicketRepository(RoutingTicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Autowired(required = false)
    public void setTicketEventRepository(RoutingTicketEventRepository ticketEventRepository) {
        this.ticketEventRepository = ticketEventRepository;
    }

    @Autowired(required = false)
    public void setDailyStatsRepository(RoutingDailyStatsRepository dailyStatsRepository) {
        this.dailyStatsRepository = dailyStatsRepository;
    }

    public QueueRoutingService(List<RoutingPool> pools, ProductEventPublisher events,
                               ProcessPort process, AgentRegistry agents,
                               @org.springframework.beans.factory.annotation.Value("${routing.ring-timeout-ms:45000}") long ringTimeoutMs) {
        for (RoutingPool pool : pools) {
            if (this.pools.put(pool.handlerType(), pool) != null) {
                throw new IllegalStateException("重复的接待方资源池：" + pool.handlerType());
            }
        }
        for (String required : new String[]{HandlerType.HUMAN, HandlerType.DIGITAL_HUMAN}) {
            if (!this.pools.containsKey(required)) throw new IllegalStateException("缺少接待方资源池：" + required);
        }
        this.events = events;
        this.process = process;
        this.agents = agents;
        this.ringTimeoutMs = ringTimeoutMs;
    }

    /**
     * 入队 / 改派：同一通会话复用同一张工单，接待方切换即重新排队路由。
     * 数字人排队、转人工、交还数字人三个流程节点都调用本方法，区别只在 {@code handlerType} 与 {@code handledNode}。
     *
     * <p>入参严格校验，失败即 400，不做任何自动纠正：{@code handlerType} 缺失或不属
     * {@link HandlerType} 取值域时绝不退回 HUMAN —— 早先的自动纠正会把一笔畸形请求
     * 静默排进错误的资源池，工单停在 WAITING 直到 30 分钟后过期，全程无任何报错。</p>
     */
    public synchronized QueueTicket route(QueueRequest request) {
        if (request == null || blank(request.processId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "排队请求缺少 processId");
        }
        if (blank(request.handlerType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "排队请求缺少 handlerType");
        }
        if (!HandlerType.HUMAN.equals(request.handlerType) && !HandlerType.DIGITAL_HUMAN.equals(request.handlerType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的接待方类别：" + request.handlerType);
        }
        String requestedSkill = request.profile == null || blank(request.profile.skillGroup) ? request.skillGroup : request.profile.skillGroup;
        if (blank(requestedSkill)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "排队请求缺少技能组");
        QueueTicket ticket = tickets.get(request.processId);
        boolean existing = ticket != null;
        if (ticket == null) {
            ticket = new QueueTicket();
            ticket.ticketId = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            ticket.processId = request.processId;
            tickets.put(request.processId, ticket);
        } else {
            // 改派前先归还上一接待方，再清空本轮匹配痕迹重新排队
            releaseHandler(ticket);
            ticket.agentId = null;
            ticket.rejectedBy.clear();
            ticket.ringStartedAt = 0L;
            ticket.answeredAt = 0L;
            ticket.completedAt = 0L;
        }
        ticket.handlerType = request.handlerType;
        ticket.excludedAgentId = request.excludedAgentId;
        ticket.handledNode = request.handledNode;
        // "交还时优先复用原接待方"这条规则由本服务自己维护：改派会把 agentId 清空（见上），
        // 接待方历史只有工单记得住。原先由流程变量 digital_human_id 承载，代价是本服务要把编号
        // 回写进调用方正处事务中的流程实例 —— 与 handler_type 被删时同一个病，且那个变量的
        // 唯一消费方就是这次回写本身（交还数字人节点读回去当"优先复用"的依据）。
        // 接待方归属以工单为准，流程里不必留副本。
        ticket.preferredHandlerId = HandlerType.DIGITAL_HUMAN.equals(ticket.handlerType)
                ? ticket.lastDigitalHumanId
                : null;
        ticket.enqueuedAt = System.currentTimeMillis();
        ticket.updatedAt = ticket.enqueuedAt;
        ticket.status = "WAITING";
        if (request.bizNo != null && !request.bizNo.isEmpty()) ticket.bizNo = request.bizNo;
        CustomerProfile profile = request.profile;
        if (profile != null) {
            ticket.customerId = profile.customerId;
            ticket.customerName = profile.name;
            ticket.level = profile.level;
            ticket.levelName = profile.levelName;
            ticket.skillGroup = profile.skillGroup;
            ticket.riskListed = profile.riskListed;
            if (profile.channel != null) ticket.channel = profile.channel;
        } else if (!blank(request.channel)) {
            ticket.channel = request.channel;
        }
        if (blank(ticket.skillGroup)) ticket.skillGroup = requestedSkill;
        if (request.bizType != null) ticket.bizType = request.bizType;
        ticket.amount = request.amount;
        ticket.payee = request.payee;
        ticket.mobilePeerId = request.mobilePeerId == null ? "" : request.mobilePeerId;
        ticket.bizKey = !blank(request.bizKey) ? request.bizKey
                : (!blank(request.bizNo) ? request.bizNo : request.processId);
        ticket.bizDate = request.bizDate;
        ticket.queueId = blank(request.queueId) ? "default" : request.queueId;
        ticket.priority = request.priority;
        ticket.expiresAt = ticket.enqueuedAt + TICKET_TTL_MS;
        // 这里原先把 ticket.handlerType 回写成流程变量 handler_type。删掉有两个理由：
        // 一是它没有任何消费方 —— BPMN 的分流条件只用 authPassed / needHuman / transfer_requested /
        // service_mode，场景配置、前端、本服务都不读它；二更要紧的是它是**事务内的跨服务回写**：
        // 调用方（工作流服务）正处在流程推进事务里，这条 HTTP 会在另一个事务中更新同一执行实例，
        // 调用方随后 flush 时持有的版本已过期，抛乐观锁冲突并整笔回滚，且重试必然重复同一路径。
        // 接待方类别由流程节点自己声明（delegate 里就写着 HandlerType 实参），不需要绕一圈写回。
        long enqueuedAt = ticket.enqueuedAt;
        persist(ticket);
        ticketEvent(ticket, existing ? "REQUEUED" : "ENQUEUED", ticket.agentId);
        try {
            broadcastQueue();
            tryRoute();
        } finally {
            // 广播与路由都不改变入队顺序，保证同一批工单按到达顺序 FIFO
            ticket.enqueuedAt = enqueuedAt;
        }
        return ticket;
    }

    /** 坐席接听接口：校验振铃归属后绑定业务会话，并由服务端主动把视频房间下发给客户与坐席。 */
    public synchronized QueueTicket answer(String agentId, String processId, String peerId) {
        QueueTicket ticket = require(processId);
        if (!HandlerType.HUMAN.equals(ticket.handlerType) || !"RINGING".equals(ticket.status) || !agentId.equals(ticket.agentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该来电已失效或不属于当前坐席");
        }
        // 认领写入流程变量；流程已离开人工节点或已被他人认领时流程引擎返回 4xx，原样上抛。
        try {
            process.claimSession(processId, agentId, peerId);
        } catch (org.springframework.web.client.HttpClientErrorException error) {
            // 只把下游 4xx 翻译给调用方，且按状态码区分语义：404（流程实例不存在）如实回 404，
            // 其余 4xx（已被他人认领 / 已离开人工节点）回 409 —— 两者不可混为一个状态码
            // （CONTRACTS.md 第四节 session/claim 语义要点）。5xx / 超时不再被伪装成 409，
            // 原样抛出为 500，让调用方看出这是流程服务不可用而不是接听冲突。
            if (error.getRawStatusCode() == 404) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "流程实例不存在，无法接听");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "业务已被其他坐席接听或已离开人工节点");
        }
        ticket.status = ANSWERED;
        long now = System.currentTimeMillis();
        ticket.answeredAt = now;
        ticket.updatedAt = now;
        pool(HandlerType.HUMAN).acquire(agentId);
        Map<String, Object> answered = new LinkedHashMap<String, Object>();
        answered.put("agentId", agentId);
        answered.put("ticket", ticket);
        events.publish("TICKET_ANSWERED", answered);
        persist(ticket);
        ticketEvent(ticket, "ANSWERED", agentId);
        recordAnswered(agentId, ticket.ringStartedAt > 0 ? now - ticket.ringStartedAt : 0L);
        broadcastQueue();
        // 认领、建房与入房邀请已由业务中枢的接待动作完成；路由仅提交工单和资源占用。
        return ticket;
    }

    /** 拒接：交给其他坐席继续匹配，无人可接时回到等待队列。 */
    public synchronized QueueTicket reject(String agentId, String processId) {
        QueueTicket ticket = require(processId);
        if (!HandlerType.HUMAN.equals(ticket.handlerType) || !"RINGING".equals(ticket.status) || !agentId.equals(ticket.agentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该来电已失效或不属于当前坐席");
        }
        ticket.rejectedBy.add(agentId);
        ticket.agentId = null;
        ticket.ringStartedAt = 0L;
        ticket.status = "WAITING";
        ticket.updatedAt = System.currentTimeMillis();
        persist(ticket);
        ticketEvent(ticket, "REJECTED", agentId);
        recordRejected(agentId);
        broadcastQueue();
        tryRoute();
        return ticket;
    }

    public synchronized void release(String processId, String reason) {
        QueueTicket ticket = tickets.get(processId);
        if (ticket == null) return;
        long now = System.currentTimeMillis();
        releaseHandler(ticket);
        ticket.status = reason == null && ANSWERED.equals(ticket.status) ? ANSWERED : "CANCELLED";
        if ("CANCELLED".equals(ticket.status)) {
            ticket.completedAt = now;
        }
        ticket.updatedAt = now;
        persist(ticket);
        ticketEvent(ticket, "RELEASED", ticket.agentId);
        broadcastQueue();
    }

    /** 流程结束或离开接待节点时移除工单并释放接待方。 */
    public synchronized void complete(String processId) {
        QueueTicket ticket = tickets.get(processId);
        if (ticket == null) return;
        long now = System.currentTimeMillis();
        long serviceMs = ticket.answeredAt > 0 ? now - ticket.answeredAt : 0L;
        releaseHandler(ticket);
        ticket.status = "COMPLETED";
        ticket.completedAt = now;
        ticket.updatedAt = now;
        persist(ticket);
        ticketEvent(ticket, "COMPLETED", ticket.agentId);
        if (ticket.agentId != null && !ticket.agentId.trim().isEmpty() && ticket.answeredAt > 0) {
            recordServed(ticket.agentId, serviceMs);
        }
        tickets.remove(processId);
        broadcastQueue();
    }

    /**
     * 坐席掉线：其名下工单回到等待队列，已经受理的同步归还服务量。
     *
     * <p>幂等：同一次掉线可能由信令的连接事件与心跳停滞两条判据先后触发，
     * 重复调用不会重复扣减服务量 —— 工单在第一次调用后已不再指向该坐席，第二次自然跳过。</p>
     */
    public synchronized void onAgentOffline(String agentId) {
        long now = System.currentTimeMillis();
        for (QueueTicket ticket : tickets.values()) {
            if (!HandlerType.HUMAN.equals(ticket.handlerType) || !agentId.equals(ticket.agentId)) continue;
            boolean affected = false;
            if ("RINGING".equals(ticket.status)) {
                ticket.agentId = null;
                ticket.status = "WAITING";
                ticket.ringStartedAt = 0L;
                ticket.updatedAt = now;
                affected = true;
            } else if (ANSWERED.equals(ticket.status)) {
                releaseHandler(ticket);
                ticket.agentId = null;
                ticket.status = "WAITING";
                ticket.updatedAt = now;
                affected = true;
            }
            if (affected) {
                persist(ticket);
                ticketEvent(ticket, "REQUEUED", agentId);
            }
        }
        broadcastQueue();
    }

    /** 启动恢复：把活动工单放回内存表，不触发派单、不重复写库。 */
    public synchronized void restoreTicket(QueueTicket ticket) {
        if (ticket == null || blank(ticket.processId)) return;
        if (blank(ticket.ticketId)) {
            ticket.ticketId = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        }
        if (blank(ticket.queueId)) ticket.queueId = "default";
        if (ticket.bizKey == null) {
            ticket.bizKey = !blank(ticket.bizNo) ? ticket.bizNo : ticket.processId;
        }
        tickets.put(ticket.processId, ticket);
    }

    public QueueTicket byProcess(String processId) { return tickets.get(processId); }

    public List<QueueTicket> waiting(String skillGroup) {
        List<QueueTicket> rows = new ArrayList<QueueTicket>();
        for (QueueTicket ticket : ordered()) {
            if (!"WAITING".equals(ticket.status) && !"RINGING".equals(ticket.status)) continue;
            if (skillGroup != null && !skillGroup.isEmpty() && !skillGroup.equals(ticket.skillGroup)) continue;
            rows.add(ticket);
        }
        return rows;
    }

    public List<QueueTicket> visibleTo(String agentId) {
        List<QueueTicket> rows = new ArrayList<QueueTicket>();
        for (QueueTicket ticket : ordered()) {
            if ("WAITING".equals(ticket.status) && !ticket.rejectedBy.contains(agentId)) rows.add(ticket);
            else if ("RINGING".equals(ticket.status) && agentId.equals(ticket.agentId)) rows.add(ticket);
            else if (ANSWERED.equals(ticket.status) && agentId.equals(ticket.agentId)) rows.add(ticket);
        }
        return rows;
    }

    public QueueTicket ringingFor(String agentId) {
        for (QueueTicket ticket : ordered()) {
            if ("RINGING".equals(ticket.status) && agentId.equals(ticket.agentId)) return ticket;
        }
        return null;
    }

    /**
     * 路由主循环：按工单所需的接待方类别取对应资源池匹配。
     * 人工坐席需要振铃与接听确认；数字人无需振铃，路由命中即受理。
     */
    public synchronized void tryRoute() {
        boolean changed = false;
        // 坐席同时只承载一路来电：名下已有振铃中工单的接待方，本轮不再参与匹配。
        java.util.Set<String> ringingNow = new java.util.HashSet<String>();
        for (QueueTicket ticket : tickets.values()) {
            if ("RINGING".equals(ticket.status) && ticket.agentId != null) ringingNow.add(ticket.agentId);
        }
        for (QueueTicket ticket : ordered()) {
            if (!"WAITING".equals(ticket.status)) continue;
            // handlerType 已在 route() 校验为已注册类别，取不到池只能是不变量被破坏，如实抛出，
            // 不再 continue —— 否则该工单会静默停在 WAITING 直到过期，排查无从下手。
            RoutingPool pool = pool(ticket.handlerType);
            // 交还场景优先复用原接待方，不可用（下线 / 忙 / 已换岗）时退回技能组匹配
            RoutingResource resource = pool.find(ticket.preferredHandlerId);
            java.util.Set<String> excluded=new java.util.LinkedHashSet<>(ticket.rejectedBy);
            if(ticket.excludedAgentId!=null&&!ticket.excludedAgentId.isEmpty())excluded.add(ticket.excludedAgentId);
            excluded.addAll(ringingNow);
            if(resource!=null&&excluded.contains(resource.id()))resource=null;
            if (resource == null) resource = pool.match(ticket.skillGroup, excluded);
            if (resource == null) continue;
            ticket.preferredHandlerId = null;
            ticket.agentId = resource.id();
            ticket.updatedAt = System.currentTimeMillis();
            if (HandlerType.DIGITAL_HUMAN.equals(ticket.handlerType)) {
                ticket.status = ANSWERED;
                ticket.answeredAt = ticket.updatedAt;
                pool.acquire(resource.id());
                // 记住本次接待的数字人，供交还时优先复用；不回写流程变量，理由见 route()
                ticket.lastDigitalHumanId = resource.id();
                persist(ticket);
                ticketEvent(ticket, "ANSWERED", resource.id());
                recordAnswered(resource.id(), 0L);
            } else {
                ticket.status = "RINGING";
                ticket.ringStartedAt = ticket.updatedAt;
                persist(ticket);
                ticketEvent(ticket, "RINGING", resource.id());
                Map<String, Object> ringing = new LinkedHashMap<String, Object>();
                ringing.put("agentId", resource.id());
                ringing.put("ticket", ticket);
                events.publish("TICKET_RINGING", ringing);
                // 本轮排他表同步登记，后续排队工单不会再把同一路来电塞给同一坐席
                ringingNow.add(resource.id());
            }
            changed = true;
        }
        if (changed) broadcastQueue();
    }

    /** 振铃超时回到等待队列，避免坐席失联时业务悬空；接待节点失活则收口并释放接待方。 */
    @Scheduled(fixedDelay = 5000)
    public synchronized void sweep() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (QueueTicket ticket : tickets.values()) {
            if ("RINGING".equals(ticket.status) && now - ticket.ringStartedAt > ringTimeoutMs) {
                if (ticket.agentId != null) ticket.rejectedBy.add(ticket.agentId);
                ticket.agentId = null;
                ticket.status = "WAITING";
                ticket.ringStartedAt = 0L;
                ticket.updatedAt = now;
                persist(ticket);
                ticketEvent(ticket, "REQUEUED", null);
                changed = true;
            }
            // 业务离开工单绑定的接待节点后自动收口，数字人 / 坐席服务量随之释放
            if (ANSWERED.equals(ticket.status) && !handledNodeActive(ticket)) {
                long serviceMs = ticket.answeredAt > 0 ? now - ticket.answeredAt : 0L;
                releaseHandler(ticket);
                ticket.status = "CANCELLED";
                ticket.completedAt = now;
                ticket.updatedAt = now;
                persist(ticket);
                ticketEvent(ticket, "CANCELLED", ticket.agentId);
                if (ticket.agentId != null && !ticket.agentId.trim().isEmpty()) {
                    recordServed(ticket.agentId, serviceMs);
                }
                changed = true;
            }
            if (now - ticket.enqueuedAt > TICKET_TTL_MS
                    && ("WAITING".equals(ticket.status) || "RINGING".equals(ticket.status))) {
                if (ticket.agentId != null && "RINGING".equals(ticket.status)) ticket.rejectedBy.add(ticket.agentId);
                ticket.status = "EXPIRED";
                ticket.completedAt = now;
                ticket.updatedAt = now;
                persist(ticket);
                ticketEvent(ticket, "EXPIRED", ticket.agentId);
                changed = true;
            }
        }
        tickets.values().removeIf(ticket -> ("EXPIRED".equals(ticket.status) || "CANCELLED".equals(ticket.status))
                && now - ticket.updatedAt > 60000L);
        if (changed) broadcastQueue();
        tryRoute();
    }

    /** 工单绑定的流程节点仍活动时工单有效；未绑定节点的历史工单不做自动收口。 */
    private boolean handledNodeActive(QueueTicket ticket) {
        Map<String,Object> callContext=process.variables(ticket.processId);
        if(callContext!=null&&Boolean.TRUE.equals(callContext.get("call_active")) && Objects.equals(ticket.agentId,callContext.get("agent_session_id")))return true;
        if (blank(ticket.handledNode)) return true;
        if (process.isNodeActive(ticket.processId, ticket.handledNode)) return true;
        // 节点判活失败按"活跃"处理是契约行为（CONTRACTS.md 第四节语义要点），由端口实现承担；
        // 走到这里说明判活已成功返回"不活跃"，随后的变量查询失败必须上抛：吞成 true 会把
        // 一次流程服务故障悄悄变成"永远不收口"，只在下次扫描再赌一把，不留任何痕迹。
        Map<String, Object> vars = process.variables(ticket.processId);
        if (vars == null) {
            throw new IllegalStateException("流程变量查询返回空响应，无法判断保留坐席，processId=" + ticket.processId);
        }
        return Boolean.TRUE.equals(vars.get("retain_agent"))
                && ticket.agentId.equals(vars.get("agent_session_id"));
    }

    /** 归还接待方资源；未受理（未占用）的工单无需归还。 */
    private void releaseHandler(QueueTicket ticket) {
        if (ticket == null || ticket.agentId == null || !ANSWERED.equals(ticket.status)) return;
        pool(ticket.handlerType).release(ticket.agentId);
    }

    public Map<String, Object> summary(String agentId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("agentId", agentId);
        result.put("ringing", ringingFor(agentId));
        result.put("queue", visibleTo(agentId));
        result.put("queueSize", waiting(null).size());
        return result;
    }

    /**
     * 队列变更广播：收集名册中非离线的坐席，交给 business 决定推送。
     *
     * <p>"发给谁"由路由算（名册在这里），"怎么发"由 business/信令决定。
     * 坐席已断线时由 business 侧推送为空操作，不需要路由先探测在线态。</p>
     */
    public void broadcastQueue() {
        List<String> agentIds = new ArrayList<String>();
        for (AgentRegistry.Agent agent : agents.all()) {
            if (agent.agentId == null || "OFFLINE".equals(agent.status)) continue;
            agentIds.add(agent.agentId);
        }
        if (agentIds.isEmpty()) return;
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("agentIds", agentIds);
        events.publish("QUEUE_UPDATED", payload);
    }

    private RoutingPool pool(String handlerType) {
        RoutingPool pool = pools.get(handlerType);
        if (pool == null) throw new IllegalStateException("未注册的接待方类别：" + handlerType);
        return pool;
    }

    private QueueTicket require(String processId) {
        QueueTicket ticket = tickets.get(processId);
        if (ticket == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到进线工单，请刷新队列");
        return ticket;
    }

    private List<QueueTicket> ordered() {
        List<QueueTicket> rows = new ArrayList<QueueTicket>(tickets.values());
        rows.sort(Comparator.comparingLong((QueueTicket ticket) -> ticket.enqueuedAt));
        return rows;
    }

    private void persist(QueueTicket ticket) {
        if (ticketRepository != null) {
            ticketRepository.save(ticket);
        }
    }

    private void ticketEvent(QueueTicket ticket, String type, String agentId) {
        if (ticketEventRepository == null) return;
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("status", ticket.status);
        payload.put("handlerType", ticket.handlerType);
        payload.put("bizKey", ticket.bizKey);
        payload.put("queueId", ticket.queueId);
        ticketEventRepository.append(ticket.ticketId, type, agentId, payload);
    }

    private void recordAnswered(String agentId, long ringMs) {
        if (dailyStatsRepository != null && agentId != null && !agentId.trim().isEmpty()) {
            dailyStatsRepository.recordAnswered(agentId, ringMs);
        }
    }

    private void recordRejected(String agentId) {
        if (dailyStatsRepository != null && agentId != null && !agentId.trim().isEmpty()) {
            dailyStatsRepository.recordRejected(agentId);
        }
    }

    private void recordServed(String agentId, long serviceMs) {
        if (dailyStatsRepository != null && agentId != null && !agentId.trim().isEmpty()) {
            dailyStatsRepository.recordServed(agentId, serviceMs);
        }
    }

    private boolean blank(String value) { return value == null || value.isEmpty(); }
}

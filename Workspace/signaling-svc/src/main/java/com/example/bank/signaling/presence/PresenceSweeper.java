package com.example.bank.signaling.presence;

import com.example.bank.signaling.centrifugo.CentrifugoClient;
import com.example.bank.signaling.centrifugo.CentrifugoProperties;
import com.example.bank.signaling.centrifugo.CentrifugoPresenceException;
import com.example.bank.signaling.persistence.SignalingPersistenceService;
import com.example.bank.signaling.port.PresenceEventPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 连接层掉线判定：把"坐席的连接没了"翻译成通用 {@code CONNECTION_CLOSED} 抛给业务逻辑服务。
 *
 * <p><b>本类不修改任何坐席状态</b>。原单体里它直接调 {@code AgentRegistry.setStatus(OFFLINE)} 与
 * {@code QueueRoutingService.onAgentOffline()}，因为路由和历史状态都在同一个进程里。
 * 拆分后信令服务既不认识坐席名册，也不认识工单 —— 它只能上报观察到的事实，
 * "这个坐席现在该不该置离线、名下工单怎么处理"由业务逻辑服务判定后委派给路由服务。</p>
 *
 * <p><b>为什么只能靠轮询</b>：Centrifugo v6 官方明确不发出 disconnect / unsubscribe 事件，
 * 因此没有回调可挂，只能反过来用 presence 反查。</p>
 *
 * <p><b>失败的三条路径都显式处置，无静默兜底</b>：</p>
 * <ul>
 *   <li>presence 查询失败（{@link CentrifugoPresenceException}）：按规格的误杀防护，
 *       <b>本轮不判定该坐席的在线态</b>（失败绝不等于"无人在线"），同时记 ERROR 日志（含异常栈）
 *       作为告警落点；</li>
 *   <li>掉线事件上报失败（{@code disconnected} 返回 {@code false}）：<b>不标记已上报</b>，
 *       记 ERROR 日志，下一轮扫描按规格（CONTRACTS.md 第三节）重新观察并再次上报；</li>
 *   <li>其余未预期异常（编程错误）：不捕获，直接冒泡由调度器记 ERROR，
 *       下一轮扫描照常进行 —— 代码缺陷不允许被吞成"跳过一个主体"。</li>
 * </ul>
 *
 * <p><b>检测延迟</b>取两个因素中的较大者：本类的扫描节拍（5s），与连接何时被 TCP 层释放。
 * 进程被强杀（{@code taskkill /F}）或正常退出时，操作系统会关闭该进程的套接字并发 RST，
 * Centrifugo 立刻感知 —— 实测强杀到判定在同一秒内完成，延迟约等于一个扫描节拍。</p>
 *
 * <p>延迟退化到 Centrifugo ping 超时（约 25s）只发生在<b>套接字仍开着但对端已不可达</b>的场景：
 * 网线被拔、进程被挂起（而非杀死）、主机断电。此时没有任何一方发出 FIN，
 * 只能等 Centrifugo 判死。这类故障下<b>对端只能沉默，不能通知</b> —— 这是协议层的限制，
 * 不是本实现的缺陷。</p>
 *
 * <p>要压到秒级只有两条路：自建 WebSocket 网关，或换支持回调的 Centrifugo 版本。
 * 届时只需把 {@code source} 从 {@code PRESENCE_SWEEP} 改成 {@code SOCKET_CALLBACK}，
 * 消费端（业务逻辑服务）不用改。</p>
 */
@Component
public class PresenceSweeper {

    private static final Logger log = LoggerFactory.getLogger(PresenceSweeper.class);

    /** 观察到的现象：频道内已无连接。 */
    private static final String REASON_PRESENCE_EMPTY = "PRESENCE_EMPTY";

    /** 生产者身份：presence 轮询。通道升级为真回调后改为此处的取值域将扩展。 */
    private static final String SOURCE_PRESENCE_SWEEP = "PRESENCE_SWEEP";

    private final ConnectedSubjectRegistry subjects;
    private final CentrifugoClient centrifugo;
    private final CentrifugoProperties properties;
    private final PresenceEventPort events;
    private final SignalingPersistenceService persistence;
    private final long subscribeGraceMs;

    public PresenceSweeper(ConnectedSubjectRegistry subjects, CentrifugoClient centrifugo,
                           CentrifugoProperties properties, PresenceEventPort events,
                           SignalingPersistenceService persistence,
                           @Value("${presence.subscribe-grace-ms:15000}") long subscribeGraceMs) {
        this.subjects = subjects;
        this.centrifugo = centrifugo;
        this.properties = properties;
        this.events = events;
        this.persistence = persistence;
        this.subscribeGraceMs = subscribeGraceMs;
    }

    @Scheduled(fixedDelayString = "${presence.sweep-interval-ms:5000}")
    public void sweep() {
        long now = System.currentTimeMillis();
        for (ConnectedSubjectRegistry.Subject subject : subjects.agents()) {
            // 签入宽限期：令牌签发到 Centrifugo 完成订阅之间存在窗口，此期间不参与判定
            if (now - subject.registeredAt < subscribeGraceMs) continue;
            String channel = properties.channelForAgent(subject.id);
            int online;
            try {
                online = centrifugo.presenceCount(channel);
            } catch (CentrifugoPresenceException failure) {
                // 误杀防护（规格行为）：查询失败不判定在线态，只有确认频道内确实没有连接才判定掉线。
                // 失败本身不再被吞成空值：记 ERROR（含异常栈），作为告警落点，交下一轮再观察。
                log.error("presence 查询失败，本轮不判定坐席 {} 的在线态 channel={}",
                        subject.id, channel, failure);
                continue;
            }
            if (online > 0) {
                // 频道内仍有连接（含 Centrifugo 客户端用同一令牌自动重连）：清除已上报标记
                boolean wasReported = subjects.markPresent(subject.id);
                persistence.presenceOnline(subject.id, subject.role, channel, now, wasReported);
                continue;
            }
            // 同一个断线事实只上报一次，避免每个扫描周期重复刷日志与 HTTP 调用。
            // 坐席重连后 markPresent 会复位此标记，因此不会永久失联。
            if (subject.reported) continue;
            // 先落库连接关闭事实：即使随后 business 上报失败，连接已关闭这一事实仍然成立
            persistence.presenceOffline(subject.id, subject.role, channel, now, REASON_PRESENCE_EMPTY);
            log.info("连接层判定：坐席 {} 的频道 {} 已无连接，上报 CONNECTION_CLOSED", subject.id, channel);
            boolean delivered = events.disconnected(subject.id, REASON_PRESENCE_EMPTY, SOURCE_PRESENCE_SWEEP, channel);
            if (!delivered) {
                // 上报失败时绝不标记"已上报"：规格（CONTRACTS.md 第三节）所述的恢复语义是
                // "下一个扫描周期会重新观察到同一事实并再次上报"——只有失败保持未上报，这条路才成立。
                log.error("掉线事件未送达，坐席 {} 保持未上报状态，下一轮扫描将再次上报 channel={}",
                        subject.id, channel);
                continue;
            }
            subjects.markReported(subject.id);
            persistence.reported(subject.id, true);
        }
    }
}

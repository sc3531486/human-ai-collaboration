package com.example.bank.signaling.web;

import com.example.bank.signaling.centrifugo.CentrifugoClient;
import com.example.bank.signaling.centrifugo.CentrifugoProperties;
import com.example.bank.signaling.centrifugo.CentrifugoTokenService;
import com.example.bank.signaling.presence.ConnectedSubjectRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 信令服务的对外接口。
 *
 * <p>前缀统一为 {@code /internal}：投递与换取令牌只供其他服务调用，
 * 浏览器侧的入口由业务逻辑服务统一提供（{@code /api/centrifugo/token}），
 * 避免令牌签发出现两个外部入口。</p>
 *
 * <p>投递路径 {@code /internal/agent/{agentId}/publish} 由<b>消费方</b>（路由服务）的
 * {@code HttpSignalAdapter} 声明，本服务照此实现 —— 端口由消费方定义。</p>
 */
@RestController
@RequestMapping("/internal")
public class SignalingController {

    private final CentrifugoClient centrifugo;
    private final CentrifugoProperties properties;
    private final CentrifugoTokenService tokens;
    private final ConnectedSubjectRegistry subjects;
    private final long sweepIntervalMs;
    private final long subscribeGraceMs;

    public SignalingController(CentrifugoClient centrifugo, CentrifugoProperties properties,
                               CentrifugoTokenService tokens, ConnectedSubjectRegistry subjects,
                               @Value("${presence.sweep-interval-ms:5000}") long sweepIntervalMs,
                               @Value("${presence.subscribe-grace-ms:15000}") long subscribeGraceMs) {
        this.centrifugo = centrifugo;
        this.properties = properties;
        this.tokens = tokens;
        this.subjects = subjects;
        this.sweepIntervalMs = sweepIntervalMs;
        this.subscribeGraceMs = subscribeGraceMs;
    }

    /**
     * 投递事件到坐席个人频道。
     *
     * <p>返回 200 只代表"已交给 Centrifugo"，不代表坐席收到 —— 无人订阅时 Centrifugo 同样返回成功。
     * 投递是尽力而为的，坐席端的轮询兜底负责补齐状态，因此调用方无需据此判断在线态。</p>
     *
     * <p>规格行为（CONTRACTS.md 第二节"语义要点"）：投递失败<b>只记日志、不重试、不抛出</b>，
     * 以 200 + {@code delivered:false} 如实上报"没有交给 Centrifugo"，此处不改为 5xx。
     * 请求体缺失仍由框架按 400 拒绝。</p>
     */
    @PostMapping("/agent/{agentId}/publish")
    public Map<String, Object> publishToAgent(@PathVariable String agentId, @RequestBody Map<String, Object> event) {
        requireEvent(event);
        String channel = properties.channelForAgent(agentId);
        boolean delivered = centrifugo.publish(channel, event);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("channel", channel);
        result.put("delivered", delivered);
        return result;
    }

    /** 投递事件到客户个人频道。语义与坐席投递一致。 */
    @PostMapping("/customer/{customerId}/publish")
    public Map<String, Object> publishToCustomer(@PathVariable String customerId, @RequestBody Map<String, Object> event) {
        requireEvent(event);
        String channel = properties.channelForCustomer(customerId);
        boolean delivered = centrifugo.publish(channel, event);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("channel", channel);
        result.put("delivered", delivered);
        return result;
    }

    /**
     * 签发连接令牌。入参二选一：坐席传 {@code agentId}（订阅 {@code agent:<id>}），
     * 客户传 {@code customerId}（订阅 {@code customer:<id>}）。
     *
     * <p>两个标识<b>都为空</b>即 400，不做默认身份推断；两个<b>都非空</b>时按"坐席优先"判定 ——
     * 这条判定规则由消费方 business-svc 的 {@code HttpSignalAdapter} 明确声明并委托本服务执行
     * （见其注释"由它按'坐席优先、客户次之'判定身份"），属规格行为，保留。</p>
     *
     * <p>令牌密钥只在本服务，业务逻辑服务对外提供 {@code /api/centrifugo/token} 时向这里换取，
     * 不接触密钥。签发的同时会登记主体，presence 扫描据此知道该盯哪个频道。</p>
     */
    @PostMapping("/token")
    public Map<String, Object> token(@RequestBody Map<String, String> input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        boolean hasAgent = !blank(input.get("agentId"));
        boolean hasCustomer = !blank(input.get("customerId"));
        if (!hasAgent && !hasCustomer) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少坐席标识或客户标识");
        boolean asCustomer = !hasAgent && hasCustomer;
        String subject = asCustomer ? input.get("customerId").trim() : text(input.get("agentId"));

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("token", tokens.issue(subject, asCustomer ? ConnectedSubjectRegistry.ROLE_CUSTOMER
                : ConnectedSubjectRegistry.ROLE_AGENT));
        result.put("url", properties.getWsUrl());
        result.put("channel", asCustomer ? properties.channelForCustomer(subject) : properties.channelForAgent(subject));
        result.put("subject", subject);
        result.put("role", asCustomer ? "CUSTOMER" : "AGENT");
        return result;
    }

    /**
     * 观测入口：纳管主体数与扫描节拍，用于联调时确认"信令检测到断线"这条判据是否在工作。
     *
     * <p>不返回各主体的状态明细 —— 那是业务逻辑服务登记的事实，信令这里只有"我盯着谁"。</p>
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("service", "signaling-svc");
        result.put("trackedSubjects", subjects.size());
        result.put("sweepIntervalMs", sweepIntervalMs);
        result.put("subscribeGraceMs", subscribeGraceMs);
        result.put("centrifugoApiUrl", properties.getApiUrl());
        return result;
    }

    /** 事件信封必须显式给出：静默按空事件投递会把"调用方没发体"伪装成一次成功投递。 */
    private void requireEvent(Map<String, Object> event) {
        if (event == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件信封不能为空");
    }

    private String text(String value) { return value == null ? "" : value.trim(); }

    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}

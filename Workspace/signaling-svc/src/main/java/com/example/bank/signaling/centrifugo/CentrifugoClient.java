package com.example.bank.signaling.centrifugo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centrifugo HTTP API 客户端：只封装 publish 与 presence。
 *
 * <p>两类调用都必须显式超时 —— Centrifugo 不可用时不能让业务线程和定时任务被拖住
 * （超时配置见 {@link CentrifugoProperties}，构造期校验为正数）。</p>
 *
 * <p><b>publish</b>：失败按规格（CONTRACTS.md 第二节"语义要点"）只记日志、不重试、不抛出，
 * 以返回值 {@code false} 如实上报"没有交给 Centrifugo"，由接口层放进
 * {@code delivered:false}。此处唯一的"失败即失败"出口就是这个返回值，
 * 不再把入参非法（空频道、空事件）静默折算成 {@code false} —— 那是编程错误，直接抛
 * {@link IllegalArgumentException}。</p>
 *
 * <p><b>presence</b>：失败（请求失败 / 响应结构异常）一律抛 {@link CentrifugoPresenceException}，
 * 绝不吞成 {@code 0} 或空值返回 —— 查询失败与"确实无人在线"必须严格区分，
 * 否则 Centrifugo 抖动一次会把全量坐席误杀成离线。</p>
 */
@Component
public class CentrifugoClient {

    private static final Logger log = LoggerFactory.getLogger(CentrifugoClient.class);

    private final CentrifugoProperties properties;
    private final RestTemplate rest;

    public CentrifugoClient(CentrifugoProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        this.rest = new RestTemplate(factory);
    }

    /**
     * 发布事件到频道。
     *
     * <p>按规格（CONTRACTS.md 第二节）：投递失败只记日志、不重试、不抛出，
     * 以 {@code false} 如实返回，调用方据此给出 {@code delivered:false}。</p>
     *
     * @return {@code true} 表示 Centrifugo 已接受投递；{@code false} 表示投递失败（已记 ERROR 日志）
     * @throws IllegalArgumentException 频道或事件为空 —— 这是调用方的编程错误，不属于投递失败，
     *                                  不得被折算成 {@code delivered:false}
     */
    public boolean publish(String channel, Map<String, Object> data) {
        if (channel == null || channel.trim().isEmpty()) {
            throw new IllegalArgumentException("投递频道不能为空");
        }
        if (data == null) {
            throw new IllegalArgumentException("投递事件不能为空");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("channel", channel);
            body.put("data", data);
            ResponseEntity<String> response = rest.exchange(path("/api/publish"), HttpMethod.POST,
                    new HttpEntity<Map<String, Object>>(body, headers()), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("Centrifugo publish 失败 channel={} status={} body={}",
                        channel, response.getStatusCodeValue(), response.getBody());
                return false;
            }
            return true;
        } catch (RestClientException error) {
            log.error("Centrifugo publish 失败 channel={} error={}", channel, error.toString(), error);
            return false;
        }
    }

    /**
     * 查询频道当前在线连接数。
     *
     * <p><b>失败即抛 {@link CentrifugoPresenceException}</b>，与数值 {@code 0}（确实无人在线）
     * 严格区分 —— 调用方只有拿到 {@code 0} 才能判定连接消失，避免 Centrifugo 抖动时误杀全量在线态。
     * 这条误杀防护是规格行为（依据：本方法原接口注释与 {@code PresenceSweeper} 的扫描判定注释），
     * 此前以"catch 后返回空 Optional"实现，现改为抛出带原因的类型化异常，语义不变：
     * 失败绝不等于 0。</p>
     *
     * <p>返回的是连接<b>数</b>而非连接列表：为 0 即意味着该坐席的所有标签页都断了。
     * 多标签页场景下不会出现"一个标签页关了就把坐席判离线"的误判。</p>
     *
     * @throws CentrifugoPresenceException 请求失败（网络、超时、非 2xx）或响应结构异常
     * @throws IllegalArgumentException     频道为空 —— 编程错误，直接抛出
     */
    public int presenceCount(String channel) {
        if (channel == null || channel.trim().isEmpty()) {
            throw new IllegalArgumentException("presence 查询频道不能为空");
        }
        ResponseEntity<Map> response;
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("channel", channel);
            response = rest.exchange(path("/api/presence"), HttpMethod.POST,
                    new HttpEntity<Map<String, Object>>(body, headers()), Map.class);
        } catch (RestClientException error) {
            throw new CentrifugoPresenceException("presence 查询请求失败 channel=" + channel
                    + " cause=" + error.toString(), error);
        }
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new CentrifugoPresenceException("presence 查询响应异常 channel=" + channel
                    + " status=" + response.getStatusCodeValue());
        }
        return count(response.getBody(), channel);
    }

    /**
     * 解析 Centrifugo 的 presence 响应体。
     *
     * <p>结构不合法（缺 {@code result}、缺 {@code num_clients} 与 {@code presence}）时抛
     * {@link CentrifugoPresenceException}，<b>绝不默认返回 0</b> —— 把畸形响应吞成 0
     * 等于把"解析失败"判成"无人在线"，会触发全量误杀。</p>
     */
    @SuppressWarnings("unchecked")
    private int count(Map<?, ?> body, String channel) {
        Object result = body.get("result");
        if (!(result instanceof Map)) {
            throw new CentrifugoPresenceException("presence 响应缺少 result 结构 channel=" + channel
                    + " body=" + body);
        }
        Map<String, Object> payload = (Map<String, Object>) result;
        Object numClients = payload.get("num_clients");
        if (numClients instanceof Number) return ((Number) numClients).intValue();
        Object presence = payload.get("presence");
        if (presence instanceof Map) return ((Map<?, ?>) presence).size();
        throw new CentrifugoPresenceException("presence 响应缺少 num_clients/presence 字段 channel="
                + channel + " payload=" + payload);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", properties.getApiKey());
        return headers;
    }

    private String path(String suffix) {
        String base = properties.getApiUrl().trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + suffix;
    }
}

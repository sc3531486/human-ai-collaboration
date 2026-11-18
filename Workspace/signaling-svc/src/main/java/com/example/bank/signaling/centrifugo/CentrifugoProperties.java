package com.example.bank.signaling.centrifugo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * Centrifugo 接入配置：信令服务只负责签发连接令牌、publish 与 presence 查询，
 * 连接管理与断线重连由 Centrifugo 承担。
 *
 * <p>本服务是这两项密钥（{@code api-key} 与 {@code token-secret}）的<b>唯一持有方</b> ——
 * 其他服务需要令牌一律经本服务的 {@code /internal/token} 换取，不接触密钥。</p>
 *
 * <p><b>启动期强校验</b>：绑定完成后立即校验全部字段，任何一项不合法都让启动失败。
 * 此前缺配置（如 {@code api-key} 为空）不会在启动时报错，而是退化成
 * "请求带空密钥 → Centrifugo 401 → 被下层吞成投递失败/查询失败"，
 * 把配置缺陷掩盖成下游抖动 —— 这种降级不再允许。</p>
 */
@Component
@ConfigurationProperties(prefix = "centrifugo")
public class CentrifugoProperties {

    /** Centrifugo 服务端 HTTP API 根地址，如 http://localhost:8000 */
    private String apiUrl = "http://localhost:8000";

    /** HTTP API 鉴权密钥，对应请求头 X-API-Key */
    private String apiKey;

    /** connection JWT 的 HS256 签名密钥 */
    private String tokenSecret;

    /** 前端连接地址，随令牌一并下发，避免前端硬编码 */
    private String wsUrl = "ws://localhost:8000/connection/websocket";

    /** 连接令牌有效期（秒） */
    private long tokenTtlSeconds = 3600;

    /** publish / presence 的连接超时，必须显式配置，避免 Centrifugo 不可用时拖垮扫描线程 */
    private int connectTimeoutMs = 1000;

    /** publish / presence 的读取超时 */
    private int readTimeoutMs = 2000;

    /** 配置绑定完成后的启动期校验：缺密钥、空地址、非正超时一律拒绝启动。 */
    @PostConstruct
    void validate() {
        requireScheme(apiUrl, "http://", "https://", "centrifugo.api-url");
        requireScheme(wsUrl, "ws://", "wss://", "centrifugo.ws-url");
        requireNonBlank(apiKey, "centrifugo.api-key");
        requireNonBlank(tokenSecret, "centrifugo.token-secret");
        if (tokenTtlSeconds <= 0) {
            throw new IllegalStateException("centrifugo.token-ttl-seconds 必须为正数，当前: " + tokenTtlSeconds);
        }
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalStateException("centrifugo 超时必须为正数（0 等于无超时，负值不合法），当前 connect="
                    + connectTimeoutMs + ", read=" + readTimeoutMs);
        }
    }

    private void requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(name + " 未配置：本服务是该配置的唯一持有方，缺失即拒绝启动");
        }
    }

    private void requireScheme(String value, String first, String second, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(name + " 未配置");
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith(first) && !trimmed.startsWith(second)) {
            throw new IllegalStateException(name + " 非法，必须以 " + first + " 或 " + second + " 开头，当前: " + value);
        }
    }

    /** 坐席个人频道：承载全部坐席侧事件（振铃、队列更新、节点状态、接听），坐席端只订阅这一个频道。 */
    public String channelForAgent(String agentId) { return "agent:" + agentId; }

    /** 客户个人频道：承载服务端主动下发给客户的呼叫与视频房间信令，客户端只订阅这一个频道。 */
    public String channelForCustomer(String customerId) { return "customer:" + customerId; }

    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getTokenSecret() { return tokenSecret; }
    public void setTokenSecret(String tokenSecret) { this.tokenSecret = tokenSecret; }

    public String getWsUrl() { return wsUrl; }
    public void setWsUrl(String wsUrl) { this.wsUrl = wsUrl; }

    public long getTokenTtlSeconds() { return tokenTtlSeconds; }
    public void setTokenTtlSeconds(long tokenTtlSeconds) { this.tokenTtlSeconds = tokenTtlSeconds; }

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
}

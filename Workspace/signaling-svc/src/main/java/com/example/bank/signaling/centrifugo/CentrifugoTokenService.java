package com.example.bank.signaling.centrifugo;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.example.bank.signaling.persistence.SignalingPersistenceService;
import com.example.bank.signaling.presence.ConnectedSubjectRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Date;

/**
 * 连接令牌签发：claims 只放 sub（坐席标识或客户标识）与 exp，Centrifugo 侧用同一密钥校验。
 * 令牌只证明"是谁"，不代表在线；在线态由 presence 轮询校准。
 *
 * <p><b>签发即登记</b>：Centrifugo v6 不提供 subscribe 回调，令牌签发是本服务唯一能观测到的
 * "某主体即将建立连接"的信号。因此在这里同步登记到 {@link ConnectedSubjectRegistry}，
 * 使 presence 扫描知道该盯着哪些频道 —— 登记与签发放同一处，避免调用方漏登记导致扫描静默失效。</p>
 */
@Service
public class CentrifugoTokenService {

    private final CentrifugoProperties properties;
    private final ConnectedSubjectRegistry subjects;
    private final SignalingPersistenceService persistence;

    public CentrifugoTokenService(CentrifugoProperties properties, ConnectedSubjectRegistry subjects,
                                  SignalingPersistenceService persistence) {
        this.properties = properties;
        this.subjects = subjects;
        this.persistence = persistence;
    }

    /** 签发连接令牌；subject 为坐席标识或客户标识，作为频道订阅的主体。 */
    public String issue(String subject, String role) {
        if (subject == null || subject.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少连接主体标识");
        }
        String secret = properties.getTokenSecret();
        if (secret == null || secret.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Centrifugo 令牌密钥未配置");
        }
        long now = System.currentTimeMillis();
        String token = JWT.create()
                .withSubject(subject)
                .withIssuedAt(new Date(now))
                .withExpiresAt(new Date(now + properties.getTokenTtlSeconds() * 1000L))
                .sign(Algorithm.HMAC256(secret));
        // 登记发生在返回令牌之前：调用方拿到令牌就会去连接，扫描必须从这一刻起开始盯
        subjects.register(subject, role);
        String channel = ConnectedSubjectRegistry.ROLE_CUSTOMER.equals(role)
                ? properties.channelForCustomer(subject) : properties.channelForAgent(subject);
        // 令牌签发事实落库；写库失败如实抛给调用方，不返回一个“未登记成功”的令牌
        persistence.tokenIssued(subject, role, channel, now);
        return token;
    }
}

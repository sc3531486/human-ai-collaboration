package com.example.bank.message.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import com.example.bank.message.config.MessageProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;

@Service
public class MessageTokenService {

    private final MessageProperties properties;
    private final Algorithm algorithm;
    private final JWTVerifier verifier;

    public MessageTokenService(MessageProperties properties) {
        this.properties = properties;
        this.algorithm = Algorithm.HMAC256(properties.getTokenSecret());
        this.verifier = JWT.require(algorithm).build();
    }

    public String issue(String subject, String role) {
        if (!StringUtils.hasText(subject)) {
            throw new IllegalArgumentException("subject不能为空");
        }
        if (!StringUtils.hasText(role)) {
            // 签发缺 role 的令牌会把缺陷推迟到认证时暴露，签发侧直接失败。
            throw new IllegalArgumentException("role不能为空");
        }
        long now = System.currentTimeMillis();
        Date issuedAt = new Date(now);
        Date expiresAt = new Date(now + properties.getTokenTtlSeconds() * 1000L);
        return JWT.create()
                .withSubject(subject)
                .withClaim("role", role)
                .withIssuedAt(issuedAt)
                .withExpiresAt(expiresAt)
                .sign(algorithm);
    }

    public MessagePrincipal authenticate(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            throw new IllegalArgumentException("缺少WebSocket令牌");
        }
        String token = rawToken.startsWith("Bearer ") ? rawToken.substring(7).trim() : rawToken.trim();
        DecodedJWT jwt = verifier.verify(token);
        String subject = jwt.getSubject();
        if (!StringUtils.hasText(subject)) {
            throw new IllegalArgumentException("WebSocket令牌缺少subject");
        }
        String role = jwt.getClaim("role").asString();
        if (!StringUtils.hasText(role)) {
            // role 决定坐席掉线上报等分支，缺省补 "unknown" 会让缺陷静默生效，必须拒绝。
            throw new IllegalArgumentException("WebSocket令牌缺少role声明");
        }
        return new MessagePrincipal(subject, role);
    }
}

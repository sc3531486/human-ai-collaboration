package com.example.bank.message.presence;

import com.example.bank.message.auth.MessagePrincipal;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConnectionRegistry {

    private final ConcurrentHashMap<String, Set<String>> sessionsBySubject = new ConcurrentHashMap<String, Set<String>>();
    private final ConcurrentHashMap<String, String> subjectBySession = new ConcurrentHashMap<String, String>();
    private final ConcurrentHashMap<String, MessagePrincipal> principalBySubject = new ConcurrentHashMap<String, MessagePrincipal>();
    private final ConcurrentHashMap<String, String> subscribedSubjectBySession = new ConcurrentHashMap<String, String>();

    public void register(String sessionId, MessagePrincipal principal) {
        if (sessionId == null || principal == null) {
            // 静默忽略会让"没登记上"与"没连"无法区分，非法入参直接失败。
            throw new IllegalArgumentException("sessionId与principal不能为空");
        }
        subjectBySession.put(sessionId, principal.getSubject());
        principalBySubject.put(principal.getSubject(), principal);
        sessionsBySubject.computeIfAbsent(principal.getSubject(), key ->
                Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>())).add(sessionId);
    }

    /**
     * 登记某会话已订阅个人事件队列。投递回执 {@code delivered} 只认真实订阅，
     * 仅"连接在线"不足以证明消息不会被 SimpleBroker 静默丢弃。
     */
    public void markSubscribed(String sessionId) {
        String subject = subjectBySession.get(sessionId);
        if (subject == null) {
            throw new IllegalStateException("订阅发生在连接登记之前，sessionId=" + sessionId);
        }
        subscribedSubjectBySession.put(sessionId, subject);
    }

    /** 取消订阅登记（UNSUBSCRIBE 帧或会话关闭时调用）。 */
    public void clearSubscribed(String sessionId) {
        subscribedSubjectBySession.remove(sessionId);
    }

    /** 该主体是否存在有效的个人事件队列订阅。 */
    public boolean isSubscribed(String subject) {
        return subscribedSubjectBySession.containsValue(subject);
    }

    public MessagePrincipal remove(String sessionId) {
        subscribedSubjectBySession.remove(sessionId);
        String subject = subjectBySession.remove(sessionId);
        if (subject == null) {
            return null;
        }
        Set<String> sessions = sessionsBySubject.get(subject);
        if (sessions == null) {
            return null;
        }
        sessions.remove(sessionId);
        if (sessions.isEmpty()) {
            sessionsBySubject.remove(subject, sessions);
            return principalBySubject.remove(subject);
        }
        return null;
    }

    public boolean isConnected(String subject) {
        Set<String> sessions = sessionsBySubject.get(subject);
        return sessions != null && !sessions.isEmpty();
    }

    public int connectedSubjects() {
        return sessionsBySubject.size();
    }
}

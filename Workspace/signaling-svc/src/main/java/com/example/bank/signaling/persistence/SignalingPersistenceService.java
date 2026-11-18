package com.example.bank.signaling.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 信令事实落库：令牌签发登记主体、presence 在线/掉线更新连接与事件。
 *
 * <p>在线态仍以 Centrifugo presence 为准；这里只把观察到的连接事实持久化，
 * 供重启恢复、审计与后续性能/质量分析使用。</p>
 */
@Service
public class SignalingPersistenceService {

    private final SignalingSubjectRepository subjects;
    private final SignalingConnectionRepository connections;
    private final SignalingEventRepository events;
    private final ObjectMapper json;
    private final long eventThrottleMs;

    public SignalingPersistenceService(SignalingSubjectRepository subjects,
                                       SignalingConnectionRepository connections,
                                       SignalingEventRepository events,
                                       ObjectMapper json,
                                       @Value("${presence.event-throttle-ms:60000}") long eventThrottleMs) {
        this.subjects = subjects;
        this.connections = connections;
        this.events = events;
        this.json = json;
        this.eventThrottleMs = eventThrottleMs;
    }

    /** 令牌签发：主体 upsert，没有打开连接时开一条连接，写 TOKEN_ISSUED（及 CONNECTION_OPENED）。 */
    @Transactional
    public void tokenIssued(String subjectId, String role, String channel, long at) {
        subjects.upsert(subjectId, role, channel, at, false);
        if (connections.listOpen(subjectId).isEmpty()) {
            String connectionId = connections.open(subjectId, role, channel, at);
            events.append("CONNECTION_OPENED", subjectId, connectionId, at, payload(role, channel, "OPEN"));
        }
        events.append("TOKEN_ISSUED", subjectId, null, at, payload(role, channel, "ISSUED"));
    }

    /**
     * presence 在线：刷新最后在线时间；连接已关闭则新开一条（同令牌重连）。
     *
     * @param wasReported 本次 markPresent 是否清除了“已上报”标记；恢复时强制写一条 online 事件
     */
    @Transactional
    public void presenceOnline(String subjectId, String role, String channel, long at, boolean wasReported) {
        Long previousPresent = subjects.lastPresentAt(subjectId);
        subjects.touchPresent(subjectId, at);

        List<Map<String, Object>> open = connections.listOpen(subjectId);
        String connectionId;
        boolean opened = false;
        if (open.isEmpty()) {
            connectionId = connections.open(subjectId, role, channel, at);
            opened = true;
            events.append("CONNECTION_OPENED", subjectId, connectionId, at, payload(role, channel, "OPEN"));
        } else {
            connectionId = String.valueOf(open.get(0).get("connection_id"));
            connections.touch(connectionId, at);
        }

        boolean due = previousPresent == null || at - previousPresent >= eventThrottleMs;
        if (opened || wasReported || due) {
            events.append("PRESENCE_CHANGED", subjectId, connectionId, at, payload(role, channel, "ONLINE"));
        }
    }

    /** presence 掉线：关闭所有打开连接并写 CONNECTION_CLOSED + offline 事件；无打开连接时不重复写。 */
    @Transactional
    public void presenceOffline(String subjectId, String role, String channel, long at, String reason) {
        List<Map<String, Object>> open = connections.listOpen(subjectId);
        for (Map<String, Object> row : open) {
            String connectionId = String.valueOf(row.get("connection_id"));
            connections.close(connectionId, at, reason);
            events.append("CONNECTION_CLOSED", subjectId, connectionId, at, payload(role, channel, reason));
        }
        if (!open.isEmpty()) {
            events.append("PRESENCE_CHANGED", subjectId, null, at, payload(role, channel, "OFFLINE"));
        }
    }

    /** 掉线事实是否已上报给 business，随主体状态一起持久化，重启后保留。 */
    public void reported(String subjectId, boolean reported) {
        subjects.updateReported(subjectId, reported);
    }

    private String payload(String role, String channel, String state) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("role", role);
        body.put("channel", channel);
        body.put("state", state);
        try {
            return json.writeValueAsString(body);
        } catch (Exception error) {
            throw new IllegalStateException("信令事件序列化失败", error);
        }
    }
}

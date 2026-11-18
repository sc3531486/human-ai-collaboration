package com.example.bank.media;

import com.example.bank.media.persistence.MediaContext;
import com.example.bank.media.persistence.MediaEventRepository;
import com.example.bank.media.persistence.MediaIdentityResolver;
import com.example.bank.media.persistence.MediaParticipantRepository;
import com.example.bank.media.persistence.MediaRoomRepository;
import com.example.bank.media.port.MediaEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 媒体命令消费显式房间上下文，不回调业务或读写流程变量。 */
@Service
public class VideoRoomService {

    private final MediaEventPublisher events;

    private MediaRoomRepository roomRepository;
    private MediaParticipantRepository participantRepository;
    private MediaEventRepository eventRepository;
    private MediaIdentityResolver identityResolver;

    @Value("${media.platform-code:PEERJS}")
    private String platformCode = "PEERJS";

    public VideoRoomService(MediaEventPublisher events) {
        this.events = events;
    }

    @Autowired(required = false)
    public void setRoomRepository(MediaRoomRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    @Autowired(required = false)
    public void setParticipantRepository(MediaParticipantRepository participantRepository) {
        this.participantRepository = participantRepository;
    }

    @Autowired(required = false)
    public void setEventRepository(MediaEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Autowired(required = false)
    public void setIdentityResolver(MediaIdentityResolver identityResolver) {
        this.identityResolver = identityResolver;
    }

    public String open(String processId, Map<String, Object> context) {
        if (processId == null || processId.trim().isEmpty()) bad("缺少接待标识");
        required(context, "customer_id");
        required(context, "agent_session_id");
        String roomId = "ROOM-" + UUID.nameUUIDFromBytes(processId.getBytes(StandardCharsets.UTF_8)).toString();
        if (roomRepository != null) {
            Map<String, Object> existing = roomRepository.find(roomId);
            if (existing == null) {
                Integer max = MediaContext.integer(context, "max_participants");
                roomRepository.insert(roomId, MediaContext.bizKey(context), MediaContext.bizDate(context),
                        MediaContext.text(context, "room_name"), platformCode,
                        MediaContext.text(context, "platform_room_id"), max == null ? 0 : max);
                recordEvent(roomId, null, null, "ROOM_OPENED", 0L);
            } else if (roomRepository.reopen(roomId)) {
                recordEvent(roomId, null, null, "ROOM_OPENED", 0L);
            }
        }
        return roomId;
    }

    /** P2P模式无服务端流资源；业务方持久化关闭事实，成员收到挂断信令后销毁连接。 */
    public void close(String processId, Map<String, Object> context) {
        if (processId == null || processId.trim().isEmpty()) bad("缺少接待标识");
        String roomId = required(context, "roomId");
        if (roomRepository == null) return;
        long now = System.currentTimeMillis();
        boolean closed = roomRepository.close(roomId, now, MediaContext.text(context, "close_reason"));
        if (!closed) return;
        if (participantRepository != null) participantRepository.markAllLeft(roomId);
        recordEvent(roomId, null, null, "ROOM_CLOSED", 0L);
    }

    public void inviteParticipants(String processId, Map<String, Object> context) {
        String customer = required(context, "customer_id");
        String agent = required(context, "agent_session_id");
        String room = required(context, "video_room_id");
        if (!(context.get("call_participants") instanceof List) || ((List<?>) context.get("call_participants")).size() < 2) {
            bad("入房命令缺少完整成员");
        }
        persistParticipants(room, context);
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("domain", "video");
        event.put("type", "VIDEO_ROOM_READY");
        event.put("at", System.currentTimeMillis());
        event.put("processId", processId);
        event.put("roomId", room);
        event.put("customerId", customer);
        event.put("agentId", agent);
        event.put("agentPeerId", context.get("agent_peer_id"));
        event.put("bizNo", context.get("biz_no"));
        event.put("callId", context.get("call_id"));
        event.put("participants", context.get("call_participants"));
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("roomId", room);
        payload.put("customerId", customer);
        payload.put("agentId", agent);
        payload.put("event", event);
        events.publish("ROOM_READY", payload);
    }

    @SuppressWarnings("unchecked")
    private void persistParticipants(String roomId, Map<String, Object> context) {
        if (participantRepository == null || !(context.get("call_participants") instanceof List)) return;
        for (Object item : (List<?>) context.get("call_participants")) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> member = (Map<String, Object>) item;
            String actorId = text(member, "actorId");
            String role = text(member, "role");
            String peerId = text(member, "peerId");
            if (actorId == null || role == null) continue;
            String userId = resolveUserId(actorId, peerId);
            if (userId == null) continue;
            boolean changed = participantRepository.join(roomId, userId, role,
                    peerId == null ? actorId : peerId);
            if (changed) recordEvent(roomId, userId, null, "PARTICIPANT_JOINED", 0L);
        }
        if (roomRepository != null) {
            roomRepository.updateParticipantCount(roomId, participantRepository.countActive(roomId));
        }
    }

    private void recordEvent(String roomId, String userId, String recordingId, String type, long durationMs) {
        if (eventRepository == null) return;
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("platformCode", platformCode);
        eventRepository.record(roomId, userId, recordingId, type, durationMs, payload, null);
    }

    private String resolveUserId(String actorId, String peerId) {
        return identityResolver == null ? actorId : identityResolver.resolve(actorId, peerId);
    }

    private String text(Map<String, Object> context, String key) {
        Object value = context == null ? null : context.get(key);
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String required(Map<String, Object> context, String key) {
        String value = text(context, key);
        if (value == null) bad("房间上下文缺少：" + key);
        return value;
    }

    private void bad(String message) {
        throw new MediaServiceException(HttpStatus.BAD_REQUEST, "ROOM_CONTEXT", message);
    }
}

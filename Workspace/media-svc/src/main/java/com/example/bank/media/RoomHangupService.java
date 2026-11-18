package com.example.bank.media;

import com.example.bank.media.persistence.MediaEventRepository;
import com.example.bank.media.persistence.MediaIdentityResolver;
import com.example.bank.media.persistence.MediaParticipantRepository;
import com.example.bank.media.port.MediaEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 媒体挂断命令：关闭房间并向成员下发终止媒体连接的信令。 */
@Service
public class RoomHangupService {

    private final VideoRoomService rooms;
    private final MediaEventPublisher events;

    private MediaParticipantRepository participantRepository;
    private MediaEventRepository eventRepository;
    private MediaIdentityResolver identityResolver;

    public RoomHangupService(VideoRoomService rooms, MediaEventPublisher events) {
        this.rooms = rooms;
        this.events = events;
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

    @SuppressWarnings("unchecked")
    public void hangup(String roomId, Map<String, Object> input) {
        if (!roomId.equals(input.get("roomId")) || input.get("processId") == null
                || !(input.get("participants") instanceof List)) {
            throw new MediaServiceException(HttpStatus.BAD_REQUEST, "HANGUP_CONTEXT", "挂断命令缺少有效房间及成员上下文");
        }
        rooms.close(String.valueOf(input.get("processId")), input);
        for (Map<String, Object> member : (List<Map<String, Object>>) input.get("participants")) {
            persistLeave(roomId, member);
            Map<String, Object> event = new LinkedHashMap<String, Object>(input);
            event.put("type", "CALL_ENDED");
            event.put("processId", member.get("processId"));
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("roomId", roomId);
            payload.put("actorId", member.get("actorId"));
            payload.put("role", member.get("role"));
            payload.put("event", event);
            events.publish("CALL_ENDED", payload);
        }
        org.slf4j.LoggerFactory.getLogger(getClass())
                .info("房间挂断命令已执行 roomId={} eventId={}", roomId, input.get("eventId"));
    }

    private void persistLeave(String roomId, Map<String, Object> member) {
        String actorId = text(member, "actorId");
        String peerId = text(member, "peerId");
        String userId = identityResolver == null ? actorId : identityResolver.resolve(actorId, peerId);
        if (userId == null) return;
        if (participantRepository != null) participantRepository.leave(roomId, userId);
        if (eventRepository != null) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("initiatorRole", member.get("role"));
            eventRepository.record(roomId, userId, null, "PARTICIPANT_LEFT", 0L, payload, null);
        }
    }

    private String text(Map<String, Object> member, String key) {
        Object value = member == null ? null : member.get(key);
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}

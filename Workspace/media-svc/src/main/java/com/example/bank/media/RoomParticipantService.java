package com.example.bank.media;

import com.example.bank.media.persistence.MediaEventRepository;
import com.example.bank.media.persistence.MediaIdentityResolver;
import com.example.bank.media.persistence.MediaParticipantRepository;
import com.example.bank.media.persistence.MediaRoomRepository;
import com.example.bank.media.port.MediaEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 房间成员控制命令；成员身份和加入许可由业务服务确定。 */
@Service
public class RoomParticipantService {

    private final MediaEventPublisher events;

    private MediaParticipantRepository participantRepository;
    private MediaEventRepository eventRepository;
    private MediaRoomRepository roomRepository;
    private MediaIdentityResolver identityResolver;

    public RoomParticipantService(MediaEventPublisher events) {
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
    public void setRoomRepository(MediaRoomRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    @Autowired(required = false)
    public void setIdentityResolver(MediaIdentityResolver identityResolver) {
        this.identityResolver = identityResolver;
    }

    @SuppressWarnings("unchecked")
    public void update(String roomId, Map<String, Object> input) {
        if (!roomId.equals(input.get("roomId")) || !(input.get("participants") instanceof List)) {
            throw new MediaServiceException(HttpStatus.BAD_REQUEST, "ROOM_MEMBERS", "缺少房间成员上下文");
        }
        for (Map<String, Object> member : (List<Map<String, Object>>) input.get("participants")) {
            persistJoin(roomId, member);
            Map<String, Object> event = new LinkedHashMap<String, Object>(input);
            event.put("processId", member.get("processId"));
            event.put("participantRole", member.get("role"));
            event.put("type", member.get("actorId").equals(input.get("joiningActorId"))
                    ? "VIDEO_ROOM_READY" : "ROOM_PARTICIPANTS_CHANGED");
            notify(roomId, member, event);
        }
        if (input.get("leavingMember") instanceof Map) {
            Map<String, Object> leaving = (Map<String, Object>) input.get("leavingMember");
            persistLeave(roomId, leaving);
            Map<String, Object> event = new LinkedHashMap<String, Object>(input);
            event.put("type", "PARTICIPANT_RELEASED");
            event.put("processId", leaving.get("processId"));
            event.put("actorId", leaving.get("actorId"));
            notify(roomId, leaving, event);
        }
        refreshCount(roomId);
    }

    private void persistJoin(String roomId, Map<String, Object> member) {
        if (participantRepository == null) return;
        String actorId = text(member, "actorId");
        String role = text(member, "role");
        String peerId = text(member, "peerId");
        if (actorId == null || role == null) return;
        String userId = identityResolver == null ? actorId : identityResolver.resolve(actorId, peerId);
        if (userId == null) return;
        boolean changed = participantRepository.join(roomId, userId, role, peerId == null ? actorId : peerId);
        if (changed && eventRepository != null) {
            eventRepository.record(roomId, userId, null, "PARTICIPANT_JOINED", 0L, payload(role), null);
        }
    }

    private void persistLeave(String roomId, Map<String, Object> member) {
        if (participantRepository == null) return;
        String actorId = text(member, "actorId");
        String peerId = text(member, "peerId");
        String userId = identityResolver == null ? actorId : identityResolver.resolve(actorId, peerId);
        if (userId == null) return;
        boolean left = participantRepository.leave(roomId, userId);
        if (left && eventRepository != null) {
            eventRepository.record(roomId, userId, null, "PARTICIPANT_LEFT", 0L, payload(null), null);
        }
    }

    private void refreshCount(String roomId) {
        if (roomRepository != null && participantRepository != null) {
            roomRepository.updateParticipantCount(roomId, participantRepository.countActive(roomId));
        }
    }

    private Map<String, Object> payload(String role) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        if (role != null) payload.put("participantRole", role);
        return payload;
    }

    private String text(Map<String, Object> member, String key) {
        Object value = member == null ? null : member.get(key);
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private void notify(String roomId, Map<String, Object> member, Map<String, Object> event) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("roomId", roomId);
        payload.put("actorId", member.get("actorId"));
        payload.put("role", member.get("role"));
        payload.put("event", event);
        events.publish(String.valueOf(event.get("type")), payload);
    }
}

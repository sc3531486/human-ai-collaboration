package com.example.bank.media;

import com.example.bank.media.port.MediaEventPublisher;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class VideoRoomCommandTest {

    private final MediaEventPublisher events = mock(MediaEventPublisher.class);
    private final VideoRoomService rooms = new VideoRoomService(events);

    @Test
    void repeatedOpenIsStableWithoutPublishingAnything() {
        Map<String, Object> context = context();
        assertEquals(rooms.open("p1", context), rooms.open("p1", context));
        assertNotEquals(rooms.open("p1", context), rooms.open("p2", context));
        verifyNoInteractions(events);
    }

    @Test
    void invitationPublishesRoomReadyForCustomerAndAgent() {
        rooms.inviteParticipants("p1", context());
        verify(events).publish(eq("ROOM_READY"), argThat(payload -> {
            Map<?, ?> event = (Map<?, ?>) payload.get("event");
            return "c1".equals(payload.get("customerId"))
                    && "a1".equals(payload.get("agentId"))
                    && "call1".equals(event.get("callId"))
                    && ((List<?>) event.get("participants")).size() == 2;
        }));
    }

    @Test
    void missingMembersCannotSendPartialInvitation() {
        Map<String, Object> context = context();
        context.remove("call_participants");
        assertThrows(MediaServiceException.class, () -> rooms.inviteParticipants("p1", context));
        verifyNoInteractions(events);
    }

    private Map<String, Object> context() {
        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put("customer_id", "c1");
        vars.put("agent_session_id", "a1");
        vars.put("video_room_id", "r1");
        vars.put("call_id", "call1");
        vars.put("call_participants", Arrays.asList(
                Collections.singletonMap("actorId", "a1"),
                Collections.singletonMap("actorId", "c1")));
        return vars;
    }
}

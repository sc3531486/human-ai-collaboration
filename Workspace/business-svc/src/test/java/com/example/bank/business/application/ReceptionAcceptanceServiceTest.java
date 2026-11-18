package com.example.bank.business.application;

import com.example.bank.business.port.*;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ReceptionAcceptanceServiceTest {
    private final ProcessPort process=mock(ProcessPort.class);
    private final SessionPort sessions=mock(SessionPort.class);
    private final MediaPort media=mock(MediaPort.class);
    private final CallSessionService calls=mock(CallSessionService.class);
    private final ReceptionAcceptanceService service=new ReceptionAcceptanceService(sessions,process,media,calls);
    @Test void businessOrchestratesClaimRoomAndInvitationInOrder() {
        Map<String,Object> context=Collections.<String,Object>singletonMap("customer_id","c1");
        Map<String,Object> room=Collections.<String,Object>singletonMap("roomId","r1");
        when(process.variables("p1")).thenReturn(context);
        when(media.openRoom("p1",context)).thenReturn(room);
        when(calls.register(eq("p1"),anyMap(),eq("r1"))).thenReturn(Collections.<String,Object>singletonMap("callId","call1"));
        assertSame(room,service.accept("p1","a1","peer1"));
        InOrder order=inOrder(sessions,process,media);
        order.verify(sessions).claim("p1","a1","peer1");
        order.verify(process).variables("p1");
        order.verify(media).openRoom(eq("p1"),anyMap());
        order.verify(process).setVariables(eq("p1"),argThat(value -> "r1".equals(value.get("video_room_id"))));
        order.verify(media).inviteRoom(eq("p1"),argThat(value -> "r1".equals(value.get("video_room_id")) && "call1".equals(value.get("call_id"))));
    }
    @Test void claimFailureCannotCreateOrInviteMedia() {
        doThrow(new IllegalStateException("conflict")).when(sessions).claim("p1","a1","peer1");
        assertThrows(IllegalStateException.class,()->service.accept("p1","a1","peer1"));
        verifyNoInteractions(process,media);
    }
    @Test void invitationFailureIsNotSuccessfulAcceptance() {
        Map<String,Object> context=Collections.emptyMap();
        when(process.variables("p1")).thenReturn(context);
        when(media.openRoom("p1",context)).thenReturn(Collections.<String,Object>singletonMap("roomId","r1"));
        when(calls.register(eq("p1"),anyMap(),eq("r1"))).thenReturn(Collections.<String,Object>singletonMap("callId","call1"));
        doThrow(new IllegalStateException("delivery failed")).when(media).inviteRoom(eq("p1"),anyMap());
        assertThrows(IllegalStateException.class,()->service.accept("p1","a1","peer1"));
        verify(media).closeRoom("p1");
        verify(sessions).releaseClaim("p1","a1");
    }
}

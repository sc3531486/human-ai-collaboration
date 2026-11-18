package com.example.bank.business.controller;
import com.example.bank.business.port.ProcessPort;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class InternalServiceContextControllerTest {
    private final ProcessPort process=mock(ProcessPort.class);
    private final InternalServiceContextController controller=new InternalServiceContextController(process,null,null,null,null,null,null);
    @Test void mediaCanOnlyWriteMediaState() {
        assertThrows(RuntimeException.class,()->controller.mediaState("p1",Collections.<String,Object>singletonMap("amount",100)));
        verifyNoInteractions(process);
        Map<String,Object> state=Collections.<String,Object>singletonMap("video_room_state","OPEN");
        controller.mediaState("p1",state); verify(process).setVariables("p1",state);
    }
    @Test void contextDoesNotExposeTransactionFields() {
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"calls",mock(com.example.bank.business.application.CallSessionService.class));
        Map<String,Object> vars=new HashMap<>();vars.put("customer_id","c1");vars.put("amount",100);vars.put("payee_account","123");
        when(process.variables("p1")).thenReturn(vars);
        assertEquals(Collections.singletonMap("customer_id","c1"),controller.context("p1"));
    }
}

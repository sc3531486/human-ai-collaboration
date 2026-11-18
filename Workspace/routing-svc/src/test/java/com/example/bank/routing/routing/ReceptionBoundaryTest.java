package com.example.bank.routing.routing;
import com.example.bank.routing.port.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ReceptionBoundaryTest {
    @Test void failedBusinessReceptionCannotMarkTicketAnsweredOrOccupyAgent() {
        AgentRegistry agents=new AgentRegistry();agents.register("a1","测试坐席","",Arrays.asList("CUSTOM"));agents.setStatus("a1","ONLINE");
        ProcessPort process=mock(ProcessPort.class);ProductEventPublisher events=mock(ProductEventPublisher.class);
        when(process.isNodeActive(anyString(),anyString())).thenReturn(true);
        QueueRoutingService routing=new QueueRoutingService(Arrays.<RoutingPool>asList(new AgentRoutingPool(agents),
                new DigitalHumanRoutingPool(new DigitalHumanRegistry())),events,process,agents,30000);
        QueueRequest request=new QueueRequest();request.processId="p1";request.handledNode="human";request.handlerType="HUMAN";request.skillGroup="CUSTOM";
        QueueTicket ticket=routing.route(request);assertEquals("RINGING",ticket.status);
        doThrow(new IllegalStateException("invite failed")).when(process).claimSession("p1","a1","peer1");
        assertThrows(IllegalStateException.class,()->routing.answer("a1","p1","peer1"));
        assertEquals("RINGING",ticket.status);assertEquals(0,agents.get("a1").serving);
        doNothing().when(process).claimSession("p1","a1","peer1");
        routing.answer("a1","p1","peer1");assertEquals("ANSWERED",ticket.status);assertEquals(1,agents.get("a1").serving);
    }
}

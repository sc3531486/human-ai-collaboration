package com.example.bank.workflow.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeNotificationOutboxTest {
    @Test void deliveryFailureKeepsSameEventForRetryAndNeverBroadcastsToOtherAudience() {
        JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:node-message-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        RestTemplate http=mock(RestTemplate.class);
        NodeNotificationOutbox outbox=new NodeNotificationOutbox(jdbc,http,new ObjectMapper(),"http://business");outbox.initialize();
        Map<String,Object> event=new HashMap<>();event.put("eventId","p1:2");event.put("type","NODE_STATE");
        outbox.enqueue("customer","c1",event);outbox.enqueue("customer","c1",event);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM workflow_node_notification",Integer.class));
        when(http.postForObject(eq("http://business/internal/notifications/{audience}/{subject}"),anyMap(),eq(Map.class),eq("customer"),eq("c1"))).thenThrow(new IllegalStateException("unavailable")).thenReturn(Collections.singletonMap("delivered",true));
        outbox.publish();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM workflow_node_notification WHERE delivered=FALSE",Integer.class));
        outbox.publish();assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM workflow_node_notification WHERE delivered=FALSE",Integer.class));
        outbox.publish();verify(http,times(2)).postForObject(anyString(),anyMap(),eq(Map.class),eq("customer"),eq("c1"));
    }
}

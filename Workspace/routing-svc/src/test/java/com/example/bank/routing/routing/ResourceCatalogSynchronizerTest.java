package com.example.bank.routing.routing;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceCatalogSynchronizerTest {
    private final AgentRegistry agents=new AgentRegistry();
    private final DigitalHumanRegistry digital=new DigitalHumanRegistry();
    private final RestTemplate http=mock(RestTemplate.class);
    private final ResourceCatalogSynchronizer sync=new ResourceCatalogSynchronizer(http,"http://business",agents,digital);
    private Map<String,Object> catalog() {
        Map<String,Object> row=new HashMap<>(); row.put("agentId","custom-agent");row.put("name","配置坐席");row.put("skills",Arrays.asList("CUSTOM"));
        Map<String,Object> dh=new HashMap<>();dh.put("digitalHumanId","custom-digital");dh.put("name","配置数字人");dh.put("skills",Arrays.asList("CUSTOM"));dh.put("status","ONLINE");
        Map<String,Object> catalog=new HashMap<>();catalog.put("agents",Arrays.asList(row));catalog.put("digitalHumans",Arrays.asList(dh));return catalog;
    }
    @Test void registriesStartEmptyAndOnlyUseApiData() {
        assertTrue(agents.all().isEmpty()); assertTrue(digital.all().isEmpty());
        when(http.getForObject("http://business/internal/mock/resources",Map.class)).thenReturn(catalog());
        sync.refresh();
        assertEquals("custom-agent",agents.all().get(0).agentId);
        assertEquals("OFFLINE",agents.all().get(0).status);
        assertEquals("custom-digital",digital.all().get(0).digitalHumanId);
        assertEquals("",digital.all().get(0).peerId,"目录不能伪造媒体用户");
    }
    @Test void apiFailureCannotGenerateDemoResources() {
        when(http.getForObject(anyString(),eq(Map.class))).thenThrow(new IllegalStateException("offline"));
        assertThrows(IllegalStateException.class,()->sync.refresh());
        assertTrue(agents.all().isEmpty());assertTrue(digital.all().isEmpty());
    }
    @Test void metadataRefreshDoesNotActAsHeartbeatOrResetLiveOccupancy() {
        when(http.getForObject(anyString(),eq(Map.class))).thenReturn(catalog());
        sync.refresh();agents.setStatus("custom-agent","ONLINE");
        AgentRegistry.Agent agent=agents.all().get(0);agent.lastActive=123L;agent.serving=1;
        digital.addServing("custom-digital",1);
        sync.refresh();
        assertEquals(123L,agent.lastActive);assertEquals(1,agent.serving);assertEquals("ONLINE",agent.status);
        assertEquals("BUSY",digital.all().get(0).status);
    }
}

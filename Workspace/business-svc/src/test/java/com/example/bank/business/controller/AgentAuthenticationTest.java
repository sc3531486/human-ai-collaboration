package com.example.bank.business.controller;

import com.example.bank.business.config.AgentSessionFilter;
import com.example.bank.business.port.AgentPort;
import com.example.bank.business.service.AgentLoginService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentAuthenticationTest {
    private final AgentLoginService accounts=mock(AgentLoginService.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final AgentPort agents=mock(AgentPort.class);
    private final AgentLoginController controller=new AgentLoginController(accounts,jdbc,agents);

    @Test void loginRotatesSessionAndDoesNotTrustBrowserIdentity() {
        Map<String,Object> profile=new HashMap<>();profile.put("agentId","AGENT-1");
        when(accounts.login("1","1")).thenReturn(profile);
        MockHttpServletRequest request=new MockHttpServletRequest();
        MockHttpSession old=new MockHttpSession();request.setSession(old);
        Map<String,String> body=new HashMap<>();body.put("username","1");body.put("password","1");
        controller.login(body,request);
        assertTrue(old.isInvalid());assertEquals("AGENT-1",request.getSession().getAttribute("agentId"));
        assertNotNull(profile.get("csrfToken"));
    }

    @Test void logoutRefusesActiveCallAndKeepsSession() {
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq("AGENT-1"))).thenReturn(1);
        MockHttpServletRequest request=sessionRequest();
        assertThrows(ResponseStatusException.class,()->controller.logout(request));
        assertNotNull(request.getSession(false));verifyNoInteractions(agents);
    }

    @Test void logoutSetsOfflineBeforeInvalidatingSession() {
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq("AGENT-1"))).thenReturn(0);
        when(agents.all()).thenReturn(Collections.emptyList());
        Map<String,Object> profile=new HashMap<>();profile.put("name","坐席1");profile.put("loginName","1");when(accounts.profile("AGENT-1")).thenReturn(profile);
        MockHttpServletRequest request=sessionRequest();MockHttpSession session=(MockHttpSession)request.getSession();
        controller.logout(request);
        verify(agents).setStatus("AGENT-1","坐席1","","","OFFLINE");assertTrue(session.isInvalid());
    }

    @Test void sessionFilterRejectsAnonymousForgedIdentityAndMissingCsrf() throws Exception {
        AgentSessionFilter filter=new AgentSessionFilter(new ObjectMapper(),accounts);
        MockHttpServletRequest anonymous=new MockHttpServletRequest("GET","/api/platform/agent/AGENT-1/calls");
        MockHttpServletResponse response=new MockHttpServletResponse();filter.doFilter(anonymous,response,new MockFilterChain());assertEquals(401,response.getStatus());
        MockHttpServletRequest forged=sessionRequest();forged.setRequestURI("/api/platform/agent/AGENT-2/calls");forged.setMethod("GET");
        response=new MockHttpServletResponse();filter.doFilter(forged,response,new MockFilterChain());assertEquals(403,response.getStatus());
        MockHttpServletRequest csrf=sessionRequest();csrf.setRequestURI("/api/platform/agent-logout");csrf.setMethod("POST");
        response=new MockHttpServletResponse();filter.doFilter(csrf,response,new MockFilterChain());assertEquals(403,response.getStatus());
    }

    private MockHttpServletRequest sessionRequest() {
        MockHttpServletRequest request=new MockHttpServletRequest();request.getSession().setAttribute("agentId","AGENT-1");request.getSession().setAttribute("csrfToken","token");return request;
    }
}

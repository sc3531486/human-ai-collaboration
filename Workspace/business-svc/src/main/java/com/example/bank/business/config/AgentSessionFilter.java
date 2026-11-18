package com.example.bank.business.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.Collections;

/** 公共坐席入口鉴权：会话身份不能被URL里的坐席号覆盖。 */
@Component
public class AgentSessionFilter extends OncePerRequestFilter {
    private final ObjectMapper json;
    private final com.example.bank.business.service.AgentLoginService accounts;
    public AgentSessionFilter(ObjectMapper json, com.example.bank.business.service.AgentLoginService accounts) { this.json=json; this.accounts=accounts; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=request.getRequestURI().replaceFirst("^/api/workbench/", "/api/platform/");
        boolean protectedPath=path.startsWith("/api/platform/agent/") || path.startsWith("/api/platform/call/") || path.equals("/api/platform/queue") || path.equals("/api/platform/agent-profile") || path.equals("/api/platform/agents") || path.equals("/api/agent/heartbeat") || path.startsWith("/api/agent-service-summaries") || path.equals("/api/platform/agent-session") || path.equals("/api/platform/agent-logout");
        if (!protectedPath) { chain.doFilter(request,response); return; }
        HttpSession session=request.getSession(false);
        if(session==null || session.getAttribute("agentId")==null) { fail(response,401,"请先登录");return; }
        String agentId=String.valueOf(session.getAttribute("agentId"));
        try { accounts.profile(agentId); }
        catch (org.springframework.web.server.ResponseStatusException error) { session.invalidate(); fail(response,401,"账号或所属组织已停用，请重新登录");return; }
        String queryId=request.getParameter("agentId");
        if ((queryId!=null && !queryId.equals(agentId)) || (path.startsWith("/api/platform/agent/") && !path.split("/")[4].equals(agentId))) { fail(response,403,"不能操作其他坐席");return; }
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod()) && !String.valueOf(session.getAttribute("csrfToken")).equals(request.getHeader("X-CSRF-Token"))) { fail(response,403,"请求校验失败，请重新登录");return; }
        chain.doFilter(request,response);
    }
    private void fail(HttpServletResponse response,int status,String message)throws IOException { response.setStatus(status);response.setContentType("application/json;charset=UTF-8");json.writeValue(response.getWriter(),Collections.singletonMap("message",message)); }
}

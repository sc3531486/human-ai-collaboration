package com.example.bank.business.controller;

import com.example.bank.business.service.AgentLoginService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.bank.business.port.AgentPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 坐席工作台的演示登录入口。 */
@RestController
@RequestMapping("/api/platform")
public class AgentLoginController {
    private final AgentLoginService accounts;
    private final JdbcTemplate jdbc;
    private final AgentPort agents;

    public AgentLoginController(AgentLoginService accounts, JdbcTemplate jdbc, AgentPort agents) { this.accounts = accounts; this.jdbc = jdbc; this.agents = agents; }

    @PostMapping("/agent-login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Map<String,Object> profile = accounts.login(body.get("username"), body.get("password"));
        HttpSession old = request.getSession(false); if (old != null) old.invalidate();
        HttpSession session = request.getSession(true);
        session.setAttribute("agentId", profile.get("agentId"));
        session.setAttribute("csrfToken", UUID.randomUUID().toString());
        session.setMaxInactiveInterval(1800);
        profile.put("csrfToken", session.getAttribute("csrfToken"));
        return profile;
    }

    @GetMapping("/agent-session")
    public Map<String,Object> current(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("agentId") == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
        Map<String,Object> profile = accounts.profile(String.valueOf(session.getAttribute("agentId")));
        profile.put("csrfToken", session.getAttribute("csrfToken")); return profile;
    }

    @PostMapping("/agent-logout")
    public Map<String,Object> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("agentId") == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
        String id = String.valueOf(session.getAttribute("agentId"));
        if (jdbc.queryForObject("SELECT COUNT(*) FROM biz_call_session WHERE agent_id=? AND status='OPEN'", Integer.class, id) > 0) throw new ResponseStatusException(HttpStatus.CONFLICT,"请先挂断当前通话再签退");
        for (com.example.bank.business.contract.Agent agent : agents.all()) if (id.equals(agent.agentId) && agent.serving > 0) throw new ResponseStatusException(HttpStatus.CONFLICT,"请先完成当前业务再签退");
        Map<String,Object> profile = accounts.profile(id);
        agents.setStatus(id, String.valueOf(profile.get("name")), "", "", "OFFLINE");
        accounts.audit(String.valueOf(profile.get("loginName")),id,"LOGOUT");
        session.invalidate();
        return java.util.Collections.<String,Object>singletonMap("loggedOut",true);
    }
}

package com.example.bank.business.config;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import javax.servlet.http.*;
import java.lang.reflect.Type;
import java.util.Map;

/** 所有显式坐席写操作必须与登录会话一致；内部服务接口另属服务间边界。 */
@ControllerAdvice
public class AgentIdentityAdvice extends RequestBodyAdviceAdapter {
    public boolean supports(MethodParameter p,Type t,Class<? extends HttpMessageConverter<?>> c) { return true; }
    public Object afterBodyRead(Object body,HttpInputMessage input,MethodParameter parameter,Type type,Class<? extends HttpMessageConverter<?>> converter) {
        ServletRequestAttributes attributes=(ServletRequestAttributes)RequestContextHolder.getRequestAttributes();
        if(attributes==null || !(body instanceof Map)) return body;
        HttpServletRequest request=attributes.getRequest();
        if(!request.getRequestURI().startsWith("/api/")) return body;
        Map<?,?> values=(Map<?,?>)body;
        Object actor=values.get("agentId");
        if(actor==null && "AGENT".equals(values.get("role"))) actor=values.get("subject");
        if(actor==null && "AGENT".equals(values.get("actorRole"))) actor=values.get("actorId");
        if(actor==null || String.valueOf(actor).isEmpty()) return body;
        HttpSession session=request.getSession(false);
        if(session==null || session.getAttribute("agentId")==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
        if(!actor.equals(session.getAttribute("agentId"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"坐席身份与登录会话不一致");
        if(!String.valueOf(session.getAttribute("csrfToken")).equals(request.getHeader("X-CSRF-Token"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请求校验失败，请重新登录");
        return body;
    }
}

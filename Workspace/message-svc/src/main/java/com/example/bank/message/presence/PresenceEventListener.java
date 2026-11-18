package com.example.bank.message.presence;

import com.example.bank.message.auth.MessagePrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

@Component
public class PresenceEventListener {

    private static final Logger log = LoggerFactory.getLogger(PresenceEventListener.class);

    private final ConnectionRegistry registry;
    private final PresenceEventPort eventPort;

    public PresenceEventListener(ConnectionRegistry registry, PresenceEventPort eventPort) {
        this.registry = registry;
        this.eventPort = eventPort;
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        if (!(accessor.getUser() instanceof MessagePrincipal)) {
            // 连接已建立却取不到已认证主体，说明认证链路被绕过或状态丢失，必须显式失败。
            throw new IllegalStateException("会话已连接但缺少已认证主体，sessionId=" + accessor.getSessionId());
        }
        registry.register(accessor.getSessionId(), (MessagePrincipal) accessor.getUser());
    }

    @EventListener
    public void onSubscribed(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        if (!(accessor.getUser() instanceof MessagePrincipal)) {
            throw new IllegalStateException("订阅发生但缺少已认证主体，sessionId=" + accessor.getSessionId());
        }
        registry.markSubscribed(accessor.getSessionId());
    }

    @EventListener
    public void onUnsubscribed(SessionUnsubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        registry.clearSubscribed(accessor.getSessionId());
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        MessagePrincipal principal = registry.remove(event.getSessionId());
        if (principal != null && "agent".equals(principal.getRole())) {
            try {
                eventPort.disconnected(principal.getSubject(), "PRESENCE_STOMP_DISCONNECT",
                        "STOMP_DISCONNECT", "agent:" + principal.getSubject());
            } catch (PresenceEventDeliveryException ex) {
                // 规格：CONTRACTS.md 第三节「上报失败只记日志、不重试」——重试会把
                // 已恢复坐席的过期离线事件补出去。此处按契约记日志且不重试；
                // 与旧实现不同，失败不再在适配器内部被静默吞掉，而是以带原因的
                // 异常浮到事件边界，按 ERROR 级别留下可告警的失败事件。
                log.error("坐席掉线事件上报失败（按契约不重试）：agentId={}, endpoint={}, 原因={}",
                        ex.getAgentId(), ex.getEndpoint(), ex.getCause().toString(), ex);
            }
        }
    }
}

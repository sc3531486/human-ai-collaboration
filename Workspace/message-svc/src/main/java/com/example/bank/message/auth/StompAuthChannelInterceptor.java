package com.example.bank.message.auth;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final MessageTokenService tokenService;

    public StompAuthChannelInterceptor(MessageTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message,StompHeaderAccessor.class);
        if(accessor==null)throw new MessagingException("缺少STOMP连接头");
        StompCommand command = accessor.getCommand();
        if (command == null) {
            // 心跳帧：解码器对空命令帧（客户端 "\n" 心跳）生成 StompHeaderAccessor，
            // 按 STOMP 协议 heart-beat 约定原样放行——这是协议行为，不是容错兜底。
            return message;
        }
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            // 只认契约头 authorization；不再接受未登记的别名头，缺失即认证失败。
            String rawToken = accessor.getFirstNativeHeader("authorization");
            try {
                accessor.setUser(tokenService.authenticate(rawToken));
            } catch (RuntimeException ex) {
                throw new MessagingException("WebSocket认证失败", ex);
            }
        } else if (command == StompCommand.SUBSCRIBE) {
            requireUser(accessor);
            if (!"/user/queue/events".equals(accessor.getDestination())) {
                throw new MessagingException("仅允许订阅个人事件队列");
            }
        } else if (command == StompCommand.SEND) {
            throw new MessagingException("客户端不允许向消息服务发送业务消息");
        } else if (command == StompCommand.DISCONNECT) {
            requireUser(accessor);
        }
        return message;
    }

    private void requireUser(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof MessagePrincipal)) {
            throw new MessagingException("WebSocket未认证");
        }
    }
}

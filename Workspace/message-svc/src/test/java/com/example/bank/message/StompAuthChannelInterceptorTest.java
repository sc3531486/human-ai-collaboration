package com.example.bank.message;
import com.example.bank.message.auth.*;
import com.example.bank.message.config.MessageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import java.util.concurrent.atomic.AtomicReference;
import java.security.Principal;
import static org.junit.jupiter.api.Assertions.*;
class StompAuthChannelInterceptorTest {
    @Test void authenticatesTheActualConnectAccessorAndUpdatesTheWebSocketSessionPrincipal() {
        MessageTokenService tokens=new MessageTokenService(new MessageProperties());
        StompAuthChannelInterceptor interceptor=new StompAuthChannelInterceptor(tokens);
        StompHeaderAccessor accessor=StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("authorization","Bearer "+tokens.issue("cust-session","customer"));
        AtomicReference<Principal> sessionUser=new AtomicReference<>();accessor.setUserChangeCallback(sessionUser::set);accessor.setLeaveMutable(true);
        Message<byte[]> message=MessageBuilder.createMessage(new byte[0],accessor.getMessageHeaders());
        assertSame(message,interceptor.preSend(message,null));assertNotNull(sessionUser.get());assertEquals("cust-session",sessionUser.get().getName());
        StompHeaderAccessor subscribe=StompHeaderAccessor.create(StompCommand.SUBSCRIBE);subscribe.setUser(sessionUser.get());subscribe.setDestination("/user/queue/events");subscribe.setLeaveMutable(true);
        assertDoesNotThrow(()->interceptor.preSend(MessageBuilder.createMessage(new byte[0],subscribe.getMessageHeaders()),null));
        subscribe.setDestination("/topic/other-customer");assertThrows(MessagingException.class,()->interceptor.preSend(MessageBuilder.createMessage(new byte[0],subscribe.getMessageHeaders()),null));
    }
    @Test void rejectsConnectUsingUndocumentedTokenHeader() {
        MessageTokenService tokens=new MessageTokenService(new MessageProperties());
        StompAuthChannelInterceptor interceptor=new StompAuthChannelInterceptor(tokens);
        StompHeaderAccessor accessor=StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("token","Bearer "+tokens.issue("cust-session","customer"));
        accessor.setLeaveMutable(true);
        Message<byte[]> message=MessageBuilder.createMessage(new byte[0],accessor.getMessageHeaders());
        assertThrows(MessagingException.class,()->interceptor.preSend(message,null));
    }
    @Test void passesThroughProtocolHeartbeatWithoutCommand() {
        MessageTokenService tokens=new MessageTokenService(new MessageProperties());
        StompAuthChannelInterceptor interceptor=new StompAuthChannelInterceptor(tokens);
        StompHeaderAccessor heartbeat=StompHeaderAccessor.createForHeartbeat();
        heartbeat.setLeaveMutable(true);
        Message<byte[]> message=MessageBuilder.createMessage(new byte[0],heartbeat.getMessageHeaders());
        assertSame(message,interceptor.preSend(message,null));
    }
}

package com.example.bank.business.controller;

import com.example.bank.business.port.*;
import com.example.bank.business.contract.QueueTicket;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchControllerTest {
    @Test void legacyAcceptUsesRoutingInsteadOfBypassingTicketOwnership() {
        RoutingPort routing=mock(RoutingPort.class);
        SessionPort sessions=mock(SessionPort.class);
        ProcessPort process=mock(ProcessPort.class);
        WorkbenchController controller=new WorkbenchController(null,process,sessions,routing,null,null);
        Map<String,String> request=new HashMap<>();request.put("agentId","agent1");request.put("peerId","peer1");
        Map<String,Object> snapshot=Collections.<String,Object>singletonMap("roomId","room1");
        when(process.state("p1")).thenReturn(snapshot);
        assertSame(snapshot,controller.accept("p1",request));
        InOrder order=inOrder(routing,process);
        order.verify(routing).answer("agent1","p1","peer1");
        order.verify(process).state("p1");
        verifyNoInteractions(sessions);
    }

    @Test void legacyAcceptCannotRecoverFromRejectedRoutingByClaiming() {
        RoutingPort routing=mock(RoutingPort.class); SessionPort sessions=mock(SessionPort.class);
        ProcessPort process=mock(ProcessPort.class);
        WorkbenchController controller=new WorkbenchController(null,process,sessions,routing,null,null);
        Map<String,String> request=new HashMap<>();request.put("agentId","agent1");request.put("peerId","peer1");
        when(routing.answer("agent1","p1","peer1")).thenThrow(new IllegalStateException("call expired"));
        assertThrows(IllegalStateException.class,()->controller.accept("p1",request));
        verifyNoInteractions(sessions,process);
    }
    /**
     * 接听只做两件事：把接听交给路由服务，然后按顺序读回现场（节点快照 + 界面还原）。
     *
     * <p><b>任务认领与开房都不在本服务</b>：CONTRACTS.md 第四节把
     * {@code /internal/process/{processId}/session/claim} 的声明方登记为 routing-svc，
     * 第五节把 {@code /internal/room/{processId}/open} 的声明方登记为
     * routing-svc「坐席接听后开房」，两者都由 routing-svc 的
     * {@code QueueRoutingService.answer()} 在同一次调用里完成。本服务再认领一次，
     * 会与正在抢单的坐席撞在同一次变量写入上；再开一次房，会绕过
     * 「同一 processId 必须返回同一 roomId」的幂等约束。</p>
     *
     * <p>顺序仍有意义：快照与轨迹必须在接听成功之后读。反过来先读快照，
     * 拿到的是接听前的现场，坐席界面会停在没有归属的状态上。</p>
     */
    @Test void answeringDelegatesToRoutingThenReadsStateAndTrailInOrder() {
        ProcessPort process=mock(ProcessPort.class); SessionPort sessions=mock(SessionPort.class);
        RoutingPort routing=mock(RoutingPort.class);
        WorkbenchController controller=new WorkbenchController(null,process,sessions,routing,null,null);
        Map<String,String> request=new HashMap<>();request.put("agentId","AGENT-9527");request.put("peerId","bank-agent-9527");
        when(routing.answer("AGENT-9527","p1","bank-agent-9527")).thenReturn(new QueueTicket());
        when(process.state("p1")).thenReturn(new HashMap<String,Object>());
        when(process.trail("p1")).thenReturn(new HashMap<String,Object>());
        Map<String,Object> result=controller.answer("p1",request);
        assertEquals(true,result.get("answered"));
        assertEquals(true,result.containsKey("ticket"));
        InOrder order=inOrder(routing,process);
        order.verify(routing).answer("AGENT-9527","p1","bank-agent-9527");
        order.verify(process).state("p1");
        order.verify(process).trail("p1");
        // 归属由路由服务在接听时一并写入流程变量，本服务不得重复认领
        verify(sessions,never()).claim(anyString(),anyString(),anyString());
    }

    @Test void rejectedCallsCannotClaimTheWorkflow() {
        ProcessPort process=mock(ProcessPort.class);SessionPort sessions=mock(SessionPort.class);RoutingPort routing=mock(RoutingPort.class);
        WorkbenchController controller=new WorkbenchController(null,process,sessions,routing,null,null);
        Map<String,String> request=new HashMap<>();request.put("agentId","other");request.put("peerId","peer");
        when(routing.answer("other","p1","peer")).thenThrow(new IllegalStateException("来电不属于当前坐席"));
        assertThrows(IllegalStateException.class,()->controller.answer("p1",request));verifyNoInteractions(sessions);
    }
}

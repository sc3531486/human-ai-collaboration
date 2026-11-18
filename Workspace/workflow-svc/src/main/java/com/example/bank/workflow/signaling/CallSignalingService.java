package com.example.bank.workflow.signaling;

import com.example.bank.workflow.session.BusinessSessionService;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

/** 呼叫控制独立于流程任务；挂断只更新通信状态，不自动审批。 */
@Service
public class CallSignalingService {
    private final RuntimeService runtime;
    private final BusinessSessionService sessions;
    public CallSignalingService(RuntimeService runtime, BusinessSessionService sessions) { this.runtime = runtime; this.sessions = sessions; }

    public void update(String processId, String agentId, String mode) {
        sessions.requireOwner(processId, agentId);
        if (!Arrays.asList("av", "audio", "released").contains(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的通话模式");
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("takeover_mode", mode);
        vars.put("call_status", "released".equals(mode) ? "ENDED" : "CONNECTING");
        runtime.setVariables(processId, vars);
    }
}

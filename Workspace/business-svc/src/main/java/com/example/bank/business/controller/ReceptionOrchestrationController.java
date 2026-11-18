package com.example.bank.business.controller;

import com.example.bank.business.application.ReceptionTransitionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 公共接待交接入口，仅供 workflow-svc 调用。
 *
 * <p>交接协议：</p>
 * <ul>
 *   <li>{@code POST /internal/reception/transition}：按目标节点策略申请接待交接，返回受理回执；</li>
 *   <li>{@code POST /internal/reception/transitions/{id}/bind}：目标任务进入后绑定真实 taskId/visitId；</li>
 *   <li>{@code GET  /internal/reception/transitions/{id}}：查询交接结果（重试与结果未知）；</li>
 *   <li>{@code POST /internal/reception/transitions/{id}/cancel}：撤销失效或被中止的意图。</li>
 * </ul>
 *
 * <p>受理成功不等于坐席接听，更不等于业务办理成功；相同交接编号返回同一回执。</p>
 */
@RestController
@RequestMapping("/internal/reception")
public class ReceptionOrchestrationController {

    private final ReceptionTransitionService transitions;

    public ReceptionOrchestrationController(ReceptionTransitionService transitions) {
        this.transitions = transitions;
    }

    /** 业务节点进入动作：按目标节点的办理策略申请公共接待交接。 */
    @PostMapping("/transition")
    public Map<String, Object> transition(@RequestBody Map<String, Object> body) {
        return transitions.transition(body);
    }

    /** 目标人工任务进入后绑定真实标识；预受理阶段不伪造 taskId。 */
    @PostMapping("/transitions/{transitionId}/bind")
    public Map<String, Object> bind(@PathVariable String transitionId, @RequestBody Map<String, Object> body) {
        return transitions.bind(transitionId, body);
    }

    /** 查询交接结果：解决动作重试与结果未知。 */
    @GetMapping("/transitions/{transitionId}")
    public Map<String, Object> query(@PathVariable String transitionId) {
        return transitions.query(transitionId);
    }

    /** 撤销失效或被中止的接待意图。 */
    @PostMapping("/transitions/{transitionId}/cancel")
    public Map<String, Object> cancel(@PathVariable String transitionId,
                                      @RequestParam(required = false) String reason) {
        return transitions.cancel(transitionId, reason);
    }
}

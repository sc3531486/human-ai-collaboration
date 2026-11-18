package com.example.bank.business.controller;

import com.example.bank.business.port.ProcessPort;
import com.example.bank.business.port.RoutingPort;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 环境复位控制器：先收口路由工单，再删除所有运行实例。
 * 兼容 /api/platform/reset 与 /api/sop/reset。
 */
@RestController
@RequestMapping({"/api/platform", "/api/sop"})
public class EnvironmentResetController {

    private final ProcessPort process;
    private final RoutingPort routing;

    public EnvironmentResetController(ProcessPort process, RoutingPort routing) {
        this.process = process;
        this.routing = routing;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        // CONTRACTS.md「环境复位」顺序：先收口路由工单，再删除流程实例。
        // 收口失败必须中止复位并如实上抛 —— 吞掉异常会先删实例、留下收不了口的工单。
        // 路由侧 complete 幂等（无工单直接返回），走到这里的异常都是真实故障。
        for (String id : process.runningProcessIds()) {
            routing.complete(id);
        }
        return process.reset();
    }
}

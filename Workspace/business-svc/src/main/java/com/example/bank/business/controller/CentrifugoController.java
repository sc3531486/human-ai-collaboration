package com.example.bank.business.controller;

import com.example.bank.business.port.SignalPort;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * 接入入口：为浏览器换取实时通道的连接令牌。
 *
 * <p>纯转发。<b>本服务不接触令牌密钥</b>，也不判断请求方是坐席还是客户 ——
 * 密钥与身份规则都归信令服务，在这里复制一份只会多出一个轮换时要同步修改的地方。</p>
 */
@RestController
@RequestMapping("/api/centrifugo")
public class CentrifugoController {

    private final SignalPort signals;

    public CentrifugoController(SignalPort signals) {
        this.signals = signals;
    }

    /**
     * 签连接令牌。入参二选一：坐席传 {@code agentId}（订阅 {@code agent:<id>}），
     * 客户传 {@code customerId}（订阅 {@code customer:<id>}，用于接收呼叫与视频房间信令）。
     *
     * <p>两个都传时以坐席为准，规则由对端执行。两者都缺失时对端返回 400，原样上抛。</p>
     */
    @PostMapping("/token")
    public Map<String, Object> token(@RequestBody(required = false) Map<String, String> input) {
        Map<String, String> params = input == null ? Collections.<String, String>emptyMap() : input;
        return signals.token(params.get("agentId"), params.get("customerId"));
    }
}

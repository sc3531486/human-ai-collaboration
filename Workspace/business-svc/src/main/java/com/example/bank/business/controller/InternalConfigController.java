package com.example.bank.business.controller;

import com.example.bank.business.service.SysConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 平台配置读取入口：只供内部服务调用。
 *
 * <p>产品服务按前缀拉取自己的配置（如 {@code routing.}、{@code media.}），
 * 拉取后本地缓存；浏览器不直接读这张表。</p>
 */
@RestController
@RequestMapping("/internal/config")
public class InternalConfigController {

    private final SysConfigService config;

    public InternalConfigController(SysConfigService config) {
        this.config = config;
    }

    /** 返回全部配置或指定前缀的配置。 */
    @GetMapping
    public Map<String, String> list(@RequestParam(required = false) String prefix) {
        return config.list(prefix);
    }

    /** 单键读取；不存在返回 404，调用方据此决定是否使用本地默认值。 */
    @GetMapping("/value")
    public ResponseEntity<String> value(@RequestParam String key) {
        String value = config.get(key);
        return value == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(value);
    }
}

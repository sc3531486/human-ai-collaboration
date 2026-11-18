package com.example.bank.business.controller;

import com.example.bank.business.service.TradeCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易目录入口：坐席工作台中央「可办交易」区域按坐席技能组展示可办理的交易品种。
 *
 * <p>路径与 {@code WorkbenchController} 共用 {@code /api/platform} 前缀，但路径段不冲突。</p>
 */
@RestController
@RequestMapping("/api/platform")
public class TradeController {

    private final TradeCatalogService catalog;

    public TradeController(TradeCatalogService catalog) {
        this.catalog = catalog;
    }

    /** 可办交易列表：{@code skills} 为逗号分隔的技能组，缺省时返回全部目录。 */
    @GetMapping("/trades")
    public Map<String, Object> trades(@RequestParam(value = "skills", required = false) String skills) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", true);
        result.put("data", catalog.bySkills(split(skills)));
        return result;
    }

    /** 技能组拆分：与工作台既有传参习惯一致，前端传 {@code BankApp.agentSkills.join(',')}。 */
    private List<String> split(String value) {
        List<String> rows = new ArrayList<String>();
        if (value == null || value.trim().isEmpty()) return rows;
        for (String part : value.split(",")) if (!part.trim().isEmpty()) rows.add(part.trim());
        return rows;
    }
}

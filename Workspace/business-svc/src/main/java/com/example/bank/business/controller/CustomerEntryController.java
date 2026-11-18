package com.example.bank.business.controller;

import com.example.bank.business.application.CustomerEntryService;
import com.example.bank.business.customer.CustomerProfile;
import com.example.bank.business.customer.CustomerProfileService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户进线入口：申请用户 → 等级识别 → 进线排队（队列路由由流程节点完成）。
 * 客户上送业务信息，由服务端决定等级、接待方匹配与振铃推送。
 */
@RestController
@RequestMapping("/api/customer")
public class CustomerEntryController {

    private final CustomerEntryService entries;
    private final CustomerProfileService profiles;

    public CustomerEntryController(CustomerEntryService entries, CustomerProfileService profiles) {
        this.entries = entries; this.profiles = profiles;
    }

    /** 申请用户接口：建档并完成首次客户等级识别。 */
    @PostMapping("/apply")
    public CustomerProfile apply(@RequestBody Map<String, Object> input) { return entries.apply(input); }

    /** 查询客户档案。 */
    @GetMapping("/{customerId}")
    public CustomerProfile profile(@PathVariable String customerId) { return entries.profile(customerId); }

    /** 进线排队接口：识别客户等级、启动流程并进入队列路由。`serviceMode` 决定人工直连还是数字人接待。 */
    @PostMapping("/queue")
    public Map<String, Object> queue(@RequestBody Map<String, Object> input) { return entries.queue(input); }

    /**
     * 独立等级识别接口：仅判定等级、不建档。
     * 入参 {@code {certNo, assetTier, monthlyVolume}}，资产要素缺省时由内置模拟数据补齐。
     */
    @PostMapping("/identify")
    public Map<String, Object> identify(@RequestBody Map<String, Object> input) {
        Integer assetTier = input.get("assetTier") instanceof Number
                ? ((Number) input.get("assetTier")).intValue() : null;
        Double monthlyVolume = input.get("monthlyVolume") instanceof Number
                ? ((Number) input.get("monthlyVolume")).doubleValue() : null;
        CustomerProfile profile = profiles.identify(String.valueOf(input.getOrDefault("certNo", "")),
                assetTier, monthlyVolume);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", true);
        result.put("certNoMasked", profile.certNoMasked);
        result.put("level", profile.level);
        result.put("levelName", profile.levelName);
        result.put("skillGroup", profile.skillGroup);
        result.put("tags", profile.tags);
        result.put("riskListed", profile.riskListed);
        result.put("assetTier", profile.assetTier);
        result.put("monthlyVolume", profile.monthlyVolume);
        return result;
    }

    /** 客户侧排队状态（轮询兜底）。 */
    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String customerId) { return entries.status(customerId); }
}

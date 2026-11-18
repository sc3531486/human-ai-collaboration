package com.example.bank.business.controller;

import com.example.bank.business.service.NoticeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工作台系统公告接口：为右栏「系统公告」卡片提供最新公告列表。 */
@RestController
@RequestMapping("/api/platform")
public class NoticeController {

    private final NoticeService noticeService;

    public NoticeController(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    /** 返回最新公告：{"success":true,"data":[{"id","title","content","level","publish_time"}]}。 */
    @GetMapping("/notices")
    public Map<String, Object> notices(@RequestParam(value = "limit", defaultValue = "10") int limit) {
        List<Map<String, Object>> rows = noticeService.latest(limit);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", true);
        result.put("data", rows);
        return result;
    }
}

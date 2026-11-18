package com.example.bank.business.mock;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 天策·实时反欺诈与大数据风控规则引擎 Mock 接口 (Risk Control & Anti-Fraud Engine)。
 * 模拟对大额出金、黑名单比对、频次监控、设备指纹等规则进行实时量化计算，并给出核验决策。
 */
@RestController
@RequestMapping("/api/mock")
public class MockRiskEngineController {

    private static final String RISK_ENGINE_NAME = "天策·实时反欺诈规则引擎 v4.6";

    /**
     * 实时风控反欺诈评估接口。
     * 请求路径：POST /api/mock/risk/evaluate
     */
    @PostMapping("/risk/evaluate")
    public Map<String, Object> evaluate(@RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> req = request == null ? new LinkedHashMap<String, Object>() : request;

        double amount;
        Object amountObj = req.get("amount");
        if (amountObj instanceof Number) {
            amount = ((Number) amountObj).doubleValue();
        } else if (amountObj != null) {
            try {
                amount = Double.parseDouble(String.valueOf(amountObj).replace(",", "").trim());
            } catch (Exception failure) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "金额格式不合法：" + amountObj, failure);
            }
        } else {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "缺少金额 amount");
        }

        boolean riskListed = Boolean.TRUE.equals(req.get("riskListed"))
                || Boolean.TRUE.equals(req.get("customer_risk_listed"));
        String certNo = String.valueOf(req.getOrDefault("certNo", req.getOrDefault("customer_cert_masked", "")));
        if (certNo.contains("610923199710250118")) {
            riskListed = true;
        }

        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        boolean needHuman;
        int riskScore;
        String riskLevel;
        String riskDecision;
        String riskDecisionCode;
        List<Map<String, Object>> hitRules = new ArrayList<Map<String, Object>>();
        List<String> riskFactors = new ArrayList<String>();

        if (riskListed) {
            needHuman = true;
            riskScore = 96;
            riskLevel = "高风险 (HIGH)";
            riskDecisionCode = "FORCE_HUMAN_BLOCK";
            riskDecision = "命中涉案账户黑名单/涉诈名单，触发最高级别强控，强制阻断并转人工全面排查";
            
            Map<String, Object> r1 = new LinkedHashMap<String, Object>();
            r1.put("ruleId", "R-1001");
            r1.put("ruleName", "涉案涉诈黑名单库实时比对");
            r1.put("severity", "CRITICAL");
            r1.put("detail", "客户证件号码或手机号码命中公安部涉案涉诈联合惩戒名单");
            hitRules.add(r1);

            riskFactors.add("黑名单高危主体");
            riskFactors.add("涉案协同拦截");
        } else if (amount >= 500000.0) {
            needHuman = true;
            riskScore = 89;
            riskLevel = "高风险 (HIGH)";
            riskDecisionCode = "REVIEW_HUMAN";
            riskDecision = "触发反欺诈强控规则，单笔金额超 50 万元免核阈值，强制阻断直通，路由至座席双向音视频复核授权";

            Map<String, Object> r2 = new LinkedHashMap<String, Object>();
            r2.put("ruleId", "R-1002");
            r2.put("ruleName", "超大额转账免核阈值拦截");
            r2.put("severity", "HIGH");
            r2.put("detail", "单笔转账金额大于等于 50 万元免视频核验阈值");
            hitRules.add(r2);

            Map<String, Object> r3 = new LinkedHashMap<String, Object>();
            r3.put("ruleId", "R-2005");
            r3.put("ruleName", "涉案账户比对库");
            r3.put("severity", "INFO");
            r3.put("detail", "比对通过，未命中涉案记录");
            hitRules.add(r3);

            Map<String, Object> r4 = new LinkedHashMap<String, Object>();
            r4.put("ruleId", "R-3001");
            r4.put("ruleName", "终端指纹安全信用评估");
            r4.put("severity", "INFO");
            r4.put("detail", "常用设备终端指纹匹配，基站地理围栏正常");
            hitRules.add(r4);

            riskFactors.add("大额出金强控");
            riskFactors.add("夜间频次正常");
            riskFactors.add("终端指纹安全信用良好");
        } else {
            needHuman = false;
            riskScore = 12;
            riskLevel = "低风险 (LOW)";
            riskDecisionCode = "PASS";
            riskDecision = "小额免视频核验，未命中任何高危拦截规则，直通银行核心系统自动记账";

            Map<String, Object> r0 = new LinkedHashMap<String, Object>();
            r0.put("ruleId", "R-0001");
            r0.put("ruleName", "小额免核规则");
            r0.put("severity", "INFO");
            r0.put("detail", "金额低于限额阈值，信用评分优良，允许快速直通");
            hitRules.add(r0);

            riskFactors.add("小额白名单");
            riskFactors.add("无异常行为聚类");
        }

        StringBuilder rulesSummary = new StringBuilder();
        for (int i = 0; i < hitRules.size(); i++) {
            Map<String, Object> rule = hitRules.get(i);
            if (i > 0) rulesSummary.append("；");
            rulesSummary.append("[").append(rule.get("ruleId")).append(" ").append(rule.get("ruleName")).append("]: ")
                    .append(rule.get("detail"));
        }

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("riskEngine", RISK_ENGINE_NAME);
        data.put("evalTime", now);
        data.put("amount", amount);
        data.put("riskLevel", riskLevel);
        data.put("riskScore", riskScore);
        data.put("needHuman", needHuman);
        data.put("decision", riskDecisionCode);
        data.put("riskDecision", riskDecision);
        data.put("rulesSummary", rulesSummary.toString());
        data.put("hitRules", hitRules);
        data.put("riskFactors", riskFactors);
        data.put("engineModelId", "XGB-FRAUD-2026-V8");

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("code", "000000");
        response.put("message", "风控反欺诈评估完成");
        response.put("success", true);
        response.put("data", data);
        return response;
    }
}

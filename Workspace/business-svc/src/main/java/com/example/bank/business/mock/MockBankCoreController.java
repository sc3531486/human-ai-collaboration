package com.example.bank.business.mock;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 银行核心主机业务系统 Mock 接口 (Core Banking System)。
 * 模拟提供核心记账扣款、借记卡余额查询、银行卡有效性与联行号核验能力。
 */
@RestController
@RequestMapping("/api/mock")
public class MockBankCoreController {

    private static final DecimalFormat MONEY_FORMAT = new DecimalFormat("#,##0.00");

    /**
     * 核心转账扣款记账接口。
     * 请求路径：POST /api/mock/core/transfer
     */
    @PostMapping("/core/transfer")
    public Map<String, Object> transfer(@RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> req = request == null ? new LinkedHashMap<String, Object>() : request;
        String bizNo = String.valueOf(req.getOrDefault("bizNo", "BIZ-" + System.currentTimeMillis()));
        String payeeName = String.valueOf(req.getOrDefault("payeeName", req.getOrDefault("payee", "收款人")));
        String payerName = String.valueOf(req.getOrDefault("payerName", "张三"));
        
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

        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String coreTxId = "TX" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
                + (int) (Math.random() * 9000 + 1000);
        String hostVoucherNo = "VOU" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();

        double currentBookBalance = 1250000.00;
        double balanceAfter = Math.max(0, currentBookBalance - amount);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("coreTxId", coreTxId);
        data.put("hostVoucherNo", hostVoucherNo);
        data.put("bizNo", bizNo);
        data.put("tradeCode", req.getOrDefault("tradeCode", "300101"));
        data.put("tradeName", "转账汇款 / 实时记账");
        data.put("payerName", payerName);
        data.put("payerAccount", req.getOrDefault("payerAccount", "622202******3456"));
        data.put("payeeName", payeeName);
        data.put("payeeAccount", req.getOrDefault("payeeAccount", "622848******9876"));
        data.put("amount", amount);
        data.put("fee", 0.00);
        data.put("currency", "CNY");
        data.put("balanceAfter", balanceAfter);
        data.put("status", "SUCCESS");
        data.put("statusDesc", "核心记账扣款成功 (STATUS: SUCCESS)");
        data.put("accountingTime", now);
        data.put("clearingChannel", amount >= 50000 ? "大额实时支付系统 (HVPS)" : "小额批量支付系统 (BEPS)");

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("code", "000000");
        response.put("message", "核心转账记账成功");
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    /**
     * 核心账户余额查询接口。
     * 请求路径：POST /api/mock/core/balance/query
     */
    @PostMapping("/core/balance/query")
    public Map<String, Object> queryBalance(@RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> req = request == null ? new LinkedHashMap<String, Object>() : request;
        String accountNo = String.valueOf(req.getOrDefault("accountNo", "6222021000123456"));
        String customerName = String.valueOf(req.getOrDefault("customerName", req.getOrDefault("customer", "张三")));

        double availableBalance = 1250000.00;
        double bookBalance = 1250000.00;
        double frozenAmount = 0.00;

        String formattedBalance = MONEY_FORMAT.format(availableBalance) + " 元";

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("accountNo", accountNo);
        data.put("accountNoMasked", maskAccount(accountNo));
        data.put("accountName", customerName);
        data.put("accountType", "借记卡I类户");
        data.put("currency", "CNY");
        data.put("availableBalance", availableBalance);
        data.put("bookBalance", bookBalance);
        data.put("frozenAmount", frozenAmount);
        data.put("formattedBalance", formattedBalance);
        data.put("accountStatus", "NORMAL");
        data.put("openBranch", "总行营业部科技金融支行");
        data.put("queryTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("code", "000000");
        response.put("message", "查询账户余额成功");
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    /**
     * 银行卡与账户核验接口（校验卡Bin、联行号及有效性）。
     * 请求路径：POST /api/mock/account/verify
     */
    @PostMapping("/account/verify")
    public Map<String, Object> verifyAccount(@RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> req = request == null ? new LinkedHashMap<String, Object>() : request;
        String accountNo = String.valueOf(req.getOrDefault("accountNo", "6228480000987654")).trim();
        String accountName = String.valueOf(req.getOrDefault("accountName", ""));

        boolean valid = accountNo.length() >= 16;
        String bankName = "中国农业银行";
        String bankCode = "ABC";
        String cardType = "DEBIT";
        String cardTypeName = "金穗借记卡";

        if (accountNo.startsWith("6222")) {
            bankName = "中国工商银行";
            bankCode = "ICBC";
            cardTypeName = "牡丹灵通借记卡";
        } else if (accountNo.startsWith("6227")) {
            bankName = "中国建设银行";
            bankCode = "CCB";
            cardTypeName = "龙卡借记卡";
        } else if (accountNo.startsWith("6214")) {
            bankName = "招商银行";
            bankCode = "CMB";
            cardTypeName = "一卡通金卡";
        }

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("valid", valid);
        data.put("accountNoMasked", maskAccount(accountNo));
        data.put("accountName", accountName);
        data.put("bankCode", bankCode);
        data.put("bankName", bankName);
        data.put("cardType", cardType);
        data.put("cardTypeName", cardTypeName);
        data.put("status", "NORMAL");
        data.put("interbankClearingCode", "103100000026");

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("code", valid ? "000000" : "100001");
        response.put("message", valid ? "银行卡要素核验通过" : "卡号不合法");
        response.put("success", valid);
        response.put("data", data);
        return response;
    }

    private String maskAccount(String account) {
        if (account == null || account.length() < 10) return account;
        return account.substring(0, 6) + "******" + account.substring(account.length() - 4);
    }
}

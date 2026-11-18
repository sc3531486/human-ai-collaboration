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
 * 金融短信与消息通知网关 Mock 接口 (Financial SMS Gateway)。
 * 模拟提供高并发金融专用短消息通道（10698000）投递、运营商回执状态（DELIVRD）与合规模板渲染。
 */
@RestController
@RequestMapping("/api/mock")
public class MockNotificationController {

    private static final String SMS_CHANNEL_NAME = "全国金融短信网关专用通道 (10698000)";
    private static final DecimalFormat AMOUNT_FORMAT = new DecimalFormat("#,##0.00");

    /**
     * 发送金融短信通知。
     * 请求路径：POST /api/mock/notify/sms/send
     */
    @PostMapping("/notify/sms/send")
    public Map<String, Object> sendSms(@RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> req = request == null ? new LinkedHashMap<String, Object>() : request;

        String mobile = String.valueOf(req.getOrDefault("mobile", req.getOrDefault("customer_mobile_masked", "138****8888")));
        String payee = String.valueOf(req.getOrDefault("payee", req.getOrDefault("payeeName", "收款人")));
        
        String amountStr;
        Object amountObj = req.get("amount");
        if (amountObj instanceof Number) {
            amountStr = AMOUNT_FORMAT.format(((Number) amountObj).doubleValue());
        } else if (amountObj != null && !String.valueOf(amountObj).isEmpty()) {
            try {
                double val = Double.parseDouble(String.valueOf(amountObj).replace(",", "").trim());
                amountStr = AMOUNT_FORMAT.format(val);
            } catch (Exception failure) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "金额格式不合法：" + amountObj, failure);
            }
        } else {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "缺少金额 amount");
        }

        String cardTail = String.valueOf(req.getOrDefault("cardTail", "8888"));
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String msgId = "SMS-" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
                + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        String smsContent = "【XX银行】您尾号" + cardTail + "账户向 " + payee + " 转账 ¥" + amountStr + " 元已成功受理。";

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("msgId", msgId);
        data.put("mobile", mobile);
        data.put("channel", SMS_CHANNEL_NAME);
        data.put("content", smsContent);
        data.put("deliverStatus", "DELIVRD");
        data.put("statusDesc", "运营商网关回执：已送达 (STATUS: DELIVRD)");
        data.put("carrierCode", "CMCC/CU/CT");
        data.put("sendTime", now);
        data.put("bizNo", req.getOrDefault("bizNo", ""));

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("code", "000000");
        response.put("message", "短信下发成功");
        response.put("success", true);
        response.put("data", data);
        return response;
    }
}

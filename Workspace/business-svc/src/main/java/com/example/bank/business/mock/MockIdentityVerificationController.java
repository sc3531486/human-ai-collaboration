package com.example.bank.business.mock;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 身份认证、活体人脸识别与 CA 电子签章 Mock 接口 (Identity & E-Signature Services)。
 * 模拟提供公安部底库权威核验、双录现场活体比对检测、CFCA 电子凭单签署与时间戳存证。
 */
@RestController
@RequestMapping("/api/mock")
public class MockIdentityVerificationController {

    /**
     * 公安人像核验与活体检测比对接口。
     * 请求路径：POST /api/mock/id/face-verify
     */
    @PostMapping("/id/face-verify")
    public Map<String, Object> verifyFace(@RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> req = request == null ? new LinkedHashMap<String, Object>() : request;

        String certNo = String.valueOf(req.getOrDefault("certNo", "610923199710250118"));
        String customerName = String.valueOf(req.getOrDefault("customerName", "张三"));
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        boolean isRisk = certNo.contains("610923199710250118");
        double similarity = isRisk ? 72.4 : 98.7;
        double livenessScore = isRisk ? 0.81 : 0.992;
        boolean pass = !isRisk;

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("auditId", "AUTH-FACE-" + System.currentTimeMillis());
        data.put("customerName", customerName);
        data.put("certNoMasked", maskCert(certNo));
        data.put("pass", pass);
        data.put("verifyStatus", pass ? "PASSED" : "FAILED");
        data.put("similarity", similarity);
        data.put("similarityThreshold", 85.0);
        data.put("livenessScore", livenessScore);
        data.put("certStatus", "VALID");
        data.put("policeCheckResult", pass ? "一致（公安部人像库三要素比对通过）" : "核验存疑（人脸特征与底库存在差异）");
        data.put("verifyTimestamp", now);

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("code", pass ? "000000" : "200003");
        response.put("message", pass ? "人脸活体比对通过" : "人脸比对未达到置信度阈值");
        response.put("success", pass);
        response.put("data", data);
        return response;
    }

    /**
     * 电子凭单与 CA 意愿签署接口。
     * 请求路径：POST /api/mock/sign/contract
     */
    @PostMapping("/sign/contract")
    public Map<String, Object> signContract(@RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> req = request == null ? new LinkedHashMap<String, Object>() : request;

        String bizNo = String.valueOf(req.getOrDefault("bizNo", "BIZ-" + System.currentTimeMillis()));
        String customerName = String.valueOf(req.getOrDefault("customerName", "张三"));
        String agentId = String.valueOf(req.getOrDefault("agentId", "AGENT-9527"));
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        String signDocId = "DOC-SIGN-" + new SimpleDateFormat("yyyyMMdd").format(new Date()) + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String certSerialNo = "CFCA-SM2-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        String tsToken = "TSA-NTSC-" + System.currentTimeMillis() + "-R8";

        String rawContent = bizNo + "|" + customerName + "|" + agentId + "|" + now;
        String sha256 = sha256Hex(rawContent);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("signDocId", signDocId);
        data.put("bizNo", bizNo);
        data.put("customerName", customerName);
        data.put("agentId", agentId);
        data.put("caCertSerialNo", certSerialNo);
        data.put("caProvider", "中国金融认证中心 (CFCA)");
        data.put("algorithm", "SM2withSM3 / 国密商业密码算法");
        data.put("timestampToken", tsToken);
        data.put("timeAuthority", "国家授时中心 (NTSC) 可信时间源");
        data.put("sha256Hash", sha256);
        data.put("signStatus", "SUCCESS");
        data.put("signTime", now);

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("code", "000000");
        response.put("message", "电子凭单数字签章生成成功");
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    private String maskCert(String cert) {
        if (cert == null || cert.length() < 10) return cert;
        return cert.substring(0, 4) + "**********" + cert.substring(cert.length() - 4);
    }

    private String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception failure) {
            // 防篡改摘要算不出来就必须失败：伪造一个随机哈希等于伪造存证
            throw new IllegalStateException("凭单摘要计算失败", failure);
        }
    }
}

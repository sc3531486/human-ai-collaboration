package com.example.bank.business.mock;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class MockControllersTest {

    @Test
    public void testMockBankCoreControllerTransfer() {
        MockBankCoreController controller = new MockBankCoreController();
        Map<String, Object> req = new HashMap<String, Object>();
        req.put("bizNo", "BIZ-TEST-001");
        req.put("amount", 80000.0);
        req.put("payeeName", "李四");

        Map<String, Object> resp = controller.transfer(req);
        Assertions.assertEquals("000000", resp.get("code"));
        Assertions.assertEquals(true, resp.get("success"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        Assertions.assertNotNull(data.get("coreTxId"));
        Assertions.assertNotNull(data.get("hostVoucherNo"));
        Assertions.assertEquals("SUCCESS", data.get("status"));
        Assertions.assertEquals(80000.0, ((Number) data.get("amount")).doubleValue());
        Assertions.assertEquals("大额实时支付系统 (HVPS)", data.get("clearingChannel"));
    }

    @Test
    public void testMockBankCoreControllerBalanceQuery() {
        MockBankCoreController controller = new MockBankCoreController();
        Map<String, Object> req = new HashMap<String, Object>();
        req.put("accountNo", "6222021000123456");
        req.put("customerName", "张三");

        Map<String, Object> resp = controller.queryBalance(req);
        Assertions.assertEquals("000000", resp.get("code"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        Assertions.assertEquals("622202******3456", data.get("accountNoMasked"));
        Assertions.assertEquals(1250000.00, ((Number) data.get("availableBalance")).doubleValue());
        Assertions.assertEquals("1,250,000.00 元", data.get("formattedBalance"));
    }

    @Test
    public void testMockBankCoreControllerVerifyAccount() {
        MockBankCoreController controller = new MockBankCoreController();
        Map<String, Object> req = new HashMap<String, Object>();
        req.put("accountNo", "6222021000123456");

        Map<String, Object> resp = controller.verifyAccount(req);
        Assertions.assertEquals("000000", resp.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        Assertions.assertEquals("ICBC", data.get("bankCode"));
        Assertions.assertEquals("中国工商银行", data.get("bankName"));
    }

    @Test
    public void testMockRiskEngineController() {
        MockRiskEngineController controller = new MockRiskEngineController();

        // 1. 小额转账
        Map<String, Object> reqSmall = new HashMap<String, Object>();
        reqSmall.put("amount", 20000.0);
        Map<String, Object> respSmall = controller.evaluate(reqSmall);
        @SuppressWarnings("unchecked")
        Map<String, Object> dataSmall = (Map<String, Object>) respSmall.get("data");
        Assertions.assertEquals(false, dataSmall.get("needHuman"));
        Assertions.assertEquals("低风险 (LOW)", dataSmall.get("riskLevel"));

        // 2. 超大额转账 (>= 50万)
        Map<String, Object> reqLarge = new HashMap<String, Object>();
        reqLarge.put("amount", 600000.0);
        Map<String, Object> respLarge = controller.evaluate(reqLarge);
        @SuppressWarnings("unchecked")
        Map<String, Object> dataLarge = (Map<String, Object>) respLarge.get("data");
        Assertions.assertEquals(true, dataLarge.get("needHuman"));
        Assertions.assertEquals("高风险 (HIGH)", dataLarge.get("riskLevel"));
        Assertions.assertEquals(89, dataLarge.get("riskScore"));

        // 3. 黑名单
        Map<String, Object> reqBlacklist = new HashMap<String, Object>();
        reqBlacklist.put("amount", 1000.0);
        reqBlacklist.put("riskListed", true);
        Map<String, Object> respBlacklist = controller.evaluate(reqBlacklist);
        @SuppressWarnings("unchecked")
        Map<String, Object> dataBlacklist = (Map<String, Object>) respBlacklist.get("data");
        Assertions.assertEquals(true, dataBlacklist.get("needHuman"));
        Assertions.assertEquals(96, dataBlacklist.get("riskScore"));
    }

    @Test
    public void testMockNotificationController() {
        MockNotificationController controller = new MockNotificationController();
        Map<String, Object> req = new HashMap<String, Object>();
        req.put("mobile", "13800138000");
        req.put("payee", "王五");
        req.put("amount", 5000.0);

        Map<String, Object> resp = controller.sendSms(req);
        Assertions.assertEquals("000000", resp.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        Assertions.assertEquals("DELIVRD", data.get("deliverStatus"));
        Assertions.assertTrue(String.valueOf(data.get("content")).contains("王五"));
        Assertions.assertTrue(String.valueOf(data.get("content")).contains("5,000.00"));
    }

    @Test
    public void testMockIdentityVerificationController() {
        MockIdentityVerificationController controller = new MockIdentityVerificationController();

        // 活体核验
        Map<String, Object> faceReq = new HashMap<String, Object>();
        faceReq.put("certNo", "110101199003072391");
        faceReq.put("customerName", "赵六");
        Map<String, Object> faceResp = controller.verifyFace(faceReq);
        Assertions.assertEquals("000000", faceResp.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> faceData = (Map<String, Object>) faceResp.get("data");
        Assertions.assertEquals(true, faceData.get("pass"));
        Assertions.assertEquals("PASSED", faceData.get("verifyStatus"));

        // 电子签章
        Map<String, Object> signReq = new HashMap<String, Object>();
        signReq.put("bizNo", "BIZ-SIGN-001");
        signReq.put("customerName", "赵六");
        Map<String, Object> signResp = controller.signContract(signReq);
        Assertions.assertEquals("000000", signResp.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> signData = (Map<String, Object>) signResp.get("data");
        Assertions.assertNotNull(signData.get("signDocId"));
        Assertions.assertNotNull(signData.get("caCertSerialNo"));
        Assertions.assertNotNull(signData.get("sha256Hash"));
        Assertions.assertEquals("SUCCESS", signData.get("signStatus"));
    }
}

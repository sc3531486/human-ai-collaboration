package com.example.bank.business.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DigitalHumanPasswordEntryTest {
    @Test void resetQuickQuestionStartsTheFullPasswordSceneWithoutModelInference(){Map<String,Object> input=new HashMap<>();input.put("text","重置银行卡密码");input.put("bizNo","BIZ-P");Map<String,Object> result=new DigitalHumanService().processChat(input);assertEquals("password_reset_sop",result.get("processKey"));assertEquals("TRIGGER",result.get("action"));assertEquals("DEMO-001",((Map<?,?>)result.get("startVars")).get("customer_id"));}
}

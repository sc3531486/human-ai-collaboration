package com.example.bank.business.service;

import com.example.bank.business.evidence.*;
import com.example.bank.business.port.ProcessPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import javax.annotation.PostConstruct;
import java.util.*;

/** 密码业务能力及Mock核验；分支与交接由流程服务决定。 */
@Service
public class PasswordResetService {
    private static final String AGENT_FACE_POLICY = "PASSWORD_RESET_FACE";
    private static final String CUSTOMER_FACE_POLICY = "PASSWORD_RESET_CUSTOMER_FACE";
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    private final InteractionTraceService traces;
    @org.springframework.beans.factory.annotation.Autowired private SmsVerificationService sms;
    @org.springframework.beans.factory.annotation.Autowired private PasswordDocumentService documents;
    @org.springframework.beans.factory.annotation.Autowired private com.example.bank.business.application.AuthorizationService authorizations;
    public PasswordResetService(JdbcTemplate jdbc,ObjectMapper json,InteractionTraceService traces){this.jdbc=jdbc;this.json=json;this.traces=traces;}
    @PostConstruct public void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS biz_verification_policy(policy_code VARCHAR(80) PRIMARY KEY, threshold DECIMAL(5,2) NOT NULL, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
        jdbc.update("INSERT IGNORE INTO biz_verification_policy(policy_code,threshold) VALUES(?,90),(?,91)",
                AGENT_FACE_POLICY, CUSTOMER_FACE_POLICY);
        jdbc.execute("CREATE TABLE IF NOT EXISTS biz_mock_setting(setting_key VARCHAR(80) PRIMARY KEY,setting_value VARCHAR(200) NOT NULL)");
        jdbc.update("INSERT IGNORE INTO biz_mock_setting(setting_key,setting_value) VALUES('PASSWORD_RESET_FACE_SCORE','90')");
    }
    public Map<String,Object> cards(Map<String,Object> input){
        String customer=required(input,"customerId");
        List<Map<String,Object>> cards=new ArrayList<>();cards.add(card("CARD-001","6222021000123456","储蓄卡 · 3456"));cards.add(card("CARD-002","6222021000654321","储蓄卡 · 4321"));
        Map<String,Object> result=new LinkedHashMap<>();result.put("cards",cards);result.put("mock",true);return result;
    }
    private Map<String,Object> card(String id,String number,String label){Map<String,Object> card=new LinkedHashMap<>();card.put("id",id);card.put("cardNo",number);card.put("label",label);card.put("cardNoMasked",number.substring(0,6)+"******"+number.substring(number.length()-4));return card;}
    private Map<String,Object> chosen(Map<String,Object> input){
        String selected=required(input,"cardId");
        return ((List<Map<String,Object>>)cards(input).get("cards")).stream().filter(card->selected.equals(card.get("id"))).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择本人名下的银行卡"));
    }
    private void evidence(Map<String,Object> input,String field,String kind,String role){
        String url=required(input,field),pid=required(input,"processId"),bizNo=required(input,"bizNo");
        boolean found=false;
        for(Map<String,Object> row:jdbc.queryForList("SELECT event_payload FROM biz_interaction_trace WHERE biz_no=? AND process_id=? AND actor_role=? AND event_type='CAPTURE_SAVED'",bizNo,pid,role)) {
            try{Map<String,Object> payload=json.readValue(String.valueOf(row.get("event_payload")),Map.class);if(url.equals(payload.get("fileUrl"))&&kind.equals(payload.get("kind")))found=true;}catch(Exception e){throw new IllegalStateException("照片留痕无法读取",e);}
        }
        if(!found)bad("请先拍摄并上传"+kind+"照片");
    }
    public Map<String,Object> mediaOcr(Map<String,Object> input){
        Map<String,Object> card=chosen(input);evidence(input,"mediaPhoto","MEDIA","CUSTOMER");
        String recognized=String.valueOf(input.getOrDefault("mockOcrCardNo","")).replace(" ","");if(recognized.isEmpty())recognized=String.valueOf(card.get("cardNo"));
        Map<String,Object> result=new LinkedHashMap<>();result.put("mediaMatched",Objects.equals(card.get("cardNo"),recognized));result.put("selectedCardNo",card.get("cardNo"));result.put("ocrCardNo",recognized);result.put("mock",true);
        result.put("mediaMessage",Boolean.TRUE.equals(result.get("mediaMatched"))?"介质卡号与所选银行卡一致":"介质卡号与所选银行卡不一致，请重新拍摄或选择正确的介质");
        result.put("ocrPhotoUrl",input.get("mediaPhoto"));result.put("selectedCardMasked",card.get("cardNoMasked"));
        trace(input,"MEDIA_OCR_CHECK",result);return result;
    }
    public Map<String,Object> identity(Map<String,Object> input){
        chosen(input);
        evidence(input, "idFrontPhoto", "ID_FRONT", "CUSTOMER");
        evidence(input, "idBackPhoto", "ID_BACK", "CUSTOMER");
        boolean customerCapture = input.containsKey("customerFacePhoto");
        if (customerCapture) {
            evidence(input, "mediaPhoto", "MEDIA", "CUSTOMER");
            evidence(input, "customerFacePhoto", "FACE", "CUSTOMER");
        } else {
            evidence(input, "facePhoto", "FACE", "AGENT");
        }
        String policyCode = customerCapture ? CUSTOMER_FACE_POLICY : AGENT_FACE_POLICY;
        Map<String,Object> policy=jdbc.queryForMap("SELECT threshold,updated_at FROM biz_verification_policy WHERE policy_code=?", policyCode);
        java.math.BigDecimal threshold=new java.math.BigDecimal(String.valueOf(policy.get("threshold")));
        if(threshold.signum()<0||threshold.compareTo(new java.math.BigDecimal("100"))>0)throw new IllegalStateException("人脸阈值必须在0至100之间");
        java.math.BigDecimal score=new java.math.BigDecimal(jdbc.queryForObject("SELECT setting_value FROM biz_mock_setting WHERE setting_key='PASSWORD_RESET_FACE_SCORE'",String.class));
        if(score.signum()<0||score.compareTo(new java.math.BigDecimal("100"))>0)throw new IllegalStateException("人脸Mock分数必须在0至100之间");
        Map<String,Object> result=new LinkedHashMap<>();result.put("faceScore",score);result.put("faceThreshold",threshold);result.put("onlinePassed",true);result.put("policyUpdatedAt",String.valueOf(policy.get("updated_at")));
        result.put("identityPassed", score.compareTo(threshold) >= 0);
        Map<String,Object> front=new LinkedHashMap<>();front.put("name","演示客户");front.put("gender","男");front.put("nationality","汉");front.put("certNoMasked","110101********1234");
        Map<String,Object> back=new LinkedHashMap<>();back.put("issuer","演示签发机构");back.put("validFrom","2020-01-01");back.put("validTo","2030-01-01");
        result.put("idFrontOcr",front);result.put("idBackOcr",back);result.put("onlineResult",Collections.singletonMap("message","模拟联网核查通过"));
        result.put("identityAuditId","ID-"+UUID.randomUUID());result.put("mock",true);result.put("identityMessage","模拟联网核查通过，人脸比对"+score+"分");trace(input,"PASSWORD_IDENTITY_CHECK",result);return result;
    }
    public Map<String,Object> reset(Map<String,Object> input){
        Map<String,Object> card=chosen(input);
        String audit=required(input,"identityAuditId");Map<String,Object> verification=null;
        for(Map<String,Object> row:jdbc.queryForList("SELECT event_payload FROM biz_interaction_trace WHERE biz_no=? AND process_id=? AND event_type='PASSWORD_IDENTITY_CHECK'",required(input,"bizNo"),required(input,"processId"))) {
            try{Map<String,Object> data=json.readValue(String.valueOf(row.get("event_payload")),Map.class);if(audit.equals(data.get("identityAuditId")))verification=data;}catch(Exception e){throw new IllegalStateException("核验记录无法读取",e);}
        }
        if(verification==null)bad("缺少本次业务的核验记录");
        boolean auto=Boolean.TRUE.equals(verification.get("onlinePassed"))&&new java.math.BigDecimal(String.valueOf(verification.get("faceScore"))).compareTo(new java.math.BigDecimal(String.valueOf(verification.get("faceThreshold"))))>=0;
        if(!auto)authorizations.requireApproved(required(input,"authorizationId"),required(input,"processId"));
        sms.requireVerified(input);
        documents.requireSigned(input);
        Map<String,Object> result=new LinkedHashMap<>();result.put("resetReceipt","PWD-"+UUID.randomUUID());result.put("resetStatus","SUCCESS");result.put("resetMessage","该银行卡密码重置模拟办理成功");result.put("resetCardMasked",card.get("cardNoMasked"));result.put("mock",true);trace(input,"PASSWORD_RESET_DONE",result);return result;
    }
    private void trace(Map<String,Object> input,String type,Map<String,Object> output){
        String op=required(input,"operationId");String eventId="cap-"+UUID.nameUUIDFromBytes(op.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        traces.append(required(input,"bizNo"),eventId,required(input,"processId"),String.valueOf(input.getOrDefault("nodeName","")),"SYSTEM",type,output,"");
    }
    public Map<String,Object> review(Map<String,Object> input){
        String audit=required(input,"identityAuditId");Map<String,Object> verification=null;
        for(Map<String,Object> row:jdbc.queryForList("SELECT event_payload FROM biz_interaction_trace WHERE biz_no=? AND process_id=? AND event_type='PASSWORD_IDENTITY_CHECK'",required(input,"bizNo"),required(input,"processId"))){try{Map<String,Object> data=json.readValue(String.valueOf(row.get("event_payload")),Map.class);if(audit.equals(data.get("identityAuditId")))verification=data;}catch(Exception e){throw new IllegalStateException(e);}}
        if(verification==null||!Boolean.TRUE.equals(verification.get("onlinePassed")))bad("联网核查未通过，不能继续办理");
        boolean passed=new java.math.BigDecimal(String.valueOf(verification.get("faceScore"))).compareTo(new java.math.BigDecimal(String.valueOf(verification.get("faceThreshold"))))>=0;
        String action=required(input,"action");
        if((passed&&!"PROCEED".equals(action))||(!passed&&!"REQUEST_AUTH".equals(action)))bad("当前核验结果不允许此办理动作");
        return Collections.<String,Object>singletonMap("needsAuthorization",!passed);
    }
    private String required(Map<String,Object> input,String key){String value=String.valueOf(input.getOrDefault(key,""));if(value.trim().isEmpty()||"null".equals(value))bad("缺少参数："+key);return value;}
    private void bad(String message){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}

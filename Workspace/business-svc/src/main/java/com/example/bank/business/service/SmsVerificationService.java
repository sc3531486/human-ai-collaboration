package com.example.bank.business.service;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.annotation.PostConstruct;
import java.util.*;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 短信挑战与校验凭证；演示验证码在业务数据库配置，流程与前端不内置验证码。 */
@Service
public class SmsVerificationService {
    private final JdbcTemplate jdbc;
    public SmsVerificationService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @PostConstruct public void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS biz_mock_setting(setting_key VARCHAR(80) PRIMARY KEY,setting_value VARCHAR(200) NOT NULL)");
        jdbc.update("INSERT IGNORE INTO biz_mock_setting(setting_key,setting_value) VALUES('SMS_CODE','123456')");
        jdbc.execute("CREATE TABLE IF NOT EXISTS biz_sms_challenge(challenge_id VARCHAR(64) PRIMARY KEY,process_id VARCHAR(64) NOT NULL,biz_no VARCHAR(64) NOT NULL,customer_id VARCHAR(100) NOT NULL,card_id VARCHAR(64) NOT NULL,code_hash VARCHAR(64) NOT NULL,expires_at TIMESTAMP NOT NULL,attempts INT NOT NULL DEFAULT 0,verified BOOLEAN NOT NULL DEFAULT FALSE,verification_id VARCHAR(64),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
    }
    public synchronized Map<String,Object> send(Map<String,Object> input){
        String process=required(input,"processId"),biz=required(input,"bizNo"),customer=required(input,"customerId"),card=required(input,"cardId");
        String code=jdbc.queryForObject("SELECT setting_value FROM biz_mock_setting WHERE setting_key='SMS_CODE'",String.class);
        if(code==null||!code.matches("[0-9]{6}"))throw new IllegalStateException("短信Mock验证码须配置为6位数字");
        List<Map<String,Object>> prior=jdbc.queryForList("SELECT * FROM biz_sms_challenge WHERE process_id=? AND customer_id=? AND card_id=? AND verified=FALSE AND attempts<5 AND expires_at>CURRENT_TIMESTAMP ORDER BY created_at DESC",process,customer,card);
        String id=prior.isEmpty()?"SMS-"+UUID.randomUUID():String.valueOf(prior.get(0).get("challenge_id"));
        if(prior.isEmpty())jdbc.update("INSERT INTO biz_sms_challenge(challenge_id,process_id,biz_no,customer_id,card_id,code_hash,expires_at) VALUES(?,?,?,?,?,?,?)",id,process,biz,customer,card,hash(id+":"+code),new Timestamp(System.currentTimeMillis()+300000));
        Map<String,Object> result=new LinkedHashMap<>();result.put("smsChallengeId",id);result.put("smsDeliveryMessage","演示短信已发送，验证码："+code);result.put("mock",true);return result;
    }
    public synchronized Map<String,Object> verify(Map<String,Object> input){
        String id=required(input,"challengeId"),code=required(input,"code");
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM biz_sms_challenge WHERE challenge_id=? AND process_id=? AND customer_id=? AND card_id=?",id,required(input,"processId"),required(input,"customerId"),required(input,"cardId"));
        if(rows.size()!=1)bad("短信挑战与当前业务不匹配");Map<String,Object> row=rows.get(0);
        boolean passed=((Timestamp)row.get("expires_at")).getTime()>System.currentTimeMillis()&&((Number)row.get("attempts")).intValue()<5&&Objects.equals(row.get("code_hash"),hash(id+":"+code));
        String receipt=Boolean.TRUE.equals(row.get("verified"))?String.valueOf(row.get("verification_id")):"SMSV-"+UUID.randomUUID();
        if(passed)jdbc.update("UPDATE biz_sms_challenge SET verified=TRUE,verification_id=? WHERE challenge_id=?",receipt,id);
        else jdbc.update("UPDATE biz_sms_challenge SET attempts=attempts+1 WHERE challenge_id=?",id);
        Map<String,Object> result=new LinkedHashMap<>();result.put("smsPassed",passed);result.put("smsVerifyMessage",passed?"短信验证通过":"验证码错误或已过期，请重新填写");if(passed)result.put("smsVerificationId",receipt);return result;
    }
    public void requireVerified(Map<String,Object> input){
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM biz_sms_challenge WHERE verification_id=? AND process_id=? AND customer_id=? AND card_id=? AND verified=TRUE",Integer.class,required(input,"smsVerificationId"),required(input,"processId"),required(input,"customerId"),required(input,"cardId"));
        if(count==null||count!=1)bad("缺少与当前业务匹配的短信验证凭证");
    }
    private String hash(String value){try{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))out.append(String.format("%02x",b));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private String required(Map<String,Object> input,String key){String value=String.valueOf(input.getOrDefault(key,""));if(value.trim().isEmpty()||"null".equals(value))bad("缺少短信参数："+key);return value;}
    private void bad(String message){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}

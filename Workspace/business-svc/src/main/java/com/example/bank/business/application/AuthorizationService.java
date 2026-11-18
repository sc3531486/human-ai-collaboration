package com.example.bank.business.application;
import com.example.bank.business.port.ProcessPort;
import com.example.bank.business.evidence.InteractionTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.annotation.PostConstruct;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 公共授权单：保存业务资料快照，授权与父交易分开归属，禁止申请人自授权。 */
@Service
public class AuthorizationService {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final ProcessPort processes;private final CallSessionService calls;
    @org.springframework.beans.factory.annotation.Autowired private com.example.bank.business.mock.MockResourceCatalog resources;
    public AuthorizationService(JdbcTemplate jdbc,ObjectMapper json,ProcessPort processes,CallSessionService calls){this.jdbc=jdbc;this.json=json;this.processes=processes;this.calls=calls;}
    @PostConstruct public void initialize(){jdbc.execute("CREATE TABLE IF NOT EXISTS biz_authorization(authorization_id VARCHAR(64) PRIMARY KEY,parent_process_id VARCHAR(64) NOT NULL,biz_no VARCHAR(64) NOT NULL,requester_id VARCHAR(100) NOT NULL,reason_text VARCHAR(1000),snapshot_json MEDIUMTEXT NOT NULL,snapshot_hash VARCHAR(64) NOT NULL,status VARCHAR(16) NOT NULL,reviewer_id VARCHAR(100),review_opinion VARCHAR(1000),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,decided_at TIMESTAMP NULL)");}
    public Map<String,Object> create(Map<String,Object> input){
        String parent=required(input,"processId");Map<String,Object> vars=processes.variables(parent);String requester=required(vars,"agent_session_id");
        if(calls.byProcess(parent)==null||!"OPEN".equals(calls.byProcess(parent).get("status")))bad("申请授权需要原视频通话保持接通");
        Map<String,Object> snapshot=snapshot(vars);String hash=digest(encode(snapshot));String id="AUTH-"+UUID.nameUUIDFromBytes(required(input,"operationId").getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT IGNORE INTO biz_authorization(authorization_id,parent_process_id,biz_no,requester_id,reason_text,snapshot_json,snapshot_hash,status) VALUES(?,?,?,?,?,?,?,'PENDING')",id,parent,required(input,"bizNo"),requester,required(input,"reason"),encode(snapshot),hash);
        Map<String,Object> result=new LinkedHashMap<>();result.put("authorizationId",id);return result;
    }
    public Map<String,Object> load(Map<String,Object> input){Map<String,Object> row=requiredOrder(required(input,"authorizationId"));Map<String,Object> result=new LinkedHashMap<>();result.put("authorizationSnapshot",decode(row.get("snapshot_json")));result.put("authorizationReason",row.get("reason_text"));result.put("authorizationRequester",row.get("requester_id"));result.put("parentProcessId",row.get("parent_process_id"));return result;}
    @org.springframework.transaction.annotation.Transactional public Map<String,Object> decide(Map<String,Object> input){
        jdbc.queryForList("SELECT authorization_id FROM biz_authorization WHERE authorization_id=? FOR UPDATE",required(input,"authorizationId"));
        Map<String,Object> row=requiredOrder(required(input,"authorizationId"));String reviewer=required(input,"agentId");String decision=required(input,"decision");
        if(reviewer.equals(row.get("requester_id")))bad("申请坐席不能授权自己的业务");
        try{boolean allowed=false;for(Map<String,Object> resource:(java.util.List<Map<String,Object>>)resources.read().get("agents"))if(reviewer.equals(resource.get("agentId"))&&((java.util.List<?>)resource.get("skills")).contains("AUTHORIZATION"))allowed=true;if(!allowed)bad("该坐席未配置授权权限");}catch(java.io.IOException e){throw new IllegalStateException(e);}
        Map<String,Object> child=processes.variables(required(input,"processId"));
        if(!reviewer.equals(child.get("agent_session_id"))||!input.get("authorizationId").equals(child.get("authorization_id")))bad("授权坐席尚未接听此授权单");
        if(!Arrays.asList("AUTHORIZE","REJECT").contains(decision))bad("授权决定不合法");
        if(!String.valueOf(row.get("snapshot_hash")).equals(digest(encode(snapshot(processes.variables(String.valueOf(row.get("parent_process_id"))))))))bad("业务资料已变化，请重新申请授权");
        String status="AUTHORIZE".equals(decision)?"APPROVED":"REJECTED";if("REJECT".equals(decision))required(input,"opinion");
        if(!"PENDING".equals(row.get("status"))&&!status.equals(row.get("status")))bad("授权单已经有其他决定");
        jdbc.update("UPDATE biz_authorization SET status=?,reviewer_id=?,review_opinion=?,decided_at=CURRENT_TIMESTAMP WHERE authorization_id=? AND status='PENDING'",status,reviewer,String.valueOf(input.getOrDefault("opinion","")),input.get("authorizationId"));
        Map<String,Object> result=new LinkedHashMap<>();result.put("authorizationApproved","APPROVED".equals(status));result.put("authorizationDecisionId",input.get("authorizationId"));result.put("authorizationMessage","APPROVED".equals(status)?"授权审核通过":"授权未通过："+input.get("opinion"));return result;
    }
    public void requireApproved(String id,String parent){Map<String,Object> row=requiredOrder(id);if(!"APPROVED".equals(row.get("status"))||!parent.equals(row.get("parent_process_id")))bad("业务未获得有效的独立授权");if(!String.valueOf(row.get("snapshot_hash")).equals(digest(encode(snapshot(processes.variables(parent))))))bad("授权资料已经变化");}
    public Map<String,Object> requiredOrder(String id){java.util.List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM biz_authorization WHERE authorization_id=?",id);if(rows.size()!=1)bad("授权单不存在");return rows.get(0);}
    private Map<String,Object> snapshot(Map<String,Object> vars){Map<String,Object> projected=new LinkedHashMap<>(vars);if(vars.get("payload")!=null)projected.putAll(decode(vars.get("payload")));if(vars.get("results")!=null)projected.putAll(decode(vars.get("results")));Map<String,Object> snap=new TreeMap<>();for(String key:Arrays.asList("biz_no","customer_id","customer_name","selected_card_id","id_front_photo","id_back_photo","face_photo","identityAuditId","faceScore","faceThreshold","onlinePassed","idFrontOcr","idBackOcr","onlineResult"))snap.put(key,projected.get(key));return snap;}
    private String required(Map<String,Object> input,String key){String value=String.valueOf(input.getOrDefault(key,""));if(value.trim().isEmpty()||"null".equals(value))bad("缺少授权参数："+key);return value;}
    private String digest(String value){try{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))out.append(String.format("%02x",b));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private Object canonical(Object value){if(value instanceof Map){Map<String,Object> sorted=new TreeMap<>();((Map<?,?>)value).forEach((key,item)->sorted.put(String.valueOf(key),canonical(item)));return sorted;}if(value instanceof List){List<Object> ordered=new ArrayList<>();((List<?>)value).forEach(item->ordered.add(canonical(item)));return ordered;}return value;}
    private String encode(Object value){try{return json.writeValueAsString(canonical(value));}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,Object> decode(Object raw){try{return raw instanceof Map?(Map<String,Object>)raw:json.readValue(String.valueOf(raw),Map.class);}catch(Exception e){throw new IllegalStateException(e);}}
    private void bad(String message){throw new ResponseStatusException(HttpStatus.CONFLICT,message);}
}

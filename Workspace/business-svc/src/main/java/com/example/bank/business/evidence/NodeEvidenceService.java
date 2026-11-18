package com.example.bank.business.evidence;

import com.example.bank.business.port.ProcessPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

/** 根据流程返回的节点策略接收照片并验证提交凭证，各交易共用。 */
@Service
public class NodeEvidenceService {
    private final ProcessPort process;private final PhotoEvidenceService photos;private final JdbcTemplate jdbc;private final ObjectMapper json;
    public NodeEvidenceService(ProcessPort process,PhotoEvidenceService photos,JdbcTemplate jdbc,ObjectMapper json){this.process=process;this.photos=photos;this.jdbc=jdbc;this.json=json;}
    public Map<String,Object> upload(MultipartFile file,String processId,String actorId,String actorRole,String fieldName,String eventId)throws Exception {
        Map<String,Object> state=process.state(processId),vars=(Map<String,Object>)state.get("vars"),policy=(Map<String,Object>)state.get("nodePolicy");
        if(!"RUNNING".equals(state.get("processStatus"))||policy==null||!((List<?>)policy.getOrDefault("actors",Collections.emptyList())).contains(actorRole))bad("当前节点不接受此参与方的照片");
        String kind=null;
        if("CUSTOMER".equals(actorRole)) {
            if(actorId.isEmpty()||!Objects.equals(vars.get("mobile_peer_id"),actorId))bad("客户会话与业务不匹配");
            for(Map<String,Object> field:(List<Map<String,Object>>)policy.getOrDefault("fields",Collections.emptyList()))if(fieldName.equals(field.get("name"))&&Arrays.asList("photo","signature").contains(field.get("type")))kind=String.valueOf(field.get("evidenceKind"));
        } else if("AGENT".equals(actorRole)) {
            if(actorId.isEmpty()||!Objects.equals(vars.get("agent_session_id"),actorId))bad("当前业务尚未由该坐席认领");
            Map<String,Object> evidence=(Map<String,Object>)policy.getOrDefault("evidence",Collections.emptyMap());
            if("witness_photo".equals(fieldName))kind=(String)evidence.get("kind");
        }
        if(kind==null||kind.isEmpty()||"null".equals(kind))bad("当前节点未配置此照片字段");
        Map<String,Object> context=new LinkedHashMap<>();
        if("CUSTOMER_SIGNATURE".equals(kind)){
            if(policy.get("signatureScope") instanceof List){
                Map<String,Object> scope=new TreeMap<>();for(Object field:(List<?>)policy.get("signatureScope"))scope.put(String.valueOf(field),vars.get(String.valueOf(field)));context.put("signedTerms",scope);
            } else {
            Map<String,Object> input=new LinkedHashMap<>();input.put("bizNo",state.get("bizNo"));input.put("processId",processId);input.put("payeeName",vars.get("payee"));input.put("payeeAccount",vars.get("payee_account"));input.put("amount",vars.get("amount"));input.put("purpose",vars.getOrDefault("purpose",""));context.put("signedTerms",TransactionDocumentService.terms(input));
            }
        }
        return photos.save(file,String.valueOf(state.get("bizNo")),eventId,processId,String.valueOf(state.get("nodeName")),actorId,actorRole,kind,context);
    }
    public void verifySubmission(Map<String,Object> state,Map<String,Object> input) {
        Map<String,Object> policy=(Map<String,Object>)state.getOrDefault("nodePolicy",Collections.emptyMap());
        Map<String,Object> evidence=(Map<String,Object>)policy.getOrDefault("evidence",Collections.emptyMap());
        Object values=input.get("payload");if(!(values instanceof Map))values=input.get("values");
        Map<String,Object> supplied=values instanceof Map?(Map<String,Object>)values:Collections.emptyMap();
        if(!Objects.equals(policy.get("action"),input.get("action")))return;
        Map<String,Object> receipts=map(supplied.get("evidenceReceipts"));
        String customer=String.valueOf(input.getOrDefault("clientId",""));
        for(Map<String,Object> field:(List<Map<String,Object>>)policy.getOrDefault("fields",Collections.emptyList())) {
            String type=String.valueOf(field.get("type")),name=String.valueOf(field.get("name"));
            if(!Arrays.asList("photo","signature").contains(type)||supplied.get(name)==null)continue;
            if(!validReceipt(state,String.valueOf(receipts.getOrDefault(name,"")),customer,"CUSTOMER",String.valueOf(field.get("evidenceKind"))))bad("请先保存当前节点的"+field.get("label")+"，再提交");
        }
        if(!Boolean.TRUE.equals(evidence.get("required")))return;
        String receipt=String.valueOf(supplied.getOrDefault("evidence_receipt","")),agent=String.valueOf(input.getOrDefault("agentId",""));
        if(!validReceipt(state,receipt,agent,"AGENT",String.valueOf(evidence.get("kind"))))bad("请先保存当前节点的核验照片，再提交授权");
    }
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object value){return value instanceof Map?(Map<String,Object>)value:Collections.emptyMap();}
    private boolean validReceipt(Map<String,Object> state,String receipt,String actor,String role,String kind){
        if(receipt.trim().isEmpty()||actor.trim().isEmpty()||kind.trim().isEmpty()||"null".equals(kind))return false;
        for(Map<String,Object> row:jdbc.queryForList("SELECT event_payload FROM biz_interaction_trace WHERE biz_no=? AND process_id=? AND node_name=? AND event_id=? AND actor_role=? AND event_type='CAPTURE_SAVED'",state.get("bizNo"),state.get("processId"),state.get("nodeName"),receipt,role)) {
            try{Map<String,Object> payload=json.readValue(String.valueOf(row.get("event_payload")),Map.class);if(Objects.equals(payload.get("actorId"),actor)&&Objects.equals(payload.get("kind"),kind))return true;}catch(Exception e){throw new IllegalStateException("照片凭证无法读取",e);}
        }
        return false;
    }
    private void bad(String message){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}

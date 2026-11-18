package com.example.bank.business.service;
import com.example.bank.business.application.CallSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import javax.annotation.PostConstruct;
import java.util.*;
@Service
public class EvaluationService {
    private final JdbcTemplate jdbc;private final CallSessionService calls;private final ObjectMapper json;
    public EvaluationService(JdbcTemplate jdbc,CallSessionService calls,ObjectMapper json){this.jdbc=jdbc;this.calls=calls;this.json=json;}
    @PostConstruct public void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS biz_evaluation_tag(id BIGINT AUTO_INCREMENT PRIMARY KEY,tag_code VARCHAR(64) UNIQUE NOT NULL,label VARCHAR(100) NOT NULL,star_level INT NOT NULL,is_common BOOLEAN NOT NULL DEFAULT FALSE,enabled BOOLEAN NOT NULL DEFAULT TRUE,sort_order INT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS biz_call_evaluation(call_id VARCHAR(64) PRIMARY KEY,customer_id VARCHAR(100) NOT NULL,stars INT NOT NULL,tags_json MEDIUMTEXT NOT NULL,comment_text VARCHAR(1000),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        String[][] seeds={{"SERVICE_PATIENT","服务耐心","5","1"},{"CLEAR_EXPLANATION","讲解清楚","5","1"},{"FAST_HANDLING","办理高效","5","1"},{"SERVICE_GOOD","服务较好","4","0"},{"NORMAL_SERVICE","服务一般","3","0"},{"LONG_WAIT","等待较久","2","0"},{"NEEDS_IMPROVEMENT","需要改进","1","0"}};
        for(int i=0;i<seeds.length;i++)jdbc.update("INSERT IGNORE INTO biz_evaluation_tag(tag_code,label,star_level,is_common,sort_order) VALUES(?,?,?,?,?)",seeds[i][0],seeds[i][1],Integer.parseInt(seeds[i][2]),"1".equals(seeds[i][3]),i);
    }
    public List<Map<String,Object>> tags(Integer stars){if(stars!=null)validStars(stars);return stars==null?jdbc.queryForList("SELECT id,label,star_level AS stars,is_common AS isCommon FROM biz_evaluation_tag WHERE enabled=TRUE AND is_common=TRUE ORDER BY sort_order,id"):jdbc.queryForList("SELECT id,label,star_level AS stars,is_common AS isCommon FROM biz_evaluation_tag WHERE enabled=TRUE AND star_level=? ORDER BY sort_order,id",stars);}
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> submit(Map<String,Object> input){
        jdbc.queryForList("SELECT call_id FROM biz_call_session WHERE call_id=? FOR UPDATE",text(input.get("callId")));
        String callId=text(input.get("callId")),customer=text(input.get("customerId"));Map<String,Object> call=calls.requiredCall(callId);
        if(!"ENDED".equals(call.get("status"))||!"AGENT".equals(call.get("end_initiator"))||!customer.equals(call.get("customer_id")))bad("该客户不能评价这次通话");
        int stars;try{stars=Integer.parseInt(text(input.get("stars")));}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择星级");}validStars(stars);
        List<?> selected=input.get("tagIds") instanceof List?(List<?>)input.get("tagIds"):Collections.emptyList();
        List<Map<String,Object>> snapshot=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(Object id:selected){if(!ids.add(text(id)))continue;List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,label,star_level AS stars FROM biz_evaluation_tag WHERE id=? AND enabled=TRUE AND star_level=?",id,stars);if(rows.size()!=1)bad("评价标签与当前星级不匹配");snapshot.add(rows.get(0));}
        String comment=text(input.get("comment"));if(comment.length()>1000)bad("评价意见过长");
        try{String encoded=json.writeValueAsString(snapshot);List<Map<String,Object>> prior=jdbc.queryForList("SELECT * FROM biz_call_evaluation WHERE call_id=?",callId);if(!prior.isEmpty()){Map<String,Object> old=prior.get(0);if(stars!=((Number)old.get("stars")).intValue()||!encoded.equals(old.get("tags_json"))||!comment.equals(text(old.get("comment_text"))))throw new ResponseStatusException(HttpStatus.CONFLICT,"本次通话已评价");return Collections.<String,Object>singletonMap("saved",true);}jdbc.update("INSERT INTO biz_call_evaluation(call_id,customer_id,stars,tags_json,comment_text) VALUES(?,?,?,?,?)",callId,customer,stars,encoded,comment);return Collections.<String,Object>singletonMap("saved",true);}catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    private void validStars(int stars){if(stars<1||stars>5)bad("评价星级必须为1至5星");}
    private String text(Object value){return value==null?"":String.valueOf(value);}
    private void bad(String message){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}

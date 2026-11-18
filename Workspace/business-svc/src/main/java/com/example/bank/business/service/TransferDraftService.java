package com.example.bank.business.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import javax.annotation.PostConstruct;
import java.util.*;
import java.util.regex.*;

/** 转账槽位由业务服务检查；模型的 slotsComplete 不能决定是否允许交易。 */
@Service
public class TransferDraftService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public TransferDraftService(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    @PostConstruct public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS biz_transfer_draft(biz_no VARCHAR(64) PRIMARY KEY, draft_json MEDIUMTEXT NOT NULL, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
    }
    public Map<String,Object> complete(Map<String,Object> input,Map<String,Object> result) {
        // 草稿按业务主键存取：调用方没带 bizNo 时退回解析结果里的 bizNo；两者都缺失必须 400，
        // 不允许把草稿写进字面量 "null" 键这种静默补值。
        Object rawBizNo = input.get("bizNo");
        if (rawBizNo == null || String.valueOf(rawBizNo).trim().isEmpty() || "null".equals(String.valueOf(rawBizNo).trim())) {
            rawBizNo = result.get("bizNo");
        }
        String bizNo = rawBizNo == null ? "" : String.valueOf(rawBizNo).trim();
        if (bizNo.isEmpty() || "null".equals(bizNo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少业务标识 bizNo");
        }
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT draft_json FROM biz_transfer_draft WHERE biz_no=?",bizNo);
        Map<String,Object> draft=new LinkedHashMap<>();
        if(!rows.isEmpty())try { draft.putAll(json.readValue(String.valueOf(rows.get(0).get("draft_json")),Map.class)); }catch(Exception e){throw new IllegalStateException("转账草稿读取失败",e);}
        String text=String.valueOf(input.getOrDefault("text",""));
        Matcher account=Pattern.compile("(?<![0-9])([0-9][0-9 ]{8,34}[0-9])(?![0-9])").matcher(text);
        boolean hasAccount=account.find();
        boolean transfer="TRANSFER".equals(result.get("intent")) || text.matches(".*(转账|汇款).*" ) || (!draft.isEmpty() && hasAccount);
        if(!transfer)return result;
        Map<String,Object> supplied=result.get("slots") instanceof Map?(Map<String,Object>)result.get("slots"):result;
        for(String key:Arrays.asList("amount","payee","purpose")) {
            Object value=supplied.get(key);
            if(value!=null && !String.valueOf(value).trim().isEmpty() && !"未知".equals(value) && !(value instanceof Number && ((Number)value).doubleValue()<=0))draft.put(key,value);
        }
        if(hasAccount)draft.put("payee_account",account.group(1).replace(" ",""));
        Matcher amount=Pattern.compile("(?:转账|金额|汇款)[^0-9]{0,4}([0-9]+(?:\\.[0-9]{1,2})?)\\s*(万|[wW]|元)?").matcher(text);
        if(amount.find()){java.math.BigDecimal value=new java.math.BigDecimal(amount.group(1));if("万".equals(amount.group(2))||"w".equalsIgnoreCase(amount.group(2)))value=value.multiply(new java.math.BigDecimal("10000"));draft.put("amount",value);}
        List<String> missing=new ArrayList<>();
        if(String.valueOf(draft.getOrDefault("payee","")).trim().isEmpty())missing.add("收款人姓名");
        if(!String.valueOf(draft.getOrDefault("payee_account","")).matches("[0-9]{10,30}"))missing.add("收款账号（10至30位数字）");
        try { if(new java.math.BigDecimal(String.valueOf(draft.get("amount"))).signum()<=0)missing.add("转账金额"); } catch(Exception e){missing.add("转账金额");}
        try { jdbc.update("INSERT INTO biz_transfer_draft(biz_no,draft_json) VALUES(?,?) ON DUPLICATE KEY UPDATE draft_json=VALUES(draft_json)",bizNo,json.writeValueAsString(draft)); }catch(Exception e){throw new IllegalStateException("转账草稿保存失败",e);}
        Map<String,Object> response=new LinkedHashMap<>(result);response.put("success",true);response.put("intent","TRANSFER");
        response.put("slots",draft);response.putAll(draft);response.put("slotsComplete",missing.isEmpty());response.put("missingFields",missing);
        // 识别转账意图即进入 Flowable 信息采集节点，缺少要素由节点表单补齐。
        response.put("action","SUBMIT");response.put("processKey","advanced_sop");
        Map<String,Object> vars=response.get("startVars") instanceof Map?new LinkedHashMap<>((Map<String,Object>)response.get("startVars")):new LinkedHashMap<>();
        vars.remove("payee_account");vars.putAll(draft);vars.put("biz_no",bizNo);vars.putIfAbsent("customer_name","客户");vars.putIfAbsent("service_mode","DIGITAL");
        response.put("startVars",vars);response.put("processId",input.get("processId")==null?"":String.valueOf(input.get("processId")));
        response.put("reply",missing.isEmpty()?"请在下方表单核对转账信息，确认无误后继续办理。":"已记录您的转账需求，请在下方表单填写"+String.join("、",missing)+"，核对后点击确认交易信息。");
        return response;
    }
}

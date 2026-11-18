package com.example.bank.business.application;

import com.example.bank.business.port.ProcessPort;
import com.example.bank.business.service.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** 通用坐席受理入口：交易选择来自目录，流程和业务表单由场景包定义。 */
@Service
public class BusinessApplicationService {
    private final ProcessPort process;
    private final BizRecordService records;
    private final TradeCatalogService catalog;
    public BusinessApplicationService(ProcessPort process,BizRecordService records,TradeCatalogService catalog) {
        this.process=process;this.records=records;this.catalog=catalog;
    }
    public String start(Map<String,Object> input) {
        String agent=String.valueOf(input.getOrDefault("agentId",""));
        if(agent.trim().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"缺少坐席标识");
        Map<String,Object> trade=catalog.resolve(input);
        String bizNo="BIZ"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        Map<String,Object> vars=new LinkedHashMap<>();
        vars.put("biz_no",bizNo); vars.put("customer_name",input.getOrDefault("customerName","待录入客户"));
        vars.put("entry_type","AGENT");vars.put("channel","坐席主动受理");
        vars.put("service_mode",input.getOrDefault("serviceMode","HUMAN"));
        vars.put("agent_session_id",agent);vars.put("agent_peer_id",input.getOrDefault("peerId",""));
        vars.put("trade_code",trade.get("tradeCode"));vars.put("trade_name",trade.get("tradeName"));vars.put("biz_type",trade.get("bizType"));
        if(input.get("payload") instanceof Map) vars.put("payload",input.get("payload"));
        String id=process.start(String.valueOf(trade.get("processDefinitionKey")),bizNo,vars);
        records.createRecord(bizNo,id,String.valueOf(vars.get("customer_name")),"",String.valueOf(trade.get("bizType")),0D,"","HUMAN_COLLABORATIVE");
        records.updateTradeInfo(bizNo,String.valueOf(vars.get("channel")),null,null,
                String.valueOf(trade.get("tradeCode")),String.valueOf(trade.get("tradeName")));
        return id;
    }
}

package com.example.bank.business.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import java.util.*;

/** 服务内多笔业务的明确关联；不能按同一客户猜测属于本次服务的流水。 */
@Service
public class ServiceBusinessCatalog {
    private final JdbcTemplate jdbc;
    public ServiceBusinessCatalog(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @PostConstruct public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS biz_call_business(call_id VARCHAR(64) NOT NULL,process_id VARCHAR(64) NOT NULL,biz_no VARCHAR(64) NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(call_id,biz_no))");
    }
    public void attach(String callId,String processId,String bizNo) {
        if(bizNo==null || bizNo.trim().isEmpty()) throw new IllegalArgumentException("服务关联缺少业务流水");
        jdbc.update("INSERT IGNORE INTO biz_call_business(call_id,process_id,biz_no) VALUES(?,?,?)",callId,processId,bizNo);
    }
    public List<Map<String,Object>> list(Map<String,Object> call) {
        String callId=String.valueOf(call.get("call_id"));
        // 根业务来自服务登记，后续业务只能由业务编排显式关联。旧服务不改写历史记录。
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT DISTINCT r.biz_no,r.process_instance_id,r.trade_code,r.trade_name,r.status FROM biz_record r WHERE (r.biz_no=? AND r.process_instance_id=?) OR EXISTS(SELECT 1 FROM biz_call_business b WHERE b.call_id=? AND b.biz_no=r.biz_no AND b.process_id=r.process_instance_id) ORDER BY r.biz_no",call.get("biz_no"),call.get("process_id"),callId);
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:rows) {
            Map<String,Object> item=new LinkedHashMap<>();item.put("bizNo",row.get("biz_no"));item.put("processId",row.get("process_instance_id"));
            item.put("tradeCode",row.get("trade_code"));
            Object name=row.get("trade_name");
            item.put("tradeName",name==null || String.valueOf(name).trim().isEmpty()?"业务名称未登记":name);
            String status=String.valueOf(row.get("status"));
            item.put("handlingResult","COMPLETED".equals(status)?"COMPLETED":Arrays.asList("FAILED","REJECTED").contains(status)?"FAILED":"PROCESSING");
            result.add(item);
        }
        return result;
    }
}

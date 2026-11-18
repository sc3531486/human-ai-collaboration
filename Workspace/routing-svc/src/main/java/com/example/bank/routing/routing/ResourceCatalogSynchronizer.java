package com.example.bank.routing.routing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

/** 只同步资源属性，在线态与占用由路由自己维护；目录失败不生成演示资源。 */
@Service
public class ResourceCatalogSynchronizer {
    private static final Logger log = LoggerFactory.getLogger(ResourceCatalogSynchronizer.class);
    private final RestTemplate http;
    private final String baseUrl;
    private final AgentRegistry agents;
    private final DigitalHumanRegistry digitalHumans;
    public ResourceCatalogSynchronizer(RestTemplate http, @Value("${routing.business-base-url}") String baseUrl,
                                       AgentRegistry agents, DigitalHumanRegistry digitalHumans) {
        this.http=http; this.baseUrl=baseUrl; this.agents=agents; this.digitalHumans=digitalHumans;
    }
    @Scheduled(fixedDelayString="${routing.resource-sync-ms:10000}")
    public void synchronize() {
        try { refresh(); }
        catch (RuntimeException error) { log.warn("资源目录同步失败，保留已登记的真实状态；不会补造资源：{}", error.getMessage()); }
    }
    @SuppressWarnings("unchecked")
    public void refresh() {
        Map<String,Object> catalog=http.getForObject(baseUrl + "/internal/mock/resources", Map.class);
        if (catalog==null || !(catalog.get("agents") instanceof List) || !(catalog.get("digitalHumans") instanceof List)) {
            throw new IllegalStateException("业务资源目录缺少 agents/digitalHumans 列表");
        }
        List<Map<String,Object>> humanRows=(List<Map<String,Object>>)catalog.get("agents");
        List<Map<String,Object>> digitalRows=(List<Map<String,Object>>)catalog.get("digitalHumans");
        Set<String> ids=new HashSet<>();
        for(Map<String,Object> row:humanRows) validate(row,"agentId",ids);
        ids.clear();
        for(Map<String,Object> row:digitalRows) {
            validate(row,"digitalHumanId",ids);
            if (!Arrays.asList("ONLINE","OFFLINE").contains(row.get("status"))) throw new IllegalStateException("数字人目录缺少有效状态");
        }
        for(Map<String,Object> row:humanRows) agents.updateMetadata((String)row.get("agentId"),(String)row.get("name"),(List<String>)row.get("skills"));
        for(Map<String,Object> row:digitalRows) digitalHumans.register((String)row.get("digitalHumanId"),(String)row.get("name"),(List<String>)row.get("skills"),(String)row.get("status"));
    }
    private void validate(Map<String,Object> row,String idField,Set<String> ids) {
        if (row==null || !(row.get(idField) instanceof String) || ((String)row.get(idField)).trim().isEmpty()
                || !ids.add((String)row.get(idField)) || !(row.get("name") instanceof String)
                || !(row.get("skills") instanceof List) || ((List<?>)row.get("skills")).isEmpty()) {
            throw new IllegalStateException("业务资源目录存在缺失或重复资源属性：" + idField);
        }
        for(Object skill:(List<?>)row.get("skills")) if (!(skill instanceof String) || ((String)skill).trim().isEmpty()) {
            throw new IllegalStateException("业务资源目录存在无效技能组");
        }
    }
}

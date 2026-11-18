package com.example.bank.business.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

/** 交易目录来自配置；受理入口按目录选择流程，不包含场景分支。 */
@Service
public class TradeCatalogService {
    private final List<Map<String,Object>> catalog;
    public TradeCatalogService(ObjectMapper json) throws java.io.IOException {
        try (java.io.InputStream stream = new ClassPathResource("trades.json").getInputStream()) {
            catalog=json.readValue(stream,new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});
        }
        Set<Object> codes=new HashSet<>();
        for (Map<String,Object> trade:catalog) if (!codes.add(trade.get("tradeCode"))) throw new IllegalStateException("交易目录编码重复");
    }
    public List<Map<String,Object>> all() { List<Map<String,Object>> out=new ArrayList<>();catalog.forEach(t->out.add(new LinkedHashMap<>(t)));return out; }
    public List<Map<String,Object>> bySkills(Collection<String> skills) {
        if(skills==null || skills.isEmpty()) return all();
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> trade:catalog) if(((List<?>)trade.get("skills")).stream().anyMatch(skills::contains)) out.add(new LinkedHashMap<>(trade));
        return out;
    }
    public Map<String,Object> resolve(Map<String,?> input) {
        String[] keys={"tradeCode","processDefinitionKey","bizType"};
        for(String key:keys) {
            Object value=input.get(key);
            if(value==null || String.valueOf(value).trim().isEmpty()) continue;
            for(Map<String,Object> trade:catalog) if(String.valueOf(value).equalsIgnoreCase(String.valueOf(trade.get(key)))) return enabled(trade);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"未注册的交易："+value);
        }
        return enabled(catalog.get(0));
    }
    private Map<String,Object> enabled(Map<String,Object> trade) {
        if(String.valueOf(trade.get("processDefinitionKey")).isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"该交易尚未发布流程");
        return new LinkedHashMap<>(trade);
    }
}

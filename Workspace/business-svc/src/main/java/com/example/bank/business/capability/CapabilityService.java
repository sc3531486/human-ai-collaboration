package com.example.bank.business.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import javax.annotation.PostConstruct;
import java.util.*;

/** 受控业务接口适配与持久化调用收据；结果未知时禁止重复执行有副作用的操作。 */
@Service
public class CapabilityService {
    public static class Definition {
        public String path;
        public List<String> acceptedCodes = new ArrayList<>(Arrays.asList("000000"));
        public List<String> required = new ArrayList<>();
        public Map<String,Object> expected = new LinkedHashMap<>();
    }
    private final Map<String,Definition> definitions = new LinkedHashMap<>();
    private final RestTemplate http;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String baseUrl;
    public CapabilityService(RestTemplate http, JdbcTemplate jdbc, ObjectMapper json,
            @Value("${business.capabilities.base-url:http://localhost:8080}") String baseUrl) {
        this.http=http; this.jdbc=jdbc; this.json=json; this.baseUrl=baseUrl;
    }
    @PostConstruct
    public void initialize() throws Exception {
        try (java.io.InputStream stream = new ClassPathResource("capabilities.json").getInputStream()) {
            Map<String,Object> raw = json.readValue(stream, Map.class);
            raw.forEach((code, value) -> definitions.put(code,json.convertValue(value,Definition.class)));
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS capability_receipt (operation_id VARCHAR(240) PRIMARY KEY, capability VARCHAR(100) NOT NULL, request_json MEDIUMTEXT NOT NULL, status VARCHAR(20) NOT NULL, response_json MEDIUMTEXT, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
    }

    @SuppressWarnings("unchecked")
    public Map<String,Object> invoke(String code, Map<String,Object> request) {
        Definition definition = definitions.get(code);
        if (definition == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"能力未注册");
        Object op = request.get("operationId");
        if (!(op instanceof String) || !((String)op).matches("[a-zA-Z0-9:._-]{8,240}") || !(request.get("payload") instanceof Map))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"缺少有效 operationId 或 payload");
        Map<String,Object> input = new LinkedHashMap<>((Map<String,Object>)request.get("payload"));
        for (String field : definition.required) if (input.get(field) == null || String.valueOf(input.get(field)).trim().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"缺少能力参数：" + field);
        String encoded = encode(canonical(input));
        try { jdbc.update("INSERT INTO capability_receipt(operation_id,capability,request_json,status) VALUES(?,?,?,'PENDING')",op,code,encoded); }
        catch (DuplicateKeyException duplicate) {
            Map<String,Object> receipt = jdbc.queryForMap("SELECT * FROM capability_receipt WHERE operation_id=?",op);
            if (!code.equals(receipt.get("capability")) || !encoded.equals(receipt.get("request_json")))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"operationId 已绑定其他请求");
            if ("SUCCEEDED".equals(receipt.get("status"))) return decode(receipt.get("response_json"));
            throw new ResponseStatusException(HttpStatus.CONFLICT,"能力结果待核对，禁止重复执行：" + op);
        }
        try {
            // 地址来自服务端能力目录；相同操作号同时传给外围系统，便于其实现幂等和查单。
            input.put("operationId",op);
            HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Idempotency-Key",String.valueOf(op));
            Map<String,Object> response = http.postForObject(baseUrl + definition.path, new HttpEntity<>(input,headers), Map.class);
            if (response == null || !definition.acceptedCodes.contains(String.valueOf(response.get("code"))) || !(response.get("data") instanceof Map))
                throw new IllegalStateException("外围接口未返回明确成功结果");
            Map<String,Object> output = (Map<String,Object>)response.get("data");
            for (Map.Entry<String,Object> expected : definition.expected.entrySet())
                if (!Objects.equals(expected.getValue(),output.get(expected.getKey()))) throw new IllegalStateException("业务结果未满足成功条件：" + expected.getKey());
            Map<String,Object> result = new LinkedHashMap<>(); result.put("operationId",op); result.put("status","SUCCEEDED"); result.put("data",output);
            jdbc.update("UPDATE capability_receipt SET status='SUCCEEDED',response_json=? WHERE operation_id=?",encode(result),op);
            return result;
        } catch (RuntimeException failure) {
            jdbc.update("UPDATE capability_receipt SET status='UNKNOWN' WHERE operation_id=? AND status='PENDING'",op);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"能力调用未确认成功，已保留核对记录：" + op,failure);
        }
    }
    private Object canonical(Object value) {
        if (value instanceof Map) { Map<String,Object> sorted=new TreeMap<>(); ((Map<?,?>)value).forEach((k,v)->sorted.put(String.valueOf(k),canonical(v))); return sorted; }
        if (value instanceof List) { List<Object> list=new ArrayList<>(); ((List<?>)value).forEach(v->list.add(canonical(v))); return list; }
        return value;
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("数据不能序列化",e); } }
    private Map<String,Object> decode(Object value) { try { return json.readValue(String.valueOf(value),Map.class); } catch(Exception e) { throw new IllegalStateException("调用收据损坏",e); } }
}

package com.example.bank.workflow.framework;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 流程数据的唯一读写边界：表单与可信接口结果分别存储，兼容旧实例的顶层变量。 */
@Component("flowData")
public class FlowData {
    private final ObjectMapper json;
    public FlowData(ObjectMapper json) { this.json = json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS); }

    public Map<String, Object> object(Object raw) {
        if (raw == null) return new LinkedHashMap<>();
        try {
            String text = raw instanceof String ? (String) raw : json.writeValueAsString(raw);
            if (text.getBytes(StandardCharsets.UTF_8).length > 262144) throw new IllegalArgumentException("载荷超过 256 KiB");
            Object parsed = json.readValue(text, Object.class);
            if (!(parsed instanceof Map)) throw new IllegalArgumentException("载荷必须为 JSON 对象");
            check(parsed, 0);
            return json.convertValue(parsed, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("载荷不是有效的 JSON 对象", e); }
    }

    private void check(Object value, int depth) {
        if (depth > 32) throw new IllegalArgumentException("JSON 嵌套超过 32 层");
        if (value instanceof Map) {
            ((Map<?, ?>) value).forEach((k, v) -> {
                if ("__proto__".equals(k) || "constructor".equals(k) || "prototype".equals(k))
                    throw new IllegalArgumentException("非法字段名");
                check(v, depth + 1);
            });
        } else if (value instanceof List) {
            if (((List<?>) value).size() > 10000) throw new IllegalArgumentException("数组过长");
            ((List<?>) value).forEach(v -> check(v, depth + 1));
        }
    }

    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("数据无法序列化", e); }
    }

    /** 对象递归合并，数组整体替换，未提交字段保留。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> merge(Map<String, Object> previous, Map<String, Object> patch) {
        Map<String, Object> merged = new LinkedHashMap<>(previous);
        patch.forEach((key, value) -> merged.put(key,
                value instanceof Map && previous.get(key) instanceof Map
                        ? merge((Map<String, Object>) previous.get(key), (Map<String, Object>) value) : value));
        return object(merged);
    }

    /** 展示兼容投影；不把业务字段再次写回引擎顶层变量。 */
    public Map<String, Object> project(Map<String, Object> variables) {
        Map<String, Object> view = new LinkedHashMap<>(variables);
        view.putAll(object(variables.get("payload")));
        view.putAll(object(variables.get("results")));
        return view;
    }

    public Object value(DelegateExecution execution, String path) { return read(project(execution.getVariables()), path); }

    public Object read(Map<String, Object> source, String path) {
        Object value = source;
        for (String part : path.split("\\.")) {
            if (!(value instanceof Map)) return null;
            value = ((Map<?, ?>) value).get(part);
        }
        return value;
    }

    /** 幂等摘要递归排序对象键，保留数组顺序。 */
    public Object canonical(Object value) {
        if (value instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>();
            ((Map<?, ?>) value).forEach((k, v) -> sorted.put(String.valueOf(k), canonical(v)));
            return sorted;
        }
        if (value instanceof List) {
            List<Object> list = new ArrayList<>();
            ((List<?>) value).forEach(v -> list.add(canonical(v)));
            return list;
        }
        return value;
    }
}

package com.example.bank.business.mock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.util.Map;

/** 演示资源名册的唯一数据源；可通过配置替换为外部 JSON 文件。 */
@Service
public class MockResourceCatalog {
    private final Resource resource;
    private final ObjectMapper json;
    public MockResourceCatalog(@Value("${business.mock.resources-location:classpath:mock-resources.json}") Resource resource, ObjectMapper json) {
        this.resource = resource;
        this.json = json;
    }
    public Map<String, Object> read() throws java.io.IOException {
        try (InputStream stream = resource.getInputStream()) {
            Map<String, Object> result = json.readValue(stream, new TypeReference<Map<String, Object>>() {});
            if (!(result.get("agents") instanceof java.util.List) || !(result.get("digitalHumans") instanceof java.util.List)) {
                throw new IllegalStateException("资源目录必须包含 agents 和 digitalHumans 列表");
            }
            return result;
        }
    }
}

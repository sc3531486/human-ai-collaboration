package com.example.bank.workflow.port;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/** {@link ArchivePort} 的 HTTP 实现：查询业务逻辑服务的存证归档状态。 */
@Component
public class HttpArchiveAdapter implements ArchivePort {

    private final RestTemplate http;
    private final String baseUrl;

    public HttpArchiveAdapter(RestTemplate http, @Value("${workflow.business-base-url}") String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @SuppressWarnings("unchecked")
    @Override
    public String archiveStatus(String bizNo, String entryType, boolean needHuman) {
        // bizNo 形如 BIZ-20260915-0001，但走 query 参数仍要转义：客户名与流水号都曾是中文字段的来源
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/internal/record/archive-status")
                .queryParam("bizNo", bizNo)
                .queryParam("entryType", entryType)
                .queryParam("needHuman", needHuman)
                .toUriString();
        Map<String, Object> response;
        try {
            response = http.getForObject(url, Map.class);
        } catch (RestClientException error) {
            // 转成专用异常：轮询循环据此区分"下游抖动（保留跟踪重试）"与"流程不存在（停止跟踪）"
            throw new ArchiveUnavailableException("查询存证归档状态失败，bizNo=" + bizNo, error);
        }
        if (response == null || response.get("archiveStatus") == null) {
            throw new ArchiveUnavailableException("存证归档状态响应缺少 archiveStatus 字段，bizNo=" + bizNo, null);
        }
        return String.valueOf(response.get("archiveStatus"));
    }
}

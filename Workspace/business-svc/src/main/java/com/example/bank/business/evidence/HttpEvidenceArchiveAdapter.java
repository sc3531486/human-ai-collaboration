package com.example.bank.business.evidence;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 归档端口的 HTTP 适配器骨架。
 *
 * <p>目标系统与 URL 由 {@code EvidenceRecordService} 从 {@code sys_config} 解析后传入；
 * 本类只负责把证据元数据 POST 给归档系统并解析回执。项目不同只需换 URL/适配器。</p>
 */
@Component
public class HttpEvidenceArchiveAdapter implements EvidenceArchivePort {

    private final RestTemplate http;

    public HttpEvidenceArchiveAdapter(RestTemplate http) {
        this.http = http;
    }

    @Override
    public ArchiveReceipt archive(ArchiveRequest request) {
        ArchiveReceipt receipt = new ArchiveReceipt();
        receipt.targetSystem = request.targetSystem;
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("evidenceId", request.evidenceId);
            body.put("bizNo", request.bizNo);
            body.put("mediaType", request.mediaType);
            body.put("mediaSubtype", request.mediaSubtype);
            body.put("filePath", request.filePath);
            body.put("fileHash", request.fileHash);
            body.put("targetSystem", request.targetSystem);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> response = http.postForEntity(
                    request.url, new HttpEntity<Map<String, Object>>(body, headers), Map.class);
            Map<?, ?> payload = response.getBody();
            receipt.success = response.getStatusCode().is2xxSuccessful();
            receipt.archiveRef = archiveRef(payload, request.targetSystem);
            if (!receipt.success) {
                receipt.message = "归档系统返回 " + response.getStatusCodeValue();
            }
        } catch (RestClientException error) {
            receipt.success = false;
            receipt.message = error.getMessage();
        }
        return receipt;
    }

    private String archiveRef(Map<?, ?> payload, String fallback) {
        if (payload != null) {
            Object ref = payload.get("archiveRef");
            if (ref == null) ref = payload.get("receiptId");
            if (ref != null && !String.valueOf(ref).trim().isEmpty() && !"null".equals(String.valueOf(ref))) {
                return String.valueOf(ref);
            }
        }
        return fallback;
    }
}

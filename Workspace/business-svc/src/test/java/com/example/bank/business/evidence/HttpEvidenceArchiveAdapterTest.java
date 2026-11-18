package com.example.bank.business.evidence;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpEvidenceArchiveAdapterTest {

    @Test
    void parsesArchiveReferenceFromReceipt() {
        RestTemplate http = mock(RestTemplate.class);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("archiveRef", "A-1");
        when(http.postForEntity(eq("http://archive/image"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<Map>(body, HttpStatus.OK));

        EvidenceArchivePort.ArchiveReceipt receipt = new HttpEvidenceArchiveAdapter(http).archive(request());

        assertTrue(receipt.success);
        assertEquals("IMAGE_ARCHIVE", receipt.targetSystem);
        assertEquals("A-1", receipt.archiveRef);
    }

    @Test
    void reportsFailureWhenArchiveSystemIsUnreachable() {
        RestTemplate http = mock(RestTemplate.class);
        when(http.postForEntity(eq("http://archive/image"), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new ResourceAccessException("down"));

        EvidenceArchivePort.ArchiveReceipt receipt = new HttpEvidenceArchiveAdapter(http).archive(request());

        assertFalse(receipt.success);
        assertEquals("IMAGE_ARCHIVE", receipt.targetSystem);
        assertEquals("down", receipt.message);
    }

    private EvidenceArchivePort.ArchiveRequest request() {
        EvidenceArchivePort.ArchiveRequest request = new EvidenceArchivePort.ArchiveRequest();
        request.evidenceId = "EV-1";
        request.bizNo = "BIZ-1";
        request.mediaType = "IMAGE";
        request.mediaSubtype = "ID_CARD_FRONT";
        request.filePath = "20261019/BIZ-1/ID_CARD_FRONT/1.jpg";
        request.fileHash = "hash";
        request.targetSystem = "IMAGE_ARCHIVE";
        request.url = "http://archive/image";
        return request;
    }
}

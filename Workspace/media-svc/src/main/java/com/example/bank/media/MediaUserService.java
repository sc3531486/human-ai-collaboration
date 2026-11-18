package com.example.bank.media;

import com.example.bank.media.persistence.MediaUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 媒体身份由媒体服务分配，业务身份与每次浏览器会话的 RTC 标识分离。 */
@Service
public class MediaUserService {

    private MediaUserRepository users;

    @Value("${media.platform-code:PEERJS}")
    private String platformCode = "PEERJS";

    @Autowired(required = false)
    public void setUserRepository(MediaUserRepository users) {
        this.users = users;
    }

    public Map<String, Object> apply(Map<String, String> input) {
        String role = input.get("role");
        String subject = input.get("subject");
        if (!("AGENT".equals(role) || "CUSTOMER".equals(role)) || subject == null || subject.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要合法的 role 与 subject");
        }
        String id = ("AGENT".equals(role) ? "bank-" : "cust-") + UUID.randomUUID().toString();
        String subjectName = text(input.get("subjectName"));
        if (subjectName == null) subjectName = text(input.get("name"));
        if (users != null) {
            users.insert(id, id, subject.trim(), subjectName, role, platformCode, null);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("userId", id);
        result.put("peerId", id);
        result.put("subject", subject.trim());
        result.put("role", role);
        result.put("platformCode", platformCode);
        return result;
    }

    private String text(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}

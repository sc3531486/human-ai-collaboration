package com.example.bank.signaling.port;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class HttpPresenceEventAdapterTest {

    @Test
    void reportsGenericConnectionClosedEnvelope() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://localhost:8080/internal/events/signaling"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.type").value("CONNECTION_CLOSED"))
                .andExpect(jsonPath("$.payload.subjectId").value("a1"))
                .andExpect(jsonPath("$.payload.role").value("AGENT"))
                .andRespond(withSuccess("{\"accepted\":true}", MediaType.APPLICATION_JSON));

        HttpPresenceEventAdapter adapter = new HttpPresenceEventAdapter(rest, "http://localhost:8080");
        assertTrue(adapter.disconnected("a1", "PRESENCE_EMPTY", "PRESENCE_SWEEP", "agent:a1"));
        server.verify();
    }
}

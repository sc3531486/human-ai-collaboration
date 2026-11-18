package com.example.bank.business.service;

import com.example.bank.business.mock.MockResourceCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentLoginServiceTest {
    private AgentLoginService accounts;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:agent-login-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        String resources = "{\"agents\":[{\"agentId\":\"AGENT-9527\",\"name\":\"林晓\",\"skills\":[\"GENERAL\"]}],\"digitalHumans\":[]}";
        MockResourceCatalog catalog = new MockResourceCatalog(
                new ByteArrayResource(resources.getBytes(StandardCharsets.UTF_8)), new ObjectMapper());
        accounts = new AgentLoginService(jdbc, catalog);
        org.springframework.test.util.ReflectionTestUtils.setField(accounts, "demoAccountsEnabled", true);
        accounts.initialize();
    }

    @Test
    void seededAccountReturnsTheMappedAgentProfile() {
        Map<String, Object> profile = accounts.login("1", "1");
        assertEquals("AGENT-9527", profile.get("agentId"));
        assertEquals("坐席1", profile.get("name"));
        assertEquals("远程银行中心", profile.get("centerName"));
        assertEquals("视频银行部", profile.get("institutionName"));
        assertEquals("1", profile.get("loginName"));
    }

    @Test
    void rejectsWrongPassword() {
        assertThrows(ResponseStatusException.class, () -> accounts.login("1", "wrong"));
    }

    @Test
    void fiveFailuresLockAccountEvenWhenRequestsFail() {
        for (int i=0; i<5; i++) assertThrows(ResponseStatusException.class, () -> accounts.login("1", "wrong"));
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> accounts.login("1", "1"));
        assertEquals(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,error.getStatus());
    }
}

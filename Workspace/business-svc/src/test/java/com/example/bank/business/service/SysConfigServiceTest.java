package com.example.bank.business.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SysConfigServiceTest {

    private SysConfigService service() {
        String url = "jdbc:h2:mem:config-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        SysConfigService config = new SysConfigService(new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")));
        config.initialize();
        return config;
    }

    @Test
    void storesReadsAndUpdatesByPrefix() {
        SysConfigService config = service();
        config.set("media.platform.active", "PEERJS", "默认平台");
        config.set("routing.ring-timeout-ms", "30000", "振铃超时");

        assertEquals("PEERJS", config.get("media.platform.active"));
        assertEquals(30000, config.getInt("routing.ring-timeout-ms", 0));
        assertEquals(1, config.list("media.").size());
        assertEquals(2, config.list(null).size());

        config.set("media.platform.active", "AGORA", "切换平台");
        assertEquals("AGORA", config.require("media.platform.active"));
        assertEquals(1, config.list("media.").size());
    }

    @Test
    void missingRequiredKeyFailsFast() {
        SysConfigService config = service();
        assertNull(config.get("missing.key"));
        assertEquals(45, config.getInt("missing.key", 45));
        assertThrows(IllegalStateException.class, () -> config.require("missing.key"));
    }
}

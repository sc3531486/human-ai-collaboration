package com.example.bank.business.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台参数配置：URL、超时、开关等统一放数据库，按服务前缀区分 key。
 *
 * <p>只保留一张表，放 business 库；产品服务通过 {@code /internal/config} 拉取并缓存，
 * 不在各自库里重复建表。密钥不存明文，这里只负责普通配置值。</p>
 */
@Service
public class SysConfigService {

    private final JdbcTemplate jdbc;
    private final Map<String, String> cache = new ConcurrentHashMap<String, String>();

    public SysConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS sys_config ("
                + "config_key VARCHAR(128) NOT NULL COMMENT '配置键，如 integration.media.base-url',"
                + "config_value TEXT NOT NULL COMMENT '配置值（URL、超时、开关等）',"
                + "description VARCHAR(255) DEFAULT NULL COMMENT '配置说明',"
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',"
                + "PRIMARY KEY (config_key)"
                + ")");
        reload();
    }

    /** 从数据库重新加载全部配置；配置量小，整体替换比逐 key 失效更简单。 */
    public synchronized void reload() {
        Map<String, String> loaded = new LinkedHashMap<String, String>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT config_key, config_value FROM sys_config")) {
            loaded.put(String.valueOf(row.get("config_key")), String.valueOf(row.get("config_value")));
        }
        cache.clear();
        cache.putAll(loaded);
    }

    /** 取配置值；不存在返回 {@code null}。 */
    public String get(String key) {
        return cache.get(key);
    }

    public String get(String key, String defaultValue) {
        String value = cache.get(key);
        return value == null ? defaultValue : value;
    }

    /** 必填配置；缺失时启动/调用即失败，不带病运行。 */
    public String require(String key) {
        String value = cache.get(key);
        if (value == null) {
            throw new IllegalStateException("缺少配置：" + key);
        }
        return value;
    }

    public int getInt(String key, int defaultValue) {
        String value = cache.get(key);
        return value == null || value.trim().isEmpty() ? defaultValue : Integer.parseInt(value.trim());
    }

    public long getLong(String key, long defaultValue) {
        String value = cache.get(key);
        return value == null || value.trim().isEmpty() ? defaultValue : Long.parseLong(value.trim());
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = cache.get(key);
        return value == null || value.trim().isEmpty() ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    /** 按前缀返回配置，供产品服务拉取自己的那一段。 */
    public Map<String, String> list(String prefix) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : cache.entrySet()) {
            if (prefix == null || prefix.isEmpty() || entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /** 新增或更新配置；更新后立即刷新缓存。 */
    public synchronized void set(String key, String value, String description) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("配置键不能为空");
        }
        if (value == null) {
            throw new IllegalArgumentException("配置值不能为空：" + key);
        }
        int updated = jdbc.update(
                "UPDATE sys_config SET config_value=?, description=?, updated_at=CURRENT_TIMESTAMP WHERE config_key=?",
                value, description, key);
        if (updated == 0) {
            jdbc.update("INSERT INTO sys_config(config_key, config_value, description) VALUES(?,?,?)",
                    key, value, description);
        }
        cache.put(key, value);
    }
}

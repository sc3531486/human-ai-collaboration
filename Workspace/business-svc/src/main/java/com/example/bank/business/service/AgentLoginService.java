package com.example.bank.business.service;

import com.example.bank.business.mock.MockResourceCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import javax.annotation.PostConstruct;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 公共账号认证与数据库坐席档案。接待状态由路由服务维护。 */
@Service
public class AgentLoginService {
    private final JdbcTemplate jdbc;
    @Value("${business.auth.demo-accounts-enabled:false}") private boolean demoAccountsEnabled;
    public AgentLoginService(JdbcTemplate jdbc, MockResourceCatalog ignored) { this.jdbc = jdbc; }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_login_account(login_name VARCHAR(32) PRIMARY KEY,agent_id VARCHAR(100) NOT NULL UNIQUE,password_hash VARCHAR(255) NOT NULL,enabled BOOLEAN NOT NULL DEFAULT TRUE,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("ALTER TABLE agent_login_account MODIFY COLUMN password_hash VARCHAR(255) NOT NULL");
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_auth_security(login_name VARCHAR(32) PRIMARY KEY,failed_attempts INT NOT NULL DEFAULT 0,locked_until TIMESTAMP NULL,last_login_at TIMESTAMP NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_center(center_id VARCHAR(64) PRIMARY KEY,name VARCHAR(100) NOT NULL,enabled BOOLEAN NOT NULL DEFAULT TRUE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_institution(institution_id VARCHAR(64) PRIMARY KEY,center_id VARCHAR(64) NOT NULL,name VARCHAR(100) NOT NULL,enabled BOOLEAN NOT NULL DEFAULT TRUE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_position(position_id VARCHAR(64) PRIMARY KEY,name VARCHAR(100) NOT NULL,enabled BOOLEAN NOT NULL DEFAULT TRUE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_profile(agent_id VARCHAR(100) PRIMARY KEY,employee_no VARCHAR(32) NOT NULL UNIQUE,name VARCHAR(100) NOT NULL,center_id VARCHAR(64) NOT NULL,institution_id VARCHAR(64) NOT NULL,position_id VARCHAR(64) NOT NULL,phone VARCHAR(32),enabled BOOLEAN NOT NULL DEFAULT TRUE,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_profile_skill(agent_id VARCHAR(100) NOT NULL,skill_code VARCHAR(64) NOT NULL,PRIMARY KEY(agent_id,skill_code))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_auth_audit(id BIGINT AUTO_INCREMENT PRIMARY KEY,login_name VARCHAR(32),agent_id VARCHAR(100),event_type VARCHAR(32) NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        if (demoAccountsEnabled) seedDemoAccounts();
    }

    private void seedDemoAccounts() {
        jdbc.update("INSERT IGNORE INTO agent_center(center_id,name) VALUES('REMOTE','远程银行中心')");
        jdbc.update("INSERT IGNORE INTO agent_institution(institution_id,center_id,name) VALUES('VIDEO','REMOTE','视频银行部')");
        jdbc.update("INSERT IGNORE INTO agent_position(position_id,name) VALUES('TELLER','视频柜员')");
        String[] ids = {"AGENT-9527", "AGENT-1024", "AGENT-2048"};
        for (int i = 0; i < ids.length; i++) {
            String account = String.valueOf(i + 1);
            jdbc.update("INSERT IGNORE INTO agent_login_account(login_name,agent_id,password_hash,enabled) VALUES(?,?,?,TRUE)", account, ids[i], hash(account));
            jdbc.update("INSERT IGNORE INTO agent_profile(agent_id,employee_no,name,center_id,institution_id,position_id) VALUES(?,?,?,'REMOTE','VIDEO','TELLER')", ids[i], account, "坐席" + account);
            for (String skill : Arrays.asList("GENERAL", "VIP", "PRIVATE_BANKING", "AUTHORIZATION")) jdbc.update("INSERT IGNORE INTO agent_profile_skill(agent_id,skill_code) VALUES(?,?)", ids[i], skill);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> login(String username, String password) {
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        // 登录失败也提交计数和审计；异常在事务结束后抛出，避免失败计数被回滚。
        Object outcome = transaction.execute(status -> {
            try { return loginLocked(username, password); }
            catch (ResponseStatusException error) { return error; }
        });
        if (outcome instanceof ResponseStatusException) throw (ResponseStatusException)outcome;
        return (Map<String,Object>)outcome;
    }

    private Map<String, Object> loginLocked(String username, String password) {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty() || name.length() > 32 || password == null || password.length() > 256) throw unauthorized();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM agent_login_account WHERE login_name=? FOR UPDATE", name);
        if (rows.size() != 1) { audit(name, "", "LOGIN_FAILED"); throw unauthorized(); }
        Map<String, Object> row = rows.get(0);
        jdbc.update("INSERT IGNORE INTO agent_auth_security(login_name) VALUES(?)", name);
        Map<String, Object> security = jdbc.queryForList("SELECT * FROM agent_auth_security WHERE login_name=?", name).get(0);
        Timestamp locked = (Timestamp) security.get("locked_until");
        if (locked != null && locked.getTime() > System.currentTimeMillis()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "账号暂时锁定，请稍后再试");
        String encoded = String.valueOf(row.get("password_hash"));
        if (!Boolean.TRUE.equals(row.get("enabled")) || !verify(password, encoded)) {
            int attempts = locked != null ? 1 : ((Number) security.get("failed_attempts")).intValue() + 1;
            jdbc.update("UPDATE agent_auth_security SET failed_attempts=?,locked_until=? WHERE login_name=?", attempts, attempts >= 5 ? new Timestamp(System.currentTimeMillis() + 900000) : null, name);
            audit(name, String.valueOf(row.get("agent_id")), "LOGIN_FAILED"); throw unauthorized();
        }
        Map<String, Object> profile = profile(String.valueOf(row.get("agent_id")));
        if (!encoded.startsWith("pbkdf2$")) jdbc.update("UPDATE agent_login_account SET password_hash=? WHERE login_name=?", hash(password), name);
        jdbc.update("UPDATE agent_auth_security SET failed_attempts=0,locked_until=NULL,last_login_at=CURRENT_TIMESTAMP WHERE login_name=?", name);
        audit(name, String.valueOf(row.get("agent_id")), "LOGIN_SUCCESS"); return profile;
    }

    public Map<String, Object> profile(String agentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT p.agent_id AS agentId,p.employee_no AS employeeNo,p.name,p.phone,c.center_id AS centerId,c.name AS centerName,i.institution_id AS institutionId,i.name AS institutionName,j.position_id AS positionId,j.name AS positionName,a.login_name AS loginName FROM agent_profile p JOIN agent_center c ON c.center_id=p.center_id JOIN agent_institution i ON i.institution_id=p.institution_id AND i.center_id=p.center_id JOIN agent_position j ON j.position_id=p.position_id JOIN agent_login_account a ON a.agent_id=p.agent_id WHERE p.agent_id=? AND p.enabled=TRUE AND a.enabled=TRUE AND c.enabled=TRUE AND i.enabled=TRUE AND j.enabled=TRUE", agentId);
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "坐席档案或所属组织未启用");
        Map<String, Object> profile = rows.get(0);
        profile.put("skills", jdbc.queryForList("SELECT skill_code FROM agent_profile_skill WHERE agent_id=? ORDER BY skill_code", String.class, agentId));
        return profile;
    }

    public List<Map<String, Object>> profiles() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : jdbc.queryForList("SELECT agent_id FROM agent_profile WHERE enabled=TRUE", String.class)) result.add(profile(id));
        return result;
    }
    public String loginNameForAgent(String agentId) { return String.valueOf(profile(agentId).get("loginName")); }
    public void audit(String username, String agentId, String type) { jdbc.update("INSERT INTO agent_auth_audit(login_name,agent_id,event_type) VALUES(?,?,?)", username, agentId, type); }
    private ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误"); }
    private String hash(String password) {
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        return "pbkdf2$210000$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(derive(password, salt, 210000));
    }
    private boolean verify(String password, String encoded) {
        try {
            if (encoded.startsWith("pbkdf2$")) {
                String[] parts = encoded.split("\\$");
                return MessageDigest.isEqual(Base64.getDecoder().decode(parts[3]), derive(password, Base64.getDecoder().decode(parts[2]), Integer.parseInt(parts[1])));
            }
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder legacy = new StringBuilder(); for (byte item : bytes) legacy.append(String.format("%02x", item));
            return MessageDigest.isEqual(encoded.getBytes(StandardCharsets.UTF_8), legacy.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { return false; }
    }
    private byte[] derive(String password, byte[] salt, int rounds) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, rounds, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (Exception error) { throw new IllegalStateException("密码摘要生成失败", error); }
        finally { spec.clearPassword(); }
    }
}

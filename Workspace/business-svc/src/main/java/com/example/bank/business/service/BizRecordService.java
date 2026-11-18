package com.example.bank.business.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;

@Service
public class BizRecordService {
    /** 成功数以持久化终态为准，重复事件不会重复计数。 */
    public int successCountToday(String agentId) {
        Integer count=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM biz_record WHERE agent_id=? AND status='COMPLETED' AND completed_time>=CURRENT_DATE",Integer.class,agentId);
        return count==null?0:count;
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void initTables() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS biz_action_log (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "biz_no VARCHAR(64), action_type VARCHAR(100), action_title VARCHAR(255), action_payload TEXT, "
                    + "client_time VARCHAR(100), created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            String sqlBizRecord = "CREATE TABLE IF NOT EXISTS biz_record (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "biz_no VARCHAR(64) NOT NULL UNIQUE, " +
                    "process_instance_id VARCHAR(64), " +
                    "customer_name VARCHAR(64) DEFAULT '测试用户', " +
                    "cert_no_masked VARCHAR(64) DEFAULT '610923199710******', " +
                    "biz_type VARCHAR(64) DEFAULT 'TRANSFER', " +
                    "amount DECIMAL(18,2) DEFAULT 0.00, " +
                    "payee VARCHAR(64) DEFAULT '未知', " +
                    "handler_mode VARCHAR(32) DEFAULT 'DIGITAL_ONLY', " +
                    "status VARCHAR(32) DEFAULT 'PROCESSING', " +
                    "agent_id VARCHAR(32), " +
                    "segments_count INT DEFAULT 0, " +
                    "created_time DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                    "completed_time DATETIME, " +
                    "INDEX idx_biz_no (biz_no), " +
                    "INDEX idx_proc_id (process_instance_id), " +
                    "INDEX idx_created (created_time)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;";
            jdbcTemplate.execute(sqlBizRecord);

            String sqlSegment = "CREATE TABLE IF NOT EXISTS biz_record_segment (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "biz_no VARCHAR(64) NOT NULL, " +
                    "process_instance_id VARCHAR(64), " +
                    "segment_index INT NOT NULL, " +
                    "handler_type VARCHAR(32) NOT NULL, " +
                    "handler_name VARCHAR(64) NOT NULL, " +
                    "file_name VARCHAR(255) NOT NULL, " +
                    "file_url VARCHAR(255) NOT NULL, " +
                    "file_size BIGINT DEFAULT 0, " +
                    "duration_seconds INT DEFAULT 0, " +
                    "file_hash VARCHAR(128), " +
                    "nodes_covered VARCHAR(255), " +
                    "created_time DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                    "INDEX idx_seg_biz (biz_no), " +
                    "INDEX idx_seg_proc (process_instance_id)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;";
            jdbcTemplate.execute(sqlSegment);

            // 补充风控反欺诈规则评估专属字段
            try { jdbcTemplate.execute("ALTER TABLE biz_record ADD COLUMN risk_score INT DEFAULT 0;"); } catch(Exception ignored) {}
            try { jdbcTemplate.execute("ALTER TABLE biz_record ADD COLUMN risk_level VARCHAR(32);"); } catch(Exception ignored) {}
            try { jdbcTemplate.execute("ALTER TABLE biz_record ADD COLUMN risk_rules TEXT;"); } catch(Exception ignored) {}
            try { jdbcTemplate.execute("ALTER TABLE biz_record ADD COLUMN risk_decision VARCHAR(255);"); } catch(Exception ignored) {}
            try { jdbcTemplate.execute("ALTER TABLE biz_record ADD COLUMN risk_engine VARCHAR(64);"); } catch(Exception ignored) {}

            // 补充交易展示与办理失败专属字段（幂等迁移，列已存在时忽略异常）
            try { jdbcTemplate.execute("ALTER TABLE biz_record ADD COLUMN channel VARCHAR(32);"); } catch(Exception ignored) {}
            try { jdbcTemplate.execute("ALTER TABLE biz_record ADD COLUMN cert_type VARCHAR(32);"); } catch(Exception ignored) {}
            try { jdbcTemplate.execute("ALTER TABLE biz_record ADD COLUMN customer_no VARCHAR(64);"); } catch(Exception ignored) {}
            try { jdbcTemplate.execute("ALTER TABLE biz_record ADD COLUMN trade_code VARCHAR(32);"); } catch(Exception ignored) {}
            try { jdbcTemplate.execute("ALTER TABLE biz_record ADD COLUMN trade_name VARCHAR(64);"); } catch(Exception ignored) {}
            try { jdbcTemplate.execute("ALTER TABLE biz_record ADD COLUMN failure_reason VARCHAR(255);"); } catch(Exception ignored) {}

            System.out.println("✅ [BizRecordService] 业务存证数据表 biz_record / biz_record_segment 校验完毕");
        } catch (Exception e) {
            // 建表失败必须让启动失败：打一行警告继续跑，会让服务带着一张不存在的表接收业务，
            // 之后每一次写入才在运行时炸开，且现场已经找不到根因。上面那些 ALTER 是幂等迁移
            // （列已存在），与"表根本没建起来"是两回事，不能共用同一个 catch。
            throw new IllegalStateException("业务存证数据表初始化失败", e);
        }
    }

    public void updateRiskInfo(String bizNo, int riskScore, String riskLevel, String riskRules, String riskDecision, String riskEngine) {
        String sql = "UPDATE biz_record SET risk_score = ?, risk_level = ?, risk_rules = ?, risk_decision = ?, risk_engine = ? WHERE biz_no = ?";
        jdbcTemplate.update(sql, riskScore, riskLevel, riskRules, riskDecision, riskEngine, bizNo);
    }

    /** 补写交易展示字段：进线渠道、证件类型、客户号与交易品种。 */
    public void updateTradeInfo(String bizNo, String channel, String certType, String customerNo, String tradeCode, String tradeName) {
        // bizNo 为空直接返回，避免误更新全表
        if (bizNo == null || bizNo.trim().isEmpty()) {
            return;
        }
        // COALESCE 保证入参为 null 的列保留原值，不被空值覆盖
        String sql = "UPDATE biz_record SET channel = COALESCE(?, channel), cert_type = COALESCE(?, cert_type), " +
                "customer_no = COALESCE(?, customer_no), trade_code = COALESCE(?, trade_code), " +
                "trade_name = COALESCE(?, trade_name) WHERE biz_no = ?";
        jdbcTemplate.update(sql, channel, certType, customerNo, tradeCode, tradeName, bizNo);
    }

    /** 记录业务办理失败原因；与 REJECTED（流程终止）语义不同。 */
    public void updateFailureReason(String bizNo, String reason) {
        // bizNo 为空直接返回，避免误更新全表
        if (bizNo == null || bizNo.trim().isEmpty()) {
            return;
        }
        String sql = "UPDATE biz_record SET failure_reason = ? WHERE biz_no = ?";
        jdbcTemplate.update(sql, reason, bizNo);
    }

    /** 咨询阶段先建立流水，随后在同一条记录关联流程实例。 */
    public void ensureConversation(String bizNo){
        jdbcTemplate.update("INSERT INTO biz_record(biz_no,customer_name,cert_no_masked,biz_type,handler_mode,status) VALUES(?,'客户','','CONSULTATION','DIGITAL_ONLY','PROCESSING') ON DUPLICATE KEY UPDATE biz_no=VALUES(biz_no)",bizNo);
    }

    public void createRecord(String bizNo, String processInstanceId, String customerName, 
                             String certNoMasked, String bizType, Double amount, 
                             String payee, String handlerMode) {
        String sql = "INSERT INTO biz_record (biz_no, process_instance_id, customer_name, cert_no_masked, " +
                "biz_type, amount, payee, handler_mode, status, segments_count, created_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSING', 0, NOW()) " +
                "ON DUPLICATE KEY UPDATE process_instance_id = VALUES(process_instance_id), amount = VALUES(amount), payee = VALUES(payee)";
        jdbcTemplate.update(sql, bizNo, processInstanceId, customerName, certNoMasked, bizType, amount, payee, handlerMode);
    }

    /**
     * 更新业务办理状态。
     * 终态语义区分：
     * REJECTED = 流程已终止（客户主动放弃、超时未确认、风控阻断等流程层面的结束）；
     * FAILED   = 业务办理失败（流程已走完但核心记账/转账未成功，失败原因由 updateFailureReason 记录）。
     */
    public void updateStatus(String bizNo, String status, String handlerMode, String agentId) {
        StringBuilder sql = new StringBuilder("UPDATE biz_record SET status = ? ");
        List<Object> params = new ArrayList<>();
        params.add(status);

        if (handlerMode != null && !handlerMode.isEmpty()) {
            sql.append(", handler_mode = ? ");
            params.add(handlerMode);
        }
        if (agentId != null && !agentId.isEmpty()) {
            sql.append(", agent_id = ? ");
            params.add(agentId);
        }
        if ("COMPLETED".equalsIgnoreCase(status) || "REJECTED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
            sql.append(", completed_time = NOW() ");
        }
        sql.append("WHERE biz_no = ?");
        params.add(bizNo);
        jdbcTemplate.update(sql.toString(), params.toArray());
    }

    public void addSegment(String bizNo, String processInstanceId, int segmentIndex, 
                           String handlerType, String handlerName, String fileName, 
                           String fileUrl, long fileSize, int durationSeconds, 
                           String fileHash, String nodesCovered) {
        String insertSql = "INSERT INTO biz_record_segment (biz_no, process_instance_id, segment_index, " +
                "handler_type, handler_name, file_name, file_url, file_size, duration_seconds, " +
                "file_hash, nodes_covered, created_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";
        jdbcTemplate.update(insertSql, bizNo, processInstanceId, segmentIndex, handlerType, handlerName, 
                fileName, fileUrl, fileSize, durationSeconds, fileHash, nodesCovered);

        String updateCountSql = "UPDATE biz_record SET segments_count = segments_count + 1 ";
        if ("HUMAN_AGENT".equalsIgnoreCase(handlerType)) {
            updateCountSql += ", handler_mode = 'HUMAN_COLLABORATIVE' ";
        }
        updateCountSql += "WHERE biz_no = ?";
        jdbcTemplate.update(updateCountSql, bizNo);
    }

    public List<Map<String, Object>> listRecords(String keyword, String handlerMode, int limit) {
        StringBuilder sql = new StringBuilder("SELECT id, biz_no, process_instance_id, customer_name, " +
                "cert_no_masked, biz_type, amount, payee, handler_mode, status, agent_id, segments_count, " +
                "created_time, " +
                "completed_time " +
                "FROM biz_record WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (keyword != null && !keyword.trim().isEmpty()) {
            sql.append("AND (biz_no LIKE ? OR customer_name LIKE ? OR payee LIKE ?) ");
            String likeVal = "%" + keyword.trim() + "%";
            params.add(likeVal);
            params.add(likeVal);
            params.add(likeVal);
        }

        if (handlerMode != null && !handlerMode.trim().isEmpty() && !"ALL".equalsIgnoreCase(handlerMode)) {
            sql.append("AND handler_mode = ? ");
            params.add(handlerMode.trim());
        }

        sql.append("ORDER BY id DESC LIMIT ?");
        params.add(limit <= 0 ? 50 : limit);

        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    /**
     * 分页查询业务流水，返回 {"rows": 当页行, "total": 总条数, "page": 页码, "pageSize": 每页条数}。
     * WHERE 过滤条件与三参重载保持一致，仅追加分页与新增展示列。
     */
    public Map<String, Object> listRecords(String keyword, String handlerMode, int page, int pageSize) {
        // 页码最小 1；每页条数钳制在 1 ~ 200
        int safePage = page < 1 ? 1 : page;
        int safeSize = pageSize < 1 ? 1 : (pageSize > 200 ? 200 : pageSize);

        StringBuilder where = new StringBuilder("FROM biz_record WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (keyword != null && !keyword.trim().isEmpty()) {
            where.append("AND (biz_no LIKE ? OR customer_name LIKE ? OR payee LIKE ?) ");
            String likeVal = "%" + keyword.trim() + "%";
            params.add(likeVal);
            params.add(likeVal);
            params.add(likeVal);
        }

        if (handlerMode != null && !handlerMode.trim().isEmpty() && !"ALL".equalsIgnoreCase(handlerMode)) {
            where.append("AND handler_mode = ? ");
            params.add(handlerMode.trim());
        }

        // 先单独统计总条数，再取当页数据；两者复用同一组 WHERE 参数
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + where, Integer.class, params.toArray());

        StringBuilder sql = new StringBuilder("SELECT id, biz_no, process_instance_id, customer_name, " +
                "cert_no_masked, biz_type, amount, payee, handler_mode, status, agent_id, segments_count, " +
                "channel, cert_type, customer_no, trade_code, trade_name, failure_reason, " +
                "created_time, " +
                "completed_time " +
                where.toString() + "ORDER BY id DESC LIMIT ? OFFSET ?");
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(safeSize);
        pageParams.add((safePage - 1) * safeSize);

        Map<String, Object> result = new HashMap<>();
        result.put("rows", jdbcTemplate.queryForList(sql.toString(), pageParams.toArray()));
        result.put("total", total == null ? 0 : total.intValue());
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return result;
    }

    public void logAction(String bizNo, String actionType, String actionTitle, String payload, String clientTime) {
        String sql = "INSERT INTO biz_action_log (biz_no, action_type, action_title, action_payload, client_time, created_time) " +
                "VALUES (?, ?, ?, ?, ?, NOW())";
        jdbcTemplate.update(sql, bizNo, actionType, actionTitle, payload, clientTime);
    }

    public List<Map<String, Object>> getActionLogs(String bizNo) {
        String sql = "SELECT id, biz_no, action_type, action_title, action_payload, client_time, " +
                "created_time " +
                "FROM biz_action_log WHERE biz_no = ? ORDER BY id ASC";
        return jdbcTemplate.queryForList(sql, bizNo);
    }

    public Map<String, Object> appendStreamChunk(String bizNo, String processId, int segmentIndex,
                                                 String handlerType, String handlerName, int duration,
                                                 String nodesCovered, byte[] chunkData, boolean isLast) {
        Map<String, Object> res = new HashMap<>();
        try {
            java.io.File recordsDir = new java.io.File("records");
            if (!recordsDir.exists()) recordsDir.mkdirs();

            String fileName = bizNo + "_seg" + segmentIndex + "_" + handlerType + ".webm";
            java.io.File targetFile = new java.io.File(recordsDir, fileName);

            // 追加写入分片字节流
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(targetFile, true)) {
                fos.write(chunkData);
                fos.flush();
            }

            String fileUrl = "/records/" + fileName;
            res.put("fileUrl", fileUrl);
            res.put("fileSize", targetFile.length());

            if (isLast) {
                // 计算 SHA-256 司法防篡改哈希
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                try (java.io.InputStream is = new java.io.FileInputStream(targetFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        md.update(buffer, 0, read);
                    }
                }
                byte[] digest = md.digest();
                StringBuilder hexString = new StringBuilder();
                for (byte b : digest) {
                    hexString.append(String.format("%02x", b));
                }
                String fileHash = hexString.toString();

                addSegment(bizNo, processId, segmentIndex, handlerType, handlerName, fileName, fileUrl,
                        targetFile.length(), duration, fileHash, nodesCovered);

                res.put("fileHash", fileHash);
                res.put("completed", true);
                System.out.println("🎥 [LiveRoomRecorder] 房间分段实时直录已落盘闭环: " + fileName + " (Hash: " + fileHash.substring(0, 12) + "...)");
            } else {
                res.put("completed", false);
            }
            res.put("success", true);
        } catch (Exception e) {
            e.printStackTrace();
            res.put("success", false);
            res.put("error", e.getMessage());
        }
        return res;
    }

    public Map<String, Object> getRecordDetail(String bizNo) {
        String sql = "SELECT id, biz_no, process_instance_id, customer_name, cert_no_masked, " +
                "biz_type, amount, payee, handler_mode, status, agent_id, segments_count, " +
                "risk_score, risk_level, risk_rules, risk_decision, risk_engine, " +
                "created_time, " +
                "completed_time " +
                "FROM biz_record WHERE biz_no = ?";
        List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, bizNo);
        if (list.isEmpty()) {
            return null;
        }
        Map<String, Object> res = new HashMap<>(list.get(0));

        // 状态语义映射：REJECTED 为流程终止，FAILED 为业务办理失败，二者不可混用
        Object statusVal = res.get("status");
        res.put("statusMeaning", statusMeaning(statusVal == null ? null : String.valueOf(statusVal)));

        String segSql = "SELECT id, biz_no, process_instance_id, segment_index, handler_type, " +
                "handler_name, file_name, file_url, file_size, duration_seconds, file_hash, nodes_covered, " +
                "created_time " +
                "FROM biz_record_segment WHERE biz_no = ? ORDER BY segment_index ASC";
        List<Map<String, Object>> segments = jdbcTemplate.queryForList(segSql, bizNo);
        res.put("segments", segments);

        // 注入客户移动端全息操作行为轨迹
        List<Map<String, Object>> actionLogs = getActionLogs(bizNo);
        res.put("actionLogs", actionLogs);

        return res;
    }

    /** 状态中文语义；未知状态原样返回。 */
    private String statusMeaning(String status) {
        if (status == null || status.trim().isEmpty()) {
            return "";
        }
        if ("PROCESSING".equalsIgnoreCase(status)) return "办理中";
        if ("COMPLETED".equalsIgnoreCase(status)) return "已完成";
        if ("REJECTED".equalsIgnoreCase(status)) return "流程已终止";
        if ("FAILED".equalsIgnoreCase(status)) return "办理失败";
        return status;
    }

    public Map<String, Object> getTodayMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        // 看板只呈现今天的真实记录，不用历史总量或演示数字填充。
        String today = "created_time >= CURRENT_DATE";
        metrics.put("totalToday", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM biz_record WHERE " + today, Integer.class));
        metrics.put("completedToday", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM biz_record WHERE status='COMPLETED' AND " + today, Integer.class));
        metrics.put("humanCollabToday", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM biz_record WHERE handler_mode='HUMAN_COLLABORATIVE' AND " + today, Integer.class));
        metrics.put("digitalOnlyToday", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM biz_record WHERE handler_mode='DIGITAL_ONLY' AND " + today, Integer.class));
        metrics.put("totalAmountToday", jdbcTemplate.queryForObject("SELECT COALESCE(SUM(amount),0) FROM biz_record WHERE " + today, Double.class));
        return metrics;
    }
}

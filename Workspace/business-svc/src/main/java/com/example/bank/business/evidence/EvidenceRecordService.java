package com.example.bank.business.evidence;

import com.example.bank.business.service.SysConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 存证登记服务：文件登记、视频分片、封存、查询与归档状态。
 *
 * <p>文件物理存储走 {@link StoragePort}，归档协议走 {@link EvidenceArchivePort}；
 * 归档目标系统/地址从 {@code sys_config} 的
 * {@code evidence.archive.<mediaType>.target/url} 读取，未配置时记录 PENDING。</p>
 */
@Service
public class EvidenceRecordService {

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String STATUS_RECORDED = "RECORDED";
    private static final String STATUS_ARCHIVING = "ARCHIVING";
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_PENDING = "PENDING";

    private final JdbcTemplate jdbc;
    private final StoragePort storage;
    private final EvidenceArchivePort archive;
    private final SysConfigService config;

    public EvidenceRecordService(JdbcTemplate jdbc, StoragePort storage,
                                 EvidenceArchivePort archive, SysConfigService config) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.archive = archive;
        this.config = config;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS biz_file_category ("
                + "subtype_code VARCHAR(64) NOT NULL COMMENT '细分编码',"
                + "subtype_name VARCHAR(100) NOT NULL COMMENT '细分名称',"
                + "media_type VARCHAR(16) NOT NULL COMMENT '文件形态：IMAGE/VIDEO/AUDIO/PDF',"
                + "archive_system VARCHAR(64) DEFAULT NULL COMMENT '归档系统编码',"
                + "sort_order INT NOT NULL DEFAULT 0 COMMENT '排序号',"
                + "enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用：1启用，0停用',"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',"
                + "PRIMARY KEY (subtype_code))");

        jdbc.execute("CREATE TABLE IF NOT EXISTS biz_evidence ("
                + "evidence_id VARCHAR(64) NOT NULL COMMENT '证据ID（文件索引主键）',"
                + "biz_no VARCHAR(64) NOT NULL COMMENT '业务流水号',"
                + "biz_date DATE NOT NULL COMMENT '业务日期',"
                + "media_type VARCHAR(16) NOT NULL COMMENT '文件形态：IMAGE/VIDEO/AUDIO/PDF',"
                + "media_subtype VARCHAR(64) NOT NULL COMMENT '文件细分编码',"
                + "file_index INT NOT NULL COMMENT '同一流水同一细分下的文件序号，从1开始',"
                + "file_name VARCHAR(255) NOT NULL COMMENT '存储文件名',"
                + "file_path VARCHAR(512) NOT NULL COMMENT '相对存储路径',"
                + "file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',"
                + "file_hash VARCHAR(64) DEFAULT NULL COMMENT 'SHA-256 文件哈希',"
                + "mime_type VARCHAR(100) DEFAULT NULL COMMENT 'MIME 类型',"
                + "status VARCHAR(16) NOT NULL COMMENT '状态：RECORDED/ARCHIVING/ARCHIVED/FAILED/PENDING',"
                + "archive_system VARCHAR(64) DEFAULT NULL COMMENT '归档系统编码',"
                + "archive_ref VARCHAR(128) DEFAULT NULL COMMENT '归档系统返回的引用号',"
                + "archived_at DATETIME DEFAULT NULL COMMENT '归档完成时间',"
                + "attempts INT NOT NULL DEFAULT 0 COMMENT '归档重试次数',"
                + "last_error VARCHAR(500) DEFAULT NULL COMMENT '最近一次失败原因',"
                + "captured_at DATETIME DEFAULT NULL COMMENT '采集时间',"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',"
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',"
                + "PRIMARY KEY (evidence_id),"
                + "UNIQUE (biz_no, media_subtype, file_index),"
                + "KEY idx_evidence_biz (biz_no),"
                + "KEY idx_evidence_date (biz_date),"
                + "KEY idx_evidence_hash (file_hash),"
                + "KEY idx_evidence_archive (status, archive_system))");

        jdbc.execute("CREATE TABLE IF NOT EXISTS biz_evidence_segment ("
                + "segment_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分片ID',"
                + "evidence_id VARCHAR(64) NOT NULL COMMENT '证据ID',"
                + "segment_index INT NOT NULL COMMENT '分片序号，从1开始',"
                + "file_path VARCHAR(512) NOT NULL COMMENT '分片相对存储路径',"
                + "file_size BIGINT NOT NULL DEFAULT 0 COMMENT '分片大小（字节）',"
                + "file_hash VARCHAR(64) DEFAULT NULL COMMENT '分片 SHA-256 哈希',"
                + "duration_ms BIGINT NOT NULL DEFAULT 0 COMMENT '分片时长（毫秒）',"
                + "status VARCHAR(16) NOT NULL COMMENT '状态：RECORDED/SEALED/FAILED',"
                + "started_at DATETIME DEFAULT NULL COMMENT '分片开始时间',"
                + "ended_at DATETIME DEFAULT NULL COMMENT '分片结束时间',"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',"
                + "PRIMARY KEY (segment_id),"
                + "UNIQUE (evidence_id, segment_index),"
                + "KEY idx_segment_evidence (evidence_id))");

        seedCategories();
    }

    /** 登记一张图片/一份文档；返回落库后的证据。 */
    public synchronized EvidenceItem recordFile(String bizNo, String subtype, String originalName,
                                                String contentType, byte[] data) {
        requireText(bizNo, "业务流水号");
        requireText(subtype, "文件分类");
        if (data == null || data.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容为空");
        }
        Category category = category(subtype);
        int fileIndex = nextIndex(bizNo, subtype);
        String ext = extension(originalName);
        String relative = datePath() + "/" + safe(bizNo) + "/" + safe(subtype) + "/" + fileIndex + "." + ext;
        StoragePort.StoredFile stored = storage.store(relative, data);
        String evidenceId = newEvidenceId();
        Timestamp now = now();
        jdbc.update("INSERT INTO biz_evidence"
                        + "(evidence_id,biz_no,biz_date,media_type,media_subtype,file_index,file_name,file_path,"
                        + "file_size,file_hash,mime_type,status,captured_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                evidenceId, bizNo, Date.valueOf(LocalDate.now()), category.mediaType, subtype, fileIndex,
                fileIndex + "." + ext, stored.relativePath, stored.size, sha256(data),
                text(contentType, "application/octet-stream"), STATUS_RECORDED, now);
        return getItem(evidenceId);
    }

    /** 追加视频分片；未传 evidenceId 时自动创建一条视频证据。 */
    public synchronized EvidenceItem appendSegment(String bizNo, String subtype, String evidenceId,
                                                   int segmentIndex, long durationMs,
                                                   String originalName, byte[] data) {
        requireText(bizNo, "业务流水号");
        requireText(subtype, "文件分类");
        if (segmentIndex <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分片序号必须从 1 开始");
        }
        if (data == null || data.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分片内容为空");
        }
        Category category = category(subtype);
        if (!"VIDEO".equals(category.mediaType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该分类不是视频：" + subtype);
        }
        EvidenceItem item = blank(evidenceId) ? createVideoEvidence(bizNo, subtype) : getItem(evidenceId);
        evidenceId = item.evidenceId;
        if (!bizNo.equals(item.bizNo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "证据不属于该业务流水");
        }
        if (!"VIDEO".equals(item.mediaType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该证据不是视频");
        }
        String hash = sha256(data);
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT file_hash FROM biz_evidence_segment WHERE evidence_id=? AND segment_index=?",
                evidenceId, segmentIndex);
        if (!existing.isEmpty()) {
            String oldHash = text(value(existing.get(0), "file_hash"), null);
            if (hash.equals(oldHash)) {
                return getItem(evidenceId);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "分片已存在且内容不同");
        }

        String datePath = item.bizDate == null ? datePath() : item.bizDate.replace("-", "");
        String relative = datePath + "/" + safe(bizNo) + "/" + safe(subtype) + "/"
                + evidenceId + "/segments/" + segmentIndex + "." + extension(originalName);
        StoragePort.StoredFile stored = storage.store(relative, data);
        Timestamp now = now();
        Timestamp endedAt = new Timestamp(now.getTime() + Math.max(0L, durationMs));
        jdbc.update("INSERT INTO biz_evidence_segment"
                        + "(evidence_id,segment_index,file_path,file_size,file_hash,duration_ms,status,started_at,ended_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?)",
                evidenceId, segmentIndex, stored.relativePath, stored.size, hash, Math.max(0L, durationMs),
                STATUS_RECORDED, now, endedAt);
        jdbc.update("UPDATE biz_evidence SET file_size=? WHERE evidence_id=?",
                segmentTotal(evidenceId), evidenceId);
        return getItem(evidenceId);
    }

    /** 封存视频：标记分片 SEALED，汇总大小与哈希。 */
    public synchronized EvidenceItem seal(String evidenceId) {
        EvidenceItem item = getItem(evidenceId);
        if (!"VIDEO".equals(item.mediaType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只有视频证据需要封存");
        }
        if (item.segments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "视频证据没有分片，不能封存");
        }
        jdbc.update("UPDATE biz_evidence_segment SET status='SEALED' WHERE evidence_id=?", evidenceId);
        long size = 0L;
        StringBuilder combined = new StringBuilder();
        for (EvidenceSegment segment : item.segments) {
            size += segment.fileSize;
            if (segment.fileHash != null) combined.append(segment.fileHash);
        }
        String hash = combined.length() == 0 ? null : sha256(combined.toString().getBytes(StandardCharsets.UTF_8));
        jdbc.update("UPDATE biz_evidence SET file_size=?, file_hash=?, "
                        + "captured_at=COALESCE(captured_at, CURRENT_TIMESTAMP), updated_at=CURRENT_TIMESTAMP "
                        + "WHERE evidence_id=?",
                size, hash, evidenceId);
        return getItem(evidenceId);
    }

    /** 按归档目标系统归档；未配置目标/地址时记录 PENDING，HTTP 失败记录 FAILED。 */
    public synchronized EvidenceItem archive(String evidenceId) {
        EvidenceItem item = getItem(evidenceId);
        if (STATUS_ARCHIVED.equals(item.status)) {
            return item;
        }
        String prefix = "evidence.archive." + item.mediaType.toLowerCase(Locale.ROOT) + ".";
        String target = config.get(prefix + "target");
        String url = config.get(prefix + "url");
        if (blank(target) || blank(url)) {
            updateArchiveStatus(evidenceId, STATUS_PENDING, null, null,
                    "未配置归档系统或地址：" + prefix + "target/url");
            return getItem(evidenceId);
        }

        jdbc.update("UPDATE biz_evidence SET status=?, attempts=attempts+1, last_error=NULL WHERE evidence_id=?",
                STATUS_ARCHIVING, evidenceId);
        EvidenceArchivePort.ArchiveRequest request = new EvidenceArchivePort.ArchiveRequest();
        request.evidenceId = item.evidenceId;
        request.bizNo = item.bizNo;
        request.mediaType = item.mediaType;
        request.mediaSubtype = item.mediaSubtype;
        request.filePath = item.filePath;
        request.fileHash = item.fileHash;
        request.targetSystem = target;
        request.url = url;
        EvidenceArchivePort.ArchiveReceipt receipt;
        try {
            receipt = archive.archive(request);
        } catch (RuntimeException error) {
            updateArchiveStatus(evidenceId, STATUS_FAILED, target, null,
                    truncate("归档调用异常：" + error.getMessage(), 500));
            return getItem(evidenceId);
        }
        if (receipt != null && receipt.success) {
            updateArchiveStatus(evidenceId, STATUS_ARCHIVED, target, receipt.archiveRef, null);
        } else {
            String message = receipt == null ? "归档适配器未返回回执"
                    : (blank(receipt.message) ? "归档失败" : receipt.message);
            updateArchiveStatus(evidenceId, STATUS_FAILED, target, null, truncate(message, 500));
        }
        return getItem(evidenceId);
    }

    public EvidenceItem getItem(String evidenceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM biz_evidence WHERE evidence_id=?", evidenceId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "证据不存在：" + evidenceId);
        }
        EvidenceItem item = toItem(rows.get(0));
        item.segments = segments(evidenceId);
        return item;
    }

    public List<EvidenceItem> getFlow(String bizNo) {
        List<EvidenceItem> items = new ArrayList<EvidenceItem>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT * FROM biz_evidence WHERE biz_no=? ORDER BY media_subtype, file_index", bizNo)) {
            EvidenceItem item = toItem(row);
            item.segments = segments(item.evidenceId);
            items.add(item);
        }
        return items;
    }

    private EvidenceItem createVideoEvidence(String bizNo, String subtype) {
        int fileIndex = nextIndex(bizNo, subtype);
        String evidenceId = newEvidenceId();
        String dir = datePath() + "/" + safe(bizNo) + "/" + safe(subtype) + "/" + evidenceId;
        jdbc.update("INSERT INTO biz_evidence"
                        + "(evidence_id,biz_no,biz_date,media_type,media_subtype,file_index,file_name,file_path,"
                        + "file_size,file_hash,mime_type,status,captured_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                evidenceId, bizNo, Date.valueOf(LocalDate.now()), "VIDEO", subtype, fileIndex,
                evidenceId + ".video", dir, 0L, null, "video/webm", STATUS_RECORDED, now());
        return getItem(evidenceId);
    }

    private void updateArchiveStatus(String evidenceId, String status, String system, String ref, String error) {
        if (STATUS_ARCHIVED.equals(status)) {
            jdbc.update("UPDATE biz_evidence SET status=?, archive_system=?, archive_ref=?, "
                            + "archived_at=CURRENT_TIMESTAMP, last_error=?, updated_at=CURRENT_TIMESTAMP "
                            + "WHERE evidence_id=?",
                    status, system, ref, error, evidenceId);
        } else {
            jdbc.update("UPDATE biz_evidence SET status=?, archive_system=?, archive_ref=?, "
                            + "last_error=?, updated_at=CURRENT_TIMESTAMP WHERE evidence_id=?",
                    status, system, ref, error, evidenceId);
        }
    }

    private List<EvidenceSegment> segments(String evidenceId) {
        List<EvidenceSegment> segments = new ArrayList<EvidenceSegment>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT * FROM biz_evidence_segment WHERE evidence_id=? ORDER BY segment_index", evidenceId)) {
            EvidenceSegment segment = new EvidenceSegment();
            segment.segmentId = longValue(value(row, "segment_id"));
            segment.evidenceId = text(value(row, "evidence_id"), null);
            segment.segmentIndex = intValue(value(row, "segment_index"));
            segment.filePath = text(value(row, "file_path"), null);
            segment.fileSize = longValue(value(row, "file_size"));
            segment.fileHash = text(value(row, "file_hash"), null);
            segment.durationMs = longValue(value(row, "duration_ms"));
            segment.status = text(value(row, "status"), null);
            segment.startedAt = time(value(row, "started_at"));
            segment.endedAt = time(value(row, "ended_at"));
            segment.createdAt = time(value(row, "created_at"));
            segments.add(segment);
        }
        return segments;
    }

    private EvidenceItem toItem(Map<String, Object> row) {
        EvidenceItem item = new EvidenceItem();
        item.evidenceId = text(value(row, "evidence_id"), null);
        item.bizNo = text(value(row, "biz_no"), null);
        item.bizDate = time(value(row, "biz_date"));
        item.mediaType = text(value(row, "media_type"), null);
        item.mediaSubtype = text(value(row, "media_subtype"), null);
        item.fileIndex = intValue(value(row, "file_index"));
        item.fileName = text(value(row, "file_name"), null);
        item.filePath = text(value(row, "file_path"), null);
        item.fileSize = longValue(value(row, "file_size"));
        item.fileHash = text(value(row, "file_hash"), null);
        item.mimeType = text(value(row, "mime_type"), null);
        item.status = text(value(row, "status"), null);
        item.archiveSystem = text(value(row, "archive_system"), null);
        item.archiveRef = text(value(row, "archive_ref"), null);
        item.archivedAt = time(value(row, "archived_at"));
        item.attempts = intValue(value(row, "attempts"));
        item.lastError = text(value(row, "last_error"), null);
        item.capturedAt = time(value(row, "captured_at"));
        item.createdAt = time(value(row, "created_at"));
        item.updatedAt = time(value(row, "updated_at"));
        return item;
    }

    private void seedCategories() {
        seed("ID_CARD_FRONT", "身份证正面", "IMAGE", "IMAGE_ARCHIVE", 1);
        seed("BANK_CARD_FRONT", "银行卡正面", "IMAGE", "IMAGE_ARCHIVE", 2);
        seed("ID_PROOF", "身份证明", "IMAGE", "IMAGE_ARCHIVE", 3);
        seed("PROCESS_PHOTO", "过程照片", "IMAGE", "IMAGE_ARCHIVE", 4);
        seed("SIGNATURE", "签名", "IMAGE", "IMAGE_ARCHIVE", 5);
        seed("DUAL_RECORD", "双录视频", "VIDEO", "VIDEO_ARCHIVE", 6);
        seed("RECEIPT", "回执", "PDF", "DOC_ARCHIVE", 7);
    }

    private void seed(String code, String name, String mediaType, String archiveSystem, int order) {
        jdbc.update("INSERT IGNORE INTO biz_file_category"
                        + "(subtype_code,subtype_name,media_type,archive_system,sort_order,enabled)"
                        + " VALUES(?,?,?,?,?,1)",
                code, name, mediaType, archiveSystem, order);
    }

    private Category category(String subtype) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT media_type, archive_system, enabled FROM biz_file_category WHERE subtype_code=?",
                subtype);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的文件分类：" + subtype);
        }
        Map<String, Object> row = rows.get(0);
        if (!enabled(value(row, "enabled"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件分类已停用：" + subtype);
        }
        return new Category(text(value(row, "media_type"), null), text(value(row, "archive_system"), null));
    }

    private int nextIndex(String bizNo, String subtype) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(file_index),0) FROM biz_evidence WHERE biz_no=? AND media_subtype=?",
                Integer.class, bizNo, subtype);
        return (max == null ? 0 : max) + 1;
    }

    private long segmentTotal(String evidenceId) {
        Long total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(file_size),0) FROM biz_evidence_segment WHERE evidence_id=?",
                Long.class, evidenceId);
        return total == null ? 0L : total;
    }

    private Object value(Map<String, Object> row, String key) {
        if (row.containsKey(key)) return row.get(key);
        return row.get(key.toUpperCase(Locale.ROOT));
    }

    private String text(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isEmpty() ? fallback : text;
    }

    private int intValue(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private long longValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private String time(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp) return ((Timestamp) value).toLocalDateTime().toString();
        if (value instanceof Date) return value.toString();
        return String.valueOf(value);
    }

    private boolean enabled(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        return value instanceof Number && ((Number) value).intValue() == 1;
    }

    private String datePath() {
        return LocalDate.now().format(DATE_PATH);
    }

    private Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }

    private String newEvidenceId() {
        return "EV-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String safe(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法路径片段");
        }
        return cleaned;
    }

    private String extension(String originalName) {
        if (originalName == null) return "bin";
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) return "bin";
        String ext = originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,10}") ? ext : "bin";
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] result = digest.digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte value : result) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 计算失败", error);
        }
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private void requireText(String value, String name) {
        if (blank(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + "不能为空");
        }
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class Category {
        private final String mediaType;
        private final String archiveSystem;

        private Category(String mediaType, String archiveSystem) {
            this.mediaType = mediaType;
            this.archiveSystem = archiveSystem;
        }
    }
}

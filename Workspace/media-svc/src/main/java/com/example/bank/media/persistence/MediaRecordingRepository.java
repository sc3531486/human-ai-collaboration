package com.example.bank.media.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** 录制会话登记（media_recording）：录制文件仍在 business 存证，media 只存会话/回执/引用。 */
@Repository
public class MediaRecordingRepository {

    private final JdbcTemplate jdbc;

    public MediaRecordingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS media_recording ("
                + "recording_id VARCHAR(64) NOT NULL,"
                + "room_id VARCHAR(64) NOT NULL,"
                + "biz_key VARCHAR(64) DEFAULT NULL,"
                + "biz_date DATE DEFAULT NULL,"
                + "platform_code VARCHAR(32) NOT NULL,"
                + "platform_recording_id VARCHAR(128) DEFAULT NULL,"
                + "state VARCHAR(16) NOT NULL,"
                + "segment_seconds INT NOT NULL DEFAULT 0,"
                + "started_at DATETIME DEFAULT NULL,"
                + "stopped_at DATETIME DEFAULT NULL,"
                + "duration_ms BIGINT NOT NULL DEFAULT 0,"
                + "segment_count INT NOT NULL DEFAULT 0,"
                + "total_bytes BIGINT NOT NULL DEFAULT 0,"
                + "evidence_ref VARCHAR(128) DEFAULT NULL,"
                + "failure_reason VARCHAR(500) DEFAULT NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (recording_id),"
                + "KEY idx_recording_room (room_id, state),"
                + "KEY idx_recording_biz (biz_key)"
                + ")");
    }

    public void insert(String recordingId, String roomId, String bizKey, Date bizDate,
                       String platformCode, String platformRecordingId, int segmentSeconds) {
        jdbc.update("INSERT INTO media_recording"
                        + "(recording_id,room_id,biz_key,biz_date,platform_code,platform_recording_id,"
                        + "state,segment_seconds,started_at) VALUES(?,?,?,?,?,?,'RECORDING',?,CURRENT_TIMESTAMP)",
                recordingId, roomId, bizKey, bizDate, platformCode, platformRecordingId, segmentSeconds);
    }

    public Map<String, Object> find(String recordingId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM media_recording WHERE recording_id=?", recordingId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 停止录制：只允许从 STARTING/RECORDING 进入 STOPPING；重复调用保持幂等。 */
    public boolean stop(String recordingId, long stoppedAt) {
        Map<String, Object> row = find(recordingId);
        if (row == null) return false;
        String state = String.valueOf(row.get("state"));
        if (!"STARTING".equals(state) && !"RECORDING".equals(state)) return false;
        Object startedAt = row.get("started_at");
        long duration = startedAt == null ? 0L : Math.max(0L, stoppedAt - JdbcTimes.millis(startedAt));
        return jdbc.update("UPDATE media_recording SET state='STOPPING', stopped_at=?, duration_ms=? "
                        + "WHERE recording_id=? AND state IN ('STARTING','RECORDING')",
                new Timestamp(stoppedAt), duration, recordingId) > 0;
    }

    /** 业务存证封存后回填回执；media 不存文件，只存 evidence_ref 与统计。 */
    public void receipt(String recordingId, String state, String evidenceRef,
                        int segmentCount, long totalBytes, String failureReason, long now) {
        Map<String, Object> row = find(recordingId);
        if (row == null) return;
        long duration = ((Number) row.get("duration_ms")).longValue();
        if (duration == 0L && row.get("started_at") != null) {
            duration = Math.max(0L, now - JdbcTimes.millis(row.get("started_at")));
        }
        jdbc.update("UPDATE media_recording SET state=?, evidence_ref=?, segment_count=?, "
                        + "total_bytes=?, failure_reason=?, stopped_at=COALESCE(stopped_at,?), "
                        + "duration_ms=?, updated_at=CURRENT_TIMESTAMP WHERE recording_id=?",
                state, evidenceRef, segmentCount, totalBytes, failureReason,
                new Timestamp(now), duration, recordingId);
    }

    public List<Map<String, Object>> listByRoom(String roomId) {
        return jdbc.queryForList(
                "SELECT * FROM media_recording WHERE room_id=? ORDER BY created_at", roomId);
    }
}

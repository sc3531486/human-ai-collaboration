package com.example.bank.media.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** 视频房间登记（media_room）。 */
@Repository
public class MediaRoomRepository {

    private final JdbcTemplate jdbc;

    public MediaRoomRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS media_room ("
                + "room_id VARCHAR(64) NOT NULL,"
                + "biz_key VARCHAR(64) DEFAULT NULL,"
                + "biz_date DATE DEFAULT NULL,"
                + "room_name VARCHAR(100) DEFAULT NULL,"
                + "platform_code VARCHAR(32) NOT NULL,"
                + "platform_room_id VARCHAR(128) DEFAULT NULL,"
                + "state VARCHAR(16) NOT NULL,"
                + "max_participants INT NOT NULL DEFAULT 0,"
                + "participant_count INT NOT NULL DEFAULT 0,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "opened_at DATETIME DEFAULT NULL,"
                + "closed_at DATETIME DEFAULT NULL,"
                + "close_reason VARCHAR(255) DEFAULT NULL,"
                + "duration_ms BIGINT NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (room_id),"
                + "KEY idx_room_biz (biz_key),"
                + "KEY idx_room_state (state, opened_at),"
                + "KEY idx_room_date (biz_date)"
                + ")");
    }

    public Map<String, Object> find(String roomId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM media_room WHERE room_id=?", roomId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(String roomId, String bizKey, Date bizDate, String roomName,
                       String platformCode, String platformRoomId, int maxParticipants) {
        jdbc.update("INSERT INTO media_room"
                        + "(room_id,biz_key,biz_date,room_name,platform_code,platform_room_id,state,"
                        + "max_participants,participant_count,opened_at) "
                        + "VALUES(?,?,?,?,?,?,'OPEN',?,0,CURRENT_TIMESTAMP)",
                roomId, bizKey, bizDate, roomName, platformCode, platformRoomId, maxParticipants);
    }

    /** 已存在的房间重新打开；返回是否发生了状态变化。 */
    public boolean reopen(String roomId) {
        return jdbc.update("UPDATE media_room SET state='OPEN', opened_at=CURRENT_TIMESTAMP, "
                + "closed_at=NULL, close_reason=NULL, duration_ms=0, participant_count=0 "
                + "WHERE room_id=? AND state<>'OPEN'", roomId) > 0;
    }

    /** 关闭房间并回填时长；返回是否发生关闭。 */
    public boolean close(String roomId, long closedAt, String reason) {
        Map<String, Object> room = find(roomId);
        if (room == null || "CLOSED".equals(room.get("state"))) return false;
        Object openedAt = room.get("opened_at");
        long duration = openedAt == null ? 0L : Math.max(0L, closedAt - JdbcTimes.millis(openedAt));
        return jdbc.update("UPDATE media_room SET state='CLOSED', closed_at=?, duration_ms=?, "
                        + "close_reason=?, participant_count=0 WHERE room_id=? AND state<>'CLOSED'",
                new Timestamp(closedAt), duration, reason, roomId) > 0;
    }

    public void updateParticipantCount(String roomId, int count) {
        jdbc.update("UPDATE media_room SET participant_count=? WHERE room_id=?", count, roomId);
    }
}

package com.example.bank.media.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

/** 房间成员登记（media_room_participant）。 */
@Repository
public class MediaParticipantRepository {

    private final JdbcTemplate jdbc;

    public MediaParticipantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS media_room_participant ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "room_id VARCHAR(64) NOT NULL,"
                + "user_id VARCHAR(64) NOT NULL,"
                + "role VARCHAR(32) NOT NULL,"
                + "peer_id VARCHAR(64) NOT NULL,"
                + "state VARCHAR(16) NOT NULL,"
                + "joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "left_at DATETIME DEFAULT NULL,"
                + "PRIMARY KEY (id),"
                + "UNIQUE (room_id, user_id),"
                + "KEY idx_participant_room (room_id)"
                + ")");
    }

    /** 加入房间；返回是否为新加入（用于只对真正的新成员写事件）。 */
    public boolean join(String roomId, String userId, String role, String peerId) {
        Map<String, Object> existing = find(roomId, userId);
        if (existing == null) {
            jdbc.update("INSERT INTO media_room_participant(room_id,user_id,role,peer_id,state,joined_at) "
                    + "VALUES(?,?,?,?,'JOINED',CURRENT_TIMESTAMP)", roomId, userId, role, peerId);
            return true;
        }
        boolean newlyJoined = !"JOINED".equals(existing.get("state"));
        jdbc.update("UPDATE media_room_participant SET role=?, peer_id=?, "
                        + "state='JOINED', left_at=NULL WHERE room_id=? AND user_id=?",
                role, peerId, roomId, userId);
        return newlyJoined;
    }

    public Map<String, Object> find(String roomId, String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM media_room_participant WHERE room_id=? AND user_id=?", roomId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean leave(String roomId, String userId) {
        return jdbc.update("UPDATE media_room_participant SET state='LEFT', left_at=CURRENT_TIMESTAMP "
                + "WHERE room_id=? AND user_id=? AND state<>'LEFT'", roomId, userId) > 0;
    }

    public int markAllLeft(String roomId) {
        return jdbc.update("UPDATE media_room_participant SET state='LEFT', left_at=CURRENT_TIMESTAMP "
                + "WHERE room_id=? AND state<>'LEFT'", roomId);
    }

    public int countActive(String roomId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM media_room_participant WHERE room_id=? AND state='JOINED'",
                Integer.class, roomId);
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> listByRoom(String roomId) {
        return jdbc.queryForList(
                "SELECT * FROM media_room_participant WHERE room_id=? ORDER BY id", roomId);
    }
}

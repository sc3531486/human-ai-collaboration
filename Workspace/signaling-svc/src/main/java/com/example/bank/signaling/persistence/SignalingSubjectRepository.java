package com.example.bank.signaling.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** 已签发令牌的主体登记表。 */
@Repository
public class SignalingSubjectRepository {

    private final JdbcTemplate jdbc;

    public SignalingSubjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 令牌签发：主体存在则刷新角色/频道/签发时间并清除已上报；不存在则插入。 */
    public void upsert(String subjectId, String role, String channel, long registeredAt, boolean reported) {
        int updated = jdbc.update("UPDATE signaling_subject SET role=?, channel=?, reported=?, registered_at=?, "
                        + "updated_at=CURRENT_TIMESTAMP WHERE subject_id=?",
                role, channel, reported ? 1 : 0, new Timestamp(registeredAt), subjectId);
        if (updated == 0) {
            jdbc.update("INSERT INTO signaling_subject(subject_id, role, channel, reported, registered_at) "
                            + "VALUES(?,?,?,?,?)",
                    subjectId, role, channel, reported ? 1 : 0, new Timestamp(registeredAt));
        }
    }

    /** presence 在线：刷新最后在线时间并清除已上报。 */
    public void touchPresent(String subjectId, long at) {
        jdbc.update("UPDATE signaling_subject SET last_present_at=?, reported=0, updated_at=CURRENT_TIMESTAMP "
                + "WHERE subject_id=?", new Timestamp(at), subjectId);
    }

    public void updateReported(String subjectId, boolean reported) {
        jdbc.update("UPDATE signaling_subject SET reported=?, updated_at=CURRENT_TIMESTAMP WHERE subject_id=?",
                reported ? 1 : 0, subjectId);
    }

    public Long lastPresentAt(String subjectId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT last_present_at FROM signaling_subject WHERE subject_id=?", subjectId);
        if (rows.isEmpty() || rows.get(0).get("last_present_at") == null) {
            return null;
        }
        return JdbcTimes.millis(rows.get(0).get("last_present_at"));
    }

    /** 启动恢复：全部纳管主体。 */
    public List<Map<String, Object>> listAll() {
        return jdbc.queryForList("SELECT subject_id, role, registered_at, reported FROM signaling_subject "
                + "ORDER BY registered_at");
    }
}

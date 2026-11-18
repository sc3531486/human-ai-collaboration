package com.example.bank.business.application;

import com.example.bank.business.port.MediaPort;
import com.example.bank.business.port.HttpMediaAdapter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.PostConstruct;
import java.util.*;

/** 业务持久化房间关联；媒体只执行命令，消除媒体反向读取流程的HTTP环。 */
@Service
public class MediaRoomApplicationService implements MediaPort {
    private final JdbcTemplate jdbc;
    private final HttpMediaAdapter media;
    public MediaRoomApplicationService(JdbcTemplate jdbc,HttpMediaAdapter media) { this.jdbc=jdbc;this.media=media; }
    @PostConstruct public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS biz_media_room(process_id VARCHAR(64) PRIMARY KEY,room_id VARCHAR(80),state VARCHAR(16) NOT NULL,opened_at TIMESTAMP NULL,closed_at TIMESTAMP NULL)");
    }
    @Transactional public Map<String,Object> openRoom(String processId,Map<String,Object> context) {
        jdbc.update("INSERT IGNORE INTO biz_media_room(process_id,state) VALUES(?,'CREATING')",processId);
        Map<String,Object> row=jdbc.queryForList("SELECT * FROM biz_media_room WHERE process_id=? FOR UPDATE",processId).get(0);
        if("OPEN".equals(row.get("state"))) return Collections.singletonMap("roomId",row.get("room_id"));
        Map<String,Object> room=media.openRoom(processId,context);
        if(room==null || room.get("roomId")==null || String.valueOf(room.get("roomId")).trim().isEmpty()) throw new IllegalStateException("媒体服务未返回房间标识");
        jdbc.update("UPDATE biz_media_room SET room_id=?,state='OPEN',opened_at=CURRENT_TIMESTAMP,closed_at=NULL WHERE process_id=?",room.get("roomId"),processId);
        return room;
    }
    public void inviteRoom(String processId,Map<String,Object> context) { media.inviteRoom(processId,context); }
    public void closeRoom(String processId) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM biz_media_room WHERE process_id=?",processId);
        if(rows.isEmpty() || "CLOSED".equals(rows.get(0).get("state"))) return;
        media.closeRoom(processId,Collections.singletonMap("roomId",rows.get(0).get("room_id")));
        jdbc.update("UPDATE biz_media_room SET state='CLOSED',closed_at=CURRENT_TIMESTAMP WHERE process_id=? AND state<>'CLOSED'",processId);
    }
    public void hangupRoom(String roomId,Map<String,Object> context) {
        media.hangupRoom(roomId,context);
        jdbc.update("UPDATE biz_media_room SET state='CLOSED',closed_at=COALESCE(closed_at,CURRENT_TIMESTAMP) WHERE room_id=?",roomId);
    }
    public void updateParticipants(String roomId,Map<String,Object> context) { media.updateParticipants(roomId,context); }
}

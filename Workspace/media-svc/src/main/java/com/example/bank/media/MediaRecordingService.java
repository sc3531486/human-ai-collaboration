package com.example.bank.media;

import com.example.bank.media.persistence.MediaContext;
import com.example.bank.media.persistence.MediaEventRepository;
import com.example.bank.media.persistence.MediaRecordingRepository;
import com.example.bank.media.persistence.MediaRoomRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 录制会话产品能力：开始/停止/回执/查询。
 *
 * <p>录制文件仍由 business 存证；media 只登记会话、回执与 evidence_ref，
 * 不保存媒体文件本身。</p>
 */
@Service
public class MediaRecordingService {

    private final MediaRoomRepository rooms;
    private final MediaRecordingRepository recordings;
    private final MediaEventRepository events;

    @Value("${media.platform-code:PEERJS}")
    private String platformCode = "PEERJS";

    public MediaRecordingService(MediaRoomRepository rooms, MediaRecordingRepository recordings,
                                 MediaEventRepository events) {
        this.rooms = rooms;
        this.recordings = recordings;
        this.events = events;
    }

    public Map<String, Object> start(String roomId, Map<String, Object> body) {
        Map<String, Object> room = rooms.find(roomId);
        if (room == null) {
            throw new MediaServiceException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "房间不存在：" + roomId);
        }
        if ("CLOSED".equals(room.get("state"))) {
            throw new MediaServiceException(HttpStatus.CONFLICT, "ROOM_CLOSED", "房间已关闭，不能开始录制");
        }
        String recordingId = "REC-" + UUID.randomUUID();
        Integer segmentSeconds = MediaContext.integer(body, "segment_seconds");
        String code = MediaContext.text(body, "platform_code");
        if (code == null) code = platformCode;
        recordings.insert(recordingId, roomId, MediaContext.bizKey(body), MediaContext.bizDate(body),
                code, MediaContext.text(body, "platform_recording_id"),
                segmentSeconds == null ? 0 : segmentSeconds);
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("roomId", roomId);
        event.put("recordingId", recordingId);
        event.put("type", "RECORDING_STARTED");
        events.record(roomId, null, recordingId, "RECORDING_STARTED", 0L, event, null);
        return view(recordings.find(recordingId));
    }

    public Map<String, Object> stop(String recordingId) {
        Map<String, Object> row = require(recordingId);
        recordings.stop(recordingId, System.currentTimeMillis());
        Map<String, Object> current = recordings.find(recordingId);
        if ("STOPPING".equals(current.get("state")) && !"STOPPING".equals(row.get("state"))) {
            Map<String, Object> event = new LinkedHashMap<String, Object>();
            event.put("roomId", current.get("room_id"));
            event.put("recordingId", recordingId);
            event.put("type", "RECORDING_STOPPED");
            events.record(String.valueOf(current.get("room_id")), null, recordingId,
                    "RECORDING_STOPPED", number(current.get("duration_ms")), event, null);
        }
        return view(current);
    }

    public Map<String, Object> receipt(String recordingId, Map<String, Object> body) {
        Map<String, Object> row = require(recordingId);
        String state = MediaContext.text(body, "state");
        if (state == null) state = "SEALED";
        if (!Arrays.asList("SEALED", "INCOMPLETE", "FAILED").contains(state)) {
            throw new MediaServiceException(HttpStatus.BAD_REQUEST, "RECORDING_STATE",
                    "回执状态只能是 SEALED/INCOMPLETE/FAILED：" + state);
        }
        Integer segments = MediaContext.integer(body, "segment_count");
        Long totalBytes = MediaContext.longValue(body, "total_bytes");
        recordings.receipt(recordingId, state, MediaContext.text(body, "evidence_ref"),
                segments == null ? 0 : segments, totalBytes == null ? 0L : totalBytes,
                MediaContext.text(body, "failure_reason"), System.currentTimeMillis());
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("roomId", row.get("room_id"));
        event.put("recordingId", recordingId);
        event.put("type", "SEALED".equals(state) ? "RECORDING_SEALED" : "RECORDING_FAILED");
        event.put("state", state);
        event.put("evidenceRef", MediaContext.text(body, "evidence_ref"));
        events.record(String.valueOf(row.get("room_id")), null, recordingId,
                String.valueOf(event.get("type")), 0L, event, null);
        return view(recordings.find(recordingId));
    }

    public Map<String, Object> find(String recordingId) {
        return view(require(recordingId));
    }

    public List<Map<String, Object>> listByRoom(String roomId) {
        if (rooms.find(roomId) == null) {
            throw new MediaServiceException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "房间不存在：" + roomId);
        }
        return recordings.listByRoom(roomId);
    }

    private Map<String, Object> require(String recordingId) {
        Map<String, Object> row = recordings.find(recordingId);
        if (row == null) {
            throw new MediaServiceException(HttpStatus.NOT_FOUND, "RECORDING_NOT_FOUND",
                    "录制会话不存在：" + recordingId);
        }
        return row;
    }

    private Map<String, Object> view(Map<String, Object> row) {
        if (row == null) return null;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("recordingId", row.get("recording_id"));
        result.put("roomId", row.get("room_id"));
        result.put("bizKey", row.get("biz_key"));
        result.put("bizDate", row.get("biz_date"));
        result.put("platformCode", row.get("platform_code"));
        result.put("platformRecordingId", row.get("platform_recording_id"));
        result.put("state", row.get("state"));
        result.put("segmentSeconds", row.get("segment_seconds"));
        result.put("startedAt", row.get("started_at"));
        result.put("stoppedAt", row.get("stopped_at"));
        result.put("durationMs", row.get("duration_ms"));
        result.put("segmentCount", row.get("segment_count"));
        result.put("totalBytes", row.get("total_bytes"));
        result.put("evidenceRef", row.get("evidence_ref"));
        result.put("failureReason", row.get("failure_reason"));
        return result;
    }

    private long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }
}

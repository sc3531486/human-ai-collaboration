package com.example.bank.media;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 录制会话接口：只登记会话/回执，不保存媒体文件。
 *
 * <p>录制文件由 business 存证；media 通过 receipt 接收 evidence_ref 与统计。</p>
 */
@RestController
@RequestMapping("/internal")
public class MediaRecordingController {

    private final MediaRecordingService recordings;

    public MediaRecordingController(MediaRecordingService recordings) {
        this.recordings = recordings;
    }

    @PostMapping("/rooms/{roomId}/recordings")
    public Map<String, Object> start(@PathVariable String roomId,
                                     @RequestBody(required = false) Map<String, Object> body) {
        return recordings.start(roomId, body == null ? java.util.Collections.<String, Object>emptyMap() : body);
    }

    @PostMapping("/recordings/{recordingId}/stop")
    public Map<String, Object> stop(@PathVariable String recordingId) {
        return recordings.stop(recordingId);
    }

    @PostMapping("/recordings/{recordingId}/receipt")
    public Map<String, Object> receipt(@PathVariable String recordingId,
                                       @RequestBody(required = false) Map<String, Object> body) {
        return recordings.receipt(recordingId,
                body == null ? java.util.Collections.<String, Object>emptyMap() : body);
    }

    @GetMapping("/recordings/{recordingId}")
    public Map<String, Object> find(@PathVariable String recordingId) {
        return recordings.find(recordingId);
    }

    @GetMapping("/rooms/{roomId}/recordings")
    public List<Map<String, Object>> list(@PathVariable String roomId) {
        return recordings.listByRoom(roomId);
    }
}

package com.example.bank.business.evidence;

import com.example.bank.business.service.BizRecordService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.*;

/** 分片各自落盘，按编号封存；网络重试不重复追加，缺片时不伪造完整录像。 */
@Service
public class MediaChunkService {
    private final Path directory;
    private final BizRecordService records;
    private final JdbcTemplate jdbc;
    private final InteractionTraceService traces;
    private final Object[] locks = new Object[32];
    public MediaChunkService(@Value("${evidence.records-path:records}") String directory, BizRecordService records,
                            JdbcTemplate jdbc, InteractionTraceService traces) {
        this.directory = Paths.get(directory).toAbsolutePath().normalize(); this.records = records; this.jdbc = jdbc; this.traces = traces;
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }

    public Map<String,Object> accept(String bizNo, String processId, int segment, String handler, String name,
            int duration, String nodes, String recordingId, int index, byte[] bytes, boolean last) throws Exception {
        traces.identifier(bizNo, 64); traces.identifier(recordingId, 100);
        if (index < 0 || index > 100000 || segment < 1 || segment > 1000 || bytes.length > 16 * 1024 * 1024
                || !Arrays.asList("DIGITAL_HUMAN", "HUMAN_AGENT").contains(handler))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的录像分片参数");
        String recording = hash((bizNo + ":" + segment + ":" + handler + ":" + recordingId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        synchronized (locks[(recording.hashCode() & Integer.MAX_VALUE) % locks.length]) {
            Path parts = directory.resolve("parts").resolve(recording); Files.createDirectories(parts);
            Path seal = parts.resolve("sealed.index");
            if (Files.exists(seal)) {
                int sealedIndex = Integer.parseInt(new String(Files.readAllBytes(seal), java.nio.charset.StandardCharsets.UTF_8));
                if (index > sealedIndex || last && index != sealedIndex)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "已封存录像不能追加新分片或改变终片编号");
            }
            Path part = parts.resolve(index + ".part");
            try { Files.write(part, bytes, StandardOpenOption.CREATE_NEW); }
            catch (FileAlreadyExistsException existing) {
                if (!hash(Files.readAllBytes(part)).equals(hash(bytes))) throw new ResponseStatusException(HttpStatus.CONFLICT, "原录像分片不能被不同内容覆盖");
            }
            Map<String,Object> receipt = new LinkedHashMap<>(); receipt.put("success", true); receipt.put("chunkIndex", index); receipt.put("completed", false);
            if (!last) return receipt;
            for (int i = 0; i <= index; i++) if (!Files.exists(parts.resolve(i + ".part")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "录像缺少第 " + i + " 片，补传后才能封存");
            if (!Files.exists(seal)) Files.write(seal, String.valueOf(index).getBytes(java.nio.charset.StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            String fileName = "recording-" + recording + ".webm";
            Path target = directory.resolve(fileName);
            try (OutputStream output = Files.newOutputStream(target)) {
                for (int i = 0; i <= index; i++) Files.copy(parts.resolve(i + ".part"), output);
            }
            long size = Files.size(target);
            if (size == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "录像没有有效内容，不能标记封存成功");
            String fileHash;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = Files.newInputStream(target)) {
                byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            fileHash = hex(digest.digest()); String url = "/records/" + fileName;
            Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM biz_record_segment WHERE file_url=?", Integer.class, url);
            if (existing == null || existing == 0) records.addSegment(bizNo, processId, segment, handler, name, fileName, url, size, duration, fileHash, nodes);
            Map<String,Object> evidence = new LinkedHashMap<>(); evidence.put("fileUrl", url); evidence.put("fileHash", fileHash);
            evidence.put("fileSize", size); evidence.put("segmentIndex", segment); evidence.put("recordingId", recordingId);
            traces.append(bizNo, recordingId + ":sealed", processId, nodes, "SYSTEM", "MEDIA_SEALED", evidence, "");
            receipt.putAll(evidence); receipt.put("completed", true); return receipt;
        }
    }
    private String hash(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private String hex(byte[] bytes) { StringBuilder result = new StringBuilder(); for (byte value : bytes) result.append(String.format("%02x", value)); return result.toString(); }
}

package com.example.bank.business.controller;

import com.example.bank.business.evidence.EvidenceItem;
import com.example.bank.business.evidence.EvidenceRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存证接口面：按业务流水登记图片/文档、追加视频分片、封存与归档。
 *
 * <p>浏览器/坐席端只经 business-svc 访问；证据按 {@code bizNo + subtype + fileIndex} 定位，
 * 列表只返回元数据与相对路径，不暴露服务器绝对路径。</p>
 */
@RestController
@RequestMapping("/api/evidence")
public class EvidenceController {

    private final EvidenceRecordService service;

    public EvidenceController(EvidenceRecordService service) {
        this.service = service;
    }

    /** 登记一张图片/一份文档。 */
    @PostMapping(value = "/flows/{bizNo}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EvidenceItem file(@PathVariable String bizNo,
                             @RequestParam String subtype,
                             @RequestParam("file") MultipartFile file) {
        return service.recordFile(bizNo, subtype, file.getOriginalFilename(), file.getContentType(), bytes(file));
    }

    /** 追加视频分片；不传 evidenceId 时自动创建一条视频证据。 */
    @PostMapping(value = "/flows/{bizNo}/video/segments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EvidenceItem segment(@PathVariable String bizNo,
                                @RequestParam String subtype,
                                @RequestParam int segmentIndex,
                                @RequestParam(required = false) String evidenceId,
                                @RequestParam(defaultValue = "0") long durationMs,
                                @RequestParam("file") MultipartFile file) {
        return service.appendSegment(bizNo, subtype, evidenceId, segmentIndex, durationMs,
                file.getOriginalFilename(), bytes(file));
    }

    /** 封存视频证据（标记分片 SEALED 并汇总大小/哈希）。 */
    @PostMapping("/items/{id}/seal")
    public EvidenceItem seal(@PathVariable String id) {
        return service.seal(id);
    }

    /** 按业务流水查询全部证据（含视频分片）。 */
    @GetMapping("/flows/{bizNo}")
    public Map<String, Object> flow(@PathVariable String bizNo) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("bizNo", bizNo);
        result.put("items", service.getFlow(bizNo));
        return result;
    }

    /** 单条证据详情。 */
    @GetMapping("/items/{id}")
    public EvidenceItem item(@PathVariable String id) {
        return service.getItem(id);
    }

    /** 手动归档/重试归档。 */
    @PostMapping("/items/{id}/archive")
    public EvidenceItem archive(@PathVariable String id) {
        return service.archive(id);
    }

    private byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传文件不能为空");
        }
        try {
            return file.getBytes();
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取上传文件失败", error);
        }
    }
}

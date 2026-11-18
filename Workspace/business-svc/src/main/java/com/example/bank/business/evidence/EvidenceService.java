package com.example.bank.business.evidence;

import com.example.bank.business.service.BizRecordService;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * 将交易完成与证据归档状态分开，缺失分段时保持待补齐。
 *
 * <p>入参是进线方式与是否经人工接待，不是流程状态：调用方（流程引擎服务）
 * 已经在本地短路掉未完成的流程，走到这里的必然是已完成的。
 * "哪些分段是必需的"这条规则连同分段数据都留在本服务，规则和数据分家必然漂移。</p>
 */
@Service
public class EvidenceService {
    private final BizRecordService records;
    public EvidenceService(BizRecordService records) { this.records = records; }

    /**
     * @param entryType 进线方式，{@code AGENT} 表示人工协同
     * @param needHuman 是否经过人工接待
     * @return {@code COMPLETED} 或 {@code PENDING}
     */
    public String archiveStatus(String bizNo, String entryType, boolean needHuman) {
        Map<String, Object> detail = records.getRecordDetail(bizNo);
        if (detail == null) return "PENDING";
        Set<Integer> available = new HashSet<>();
        Object segments = detail.get("segments");
        if (segments instanceof List) {
            for (Object segment : (List<?>) segments) {
                Map<?, ?> row = (Map<?, ?>) segment;
                Object index = row.get("segment_index");
                Object size = row.get("file_size");
                if (index instanceof Number && size instanceof Number && ((Number) size).longValue() > 0
                        && row.get("file_hash") != null) available.add(((Number) index).intValue());
            }
        }
        Set<Integer> required = new HashSet<>("AGENT".equals(entryType) ? Collections.singletonList(2)
                : needHuman ? Arrays.asList(1, 2, 3) : Collections.singletonList(1));
        return available.containsAll(required) ? "COMPLETED" : "PENDING";
    }
}

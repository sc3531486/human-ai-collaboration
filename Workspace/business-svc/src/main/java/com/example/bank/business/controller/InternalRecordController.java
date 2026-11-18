package com.example.bank.business.controller;

import com.example.bank.business.evidence.EvidenceService;
import com.example.bank.business.service.BizRecordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务登记的入站接口面：只供流程引擎服务调用，不经浏览器直连。
 *
 * <p><b>为什么这些写入口必须跨服务</b>：分库之后 {@code biz_record} 落在业务库，
 * 流程引擎服务的委托节点再也读不到它。拆分前这些写入与流程推进处于同一个本地事务，
 * 现在没有了 —— 这个已知缺口记在流程引擎服务的 {@code RecordPort} 上，
 * 本类只负责把每一笔登记如实落库，不做补偿。</p>
 *
 * <p>参数用 {@code Map} 接收而不是定义 DTO：这三个是动作型接口，参数扁平且只写不读。
 * 为序列化一次定义一个类，字段名反而散落到两个服务里更难核对 ——
 * 这里的请求体字面量就是契约，与 CONTRACTS.md 的登记表逐条对应。</p>
 */
@RestController
@RequestMapping("/internal/record")
public class InternalRecordController {

    private final BizRecordService records;
    private final EvidenceService evidence;

    public InternalRecordController(BizRecordService records, EvidenceService evidence) {
        this.records = records;
        this.evidence = evidence;
    }

    /**
     * 建业务记录（转账场景在人工提交转账要素时调用）。
     *
     * <p>按业务号幂等：同一笔业务重复受理只会更新，不会插出第二条底账。</p>
     */
    @PostMapping
    public void create(@RequestBody Map<String, Object> body) {
        records.createRecord(text(body.get("bizNo")), text(body.get("processInstanceId")),
                text(body.get("customerName")), text(body.get("certNoMasked")),
                text(body.get("bizType")), number(body.get("amount")),
                text(body.get("payee")), optional(body.get("handlerMode")));
    }

    /** 更新业务办理状态。{@code handlerMode} 与 {@code agentId} 为空时不覆盖既有值。 */
    @PostMapping("/status")
    public void status(@RequestBody Map<String, Object> body) {
        records.updateStatus(text(body.get("bizNo")), text(body.get("status")),
                optional(body.get("handlerMode")), optional(body.get("agentId")));
    }

    /**
     * 存证归档状态：已完成流程的双录分段是否齐备。
     *
     * <p>取值域 {@code COMPLETED} / {@code PENDING}。未完成流程的 {@code COLLECTING}
     * 由调用方在本地短路得出，不走这里 —— 那条路径处在 700ms 一轮的轮询热路径上。</p>
     */
    @GetMapping("/archive-status")
    public Map<String, Object> archiveStatus(@RequestParam String bizNo,
                                             @RequestParam(required = false) String entryType,
                                             @RequestParam(defaultValue = "false") boolean needHuman) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("bizNo", bizNo);
        result.put("archiveStatus", evidence.archiveStatus(bizNo, entryType, needHuman));
        return result;
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    /** 可空文本：写入语义里"不提供"与"提供空串"不是一回事，null 必须保持为 null。 */
    private String optional(Object value) { return value == null ? null : String.valueOf(value).trim(); }

    private Double number(Object value) { return value instanceof Number ? ((Number) value).doubleValue() : null; }
}

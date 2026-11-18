package com.example.bank.business.application;

import com.example.bank.business.contract.QueueTicket;
import com.example.bank.business.port.MediaPort;
import com.example.bank.business.port.RoutingPort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 接待编排：业务服务统一协调排队与媒体资源。
 *
 * <p>workflow-svc 只负责执行 Flowable 节点并调用本入口；routing-svc 和 media-svc
 * 不再成为流程引擎的直接依赖。排队算法和房间实现仍分别归属各自服务。</p>
 */
@Service
public class ReceptionOrchestrationService {

    private final RoutingPort routing;
    private final MediaPort media;

    public ReceptionOrchestrationService(RoutingPort routing, MediaPort media) {
        this.routing = routing;
        this.media = media;
    }

    /** 执行一次接待路由；{@code closeRoom} 仅用于交还数字人后的媒体收尾。 */
    public Map<String, Object> route(Map<String, Object> request, boolean closeRoom) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少接待请求");
        }
        String processId = text(request.get("processId"));
        if (processId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接待请求缺少 processId");
        }

        QueueTicket ticket = routing.route(routeRequestWithBizKey(request));
        if (closeRoom) {
            media.closeRoom(processId);
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", true);
        result.put("accepted", true);
        result.put("processId", processId);
        result.put("ticket", ticket);
        result.put("matching", "ASYNC");
        result.put("roomClosed", closeRoom);
        return result;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 产品只认 biz_key/biz_date：business 把 biz_no 翻译成产品字段，产品不解释业务含义。 */
    private Map<String, Object> routeRequestWithBizKey(Map<String, Object> request) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>(request);
        String bizNo = text(request.get("bizNo"));
        if (!bizNo.isEmpty() && !copy.containsKey("bizKey")) copy.put("bizKey", bizNo);
        if (!copy.containsKey("bizDate")) copy.put("bizDate", LocalDate.now().toString());
        return copy;
    }
}

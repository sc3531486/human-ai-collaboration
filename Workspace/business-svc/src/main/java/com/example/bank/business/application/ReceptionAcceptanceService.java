package com.example.bank.business.application;

import com.example.bank.business.port.MediaPort;
import com.example.bank.business.port.ProcessPort;
import com.example.bank.business.port.SessionPort;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

/** 工单已由路由校验归属后，业务中枢统一组织认领、建房与入房邀请。 */
@Service
public class ReceptionAcceptanceService {
    private final SessionPort sessions;
    private final ProcessPort process;
    private final MediaPort media;
    private final CallSessionService calls;
    public ReceptionAcceptanceService(SessionPort sessions, ProcessPort process, MediaPort media,CallSessionService calls) {
        this.sessions = sessions; this.process = process; this.media = media;this.calls=calls;
    }
    public Map<String, Object> accept(String processId, String agentId, String peerId) {
        if (peerId == null || peerId.trim().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少媒体用户 peerId");
        sessions.claim(processId, agentId, peerId);
        try {
            Map<String, Object> context = new java.util.LinkedHashMap<>(process.variables(processId));
            Object bizNo = context.get("biz_no");
            if (bizNo != null && !String.valueOf(bizNo).trim().isEmpty()) {
                // 产品只认 biz_key/biz_date，不解释业务含义
                context.putIfAbsent("biz_key", String.valueOf(bizNo).trim());
                context.putIfAbsent("biz_date", java.time.LocalDate.now().toString());
            }
            if(context.get("parent_process_id")!=null&&!String.valueOf(context.get("parent_process_id")).isEmpty()) {
                return calls.joinAuthorization(String.valueOf(context.get("parent_process_id")),processId,agentId,peerId);
            }
            Map<String, Object> room = media.openRoom(processId, context);
            if (room == null || room.get("roomId") == null || String.valueOf(room.get("roomId")).trim().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "媒体服务未返回 roomId");
            }
            Map<String,Object> mediaState = new java.util.LinkedHashMap<>();
            mediaState.put("video_room_id",room.get("roomId"));mediaState.put("video_room_state","OPEN");
            process.setVariables(processId,mediaState);
            Map<String,Object> call = calls.register(processId,context,String.valueOf(room.get("roomId")));
            context.putAll(mediaState);
            context.put("call_id",call.get("callId"));context.put("call_participants",call.get("participants"));
            media.inviteRoom(processId, context);
            return room;
        } catch(RuntimeException error) {
            try { calls.abandon(processId); } catch(RuntimeException cleanup) { error.addSuppressed(cleanup); }
            // 异常兜底只清理已申请的资源，不能换一条业务路径继续接听。
            try { media.closeRoom(processId); } catch(RuntimeException cleanup) { error.addSuppressed(cleanup); }
            try { sessions.releaseClaim(processId,agentId); } catch(RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
}

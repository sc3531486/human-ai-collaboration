package com.example.bank.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多媒体服务的对外接口。
 *
 * <p>前缀统一为 {@code /internal}：这些能力由业务中枢编排调用，不经浏览器直连。
 * 浏览器侧的媒体面是 PeerJS P2P 直连，不经过本服务。</p>
 *
 * <p>open、invite、close 分别表示媒体命令；本服务不决定下一业务节点。</p>
 */
@RestController
@RequestMapping("/internal")
public class MediaController {

    private static final Logger log = LoggerFactory.getLogger(MediaController.class);

    private final VideoRoomService rooms;

    public MediaController(VideoRoomService rooms) {
        this.rooms = rooms;
    }

    /**
     * 开房：根据业务传入的参与者上下文分配房间号；业务服务负责保存关联。
     *
     * <p>重复调用返回同一房间号。开房本身不推送任何东西 —— 推送由 {@code invite} 单独触发，
     * 这样调用方可以在开房与通知之间做别的事（例如先确认坐席已进入通话态）。</p>
     */
    @PostMapping("/room/{processId}/open")
    public Map<String, Object> open(@PathVariable String processId, @RequestBody Map<String,Object> context) {
        String roomId = rooms.open(processId, context);
        log.info("开房 processId={} roomId={}", processId, roomId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("roomId", roomId);
        return result;
    }

    /**
     * 推送房间就绪：通知客户与坐席两端加入。
     *
     * <p>信令投递失败明确返回错误；业务编排不能据此报告接听成功。</p>
     *
     * <p>房间号或收件人标识缺失返回 409
     * （{@code ROOM_INVITE_PRECONDITION}），下游不可达返回 503。</p>
     */
    @PostMapping("/room/{processId}/invite")
    public ResponseEntity<Void> invite(@PathVariable String processId, @RequestBody Map<String,Object> context) {
        rooms.inviteParticipants(processId, context);
        return ResponseEntity.ok().build();
    }

    /**
     * 关闭媒体资源命令。业务办结不会自动调用；双方主动挂断由挂断服务通知成员。
     */
    @PostMapping("/room/{processId}/close")
    public ResponseEntity<Void> close(@PathVariable String processId, @RequestBody Map<String,Object> context) {
        rooms.close(processId, context);
        log.info("关房 processId={}", processId);
        return ResponseEntity.ok().build();
    }

    /**
     * 观测入口：标明媒体传输由浏览器 P2P 承担。
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("service", "media-svc");
        result.put("mediaPlane", "browser-p2p（本服务不含媒体转发）");
        return result;
    }
}

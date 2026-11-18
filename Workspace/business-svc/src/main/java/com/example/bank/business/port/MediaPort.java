package com.example.bank.business.port;

import java.util.Map;

/**
 * 出站端口：媒体房间生命周期。
 *
 * <p>媒体房间属于业务接待编排的一部分，但房间和 RTC 细节属于 media-svc。
 * business-svc 只发送业务动作，不持有媒体状态。</p>
 */
public interface MediaPort {

    /** 开启业务会话房间并返回房间信息；context 来自 business-svc 的流程快照。 */
    Map<String, Object> openRoom(String processId, Map<String, Object> context);

    /** 通知客户和坐席加入已开启的房间；context 用于刷新 media-svc 的通知上下文。 */
    void inviteRoom(String processId, Map<String, Object> context);

    /** 关闭一条业务会话对应的视频房间，重复调用无副作用。 */
    void closeRoom(String processId);
    /** 结束整通视频及下发挂断通知，由媒体服务执行，业务服务只组织命令。 */
    void hangupRoom(String roomId, Map<String,Object> context);
    void updateParticipants(String roomId, Map<String,Object> context);
}

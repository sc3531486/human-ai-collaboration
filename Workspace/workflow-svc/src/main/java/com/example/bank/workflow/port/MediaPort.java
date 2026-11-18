package com.example.bank.workflow.port;

/**
 * 多媒体端口 —— 由<b>本服务</b>定义，media-svc 实现。
 *
 * <p>本服务只知道"这通会话结束了，房间该关"，房间怎么分配、用哪家 RTC 都是 media-svc 的实现细节。
 * 注意 media-svc <b>不含任何媒体转发能力</b>，媒体面是浏览器里的 P2P，这里管的只是房间身份。</p>
 */
public interface MediaPort {

    /**
     * 关闭该流程的视频房间。
     *
     * <p>失败只记日志、不抛出：它是交还数字人时的收尾动作，房间没关掉不影响流程继续走完，
     * 而抛出会让一次收尾失败回滚掉整笔已经完成的交还。</p>
     */
    void closeRoom(String processId);
}

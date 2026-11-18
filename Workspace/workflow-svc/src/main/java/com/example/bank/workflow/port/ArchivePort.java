package com.example.bank.workflow.port;

/**
 * 存证归档端口 —— 由<b>本服务</b>定义，business-svc 实现。
 *
 * <h3>为什么短路放在调用方</h3>
 *
 * <p>归档状态只在流程<b>已完成</b>时才有非平凡取值，未完成的一律是 {@code COLLECTING}。
 * 而本服务的快照会被引擎事件与归档等待队列反复取用，若把这个判断交给对端，
 * 每一次快照都要发一次 HTTP —— 把原本零开销的本地短路变成热点。</p>
 *
 * <p>因此调用方本地判 {@code COMPLETED}，只有已完成的流程才跨服务查一次；
 * 而"哪些双录分段是必需的"这条规则连同分段数据一起留在 business-svc，
 * 不在这里复制一份 —— 规则和数据分家必然漂移。</p>
 */
public interface ArchivePort {

    /**
     * 查询已完成流程的存证归档状态。
     *
     * @param entryType 进线方式，{@code AGENT} 表示人工协同（只需第 2 段双录）
     * @param needHuman 是否经过人工接待
     * @return {@code COMPLETED} 或 {@code PENDING}；取值域与 CONTRACTS.md 登记的一致
     * @throws ArchiveUnavailableException 下游不可达或返回 5xx（可重试，与"流程不存在"区分）
     */
    String archiveStatus(String bizNo, String entryType, boolean needHuman);
}

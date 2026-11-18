package com.example.bank.workflow.port;

/**
 * 存证归档查询的下游故障。
 *
 * <p>存在的唯一理由是<b>把两类失败分开</b>：轮询循环遇到"流程实例不存在"应当停止跟踪该流程，
 * 遇到"下游暂时不可达"则应当保留跟踪、下个节拍重试。若两者混成同一个异常，
 * 业务服务抖动几秒就会把所有正在跟踪的流程永久踢出列表 —— 节点状态再也不更新，
 * 且没有任何提示。</p>
 */
public class ArchiveUnavailableException extends RuntimeException {

    public ArchiveUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

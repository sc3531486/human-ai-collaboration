package com.example.bank.signaling.centrifugo;

/**
 * presence 查询失败：请求未成功（网络、超时、非 2xx）或 Centrifugo 响应结构异常。
 *
 * <p>它与 {@code 0}（频道内确实无连接）是两件不同的事，必须严格区分：
 * 只有拿到数值 {@code 0} 才能判定"连接消失"。若把查询失败吞成 0，
 * Centrifugo 抖动一次就会把全量在线坐席打成离线（误杀防护，
 * 见 {@link CentrifugoClient#presenceCount(String)} 与 {@code PresenceSweeper} 的扫描判定）。</p>
 *
 * <p>本异常只允许在信令服务内部传递：由 {@code PresenceSweeper} 按扫描语义处置
 * （失败的那一轮不判定该坐席的在线态），不跨服务、不进 HTTP 响应。</p>
 */
public class CentrifugoPresenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CentrifugoPresenceException(String message) {
        super(message);
    }

    public CentrifugoPresenceException(String message, Throwable cause) {
        super(message, cause);
    }
}

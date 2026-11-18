package com.example.bank.routing.persistence;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Date;

/**
 * JDBC 时间列读取的统一折算：MySQL Connector/J 8.x 下 DATETIME 读回
 * {@link LocalDateTime}，TIMESTAMP 读回 {@link Timestamp}，两种都折算成毫秒。
 */
public final class JdbcTimes {
    private JdbcTimes() { }

    /** 折算为毫秒；类型不识别即报错，不静默归零掩盖数据问题。 */
    public static long millis(Object value) {
        if (value instanceof LocalDateTime) {
            return Timestamp.valueOf((LocalDateTime) value).getTime();
        }
        if (value instanceof Date) {
            return ((Date) value).getTime();
        }
        throw new IllegalArgumentException("时间列类型不支持：" + (value == null ? "null" : value.getClass().getName()));
    }
}

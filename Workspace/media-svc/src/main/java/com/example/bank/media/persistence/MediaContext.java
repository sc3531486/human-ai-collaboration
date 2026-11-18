package com.example.bank.media.persistence;

import com.example.bank.media.MediaServiceException;
import org.springframework.http.HttpStatus;

import java.sql.Date;
import java.util.Map;

/**
 * media 产品的通用上下文取值：只接受 {@code biz_key/biz_date} 这类不透明字段，
 * 不解释业务含义；未提供就返回 null。
 */
public final class MediaContext {

    private MediaContext() {
    }

    public static String text(Map<String, Object> context, String key) {
        if (context == null) return null;
        Object value = context.get(key);
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public static String firstText(Map<String, Object> context, String... keys) {
        if (keys == null) return null;
        for (String key : keys) {
            String value = text(context, key);
            if (value != null) return value;
        }
        return null;
    }

    public static String bizKey(Map<String, Object> context) {
        return firstText(context, "biz_key", "bizKey");
    }

    public static Date bizDate(Map<String, Object> context) {
        String value = firstText(context, "biz_date", "bizDate");
        if (value == null) return null;
        try {
            return Date.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new MediaServiceException(HttpStatus.BAD_REQUEST, "MEDIA_CONTEXT",
                    "biz_date 格式应为 yyyy-MM-dd：" + value);
        }
    }

    public static Integer integer(Map<String, Object> context, String key) {
        String value = text(context, key);
        if (value == null) return null;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException error) {
            throw new MediaServiceException(HttpStatus.BAD_REQUEST, "MEDIA_CONTEXT",
                    key + " 必须是整数：" + value);
        }
    }

    public static Long longValue(Map<String, Object> context, String key) {
        String value = text(context, key);
        if (value == null) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException error) {
            throw new MediaServiceException(HttpStatus.BAD_REQUEST, "MEDIA_CONTEXT",
                    key + " 必须是整数：" + value);
        }
    }
}

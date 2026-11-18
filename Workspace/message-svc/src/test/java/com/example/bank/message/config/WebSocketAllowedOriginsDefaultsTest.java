package com.example.bank.message.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STOMP 允许来源的配置回归。
 *
 * <p>真实故障：客户侧 SDK 在 {@code https://localhost:5175}（enterprise-demo 渠道端），
 * 而 {@code message.allowed-origin-patterns} 只配了 {@code http://localhost:5173}
 * —— 那是已被删除的 navis-web 旧端口。结果是真实客户来源的 {@code /ws} 握手
 * 一律 403，SDK 静默降级为 3s 轮询；坐席授权后推进到客户签名节点时，
 * 客户侧收不到实时推送，表现为「签名弹框不出现」。</p>
 *
 * <p>本测试直接读 {@code application.yml} 的默认值，把「配置必须覆盖真实前端来源」
 * 钉住 —— 只测解析函数会让这条配置漂移无人察觉。</p>
 */
class WebSocketAllowedOriginsDefaultsTest {

    /** 读取打包资源里的 application.yml 默认 allowed-origin-patterns 值。 */
    private static String configuredDefault() throws IOException {
        try (InputStream in = WebSocketAllowedOriginsDefaultsTest.class
                .getResourceAsStream("/application.yml")) {
            if (in == null) throw new IllegalStateException("找不到 application.yml");
            // Java 8 目标：InputStream.readAllBytes() 是 Java 9+ API，此处手工读取。
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
            String yml = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            for (String raw : yml.split("\\R")) {
                String line = raw.trim();
                if (line.startsWith("allowed-origin-patterns:")) {
                    String value = line.substring("allowed-origin-patterns:".length()).trim();
                    // 形如 ${MESSAGE_ALLOWED_ORIGINS:默认值} —— 取默认值部分
                    int colon = value.indexOf(':');
                    if (value.startsWith("${") && value.endsWith("}") && colon > 0) {
                        return value.substring(colon + 1, value.length() - 1);
                    }
                    return value;
                }
            }
            throw new IllegalStateException("application.yml 未配置 allowed-origin-patterns");
        }
    }

    @Test
    void defaultOriginsCoverTheRealCustomerChannelOrigin() throws IOException {
        String[] origins = WebSocketConfig.origins(configuredDefault());

        assertTrue(
                Arrays.asList(origins).contains("https://localhost:5175"),
                "客户侧 SDK 实际跑在 https://localhost:5175（enterprise-demo）；"
                + "缺这条来源会让 /ws 握手 403 并静默降级为轮询，当前配置=" + Arrays.toString(origins));
    }

    @Test
    void defaultOriginsAreStillExplicitlyEnumerated() throws IOException {
        String[] origins = WebSocketConfig.origins(configuredDefault());

        // 不允许用 "*" 兜底（WebSocketConfig 已 fail-fast 拒绝空配置，这里再钉住通配符）
        assertArrayEquals(
                Arrays.stream(origins).filter(o -> !"*".equals(o)).toArray(String[]::new),
                origins,
                "允许来源必须逐条枚举，不得使用通配符 *");
        assertTrue(origins.length >= 1, "至少要有一条允许来源");
    }

    @Test
    void blankConfigurationStillFailsFast() {
        assertThrows(IllegalStateException.class, () -> WebSocketConfig.origins(" "));
    }
}

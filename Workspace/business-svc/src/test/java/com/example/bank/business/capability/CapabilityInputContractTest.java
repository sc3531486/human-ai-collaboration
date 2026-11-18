package com.example.bank.business.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 能力目录与 BPMN 输入映射的一致性回归。
 *
 * <p>真实故障：{@code notification.send.v1} 的 BPMN 只映射了 {@code mobile}，
 * 而短信网关模板需要 {@code amount} 才能渲染「向 X 转账 ¥Y 元」。结果客户签名核实通过后，
 * 流程走到「结果通知」被网关以 400「缺少金额 amount」拒绝，整笔业务在最后一步
 * 回滚 —— 签字单与核心转账都已经发生，业务却无法办结。</p>
 *
 * <p><b>校验口径</b>：{@code bizNo / processId / nodeName} 由
 * {@code ApiTaskDelegate#executePhase} 统一附加到每次能力请求上，不需要 BPMN 声明；
 * 其余 required 字段都必须在 BPMN 的 {@code inputs} 里显式映射。
 * 若把自动注入字段也算作"必须在 BPMN 映射"，测试会误报，所以这里显式排除。</p>
 */
class CapabilityInputContractTest {

    /** 由 ApiTaskDelegate 自动附加、无需 BPMN 声明的字段。 */
    private static final Set<String> AUTO_INJECTED = new LinkedHashSet<>(
            Arrays.asList("bizNo", "processId", "nodeName"));

    /** 该能力在 BPMN 里必须显式映射的字段（排除自动注入项）。 */
    private static List<String> mustBeMapped(String capability) throws IOException {
        List<String> result = new ArrayList<>();
        for (String field : declaredRequired().getOrDefault(capability, Collections.<String>emptyList())) {
            if (!AUTO_INJECTED.contains(field)) result.add(field);
        }
        return result;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 从模块工作目录向上找到工作区根（含 workflow-svc 与 business-svc 的那一层）。 */
    private static Path workspaceRoot() {
        Path probe = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && probe != null; i++, probe = probe.getParent()) {
            if (Files.isDirectory(probe.resolve("workflow-svc"))
                    && Files.isDirectory(probe.resolve("business-svc"))) {
                return probe;
            }
        }
        throw new IllegalStateException("找不到工作区根（应同时含 workflow-svc 与 business-svc）："
                + Paths.get("").toAbsolutePath());
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** 能力目录声明的 required 字段。 */
    private static Map<String, List<String>> declaredRequired() throws IOException {
        try (InputStream in = CapabilityInputContractTest.class.getResourceAsStream("/capabilities.json")) {
            assertNotNull(in, "找不到 capabilities.json");
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) buf.write(chunk, 0, read);
            Map<String, Object> raw = JSON.readValue(new String(buf.toByteArray(), StandardCharsets.UTF_8), Map.class);
            Map<String, List<String>> result = new LinkedHashMap<>();
            raw.forEach((code, value) -> {
                Object required = ((Map<?, ?>) value).get("required");
                @SuppressWarnings("unchecked")
                List<String> list = required instanceof List
                        ? new ArrayList<>((List<String>) required) : Collections.<String>emptyList();
                result.put(String.valueOf(code), list);
            });
            return result;
        }
    }

    /** 工作区里的全部流程定义。 */
    private static List<Path> processDefinitions() throws IOException {
        Path dir = workspaceRoot().resolve("workflow-svc/src/main/resources/processes");
        assertTrue(Files.isDirectory(dir), "找不到流程定义目录：" + dir);
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".bpmn20.xml")).sorted().forEach(files::add);
        }
        assertFalse(files.isEmpty(), "流程定义目录为空：" + dir);
        return files;
    }

    /** 抽取 code="..." inputs="a=b,c=d" 的能力声明。 */
    private static List<String[]> capabilityInputs(String xml) {
        List<String[]> found = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("code=\"([^\"]+)\"\\s+inputs=\"([^\"]*)\"").matcher(xml);
        while (m.find()) found.add(new String[]{m.group(1), m.group(2)});
        return found;
    }

    /** inputs 形如 amount=amount,payeeName=payee：取左侧（能力侧字段名）。 */
    private static Set<String> capabilitySideFields(String inputs) {
        Set<String> names = new LinkedHashSet<>();
        for (String mapping : inputs.split(",")) {
            if (mapping.trim().isEmpty()) continue;
            String[] parts = mapping.split("=", 2);
            if (parts.length == 2 && !parts[0].trim().isEmpty()) names.add(parts[0].trim());
        }
        return names;
    }

    @Test
    void notificationCapabilityDeclaresEveryFieldItsGatewayRequires() throws IOException {
        List<String> required = declaredRequired().get("notification.send.v1");
        assertNotNull(required, "能力目录缺少 notification.send.v1");

        assertTrue(required.contains("amount"),
                "notification.send.v1 必须声明 amount：短信模板需要它渲染金额，"
                        + "缺失会让流程在最后一步被网关 400 拒绝并整体回滚");
        assertTrue(required.contains("payeeName"),
                "notification.send.v1 必须声明 payeeName：短信模板需要它渲染收款人");
    }

    @Test
    void bpmnNotifyNodeMapsEveryRequiredFieldOfNotificationCapability() throws IOException {
        List<String> needed = mustBeMapped("notification.send.v1");
        assertFalse(needed.isEmpty(),
                "能力目录未声明 notification.send.v1 的必需字段（除自动注入项外）");

        boolean found = false;
        for (Path file : processDefinitions()) {
            for (String[] capability : capabilityInputs(read(file))) {
                if (!"notification.send.v1".equals(capability[0])) continue;
                found = true;
                Set<String> mapped = capabilitySideFields(capability[1]);
                List<String> missing = new ArrayList<>();
                for (String field : needed) if (!mapped.contains(field)) missing.add(field);
                assertTrue(missing.isEmpty(),
                        file.getFileName() + " 的结果通知节点缺必需输入 " + missing
                                + "（当前 inputs=" + capability[1] + "）；"
                                + "缺字段会让流程走到该节点才被网关拒绝，整笔业务回滚。"
                                + "真实事故：缺 amount 导致短信网关 400「缺少金额 amount」");
            }
        }
        assertTrue(found, "没有任何流程定义使用 notification.send.v1，测试已失去意义");
    }

    /**
     * 未被任何流程使用的注册能力允许存在（如仅由 password 流程之外调用），
     * 但反过来——流程用了目录里没有的能力——一定是配置漂移。
     */
    @Test
    void everyBpmnCapabilityIsRegisteredInTheDirectory() throws IOException {
        Set<String> registered = declaredRequired().keySet();
        List<String> unknown = new ArrayList<>();
        for (Path file : processDefinitions()) {
            for (String[] capability : capabilityInputs(read(file))) {
                if (!registered.contains(capability[0])) {
                    unknown.add(file.getFileName() + " → " + capability[0]);
                }
            }
        }
        assertTrue(unknown.isEmpty(),
                "BPMN 使用了未在 capabilities.json 注册的能力：" + unknown
                        + "；未注册的能力在运行期会被 CapabilityService 以 404「能力未注册」拒绝");
    }

    @Test
    void bpmnInputsCoverRequiredFieldsForEveryRegisteredCapability() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path file : processDefinitions()) {
            for (String[] capability : capabilityInputs(read(file))) {
                Set<String> mapped = capabilitySideFields(capability[1]);
                for (String field : mustBeMapped(capability[0])) {
                    if (!mapped.contains(field)) {
                        problems.add(file.getFileName() + " 的 " + capability[0] + " 缺必需字段 " + field);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(),
                "BPMN 能力输入映射与能力目录不一致：" + problems
                        + "；这类缺失只在流程走到该节点时才暴露，且会导致整笔业务回滚");
    }
}

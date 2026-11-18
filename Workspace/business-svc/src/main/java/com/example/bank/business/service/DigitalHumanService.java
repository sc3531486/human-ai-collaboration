package com.example.bank.business.service;

import com.example.bank.business.config.DigitalHumanProperties;
import com.example.bank.business.port.ProcessPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数字人意图识别与槽位提取服务。
 *
 * <p>单一职责：调用大模型解析用户输入，返回意图标识、动作指令与表单槽位。
 * 不参与流程编排——启动流程、推进节点、创建业务存证等由调用方（BalanceController）承担。</p>
 *
 * <p>返回契约中的关键字段：</p>
 * <ul>
 *   <li>{@code action} — 需要控制器执行的流程操作（SUBMIT / TRIGGER），null 表示无需流程交互</li>
 *   <li>{@code processKey} — 启动新流程时使用的流程定义 key</li>
 *   <li>{@code nodeId} — 目标节点（可选，null = 自动检测当前活动节点）</li>
 *   <li>{@code slots} — 提交到流程的表单数据</li>
 *   <li>{@code startVars} — 启动新流程时的初始变量</li>
 *   <li>{@code reply} — 数字人对用户的回复文本</li>
 * </ul>
 */
@Service
public class DigitalHumanService {

    private static final String DEFAULT_SERVICE_MODE = "DIGITAL";

    @Autowired
    private DigitalHumanProperties properties;

    @Autowired
    private BankingKnowledgeService knowledgeService;

    @Autowired
    private ProcessPort process;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取前端数字人初始化配置
     */
    public Map<String, Object> getDigitalHumanConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("profile", properties.getProfile());
        config.put("rules", properties.getRules());
        config.put("knowledge", knowledgeService.getAllKnowledge());
        Map<String, Object> llmInfo = new HashMap<>();
        llmInfo.put("model", properties.getLlm().getModel());
        llmInfo.put("provider", "MiMo");
        config.put("llm", llmInfo);
        return config;
    }

    /**
     * 处理数字人多轮对话与意图识别。
     *
     * <p>返回结构包含流程操作指令（action/processKey/nodeId/slots/startVars），
     * 由调用方负责执行对应的流程编排。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired private TransferDraftService transferDrafts;
    public Map<String,Object> processChat(Map<String,Object> payload){
        String text = payload.get("text") == null ? "" : String.valueOf(payload.get("text")).trim();
        // 快问快答：密码重置是规格指定的确定性入口（PASSWORD_RESET_GUIDE.md「场景流程」），
        // 不经模型推理直接进入完整场景，模型故障也不影响这条入口。
        if (isPasswordResetQuickQuestion(text)) return passwordResetScene(text, payload);
        return transferDrafts.complete(payload, parseChat(payload));
    }

    /** 密码重置快捷问法：「重置/忘记 + 密码」。 */
    private boolean isPasswordResetQuickQuestion(String text) {
        return (text.contains("重置") && text.contains("密码")) || text.contains("忘记密码");
    }

    /** 进入 password_reset_sop 完整场景的启动回执；演示客户固定 DEMO-001（PASSWORD_RESET_GUIDE.md）。 */
    private Map<String,Object> passwordResetScene(String text, Map<String,Object> payload) {
        Object incoming = payload.get("bizNo");
        String bizNo = incoming == null || String.valueOf(incoming).trim().isEmpty()
                ? "BIZ" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + (int)(100 + Math.random() * 900)
                : String.valueOf(incoming).trim();
        Map<String,Object> response = new HashMap<>();
        Map<String,Object> startVars = new LinkedHashMap<>();
        startVars.put("biz_no", bizNo);
        startVars.put("user_speech", text);
        startVars.put("customer_id", "DEMO-001");
        response.put("success", true);
        response.put("action", "TRIGGER");
        response.put("intent", "PASSWORD_RESET");
        response.put("processKey", "password_reset_sop");
        response.put("nodeId", null);
        response.put("slots", Collections.emptyMap());
        response.put("startVars", startVars);
        response.put("bizNo", bizNo);
        response.put("processId", "");
        response.put("reply", "好的，已为您进入银行卡密码重置流程，请按提示选择卡片并完成身份核验。");
        return response;
    }
    private Map<String, Object> parseChat(Map<String, Object> payload) {
        String userText = (String) payload.get("text");
        String incomingBizNo = (String) payload.get("bizNo");
        String incomingProcId = (String) payload.get("processId");
        String requestedModel = payload.get("model") != null ? String.valueOf(payload.get("model")).trim() : "";
        String model = requestedModel.isEmpty() ? properties.getLlm().getModel() : requestedModel;
        List<Map<String, String>> history = (List<Map<String, String>>) payload.get("history");
        Map<String, Object> response = new HashMap<>();

        if (userText == null || userText.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户输入内容不能为空");
        }

        String currentBizNo;
        if (incomingBizNo != null && !incomingBizNo.trim().isEmpty()) {
            currentBizNo = incomingBizNo.trim();
        } else {
            currentBizNo = "BIZ" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + (int)(100 + Math.random() * 900);
        }

        try {
            String systemPrompt = buildSystemPrompt(userText);
            JsonNode resultJson = callLlm(systemPrompt, userText, history, model);

            String intent = resultJson.has("intent") ? resultJson.get("intent").asText().toUpperCase() : "QA";
            String reply = resultJson.has("reply") ? resultJson.get("reply").asText() : "";
            boolean isModification = resultJson.has("isModification") && resultJson.get("isModification").asBoolean();
            boolean slotsComplete = resultJson.has("slotsComplete") && resultJson.get("slotsComplete").asBoolean();
            long amount = resultJson.has("amount") ? resultJson.get("amount").asLong() : 0;
            String payee = resultJson.has("payee") ? resultJson.get("payee").asText() : "未知";

            if (amount > 0 && payee != null && !payee.trim().isEmpty() && !"未知".equals(payee.trim())) {
                slotsComplete = true;
            }

            if ("TRANSFER".equalsIgnoreCase(intent)) {
                if (!slotsComplete && amount <= 0 && ("未知".equals(payee) || payee.trim().isEmpty())) {
                    response.put("success", true);
                    response.put("action", "slot_filling");
                    response.put("intent", "TRANSFER");
                    response.put("amount", amount);
                    response.put("payee", payee);
                    response.put("bizNo", currentBizNo);
                    response.put("processId", incomingProcId != null ? incomingProcId : "");
                    response.put("slotsComplete", false);
                    response.put("reply", reply.isEmpty() ? "请问您要转给哪位收款人，转账金额是多少呢？" : reply);
                    return response;
                }

                Map<String, Object> slots = new LinkedHashMap<>();
                slots.put("payee", payee);
                slots.put("amount", amount);

                boolean isNewProcess = incomingProcId == null || incomingProcId.trim().isEmpty() || !process.isRunning(incomingProcId);
                if (!isNewProcess && amount == 0) {
                    // 上游流程状态读取失败如实上抛（下游失败语义按 CONTRACTS 透传），不再静默按 0 处理
                    Object prev = process.variables(incomingProcId).get("amount");
                    if (prev instanceof Number) amount = ((Number) prev).longValue();
                    slots.put("amount", amount);
                }

                if (isNewProcess) {
                    Map<String, Object> startVars = new LinkedHashMap<>();
                    String safeUserText = userText != null ? userText.replaceAll("[\\x{10000}-\\x{10FFFF}]", "") : "";
                    startVars.put("biz_no", currentBizNo);
                    startVars.put("amount", amount);
                    startVars.put("payee", payee);
                    startVars.put("user_speech", safeUserText);
                    startVars.put("customer_name", "测试用户");
                    startVars.put("gender", "男");
                    startVars.put("cust_id", "199088231");
                    startVars.put("cert_type", "第二代居民身份证");
                    startVars.put("cert_no_masked", "610923199710******");
                    startVars.put("cert_validity", "2020.01.01-2030.01.01");
                    startVars.put("phone_masked", "1300******8");
                    startVars.put("card_no_masked", "6222 02** **** 8888");
                    startVars.put("cust_level", "钻石财富");
                    startVars.put("branch_name", "高新科技支行");
                    startVars.put("device_id", "20178114");
                    startVars.put("teller_id", "781448");
                    startVars.put("model_used", properties.getLlm().getModel());
                    startVars.put("channel", "智能柜台 (HTTPS P2P专线)");
                    startVars.put("ai_collect_time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
                    startVars.put("service_mode", DEFAULT_SERVICE_MODE);
                    response.put("processKey", "advanced_sop");
                    response.put("startVars", startVars);
                }

                response.put("success", true);
                response.put("action", "SUBMIT");
                response.put("intent", "TRANSFER");
                response.put("nodeId", null);
                response.put("slots", slots);
                response.put("amount", amount);
                response.put("payee", payee);
                response.put("processId", isNewProcess ? "" : incomingProcId);
                response.put("bizNo", currentBizNo);
                response.put("isModification", isModification);
                response.put("slotsComplete", slotsComplete);
                response.put("reply", reply.isEmpty() ? String.format("好的，已为您准备转账信息：收款人【%s】，金额【¥%d元】，请您核对确认。", payee, amount) : reply);

            } else if ("BALANCE".equalsIgnoreCase(intent)) {
                Map<String, Object> startVars = new LinkedHashMap<>();
                startVars.put("biz_no", currentBizNo);
                startVars.put("user_speech", userText);
                startVars.put("customer", "洗荣池");
                startVars.put("authPassed", true);

                response.put("success", true);
                response.put("action", "TRIGGER");
                response.put("intent", "BALANCE");
                response.put("processKey", "balance_sop");
                response.put("nodeId", null);
                response.put("slots", Collections.emptyMap());
                response.put("startVars", startVars);
                response.put("bizNo", currentBizNo);
                response.put("processId", "");
                response.put("reply", "正在为您连接核心系统查询账户实时余额，请稍候...");

            } else if ("TRANSFER_HUMAN".equalsIgnoreCase(intent)) {
                String result = resolveTransferToHuman(currentBizNo, incomingProcId);
                if (result == null) {
                    // 已过 ai_collect 节点，无需操作
                    response.put("success", true);
                    response.put("intent", "TRANSFER_HUMAN");
                    response.put("bizNo", currentBizNo);
                    response.put("processId", incomingProcId != null ? incomingProcId : "");
                    response.put("reply", reply.isEmpty() ? "好的，正在为您转接人工座席，请稍候..." : reply);
                } else if (result.startsWith("submit:")) {
                    // 已有流程，需要提交到指定节点
                    response.put("success", true);
                    response.put("action", "SUBMIT");
                    response.put("intent", "TRANSFER_HUMAN");
                    response.put("nodeId", result.substring(7));
                    Map<String, Object> slots = new LinkedHashMap<>();
                    slots.put("transfer_requested", true);
                    response.put("slots", slots);
                    response.put("bizNo", currentBizNo);
                    response.put("processId", incomingProcId);
                    response.put("reply", reply.isEmpty() ? "好的，正在为您转接人工座席，请稍候..." : reply);
                } else {
                    // 需要启动新流程
                    Map<String, Object> startVars = new LinkedHashMap<>();
                    startVars.put("biz_no", currentBizNo);
                    startVars.put("customer_name", "数字人客户");
                    startVars.put("channel", "数字人转人工");
                    startVars.put("service_mode", DEFAULT_SERVICE_MODE);
                    startVars.put("mobile_peer_id", "");
                    Map<String, Object> slots = new LinkedHashMap<>();
                    slots.put("transfer_requested", true);
                    response.put("success", true);
                    response.put("action", "SUBMIT");
                    response.put("intent", "TRANSFER_HUMAN");
                    response.put("processKey", "advanced_sop");
                    response.put("nodeId", null);
                    response.put("slots", slots);
                    response.put("startVars", startVars);
                    response.put("bizNo", currentBizNo);
                    response.put("processId", "");
                    response.put("reply", reply.isEmpty() ? "好的，正在为您转接人工座席，请稍候..." : reply);
                }

            } else if ("CHITCHAT".equalsIgnoreCase(intent)) {
                response.put("success", true);
                response.put("action", "chitchat");
                response.put("intent", "CHITCHAT");
                response.put("bizNo", currentBizNo);
                response.put("reply", reply.isEmpty() ? "您好！我是银行智能数字员工晨曦。很高兴为您服务，请问有什么可以帮您？" : reply);

            } else {
                response.put("success", true);
                response.put("action", "qa");
                response.put("intent", "QA");
                response.put("bizNo", currentBizNo);
                response.put("reply", reply.isEmpty() ? "您好，我已了解您的咨询需求。如有需要，您也可以选择办理转账、查余额或联系人工座席。" : reply);
            }

            return response;

        } catch (RuntimeException failure) {
            // 下游（流程服务等）的运行时失败按 CONTRACTS「下游失败语义原样透传」上抛，不降级、不走本地规则
            throw failure;
        } catch (Exception e) {
            // 模型侧调用或返回解析失败：显式 502，不再回退到本地规则兜底
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "大模型意图解析失败", e);
        }
    }

    /**
     * 解析转人工路径。
     *
     * @return {@code "submit:<nodeId>"} 表示已有流程需提交到指定节点；
     *         {@code "start"} 表示需启动新流程；{@code null} 表示无需操作
     */
    private String resolveTransferToHuman(String bizNo, String incomingProcId) {
        if (incomingProcId != null && !incomingProcId.trim().isEmpty()) {
            Map<String,Object> state = process.state(incomingProcId);
            Object rawPolicy = state.get("nodePolicy");
            if (rawPolicy instanceof Map && ((Map<?,?>)rawPolicy).get("actions") instanceof List
                    && ((List<?>)((Map<?,?>)rawPolicy).get("actions")).contains("REQUEST_HUMAN")) return "submit:" + state.get("nodeId");
            if (process.isRunning(incomingProcId)) return null;
        }
        return "start";
    }

    private String buildSystemPrompt(String userQuery) {
        StringBuilder sb = new StringBuilder();
        sb.append(properties.getProfile().getPersonaPrompt()).append("\n\n");
        sb.append(knowledgeService.buildKnowledgePromptContext(userQuery)).append("\n");
        sb.append("【意图识别与业务分发准则】：\n")
          .append("请分析客户输入的文字，准确判定以下 5 类意图之一：\n")
          .append("1. TRANSFER（转账汇款）：客户希望进行转账、汇款或修改收款人/金额要素。\n")
          .append("   - 必须提取 amount (纯数字，如200万提取为2000000，1000元提取为1000) 和 payee (收款人姓名)；\n")
          .append("   - 若要素缺失（例如未提收款人或金额），设置 slotsComplete 为 false，并在 reply 中礼貌追问缺失信息；\n")
          .append("   - 若要素齐全，设置 slotsComplete 为 true；\n")
          .append("   - 若在上一轮基础上修改要素，设置 isModification 为 true，并继承未变动的要素。\n")
          .append("2. BALANCE（查询余额）：客户想要查询账户余额、资金状况。\n")
          .append("3. QA（银行业务咨询）：客户询问银行政策、转账限额、手续费、理财产品推荐、营业时间、防诈提醒等。\n")
          .append("   - 必须结合【银行官方业务知识与政策规章库】给出专业、清晰、亲切的解答；严禁胡编乱造。\n")
          .append("4. CHITCHAT（日常问候）：如打招呼、询问你是谁、表达感谢等。\n")
          .append("5. TRANSFER_HUMAN（转人工服务）：客户明确要求真人客服、人工座席介入。\n\n")
          .append("【输出格式约束】：\n")
          .append("必须严格返回无任何 Markdown 标记的合法 JSON 字符串，格式示例：\n")
          .append("{\"intent\":\"TRANSFER\", \"amount\":2000000, \"payee\":\"王总\", \"isModification\":false, \"slotsComplete\":true, \"reply\":\"好的，已为您记录给王总转账200万元，请核对信息。\"}\n")
          .append("{\"intent\":\"QA\", \"reply\":\"我行手机银行单笔转账限额为50万元；超过50万元将由座席进行音视频授权见证。\"}\n");
        return sb.toString();
    }

    private JsonNode callLlm(String systemPrompt, String userText, List<Map<String, String>> history, String model) throws Exception {
        DigitalHumanProperties.Llm llmConfig = properties.getLlm();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(llmConfig.getTimeoutMs());
        factory.setReadTimeout(llmConfig.getTimeoutMs());
        RestTemplate restTemplate = new RestTemplate(factory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(llmConfig.getApiKey());
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        Map<String, Object> body = new HashMap<>();
        body.put("model", model != null && !model.isEmpty() ? model : llmConfig.getModel());
        body.put("temperature", llmConfig.getTemperature());

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> sysMsg = new HashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        messages.add(sysMsg);

        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 6);
            for (int i = start; i < history.size(); i++) {
                messages.add(history.get(i));
            }
        }

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userText);
        messages.add(userMsg);

        body.put("messages", messages);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> apiRes;
        try {
            apiRes = restTemplate.postForEntity(llmConfig.getApiUrl(), entity, String.class);
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "大模型服务不可用", failure);
        }

        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(apiRes.getBody());
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "大模型返回内容无法解析", failure);
        }
        JsonNode choice = rootNode.path("choices").get(0);
        if (choice == null || !choice.path("message").has("content")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "大模型返回缺少 choices 内容");
        }
        String aiContent = choice.path("message").path("content").asText();
        // 过滤思维链与 Markdown 标记
        aiContent = aiContent.replaceAll("(?s)<think>.*?</think>", "").replaceAll("```json", "").replaceAll("```", "").trim();
        return objectMapper.readTree(aiContent);
    }
}

package com.example.bank.business.application;

import com.example.bank.business.contract.HandlerType;
import com.example.bank.business.contract.QueueTicket;
import com.example.bank.business.customer.CustomerProfile;
import com.example.bank.business.customer.CustomerProfileService;
import com.example.bank.business.port.ProcessPort;
import com.example.bank.business.port.RoutingPort;
import com.example.bank.business.service.BizRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户进线用例：申请用户 → 进线排队（等级识别）→ 流程节点内完成队列路由 → 接待方受理。
 *
 * <p>入队职责在流程节点（{@code dh_queue} / {@code human_queue}）：本服务只负责上送业务要素并启动流程，
 * 「由谁接待」由 {@code entry_gateway} 按 {@code service_mode} 决定，因此响应中的工单信息是<b>读回</b>而非本地入队。</p>
 *
 * <p><b>拆分后的边界</b>：流程引擎在流程服务、工单在路由服务，两者都只经端口调用。
 * 客户建档与等级识别仍在本服务本地 —— 那是本服务独占的业务事实，全系统唯一的画像来源。</p>
 */
@Service
public class CustomerEntryService {

    /** 接待方式：人工直连。委托节点据此在 entry_gateway 处直接排队给坐席。 */
    public static final String SERVICE_MODE_HUMAN = "HUMAN";

    /** 接待方式：数字人接待。委托节点据此先排给空闲数字人，客户可中途转人工。 */
    public static final String SERVICE_MODE_DIGITAL = "DIGITAL";

    // ==================== 办理人信息与核验状态：演示取值集中定义区 ====================
    // 下列取值当前均无真实数据源，统一集中在此处构造，禁止散落到业务逻辑里；
    // 真实数据源接入后替换（联网核查平台 / 客户信息系统）。

    /** 证件类型：存人行证件类型代码，展示层补全为「代码-名称」（真实数据源接入后替换：客户信息系统）。 */
    private static final String CERT_TYPE_ID_CARD = "01";
    /** 客户号核验状态：已核验。核验平台取值域为 VERIFIED / UNVERIFIED。 */
    private static final String CUST_ID_STATUS_VERIFIED = "VERIFIED";
    /** 证件有效期状态：有效。核验平台取值域为 VALID / EXPIRING / EXPIRED。 */
    private static final String CERT_VALIDITY_STATUS_VALID = "VALID";
    /** 联网核查状态：已核查。核验平台取值域为 CHECKED / UNCHECKED。 */
    private static final String NETWORK_CHECK_STATUS_CHECKED = "CHECKED";
    /** 证件有效期：真实数据源接入后替换（客户信息系统）。 */
    private static final String CERT_VALIDITY_DEMO = "2028-06-30";
    /** 归属网点：真实数据源接入后替换（设备接入平台）。 */
    private static final String BRANCH_NAME_DEMO = "杭州西湖支行营业部";
    /** 设备编号：真实数据源接入后替换（设备接入平台）。 */
    private static final String DEVICE_ID_DEMO = "VTM-HZ-0207";
    /** 终端柜员：真实数据源接入后替换（坐席签入信息）。 */
    private static final String TELLER_ID_DEMO = "T10086";
    /** 性别：真实数据源接入后替换（客户信息系统）。 */
    private static final String GENDER_DEMO = "女";

    /** 客户画像与等级识别（内置模拟数据，规则与既有等级判定逐条一致）。 */
    private final CustomerProfileService profiles;
    @org.springframework.beans.factory.annotation.Autowired private com.example.bank.business.service.TradeCatalogService catalog;
    private final ProcessPort process;
    private final RoutingPort routing;
    private final BizRecordService records;
    /** 只包住本地的两次登记写。远程调用不在事务里 —— 见 {@link #queue}。 */
    private final TransactionTemplate transaction;

    public CustomerEntryService(CustomerProfileService profiles, ProcessPort process, RoutingPort routing,
                                BizRecordService records, PlatformTransactionManager transactions) {
        this.profiles = profiles; this.process = process; this.routing = routing; this.records = records;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** 申请用户接口。 */
    public CustomerProfile apply(Map<String, Object> input) {
        Integer tier = null;
        Object override = input.get("assetTier");
        if (override instanceof Number) tier = ((Number) override).intValue();
        else if (override != null && !String.valueOf(override).isEmpty()) {
            // 资产等级是调用方显式指定的路由依据，解析不了必须 400。
            // 早先吞掉 NumberFormatException 会退回按证件号哈希推导等级，
            // 调用方拿到的是一份"看起来成功"的画像，等级却不是它指定的那个。
            try { tier = Integer.valueOf(String.valueOf(override).trim()); }
            catch (NumberFormatException failure) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "资产等级必须是整数：" + override, failure);
            }
        }
        return profiles.apply(text(input, "name"), text(input, "certNo"), text(input, "mobile"),
                text(input, "channel"), tier);
    }

    /**
     * 进线排队接口：识别客户等级、启动流程并进入队列路由。
     *
     * <p><b>没有方法级事务</b>：启动流程是出站 HTTP，包进事务会让数据库连接在整个调用期间被占用
     * （流程启动会同步跑完委托节点，那些节点再各自出站，读超时按 15 秒设），并发进线时连接池会被拖垮。
     * 而跨服务后「流程启动 + 本地登记」本来就不再原子，事务守住也守不住它。</p>
     *
     * <p>代价落在顺序上：<b>先启动流程，后写本地登记</b>。流程推进是业务事实，登记是记录 ——
     * 事实先发生、记录后补，缺了可以补；反过来先写登记再启动流程，失败时留下的是一条
     * 对应不到任何流程的假记录。本地两次写（建档 + 补写交易信息）仍包在一个事务里：
     * 缺了交易信息的记录会让工作台左栏的业务标签整块空白。</p>
     */
    public Map<String, Object> queue(Map<String, Object> input) {
        String customerId = text(input, "customerId");
        CustomerProfile profile = profiles.identify(profiles.get(customerId));

        Map<String,Object> trade = catalog.resolve(input);
        String bizType = String.valueOf(trade.get("bizType"));
        Double amount = number(input.get("amount"));
        String payee = text(input, "payee");
        String purpose = text(input, "purpose");
        String mobilePeerId = text(input, "peerId");
        String channel = blank(text(input, "channel")) ? profile.channel : text(input, "channel");
        // 进线渠道：与写入流程变量 channel 的取值保持一致，供工单展示与补写使用
        String entryChannel = blank(channel) ? "移动端进线" : channel;
        String serviceMode = serviceModeOf(input);
        // 交易品种：进线未携带交易编码时按业务类型兜底，保证工作台能显示业务标签（形如 300101-转账汇款）
        String tradeCode = String.valueOf(trade.get("tradeCode"));
        String tradeName = String.valueOf(trade.get("tradeName"));

        String requested = text(input, "processId");
        String processId;
        if (!blank(requested) && process.isRunning(requested)) {
            // 续办既有流程：只补画像，不重复建档
            processId = requested;
            process.setVariables(processId, customerContext(profile, tradeCode, tradeName));
        } else {
            final String bizNo = "BIZ" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            final double finalAmount = amount == null ? 0D : amount;
            Map<String, Object> vars = new HashMap<String, Object>();
            vars.put("biz_no", bizNo);
            vars.put("amount", finalAmount);
            vars.put("payee", payee);
            vars.put("purpose", purpose);
            vars.put("channel", entryChannel);
            vars.put("entry_type", "CUSTOMER");
            vars.put("mobile_peer_id", mobilePeerId);
            vars.put("client_confirmed", true);
            // entry_gateway 据此选择接待方式；缺省为人工直连，保持既有进线行为不变
            vars.put("service_mode", serviceMode);
            // 接待信息须在流程启动前写入，排队路由节点在流程内读取画像做技能组匹配
            vars.putAll(customerContext(profile, tradeCode, tradeName));
            // 本用例不设业务主键：流程实例号即业务主键，重复进线由上面的 isRunning 分支挡住
            processId = process.start(String.valueOf(trade.get("processDefinitionKey")), bizNo, vars);
            final String startedProcessId = processId;
            final String finalPayee = payee;
            transaction.execute(status -> {
                records.createRecord(bizNo, startedProcessId, profile.name, profile.certNoMasked, bizType,
                        finalAmount, finalPayee, "HUMAN_COLLABORATIVE");
                // 补写交易展示字段与呼入信息，供坐席工作台左栏展示业务标签（形如 300101-转账汇款）
                records.updateTradeInfo(bizNo, entryChannel, CERT_TYPE_ID_CARD, profile.customerId,
                        tradeCode, tradeName);
                return null;
            });
        }

        Map<String, Object> variables = process.variables(processId);
        Object bizNoValue = variables.get("biz_no");
        // 工单由流程节点创建：数字人排队节点与转人工排队节点都会写入同一张工单，此处按流程实例读回
        QueueTicket ticket = routing.byProcess(processId);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", true);
        result.put("processId", processId);
        result.put("bizNo", bizNoValue == null ? "" : String.valueOf(bizNoValue));
        result.put("customerId", profile.customerId);
        result.put("customerName", profile.name);
        result.put("level", profile.level);
        result.put("levelName", profile.levelName);
        result.put("skillGroup", profile.skillGroup);
        result.put("tags", profile.tags);
        result.put("riskListed", profile.riskListed);
        result.put("serviceMode", serviceMode);
        result.put("needHuman", ticket != null && HandlerType.HUMAN.equals(ticket.handlerType));
        result.put("state", process.state(processId));
        if (ticket == null) {
            result.put("message", "风险校验通过，业务已直通核心系统，无需人工介入");
            return result;
        }
        result.put("ticket", ticket);
        result.put("queuePosition", routing.waiting(profile.skillGroup).size());
        result.put("assigned", ticket.agentId != null);
        result.put("assignedAgentId", ticket.agentId);
        result.put("handlerType", ticket.handlerType);
        result.put("status", ticket.status);
        result.put("message", HandlerType.HUMAN.equals(ticket.handlerType)
                ? "已为您转接人工坐席，请稍候" : "已为您安排智能数字员工，请稍候");
        return result;
    }

    /** 查询客户档案（含最新等级识别结果）。 */
    public CustomerProfile profile(String customerId) {
        return profiles.identify(profiles.get(customerId));
    }

    /** 进线排队快照，供客户移动端与坐席端轮询兜底。 */
    public Map<String, Object> status(String customerId) {
        if (blank(customerId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少客户标识");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        CustomerProfile profile = profiles.get(customerId);
        result.put("success", true);
        result.put("customer", profile);
        List<QueueTicket> all = routing.waiting(null);
        List<QueueTicket> own = new ArrayList<QueueTicket>();
        for (QueueTicket ticket : all) {
            if (profile.customerId.equals(ticket.customerId)) own.add(ticket);
        }
        result.put("tickets", own);
        result.put("waitingCount", all.size());
        return result;
    }

    /** 接待方式归一化：仅接受 HUMAN / DIGITAL，其余（含缺省）一律按人工直连接待。 */
    private String serviceModeOf(Map<String, Object> input) {
        String mode = text(input, "serviceMode").toUpperCase();
        return SERVICE_MODE_DIGITAL.equals(mode) ? SERVICE_MODE_DIGITAL : SERVICE_MODE_HUMAN;
    }

    /** 进线上下文：写入流程变量，供接待方资源匹配与页面渲染使用。 */
    private Map<String, Object> customerContext(CustomerProfile profile, String tradeCode, String tradeName) {
        Map<String, Object> context = new HashMap<String, Object>();
        context.put("customer_id", profile.customerId);
        context.put("customer_name", profile.name);
        context.put("customer_level", profile.level);
        context.put("customer_level_name", profile.levelName);
        context.put("customer_skill_group", profile.skillGroup);
        context.put("customer_tags", String.join("/", profile.tags));
        context.put("customer_cert_masked", profile.certNoMasked);
        context.put("customer_mobile_masked", profile.mobileMasked);
        // 风险名单标记：流程服务从流程变量组装画像 DTO（它不查本服务的档案），
        // 漏写这一项会让流程内的风险校验节点凭空少一个判据，且不报错
        context.put("customer_risk_listed", profile.riskListed);
        // 交易标识、办理人信息、呼入信息与三项核验状态（演示取值集中构造）
        context.putAll(applicantContext(profile, tradeCode, tradeName));
        return context;
    }

    /**
     * 办理人信息与核验状态：演示取值的唯一构造入口，取值来自本类顶部的演示常量区。
     *
     * <p>字段名与坐席工作台左栏标签一一对应，核验状态严格使用核验平台枚举：
     * {@code cust_id_status}（VERIFIED / UNVERIFIED）、
     * {@code cert_validity_status}（VALID / EXPIRING / EXPIRED）、
     * {@code network_check_status}（CHECKED / UNCHECKED）。</p>
     */
    private Map<String, Object> applicantContext(CustomerProfile profile, String tradeCode, String tradeName) {
        Map<String, Object> context = new HashMap<String, Object>();
        // 交易标识：供快照与工作台显示业务标签（形如 300101-转账汇款）
        context.put("trade_code", tradeCode);
        context.put("trade_name", tradeName);
        // 呼入信息：归属网点 / 设备编号 / 终端柜员（真实数据源接入后替换：设备接入平台 / 坐席签入信息）
        context.put("branch_name", BRANCH_NAME_DEMO);
        context.put("device_id", DEVICE_ID_DEMO);
        context.put("teller_id", TELLER_ID_DEMO);
        // 办理人信息：客户号 / 性别 / 证件类型 / 证件有效期（真实数据源接入后替换：客户信息系统）
        context.put("cust_id", profile.customerId);
        context.put("gender", GENDER_DEMO);
        context.put("cert_type", CERT_TYPE_ID_CARD);
        context.put("cert_validity", CERT_VALIDITY_DEMO);
        // 三项核验标签状态（真实数据源接入后替换：联网核查平台）
        context.put("cust_id_status", CUST_ID_STATUS_VERIFIED);
        context.put("cert_validity_status", CERT_VALIDITY_STATUS_VALID);
        context.put("network_check_status", NETWORK_CHECK_STATUS_CHECKED);
        return context;
    }

    private String text(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 解析交易金额。缺省（null / 空串）返回 {@code null} —— 咨询类进线本就没有金额，
     * 这是"未提供"而不是失败。
     *
     * <p>非空却解析不了则 400：早先返回 null 会让一笔写错格式的转账金额静默变成 0，
     * 流程变量与台账上留下一笔金额为零的交易，而调用方收到的是成功。</p>
     */
    private Double number(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return null;
        try { return Double.valueOf(text); }
        catch (NumberFormatException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "交易金额格式不合法：" + value, failure);
        }
    }

    private boolean blank(String value) { return value == null || value.isEmpty(); }
}

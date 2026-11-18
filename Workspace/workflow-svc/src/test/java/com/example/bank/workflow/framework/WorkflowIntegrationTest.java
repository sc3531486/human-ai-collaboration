package com.example.bank.workflow.framework;

import com.example.bank.workflow.delegate.ApiTaskDelegate;
import com.example.bank.workflow.session.BusinessSessionService;
import com.example.bank.workflow.sop.TrailService;
import com.example.bank.workflow.events.ProcessOutcomeOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.*;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实 Flowable 6.8 引擎和独立内存数据库，验证事务和场景编排。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowIntegrationTest {
    private ProcessEngine engine;
    private RuntimeService runtime;
    private TaskService tasks;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private NodeSubmissionService submissions;
    private BpmnMetaReader bpmnMeta;
    private FlowData data;
    private RestTemplate http;
    private TrailService trail;
    private final List<Map<String,Object>> calls=Collections.synchronizedList(new ArrayList<>());
    private final Map<Object,Object> beans=new HashMap<>();
    private boolean passwordIdentityPassed;

    @BeforeAll void initialize() throws Exception {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:workflow-refactor;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        DataSourceTransactionManager manager=new DataSourceTransactionManager(source);tx=new TransactionTemplate(manager);jdbc=new JdbcTemplate(source);
        SpringProcessEngineConfiguration configuration=new SpringProcessEngineConfiguration();
        configuration.setDataSource(source);configuration.setTransactionManager(manager);configuration.setDatabaseSchemaUpdate("true");
        configuration.setAsyncExecutorActivate(false);configuration.setHistory("full");configuration.setBeans(beans);
        engine=configuration.buildProcessEngine();runtime=engine.getRuntimeService();tasks=engine.getTaskService();
        // 部署 BPMN 流程定义
        engine.getRepositoryService().createDeployment()
                .addClasspathResource("processes/AdvancedSOP.bpmn20.xml")
                .addClasspathResource("processes/BalanceSOP.bpmn20.xml")
                .addClasspathResource("processes/PasswordResetSOP.bpmn20.xml")
                .addClasspathResource("processes/UnifiedAuthorizationSOP.bpmn20.xml")
                .deploy();
        data=new FlowData(new ObjectMapper());
        beans.put("flowData", data);
        bpmnMeta=new BpmnMetaReader(engine.getRepositoryService());
        jdbc.execute("CREATE TABLE node_submission(process_id VARCHAR(64),request_id VARCHAR(80),node_id VARCHAR(100),node_name VARCHAR(200),action_code VARCHAR(40),actor_id VARCHAR(100),request_hash VARCHAR(64),submitted_values CLOB,submitted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,visit_seq INT,task_id VARCHAR(64),act_inst_id VARCHAR(64),schema_version VARCHAR(20),execution_id VARCHAR(64),entry_seq BIGINT AUTO_INCREMENT UNIQUE,PRIMARY KEY(process_id,request_id))");
        jdbc.execute("CREATE TABLE workflow_submission_lock(process_id VARCHAR(64) PRIMARY KEY)");
        BusinessSessionService sessions=new BusinessSessionService(runtime,tasks);
        submissions=new NodeSubmissionService(runtime,tasks,engine.getHistoryService(),engine.getRepositoryService(),jdbc,data,bpmnMeta,sessions);
        trail=new TrailService(engine.getHistoryService(),runtime,bpmnMeta,submissions,data);
        http=mock(RestTemplate.class);
        ApiTaskDelegate apiTask=new ApiTaskDelegate(http,"http://capabilities.test",data,submissions);
        beans.put("apiTaskDelegate",apiTask);
        // BPMN 的等待节点用 ${businessNodeActions} 声明进入/完成动作，交接动作统一用
        // ${nodeTransitionDelegate}（改造方案 §14.1）。引擎按 delegateExpression 解析 Bean，
        // 缺失即 Unknown property 启动失败，因此两个名字都要注册。
        com.example.bank.workflow.delegate.NodeTransitionDelegate transition =
                new com.example.bank.workflow.delegate.NodeTransitionDelegate(http,"http://business.test",sessions,data);
        beans.put("nodeTransitionDelegate",transition);
        beans.put("businessNodeActions",new com.example.bank.workflow.delegate.BusinessNodeActions(
                transition, apiTask, sessions));
        ProcessOutcomeOutbox outbox=new ProcessOutcomeOutbox(jdbc,data,http,"http://business.test");outbox.initialize();
        engine.getRuntimeService().addEventListener(outbox,org.flowable.common.engine.api.delegate.event.FlowableEngineEventType.PROCESS_COMPLETED);
    }
    @AfterAll void close(){engine.close();}
    @BeforeEach void resetCalls(){calls.clear(); reset(http);
        passwordIdentityPassed = false;
        when(http.postForObject(eq("http://business.test/internal/reception/transition"),any(),eq(Map.class)))
                .thenReturn(new HashMap<String,Object>(){{put("accepted",true);put("segmentIndex",1);}});
        when(http.postForObject(anyString(),any(),eq(Map.class),anyString())).thenAnswer(invocation->{
            Map<String,Object> request=invocation.getArgument(1);Map<String,Object> input=(Map<String,Object>)request.get("payload");
            String code=invocation.getArgument(3);Map<String,Object> call=new LinkedHashMap<>(input);call.put("code",code);call.put("operationId",request.get("operationId"));calls.add(call);
            Map<String,Object> result=new LinkedHashMap<>();
            if(code.equals("risk.evaluate.v1")) {
                boolean need=new java.math.BigDecimal(String.valueOf(input.get("amount"))).compareTo(new java.math.BigDecimal("500000"))>=0;
                result.put("needHuman",need);result.put("riskScore",need?89:12);result.put("riskEngine","测试风控");result.put("evalTime","2026-09-16");result.put("riskLevel",need?"HIGH":"LOW");result.put("rulesSummary","测试规则");result.put("riskDecision",need?"人工核验":"通过");
            } else if(code.equals("core.transfer.v1")) {
                result.put("coreTxId","TX-TEST");result.put("statusDesc","SUCCESS");result.put("accountingTime","2026-09-16");result.put("hostVoucherNo","V-1");
            } else if(code.equals("notification.send.v1")) {
                result.put("channel","测试");result.put("content","回执");result.put("statusDesc","DELIVERED");result.put("sendTime","2026-09-16");result.put("msgId","M-1");
            } else if(code.equals("core.balance.v1")) {
                result.put("formattedBalance","123.45 元");result.put("availableBalance",new java.math.BigDecimal("123.45"));result.put("currency","CNY");result.put("accountStatus","NORMAL");
            } else if(code.equals("identity.verify.v1")) {result.put("pass",true);result.put("auditId","I-1");}
            else if(code.equals("password.cards.v1")) result.put("cards",Collections.emptyList());
            else if(code.equals("password.customer-identity.v1")) {
                result.put("identityAuditId","AUDIT");
                result.put("faceScore",passwordIdentityPassed ? 96 : 90);
                result.put("faceThreshold",91);
                result.put("onlinePassed",true);
                result.put("identityPassed",passwordIdentityPassed);
            }
            else if(code.equals("password.review.v1")) result.put("needsAuthorization","REQUEST_AUTH".equals(input.get("action")));
            else if(code.equals("authorization.create.v1")) result.put("authorizationId","AUTH");
            else if(code.equals("authorization.load.v1")) result.put("authorizationSnapshot",Collections.emptyMap());
            else if(code.equals("authorization.decide.v1")) {result.put("authorizationApproved","AUTHORIZE".equals(input.get("decision")));result.put("authorizationDecisionId","AUTH");result.put("authorizationMessage","AUTHORIZE".equals(input.get("decision"))?"授权通过":"授权未通过");}
            else if(code.equals("sms.send.v1")) result.put("smsChallengeId","SMS");
            else if(code.equals("sms.verify.v1")) {result.put("smsPassed","123456".equals(input.get("code")));if("123456".equals(input.get("code")))result.put("smsVerificationId","SMSV");}
            else if(code.equals("password.form.v1")) {result.put("serviceFormId",request.get("operationId"));result.put("serviceFormUrl","/records/form.pdf");}
            else if(code.equals("password.signature.v1")) result.put("signatureDocumentId","SIGNED");
            else if(code.equals("password.reset.v1")) {result.put("resetStatus","SUCCESS");result.put("resetReceipt","RESET");}
            else if(code.equals("password.receipt.v1")) result.put("receiptPdfUrl","/records/receipt.pdf");
            return new LinkedHashMap<String,Object>(){{put("status","SUCCEEDED");put("data",result);}};
        });
    }
    private String start(String key){
        Map<String,Object> vars=new HashMap<>();vars.put("service_mode","DIGITAL");vars.put("mobile_peer_id","peer-test");vars.put("biz_no","BIZ-TEST");vars.put("customer_name","客户");vars.put("customer_cert_masked","110***1234");
        vars.put("customer_id","customer-test");vars.put("customer_level","NORMAL");vars.put("customer_level_name","测试客户");vars.put("customer_skill_group","GENERAL");vars.put("channel","测试渠道");vars.put("biz_type","TRANSFER");
        vars.put("payload","{\"amount\":1,\"payee\":\"旧收款人\",\"payee_account\":\"6222020200001234\"}");vars.put("submission_revision",0L);
        return runtime.startProcessInstanceByKey(key,vars).getId();
    }
    private NodeSubmission request(String id,String node,String action,String payload){
        NodeSubmission request=new NodeSubmission();request.requestId=UUID.randomUUID().toString();request.nodeId=node;request.action=action;request.clientId="peer-test";request.payload=payload;return request;
    }
    private boolean submit(String id,NodeSubmission request){return tx.execute(status->submissions.submit(id,request));}
    private NodeSubmission agent(String id,String node,String action,String payload,String taskId){
        NodeSubmission request=request(id,node,action,payload);request.clientId=null;request.agentId="agent-test";request.taskId=taskId;return request;
    }

    private String submitPasswordPhotos() {
        String id=start("password_reset_sop");
        assertEquals(Collections.singletonList("card_capture"),runtime.getActiveActivityIds(id));
        submit(id,request(id,"card_capture","SUBMIT","{\"selected_card_id\":\"CARD\",\"media_photo\":\"card\"}"));
        assertEquals(Collections.singletonList("id_front_capture"),runtime.getActiveActivityIds(id));
        submit(id,request(id,"id_front_capture","SUBMIT","{\"id_front_photo\":\"front\"}"));
        assertEquals(Collections.singletonList("id_back_capture"),runtime.getActiveActivityIds(id));
        submit(id,request(id,"id_back_capture","SUBMIT","{\"id_back_photo\":\"back\"}"));
        assertEquals(Collections.singletonList("customer_face_capture"),runtime.getActiveActivityIds(id));
        verify(http,never()).postForObject(eq("http://business.test/internal/reception/transition"),any(),eq(Map.class));
        submit(id,request(id,"customer_face_capture","SUBMIT","{\"face_photo\":\"face\"}"));
        Map<String,Object> identity = calls.stream().filter(call -> "password.customer-identity.v1".equals(call.get("code"))).findFirst().get();
        assertEquals("card",identity.get("mediaPhoto"));
        assertEquals("front",identity.get("idFrontPhoto"));
        assertEquals("back",identity.get("idBackPhoto"));
        assertEquals("face",identity.get("customerFacePhoto"));
        return id;
    }
    private String passwordToReview() {
        String id=submitPasswordPhotos();
        assertEquals("verification_review",tasks.createTaskQuery().processInstanceId(id).singleResult().getTaskDefinitionKey());
        assertFalse(calls.stream().anyMatch(c -> "sms.send.v1".equals(c.get("code"))));
        verify(http).postForObject(eq("http://business.test/internal/reception/transition"),any(),eq(Map.class));
        runtime.setVariable(id,"agent_session_id","agent-test");
        return id;
    }
    @Test void passwordRequiresSmsAndSignatureReviewAndDoesNotCloseRoom() {
        passwordIdentityPassed = true;
        String id=submitPasswordPhotos();
        assertEquals(Collections.singletonList("sms_verify"),runtime.getActiveActivityIds(id));
        verify(http,never()).postForObject(eq("http://business.test/internal/reception/transition"),any(),eq(Map.class));
        submit(id,request(id,"sms_verify","SUBMIT","{\"sms_code\":\"000000\"}"));
        assertTrue(runtime.getActiveActivityIds(id).contains("sms_verify"));
        assertFalse(calls.stream().anyMatch(c->"password.form.v1".equals(c.get("code"))));
        submit(id,request(id,"sms_verify","SUBMIT","{\"sms_code\":\"123456\"}"));
        submit(id,request(id,"customer_sign","SUBMIT","{\"signature_image\":\"signature\",\"service_form_confirmed\":true}"));
        assertFalse(calls.stream().anyMatch(c->"password.reset.v1".equals(c.get("code"))));
        Task signature=tasks.createTaskQuery().processInstanceId(id).singleResult();
        verify(http).postForObject(eq("http://business.test/internal/reception/transition"),any(),eq(Map.class));
        runtime.setVariable(id,"agent_session_id","agent-test");
        submit(id,agent(id,"signature_review","REJECT","{\"signature_review_confirmed\":false,\"review_reason\":\"签名不清晰\"}",signature.getId()));
        assertTrue(runtime.getActiveActivityIds(id).contains("customer_sign"));
        submit(id,request(id,"customer_sign","SUBMIT","{\"signature_image\":\"new-signature\",\"service_form_confirmed\":true}"));
        signature=tasks.createTaskQuery().processInstanceId(id).singleResult();
        submit(id,agent(id,"signature_review","AUTHORIZE","{\"signature_review_confirmed\":true}",signature.getId()));
        assertNull(runtime.createProcessInstanceQuery().processInstanceId(id).singleResult());
        assertEquals(1,calls.stream().filter(c->"password.reset.v1".equals(c.get("code"))).count());
        assertEquals(1,calls.stream().filter(c->"password.receipt.v1".equals(c.get("code"))).count());
        verify(http,never()).postForObject(contains("close"),any(),eq(Map.class));
    }
    @Test void separateAuthorizationReturnsChildDecisionToParentBeforeSms() {
        String id=passwordToReview();Task review=tasks.createTaskQuery().processInstanceId(id).singleResult();
        submit(id,agent(id,"verification_review","REQUEST_AUTH","{\"review_reason\":\"分数不足\"}",review.getId()));
        ProcessInstance child=runtime.createProcessInstanceQuery().superProcessInstanceId(id).singleResult();assertNotNull(child);
        assertEquals(id,runtime.getVariable(child.getId(),"parent_process_id"));
        assertEquals("agent-test",runtime.getVariable(child.getId(),"authorization_requester"));
        assertEquals("AUTHORIZER",runtime.getVariable(child.getId(),"participant_role"));
        assertEquals("AUTHORIZATION",runtime.getVariable(child.getId(),"customer_skill_group"));
        assertEquals("AUTHORIZATION",runtime.getVariable(child.getId(),"biz_type"));
        assertFalse(calls.stream().anyMatch(c->"sms.send.v1".equals(c.get("code"))));
        runtime.setVariable(child.getId(),"agent_session_id","reviewer");Task task=tasks.createTaskQuery().processInstanceId(child.getId()).singleResult();
        NodeSubmission decision=agent(child.getId(),"authorization_review","AUTHORIZE","{\"authorization_confirmed\":true,\"review_reason\":\"资料一致\"}",task.getId());decision.agentId="reviewer";
        submit(child.getId(),decision);
        assertTrue(runtime.getActiveActivityIds(id).contains("sms_verify"));
        assertEquals("AUTH",runtime.getVariable(id,"authorizationDecisionId"));
        assertEquals("agent-test",runtime.getVariable(id,"agent_session_id"));
    }
    @Test void authorizationRejectionEndsBusinessWithoutSmsOrReset() {
        String id=passwordToReview();Task review=tasks.createTaskQuery().processInstanceId(id).singleResult();
        submit(id,agent(id,"verification_review","REQUEST_AUTH","{\"review_reason\":\"分数不足\"}",review.getId()));
        ProcessInstance child=runtime.createProcessInstanceQuery().superProcessInstanceId(id).singleResult();runtime.setVariable(child.getId(),"agent_session_id","reviewer");
        Task task=tasks.createTaskQuery().processInstanceId(child.getId()).singleResult();
        NodeSubmission decision=agent(child.getId(),"authorization_review","REJECT","{\"authorization_confirmed\":false,\"review_reason\":\"资料不一致\"}",task.getId());decision.agentId="reviewer";submit(child.getId(),decision);
        assertNull(runtime.createProcessInstanceQuery().processInstanceId(id).singleResult());
        assertFalse(calls.stream().anyMatch(c->Arrays.asList("sms.send.v1","password.reset.v1").contains(c.get("code"))));
        assertEquals("授权未通过",engine.getHistoryService().createHistoricVariableInstanceQuery().processInstanceId(id).variableName("resetMessage").singleResult().getValue());
    }
    @Test void latestAmountControlsGatewayAndCoreUsesNewPayload(){
        String id=start("advanced_sop");NodeSubmission collect=request(id,"ai_collect","SUBMIT","{\"amount\":600000.01,\"payee\":\"新收款人\",\"payee_account\":\"6222020200001234\"}");
        submit(id,collect);assertTrue(runtime.getActiveActivityIds(id).contains("human_auth"));
        assertEquals("600000.01",String.valueOf(calls.get(0).get("amount")));assertEquals("新收款人",calls.get(0).get("payee"));
        assertFalse(calls.stream().anyMatch(c->c.get("code").equals("core.transfer.v1")));
        Task task=tasks.createTaskQuery().processInstanceId(id).singleResult();runtime.setVariable(id,"agent_session_id","agent-test");
        // 人工授权后还有客户签名与人工核实签名（改造方案 §5.3），转账能力在核实通过后才执行
        submit(id,agent(id,"human_auth","AUTHORIZE","{\"evidence_receipt\":\"ev-1\",\"confirmed\":true}",task.getId()));
        submit(id,request(id,"customer_sign","SUBMIT","{\"signature_image\":\"sig-1\",\"confirmed\":true}"));
        assertEquals("新收款人",data.object(runtime.getVariable(id,"payload")).get("payee"));
        Task review=tasks.createTaskQuery().processInstanceId(id).singleResult();
        submit(id,agent(id,"signature_review","AUTHORIZE","{\"confirmed\":true}",review.getId()));
        Map<String,Object> transfer=calls.stream().filter(c->c.get("code").equals("core.transfer.v1")).findFirst().get();
        assertEquals("新收款人",transfer.get("payeeName"));assertEquals("600000.01",String.valueOf(transfer.get("amount")));
    }

    @Test void receiptReplaysAfterNodeAndProcessHaveEnded(){
        String id=start("advanced_sop");NodeSubmission collect=request(id,"ai_collect","SUBMIT","{\"amount\":100,\"payee\":\"李四\",\"payee_account\":\"6222020200001234\"}");
        assertFalse(submit(id,collect));assertTrue(submit(id,collect));
        // 金额低于风控阈值直通客户签名，再由人工核实签名，流程自然结束
        submit(id,request(id,"customer_sign","SUBMIT","{\"signature_image\":\"sig-1\",\"confirmed\":true}"));
        Task task=tasks.createTaskQuery().processInstanceId(id).singleResult();runtime.setVariable(id,"agent_session_id","agent-test");
        submit(id,agent(id,"signature_review","AUTHORIZE","{\"confirmed\":true}",task.getId()));
        assertNull(runtime.createProcessInstanceQuery().processInstanceId(id).singleResult());
        assertTrue(submit(id,collect));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM workflow_outbox WHERE event_id=?",Integer.class,id+":terminal"));
        collect.payload="{\"amount\":200,\"payee\":\"李四\",\"payee_account\":\"6222020200001234\"}";
        assertThrows(ResponseStatusException.class,()->submit(id,collect));
    }

    @Test void invalidTaskNeverTriggersAnotherWaitingNode(){
        String id=start("advanced_sop");NodeSubmission request=request(id,"ai_collect","SUBMIT","{\"amount\":100,\"payee\":\"客户\",\"payee_account\":\"6222020200001234\"}");request.taskId="missing-task";
        assertThrows(ResponseStatusException.class,()->submit(id,request));assertEquals(Collections.singletonList("ai_collect"),runtime.getActiveActivityIds(id));assertTrue(calls.isEmpty());
    }

    @Test void rejectDecisionInjectionAndUnknownActions(){
        String id=start("advanced_sop");
        assertThrows(ResponseStatusException.class,()->submit(id,request(id,"ai_collect","SUBMIT","{\"amount\":100,\"payee\":\"客户\",\"payee_account\":\"6222020200001234\",\"needHuman\":false}")));
        assertThrows(ResponseStatusException.class,()->submit(id,request(id,"ai_collect","AUTHORIZE","{\"amount\":100,\"payee\":\"客户\",\"payee_account\":\"6222020200001234\"}")));
        assertTrue(calls.isEmpty());
    }

    @Test void genericTransferToHumanNeedsNoTransferFields(){
        String id=start("advanced_sop");submit(id,request(id,"ai_collect","REQUEST_HUMAN","{}"));
        assertTrue(runtime.getActiveActivityIds(id).contains("human_auth"));assertTrue(calls.isEmpty());
    }

    @Test void missingCustomerIdentityCannotSkipHumanReception() {
        String id=start("advanced_sop"); runtime.removeVariable(id,"customer_id");
        assertThrows(IllegalStateException.class,()->submit(id,request(id,"ai_collect","REQUEST_HUMAN","{}")));
        assertTrue(runtime.getActiveActivityIds(id).contains("ai_collect"));
    }

    @Test void emptyReceptionReceiptCannotAdvanceWorkflow() {
        String id=start("advanced_sop");
        when(http.postForObject(eq("http://business.test/internal/reception/transition"),any(),eq(Map.class))).thenReturn(null);
        assertThrows(IllegalStateException.class,()->submit(id,request(id,"ai_collect","REQUEST_HUMAN","{}")));
        assertTrue(runtime.getActiveActivityIds(id).contains("ai_collect"));
    }

    // 低风险金额不触发人工授权（改造方案 §3.1），但仍必经人工签名核实。
    @Test void lowRiskAmountSkipsHumanAuthorization(){
        String id=start("advanced_sop");submit(id,request(id,"ai_collect","SUBMIT","{\"amount\":100,\"payee\":\"客户\",\"payee_account\":\"6222020200001234\"}"));
        assertTrue(runtime.getActiveActivityIds(id).contains("customer_sign"));
        assertEquals(1,calls.size());assertEquals("risk.evaluate.v1",calls.get(0).get("code"));
    }

    @Test void serviceFailureRollsBackSubmissionInsteadOfReportingSuccess(){
        String id=start("advanced_sop");
        doThrow(new IllegalStateException("接口不可用")).when(http).postForObject(anyString(),any(),eq(Map.class),eq("risk.evaluate.v1"));
        try {
            NodeSubmission request=request(id,"ai_collect","SUBMIT","{\"amount\":100,\"payee\":\"客户\",\"payee_account\":\"6222020200001234\"}");
            assertThrows(RuntimeException.class,()->submit(id,request));
            assertTrue(runtime.getActiveActivityIds(id).contains("ai_collect"));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM node_submission WHERE process_id=?",Integer.class,id));
            assertNull(runtime.getVariable(id,"results"));
        } finally { reset(http); }
    }

    @Test void bpmnMetaReaderReadsExtensionElements(){
        String id=start("advanced_sop");
        String definitionId = runtime.createProcessInstanceQuery().processInstanceId(id).singleResult().getProcessDefinitionId();

        // 验证 ai_collect 节点的 extension elements
        Map<String, String> meta = bpmnMeta.nodeMeta(definitionId, "ai_collect");
        assertEquals("CUSTOMER,AGENT", meta.get("actors"));
        assertEquals("SUBMIT,REQUEST_HUMAN", meta.get("actionCode"));

        // 验证 isActorAllowed
        assertTrue(bpmnMeta.isActorAllowed(definitionId, "ai_collect", "CUSTOMER"));
        assertTrue(bpmnMeta.isActorAllowed(definitionId, "ai_collect", "AGENT"));
        assertFalse(bpmnMeta.isActorAllowed(definitionId, "ai_collect", "OTHER"));

        // 验证 isActionAllowed
        assertTrue(bpmnMeta.isActionAllowed(definitionId, "ai_collect", "SUBMIT"));
        assertFalse(bpmnMeta.isActionAllowed(definitionId, "ai_collect", "AUTHORIZE"));
    }
}

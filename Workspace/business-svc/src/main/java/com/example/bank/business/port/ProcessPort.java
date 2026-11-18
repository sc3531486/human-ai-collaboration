package com.example.bank.business.port;

import java.util.List;
import java.util.Map;

/**
 * 出站端口：流程引擎的操作面。
 *
 * <p>由流程引擎服务实现。本服务<b>不持有流程数据库、不认识 BPMN</b>，
 * 所有"启动流程、读写变量、推进节点、完成待办"都要经这里出站。</p>
 *
 * <p>接口形状里的操作都是<b>有业务含义的</b>——"在收款确认节点推进"而不是"触发某个执行 ID"。
 * 引擎原语（执行查询、触发器）留在对端，本服务不需要为一次节点推进记住两步顺序。</p>
 *
 * <p><b>失败默认向上传播</b>：流程没能推进就是业务没发生，吞掉会让调用方对着一个
 * 成功响应报告一件并未发生的事。确实可以忽略的场景（如流程已结束时的变量补写）
 * 由调用方自己 catch，判断权留在知道业务含义的那一层。</p>
 */
public interface ProcessPort {

    /**
     * 启动流程实例。
     *
     * @param businessKey 为空时按无业务主键启动，与拆分前两种启动方式并存的行为一致
     * @return 流程实例标识
     */
    String start(String processDefinitionKey, String businessKey, Map<String, Object> variables);

    /** 实例是否仍在运行。进线时"该新建还是该更新流程"据此判定。 */
    boolean isRunning(String processId);

    /** 读取整张流程变量表。实例不存在或已结束时抛异常。 */
    Map<String, Object> variables(String processId);

    /** 写入流程变量。实例不存在或已结束时抛异常。 */
    void setVariables(String processId, Map<String, Object> variables);

    /** 挂起实例。实例不存在或已结束抛异常。 */
    void suspend(String processId);

    /** 恢复实例。已处于活动状态时按成功处理。 */
    void activate(String processId);

    /** 全部运行中实例标识。环境复位要"先收口工单、再删实例"，因此需要先拿到名单。 */
    List<String> runningProcessIds();

    /** 环境复位：删除全部运行中实例，返回 {@code {closed, processIds}}。 */
    Map<String, Object> reset();

    /**
     * 节点快照，供页面轮询。
     *
     * <p>本方法同时是<b>节点推送的登记入口</b>：流程引擎服务在被轮询时才会把该实例
     * 纳入推送跟踪。业务侧只读变量不读快照的话，实时事件永远不会产生。</p>
     */
    Map<String, Object> state(String processId);

    /** 节点快照，实例不存在时返回 {@code null}。页面拿着过期标识轮询属于正常路径，不是错误。 */
    Map<String, Object> stateOrNull(String processId);

    /** 流程轨迹：按历史节点回放结构化载荷，供坐席端还原当时界面。 */
    Map<String, Object> trail(String processId);

    /**
     * 场景配置全量：节点页面名、视图、提交动作、字段与参与者策略。
     *
     * <p><b>原样转发，本服务不解析</b>：场景是流程定义的元数据，与 BPMN 同源。
     * 在这里镜像一份 {@code Scene} 结构，就会多出一处"配置说支持某动作、
     * 流程其实没这个节点"的分叉点，而分叉只表现为提交被拒，不报错。</p>
     */
    List<Map<String, Object>> scenes();

    /** 某流程实例的节点提交历史：已提交过哪些动作、由谁、提交了什么。 */
    List<Map<String, Object>> submissions(String processId);

    /**
     * 节点提交：幂等检查、节点与归属校验、字段校验、登记、场景钩子、流程推进
     * 在对端<b>同一个事务</b>里完成。
     *
     * <p>提交内容原样透传（{@code requestId/nodeId/taskId/action/agentId/clientId/values}），
     * 本服务不逐字段解析 —— 字段白名单来自场景配置，那份配置在对端。</p>
     *
     * @return 是否命中幂等：同一 {@code requestId} 重复提交返回 {@code true} 且不重复推进。
     *         它是"这次是重试"的事实，不是失败
     */
    boolean submit(String processId, Map<String, Object> submission);
}

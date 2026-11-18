package com.example.bank.workflow.sop;

import com.example.bank.workflow.framework.*;
import org.flowable.engine.*;
import org.flowable.engine.history.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** 按活动实例读取已保存的快照，不用最新变量替代过去的现场。 */
@Service
public class TrailService {
    private final HistoryService history;
    private final RuntimeService runtime;
    private final BpmnMetaReader bpmnMeta;
    private final NodeSubmissionService submissions;
    private final FlowData data;
    public TrailService(HistoryService history, RuntimeService runtime, BpmnMetaReader bpmnMeta, NodeSubmissionService submissions, FlowData data) {
        this.history=history; this.runtime=runtime; this.bpmnMeta=bpmnMeta; this.submissions=submissions; this.data=data;
    }
    public Map<String,Object> restore(String processId) {
        HistoricProcessInstance process = history.createHistoricProcessInstanceQuery().processInstanceId(processId).singleResult();
        if (process == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"流程实例不存在");
        List<Map<String,Object>> saved = submissions.visits(processId);
        Map<String,Integer> occurrences = new HashMap<>();
        List<Map<String,Object>> trail = new ArrayList<>();
        List<String> active = runtime.createProcessInstanceQuery().processInstanceId(processId).singleResult() == null
                ? Collections.emptyList() : runtime.getActiveActivityIds(processId);
        for (HistoricActivityInstance activity : history.createHistoricActivityInstanceQuery().processInstanceId(processId)
                .orderByHistoricActivityInstanceStartTime().asc().list()) {
            if (activity.getActivityName() == null || activity.getActivityType().contains("Gateway")) continue;
            String key = activity.getActivityId()+":"+activity.getExecutionId();
            int occurrence = occurrences.merge(key,1,Integer::sum);
            List<Map<String,Object>> matches = new ArrayList<>();
            Map<String,Object> snapshot = Collections.emptyMap();
            for (Map<String,Object> row : saved) {
                if (activity.getId().equals(row.get("act_inst_id")) || activity.getTaskId() != null && activity.getTaskId().equals(row.get("task_id"))) { snapshot=row; break; }
                if (activity.getActivityId().equals(row.get("node_id")) && activity.getExecutionId().equals(row.get("execution_id"))) matches.add(row);
            }
            // 自动节点在委托执行时尚无历史活动 ID，同执行分支内按访问序号关联。
            if (snapshot.isEmpty() && occurrence <= matches.size()) snapshot=matches.get(occurrence-1);
            Map<String,Object> values = data.object(snapshot.get("submitted_values"));
            // 不再从 SceneRegistry 读取 fields，直接返回提交的值
            List<Map<String,Object>> fields = new ArrayList<>();
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("nodeId",activity.getActivityId()); row.put("activityInstanceId",activity.getId());
            row.put("nodeName",activity.getActivityName()); row.put("completed",activity.getEndTime()!=null);
            row.put("active",activity.getEndTime()==null && active.contains(activity.getActivityId()));
            row.put("startedAt",activity.getStartTime()==null?null:activity.getStartTime().getTime());
            row.put("endedAt",activity.getEndTime()==null?null:activity.getEndTime().getTime());
            row.put("pageName",activity.getActivityName());
            row.put("title",activity.getActivityName());
            row.put("view","service"); row.put("fields",fields);
            row.put("payload",values); row.put("actor",snapshot.get("actor_id")); row.put("submittedAt",snapshot.get("submitted_at"));
            row.put("schemaVersion",snapshot.get("schema_version")); row.put("source",snapshot.isEmpty()?"UNAVAILABLE":"SUBMISSION");
            trail.add(row);
        }
        Map<String,Object> result = new LinkedHashMap<>(); result.put("success",true); result.put("processId",processId);
        result.put("processDefinitionKey",process.getProcessDefinitionKey()); result.put("sceneVersion","1");
        result.put("currentNodeId",active.size()==1?active.get(0):""); result.put("trail",trail);
        return result;
    }
}

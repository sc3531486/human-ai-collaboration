package com.example.bank.workflow.framework;

import java.util.Map;

/** 公共提交信封；旧别名仅作兼容，不能与 payload 同时提交。 */
public class NodeSubmission {
    public String requestId, nodeId, taskId, executionId, action, agentId, clientId;
    public Long expectedRevision;
    public Object payload;
    public Map<String, Object> values;
    public Object formData;

    public Map<String, Object> resolveValues(FlowData data) {
        int count = (payload == null ? 0 : 1) + (values == null ? 0 : 1) + (formData == null ? 0 : 1);
        if (count > 1) throw new IllegalArgumentException("payload、values、formData 只能提供一个");
        return data.object(payload != null ? payload : values != null ? values : formData);
    }
}

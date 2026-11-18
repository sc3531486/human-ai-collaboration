import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { computed, ref } from 'vue';

function reviewHarness(values) {
    const source = readFileSync(new URL('../src/scenarios/transfer/agent/TransferSignatureReview.vue', import.meta.url), 'utf8');
    const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*;\r?$/gm, '');
    const bpmn = readFileSync(new URL('../../workflow-svc/src/main/resources/processes/AdvancedSOP.bpmn20.xml', import.meta.url), 'utf8');
    const node = bpmn.match(/<userTask id="signature_review"[\s\S]*?<\/userTask>/)[0];
    const fields = JSON.parse(node.match(/<!\[CDATA\[(\{"view":"signature-review"[\s\S]*?\})\]\]>/)[1]).fields;
    const state = { processId: 'p1', nodePolicy: { fields }, vars: {} };
    const sent = [];
    const sandbox = { computed, ref, defineProps: () => ({ state }), money: String,
        submitNode: async (snapshot, payload, options) => {
            const allowed = fields.map(field => field.name);
            for (const key of Object.keys(payload)) assert.ok(allowed.includes(key), `当前节点不允许提交字段：${key}`);
            sent.push({ snapshot, payload, options });
        },
    };
    runInNewContext(script + '\nglobalThis.reject = onReject; globalThis.form = formRef;', sandbox);
    sandbox.form.value = { read: () => values };
    return { sandbox, sent, state };
}

test('签名退回只提交 BPMN 允许字段，并明确取消确认', async () => {
    const { sandbox, sent, state } = reviewHarness({ confirmed: true, review_reason: ' 签名不符 ' });
    await sandbox.reject();
    assert.equal(sent.length, 1);
    assert.equal(sent[0].snapshot, state);
    assert.equal(sent[0].options.action, 'REJECT');
    assert.equal(sent[0].payload.confirmed, false);
    assert.equal(sent[0].payload.review_reason, '签名不符');
    assert.equal(Object.hasOwn(sent[0].payload, 'review_confirmed'), false);
});

test('未填写退回原因不能提交签名退回', async () => {
    const { sandbox, sent } = reviewHarness({ confirmed: false, review_reason: ' ' });
    await assert.rejects(sandbox.reject(), /请填写退回原因/);
    assert.equal(sent.length, 0);
});

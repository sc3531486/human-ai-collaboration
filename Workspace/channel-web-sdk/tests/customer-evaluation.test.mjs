import test from 'node:test';
import assert from 'node:assert/strict';
import { createEvaluation } from '../src/app/evaluation.js';

test('客户主动挂断不打开坐席小结或评价界面', async () => {
    const evaluation = createEvaluation({}, {});
    await assert.doesNotReject(() => evaluation.show({ callId: 'call1', initiatorRole: 'CUSTOMER' }));
    evaluation.destroy();
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { createWaitTone } from '../src/app/wait-tone.js';

test('人工未接听时播放等待音，入房通知到达立即停止，重复快照不重新播放', async () => {
    const previous = globalThis.window;
    let played = 0;
    class Audio {
        state = 'running'; currentTime = 0; destination = {};
        createOscillator() { return { frequency: {}, connect() {}, disconnect() {}, start() { played++; }, stop() {} }; }
        createGain() { return { gain: { setValueAtTime() {}, linearRampToValueAtTime() {} }, connect() {}, disconnect() {} }; }
    }
    globalThis.window = { AudioContext: Audio };
    const refs = { waitPanel: { hidden: true }, waitResume: { hidden: true } };
    const tone = createWaitTone(refs);
    const state = { processStatus: 'RUNNING', nodeName: '人工授权', vars: {} };
    try {
        await tone.prime();
        tone.sync(state, false);
        assert.equal(refs.waitPanel.hidden, false);
        assert.ok(played > 1);
        tone.sync(state, true);
        assert.equal(refs.waitPanel.hidden, true);
        const stoppedAt = played;
        tone.sync({ ...state, vars: { agent_session_id: 'agent-1' } }, true);
        assert.equal(played, stoppedAt);
    } finally { tone.stop(); globalThis.window = previous; }
});

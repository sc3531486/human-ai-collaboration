import test from 'node:test';
import assert from 'node:assert/strict';
import { configure, teardown } from '../src/core/video-widget.js';
import { createVideoSession } from '../src/app/video-session.js';

test('渠道接入使用无工具栏的悬浮控件，不采集桌面', async () => {
    let mountedOptions;
    let opened;
    let connected;
    const module = {
        mount: (_container, options) => { mountedOptions = options; return {}; },
        destroy() {},
        video: {
            open: options => { opened = options; },
            connect: options => { connected = options; },
            close() {}, takeover() {},
        },
    };
    configure({ widgetModule: module });
    const refs = { widgetSlot: { style: {} }, pip: { style: {} } };
    const session = createVideoSession(refs, {
        consent: { consented: true }, media: { ensureLocalStream: async () => ({}) },
        getLocalPeerId: () => 'cust-1', getPeerId: () => 'agent-1', getProcessId: () => 'p1',
    });
    try {
        assert.equal(await session.engage({ incomingOnly: true }), true);
        assert.equal(mountedOptions.toolbar, false);
        assert.equal(opened.mode, 'overlay');
        assert.equal(opened.draggable, true);
        assert.ok(opened.layout.width < 1);
        assert.equal(connected.desktop, false);
        assert.equal(connected.incomingOnly, true);
    } finally { session.release(); teardown(); }
});

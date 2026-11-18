/* 内核事件总线：替代 navis-web 里散落的 window CustomEvent（契约 §1）。 */

const channels = new Map();

function listeners(name) {
    if (!channels.has(name)) channels.set(name, new Set());
    return channels.get(name);
}

export const bus = {
    on(name, handler) {
        listeners(name).add(handler);
        return () => bus.off(name, handler);
    },
    once(name, handler) {
        const off = bus.on(name, (payload) => {
            off();
            handler(payload);
        });
        return off;
    },
    off(name, handler) {
        listeners(name).delete(handler);
    },
    emit(name, payload) {
        const snapshot = Array.from(listeners(name));
        for (let i = 0; i < snapshot.length; i++) {
            try {
                snapshot[i](payload);
            } catch (error) {
                console.warn(`[bus] ${name} 处理异常:`, error);
            }
        }
    },
    clear(name) {
        if (name) channels.delete(name);
        else channels.clear();
    },
};

/** 内核事件名常量：避免各处手写字符串。 */
export const EVENTS = Object.freeze({
    SUBMIT_START: 'bank:submit-start',
    SUBMIT_SUCCESS: 'bank:submit-success',
    SUBMIT_ERROR: 'bank:submit-error',
    STATE: 'flow:state',
    ROUTED: 'flow:routed',
    UNMATCHED: 'flow:route-unmatched',
    COMPLETED: 'flow:completed',
    CONNECTION: 'realtime:connection',
    CHAT: 'chat:message',
    STAGE_AVATAR: 'stage:avatar-state',
    STAGE_BADGE: 'stage:badge',
    WAIT_TONE: 'wait-tone:state',
    TOAST: 'ui:toast',
});

export default bus;

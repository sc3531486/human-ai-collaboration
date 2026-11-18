/*
 * 场景页辅助工具：让场景包一行代码即可「叠加定制」到通用兜底之上。
 * 场景页契约（契约 §2）：mount(panel, state, ctx) → 释放函数 | false（不接管）。
 */
import GenericNode from './GenericNode.js';

/**
 * 场景可保留自己的组件入口，但通用节点表单不再追加装饰性提示横幅。
 * 节点页面只展示当前节点需要的业务要素；说明、字段、提交动作均由 nodePolicy 决定。
 *
 * GenericNode 声明不接管（返回 false）时本页同样不接管，交回路由继续兜底。
 */
export function withGenericHint(_hint) {
    return {
        async mount(panel, state, ctx) {
            const release = await GenericNode.mount(panel, state, ctx);
            if (release === false) return false;
            return typeof release === 'function' ? release : () => {};
        },
    };
}

export default { withGenericHint };

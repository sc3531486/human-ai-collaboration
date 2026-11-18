<script setup>
/*
 * 宿主能力页：逐域开关 + 调用日志。
 * 开关 = 渠道方「是否实现该原生能力」的替身：
 *   打开 → SDK 走宿主桥（原生路径）；关闭 → 桥中缺该域，SDK 走浏览器兜底。
 * 挂载/重挂载「远程视频银行」页后生效。
 */
import { DOMAINS, capabilityState, toggle } from '../core/bridge-state.js';
import { bridgeLog, clearBridgeLog } from '../core/bridge-log.js';
import { runCapabilityDemo } from '../core/capability-demo.js';

const enabledCount = DOMAINS.filter((d) => capabilityState.enabled[d.id] !== false).length;

function onChange(id, event) {
    toggle(id, event.target.checked);
}

async function runDemo() {
    await runCapabilityDemo();
}

function goBank() {
    window.location.hash = '#/bank';
}
</script>

<template>
    <div>
        <div class="notice">
            逐域开关模拟「渠道方是否已实现该原生能力」。<strong>打开</strong>：能力调用经 <code>window.VBBankingBridge</code> / <code>config.bridge</code> 走宿主原生路径；
            <strong>关闭</strong>：桥中该域缺省，SDK 按契约 §6.2 自动走浏览器兜底。开关改动后需回到「远程视频银行」页重新挂载。
        </div>

        <div class="host-card">
            <h2>宿主能力矩阵（JSBridge 契约 §6.2 / contract.md）
                <span style="font-size: 12px; color: #64748b; font-weight: 400;">已启用 {{ enabledCount }}/{{ DOMAINS.length }}</span>
            </h2>
            <table class="cap-table">
                <thead>
                    <tr><th>能力域</th><th>说明</th><th>宿主已实现</th></tr>
                </thead>
                <tbody>
                    <tr v-for="domain in DOMAINS" :key="domain.id">
                        <td><strong>{{ domain.name }}</strong><br><code>{{ domain.id }}</code></td>
                        <td>{{ domain.desc }}</td>
                        <td class="switch-cell">
                            <label class="switch">
                                <input
                                    type="checkbox"
                                    :checked="capabilityState.enabled[domain.id] !== false"
                                    @change="onChange(domain.id, $event)"
                                >
                                <span class="slider"></span>
                            </label>
                        </td>
                    </tr>
                </tbody>
            </table>
            <p style="margin-top: 14px">
                <button class="btn" @click="runDemo">运行能力自检（逐域调用并记录路径）</button>
                <button class="btn secondary" @click="goBank" style="margin-left: 8px;">去挂载 SDK</button>
            </p>
        </div>

        <div class="host-card">
            <h2>调用日志
                <span style="font-size: 12px; color: #64748b; font-weight: 400;">
                    native = 宿主原生路径 · browser = 浏览器兜底
                </span>
            </h2>
            <p v-if="!bridgeLog.entries.length">暂无调用记录。点击上方「运行能力自检」，或在 SDK 容器内操作按钮触发。</p>
            <ul class="log-list" v-else>
                <li v-for="entry in bridgeLog.entries" :key="entry.id">
                    <span class="t">{{ entry.at }}</span>
                    <span class="d">{{ entry.domain }}</span>
                    <span class="m">{{ entry.method }}</span>
                    <span class="log-path" :class="entry.path">{{ entry.path === 'native' ? '宿主原生' : '浏览器兜底' }}</span>
                    <span class="detail">{{ entry.detail }}</span>
                </li>
            </ul>
            <p v-if="bridgeLog.entries.length" style="margin-top: 10px">
                <button class="btn secondary" @click="clearBridgeLog">清空日志</button>
            </p>
        </div>
    </div>
</template>

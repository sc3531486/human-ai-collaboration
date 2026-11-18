<template>
    <!--
      由 nodePolicy.fields 驱动的通用表单（契约 §1 ui/）。
      字段类型、必填、pattern、草稿保存与提交按钮全部来自后端协议，场景无需重复处理。
    -->
    <form class="vb-policy-form" @submit.prevent="onSubmit">
        <div class="form-grid">
            <template v-for="field in fields" :key="field.name">
                <input v-if="field.type === 'hidden'" type="hidden" :name="field.name" :value="value(field)">
                <label v-else-if="field.type === 'textarea'" class="form-field full">
                    {{ field.label }}
                    <textarea :name="field.name" rows="3" :required="field.required" :maxlength="field.maxLength"
                        :placeholder="field.placeholder || ''" :value="value(field)" @input="write(field, $event.target.value)"></textarea>
                </label>
                <label v-else-if="field.type === 'checkbox'" class="confirm-check">
                    <input type="checkbox" :name="field.name" :checked="Boolean(values[field.name])"
                        :required="field.required" @change="write(field, $event.target.checked)">
                    {{ field.label }}
                </label>
                <label v-else class="form-field">
                    {{ field.label }}
                    <input :name="field.name" :type="field.type === 'number' ? 'number' : 'text'"
                        :required="field.required" :maxlength="field.maxLength" :pattern="field.pattern"
                        :min="field.min" :max="field.max" :step="stepOf(field)"
                        :inputmode="field.type === 'number' ? 'decimal' : undefined"
                        :placeholder="field.placeholder || ''" :value="textOf(field)" @input="write(field, $event.target.value)">
                </label>
            </template>
        </div>
        <div class="node-actions">
            <span>草稿仅保存在当前浏览器会话</span>
            <slot name="actions" />
            <button class="primary-button" type="submit" :disabled="busy">
                {{ actionLabel }}<vb-icon name="arrow" :size="17" />
            </button>
        </div>
    </form>
</template>

<script setup>
import { reactive, ref, watch } from 'vue';
import VbIcon from './VbIcon.vue';
import { report } from '@/core/bus.js';

const props = defineProps({
    state: { type: Object, required: true },
    fields: { type: Array, default: () => [] },
    actionLabel: { type: String, default: '提交' },
    disabled: { type: Boolean, default: false }
});
const emit = defineEmits(['submit']);

const busy = ref(false);
const values = reactive({});

/** 初始值＝流程变量 + 会话草稿；数字 0 显示为空白（与 bank-form.js 同口径）。 */
function seed() {
    Object.keys(values).forEach(key => delete values[key]);
    let draft = {};
    const key = props.state?.processId && props.state?.nodeId ? `bank.draft.${props.state.processId}.${props.state.nodeId}` : '';
    if (key) {
        try { draft = JSON.parse(sessionStorage.getItem(key) || '{}'); }
        catch (error) {
            // 草稿损坏/存储不可用：不许静默丢弃坐席已填内容——上报后才退化为仅用流程变量。
            draft = {};
            report('表单草稿解析', error, { sticky: true });
        }
    }
    const source = { ...(props.state?.vars || {}), ...draft };
    props.fields.forEach(field => {
        values[field.name] = source[field.name] ?? (field.type === 'checkbox' ? false : '');
    });
    draftKey = key;
}
let draftKey = '';
watch(() => [props.fields, props.state?.nodeId, props.state?.processId], seed, { immediate: true, deep: false });

function value(field) {
    return values[field.name] ?? '';
}
/** 0 金额不显示为 "0"，保持与基线一致的空输入体验。 */
function textOf(field) {
    const raw = values[field.name];
    if (field.type === 'number' && Number(raw) === 0) return '';
    return raw ?? '';
}
const stepOf = field => String(1 / 10 ** (field.scale ?? 0));

function write(field, next) {
    if (props.disabled) return;
    values[field.name] = next;
    persist();
}
function persist() {
    if (!draftKey) return;
    try { sessionStorage.setItem(draftKey, JSON.stringify(values)); }
    catch (error) {
        // 草稿写不进（隐私模式/配额）：坐席内容不会丢（内存里还在），但"仅保存在会话"的
        // 承诺已失效——必须可见。sticky：每次击键都会调用，去重防刷屏。
        report('表单草稿保存', error, { sticky: true });
    }
}
function read() {
    return Object.fromEntries(props.fields.map(field => [field.name, values[field.name]]));
}
function clearDraft() {
    if (!draftKey) return;
    try { sessionStorage.removeItem(draftKey); }
    catch (error) { report('表单草稿清理', error, { sticky: true }); }
}

async function onSubmit() {
    if (props.disabled || busy.value) return;
    busy.value = true;
    try {
        // emit 返回 void，因此 beforeSubmit 的实际 await 发生在父组件的 submit 处理里；
        // 父组件抛出会向上冒泡，这里只负责复位忙碌态。
        emit('submit', read());
    } finally {
        busy.value = false;
    }
}

defineExpose({ read, clearDraft });
</script>

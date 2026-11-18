<template>
    <!--
      现场照片采集：替代 node_ui.js / witness.js 里 [data-photo] 的硬编码抓帧。
      画面一律经 §2.9 命令 API 的 capture() 取 Blob，本组件不接触任何 <video> 元素或流对象。
    -->
    <section class="vb-photo-capture">
        <div class="node-photo-stage">
            <img v-if="preview && !previewHidden" :src="preview" alt="现场照片预览">
            <span v-else>尚未拍照</span>
        </div>
        <p class="photo-hint" role="status">{{ status }}</p>
        <div class="photo-actions">
            <button type="button" class="teal-button" :disabled="disabled || busy" @click="shoot">
                <vb-icon name="camera" :size="16" />{{ photo ? '重新拍照' : '拍照' }}
            </button>
        </div>
    </section>
</template>

<script setup>
import { ref, onBeforeUnmount } from 'vue';
import { video } from '@/core/video-widget.js';
import { report } from '@/core/bus.js';

const props = defineProps({
    disabled: { type: Boolean, default: false },
    source: { type: String, default: 'remote' }   // remote=客户画面 | local=坐席画面
});
const emit = defineEmits(['captured']);

const busy = ref(false);
const photo = ref(null);          // Blob
const preview = ref('');
const previewHidden = ref(true);
const status = ref('点击拍照后在此预览，确认无误后继续。');

/** 取到的画面交给父组件去归档；本组件只负责采集与预览。 */
async function shoot() {
    if (busy.value) return;
    busy.value = true;
    try {
        const blob = await video.capture(props.source);
        // 没有画面时如实提示，不伪造占位图冒充采集成功（§2.9 / §3.4）。
        if (!blob) {
            status.value = props.source === 'remote' ? '尚未收到客户画面，无法拍照' : '尚未获取本地画面，无法截图';
            return;
        }
        releasePreview();
        photo.value = blob;
        preview.value = URL.createObjectURL(blob);
        previewHidden.value = false;
        status.value = '请核对照片。可重新拍照，确认后进入下一节点。';
        emit('captured', blob);
    } catch (error) {
        // 失败呈现面＝状态区（含错误码）+ report（console/system:error），不伪造采集结果。
        const code = error.code || (error.status ? `HTTP_${error.status}` : 'APP_ERROR');
        status.value = `${code} ${error.message}`;
        report('现场照片采集', error, { sticky: true });
    } finally {
        busy.value = false;
    }
}

function releasePreview() {
    if (preview.value) URL.revokeObjectURL(preview.value);
    preview.value = '';
    previewHidden.value = true;
}

/** 供父组件在提交前取用当前照片。 */
function current() { return photo.value; }
defineExpose({ current, reset: releasePreview });

onBeforeUnmount(releasePreview);
</script>

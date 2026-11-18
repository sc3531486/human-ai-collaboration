<template>
  <vb-node-frame label="密码重置" title="核实办理单与客户签字">
    <a v-if="formUrl" :href="formUrl" target="_blank" rel="noopener">查看本次业务办理单</a>
    <vb-evidence title="客户签字" :url="state.vars?.signature_image"/>
    <vb-policy-form v-if="!readOnly" ref="form" :state="state" :fields="state.nodePolicy?.fields || []" action-label="确认提交密码重置" @submit="approve"><template #actions><button type="button" @click="reject">退回客户重签</button></template></vb-policy-form>
  </vb-node-frame>
</template>
<script setup>
import {computed,ref} from 'vue';
import VbNodeFrame from '@/ui/VbNodeFrame.vue';import VbEvidence from '@/ui/VbEvidence.vue';import VbPolicyForm from '@/ui/VbPolicyForm.vue';
import {submitNode} from '@/core/flow/actions.js';import {report} from '@/core/bus.js';
const props=defineProps({state:{type:Object,required:true},readOnly:Boolean});const form=ref(null);
const formUrl=computed(()=>/^\/records\/[A-Za-z0-9_.-]+\.pdf$/.test(props.state.vars?.serviceFormUrl || '')?props.state.vars.serviceFormUrl:'');
async function approve(values){await submitNode(props.state,values);}
async function reject(){try{const reason=String(form.value?.read().review_reason || '').trim();if(!reason)throw new Error('请填写退回原因');await submitNode(props.state,{signature_review_confirmed:false,review_reason:reason},{action:'REJECT'});}catch(error){report('退回办理单签名',error);}}
</script>

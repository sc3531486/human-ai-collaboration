<template>
  <section>
    <password-identity :state="state" :read-only="true"/>
    <div v-if="!readOnly" class="password-review-actions">
      <template v-if="vars.onlinePassed !== true"><p role="alert">联网核查未通过，当前不能继续办理。</p></template>
      <button v-else-if="passed" class="primary" :disabled="busy" @click="submit('PROCEED')">确认核验结果，进入短信验证</button>
      <template v-else><label>授权原因<textarea v-model="reason" rows="3" maxlength="1000" placeholder="请填写申请独立授权的原因"/></label><button class="primary" :disabled="busy" @click="submit('REQUEST_AUTH')">申请其他坐席授权</button></template>
    </div>
  </section>
</template>
<script setup>
import {computed,ref} from 'vue';
import PasswordIdentity from './PasswordIdentity.vue';
import {submitNode} from '@/core/flow/actions.js';
import {report} from '@/core/bus.js';
const props=defineProps({state:{type:Object,required:true},readOnly:Boolean});const vars=computed(()=>props.state.vars||{});
const passed=computed(()=>Number.isFinite(Number(vars.value.faceScore))&&Number.isFinite(Number(vars.value.faceThreshold))&&Number(vars.value.faceScore)>=Number(vars.value.faceThreshold));
const reason=ref('');const busy=ref(false);
async function submit(action){busy.value=true;try{if(action==='REQUEST_AUTH'&&!reason.value.trim())throw new Error('请填写授权原因');await submitNode(props.state,{review_reason:reason.value.trim()},{action});}catch(error){report('核验结果处理',error);}finally{busy.value=false;}}
</script>
<style scoped>.password-review-actions{padding:20px;background:white;border-radius:12px;margin-top:16px}.password-review-actions label{display:block;margin-bottom:12px}.password-review-actions textarea{display:block;width:100%;box-sizing:border-box;padding:12px;border:1px solid #d4dfec;border-radius:8px;margin-top:8px;font:inherit}.primary{min-height:44px;border:0;border-radius:8px;background:#1769cc;color:white;padding:12px 20px;cursor:pointer}.primary:disabled{opacity:.5}</style>

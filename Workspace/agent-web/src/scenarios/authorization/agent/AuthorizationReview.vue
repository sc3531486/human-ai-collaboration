<template>
  <vb-node-frame label="统一授权" title="资料复核授权" :description="vars.authorizationReason">
    <vb-facts :rows="rows"/>
    <div class="authorization-photos"><vb-evidence title="身份证人像面" :url="snapshot.id_front_photo"/><vb-evidence title="身份证国徽面" :url="snapshot.id_back_photo"/><vb-evidence title="现场照片" :url="snapshot.face_photo"/></div>
    <vb-facts :rows="ocrRows"/>
    <vb-policy-form v-if="!readOnly" ref="form" :state="state" :fields="state.nodePolicy?.fields || []" action-label="授权通过" @submit="approve"><template #actions><button type="button" @click="reject">授权不通过</button></template></vb-policy-form>
  </vb-node-frame>
</template>
<script setup>
import {computed,ref} from 'vue';import VbNodeFrame from '@/ui/VbNodeFrame.vue';import VbFacts from '@/ui/VbFacts.vue';import VbEvidence from '@/ui/VbEvidence.vue';import VbPolicyForm from '@/ui/VbPolicyForm.vue';import {submitNode} from '@/core/flow/actions.js';import {report} from '@/core/bus.js';
const props=defineProps({state:{type:Object,required:true},readOnly:Boolean});const form=ref(null);const vars=computed(()=>props.state.vars||{});const snapshot=computed(()=>vars.value.authorizationSnapshot||{});
const rows=computed(()=>[['原业务流水',snapshot.value.biz_no],['客户姓名',snapshot.value.customer_name],['申请坐席',vars.value.authorizationRequester],['人脸比对分数',snapshot.value.faceScore],['当次通过阈值',snapshot.value.faceThreshold],['联网核查',snapshot.value.onlineResult?.message]]);
const ocrRows=computed(()=>[['识别姓名',snapshot.value.idFrontOcr?.name],['识别证件号码',snapshot.value.idFrontOcr?.certNoMasked],['签发机构',snapshot.value.idBackOcr?.issuer]]);
async function approve(values){await submitNode(props.state,values);}
async function reject(){try{const reason=String(form.value?.read().review_reason||'').trim();if(!reason)throw new Error('请填写授权不通过原因');await submitNode(props.state,{authorization_confirmed:false,review_reason:reason},{action:'REJECT'});}catch(error){report('授权审核',error);}}
</script>
<style scoped>.authorization-photos{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;margin:16px 0}@media(max-width:900px){.authorization-photos{grid-template-columns:1fr}}</style>

<template>
  <vb-node-frame label="密码重置" title="采集现场照片" description="核对客户证件，拍摄本次通话的客户现场照片。">
    <div class="password-photos"><vb-evidence title="身份证人像面" :url="vars.id_front_photo"/><vb-evidence title="身份证国徽面" :url="vars.id_back_photo"/></div>
    <vb-photo-capture v-if="!readOnly" @captured="photo = $event"/>
    <vb-evidence v-else title="现场照片" :url="vars.face_photo"/>
    <button v-if="!readOnly" class="primary-button" :disabled="busy || !photo" @click="submit">提交照片并核验</button>
  </vb-node-frame>
</template>
<script setup>
import {computed,ref} from 'vue';
import VbNodeFrame from '@/ui/VbNodeFrame.vue';
import VbEvidence from '@/ui/VbEvidence.vue';
import VbPhotoCapture from '@/ui/VbPhotoCapture.vue';
import {evidence} from '@/core/sdk.js';
import {identity} from '@/core/identity.js';
import {submitNode} from '@/core/flow/actions.js';
import {report} from '@/core/bus.js';
const props=defineProps({state:{type:Object,required:true},readOnly:Boolean});
const vars=computed(()=>props.state.vars||{});const photo=ref(null);const busy=ref(false);
async function submit(){busy.value=true;try{const saved=await evidence.photo(props.state,'witness_photo',photo.value,{agentId:identity.agentId});await submitNode(props.state,{face_photo:saved.fileUrl,evidence_receipt:saved.eventId});}catch(error){report('现场照片核验',error);}finally{busy.value=false;}}
</script>
<style scoped>.password-photos{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;margin-bottom:16px}.primary-button{min-height:44px;padding:10px 24px;background:#1769cc;color:white;border:0;border-radius:8px;margin-top:18px;cursor:pointer}.primary-button:disabled{opacity:.5}@media(max-width:700px){.password-photos{grid-template-columns:1fr}}</style>

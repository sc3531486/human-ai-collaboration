import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { deflateSync } from 'node:zlib';
import { Centrifuge } from '../agent-web/node_modules/centrifuge/build/index.mjs';

// 本机演示接口验证；生成合成照片，不读取真实客户图片或更改阈值配置。
const base='http://127.0.0.1:8090';
const clients=[];const events=[];let state,call,agent,reviewer;
async function api(path,body) {
    const response=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:body instanceof FormData?{}:{'Content-Type':'application/json'},body:body===undefined?undefined:body instanceof FormData?body:JSON.stringify(body)});
    const text=await response.text();let result;try{result=text?JSON.parse(text):{};}catch{throw new Error(`${path}: HTTP ${response.status}`);}
    if(!response.ok)throw new Error(`${path}: ${response.status} ${result.message||result.error||text.slice(0,200)}`);
    return result;
}
async function subscribe(role,subject) {
    const user=await api('/api/media/users',{role,subject});
    const client=new Centrifuge('ws://127.0.0.1:8000/connection/websocket',{token:user.token,websocket:WebSocket});clients.push(client);
    const sub=client.newSubscription(user.channel);sub.on('publication',ctx=>events.push({...ctx.data,recipientRole:role,recipientSubject:subject}));
    const ready=new Promise((resolve,reject)=>{sub.on('subscribed',resolve);sub.on('error',ctx=>reject(new Error(ctx.error.message)));setTimeout(()=>reject(new Error('信令订阅超时')),10000).unref();});
    sub.subscribe();client.connect();await ready;return user;
}
function png() {
    const width=180,height=90,raw=Buffer.alloc((width*4+1)*height,255);
    for(let y=0;y<height;y++){raw[y*(width*4+1)]=0;for(let x=0;x<width;x++)if(x>20&&x<160&&Math.abs(y-(40+Math.sin(x/10)*12))<4){const p=y*(width*4+1)+1+x*4;raw[p]=raw[p+1]=raw[p+2]=20;}}
    function crc(buffer){let value=0xffffffff;for(const byte of buffer){value^=byte;for(let i=0;i<8;i++)value=(value>>>1)^((value&1)?0xedb88320:0);}return(value^0xffffffff)>>>0;}
    function chunk(type,data){const typeBytes=Buffer.from(type),size=Buffer.alloc(4),check=Buffer.alloc(4);size.writeUInt32BE(data.length);check.writeUInt32BE(crc(Buffer.concat([typeBytes,data])));return Buffer.concat([size,typeBytes,data,check]);}
    const ihdr=Buffer.alloc(13);ihdr.writeUInt32BE(width);ihdr.writeUInt32BE(height,4);ihdr[8]=8;ihdr[9]=6;
    return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ihdr),chunk('IDAT',deflateSync(raw)),chunk('IEND',Buffer.alloc(0))]);
}
async function photo(field,role,user) {
    const body=new FormData();body.append('file',new Blob([png()],{type:'image/png'}),'synthetic.png');
    for(const [key,value] of Object.entries({fieldName:field,processId:state.processId,actorId:role==='AGENT'?user.subject:user.peerId,actorRole:role,eventId:randomUUID()}))body.append(key,value);
    return api('/api/evidence/photo',body);
}
async function submit(payload,role,user,action=state.nodePolicy.action) {
    state=await api(`/api/platform/process/${state.processId}/submit`,{nodeId:state.nodeId,taskId:state.taskId||null,executionId:state.executionId||null,expectedRevision:state.revision,requestId:randomUUID(),action,payload,...(role==='AGENT'?{agentId:user.subject}:{clientId:user.peerId})});
    console.log('节点:',state.nodeId||state.processStatus);
}
async function online(user,status='ONLINE') {return api(`/api/platform/agent/${user.subject}/status`,{peerId:user.peerId,status});}
try {
    const profile=await api('/api/customer/apply',{name:'API联调合成客户',certNo:'VERIFY-'+randomUUID(),mobile:'',channel:'API验证'});
    const customer=await subscribe('CUSTOMER',profile.customerId);
    const configured=await api('/api/platform/agent-profile');agent=await subscribe('AGENT',configured.agentId);await online(agent);
    const queued=await api('/api/customer/queue',{customerId:profile.customerId,peerId:customer.peerId,tradeCode:'200101',serviceMode:'DIGITAL',channel:'API验证'});state=queued.state;
    assert.equal(state.nodeId,'id_capture');const card=state.vars.cards[0].id;
    const front=await photo('id_front_photo','CUSTOMER',customer),back=await photo('id_back_photo','CUSTOMER',customer);
    await submit({selected_card_id:card,id_front_photo:front.fileUrl,id_back_photo:back.fileUrl,evidenceReceipts:{id_front_photo:front.eventId,id_back_photo:back.eventId}},'CUSTOMER',customer);
    state=await api(`/api/platform/call/${state.processId}/answer`,{agentId:agent.subject,peerId:agent.peerId});call=await api(`/api/calls/by-process/${state.processId}`);assert.equal(call.status,'OPEN');
    const face=await photo('witness_photo','AGENT',agent);await submit({face_photo:face.fileUrl,evidence_receipt:face.eventId},'AGENT',agent);
    if(Number(state.vars.faceScore)<Number(state.vars.faceThreshold)) {
        const agents=await api('/api/platform/agents');const other=agents.find(row=>row.agentId!==agent.subject&&row.skills.includes('AUTHORIZATION'));assert.ok(other,'需要另一名配置授权技能的坐席');
        reviewer=await subscribe('AGENT',other.agentId);await online(reviewer);
        const parentId=state.processId;await submit({review_reason:'接口验证：分数低于数据库阈值'},'AGENT',agent,'REQUEST_AUTH');
        let ringing;const timeout=Date.now()+10000;
        while(!ringing&&Date.now()<timeout){const summary=await api(`/api/platform/agent/${reviewer.subject}/calls`);ringing=summary.queue.ringing;if(!ringing)await new Promise(resolve=>setTimeout(resolve,100));}
        assert.ok(ringing?.processId,'独立授权工单分配到其他坐席');assert.notEqual(ringing.processId,parentId);
        state=await api(`/api/platform/call/${ringing.processId}/answer`,{agentId:reviewer.subject,peerId:reviewer.peerId});const memberCall=await api(`/api/calls/by-process/${state.processId}`);
        assert.equal(memberCall.roomId,call.roomId);assert.equal(memberCall.role,'AUTHORIZER');assert.equal(memberCall.participants.length,3);
        await submit({authorization_confirmed:true,review_reason:'合成资料复核通过'},'AGENT',reviewer,'AUTHORIZE');
        state=await api(`/api/platform/process/${parentId}`);assert.equal(state.nodeId,'sms_verify');assert.equal(state.vars.agent_session_id,agent.subject);
        console.log('PASS: 独立授权坐席加入原房间并回传结果');
    } else await submit({},'AGENT',agent,'PROCEED');
    await submit({sms_code:'000000'},'CUSTOMER',customer);assert.equal(state.nodeId,'sms_verify');
    const code=String(state.vars.smsDeliveryMessage).match(/[0-9]{6}/)?.[0];assert.ok(code);await submit({sms_code:code},'CUSTOMER',customer);assert.equal(state.nodeId,'customer_sign');
    assert.ok((await fetch(base+state.vars.serviceFormUrl)).ok,'办理单PDF可访问');
    const signature=await photo('signature_image','CUSTOMER',customer);await submit({signature_image:signature.fileUrl,service_form_confirmed:true,evidenceReceipts:{signature_image:signature.eventId}},'CUSTOMER',customer);
    await submit({signature_review_confirmed:true},'AGENT',agent,'AUTHORIZE');assert.equal(state.processStatus,'COMPLETED');assert.equal(state.vars.resetStatus,'SUCCESS');
    assert.ok((await fetch(base+state.vars.receiptPdfUrl)).ok,'回执PDF可访问');assert.equal((await api(`/api/calls/by-process/${state.processId}`)).status,'OPEN');
    await api('/api/evidence/chat',{processId:state.processId,actorRole:'CUSTOMER',actorId:customer.peerId,eventId:randomUUID(),text:'办结后通话中的测试消息'});
    await api(`/api/calls/${call.callId}/hangup`,{actorId:agent.subject,actorRole:'AGENT'});
    const deadline=Date.now()+10000;while(!events.some(e=>e.type==='CALL_ENDED'&&e.callId===call.callId&&e.recipientRole==='CUSTOMER')&&Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,100));
    assert.ok(events.some(e=>e.type==='VIDEO_ROOM_READY'&&e.callId===call.callId&&e.recipientRole==='CUSTOMER'),'客户收到入房信令');assert.ok(events.some(e=>e.type==='CALL_ENDED'&&e.callId===call.callId&&e.recipientRole==='CUSTOMER'),'客户收到挂断信令');
    const tags=await api('/api/evaluations/tags?stars=5');await api('/api/evaluations',{callId:call.callId,customerId:profile.customerId,stars:5,tagIds:[tags[0].id],comment:'自动化接口联调'});
    console.log('PASS: 证件→人工接听→核查→短信重试→办理单→签字→坐席确认→重置→PDF→保留通话→挂断信令→评价');
} catch(error) {
    console.error(error.message);process.exitCode=1;
} finally {
    if(call?.callId)try{await api(`/api/calls/${call.callId}/hangup`,{actorId:agent.subject,actorRole:'AGENT'});}catch(error){console.error('测试通话清理失败:',error.message);}
    if(state?.processId&&state.processStatus!=='COMPLETED')try{await api(`/api/platform/process/${state.processId}/close`,{});}catch(error){console.error('测试流程清理失败:',error.message);}
    if(agent)await online(agent,'OFFLINE').catch(error=>console.error(error.message));if(reviewer)await online(reviewer,'OFFLINE').catch(error=>console.error(error.message));clients.forEach(client=>client.disconnect());
}

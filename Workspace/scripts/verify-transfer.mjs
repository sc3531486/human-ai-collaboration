import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { deflateSync } from 'node:zlib';
import { Centrifuge } from '../agent-web/node_modules/centrifuge/build/index.mjs';

// 转账汇款（advanced_sop）人机协同全流程接口验证。
// 人机分工断言：信息采集/客户签名由客户提交（数字人接待节点），
// 人工授权/人工核签由坐席提交（关键人工节点），风控/签字单/核心记账/回执/通知由系统自动执行（SYSTEM）。
// 覆盖：低风险数字人直通 + 高风险人工授权 + 核签退回重签保留原坐席。
// 照片全部为合成 PNG，不读取真实客户图片。
const base='http://127.0.0.1:8090';
const clients=[];const events=[];const agentsOnline=[];
let agent;const agentPeers={};
const sessions={}; // agentId → {cookie, csrfToken}
async function api(path,body,agentId) {
    const headers=body instanceof FormData?{}:{'Content-Type':'application/json'};
    if(agentId&&sessions[agentId]){headers['Cookie']=sessions[agentId].cookie;if(body!==undefined)headers['X-CSRF-Token']=sessions[agentId].csrfToken;}
    const response=await fetch(base+path,{method:body===undefined?'GET':'POST',headers,body:body===undefined?undefined:body instanceof FormData?body:JSON.stringify(body)});
    const text=await response.text();let result;try{result=text?JSON.parse(text):{};}catch{throw new Error(`${path}: HTTP ${response.status}`);}
    if(!response.ok)throw new Error(`${path}: ${response.status} ${result.message||result.error||text.slice(0,200)}`);
    return result;
}
async function login(username,password) {
    const response=await fetch(base+'/api/platform/agent-login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username,password})});
    const result=await response.json();if(!response.ok)throw new Error(`agent-login: ${response.status} ${result.message||''}`);
    const cookie=(response.headers.get('set-cookie')||'').split(';')[0];if(!cookie)throw new Error('agent-login: 未返回会话Cookie');
    sessions[result.agentId]={cookie,csrfToken:result.csrfToken};return result;
}
async function subscribe(role,subject) {
    const user=await api('/api/media/users',{role,subject},role==='AGENT'?subject:undefined);
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
async function photo(field,role,user,processId) {
    const body=new FormData();body.append('file',new Blob([png()],{type:'image/png'}),'synthetic.png');
    for(const [key,value] of Object.entries({fieldName:field,processId,actorId:role==='AGENT'?user.subject:user.peerId,actorRole:role,eventId:randomUUID()}))body.append(key,value);
    return api('/api/evidence/photo',body);
}
async function submit(state,payload,role,user,action) {
    return api(`/api/platform/process/${state.processId}/submit`,{nodeId:state.nodeId,taskId:state.taskId||null,executionId:state.executionId||null,expectedRevision:state.revision,requestId:randomUUID(),action,payload,...(role==='AGENT'?{agentId:user.subject}:{clientId:user.peerId})},role==='AGENT'?user.subject:undefined);
}
async function ring(agentUser,processId) {
    const deadline=Date.now()+15000;
    while(Date.now()<deadline){const summary=await api(`/api/platform/agent/${agentUser.subject}/calls`,undefined,agentUser.subject);const ticket=summary.queue.ringing;if(ticket&&ticket.processId===processId)return ticket;await new Promise(resolve=>setTimeout(resolve,150));}
    throw new Error(`坐席未在时限内收到振铃 processId=${processId}`);
}
// 上电复位：上一轮验证中断可能留下未收口的工单/通话，坐席容量被占满会导致本轮永远等不到振铃。
async function reset(agentUser) {
    const summary=await api(`/api/platform/agent/${agentUser.subject}/calls`,undefined,agentUser.subject);
    const stale=new Set();
    for(const ticket of [summary.queue.ringing,...(summary.queue.queue||[])])if(ticket&&ticket.processId)stale.add(ticket.processId);
    for(const processId of stale){
        const call=await api(`/api/calls/by-process/${processId}`).catch(()=>null);
        if(call&&call.status==='OPEN')await api(`/api/calls/${call.callId}/hangup`,{actorId:agentUser.subject,actorRole:'AGENT'},agentUser.subject).catch(()=>{});
        await fetch(`http://127.0.0.1:8081/internal/ticket/${processId}/release?reason=verify-reset`,{method:'POST'}).catch(()=>{});
        console.log(`复位: 清理遗留工单 processId=${processId}`);
    }
}
async function answer(agentUser,processId){return api(`/api/platform/call/${processId}/answer`,{agentId:agentUser.subject,peerId:agentUser.peerId},agentUser.subject);}
async function customer(name) {
    const profile=await api('/api/customer/apply',{name,certNo:'VERIFY-'+randomUUID(),mobile:'13800138000',channel:'API验证'});
    return {profile,user:await subscribe('CUSTOMER',profile.customerId)};
}
function assertDocument(url,label){assert.ok(url,`缺少${label}`);return fetch(base+url).then(r=>assert.ok(r.ok,`${label}不可访问`));}

async function run() {
    const configured=await login('1','1');
    agent=await subscribe('AGENT',configured.agentId);
    await api(`/api/platform/agent/${agent.subject}/status`,{peerId:agent.peerId,status:'ONLINE'},agent.subject);agentsOnline.push(agent.subject);agentPeers[agent.subject]=agent.peerId;
    console.log(`坐席签入: ${agent.subject} (${configured.name||''})`);
    await reset(agent);

    // ============ 场景一：低风险 —— 数字人直通采集/签名，人工仅核签 ============
    {
        const {profile,user}=await customer('API低风险客户');
        const queued=await api('/api/customer/queue',{customerId:profile.customerId,peerId:user.peerId,tradeCode:'300101',serviceMode:'DIGITAL',channel:'API验证'});
        let state=queued.state;assert.equal(state.nodeId,'ai_collect');
        console.log('场景一[数字人] 信息采集 ai_collect，客户提交要素');
        state=await submit(state,{amount:2000,payee:'测试收款人甲',payee_account:'6222021000111222',purpose:'低风险直通验证'},'CUSTOMER',user,'SUBMIT');
        assert.equal(state.nodeId,'customer_sign');assert.equal(state.vars.needHuman,false);
        console.log('场景一[系统]   风控评估 risk_check → needHuman=false 直通，跳过人工授权');
        const signature=await photo('signature_image','CUSTOMER',user,state.processId);
        state=await submit(state,{signature_image:signature.fileUrl,confirmed:true,evidenceReceipts:{signature_image:signature.eventId}},'CUSTOMER',user,'SUBMIT');
        console.log('场景一[数字人] 客户签名 customer_sign 已提交，进入人工核签排队');
        assert.equal(state.nodeId,'signature_review');
        // 无保留坐席时核签申请新接待：坐席振铃 → 接听认领会话
        const reviewTicket=await ring(agent,state.processId);assert.equal(reviewTicket.handlerType,'HUMAN');
        state=await answer(agent,state.processId);
        assert.equal(state.nodeId,'signature_review');assert.ok(state.taskId,'核签任务未生成');
        state=await submit(state,{confirmed:true},'AGENT',agent,'AUTHORIZE');
        console.log('场景一[人工]   核签通过 signature_review，系统接续签字单→核心记账→回执→通知');
        assert.equal(state.processStatus,'COMPLETED');
        assert.ok(state.vars.coreTxId,'缺核心流水号');assert.ok(state.vars.signatureDocumentId,'缺签字单号');
        await assertDocument(state.vars.signatureDocumentUrl,'签字单PDF');await assertDocument(state.vars.receiptPdfUrl,'回执PDF');
        assert.ok(state.vars.notificationMsgId,'缺短信回执');
        console.log(`PASS-场景一: 低风险直通 coreTxId=${state.vars.coreTxId}`);
        // 流程完结不自动结束通话：通话存续期间坐席持续占用，挂断后才释放接待下一路
        const call=await api(`/api/calls/by-process/${state.processId}`);
        if(call&&call.status==='OPEN')await api(`/api/calls/${call.callId}/hangup`,{actorId:agent.subject,actorRole:'AGENT'},agent.subject);
    }

    // ============ 场景二：高风险 —— 人工授权 + 核签退回重签（保留原坐席） ============
    {
        const {profile,user}=await customer('API高风险客户');
        const queued=await api('/api/customer/queue',{customerId:profile.customerId,peerId:user.peerId,tradeCode:'300101',serviceMode:'DIGITAL',channel:'API验证'});
        let state=queued.state;assert.equal(state.nodeId,'ai_collect');
        state=await submit(state,{amount:800000,payee:'测试收款人乙',payee_account:'6228480402564890',purpose:'高风险授权与重签验证'},'CUSTOMER',user,'SUBMIT');
        assert.equal(state.vars.needHuman,true);assert.equal(state.nodeId,'human_auth');
        console.log('场景二[系统]   风控评估命中大额阈值 → 转人工授权排队');
        const ticket=await ring(agent,state.processId);assert.equal(ticket.handlerType,'HUMAN');
        state=await answer(agent,state.processId);
        assert.equal(state.vars.agent_session_id,agent.subject,'会话未归属办理坐席');
        console.log('场景二[人工]   坐席接听 human_auth，采集现场照片并授权');
        const witness=await photo('witness_photo','AGENT',agent,state.processId);
        state=await submit(state,{confirmed:true,evidence_receipt:witness.eventId},'AGENT',agent,'AUTHORIZE');
        assert.equal(state.nodeId,'customer_sign');
        let signature=await photo('signature_image','CUSTOMER',user,state.processId);
        state=await submit(state,{signature_image:signature.fileUrl,confirmed:true,evidenceReceipts:{signature_image:signature.eventId}},'CUSTOMER',user,'SUBMIT');
        assert.equal(state.nodeId,'signature_review');assert.equal(state.taskAssignee,agent.subject,'核签未绑定保留坐席');
        console.log('场景二[人工]   原坐席直绑核签，本次退回要求重签');
        state=await submit(state,{review_reason:'签名与预留样本不符，请重新签署'},'AGENT',agent,'REJECT');
        assert.equal(state.nodeId,'customer_sign','退回后未回到签名节点');
        signature=await photo('signature_image','CUSTOMER',user,state.processId);
        state=await submit(state,{signature_image:signature.fileUrl,confirmed:true,evidenceReceipts:{signature_image:signature.eventId}},'CUSTOMER',user,'SUBMIT');
        assert.equal(state.nodeId,'signature_review');assert.equal(state.taskAssignee,agent.subject,'重签后未复用原坐席');
        console.log('场景二[数字人] 客户重签完成，再次进入原坐席核签');
        state=await submit(state,{confirmed:true},'AGENT',agent,'AUTHORIZE');
        assert.equal(state.processStatus,'COMPLETED');
        assert.ok(state.vars.coreTxId,'缺核心流水号');
        await assertDocument(state.vars.signatureDocumentUrl,'签字单PDF');await assertDocument(state.vars.receiptPdfUrl,'回执PDF');
        console.log(`PASS-场景二: 高风险授权+退回重签 coreTxId=${state.vars.coreTxId}`);
        const submissions=await api(`/api/platform/process/${state.processId}/submissions`);
        const actorOf=node=>submissions.filter(row=>row.node_id===node).map(row=>`${row.action_code}@${row.actor_id}`).join(',');
        assert.ok(actorOf('ai_collect').includes('SUBMIT@'+user.peerId),'信息采集提交方不是客户');
        assert.ok(actorOf('customer_sign').includes('SUBMIT@'+user.peerId),'签名提交方不是客户');
        assert.ok(actorOf('human_auth').includes('AUTHORIZE@'+agent.subject),'人工授权提交方不是坐席');
        assert.ok(actorOf('signature_review').includes('AUTHORIZE@'+agent.subject),'核签通过提交方不是坐席');
        assert.ok(actorOf('signature_review').includes('REJECT@'+agent.subject),'核签退回提交方不是坐席');
        for(const sys of ['risk_check','sign_document','core_transfer','receipt_doc','notify_result'])
            assert.ok(actorOf(sys).includes('AUTO@SYSTEM'),`系统节点 ${sys} 缺少自动执行记录`);
        console.log('PASS-人机分工留痕: 客户提交采集/签名，坐席提交授权/核签，SYSTEM执行风控/记账/回执/通知');
        const call=await api(`/api/calls/by-process/${state.processId}`);
        if(call.status==='OPEN')await api(`/api/calls/${call.callId}/hangup`,{actorId:agent.subject,actorRole:'AGENT'},agent.subject);
        const deadline=Date.now()+10000;while(!events.some(e=>e.type==='CALL_ENDED'&&e.callId===call.callId)&&Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,100));
        assert.ok(events.some(e=>e.type==='VIDEO_ROOM_READY'&&e.recipientRole==='CUSTOMER'),'客户未收到入房信令');
        if(call.status==='OPEN')assert.ok(events.some(e=>e.type==='CALL_ENDED'&&e.callId===call.callId),'客户未收到挂断信令');
    }
    console.log('PASS: 转账全流程人机协同验证完成（数字人重复节点 + 人工关键节点 + 系统收尾）');
}

try{await run();}
catch(error){console.error('FAIL:',error.message);process.exitCode=1;}
finally{
    if(agent)await reset(agent).catch(()=>{});
    for(const id of agentsOnline)await api(`/api/platform/agent/${id}/status`,{peerId:agentPeers[id]||'',status:'OFFLINE'},id).catch(()=>{});
    clients.forEach(client=>client.disconnect());
}

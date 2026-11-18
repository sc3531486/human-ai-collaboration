import {test} from 'node:test';
import assert from 'node:assert/strict';
import GenericNode from '../src/ui/GenericNode.js';

test('选卡与证件照片同一节点提交时必须包含照片及各自凭证', async () => {
    const globals={document:globalThis.document,FileReader:globalThis.FileReader,Image:globalThis.Image};
    const names=['id_front_photo','id_back_photo'];
    const uploads=names.map(name=>({dataset:{photo:name},files:[{size:20}]}));
    const error={textContent:''},button={disabled:false},statuses={},images={};
    names.forEach(name=>{statuses[name]={};images[name]={};});
    const form={elements:{selected_card_id:{value:'CARD-002'}},
        querySelector(selector){if(selector==='.vb-node-form-error')return error;if(selector==='[type="submit"]')return button;if(selector==='[data-face]')return null;const name=selector.match(/="(.*?)"/)?.[1];return selector.includes('data-preview')?images[name]:statuses[name];},
        querySelectorAll(){return uploads;}};
    const panel={innerHTML:'',querySelector(){return form;}};
    let submitted;
    try {
        globalThis.document={createElement(){return{width:0,height:0,getContext(){return{drawImage(){}};},toBlob(callback){callback(new Blob(['synthetic']));},toDataURL(){return'data:image/png;base64,preview';}};}};
        globalThis.FileReader=class{readAsDataURL(){this.result='data:image/png;base64,preview';this.onload();}};
        globalThis.Image=class{width=100;height=80;set src(value){this.onload();}};
        GenericNode.mount(panel,{nodePolicy:{actors:['CUSTOMER'],fields:[{name:'selected_card_id',type:'card',required:true},...names.map(name=>({name,label:name,type:'photo',required:true}))]}},{values:{cards:[{id:'CARD-002',label:'合成卡',cardNoMasked:'****4321'}]},primeAudio(){},async photoUpload(name){return{fileUrl:`/records/${name}.png`,eventId:`receipt-${name}`};},async submit(payload){submitted=payload;}});
        for(const input of uploads)await input.onchange();
        await new Promise(resolve=>setImmediate(resolve));
        await form.onsubmit({preventDefault(){}});
        assert.equal(error.textContent,'');assert.equal(submitted.selected_card_id,'CARD-002');
        for(const name of names){assert.equal(submitted[name],`/records/${name}.png`);assert.equal(submitted.evidenceReceipts[name],`receipt-${name}`);}
    } finally {for(const [key,value] of Object.entries(globals)){if(value===undefined)delete globalThis[key];else globalThis[key]=value;}}
});

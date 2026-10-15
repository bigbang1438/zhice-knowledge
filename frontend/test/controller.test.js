import test from 'node:test';
import assert from 'node:assert/strict';
import {createWorkspace, initialState} from '../src/controller.js';

function pending(){let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};}
function setup(request){const state=initialState();state.user={id:'anna',name:'Anna'};state.models={chatConfigured:true,embeddingConfigured:true};state.spaces=[{id:'a',name:'A'},{id:'b',name:'B'}];return {state,workspace:createWorkspace({request},state)};}
const empty={items:[]};

test('slower former-space documents cannot overwrite selected space',async()=>{
  const slow=pending();const {state,workspace}=setup(path=>path==='/api/spaces/a/documents'?slow.promise:Promise.resolve(path.endsWith('/documents')?{items:[{id:'bdoc',filename:'B.md'}]}:empty));
  const old=workspace.selectSpace('a');await workspace.selectSpace('b');slow.resolve({items:[{id:'adoc',filename:'A.md'}]});await old;
  assert.equal(state.spaceId,'b');assert.deepEqual(state.documents.map(x=>x.id),['bdoc']);assert.equal(state.loading.documents,false);
});
test('conversation switch prevents older answer showing in new conversation',async()=>{
  const slow=pending();const {state,workspace}=setup(path=>path.endsWith('/messages')?slow.promise:Promise.resolve(path.endsWith('/second')?{id:'second',title:'第二个',turns:[{id:'existing',answer:'现有'}]}:empty));
  await workspace.selectSpace('a');state.conversationId='first';await workspace.selectConversation('first');state.question='原问题';const send=workspace.send();await workspace.selectConversation('second');slow.resolve({id:'late',answer:'迟到回复'});await send;
  assert.equal(state.conversationId,'second');assert.deepEqual(state.turns.map(x=>x.id),['existing']);assert.equal(state.loading.send,false);
});
test('knowledge revision conflict keeps question and never appends a failed turn',async()=>{
  const calls=[];const {state,workspace}=setup(async(path,options)=>{calls.push(path);if(path.endsWith('/messages'))throw Object.assign(new Error('资料已更新'),{status:409});if(options?.method==='POST')return {id:'conv',title:'问题'};return empty;});
  await workspace.selectSpace('a');state.question='保留这个问题';await workspace.send();
  assert.equal(state.question,'保留这个问题');assert.deepEqual(state.turns,[]);assert.match(state.error,/资料已更新/);assert.equal(calls.filter(x=>x.endsWith('/messages')).length,1);
});
test('new conversation is created only once before first real question',async()=>{
  const calls=[];const {state,workspace}=setup(async(path,options)=>{calls.push([path,options]);if(path.endsWith('/messages'))return {id:'answer',question:options.json.question,answer:'答复',sources:[]};if(options?.method==='POST')return {id:'new-conv',title:options.json.title};return empty;});
  await workspace.selectSpace('a');workspace.newConversation();assert.equal(calls.filter(([,o])=>o?.method==='POST').length,0);state.question='知识库怎么用';await workspace.send();
  assert.equal(state.conversationId,'new-conv');assert.equal(state.turns[0].answer,'答复');assert.equal(state.question,'');assert.equal(calls.filter(([path,o])=>o?.method==='POST'&&!path.endsWith('/messages')).length,1);
});
test('file conflict retains document list and submits original document version',async()=>{
  let sent;const {state,workspace}=setup(async(path,options)=>{if(options?.method==='PUT'){sent=options.body;throw Object.assign(new Error('版本已变化'),{status:409});}return empty;});
  await workspace.selectSpace('a');state.documents=[{id:'doc',filename:'old.md',version:7}];const file=new Blob(['新资料']);Object.defineProperty(file,'name',{value:'new.md'});
  await workspace.upload(file,state.documents[0]);assert.equal(sent.get('version'),'7');assert.equal(state.documents[0].filename,'old.md');assert.match(state.error,/版本已变化/);
});
test('failed source-only search is not displayed as a model answer',async()=>{
  const {state,workspace}=setup(async(path)=>{if(path.includes('/search?'))return {sources:[{id:'s',text:'原文'}],mode:'bm25',revision:3};return empty;});
  await workspace.selectSpace('a');state.searchQuery='查原文';await workspace.search();assert.equal(state.searchResults.sources.length,1);assert.deepEqual(state.turns,[]);
});
test('unconfigured chat blocks model question without issuing network mutation',async()=>{
  let mutations=0;const {state,workspace}=setup(async(path,options)=>{if(options?.method)mutations++;return empty;});await workspace.selectSpace('a');state.models.chatConfigured=false;state.question='问题';await workspace.send();assert.equal(mutations,0);assert.match(state.error,/模型/);
});
test('logout invalidates outstanding old-account documents before next login',async()=>{
  const slow=pending();let loggedOut=false;
  const state=initialState();state.user={id:'anna',name:'Anna'};state.spaces=[{id:'a',name:'A'}];
  const workspace=createWorkspace({request:path=>path.endsWith('/documents')?slow.promise:Promise.resolve(empty),logout:async()=>{loggedOut=true;return {user:null,csrf:{token:'new',headerName:'X-CSRF-TOKEN'},models:{}};}},state);
  const loading=workspace.selectSpace('a');await workspace.logout();slow.resolve({items:[{id:'old-private-document'}]});await loading;
  assert.equal(loggedOut,true);assert.equal(state.user,null);assert.deepEqual(state.documents,[]);assert.deepEqual(state.conversations,[]);assert.equal(state.spaceId,'');
});
test('delete conflict preserves visible document and does not submit a newer guessed version',async()=>{
  const deletion=[];const {state,workspace}=setup(async(path,options)=>{if(options?.method==='DELETE'){deletion.push(path);throw Object.assign(new Error('请重新核对版本'),{status:409});}return empty;});
  await workspace.selectSpace('a');const document={id:'doc',filename:'guide.md',version:4};state.documents=[document];await workspace.remove(document);
  assert.deepEqual(deletion,['/api/spaces/a/documents/doc?version=4']);assert.equal(state.documents[0].version,4);assert.match(state.error,/版本/);assert.equal(state.loading.mutation,false);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { createContextGate, validateUpload, sourceLocation, selectSearchMode } from '../src/workspace.js';

test('account change invalidates requests even when same space and conversation selected',()=>{
  const gate=createContextGate(); gate.set('anna','team','c1');const request=gate.capture();gate.set('ben','team','c1');
  assert.equal(gate.current(request),false);
});
test('A to B to A selection never accepts the original A response',()=>{
  const gate=createContextGate();gate.set('anna','team','a');const old=gate.capture();gate.set('anna','team','b');gate.set('anna','team','a');
  assert.equal(gate.current(old),false);assert.equal(gate.current(gate.capture()),true);
});
test('newer request supersedes older result in the same request lane',()=>{
  const gate=createContextGate();gate.set('anna','team','a');const old=gate.start('turns');const latest=gate.start('turns');
  assert.equal(gate.current(old),false);assert.equal(gate.current(latest),true);
});
test('independent request lanes do not invalidate each other',()=>{
  const gate=createContextGate();gate.set('anna','team','a');const docs=gate.start('documents');gate.start('turns');assert.equal(gate.current(docs),true);
});
test('exact upload size boundary accepted and oversize rejected before network call',()=>{
  assert.equal(validateUpload({name:'资料.pdf',size:2097152}),null);
  assert.match(validateUpload({name:'资料.pdf',size:2097153}),/2/);
});
test('unsupported extension, unsafe name and empty file are rejected',()=>{
  assert.ok(validateUpload({name:'malicious.html',size:40}));assert.ok(validateUpload({name:'../data.md',size:40}));assert.ok(validateUpload({name:'empty.txt',size:0}));
  assert.equal(validateUpload({name:'TEAM-GUIDE.MD',size:40}),null);
});
test('sources show PDF page or original text line range without fabricated page',()=>{
  assert.equal(sourceLocation({page:3,lineStart:10,lineEnd:16}),'第 3 页 · 第 10–16 行');
  assert.equal(sourceLocation({page:0,lineStart:4,lineEnd:4}),'第 4 行');
});
test('unconfigured embedding never silently selects vector or hybrid',()=>{
  assert.equal(selectSearchMode('hybrid',false),'bm25');assert.equal(selectSearchMode('vector',false),'bm25');assert.equal(selectSearchMode('hybrid',true),'hybrid');
});

import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createClient, ApiError } from '../src/client.js';
import { createWorkspace, initialState } from '../src/controller.js';

async function serve(handler, fn) {
  const server = http.createServer(handler);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try { await fn(`http://127.0.0.1:${server.address().port}`); }
  finally { await new Promise(resolve => server.close(resolve)); }
}
function json(res, status, value) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(value));
}
async function read(req) { let body=''; for await(const chunk of req) body += chunk; return body; }

test('session response reaches caller and rotates CSRF used by later mutation', async () => {
  await serve(async (req,res) => {
    if(req.url === '/api/session') return json(res,200,{user:{id:'anna',name:'Anna'},csrf:{headerName:'X-CSRF-TOKEN',token:'new-token'},models:{chatConfigured:true,embeddingConfigured:false}});
    if(req.headers['x-csrf-token'] !== 'new-token') return json(res,403,{error:{code:'csrf',message:'令牌失效'}});
    json(res,201,{id:'created'});
  }, async base => {
    const client=createClient(base);
    const session=await client.session();
    assert.equal(session.user.name,'Anna');
    assert.deepEqual(await client.request('/api/spaces/team/conversations',{method:'POST',json:{title:'标题'}}),{id:'created'});
  });
});

test('login sends URL encoded credentials with server CSRF header and refreshes session', async () => {
  let loggedIn=false;
  await serve(async (req,res) => {
    if(req.url==='/api/session') return json(res,200,{user:loggedIn?{id:'a',name:'Anna'}:null,csrf:{headerName:'X-DEMO-CSRF',token:loggedIn?'after':'before'},models:{}});
    if(req.url==='/api/login') {
      const body=new URLSearchParams(await read(req));
      if(req.headers['x-demo-csrf']!=='before'||body.get('username')!=='demo_anna'||body.get('password')!=='a&b=c密码') return json(res,403,{error:{code:'csrf',message:'失败'}});
      loggedIn=true; res.writeHead(204); return res.end();
    }
  }, async base => {
    const client=createClient(base); await client.session();
    const session=await client.login('demo_anna','a&b=c密码');
    assert.equal(session.user.id,'a');
    assert.equal(session.csrf.token,'after');
  });
});

test('conflict stays an explicit error and mutation is never automatically retried', async () => {
  let writes=0;
  await serve((req,res) => { writes++; json(res,409,{error:{code:'stale_version',message:'资料已变化，请重新加载'}}); },async base => {
    const client=createClient(base);
    await assert.rejects(client.request('/api/spaces/team/documents/d?version=1',{method:'DELETE'}),error=>error instanceof ApiError && error.status===409 && error.code==='stale_version' && error.message==='资料已变化，请重新加载');
    assert.equal(writes,1);
  });
});

test('non JSON upstream error exposes safe application wording instead of HTML', async () => {
  await serve((req,res)=>{res.writeHead(502,{'Content-Type':'text/html'});res.end('<script>secret server path</script>');},async base=>{
    const client=createClient(base);
    await assert.rejects(client.request('/api/spaces'),error=>error.status===502&&!error.message.includes('script')&&!error.message.includes('secret'));
  });
});

test('empty 204 responses are accepted for versioned deletion', async () => {
  await serve((req,res)=>{res.writeHead(204);res.end();},async base=>{
    assert.equal(await createClient(base).request('/api/spaces/a/documents/b?version=2',{method:'DELETE'}),null);
  });
});

test('multipart keeps generated content type boundary and server receives version and file', async () => {
  await serve(async(req,res)=>{
    const body=await read(req);
    if(!req.headers['content-type']?.includes('boundary=')||!body.includes('name="version"')||!body.includes('filename="guide.md"')||!body.includes('政策正文'))return json(res,422,{error:{message:'上传格式错误'}});
    json(res,200,{id:'doc',version:2});
  },async base=>{
    const data=new FormData();data.set('version','1');data.set('file',new Blob(['政策正文'],{type:'text/markdown'}),'guide.md');
    assert.equal((await createClient(base).request('/api/spaces/a/documents/d',{method:'PUT',body:data})).version,2);
  });
});

test('successful server logout clears private workspace even when next session refresh fails',async()=>{
  let loggedIn=true;
  await serve((req,res)=>{
    if(req.url==='/api/logout'){loggedIn=false;return json(res,200,{authenticated:false});}
    if(req.url==='/api/session'){
      if(!loggedIn)return json(res,502,{error:{code:'temporarily_unavailable',message:'暂时无法刷新会话'}});
      return json(res,200,{user:{id:'anna',name:'Anna'},csrf:{headerName:'X-CSRF-TOKEN',token:'before'},models:{chatConfigured:true,embeddingConfigured:true}});
    }
    json(res,200,{items:[]});
  },async base=>{
    const state=initialState();const workspace=createWorkspace(createClient(base),state);await workspace.initialize();
    state.spaceId='private';state.documents=[{id:'secret'}];state.turns=[{id:'turn',answer:'私有内容'}];state.conversations=[{id:'c'}];state.question='私有问题';state.source={text:'私有原文'};
    await workspace.logout();
    assert.equal(loggedIn,false);assert.equal(state.user,null);assert.equal(state.spaceId,'');assert.deepEqual(state.documents,[]);assert.deepEqual(state.turns,[]);assert.deepEqual(state.conversations,[]);assert.equal(state.question,'');assert.equal(state.source,null);assert.match(state.error,/刷新会话/);assert.equal(state.sessionReady,false);
  });
});

test('failed server logout preserves current workspace for explicit retry',async()=>{
  await serve((req,res)=>{
    if(req.url==='/api/logout')return json(res,503,{error:{code:'busy',message:'退出未完成，请重试'}});
    if(req.url==='/api/session')return json(res,200,{user:{id:'anna',name:'Anna'},csrf:{headerName:'X-CSRF-TOKEN',token:'before'},models:{}});
    json(res,200,{items:[]});
  },async base=>{
    const state=initialState();const workspace=createWorkspace(createClient(base),state);await workspace.initialize();state.documents=[{id:'keep'}];await workspace.logout();
    assert.equal(state.user.id,'anna');assert.equal(state.documents[0].id,'keep');assert.match(state.error,/退出未完成/);
  });
});

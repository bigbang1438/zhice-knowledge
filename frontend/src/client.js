export class ApiError extends Error {
  constructor(status, code, message) {
    super(message); this.name='ApiError'; this.status=status; this.code=code;
  }
}

export function createClient(base='') {
  let csrf=null;
  async function request(path, options={}) {
    const {json, ...init}=options;
    const method=(init.method||'GET').toUpperCase();
    const headers=new Headers(init.headers);
    headers.set('Accept','application/json');
    if(json!==undefined) { headers.set('Content-Type','application/json');init.body=JSON.stringify(json); }
    if(!['GET','HEAD','OPTIONS'].includes(method)&&csrf?.token) headers.set(csrf.headerName,csrf.token);
    let response;
    try { response=await fetch(base+path,{...init,method,headers,credentials:'same-origin'}); }
    catch(error) {
      if(error.name==='AbortError') throw error;
      throw new ApiError(0,'network_error','无法连接服务，请确认服务已启动后重试。');
    }
    let value=null;
    if(response.status!==204) {
      const raw=await response.text();
      if(raw) {try {value=JSON.parse(raw);} catch { if(response.ok) throw new ApiError(response.status,'invalid_response','服务返回格式异常，请刷新后重试。'); }}
    }
    if(!response.ok) throw new ApiError(response.status,value?.error?.code||'request_failed',value?.error?.message||safeMessage(response.status));
    return value;
  }
  async function session() {
    const value=await request('/api/session');
    csrf=value?.csrf||null;
    return value;
  }
  async function login(username,password) {
    await request('/api/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({username,password})});
    return session();
  }
  async function logout(onSignedOut=()=>{}) {
    await request('/api/logout',{method:'POST'});
    csrf=null;
    // The private UI must be cleared at the successful sign-out boundary,
    // independently of the optional request for the next anonymous CSRF token.
    onSignedOut();
    return session();
  }
  return {request,session,login,logout};
}

function safeMessage(status) {
  if(status===401)return '登录已失效，请重新登录。';
  if(status===403)return '无法执行此操作，请刷新页面并确认账号权限。';
  if(status===409)return '资料已被更新，请重新加载后核对。';
  if(status===429||status===503)return '当前问答任务繁忙，请稍后重试。';
  if(status===502)return '模型服务暂不可用，请确认本地模型配置。';
  return '请求未完成，请稍后重试。';
}

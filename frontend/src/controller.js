import {createContextGate,validateUpload,selectSearchMode} from './workspace.js';

export function initialState(){return {
  sessionReady:false,user:null,models:{chatConfigured:false,embeddingConfigured:false},spaces:[],spaceId:'',documents:[],conversations:[],conversationId:null,turns:[],question:'',searchQuery:'',searchResults:null,error:'',notice:'',section:'ask',mode:'rag',searchMode:'bm25',source:null,
  loading:{session:false,space:false,documents:false,conversations:false,turns:false,send:false,mutation:false,search:false}
};}

export function createWorkspace(client,state) {
  const spaceGate=createContextGate(),conversationGate=createContextGate();
  let accountEpoch=0,conversationEpoch=0;
  const sid=()=>`/api/spaces/${encodeURIComponent(state.spaceId)}`;
  function syncGates(){spaceGate.set(state.user?.id,state.spaceId,accountEpoch);conversationGate.set(state.user?.id,state.spaceId,state.conversationId,accountEpoch,conversationEpoch);}
  function clearMessages(){state.error='';state.notice='';}
  function clearWorkspace(){state.spaces=[];state.spaceId='';state.documents=[];state.conversations=[];state.conversationId=null;state.turns=[];state.question='';state.searchQuery='';state.searchResults=null;state.source=null;state.loading=initialState().loading;conversationEpoch++;syncGates();}
  function fail(error){state.error=error.message||'操作未完成，请重试。';if(error.status===401){accountEpoch++;state.user=null;clearWorkspace();state.error='登录已失效，请重新登录。';}}
  async function acceptSession(session){
    accountEpoch++;state.user=session.user;state.models=session.models||{};clearWorkspace();state.sessionReady=true;state.searchMode=selectSearchMode(state.models.embeddingConfigured?'hybrid':'bm25',state.models.embeddingConfigured);
    if(!state.user)return;
    const token=spaceGate.start('spaces');state.loading.space=true;
    try {const result=await client.request('/api/spaces');if(!spaceGate.current(token))return;state.spaces=result.items||[];if(state.spaces.length)await selectSpace(state.spaces[0].id);}
    catch(error){if(spaceGate.current(token))fail(error);}
    finally{state.loading.space=false;}
  }
  async function initialize(){state.loading.session=true;clearMessages();try{await acceptSession(await client.session());}catch(error){fail(error);state.sessionReady=true;}finally{state.loading.session=false;}}
  async function login(username,password){state.loading.session=true;clearMessages();try{await acceptSession(await client.login(username,password));}catch(error){fail(error);}finally{state.loading.session=false;}}
  async function logout(){
    state.loading.session=true;clearMessages();
    try{await acceptSession(await client.logout(()=>{accountEpoch++;state.user=null;clearWorkspace();state.sessionReady=false;state.loading.session=true;}));}
    catch(error){fail(error);}
    finally{state.loading.session=false;}
  }
  async function loadDocuments(){
    if(!state.spaceId)return;syncGates();const token=spaceGate.start('documents');const path=sid();state.loading.documents=true;
    try{const result=await client.request(path+'/documents');if(spaceGate.current(token))state.documents=result.items||[];}
    catch(error){if(spaceGate.current(token))fail(error);}
    finally{if(spaceGate.current(token))state.loading.documents=false;}
  }
  async function loadConversations(){
    if(!state.spaceId)return;syncGates();const token=spaceGate.start('conversations');const path=sid();state.loading.conversations=true;
    try{const result=await client.request(path+'/conversations');if(spaceGate.current(token))state.conversations=result.items||[];}
    catch(error){if(spaceGate.current(token))fail(error);}
    finally{if(spaceGate.current(token))state.loading.conversations=false;}
  }
  async function selectSpace(id){
    clearMessages();state.spaceId=id;state.documents=[];state.conversations=[];state.conversationId=null;state.turns=[];state.question='';state.searchQuery='';state.searchResults=null;state.source=null;state.loading.send=false;state.loading.turns=false;state.loading.mutation=false;state.loading.search=false;conversationEpoch++;syncGates();
    await Promise.all([loadDocuments(),loadConversations()]);
  }
  function newConversation(){clearMessages();state.conversationId=null;state.turns=[];state.question='';state.source=null;state.loading.turns=false;state.loading.send=false;state.section='ask';conversationEpoch++;syncGates();}
  async function selectConversation(id){
    clearMessages();state.conversationId=id;state.turns=[];state.question='';state.source=null;state.section='ask';state.loading.send=false;conversationEpoch++;syncGates();const token=conversationGate.start('turns');const path=sid();state.loading.turns=true;
    try{const result=await client.request(path+'/conversations/'+encodeURIComponent(id));if(conversationGate.current(token))state.turns=result.turns||[];}
    catch(error){if(conversationGate.current(token))fail(error);}
    finally{if(conversationGate.current(token))state.loading.turns=false;}
  }
  async function send(){
    if(state.loading.send||!state.spaceId)return;clearMessages();const question=state.question.trim();if(!question){state.error='请先输入问题。';return;}if(question.length>1000){state.error='问题请控制在 1000 字以内。';return;}
    if(!state.models.chatConfigured){state.error='尚未配置问答模型，可先到资料库检索原文。';return;}
    syncGates();let token=conversationGate.start('send');const path=sid();state.loading.send=true;
    try{
      if(!state.conversationId){
        const conversation=await client.request(path+'/conversations',{method:'POST',json:{title:question.slice(0,48)}});
        if(!conversationGate.current(token))return;state.conversationId=conversation.id;conversationEpoch++;syncGates();token=conversationGate.start('send');
      }
      const turn=await client.request(path+'/conversations/'+encodeURIComponent(state.conversationId)+'/messages',{method:'POST',json:{question,mode:state.mode,searchMode:selectSearchMode(state.searchMode,state.models.embeddingConfigured)}});
      if(!conversationGate.current(token))return;
      state.turns.push(turn);if(state.question.trim()===question)state.question='';
      await loadConversations();
    }catch(error){if(conversationGate.current(token))fail(error);}
    finally{if(conversationGate.current(token))state.loading.send=false;}
  }
  async function upload(file,document=null){
    if(state.loading.mutation||!state.spaceId)return;clearMessages();const error=validateUpload(file);if(error){state.error=error;return;}
    const token=spaceGate.start('mutation');const path=sid();state.loading.mutation=true;const body=new FormData();body.set('file',file,file.name);if(document)body.set('version',String(document.version));
    try{await client.request(path+'/documents'+(document?'/'+encodeURIComponent(document.id):''),{method:document?'PUT':'POST',body});if(!spaceGate.current(token))return;state.notice=document?'资料已替换，后续问题将使用新版本。':'资料已加入知识库。';state.searchResults=null;state.source=null;await loadDocuments();}
    catch(error){if(spaceGate.current(token))fail(error);}
    finally{if(spaceGate.current(token))state.loading.mutation=false;}
  }
  async function remove(document){
    if(state.loading.mutation||!state.spaceId)return;clearMessages();const token=spaceGate.start('mutation');const path=sid();state.loading.mutation=true;
    try{await client.request(path+'/documents/'+encodeURIComponent(document.id)+'?version='+encodeURIComponent(document.version),{method:'DELETE'});if(!spaceGate.current(token))return;state.notice='资料已删除，后续检索不再包含该资料。';state.searchResults=null;state.source=null;await loadDocuments();}
    catch(error){if(spaceGate.current(token))fail(error);}
    finally{if(spaceGate.current(token))state.loading.mutation=false;}
  }
  async function search(){
    if(!state.spaceId||state.loading.search)return;clearMessages();const query=state.searchQuery.trim();if(!query){state.error='请输入要查找的内容。';return;}if(query.length>1000){state.error='检索内容请控制在 1000 字以内。';return;}
    const token=spaceGate.start('search');const path=sid();state.loading.search=true;state.searchResults=null;
    try{const queryString=new URLSearchParams({q:query,mode:selectSearchMode(state.searchMode,state.models.embeddingConfigured),limit:'5'});const result=await client.request(path+'/search?'+queryString);if(spaceGate.current(token))state.searchResults=result;}
    catch(error){if(spaceGate.current(token))fail(error);}
    finally{if(spaceGate.current(token))state.loading.search=false;}
  }
  return {initialize,login,logout,selectSpace,newConversation,selectConversation,loadDocuments,loadConversations,send,upload,remove,search};
}

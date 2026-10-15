<script setup>
import {computed,nextTick,onBeforeUnmount,onMounted,reactive,ref,watch} from 'vue';
import Icon from './Icon.vue';
import {createClient} from './client.js';
import {createWorkspace,initialState} from './controller.js';
import {sourceLocation,validateUpload} from './workspace.js';

const state=reactive(initialState());
const workspace=createWorkspace(createClient(),state);
const username=ref('demo_anna'),password=ref(''),sidebarOpen=ref(false);
const uploadInput=ref(),replaceInput=ref(),replaceTarget=ref(),confirmDialog=ref(),pendingChange=ref(null),turnList=ref(),questionInput=ref();
const selectedSpace=computed(()=>state.spaces.find(space=>space.id===state.spaceId));
const selectedConversation=computed(()=>state.conversations.find(conversation=>conversation.id===state.conversationId));
const searchLabels={bm25:'关键词检索',vector:'语义检索',hybrid:'混合检索'};
const toolLabels={search_knowledge:'检索资料',read_passage:'阅读原文'};
const questionHint=computed(()=>state.mode==='agent'?'助手按需要选择检索、阅读原文等步骤，再整理回答。':'先检索相关段落，再基于资料整理回答。');
function date(value){if(!value)return '';const parsed=new Date(value);return Number.isNaN(parsed.getTime())?value:parsed.toLocaleDateString('zh-CN',{month:'2-digit',day:'2-digit'});}
async function login(){try{await workspace.login(username.value,password.value);}finally{password.value='';}}
async function selectSpace(event){await workspace.selectSpace(event.target.value);sidebarOpen.value=false;}
function selectConversation(conversation){workspace.selectConversation(conversation.id);sidebarOpen.value=false;}
function newConversation(){workspace.newConversation();sidebarOpen.value=false;nextTick(()=>questionInput.value?.focus());}
function chooseUpload(){uploadInput.value?.click();}
function chooseReplacement(document){replaceTarget.value=document;replaceInput.value?.click();}
function checkFile(file){const error=validateUpload(file);if(error){state.error=error;return false;}return true;}
async function uploadChanged(event){const file=event.target.files?.[0];event.target.value='';if(file&&checkFile(file))await workspace.upload(file);}
function replacementChanged(event){const file=event.target.files?.[0];event.target.value='';if(file&&checkFile(file)){pendingChange.value={type:'replace',document:{...replaceTarget.value},file};confirmDialog.value.showModal();}}
function confirmDelete(document){pendingChange.value={type:'delete',document:{...document}};confirmDialog.value.showModal();}
function closeConfirmation(){confirmDialog.value.close();pendingChange.value=null;}
async function applyChange(){const change=pendingChange.value;closeConfirmation();if(change.type==='delete')await workspace.remove(change.document);else await workspace.upload(change.file,change.document);}
function showSource(source,index){state.source={...source,number:index+1};}
function onKeydown(event){if(event.key==='Escape'){state.source=null;sidebarOpen.value=false;}}
function questionKeydown(event){if((event.ctrlKey||event.metaKey)&&event.key==='Enter'){event.preventDefault();workspace.send();}}
function humanTrace(trace){return toolLabels[trace.tool]||trace.tool;}
watch(()=>state.turns.length,async()=>{await nextTick();turnList.value?.scrollTo({top:turnList.value.scrollHeight,behavior:'smooth'});});
onMounted(()=>{workspace.initialize();window.addEventListener('keydown',onKeydown);});
onBeforeUnmount(()=>window.removeEventListener('keydown',onKeydown));
</script>

<template>
  <div v-if="!state.user" class="entrance">
    <div class="entrance-brand"><span class="brand-mark"><Icon name="book" :size="25"/></span><span>知策</span></div>
    <div class="entrance-layout">
      <section class="entrance-copy" aria-labelledby="welcome-title">
        <div class="document-emblem" aria-hidden="true"><Icon name="file" :size="68"/><span class="emblem-quote">“</span></div>
        <h1 id="welcome-title">团队知识，<br>有据可查。</h1>
        <p>把分散的手册与规则放在一起。<br>带着问题查资料，沿着引用看原文。</p>
        <div class="entrance-features"><span><Icon name="layers"/>管理资料版本</span><span><Icon name="search"/>检索与问答</span><span><Icon name="file"/>回看原文依据</span></div>
      </section>
      <section class="login-panel" aria-labelledby="login-title">
        <span class="small-symbol"><Icon name="book"/></span><h2 id="login-title">进入知识工作台</h2><p class="muted">本地学习演示，使用虚构账号体验。</p>
        <div v-if="state.error" class="message error" role="alert">{{state.error}}</div>
        <form @submit.prevent="login">
          <label for="username">演示账号</label>
          <select id="username" v-model="username" autocomplete="username" :disabled="state.loading.session"><option value="demo_anna">Anna · 团队资料</option><option value="demo_ben">Ben · 独立资料</option></select>
          <label for="password">演示密码</label><input id="password" v-model="password" type="password" autocomplete="current-password" required :disabled="state.loading.session" placeholder="输入项目启动时设置的密码">
          <p class="field-help">两个账号使用各自的资料空间，问答记录按账号保存。</p>
          <button class="button primary login-button" :disabled="state.loading.session||!state.sessionReady"><span v-if="state.loading.session" class="spinner"/>{{state.loading.session?'正在连接…':'进入工作台'}}<Icon v-if="!state.loading.session" name="arrow"/></button>
        </form>
        <button v-if="state.error&&!state.loading.session" class="text-button retry-connection" @click="workspace.initialize">重新连接服务</button>
        <p class="login-footnote">这是个人学习项目的本地演示，不接收真实业务敏感资料。</p>
      </section>
    </div>
    <footer class="entrance-footer">知策 · 团队知识库与问答助手</footer>
  </div>

  <div v-else class="app-shell" :class="{'has-source':state.source}">
    <button v-if="sidebarOpen" class="sidebar-scrim" aria-label="收起导航" @click="sidebarOpen=false"/>
    <aside class="sidebar" :class="{open:sidebarOpen}" aria-label="知识库与问答导航">
      <div class="brand"><span class="brand-mark"><Icon name="book" :size="23"/></span><div><strong>知策</strong><span>团队知识工作台</span></div><button class="icon-button mobile-close" aria-label="收起导航" @click="sidebarOpen=false"><Icon name="close"/></button></div>
      <label class="space-label" for="space-select">当前知识库</label>
      <select id="space-select" class="space-select" :value="state.spaceId" :disabled="state.loading.space" @change="selectSpace"><option v-for="space in state.spaces" :key="space.id" :value="space.id">{{space.name}}</option><option v-if="!state.spaces.length" value="">暂无可用知识库</option></select>
      <nav class="primary-nav"><button :class="{active:state.section==='ask'}" :aria-current="state.section==='ask'?'page':undefined" @click="state.section='ask';sidebarOpen=false"><Icon name="chat"/>知识问答</button><button :class="{active:state.section==='documents'}" :aria-current="state.section==='documents'?'page':undefined" @click="state.section='documents';sidebarOpen=false"><Icon name="file"/>资料库<span class="nav-count">{{state.documents.length}}</span></button></nav>
      <div class="history-heading"><h2>最近问答</h2><button class="icon-button" :disabled="!state.spaceId" aria-label="新建问答" title="新建问答" @click="newConversation"><Icon name="plus" :size="18"/></button></div>
      <div class="history-list"><p v-if="state.loading.conversations" class="sidebar-empty">正在读取记录…</p><p v-else-if="!state.conversations.length" class="sidebar-empty">提出第一个问题后，<br>记录会显示在这里。</p><button v-for="conversation in state.conversations" :key="conversation.id" :class="{'is-selected':state.conversationId===conversation.id}" @click="selectConversation(conversation)"><Icon name="chat" :size="16"/><span>{{conversation.title}}</span><time>{{date(conversation.createdAt)}}</time></button></div>
      <div class="sidebar-bottom"><div class="model-state"><span :class="['status-dot',{ready:state.models.chatConfigured}]"/>{{state.models.chatConfigured?'问答模型已配置':'尚未配置问答模型'}}</div><div class="user-bar"><span class="user-avatar">{{state.user.name?.slice(0,1)||'访'}}</span><div><strong>{{state.user.name}}</strong><span>虚构演示账号</span></div><button class="icon-button" aria-label="退出登录" title="退出登录" :disabled="state.loading.session" @click="workspace.logout"><Icon name="logout" :size="18"/></button></div></div>
    </aside>

    <main class="workspace">
      <header class="workspace-header"><div class="header-crumb"><button class="icon-button mobile-menu" aria-label="展开导航" @click="sidebarOpen=true"><Icon name="menu"/></button><span>{{selectedSpace?.name||'知识工作台'}}</span><Icon name="chevron" :size="15"/><strong>{{state.section==='documents'?'资料库':'知识问答'}}</strong></div><span class="local-badge"><span class="status-dot ready"/>本地演示</span></header>
      <div class="message-zone" aria-live="polite"><div v-if="state.error" class="message error" role="alert"><Icon name="warning" :size="18"/><span>{{state.error}}</span><button v-if="state.spaceId" class="text-button" @click="workspace.loadDocuments">重新加载资料</button><button class="icon-button" aria-label="关闭错误提示" @click="state.error=''"><Icon name="close" :size="16"/></button></div><div v-if="state.notice" class="message success" role="status"><Icon name="check" :size="18"/><span>{{state.notice}}</span><button class="icon-button" aria-label="关闭完成提示" @click="state.notice=''"><Icon name="close" :size="16"/></button></div></div>
      <section v-if="state.section==='ask'" class="ask-workspace" aria-labelledby="ask-title">
        <div class="page-heading"><div><h1 id="ask-title">{{selectedConversation?.title||'从一个问题开始'}}</h1><p>{{selectedConversation?'回答来自当前知识库，引用可打开核对。':'问清楚一件事，找到对应的资料依据。'}}</p></div><button class="button secondary compact" :disabled="!state.spaceId" @click="newConversation"><Icon name="plus" :size="17"/>新建问答</button></div>
        <div ref="turnList" class="turn-list" :aria-busy="state.loading.turns||state.loading.send">
          <div v-if="state.loading.turns" class="loading-placeholder"><span class="spinner"/>正在读取问答记录…</div>
          <div v-else-if="!state.turns.length&&!state.loading.send" class="question-empty"><div class="question-illustration" aria-hidden="true"><div class="sheet sheet-back"/><div class="sheet sheet-front"><span/><span/><span/><b>?</b></div><span class="quote-orbit">“</span></div><h2>资料里的答案，带着出处回来。</h2><p v-if="state.documents.length">可以询问知识库中的流程、规则或操作方法。<br>点击回答下方的引用，查看对应文件和原文。</p><p v-else>先在资料库加入一份手册或规则，<br>再到这里提问。</p><button v-if="!state.documents.length" class="button secondary" @click="state.section='documents'"><Icon name="upload" :size="18"/>添加第一份资料</button><div v-if="state.documents.length" class="question-prompts"><button @click="state.question='请概括当前资料中与新成员有关的注意事项';questionInput?.focus()">概括资料要点<Icon name="arrow" :size="16"/></button><button @click="state.question='这份资料规定了哪些具体操作步骤？请给出出处';questionInput?.focus()">查找操作步骤<Icon name="arrow" :size="16"/></button></div></div>
          <article v-for="turn in state.turns" :key="turn.id" class="turn"><div class="question-message"><span class="turn-label">我的问题</span><p>{{turn.question}}</p></div><div class="answer-message"><span class="answer-mark"><Icon name="book" :size="18"/></span><div class="answer-body"><div class="answer-label">知策<span v-if="turn.status==='insufficient'" class="status-label">资料不足</span></div><p class="answer-text">{{turn.answer}}</p><div v-if="turn.sources?.length" class="citation-section"><span class="citation-label">参考资料</span><div class="citation-list"><button v-for="(source,index) in turn.sources" :key="source.id" @click="showSource(source,index)"><span class="citation-number">{{index+1}}</span><span><strong>{{source.filename}}</strong><small>{{sourceLocation(source)}}</small></span><Icon name="chevron" :size="16"/></button></div></div><details v-if="turn.trace?.length" class="trace-details"><summary><Icon name="trace" :size="16"/>查看查证过程<span>{{turn.trace.length}} 个步骤</span></summary><ol><li v-for="trace in turn.trace" :key="trace.step"><div><strong>{{humanTrace(trace)}}</strong><span>{{trace.elapsedMs}} ms</span></div><pre>{{JSON.stringify(trace.arguments,null,2)}}</pre><p>{{trace.result}}</p></li></ol></details></div></div></article>
          <div v-if="state.loading.send" class="answer-pending" role="status"><span class="spinner"/><span>{{state.mode==='agent'?'正在选择工具并核对资料…':'正在检索资料并整理回答…'}}</span><small>完成后显示回答与引用，可先切换查看资料。</small></div>
        </div>
        <form class="composer" @submit.prevent="workspace.send"><div class="composer-options"><fieldset class="answer-mode"><legend class="visually-hidden">回答方式</legend><label :class="{selected:state.mode==='rag'}"><input v-model="state.mode" type="radio" name="answer-mode" value="rag" :disabled="state.loading.send">资料问答</label><label :class="{selected:state.mode==='agent'}"><input v-model="state.mode" type="radio" name="answer-mode" value="agent" :disabled="state.loading.send">分步查证 <span>Agent</span></label></fieldset><label class="search-select-label"><span class="visually-hidden">检索方式</span><select v-model="state.searchMode" :disabled="state.loading.send"><option value="bm25">关键词检索</option><option value="vector" :disabled="!state.models.embeddingConfigured">语义检索</option><option value="hybrid" :disabled="!state.models.embeddingConfigured">混合检索</option></select></label></div><p class="mode-hint">{{questionHint}}</p><label class="visually-hidden" for="question">向知识库提问</label><textarea id="question" ref="questionInput" v-model="state.question" rows="2" maxlength="1000" :disabled="state.loading.send||!state.spaceId" placeholder="输入问题，例如：新成员如何申请设备？" @keydown="questionKeydown"/><div class="composer-bottom"><span v-if="!state.models.chatConfigured" class="config-hint">尚未配置问答模型，可使用资料库中的原文检索。</span><span v-else>回答需结合原文核对<span class="keyboard-hint"> · Ctrl / ⌘ + Enter 发送</span></span><button class="button primary" :disabled="state.loading.send||state.loading.turns||!state.spaceId||!state.question.trim()||!state.models.chatConfigured"><span v-if="state.loading.send" class="spinner"/><template v-else>发送<Icon name="send" :size="17"/></template></button></div></form>
        <p v-if="!state.models.embeddingConfigured" class="embedding-hint">当前提供关键词检索；配置向量模型后可选择语义或混合检索。</p>
      </section>

      <section v-else class="documents-workspace" aria-labelledby="documents-title"><div class="page-heading"><div><h1 id="documents-title">资料库</h1><p>管理回答所依据的文件，每次替换都会更新资料版本。</p></div><button class="button primary" :disabled="!state.spaceId||state.loading.mutation" @click="chooseUpload"><span v-if="state.loading.mutation" class="spinner"/><Icon v-else name="plus" :size="18"/>{{state.loading.mutation?'正在处理…':'添加资料'}}</button></div><div class="upload-guidance"><span class="file-stack"><Icon name="file" :size="27"/></span><div><strong>上传手册、规则或操作说明</strong><p>Markdown、TXT、可提取文字的 PDF，单个不超过 2 MiB。扫描图片 PDF 暂不支持。</p></div></div><div class="section-topline"><h2>全部资料 <span>{{state.documents.length}}</span></h2><button class="text-button" :disabled="state.loading.documents" @click="workspace.loadDocuments"><Icon name="refresh" :size="15"/>刷新</button></div><div v-if="state.loading.documents" class="loading-placeholder"><span class="spinner"/>正在读取资料…</div><div v-else-if="!state.documents.length" class="documents-empty"><Icon name="file" :size="38"/><h3>还没有资料</h3><p>添加第一份文件，开始建立可追溯的知识库。</p><button class="button secondary" :disabled="state.loading.mutation||!state.spaceId" @click="chooseUpload">选择文件</button></div><div v-else class="document-table-wrap"><table class="document-table"><thead><tr><th scope="col">文件</th><th scope="col">版本 / 段落</th><th scope="col">索引方式</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="document in state.documents" :key="document.id"><td><div class="document-name"><span class="file-type">{{document.filename.split('.').pop().toUpperCase()}}</span><div><strong>{{document.filename}}</strong><span>{{date(document.createdAt)}} 加入</span></div></div></td><td><span class="version-label">v{{document.version}}</span><span class="chunk-count">{{document.chunkCount}} 段</span></td><td><span class="index-label">{{document.indexMode==='bm25'?'关键词':document.indexMode==='vector'?'语义':'关键词 + 语义'}}</span></td><td><div class="document-actions"><button class="text-button" :disabled="state.loading.mutation" @click="chooseReplacement(document)">替换</button><button class="text-button danger-text" :disabled="state.loading.mutation" @click="confirmDelete(document)">删除</button></div></td></tr></tbody></table></div><section class="source-search" aria-labelledby="source-search-title"><div class="search-heading"><Icon name="search" :size="22"/><div><h2 id="source-search-title">先查原文</h2><p>直接检索相关段落，不生成模型回答。</p></div></div><form class="source-search-form" @submit.prevent="workspace.search"><label class="visually-hidden" for="source-query">检索资料内容</label><input id="source-query" v-model="state.searchQuery" maxlength="1000" placeholder="输入关键词或要查找的内容" :disabled="state.loading.search||!state.spaceId"><label><span class="visually-hidden">原文检索方式</span><select v-model="state.searchMode" :disabled="state.loading.search"><option value="bm25">关键词</option><option value="vector" :disabled="!state.models.embeddingConfigured">语义</option><option value="hybrid" :disabled="!state.models.embeddingConfigured">混合</option></select></label><button class="button secondary" :disabled="state.loading.search||!state.spaceId||!state.searchQuery.trim()"><span v-if="state.loading.search" class="spinner"/>{{state.loading.search?'检索中…':'检索'}}</button></form><p v-if="!state.models.embeddingConfigured" class="field-help">语义和混合检索需要先配置向量模型，当前可使用关键词检索。</p><div v-if="state.searchResults" class="search-results" aria-live="polite"><p class="result-caption">{{searchLabels[state.searchResults.mode]||'资料检索'}} · 找到 {{state.searchResults.sources.length}} 个相关段落</p><p v-if="!state.searchResults.sources.length" class="no-results">没有找到相关段落。可以换一种说法，或补充相关资料。</p><button v-for="(source,index) in state.searchResults.sources" :key="source.id" class="search-result" @click="showSource(source,index)"><span class="result-file"><Icon name="file" :size="17"/><strong>{{source.filename}}</strong><small>{{sourceLocation(source)}}</small><Icon name="chevron" :size="17"/></span><span class="result-excerpt">{{source.text}}</span></button></div></section></section>
    </main>

    <aside v-if="state.source" class="source-panel" aria-labelledby="source-title"><div class="source-panel-header"><h2 id="source-title"><Icon name="file" :size="19"/>引用原文</h2><button class="icon-button" aria-label="关闭引用原文" @click="state.source=null"><Icon name="close"/></button></div><div class="source-panel-body"><span class="source-reference">参考资料 {{state.source.number}}</span><h3>{{state.source.filename}}</h3><p class="source-position">{{sourceLocation(state.source)}}</p><div class="source-passage">{{state.source.text}}</div><p class="source-note">以上是回答时引用的资料片段。资料可能已被更新，请结合当前版本核对。</p></div></aside>
    <input ref="uploadInput" class="visually-hidden" type="file" tabindex="-1" accept=".md,.txt,.pdf" @change="uploadChanged"><input ref="replaceInput" class="visually-hidden" type="file" tabindex="-1" accept=".md,.txt,.pdf" @change="replacementChanged">
    <dialog ref="confirmDialog" class="confirm-dialog" aria-labelledby="confirm-title" @cancel="pendingChange=null"><template v-if="pendingChange"><span class="dialog-symbol"><Icon :name="pendingChange.type==='delete'?'warning':'refresh'" :size="25"/></span><h2 id="confirm-title">{{pendingChange.type==='delete'?'删除这份资料？':'替换这份资料？'}}</h2><p class="dialog-file">{{pendingChange.document.filename}}</p><p v-if="pendingChange.type==='delete'">将删除当前第 {{pendingChange.document.version}} 版及其检索段落。已有问答保留历史引用，后续检索将不再使用这份资料。</p><p v-else>使用「{{pendingChange.file.name}}」替换当前第 {{pendingChange.document.version}} 版。处理成功后，新问题使用新版本；若版本已变化，本次操作将停止。</p><div class="dialog-actions"><button class="button secondary" autofocus @click="closeConfirmation">取消</button><button :class="['button',pendingChange.type==='delete'?'danger':'primary']" @click="applyChange">{{pendingChange.type==='delete'?'确认删除':'确认替换'}}</button></div></template></dialog>
  </div>
</template>

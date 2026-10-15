export function createContextGate() {
  let epoch=0,context=[];
  const lanes=new Map();
  function set(...next) {
    if(next.length!==context.length||next.some((value,i)=>value!==context[i])) { epoch++;context=next;lanes.clear(); }
  }
  function capture(){return {epoch};}
  function start(lane){const sequence=(lanes.get(lane)||0)+1;lanes.set(lane,sequence);return {epoch,lane,sequence};}
  function current(token){return token.epoch===epoch&&(!token.lane||lanes.get(token.lane)===token.sequence);}
  return {set,capture,start,current};
}
export function validateUpload(file) {
  if(!file)return '请选择资料文件。';
  if(!file.name||file.name.length>100||/[\\/\x00-\x1f]/.test(file.name))return '文件名须在 100 字以内，且不能包含路径。';
  if(!/\.(md|txt|pdf)$/i.test(file.name))return '支持 Markdown、TXT 和可提取文字的 PDF。';
  if(file.size<=0)return '文件内容为空，请选择有文字的文件。';
  if(file.size>2*1024*1024)return '单个文件不能超过 2 MiB。';
  return null;
}
export function sourceLocation(source) {
  const lines=source.lineStart===source.lineEnd?`第 ${source.lineStart} 行`:`第 ${source.lineStart}–${source.lineEnd} 行`;
  return source.page>0?`第 ${source.page} 页 · ${lines}`:lines;
}
export function selectSearchMode(mode, embeddingConfigured) {
  return embeddingConfigured&&['bm25','vector','hybrid'].includes(mode)?mode:'bm25';
}

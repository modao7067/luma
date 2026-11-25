import fs from 'node:fs';
import {pathToFileURL} from 'node:url';
import {hostServer,receiptBook} from './host-server.mjs';
import {observeMessages,submissionScope} from './message-identity.mjs';
const isZh=()=>(String(process.env.LUMA_LANG||'').trim()||'zh').toLowerCase().startsWith('zh');
const tr=(zh,en)=>(isZh()?zh:en);
const options=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
function failContract(message){
 try{fs.writeFileSync(options.socket+'.error',message,{mode:0o600});}catch{}
 process.exit(1);
}
let sdk,resolveProjectTrusted,createProjectTrustContext,applyHttpProxySettings,configureHttpDispatcher;
const sdkUrl=pathToFileURL(options.sdk);
try{
 sdk=await import(sdkUrl);
 ({resolveProjectTrusted}=await import(new URL('./core/project-trust.js',sdkUrl)));
 ({createProjectTrustContext}=await import(new URL('./cli/project-trust.js',sdkUrl)));
 ({applyHttpProxySettings,configureHttpDispatcher}=await import(new URL('./core/http-dispatcher.js',sdkUrl)));
}catch{
 failContract(tr('当前 Pi SDK 模块结构不兼容；原有 RPC 会话入口保持可用','Current Pi SDK module layout is incompatible; legacy RPC session entry remains available'));
}
const {createAgentSessionRuntime,createAgentSessionServices,createAgentSessionFromServices,SessionManager,InteractiveMode,ExtensionEditorComponent,SettingsManager,ProjectTrustStore,hasTrustRequiringProjectResources,initTheme,resolveModelScopeWithDiagnostics,parseSkillBlock}=sdk;
if([createAgentSessionRuntime,createAgentSessionServices,createAgentSessionFromServices,SessionManager,InteractiveMode,ExtensionEditorComponent,SettingsManager,ProjectTrustStore,hasTrustRequiringProjectResources,resolveProjectTrusted,createProjectTrustContext,applyHttpProxySettings,configureHttpDispatcher].some(fn=>typeof fn!=='function')){
 failContract(tr('当前 Pi SDK 导出契约不兼容；原有 RPC 会话入口保持可用','Current Pi SDK export contract is incompatible; legacy RPC session entry remains available'));
}
let runtime;const identity=()=>runtime?.session.sessionId??options.nativeId;
let transport;const emit=e=>transport?.emit(e);
const book=receiptBook(options.receipts,identity,emit);
const dialogs=new Map();
const messageObservers=new WeakMap();
const trustStore=new ProjectTrustStore(options.agentDir);const trustByCwd=new Map();
function decorate(session,services){
 const bind=session.bindExtensions.bind(session);
 session.bindExtensions=bindings=>{
  const ui=bindings.uiContext;if(!ui)return bind(bindings);
  const both={...ui};
  for(const method of ['select','confirm','input','editor'])both[method]=(...args)=>{
   const id=crypto.randomUUID();const controller=new AbortController();
   let finish;let settled=false;let finalValue;let externalAbort;let doneNative;
   const promise=new Promise(resolve=>{finish=value=>{if(settled)return;settled=true;finalValue=value;dialogs.delete(id);controller.abort();doneNative?.(value);emit({type:'extension_ui_request',method:'cancel',targetId:id});resolve(value);};});
   const request={type:'extension_ui_request',id,method,title:args[0],...(method==='select'?{options:args[1]}:method==='confirm'?{message:args[1]}:method==='editor'?{prefill:args[1],placeholder:args[1]}:{placeholder:args[1]}),secret:args[2]?.secret};
   dialogs.set(id,{request,finish});emit(request);
   if(method==='editor'){
    void ui.custom((tui,_theme,keybindings,done)=>{doneNative=done;if(settled)queueMicrotask(()=>done(finalValue));return new ExtensionEditorComponent(tui,keybindings,args[0],args[1],finish,()=>finish(undefined),undefined,services.settingsManager.getExternalEditorCommand());}).then(finish);
   }else{
    const opts=args[2]??{};if(opts.signal){externalAbort=()=>finish(method==='confirm'?false:undefined);opts.signal.addEventListener('abort',externalAbort,{once:true});if(opts.signal.aborted)externalAbort();}
    void ui[method](args[0],args[1],{...opts,signal:controller.signal}).then(finish,()=>finish(method==='confirm'?false:undefined));
    void promise.finally(()=>opts.signal?.removeEventListener('abort',externalAbort));
   }
   return promise;
  };
  return bind({...bindings,uiContext:both});
 };
 messageObservers.set(session,observeMessages(session,book,emit,event=>{
  if(event.type==='agent_settled'||event.type==='compaction_end'){
   emit({type:'luma_stats',stats:session.getSessionStats(),contextUsage:session.getContextUsage(),sessionFile:session.sessionFile,model:session.model});
  }else if(event.type==='session_info_changed'||event.type==='thinking_level_changed'){
   emit({type:'luma_state',sessionId:session.sessionId,sessionName:session.sessionName??'',model:session.model,thinkingLevel:session.thinkingLevel});
  }
 },{parseSkillBlock,compactUpdates:true}));
}
const savedSessionFile=options.sessionFile&&fs.existsSync(options.sessionFile)?options.sessionFile:undefined;
const factory=async ({cwd,agentDir,sessionManager,sessionStartEvent,projectTrustContext})=>{
 const requiresTrust=hasTrustRequiringProjectResources(cwd);const cached=trustByCwd.get(cwd);
 const settingsManager=SettingsManager.create(cwd,agentDir,{projectTrusted:cached??trustStore.get(cwd)??!requiresTrust});
 const services=await createAgentSessionServices({cwd,agentDir,settingsManager,resourceLoaderOptions:{additionalSkillPaths:options.skills??[]},
  resourceLoaderReloadOptions:requiresTrust&&cached===undefined?{resolveProjectTrust:async({extensionsResult})=>{
   const trusted=await resolveProjectTrusted({cwd,trustStore,defaultProjectTrust:settingsManager.getDefaultProjectTrust(),extensionsResult,
    projectTrustContext:projectTrustContext??createProjectTrustContext({cwd,mode:'interactive',hasUI:true,settingsManager})});trustByCwd.set(cwd,trusted);return trusted;
  }}:undefined});
 const enabledPatterns=settingsManager.getEnabledModels?.();
 const scopedModels=typeof resolveModelScopeWithDiagnostics==='function'&&Array.isArray(enabledPatterns)&&enabledPatterns.length>0
  ?(await resolveModelScopeWithDiagnostics(enabledPatterns,services.modelRuntime,{signal:AbortSignal.timeout(15000)})).scopedModels
  :undefined;
 const hasExisting=sessionManager.buildSessionContext().messages.length>0;
 const model=options.model&&!savedSessionFile&&!hasExisting?services.modelRuntime.getModel(...options.model.split(/\/(.*)/s,2)):undefined;
 const thinkingLevel=options.effort&&!savedSessionFile&&!hasExisting?options.effort:undefined;
 const created=await createAgentSessionFromServices({services,sessionManager,sessionStartEvent,model,thinkingLevel,scopedModels});decorate(created.session,services);
 return {...created,services,diagnostics:services.diagnostics};
};
const bootstrapSettings=SettingsManager.create(process.cwd(),options.agentDir,{projectTrusted:false});
applyHttpProxySettings(bootstrapSettings.getGlobalSettings().httpProxy);
configureHttpDispatcher(bootstrapSettings.getHttpIdleTimeoutMs());
const manager=savedSessionFile?SessionManager.open(savedSessionFile):SessionManager.create(process.cwd(),undefined,{id:options.nativeId});
runtime=await createAgentSessionRuntime(factory,{cwd:process.cwd(),agentDir:options.agentDir,sessionManager:manager});
applyHttpProxySettings(runtime.services.settingsManager.getGlobalSettings().httpProxy);
configureHttpDispatcher(runtime.services.settingsManager.getHttpIdleTimeoutMs());
initTheme?.(runtime.services.settingsManager.getTheme(),true);
transport=hostServer(options,identity,async p=>{
 const s=runtime.session;
 switch(p.type){
  case 'get_state':return {sessionId:s.sessionId,sessionFile:s.sessionFile,sessionName:s.sessionName,model:s.model,thinkingLevel:s.thinkingLevel,isStreaming:s.isStreaming,isCompacting:s.isCompacting,isSettled:s.isIdle,pendingMessageCount:s.pendingMessageCount,queuedMessages:messageObservers.get(s).queue(),contextUsage:s.getContextUsage(),receipts:book.entries,requests:[...dialogs.values()].map(d=>d.request),observerCount:transport?.clients.size??0};
  case 'get_messages':return {messages:messageObservers.get(s).messages()};
  case 'get_available_models':return {models:s.modelRuntime.getAvailableSnapshot()};
  case 'get_available_thinking_levels':return {levels:s.getAvailableThinkingLevels()};
  case 'get_session_stats':return {...s.getSessionStats(),contextUsage:s.getContextUsage()};
  case 'get_commands':return {commands:s.resourceLoader.getSkills().skills.map(v=>({name:'skill:'+v.name,description:v.description,source:'skill',sourceInfo:{path:v.filePath}}))};
  case 'set_model':{const model=s.modelRuntime.getAvailableSnapshot().find(m=>m.provider===p.provider&&m.id===p.modelId);if(!model)throw Error(tr('原生模型不可用','Native model is unavailable'));await s.setModel(model);return {};}
  case 'set_thinking_level':s.setThinkingLevel(p.level);return {level:s.thinkingLevel};
  case 'set_session_name':s.setSessionName(p.name);return {};
  case 'abort':await s.abort();return {};
  case 'compact':return await s.compact();
  case 'prompt':{
   const payload={message:p.message,images:p.images??[],streamingBehavior:p.streamingBehavior};
   const {receipt,fresh}=book.begin(p.submissionId,payload);if(!fresh)return {state:receipt.state};
   return await new Promise(resolve=>{
    void submissionScope.run(p.submissionId,()=>s.prompt(p.message,{images:p.images,source:'rpc',streamingBehavior:p.streamingBehavior,preflightResult:disposition=>{book.settle(p.submissionId,'accepted',{disposition,...(disposition==='handled'?{consumed:true}:{})});resolve({state:'accepted',disposition});}}))
    .then(()=>{if(receipt.state==='pending'){book.settle(p.submissionId,'unknown');resolve({state:'unknown'});}})
    .catch(error=>{if(receipt.state!=='accepted'){book.settle(p.submissionId,'rejected',{error:String(error)});resolve({state:'rejected',error:String(error)});}});
   });
  }
  case 'extension_ui_response':{
   const dialog=dialogs.get(p.requestId);if(!dialog)return {answered:false};const r=dialog.request;let value;
   if(p.cancelled)value=r.method==='confirm'?false:undefined;
   else if(r.method==='confirm')value=p.confirmed===true;
   else if(r.method==='select'){if(!r.options.includes(p.value))throw Error(tr('无效原生选项','Invalid native option'));value=p.value;}
   else value=p.value;
   dialog.finish(value);return {answered:true};
  }
  default:throw Error(tr('不支持的原生操作','Unsupported native operation'));
 }
});
const tui=new InteractiveMode(runtime,{});
let closing=false;
async function shutdown(code=0){
 if(closing)return;closing=true;
 try{tui.stop();}catch{}
 try{await runtime?.dispose();}catch{}
 try{transport?.detach();}catch{}
 process.exit(code);
}
process.on('SIGTERM',()=>void shutdown(143));
process.on('SIGHUP',()=>void shutdown(129));
await tui.init();void tui.run().finally(()=>shutdown(0));
let previousState;setInterval(()=>{if(!transport?.clients?.size)return;const s=runtime.session;const state={type:'luma_state',sessionId:identity(),sessionName:s.sessionName??'',model:s.model,thinkingLevel:s.thinkingLevel};const next=JSON.stringify(state);if(next!==previousState){previousState=next;emit(state);}},1000).unref();

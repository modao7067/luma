import {AsyncLocalStorage} from 'node:async_hooks';
export const submissionScope=new AsyncLocalStorage();
export function observeMessages(session,book,emit,after=()=>{},opts={}){
 const tags=new WeakMap();const liveIds=new WeakMap();const queueIds=new WeakMap();const queued={steering:[],followUp:[]};const agent=session.agent;
 const parseSkill=typeof opts.parseSkillBlock==='function'?opts.parseSkillBlock:null;
 const PI_IMAGE_HINT_TAIL_RE=/(?:\s*(?:\[Image: original \d+x\d+, displayed at \d+x\d+\. Multiply coordinates by \d+(?:\.\d+)? to map to original image\.\]|\[Image converted from [^\r\n\]]+ to [^\r\n\]]+\.\]|\[Image omitted: [^\r\n\]]+\]))+\s*$/;
 const stripPiImageHints=value=>typeof value==='string'&&value.includes('[Image')?value.replace(PI_IMAGE_HINT_TAIL_RE,''):value;
 const rawUserText=message=>typeof message?.content==='string'?message.content:(message?.content??[]).filter(b=>b.type==='text').map(b=>b.text).join('\n');
 const skillMeta=message=>{
  if(message?.role==='custom'&&message.customType==='skill-prompt'&&message.details?.name){
   return {name:'skill:'+message.details.name,userMessage:message.details.args??''};
  }
  if(!parseSkill||message?.role!=='user')return undefined;
  const raw=rawUserText(message);if(!raw.startsWith('<skill '))return undefined;
  const parsed=parseSkill(raw);return parsed?{name:parsed.name,userMessage:parsed.userMessage??''}:undefined;
 };
 let activeAssistantId;let seq=0;const claimedIds=new Set();
 const uniqueNaturalId=message=>{
  if(!message||typeof message!=='object')return undefined;
  if(typeof message.id==='string'&&message.id){claimedIds.add(message.id);return message.id;}
  if(typeof message.timestamp!=='number')return undefined;
  const base=`${message.role??'message'}-${message.timestamp}`;
  if(!claimedIds.has(base)){claimedIds.add(base);return base;}
  let candidate;
  do{seq+=1;candidate=`${base}-${seq}`;}while(claimedIds.has(candidate));
  claimedIds.add(candidate);
  return candidate;
 };
 const assignId=message=>{
  if(!message||typeof message!=='object')return undefined;
  if(tags.has(message))return tags.get(message);
  let id=liveIds.get(message);
  if(id)return id;
  id=uniqueNaturalId(message);
  if(!id){
   const entries=session.sessionManager?.getEntries?.()??[];
   const entry=entries.find(e=>e.type==='message'&&e.message===message);
   id=entry?.id??crypto.randomUUID();
   claimedIds.add(id);
  }
  liveIds.set(message,id);
  return id;
 };
 const queue=()=>Object.fromEntries(Object.entries(queued).map(([name,items])=>[name,items.map(message=>{
  const raw=stripPiImageHints(rawUserText(message));const skill=skillMeta(message);
  const display=skill?(skill.userMessage?`/${skill.name} ${stripPiImageHints(skill.userMessage)}`:`/${skill.name}`):raw;
  return {id:queueIds.get(message),message:display,...(skill?{_skillBlock:skill}:{}),...(tags.has(message)?{clientUserMessageId:tags.get(message)}:{})};
 })]));
 const queueChanged=()=>emit({type:'luma_queue',queue:queue()});
 for(const method of ['prompt','steer','followUp']){
  const original=agent[method].bind(agent);
  agent[method]=(input,...rest)=>{const id=submissionScope.getStore();if(id){for(const m of Array.isArray(input)?input:[input])if(m&&typeof m==='object'&&(m.role==='user'||(m.role==='custom'&&m.attribution==='user')))tags.set(m,id);}const result=original(input,...rest);if(method!=='prompt'&&input&&typeof input==='object'){const name=method==='steer'?'steering':'followUp';queueIds.set(input,queueIds.get(input)??crypto.randomUUID());queued[name].push(input);queueChanged();}return result;};
 }
 for(const [method,names] of [['clearSteeringQueue',['steering']],['clearFollowUpQueue',['followUp']],['clearAllQueues',['steering','followUp']],['reset',['steering','followUp']]])if(typeof agent[method]==='function'){const original=agent[method].bind(agent);agent[method]=(...args)=>{const result=original(...args);for(const name of names)queued[name]=[];queueChanged();return result;};}
 const unsubscribe=session.subscribe(event=>{
  const subId=event.message&&tags.get(event.message);
  const isAssistant=event.message?.role==='assistant';
  if(event.type==='message_start'){
   let changed=false;for(const name of ['steering','followUp']){const count=queued[name].length;queued[name]=queued[name].filter(message=>message!==event.message);changed=changed||count!==queued[name].length;}
   if(changed)queueChanged();
   if(subId)book.settle(subId,'accepted',{consumed:true});
   if(isAssistant){
    activeAssistantId=subId??assignId(event.message);
    const liveTail=session.messages?.at?.(-1);
    if(liveTail&&typeof liveTail==='object'&&liveTail.role==='assistant')liveIds.set(liveTail,activeAssistantId);
   }
  }
  let outEvent=event;
  if(opts.compactUpdates&&event.type==='message_update'&&event.assistantMessageEvent){
   const ame=event.assistantMessageEvent;const {partial,...rest}=ame;
   const tc=ame.type==='toolcall_start'?partial?.content?.[ame.contentIndex]:undefined;
   const msgId=(isAssistant?activeAssistantId:undefined)??assignId(event.message);
   if(isAssistant&&msgId&&!activeAssistantId)activeAssistantId=msgId;
   outEvent={type:'message_update',...(msgId?{_messageId:msgId}:{}),usage:event.message?.usage,assistantMessageEvent:tc?.type==='toolCall'?{...rest,id:tc.id,toolName:tc.name}:rest};
  }else if(event.message){
   let msgId;
   if(isAssistant&&activeAssistantId){
    msgId=activeAssistantId;
    liveIds.set(event.message,msgId);
   }else{
    msgId=subId??assignId(event.message);
   }
   const skill=skillMeta(event.message);
   outEvent={...event,message:{...event.message,...(msgId?{id:msgId}:{}),...(skill?{_skillBlock:skill}:{})},...(subId?{_submissionId:subId}:{})};
  }
  if((event.type==='message_end'&&isAssistant)||event.type==='agent_end'||event.type==='agent_settled'||event.type==='session_settled'){
   activeAssistantId=undefined;
  }
  emit(outEvent);after(event);
  if(subId&&event.type==='message_end')setImmediate(()=>{
   const entry=session.sessionManager.getEntries().find(e=>e.type==='message'&&e.message===event.message);
   if(entry)book.settle(subId,'accepted',{consumed:true,nativeEntryId:entry.id});
  });
 });
 return {unsubscribe,queue,identity(message){const id=tags.get(message);return id?{clientUserMessageId:id}:{};},messages(){
  const entries=session.sessionManager.getEntries();const byEntry=new Map(Object.entries(book.entries).filter(([,r])=>r.nativeEntryId).map(([id,r])=>[r.nativeEntryId,id]));
  return session.messages.map(message=>{
   let id=tags.get(message);
   if(!id){const entry=entries.find(e=>e.type==='message'&&e.message===message);id=(entry&&byEntry.get(entry.id))??liveIds.get(message)??uniqueNaturalId(message)??entry?.id;}
   if(id){liveIds.set(message,id);claimedIds.add(id);}
   const skill=skillMeta(message);
   return (id||skill)?{...message,...(id?{id}:{}),...(skill?{_skillBlock:skill}:{})}:message;
  });
 }};
}

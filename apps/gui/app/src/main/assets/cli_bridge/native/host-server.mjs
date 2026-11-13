import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
export function durableWrite(file, value) {
 const tmp=file+'.tmp'; const fd=fs.openSync(tmp,'w',0o600);
 try {fs.writeFileSync(fd,JSON.stringify(value));fs.fsyncSync(fd);} finally {fs.closeSync(fd);}
 fs.renameSync(tmp,file); const dir=fs.openSync(path.dirname(file),'r');try{fs.fsyncSync(dir);}finally{fs.closeSync(dir);}
}
export const canonical=value=>JSON.stringify(value,(_k,v)=>v&&typeof v==='object'&&!Array.isArray(v)?Object.fromEntries(Object.keys(v).sort().map(k=>[k,v[k]])):v);
export function hostServer(options, identity, handler) {
 const clients=new Set();const requests=new Map();
 const emit=value=>{const data=JSON.stringify({...value,_nativeId:identity()})+'\n';for(const s of clients)if(!s.destroyed)s.write(data)};
 let socketInode;const server=net.createServer(s=>{clients.add(s);let buffer='';s.on('error',()=>{});s.on('close',()=>clients.delete(s));s.on('data',data=>{
  buffer+=data;if(Buffer.byteLength(buffer)>32*1024*1024){s.destroy();return;}let end;
  while((end=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,end);buffer=buffer.slice(end+1);if(line.trim())void dispatch(line,s);}
 });});
 async function dispatch(line,s){let p;try{p=JSON.parse(line);if(p.nativeId&&p.nativeId!==identity())throw Error('原生前台会话已改变，请重新核对身份');
  const data=await handler(p);if(!s.destroyed)s.write(JSON.stringify({id:p.id,type:'response',success:true,data})+'\n');
 }catch(e){if(!s.destroyed)s.write(JSON.stringify({id:p?.id,type:'response',success:false,error:String(e.message??e)})+'\n');}}
 void (async()=>{
  if(fs.existsSync(options.socket)){
   const stat=fs.lstatSync(options.socket);if(!stat.isSocket()||stat.uid!==process.getuid())throw Error('原生控制端点归属不符');
   await new Promise((resolve,reject)=>{const probe=net.createConnection(options.socket);probe.once('connect',()=>{probe.destroy();reject(Error('原生控制端点仍被使用'));});probe.once('error',error=>{if(error.code==='ECONNREFUSED'){fs.unlinkSync(options.socket);resolve();}else reject(error);});});
  }
  server.listen(options.socket,()=>{fs.chmodSync(options.socket,0o600);socketInode=fs.lstatSync(options.socket).ino;});
 })().catch(error=>server.emit('error',error));
 return {emit,clients,requests,server,detach(){for(const client of clients)client.destroy();server.close(()=>{try{if(fs.lstatSync(options.socket).ino===socketInode)fs.unlinkSync(options.socket);}catch(error){if(error.code!=='ENOENT')throw error;}});}};
}
export function receiptBook(file,identity,emit) {
 const entries=fs.existsSync(file)?JSON.parse(fs.readFileSync(file,'utf8')):{};
 for(const r of Object.values(entries))if(r.state==='pending')r.state='unknown';
 const save=()=>durableWrite(file,entries);
 return {entries,save,begin(id,payload){
  if(typeof id!=='string'||!id||id.length>160)throw Error('无效发送标识');
  const old=entries[id];if(old){if(old.sessionId!==identity()||canonical(old.payload)!==canonical(payload))throw Error('发送标识已用于不同会话或内容');return {receipt:old,fresh:false};}
  const receipt={sessionId:identity(),payload,state:'pending'};entries[id]=receipt;save();return {receipt,fresh:true};
 },settle(id,state,extra={}){const r=entries[id];if(!r)return;if(r.state==='accepted'&&state!=='accepted')return;Object.assign(r,extra,{state});save();emit({type:'luma_receipt',submissionId:id,state,...extra});}};
}

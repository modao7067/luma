package io.github.modao7067.luma.root;

import android.content.Context;
import java.io.*;

final class TerminalLinkReader {
    private static final String ALLOWED_PREFIX_COPY_SCRIPT="import os,stat,sys\n"
        +"_zh=os.environ.get('LUMA_LANG','zh').lower().startswith('zh')\n"
        +"def _tr(z,e):return z if _zh else e\n"
        +"def safe_allowed_path(raw,allowed):\n"
        +" err=_tr('不允许预览系统伪文件系统路径','Previewing system pseudo-filesystem paths is not allowed')\n"
        +" prefix=os.path.normpath(os.path.abspath(allowed)).rstrip('/')\n"
        +" if not prefix.startswith('/proc/1/root/') or len(prefix)<=len('/proc/1/root/') or os.path.islink(prefix):raise ValueError(err)\n"
        +" norm_raw=os.path.normpath(os.path.abspath(raw))\n"
        +" if norm_raw!=prefix and not norm_raw.startswith(prefix+'/'):raise ValueError(err)\n"
        +" rel=norm_raw[len(prefix):].lstrip('/')\n"
        +" if not rel:raise ValueError(err)\n"
        +" cur=prefix;stack=[s for s in rel.split('/') if s and s!='.'];hops=0\n"
        +" while stack:\n"
        +"  seg=stack.pop(0)\n"
        +"  if seg=='..':\n"
        +"   if cur==prefix:raise ValueError(err)\n"
        +"   cur=os.path.dirname(cur.rstrip('/'))\n"
        +"   if cur!=prefix and not cur.startswith(prefix+'/'):raise ValueError(err)\n"
        +"   continue\n"
        +"  nxt=os.path.join(cur,seg)\n"
        +"  if os.path.islink(nxt):\n"
        +"   hops+=1\n"
        +"   if hops>40:raise ValueError(_tr('符号链接层级过深','Too many symbolic link levels'))\n"
        +"   target=os.readlink(nxt)\n"
        +"   joined=target if os.path.isabs(target) else os.path.join(cur,target)\n"
        +"   norm_t=os.path.normpath(joined)\n"
        +"   if norm_t!=prefix and not norm_t.startswith(prefix+'/'):raise ValueError(err)\n"
        +"   cur=prefix;rel_t=norm_t[len(prefix):].lstrip('/');stack=[s for s in rel_t.split('/') if s and s!='.']+stack\n"
        +"  else:cur=nxt\n"
        +" if cur!=prefix and not cur.startswith(prefix+'/'):raise ValueError(err)\n"
        +" return cur\n"
        +"path=sys.argv[1]\n"
        +"try:\n"
        +" limit=int(sys.argv[2]);allowed=sys.argv[3]\n"
        +" safe_target=safe_allowed_path(path,allowed)\n"
        +" fd=os.open(safe_target,os.O_RDONLY|os.O_NONBLOCK|os.O_NOFOLLOW)\n"
        +" try:\n"
        +"  info=os.fstat(fd)\n"
        +"  if not stat.S_ISREG(info.st_mode):raise ValueError(_tr('只能预览普通文件','Only regular files can be previewed'))\n"
        +"  if info.st_size>limit:raise ValueError(_tr('文件超过预览大小限制','File exceeds preview size limit'))\n"
        +"  with os.fdopen(fd,'rb') as f:\n"
        +"   fd=-1\n"
        +"   while True:\n"
        +"    chunk=f.read(65536)\n"
        +"    if not chunk:break\n"
        +"    sys.stdout.buffer.write(chunk)\n"
        +" finally:\n"
        +"  if fd>=0:os.close(fd)\n"
        +"except FileNotFoundError:sys.stderr.write(_tr('找不到文件：','File not found: ')+path);sys.exit(2)\n"
        +"except PermissionError:sys.stderr.write(_tr('没有文件读取权限：','Permission denied reading file: ')+path);sys.exit(3)\n"
        +"except (OSError,ValueError) as error:sys.stderr.write(str(error));sys.exit(4)\n";
    static void copy(Context context,String path,File destination,long limit)throws IOException {copy(context,path,0,destination,limit,false);}
    static void copy(Context context,String path,File destination,long limit,String allowedPrefix)throws IOException {
        if(allowedPrefix==null || allowedPrefix.isEmpty()){
            copy(context,path,0,destination,limit,false,80);
            return;
        }
        boolean builtin=RootEntry.usesBuiltinChroot(context);
        String entry=RootEntry.launcher(context);
        String command="exec "+(builtin?RootEntry.quote(entry):entry)+" /usr/bin/env LUMA_LANG="+TerminalFileTransfer.quote(com.termux.terminal.LumaLang.code())+" /usr/bin/python3 -I -c "
                +TerminalFileTransfer.quote(ALLOWED_PREFIX_COPY_SCRIPT)+" "+TerminalFileTransfer.quote(path)+" "+limit+" "+TerminalFileTransfer.quote(allowedPrefix);
        TerminalFileTransfer.copy(new ProcessBuilder(RootEntry.suExecutable(),"-c",command),destination,limit);
    }
    static void copy(Context context,String path,int pid,File destination,long limit,boolean highlight)throws IOException {copy(context,path,pid,destination,limit,highlight,80);}
    static void copy(Context context,String path,int pid,File destination,long limit,boolean highlight,int columns)throws IOException {
        boolean builtin=RootEntry.usesBuiltinChroot(context);
        String entry=RootEntry.launcher(context);
        String command="exec "+(builtin?RootEntry.quote(entry):entry)+" /usr/bin/env LUMA_LANG="+TerminalFileTransfer.quote(com.termux.terminal.LumaLang.code())+" /usr/bin/python3 -I -c "
                +TerminalFileTransfer.quote(TerminalFileTransfer.SCRIPT)+" "+TerminalFileTransfer.quote(path)+" "+limit+" "+pid+" "+(highlight ? "ansi" : "raw")+" "+columns;
        TerminalFileTransfer.copy(new ProcessBuilder(RootEntry.suExecutable(),"-c",command),destination,limit);
    }
}

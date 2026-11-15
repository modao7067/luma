package io.github.modao7067.luma.gui.runtime

internal object RuntimeScripts {
    val mediaMetadata = """
import json,mimetypes,os,stat,sys
def _safe_guest_path(raw):
    cur='/'
    stack=[s for s in os.path.normpath(os.path.abspath(raw)).split('/') if s and s!='.']
    hops=0
    while stack:
        seg=stack.pop(0)
        if seg=='..':
            cur=os.path.dirname(cur.rstrip('/')) or '/'
            continue
        nxt=os.path.join(cur,seg)
        if nxt in ('/proc','/sys','/dev') or nxt.startswith(('/proc/','/sys/','/dev/')):
            raise ValueError(('Accessing system pseudo-filesystem paths is not allowed' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '不允许访问系统伪文件系统路径'))
        if os.path.islink(nxt):
            hops+=1
            if hops>40:raise ValueError(('Too many levels of symbolic links' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '符号链接层级过深'))
            target=os.readlink(nxt)
            joined=target if os.path.isabs(target) else os.path.join(cur,target)
            norm_t=os.path.normpath(joined)
            if norm_t in ('/proc','/sys','/dev') or norm_t.startswith(('/proc/','/sys/','/dev/')):
                raise ValueError(('Accessing system pseudo-filesystem paths is not allowed' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '不允许访问系统伪文件系统路径'))
            cur='/'
            stack=[s for s in norm_t.split('/') if s and s!='.']+stack
        else:
            cur=nxt
    real=os.path.realpath(cur)
    if real in ('/proc','/sys','/dev') or real.startswith(('/proc/','/sys/','/dev/')):
        raise ValueError(('Accessing system pseudo-filesystem paths is not allowed' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '不允许访问系统伪文件系统路径'))
    return real
try:p=_safe_guest_path(sys.argv[1])
except (OSError,ValueError) as error:raise SystemExit(str(error))
s=os.stat(p)
if stat.S_ISDIR(s.st_mode):
    print(json.dumps({'size':0,'mime':'inode/directory'}))
    sys.exit(0)
if not stat.S_ISREG(s.st_mode):raise SystemExit(('Only regular files can be read' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '只能读取普通文件'))
m=mimetypes.guess_type(p)[0] or ''
if m in ('','application/octet-stream'):
    try:
        with open(p,'rb') as f:b=f.read(8192)
        if b and b'\x00' not in b:
            try:b.decode('utf-8');m='text/plain'
            except UnicodeDecodeError as e:
                if len(b)==8192 and e.start>=len(b)-3:m='text/plain'
    except OSError:pass
print(json.dumps({'size':s.st_size,'mime':m}))
""".trimIndent()

    val openCodeServer = """
import json,os,sys
port,password=sys.argv[1:]
with open(password,encoding='utf-8') as source:os.environ['OPENCODE_PASSWORD']=source.read().strip()
config=json.loads(os.environ.get('OPENCODE_CONFIG_CONTENT') or '{}')
skills=config.setdefault('skills',[])
if not isinstance(skills,list):raise SystemExit(('OpenCode 2.x skills configuration must be an array' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else 'OpenCode 2.x skills 配置必须是数组'))
root='/opt/luma/agent/skills'
if root not in skills:skills.append(root)
os.environ['OPENCODE_CONFIG_CONTENT']=json.dumps(config)
os.execvp('opencode',['opencode','serve','--hostname','127.0.0.1','--port',port])
""".trimIndent()

    val externalCodexRecovery = """
import errno,json,os,pathlib,socket,subprocess,sys
selected=pathlib.Path(sys.argv[1])
def reachable():
    with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as client:
        client.settimeout(2)
        try:client.connect(str(selected));return True
        except OSError as error:
            if error.errno not in (errno.ENOENT,errno.ECONNREFUSED):
                raise SystemExit(('Cannot access original Codex socket; check permissions' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '无法访问原 Codex socket；请检查原服务权限'))
            return False
if reachable():sys.exit(0)
home=pathlib.Path(os.environ.get('CODEX_HOME') or pathlib.Path.home()/'.codex')
control=home/'app-server-control'/'app-server-control.sock'
if control.resolve()!=selected.resolve():
    raise SystemExit(('Original external Codex service exited; restart it in the original client and refresh' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '原外部 Codex 服务已退出；此 socket 不是当前官方 daemon 端点，请由原客户端启动后刷新'))
try:
    result=subprocess.run(['codex','app-server','daemon','start'],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=25)
    if result.returncode:raise ValueError('start failed')
    endpoint=json.loads(result.stdout)['socketPath']
    if pathlib.Path(endpoint).resolve()!=selected.resolve() or not reachable():raise ValueError('endpoint mismatch')
except (OSError,ValueError,KeyError,subprocess.TimeoutExpired):
    raise SystemExit(('Original Codex daemon failed to recover; start it in the original client and refresh (session ID and pending inputs are preserved)' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '原 Codex daemon 未能恢复，请在原客户端启动后刷新；原会话 ID 和未确认消息已保留'))
""".trimIndent()

    val rootServer = """
import fcntl,json,os,pathlib,shutil,socket,subprocess,sys,time,urllib.request
base=pathlib.Path(sys.argv[1]); token=base/'capability-token'
owner=token.stat()
def save(path,value):
    temp=path.with_suffix('.new')
    temp.write_text(json.dumps(value))
    os.chmod(temp,0o600); os.chown(temp,owner.st_uid,owner.st_gid)
    os.replace(temp,path)
def stamp(pid):
    try:return pathlib.Path('/proc',str(pid),'stat').read_text().split(') ',1)[1].split()[19]
    except (OSError,IndexError):return None
def alive(value):
    try:
        args=pathlib.Path('/proc',str(value['pid']),'cmdline').read_bytes().split(b'\0')
        return stamp(value['pid'])==value['stamp'] and b'app-server' in args and value['url'].encode() in args
    except (OSError,KeyError):return False
with (base/'server.lock').open('a') as lock:
    fcntl.flock(lock,fcntl.LOCK_EX)
    state=base/'server.json'
    try:previous=json.loads(state.read_text())
    except (OSError,ValueError):previous={}
    if alive(previous):
        print(json.dumps({'url':previous['url']}));sys.exit(0)
    codex=shutil.which('codex')
    if not codex:raise SystemExit(('codex not found in Ubuntu; install and sign in to Codex in the Root terminal' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else 'Ubuntu 未找到 codex，请在 Root 终端安装并登录 Codex 后重试'))
    with socket.socket() as listener:
        listener.bind(('127.0.0.1',0));port=listener.getsockname()[1]
    url='ws://127.0.0.1:'+str(port)
    with (base/'server.log').open('ab',buffering=0) as log:
        proc=subprocess.Popen([codex,'app-server','--listen',url,'--ws-auth','capability-token','--ws-token-file',str(token)],cwd='/workspace',stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
    value={'pid':proc.pid,'stamp':stamp(proc.pid),'url':url};save(state,value)
    deadline=time.monotonic()+25
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    while time.monotonic()<deadline:
        if proc.poll() is not None:raise SystemExit(('Codex App Server failed to start; verify in the Root terminal that this version supports --ws-auth and --remote' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else 'Codex App Server 启动失败，请在 Root 终端确认版本支持 --ws-auth 与 --remote'))
        try:
            with opener.open('http://127.0.0.1:'+str(port)+'/readyz',timeout=.4) as response:
                if response.status==200:print(json.dumps({'url':url}));sys.exit(0)
        except OSError:pass
        time.sleep(.1)
    raise SystemExit(('Codex service is still starting; reconnect shortly (session input will not be resent)' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else 'Codex 服务仍在启动，请稍后重新连接；不会重新发送会话输入'))
""".trimIndent()

    val rootService = """
import errno,fcntl,json,os,pathlib,select,shlex,signal,socket,subprocess,sys,time,urllib.request,urllib.error
from urllib.parse import urlparse
base=pathlib.Path(sys.argv[1]);name,probe=sys.argv[2:4];template=json.loads(sys.argv[4])
state=base/(name+'.json');owner=state.stat()
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def stamp(pid):
    try:
        fields=pathlib.Path('/proc',str(pid),'stat').read_text().rsplit(') ',1)[1].split()
        return fields[19] if fields[0] not in ('Z','X') else None
    except (OSError,IndexError):return None
def cmdline(pid):
    try:return pathlib.Path('/proc',str(pid),'cmdline').read_bytes().rstrip(b'\0').decode('utf-8','replace').split('\0')
    except OSError:return []
def probed(port,timeout):
    try:
        with opener.open('http://127.0.0.1:'+str(port)+probe,timeout=timeout) as response:
            return response.status==200
    except urllib.error.HTTPError as error:
        error.close()
        return error.code==401 and name!='codex'
    except OSError:return False
def healthy(port):
    deadline=time.monotonic()+3.0
    while True:
        remaining=max(0.2,deadline-time.monotonic())
        if probed(port,min(1.5,remaining)):return True
        if time.monotonic()>=deadline:return False
        time.sleep(0.1)
def owned_opencode(args,port):
    p=str(port);launcher=str(base/'opencode-launch.py')
    if len(args)>=6 and args[-5:]==['serve','--hostname','127.0.0.1','--port',p] and any('opencode' in a.lower() for a in args[:-5]):
        return True
    return any(launcher in a for a in args) and any(p in a for a in args)
def signal_owned(fd,pid,sig):
    try:
        if os.getpgid(pid)==pid:os.killpg(pid,sig)
    except OSError:pass
    try:signal.pidfd_send_signal(fd,sig)
    except ProcessLookupError:pass
def retire_opencode(previous,port):
    pid=previous.get('pid');expected=previous.get('stamp')
    if type(pid) is not int or pid<2 or pid==os.getpid() or not isinstance(expected,str) or not expected.isdigit() or not port:
        raise SystemExit(('Could not verify old OpenCode service process identity; not stopped automatically' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '无法确认旧 OpenCode 服务进程身份；未自动停止'))
    if not callable(getattr(os,'pidfd_open',None)) or not callable(getattr(signal,'pidfd_send_signal',None)):
        raise SystemExit(('System does not support pidfd to safely stop stalled OpenCode service' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '系统不支持 pidfd 安全停止卡死的 OpenCode 服务'))
    try:fd=os.pidfd_open(pid,0)
    except ProcessLookupError:return
    try:
        if select.select([fd],[],[],0)[0]:return
        if stamp(pid)!=expected or not owned_opencode(cmdline(pid),port):
            raise SystemExit(('OpenCode service process identity changed; not stopped automatically' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else 'OpenCode 服务进程身份已变化；未自动停止'))
        signal_owned(fd,pid,signal.SIGTERM)
        if select.select([fd],[],[],1.5)[0]:return
        signal_owned(fd,pid,signal.SIGKILL)
        if not select.select([fd],[],[],1.5)[0]:
            raise SystemExit(('Stalled OpenCode service has not exited yet; try again shortly' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '卡死的 OpenCode 服务未能退出，请稍后重试'))
    finally:os.close(fd)
def save(value):
    temp=state.with_suffix('.new');temp.write_text(json.dumps(value))
    os.chmod(temp,0o600);os.chown(temp,owner.st_uid,owner.st_gid)
    try:label=os.getxattr(state,'security.selinux')
    except OSError as error:
        if error.errno not in (errno.ENODATA,errno.ENOTSUP):raise
    else:os.setxattr(temp,'security.selinux',label)
    os.replace(temp,state)
with (base/(name+'.lock')).open('a') as lock:
    fcntl.flock(lock,fcntl.LOCK_EX)
    try:previous=json.loads(state.read_text())
    except (OSError,ValueError):previous={}
    if previous.get('stamp') and stamp(previous.get('pid'))==previous['stamp']:
        if name!='opencode':
            print(json.dumps({'url':previous['url']}));sys.exit(0)
        parsed=urlparse(previous.get('url',''))
        prev_port=parsed.port if parsed.scheme=='http' and parsed.hostname=='127.0.0.1' else None
        if prev_port and healthy(prev_port):
            print(json.dumps({'url':previous['url']}));sys.exit(0)
        retire_opencode(previous,prev_port)
    with socket.socket() as listener:listener.bind(('127.0.0.1',0));port=listener.getsockname()[1]
    scheme='ws' if name=='codex' else 'http'
    url=scheme+'://127.0.0.1:'+str(port)
    command=[a.replace('{port}',str(port)).replace('{url}',url) for a in template]
    runtime=base.parent.parent
    shell='export PATH='+shlex.quote(str(runtime/'bin'))+':"${'$'}PATH":"${'$'}HOME/.opencode/bin"; '
    shell+='export LUMA_BROWSER_ROOT='+shlex.quote(str(base/'browser'))+'; '
    shell+='export LUMA_BROWSER_SKILL='+shlex.quote(str(base/'skills/luma-browser/SKILL.md'))+'; '
    shell+='exec '+shlex.join(command)
    with (base/(name+'.log')).open('ab',buffering=0) as log:
        proc=subprocess.Popen(['/bin/bash','--login','-c',shell],cwd='/workspace',stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
    try:save({'pid':proc.pid,'stamp':stamp(proc.pid),'url':url,'template':template})
    except BaseException:
        proc.terminate();proc.wait(timeout=5);raise
    deadline=time.monotonic()+24
    while time.monotonic()<deadline:
        if proc.poll() is not None:raise SystemExit(name+(' failed to start; verify in Root terminal that the CLI is installed, compatible, and signed in' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else ' 启动失败；请在 Root 普通终端确认该 CLI 已安装、版本兼容且已登录'))
        if probed(port,.4):print(json.dumps({'url':url}));sys.exit(0)
        time.sleep(.1)
    raise SystemExit(name+(' service is still starting; reconnect shortly (input will not be resent)' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else ' 服务仍在启动，请稍后重连；不会重发会话输入'))
""".trimIndent()

    val cliBridgeStop = """
import fcntl,json,os,re,select,signal,sys
from pathlib import Path
from urllib.parse import urlparse

def read_stamp(pid):
    try:
        fields=Path('/proc',str(pid),'stat').read_text().rsplit(') ',1)[1].split()
        return fields[19] if fields[0] not in ('Z','X') else None
    except FileNotFoundError:return None

def read_command(pid):
    return Path('/proc',str(pid),'cmdline').read_bytes().rstrip(b'\0').decode().split('\0')

def stop_owned_bridge(base,name,confirmation):
    if confirmation!='confirmed':raise ValueError(('Explicit confirmation is required before stopping the CLI bridge service' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '必须明确确认后才能停止 CLI 桥服务'))
    if name not in {'luma-native-cli-gui','luma-native-cli-gui-v3','luma-antigravity-gui','luma-acp-cli-gui','luma-codebuddy-gui-v1'}:raise ValueError(('Invalid CLI bridge service ID' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '无效 CLI 桥服务标识'))
    base=Path(base)
    with (base/(name+'.lock')).open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        saved=json.loads((base/(name+'.json')).read_text())
        pid=saved.get('pid');stamp=saved.get('stamp');template=saved.get('template')
        if type(pid) is not int or pid<2 or not isinstance(stamp,str) or not stamp.isdigit():
            raise ValueError(('Could not verify CLI bridge process identity; no service stopped' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '无法确认 CLI 桥服务进程身份；未停止任何服务'))
        if not isinstance(template,list) or len(template)!=7 or not all(isinstance(x,str) for x in template):
            raise ValueError(('CLI bridge command identity mismatch; no service stopped' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else 'CLI 桥服务命令身份不匹配；未停止任何服务'))
        launcher=Path(template[3]);url=urlparse(saved.get('url',''))
        if (template[:3]!=['/usr/bin/python3','-I','-B'] or launcher.parent.parent!=base or
            not re.fullmatch(r'cli-bridge-v[0-9]+',launcher.parent.name) or launcher.name!='main.py' or
            template[4]!='{port}' or template[5]!=str(base/'cli-bridge-token') or template[6]!=str(base/'cli-sessions') or
            url.scheme!='http' or url.hostname!='127.0.0.1' or url.username or url.password or not url.port):
            raise ValueError(('CLI bridge path identity mismatch; no service stopped' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else 'CLI 桥服务路径身份不匹配；未停止任何服务'))
        if not callable(getattr(os,'pidfd_open',None)) or not callable(getattr(signal,'pidfd_send_signal',None)):
            raise RuntimeError(('System does not support locking process identity safely; no service stopped' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '系统不支持安全锁定服务进程身份；未停止任何服务'))
        try:fd=os.pidfd_open(pid,0)
        except ProcessLookupError:return {'alreadyExited':True}
        try:
            if select.select([fd],[],[],0)[0]:return {'alreadyExited':True}
            expected=[arg.replace('{port}',str(url.port)) for arg in template]
            if read_stamp(pid)!=stamp or read_command(pid)!=expected:
                raise ValueError(('CLI bridge process identity changed; no service stopped' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else 'CLI 桥服务进程身份已变化；未停止任何服务'))
            try:signal.pidfd_send_signal(fd,signal.SIGTERM)
            except ProcessLookupError:return {'alreadyExited':True}
            if not select.select([fd],[],[],5)[0]:
                raise RuntimeError(('CLI bridge has not exited yet; new service not started, retry update' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else 'CLI 桥服务尚未退出；未启动新服务，可重试更新'))
            return {'stopped':True}
        finally:os.close(fd)

if __name__=='__main__':
    print(json.dumps(stop_owned_bridge(*sys.argv[1:])))
""".trimIndent()

    private val daemonIdleHelpers = """
def read_stamp(pid):
    try:
        fields=Path('/proc',str(pid),'stat').read_text().rsplit(')',1)[1].split()
        return fields[19] if fields[0] not in ('Z','X') else None
    except (OSError,ValueError,IndexError):return None

def read_command(pid):
    try:return Path('/proc',str(pid),'cmdline').read_bytes().rstrip(b'\0').decode('utf-8','replace').split('\0')
    except OSError:return []

def has_established_clients(port):
    v4={f'0100007F:{port:04X}'}
    v6={f'0000000000000000FFFF00000100007F:{port:04X}',f'00000000000000000000000001000000:{port:04X}'}
    for path,targets in (('/proc/net/tcp',v4),('/proc/net/tcp6',v6)):
        try:lines=Path(path).read_text().splitlines()[1:]
        except OSError:continue
        for line in lines:
            parts=line.split()
            if len(parts)>=4 and parts[1].upper() in targets and parts[3]=='01':
                return True
    return False

def read_pipes(pid):
    targets=set()
    try:entries=list(Path('/proc',str(pid),'fd').iterdir())
    except OSError:return targets
    for entry in entries:
        try:target=os.readlink(entry)
        except OSError:continue
        if target.startswith(('pipe:','socket:')):targets.add(target)
    return targets

def non_mcp_background_child_alive(pid):
    try:entries=os.listdir('/proc')
    except OSError:return False
    self_pid=os.getpid(); by_ppid={}
    for name in entries:
        if not name.isdigit():continue
        cp=int(name)
        if cp in (pid,self_pid):continue
        try:
            fields=Path('/proc',str(cp),'stat').read_text().rsplit(')',1)[1].split()
            if fields[0] not in ('Z','X'):by_ppid.setdefault(int(fields[1]),[]).append(cp)
        except (OSError,ValueError,IndexError):continue
    def visit(parent_pid,visited):
        children=by_ppid.get(parent_pid,())
        if not children:return False
        holder_pipes=read_pipes(parent_pid)
        for cp in children:
            if cp in visited:continue
            visited.add(cp)
            cmd=' '.join(read_command(cp)).lower()
            named=any(t in cmd for t in ('mcp','supervisor.mjs','localharness'))
            try:stdin_t=os.readlink(f'/proc/{cp}/fd/0')
            except OSError:stdin_t=''
            stdio=stdin_t.startswith(('pipe:','socket:')) and stdin_t in holder_pipes
            if not named and not stdio:return True
            if visit(cp,visited):return True
        return False
    return visit(pid,{pid})
""".trimIndent()

    val openCodeIdleStop = """
import base64,fcntl,http.client,json,os,select,signal,sys,urllib.request
from pathlib import Path
from urllib.parse import urlparse

$daemonIdleHelpers

def owned_opencode(base,args,port):
    p=str(port);launcher=str(base/'opencode-launch.py')
    if len(args)>=6 and args[-5:]==['serve','--hostname','127.0.0.1','--port',p] and any('opencode' in a.lower() for a in args[:-5]):
        return True
    return any(launcher in a for a in args) and any(p in a for a in args)

def stop_if_idle(base_dir):
    base=Path(base_dir)
    state_file=base/'opencode.json'
    if not state_file.is_file():return {'alreadyExited':True}
    with (base/'opencode.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        try:saved=json.loads(state_file.read_text())
        except (OSError,ValueError):return {'alreadyExited':True}
        pid=saved.get('pid');stamp=saved.get('stamp')
        url=urlparse(saved.get('url',''))
        port=url.port if url.scheme=='http' and url.hostname=='127.0.0.1' else None
        if type(pid) is not int or pid<2 or not isinstance(stamp,str) or not port:return {'alreadyExited':True}
        if read_stamp(pid)!=stamp or not owned_opencode(base,read_command(pid),port):return {'alreadyExited':True}
        if has_established_clients(port):return {'busy':'attached'}
        try:password=(base/'opencode-password').read_text(encoding='utf-8').strip()
        except OSError:return {'busy':'missing_auth'}
        auth='Basic '+base64.b64encode(('opencode:'+password).encode('utf-8')).decode('ascii')
        req=urllib.request.Request(f'http://127.0.0.1:{port}/api/session/active',headers={'Authorization':auth,'Connection':'close'})
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            with opener.open(req,timeout=2.5) as resp:
                raw=resp.read().decode('utf-8')
                if not raw:return {'busy':'probe_failed'}
                payload=json.loads(raw)
                if not isinstance(payload,dict) or 'data' not in payload:return {'busy':'probe_failed'}
                active=payload.get('data')
                if active is not None and not isinstance(active,dict):return {'busy':'probe_failed'}
                if active:return {'busy':'active_turn'}
        except (OSError,http.client.HTTPException,ValueError):
            return {'busy':'probe_failed'}
        if has_established_clients(port):return {'busy':'attached'}
        if non_mcp_background_child_alive(pid):return {'busy':'background_child'}
        if not callable(getattr(os,'pidfd_open',None)) or not callable(getattr(signal,'pidfd_send_signal',None)):
            return {'skipped':True}
        try:fd=os.pidfd_open(pid,0)
        except ProcessLookupError:return {'alreadyExited':True}
        try:
            if read_stamp(pid)!=stamp or not owned_opencode(base,read_command(pid),port):return {'alreadyExited':True}
            if has_established_clients(port):return {'busy':'attached'}
            if non_mcp_background_child_alive(pid):return {'busy':'background_child'}
            try:
                if os.getpgid(pid)==pid:os.killpg(pid,signal.SIGTERM)
            except OSError:pass
            try:signal.pidfd_send_signal(fd,signal.SIGTERM)
            except ProcessLookupError:return {'alreadyExited':True}
            select.select([fd],[],[],3.0)
            return {'stopped':True}
        finally:os.close(fd)

if __name__=='__main__':
    print(json.dumps(stop_if_idle(sys.argv[1])))
""".trimIndent()

    val codexIdleStop = """
import fcntl,json,os,select,signal,sys
from pathlib import Path
from urllib.parse import urlparse

$daemonIdleHelpers

def owned_codex(base,args,url_str):
    token_file=base/'capability-token'
    tokens={str(token_file)}
    try:tokens.add(os.path.realpath(token_file))
    except OSError:pass
    has_token=False
    for a in args:
        if a in tokens:has_token=True;break
        if a.endswith('/capability-token'):
            try:
                if os.path.realpath(a) in tokens:has_token=True;break
            except OSError:pass
    return 'app-server' in args and url_str in args and any('codex' in Path(a).name.lower() for a in args if a) and has_token

def stop_one(base,state_name,lock_name):
    state_file=base/state_name
    if not state_file.is_file():return {'alreadyExited':True}
    with (base/lock_name).open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        try:saved=json.loads(state_file.read_text())
        except (OSError,ValueError):return {'alreadyExited':True}
        pid=saved.get('pid');stamp=saved.get('stamp');url_str=saved.get('url','')
        url=urlparse(url_str)
        port=url.port if url.scheme=='ws' and url.hostname=='127.0.0.1' else None
        if type(pid) is not int or pid<2 or not isinstance(stamp,str) or not stamp.isdigit() or not port:return {'alreadyExited':True}
        if read_stamp(pid)!=stamp or not owned_codex(base,read_command(pid),url_str):return {'alreadyExited':True}
        if has_established_clients(port):return {'busy':'attached'}
        if non_mcp_background_child_alive(pid):return {'busy':'background_child'}
        if not callable(getattr(os,'pidfd_open',None)) or not callable(getattr(signal,'pidfd_send_signal',None)):
            return {'skipped':True}
        try:fd=os.pidfd_open(pid,0)
        except ProcessLookupError:return {'alreadyExited':True}
        try:
            if read_stamp(pid)!=stamp or not owned_codex(base,read_command(pid),url_str):return {'alreadyExited':True}
            if has_established_clients(port):return {'busy':'attached'}
            if non_mcp_background_child_alive(pid):return {'busy':'background_child'}
            try:
                if os.getpgid(pid)==pid:os.killpg(pid,signal.SIGTERM)
            except OSError:pass
            try:signal.pidfd_send_signal(fd,signal.SIGTERM)
            except ProcessLookupError:return {'alreadyExited':True}
            select.select([fd],[],[],3.0)
            return {'stopped':True}
        finally:os.close(fd)

def stop_if_idle(base_dir):
    base=Path(base_dir)
    return {name:stop_one(base,name,lock) for name,lock in (('codex.json','codex.lock'),('server.json','server.lock'))}

if __name__=='__main__':
    print(json.dumps(stop_if_idle(sys.argv[1])))
""".trimIndent()

    val listFiles = """
import json,os,sys
def _safe_guest_path(raw):
    cur='/'
    stack=[s for s in os.path.normpath(os.path.abspath(raw)).split('/') if s and s!='.']
    hops=0
    while stack:
        seg=stack.pop(0)
        if seg=='..':
            cur=os.path.dirname(cur.rstrip('/')) or '/'
            continue
        nxt=os.path.join(cur,seg)
        if nxt in ('/proc','/sys','/dev') or nxt.startswith(('/proc/','/sys/','/dev/')):
            raise ValueError(('Accessing system pseudo-filesystem paths is not allowed' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '不允许访问系统伪文件系统路径'))
        if os.path.islink(nxt):
            hops+=1
            if hops>40:raise ValueError(('Too many levels of symbolic links' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '符号链接层级过深'))
            target=os.readlink(nxt)
            joined=target if os.path.isabs(target) else os.path.join(cur,target)
            norm_t=os.path.normpath(joined)
            if norm_t in ('/proc','/sys','/dev') or norm_t.startswith(('/proc/','/sys/','/dev/')):
                raise ValueError(('Accessing system pseudo-filesystem paths is not allowed' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '不允许访问系统伪文件系统路径'))
            cur='/'
            stack=[s for s in norm_t.split('/') if s and s!='.']+stack
        else:
            cur=nxt
    real=os.path.realpath(cur)
    if real in ('/proc','/sys','/dev') or real.startswith(('/proc/','/sys/','/dev/')):
        raise ValueError(('Accessing system pseudo-filesystem paths is not allowed' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '不允许访问系统伪文件系统路径'))
    return real
try:
    raw_abs=os.path.abspath(sys.argv[1])
    path=_safe_guest_path(raw_abs)
    entries=list(os.scandir(path))
    if len(entries)>5000:raise ValueError(('Directory has over 5,000 entries; enter a more specific path' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '目录超过 5000 项，请输入更具体的路径'))
    def row(e):
        entry_logical=os.path.join(raw_abs,e.name) if raw_abs!='/' else '/'+e.name
        try:
            resolved=_safe_guest_path(e.path)
            st=os.stat(resolved)
            is_dir=os.path.isdir(resolved)
        except (OSError,ValueError):
            return None
        return {'name':e.name,'path':entry_logical,'directory':is_dir,'size':st.st_size if not is_dir else None,'mtime':int(st.st_mtime)}
    rows=[r for r in (row(e) for e in entries) if r is not None]
    print(json.dumps(sorted(rows,key=lambda r:(not r['directory'],r['name'].lower())),ensure_ascii=False))
except (OSError,ValueError) as error:raise SystemExit(str(error))
""".trimIndent()

    val readFile = """
import os,pathlib,sys
def _safe_guest_path(raw):
    cur='/'
    stack=[s for s in os.path.normpath(os.path.abspath(raw)).split('/') if s and s!='.']
    hops=0
    while stack:
        seg=stack.pop(0)
        if seg=='..':
            cur=os.path.dirname(cur.rstrip('/')) or '/'
            continue
        nxt=os.path.join(cur,seg)
        if nxt in ('/proc','/sys','/dev') or nxt.startswith(('/proc/','/sys/','/dev/')):
            raise ValueError(('Accessing system pseudo-filesystem paths is not allowed' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '不允许访问系统伪文件系统路径'))
        if os.path.islink(nxt):
            hops+=1
            if hops>40:raise ValueError(('Too many levels of symbolic links' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '符号链接层级过深'))
            target=os.readlink(nxt)
            joined=target if os.path.isabs(target) else os.path.join(cur,target)
            norm_t=os.path.normpath(joined)
            if norm_t in ('/proc','/sys','/dev') or norm_t.startswith(('/proc/','/sys/','/dev/')):
                raise ValueError(('Accessing system pseudo-filesystem paths is not allowed' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '不允许访问系统伪文件系统路径'))
            cur='/'
            stack=[s for s in norm_t.split('/') if s and s!='.']+stack
        else:
            cur=nxt
    real=os.path.realpath(cur)
    if real in ('/proc','/sys','/dev') or real.startswith(('/proc/','/sys/','/dev/')):
        raise ValueError(('Accessing system pseudo-filesystem paths is not allowed' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '不允许访问系统伪文件系统路径'))
    return real
try:
    safe_path=_safe_guest_path(os.path.abspath(sys.argv[1]))
    with pathlib.Path(safe_path).open('rb') as source:data=source.read(524289)
    if len(data)>524288:raise ValueError(('File exceeds 512 KiB; view it in the terminal' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '文件超过 512 KiB，请在终端查看'))
    try:text=data.decode('utf-8')
    except UnicodeDecodeError:raise ValueError(('Not a UTF-8 text file; preview it in the terminal' if os.environ.get('LUMA_LANG','zh').lower().startswith('en') else '该文件不是 UTF-8 文本，请在终端使用原生文件预览'))
    print(text,end='')
except (OSError,ValueError) as error:raise SystemExit(str(error))
""".trimIndent()

    val codexRolloutTokenUsage = """
import json,pathlib,sys
path=pathlib.Path(sys.argv[1])
info=None
if path.is_file():
    with path.open('r',encoding='utf-8',errors='replace') as fh:
        for line in fh:
            if 'token_count' not in line:continue
            try:obj=json.loads(line)
            except Exception:continue
            payload=obj.get('payload') if isinstance(obj,dict) else None
            if obj.get('type')=='event_msg' and isinstance(payload,dict) and payload.get('type')=='token_count':
                cand=payload.get('info')
                if isinstance(cand,dict):info=cand
print(json.dumps(info or {},ensure_ascii=False))
""".trimIndent()

    val antigravitySessionMetrics = """
import json,re,sys
if len(sys.argv)<3 or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}',sys.argv[2]):
    print('[]')
    sys.exit(0)
sys.path.insert(0,sys.argv[1])
from luma_cli.metrics import agy_session_metrics
print(json.dumps(agy_session_metrics(sys.argv[2],model=sys.argv[3] if len(sys.argv)>3 else None),ensure_ascii=False))
""".trimIndent()
}

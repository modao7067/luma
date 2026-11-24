package io.github.modao7067.luma.gui.runtime

import io.github.modao7067.luma.gui.i18n.tr

import android.content.Context
import android.system.Os
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.github.modao7067.luma.gui.GuiProduct
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal data class WatchedRootSession(
    val id: String,
    val epoch: Int,
    val protocol: String,
    val task: String = "",
    val threadId: String = "",
    val endpointUrl: String = "",
    val externalSocket: String = "",
    val running: Boolean,
    val userStopped: Boolean,
    val requestIds: List<String>,
)

internal object RootBackgroundNotifications {
    const val ACTION_SESSION_WAKE = "io.github.modao7067.luma.gui.SESSION_WAKE"
    private const val TOKEN_FILE = "bg-watch-token"
    private const val SPEC_FILE = "bg-watch-spec.json"
    private const val SCRIPT_FILE = "bg-notify-watch.py"
    private const val LOCK_FILE = "bg-watch.lock"
    private const val LOG_FILE = "bg-watch.log"

    private val fileLock = Any()
    private val armGeneration = AtomicLong(0L)
    @Volatile internal var forceEnableForTesting = false

    internal fun isEnabled(context: Context): Boolean = GuiProduct.usesRootExecution(context) || forceEnableForTesting

    private fun agentDirectoryPath(context: Context): String = GuiProduct.agentRuntimePath()

    private fun rootDirLocked(context: Context): File =
        File(context.applicationContext.filesDir, agentDirectoryPath(context)).apply {
            if (!isDirectory) {
                mkdirs()
                runCatching { Os.chmod(absolutePath, 0x1C0) }
            }
        }

    private fun writePrivateLocked(file: File, content: String) {
        val temp = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temp.writeText(content, StandardCharsets.UTF_8)
            runCatching { Os.chmod(temp.absolutePath, 0x180) }
            check(temp.renameTo(file)) { tr("无法更新私有通知监听配置", "Failed to update private notification watcher config") }
            runCatching { Os.chmod(file.absolutePath, 0x180) }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    internal fun ensureTokenForTesting(context: Context): String = synchronized(fileLock) {
        ensureTokenLocked(rootDirLocked(context))
    }

    private fun ensureTokenLocked(dir: File): String {
        val file = File(dir, TOKEN_FILE)
        if (file.isFile) {
            val existing = file.readText(StandardCharsets.UTF_8).trim()
            if (existing.length >= 64) return existing
        }
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        writePrivateLocked(file, token)
        return token
    }

    fun verifyToken(context: Context, candidate: String): Boolean {
        if (candidate.length < 64) return false
        val expected = synchronized(fileLock) {
            val file = File(context.applicationContext.filesDir, "${agentDirectoryPath(context)}/$TOKEN_FILE")
            if (!file.isFile) "" else runCatching { file.readText(StandardCharsets.UTF_8).trim() }.getOrDefault("")
        }
        if (expected.isEmpty()) return false
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            candidate.toByteArray(StandardCharsets.UTF_8),
        )
    }

    fun verifyWakeGeneration(context: Context, candidateGen: Long): Boolean {
        if (candidateGen <= 0L) return true
        return synchronized(fileLock) {
            val file = File(context.applicationContext.filesDir, "${agentDirectoryPath(context)}/$SPEC_FILE")
            if (!file.isFile) return@synchronized false
            val spec = runCatching { JSONObject(file.readText(StandardCharsets.UTF_8)) }.getOrNull() ?: return@synchronized false
            spec.optBoolean("active", false) && spec.optLong("watchGen", -1L) == candidateGen
        }
    }

    fun disarmLocal(context: Context) {
        if (!isEnabled(context)) return
        val gen = armGeneration.incrementAndGet()
        runCatching {
            synchronized(fileLock) {
                val dir = File(context.applicationContext.filesDir, agentDirectoryPath(context))
                if (dir.isDirectory) {
                    writePrivateLocked(
                        File(dir, SPEC_FILE),
                        JSONObject().put("active", false).put("watchGen", gen).toString(),
                    )
                }
            }
        }
    }

    fun armInBackground(
        context: Context,
        scope: CoroutineScope,
        sessions: List<WatchedRootSession>,
        spawnLauncher: Boolean = true,
    ) {
        if (!isEnabled(context)) return
        if (sessions.isEmpty()) {
            disarmLocal(context)
            return
        }
        val myGeneration = armGeneration.incrementAndGet()
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            runCatching {
                val launchCmd: String? = synchronized(fileLock) {
                    if (armGeneration.get() != myGeneration) return@runCatching
                    val dir = rootDirLocked(app)
                    ensureTokenLocked(dir)
                    for (name in listOf(LOCK_FILE, LOG_FILE)) {
                        val f = File(dir, name)
                        if (!f.exists()) {
                            f.writeText("", StandardCharsets.UTF_8)
                            runCatching { Os.chmod(f.absolutePath, 0x180) }
                        }
                    }
                    val rows = JSONArray()
                    for (s in sessions) {
                        rows.put(
                            JSONObject()
                                .put("id", s.id)
                                .put("epoch", s.epoch)
                                .put("protocol", s.protocol)
                                .put("task", s.task)
                                .put("threadId", s.threadId)
                                .put("endpointUrl", s.endpointUrl)
                                .put("externalSocket", s.externalSocket)
                                .put("running", s.running)
                                .put("userStopped", s.userStopped)
                                .put("requestIds", JSONArray(s.requestIds))
                        )
                    }
                    val spec = JSONObject()
                        .put("active", true)
                        .put("watchGen", myGeneration)
                        .put("packageName", app.packageName)
                        .put("updatedAt", System.currentTimeMillis())
                        .put("sessions", rows)
                    writePrivateLocked(File(dir, SPEC_FILE), spec.toString())
                    if (!spawnLauncher) {
                        null
                    } else {
                        val scriptFile = File(dir, SCRIPT_FILE)
                        writePrivateLocked(scriptFile, watcherScript)
                        val useGuestOpt = DebianRootMode.isEnabled(app) && !GuiProduct.isRoot
                        val targetDir = if (useGuestOpt) "/opt/luma/${GuiProduct.agentRelative()}" else "/proc/1/root" + dir.absolutePath
                        val targetScript = if (useGuestOpt) "/opt/luma/${GuiProduct.agentRelative()}/$SCRIPT_FILE" else "/proc/1/root" + scriptFile.absolutePath
                        val launcher = DebianRootMode.rootLauncher(app)
                        listOf(launcher, "/usr/bin/python3", "-I", "-B", targetScript, targetDir)
                            .joinToString(" ") { "'" + it.replace("'", "'\"'\"'") + "'" }
                    }
                }
                if (launchCmd == null || armGeneration.get() != myGeneration) return@runCatching
                val process = ProcessBuilder(DebianRootMode.suExecutable(), "-c", "exec $launchCmd")
                    .redirectErrorStream(true)
                    .start()
                process.outputStream.close()
                process.inputStream.close()
                if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroy()
            }
        }
    }

    internal val watcherScript = """
import base64,ctypes,fcntl,json,os,pathlib,select,socket,struct,subprocess,sys,time,urllib.request

base=pathlib.Path(sys.argv[1])
lock_path=base/'bg-watch.lock'
log_path=base/'bg-watch.log'

lock_fd=os.open(str(lock_path),os.O_RDWR|os.O_CREAT,0o600)
try:
    fcntl.flock(lock_fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
except BlockingIOError:
    sys.exit(0)

if os.fork()>0:
    sys.exit(0)
os.setsid()
if os.fork()>0:
    os._exit(0)

devnull=os.open(os.devnull,os.O_RDWR)
os.dup2(devnull,0)
try:
    log_fd=os.open(str(log_path),os.O_WRONLY|os.O_APPEND|os.O_CREAT,0o600)
    try:
        if os.fstat(log_fd).st_size>65536:os.ftruncate(log_fd,0)
    except OSError:
        pass
    os.dup2(log_fd,1);os.dup2(log_fd,2)
    if log_fd>2:os.close(log_fd)
except OSError:
    os.dup2(devnull,1);os.dup2(devnull,2)
if devnull>2:os.close(devnull)

opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
android_sh=os.environ.get('LUMA_BG_ANDROID_SH','/usr/local/bin/android-sh')
custom_tick='LUMA_BG_TICK_SEC' in os.environ
tick_sleep=float(os.environ.get('LUMA_BG_TICK_SEC','1.5'))
max_http_sleep=float(os.environ.get('LUMA_BG_MAX_TICK_SEC',str(tick_sleep if custom_tick else max(tick_sleep,6.0))))
event_wait_sec=float(os.environ.get('LUMA_BG_EVENT_WAIT_SEC','30.0'))
idle_exit_sec=float(os.environ.get('LUMA_BG_IDLE_EXIT_SEC','15.0'))

def read_text(path):
    try:return path.read_text(encoding='utf-8').strip()
    except OSError:return ''

def read_json(path):
    try:return json.loads(path.read_text(encoding='utf-8'))
    except (OSError,ValueError):return {}

def http_json(url,headers):
    req=urllib.request.Request(url,headers=headers,method='GET')
    with opener.open(req,timeout=2.5) as resp:
        raw=resp.read(4*1024*1024).decode('utf-8','replace')
        return json.loads(raw) if raw.strip() else {}

class InotifyDir:
    def __init__(self,path):
        self.fd=None
        try:
            libc=ctypes.CDLL(None)
            libc.inotify_init1.argtypes=[ctypes.c_int]
            libc.inotify_init1.restype=ctypes.c_int
            libc.inotify_add_watch.argtypes=[ctypes.c_int,ctypes.c_char_p,ctypes.c_uint32]
            libc.inotify_add_watch.restype=ctypes.c_int
            fd=libc.inotify_init1(os.O_NONBLOCK|os.O_CLOEXEC)
            if fd>=0:
                wd=libc.inotify_add_watch(fd,os.fsencode(str(path)),0x388)
                if wd>=0:self.fd=fd
                else:os.close(fd)
        except Exception:
            self.close()
    def drain(self):
        if self.fd is None:return False
        got=False
        try:
            while True:
                data=os.read(self.fd,4096)
                if not data:break
                got=True
        except BlockingIOError:pass
        except OSError:self.close()
        return got
    def close(self):
        if self.fd is not None:
            try:os.close(self.fd)
            except OSError:pass
            self.fd=None

class BridgeWatchStream:
    def __init__(self,url,token):
        self.url=url;self.token=token
        self.sock=None;self.buf=bytearray()
        self.supported=None;self.dirty=True
        self.last_try=0.0
    def close(self):
        if self.sock is not None:
            try:self.sock.close()
            except OSError:pass
            self.sock=None
        self.buf.clear()
    def ensure_connected(self):
        if self.sock is not None:return True
        if self.supported is False:return False
        now=time.monotonic()
        if now-self.last_try<2.0:return False
        self.last_try=now
        s=None
        try:
            if not self.url.startswith('http://127.0.0.1:'):
                self.supported=False;return False
            port=int(self.url.rsplit(':',1)[1].split('/',1)[0])
            s=socket.create_connection(('127.0.0.1',port),timeout=2.0)
            s.settimeout(2.0)
            req=(f'GET /watch/events HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n'
                 f'Authorization: Bearer {self.token}\r\nX-Luma-Watch: 1\r\nConnection: close\r\n\r\n')
            s.sendall(req.encode('ascii'))
            resp=b''
            while b'\r\n\r\n' not in resp:
                chunk=s.recv(4096)
                if not chunk:raise OSError('closed')
                resp+=chunk
                if len(resp)>16384:raise OSError('header too large')
            head,rest=resp.split(b'\r\n\r\n',1)
            lines=head.decode('latin1').splitlines()
            status_ok=bool(lines and ' 200 ' in lines[0])
            ctype=''
            for ln in lines[1:]:
                if ln.lower().startswith('content-type:'):
                    ctype=ln.split(':',1)[1].strip().lower()
                    break
            if not status_ok or 'text/event-stream' not in ctype:
                self.supported=False
                s.close();return False
            s.setblocking(False)
            self.sock=s;s=None
            self.supported=True
            self.dirty=True
            self.buf=bytearray(rest)
            self.pump()
            return True
        except Exception:
            if s is not None:
                try:s.close()
                except OSError:pass
            self.close();return False
    def pump(self):
        if self.sock is None:return False
        got=False
        try:
            while True:
                r,_,_=select.select([self.sock],[],[],0)
                if not r:break
                chunk=self.sock.recv(16384)
                if not chunk:
                    self.close();self.dirty=True;return True
                self.buf.extend(chunk)
                if len(self.buf)>65536:
                    self.close();self.dirty=True;return True
        except BlockingIOError:pass
        except OSError:
            self.close();self.dirty=True;return True
        if b'\n' in self.buf:
            raw=bytes(self.buf)
            idx=raw.rfind(b'\n')+1
            del self.buf[:idx]
            for ln in raw[:idx].splitlines():
                if ln.startswith(b'data:'):
                    got=True;self.dirty=True
        return got

class WsCodexMonitor:
    def __init__(self,url='',token='',unix_sock=''):
        self.url=url;self.token=token;self.unix_sock=unix_sock
        self.sock=None;self.buf=bytearray();self.seq=0
        self.threads={}
        self.pending_resume={}

    def close(self):
        if self.sock is not None:
            try:self.sock.close()
            except OSError:pass
            self.sock=None
        self.buf.clear();self.pending_resume.clear()
        for st in self.threads.values():st['resumed']=False

    def _send_frame(self,payload_bytes,opcode=1):
        mask=os.urandom(4)
        n=len(payload_bytes)
        header=bytearray([0x80|opcode])
        if n<126:header.append(0x80|n)
        elif n<65536:header.append(0x80|126);header.extend(struct.pack('!H',n))
        else:header.append(0x80|127);header.extend(struct.pack('!Q',n))
        header.extend(mask)
        masked=bytes(b^mask[i&3] for i,b in enumerate(payload_bytes))
        self.sock.sendall(bytes(header)+masked)

    def _send_json(self,obj):
        self._send_frame(json.dumps(obj).encode('utf-8'))

    def _pop_frame(self):
        if len(self.buf)<2:return None
        b0,b1=self.buf[0],self.buf[1]
        opcode=b0&0x0f;masked=(b1&0x80)!=0;length=b1&0x7f;idx=2
        if length==126:
            if len(self.buf)<4:return None
            length=struct.unpack('!H',self.buf[2:4])[0];idx=4
        elif length==127:
            if len(self.buf)<10:return None
            length=struct.unpack('!Q',self.buf[2:10])[0];idx=10
        if length>4*1024*1024:raise OSError('ws frame too large')
        if masked:idx+=4
        if len(self.buf)<idx+length:return None
        payload=bytes(self.buf[idx:idx+length])
        if masked:
            m=self.buf[idx-4:idx]
            payload=bytes(b^m[i&3] for i,b in enumerate(payload))
        del self.buf[:idx+length]
        return (opcode,payload)

    def ensure_connected(self):
        if self.sock is not None:return True
        s=None
        try:
            if self.unix_sock:
                s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
                s.settimeout(2.5)
                s.connect(self.unix_sock)
                host_hdr='127.0.0.1'
                auth_hdr=''
            else:
                port=int(self.url.rsplit(':',1)[1].split('/',1)[0])
                s=socket.create_connection(('127.0.0.1',port),timeout=2.5)
                host_hdr=f'127.0.0.1:{port}'
                auth_hdr=f'Authorization: Bearer {self.token}\r\n'
            key=base64.b64encode(os.urandom(16)).decode('ascii')
            req=(f'GET / HTTP/1.1\r\nHost: {host_hdr}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
                 f'Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n{auth_hdr}\r\n')
            s.sendall(req.encode('ascii'))
            resp=b''
            while b'\r\n\r\n' not in resp:
                chunk=s.recv(4096)
                if not chunk:raise OSError('closed')
                resp+=chunk
                if len(resp)>64*1024:raise OSError('ws handshake header too large')
            head,rest=resp.split(b'\r\n\r\n',1)
            if len(head)>64*1024:raise OSError('ws handshake header too large')
            if len(rest)>4*1024*1024:raise OSError('ws buffer overflow')
            if b' 101 ' not in head.splitlines()[0]:raise OSError('upgrade failed')
            self.sock=s;s=None;self.buf=bytearray(rest)
            self.seq+=1;init_id=str(self.seq)
            self._send_json({'id':init_id,'method':'initialize','params':{'clientInfo':{'name':'luma_bg_watch','title':'Luma','version':'0.8.0'},'capabilities':{'experimentalApi':True}}})
            deadline=time.monotonic()+2.5
            init_ok=False
            while time.monotonic()<deadline and not init_ok:
                frame=self._pop_frame()
                if frame is None:
                    chunk=self.sock.recv(16384)
                    if not chunk:raise OSError('closed during init')
                    self.buf.extend(chunk)
                    if len(self.buf)>4*1024*1024:raise OSError('ws buffer overflow')
                    continue
                opcode,payload=frame
                if opcode==8:raise OSError('ws closed')
                if opcode==9:self._send_frame(payload,opcode=10);continue
                if opcode==1:
                    msg=json.loads(payload.decode('utf-8','replace'))
                    if isinstance(msg,dict) and str(msg.get('id'))==init_id and 'method' not in msg:
                        init_ok=True
                    else:
                        self._handle_message(msg)
            if not init_ok:raise OSError('init timeout')
            self._send_json({'method':'initialized'})
            self.sock.setblocking(False)
            return True
        except Exception:
            if s is not None:
                try:s.close()
                except OSError:pass
            self.close();return False

    def watch_thread(self,tid,seed_running,seed_reqs):
        st=self.threads.get(tid)
        if st is None:
            st={'running':seed_running,'reqs':{r:'approval' for r in seed_reqs},'resumed':False}
            self.threads[tid]=st
        if not self.ensure_connected():return None
        if not st['resumed']:
            self.seq+=1;rid=str(self.seq)
            self.pending_resume[rid]=tid
            try:
                self._send_json({'id':rid,'method':'thread/resume','params':{'threadId':tid}})
                st['resumed']=True
            except Exception:
                self.close();return None
        return st

    def pump(self):
        if self.sock is None:return
        try:
            while True:
                r,_,_=select.select([self.sock],[],[],0)
                if not r:break
                chunk=self.sock.recv(65536)
                if not chunk:self.close();return
                self.buf.extend(chunk)
                if len(self.buf)>4*1024*1024:self.close();return
        except BlockingIOError:pass
        except OSError:self.close();return

        while True:
            try:frame=self._pop_frame()
            except OSError:self.close();return
            if frame is None:break
            opcode,payload=frame
            if opcode==8:self.close();return
            if opcode==9:
                try:self._send_frame(payload,opcode=10)
                except OSError:self.close();return
                continue
            if opcode==1:
                try:self._handle_message(json.loads(payload.decode('utf-8','replace')))
                except Exception:pass

    def _handle_message(self,msg):
        if not isinstance(msg,dict):return
        if 'id' in msg and 'method' not in msg:
            rid=str(msg.get('id'))
            tid=self.pending_resume.pop(rid,None)
            if tid and tid in self.threads:
                thread=(msg.get('result') or {}).get('thread') or {}
                stype=((thread.get('status') or {}).get('type')) or ''
                if stype=='idle':self.threads[tid]['running']=False
                elif stype in ('running','active','inProgress'):self.threads[tid]['running']=True
            return
        method=msg.get('method') or ''
        p=msg.get('params') or {}
        tid=p.get('threadId') or ((p.get('thread') or {}).get('id'))
        if not tid or tid not in self.threads:return
        st=self.threads[tid]
        if 'id' in msg:
            req_id=str(msg['id'])
            st['reqs'][req_id]='question' if 'requestUserInput' in method else 'approval'
            return
        if method=='turn/started':st['running']=True
        elif method=='turn/completed':st['running']=False
        elif method=='thread/status/changed':
            if ((p.get('status') or {}).get('type'))=='idle':st['running']=False
        elif method=='serverRequest/resolved':
            st['reqs'].pop(str(p.get('requestId')),None)
        elif method in ('item/started','item/completed'):
            item=p.get('item') or {}
            if item.get('delivery')=='async' and item.get('questions'):
                st['reqs']['codex-async:'+str(item.get('id',''))]='question'

def send_wake_batch(pkg,token,wakes,watch_gen=0):
    if not pkg or not token or not wakes:return
    dedup={}
    for sid,kind in wakes:
        dedup[sid]=kind
    items=[{'sessionId':sid,'kind':kind} for sid,kind in list(dedup.items())[:32]]
    args=[
        android_sh,'/system/bin/cmd','activity','broadcast',
        '--user','current',
        '-n',f'{pkg}/io.github.modao7067.luma.gui.session.SessionWakeReceiver',
        '-a','io.github.modao7067.luma.gui.SESSION_WAKE',
        '--es','token',token,
        '--es','events',json.dumps(items,separators=(',',':')),
    ]
    if watch_gen>0:
        args.extend(['--es','watchGen',str(watch_gen)])
    if len(items)==1:
        args.extend(['--es','sessionId',items[0]['sessionId'],'--es','kind',items[0]['kind']])
    try:
        subprocess.run(args,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=8)
    except Exception:
        pass

states={}
codex_monitors={}
bridge_streams={}
inotify=InotifyDir(base)
started=time.monotonic()
idle_since=None
http_sleep=tick_sleep
last_spec_sig=None

try:
    while time.monotonic()-started<14400:
        spec=read_json(base/'bg-watch-spec.json')
        if not spec.get('active'):break
        sessions=spec.get('sessions') or []
        if not sessions:break
        spec_sig=(spec.get('watchGen'),spec.get('updatedAt'),len(sessions))
        spec_changed=spec_sig!=last_spec_sig
        last_spec_sig=spec_sig
        pkg=spec.get('packageName') or ''
        watch_gen=int(spec.get('watchGen') or 0)
        wake_token=read_text(base/'bg-watch-token')
        bridge_token=read_text(base/'cli-bridge-token')
        oc_password=read_text(base/'opencode-password')
        codex_token=read_text(base/'capability-token')

        active_keys=set()
        active_bridge_urls=set()
        active_codex_keys=set()
        polled_bridge_streams=set()
        any_busy=False
        needs_http_poll=False
        changed_any=spec_changed
        pending_wakes=[]
        oc_active_cache={}
        oc_dead_urls=set()
        oc_live_url=read_json(base/'opencode.json').get('url') or ''
        now_ts=time.monotonic()

        for entry in sessions:
            sid=entry.get('id') or ''
            epoch=int(entry.get('epoch') or 0)
            if not sid:continue
            key=(sid,epoch);active_keys.add(key)
            st=states.get(key)
            if st is None:
                st={'running':bool(entry.get('running')),'bg_busy':False,'sub_busy':False,'known_reqs':set(entry.get('requestIds') or []),'reqs_cached':[],'notified_complete':False,'snap_at':0.0}
                states[key]=st

            proto=entry.get('protocol') or ''
            now_running=st['running']
            now_bg_busy=False
            now_sub_busy=False
            reqs=[]
            observed=False

            try:
                if proto=='bridged':
                    task=entry.get('task') or ''
                    url=entry.get('endpointUrl') or (read_json(base/f'{task}.json').get('url') or '')
                    if url and bridge_token:
                        active_bridge_urls.add(url)
                        bstream=bridge_streams.get(url)
                        if bstream is None or bstream.token!=bridge_token:
                            if bstream is not None:bstream.close()
                            bstream=BridgeWatchStream(url,bridge_token)
                            bridge_streams[url]=bstream
                        sse_live=bstream.ensure_connected()
                        if sse_live:
                            bstream.pump()
                            sse_live=bstream.sock is not None
                        if not sse_live:
                            needs_http_poll=True
                        must_fetch=(not sse_live) or bstream.dirty or spec_changed or st['snap_at']==0.0 or (now_ts-st['snap_at']>=event_wait_sec)
                        if must_fetch:
                            snap=http_json(f'{url}/sessions/{sid}',{'Authorization':f'Bearer {bridge_token}','X-Luma-Watch':'1'})
                            now_running=bool(snap.get('running'))
                            for r in (snap.get('requests') or []):
                                if isinstance(r,dict) and r.get('id'):
                                    reqs.append((str(r['id']),'question' if r.get('questions') else 'approval'))
                            now_bg_busy=(snap.get('releasable') is False) and (not reqs) and bool((snap.get('capabilities') or {}).get('release',True))
                            now_sub_busy=any(isinstance(sa,dict) and sa.get('status') not in ('succeeded','completed','failed','cancelled','closed','aborted') for sa in (snap.get('subAgents') or []))
                            st['bg_busy']=now_bg_busy
                            st['sub_busy']=now_sub_busy
                            st['reqs_cached']=reqs
                            st['snap_at']=now_ts
                            if sse_live:polled_bridge_streams.add(url)
                            observed=True
                        else:
                            now_running=st['running']
                            now_bg_busy=st['bg_busy']
                            now_sub_busy=st['sub_busy']
                            reqs=st['reqs_cached']
                            observed=True
                    else:
                        needs_http_poll=True
                elif proto=='opencode':
                    needs_http_poll=True
                    tid=entry.get('threadId') or ''
                    url=oc_live_url or entry.get('endpointUrl') or ''
                    if url and url not in oc_dead_urls and oc_password and tid:
                        auth='Basic '+base64.b64encode(('opencode:'+oc_password).encode('utf-8')).decode('ascii')
                        hdr={'Authorization':auth}
                        if url not in oc_active_cache:
                            try:oc_active_cache[url]=http_json(f'{url}/api/session/active',hdr).get('data') or {}
                            except Exception:
                                oc_dead_urls.add(url);raise
                        act=oc_active_cache[url]
                        perms=http_json(f'{url}/api/session/{tid}/permission',hdr).get('data') or []
                        forms=http_json(f'{url}/api/session/{tid}/form',hdr).get('data') or []
                        now_running=tid in act
                        reqs=([('permission:'+str(r['id']),'approval') for r in perms if isinstance(r,dict) and r.get('id')]+
                              [('form:'+str(r['id']),'question') for r in forms if isinstance(r,dict) and r.get('id')])
                        observed=True
                elif proto=='codex':
                    tid=entry.get('threadId') or ''
                    ext_sock=entry.get('externalSocket') or ''
                    url=entry.get('endpointUrl') or (read_json(base/'endpoint.json').get('url') or '')
                    if tid and ext_sock:
                        mkey='unix:'+ext_sock
                        active_codex_keys.add(mkey)
                        mon=codex_monitors.get(mkey)
                        if mon is None:
                            mon=WsCodexMonitor(unix_sock=ext_sock);codex_monitors[mkey]=mon
                        tst=mon.watch_thread(tid,st['running'],st['known_reqs'])
                        mon.pump()
                        if tst is not None and mon.sock is not None:
                            now_running=tst['running']
                            reqs=list(tst['reqs'].items())
                            observed=True
                        else:
                            needs_http_poll=True
                    elif tid and url.startswith('ws://127.0.0.1:') and codex_token:
                        mkey='ws:'+url
                        active_codex_keys.add(mkey)
                        mon=codex_monitors.get(mkey)
                        if mon is None:
                            mon=WsCodexMonitor(url=url,token=codex_token);codex_monitors[mkey]=mon
                        tst=mon.watch_thread(tid,st['running'],st['known_reqs'])
                        mon.pump()
                        if tst is not None and mon.sock is not None:
                            now_running=tst['running']
                            reqs=list(tst['reqs'].items())
                            observed=True
                        else:
                            needs_http_poll=True
                    else:
                        needs_http_poll=True
            except Exception:
                observed=False
                needs_http_poll=True

            if not observed:continue

            new_reqs=[(rid,rkind) for rid,rkind in reqs if rid not in st['known_reqs']]
            req_ids={rid for rid,_ in reqs}
            if req_ids!=st['known_reqs'] or now_running!=st['running']:
                changed_any=True
            st['known_reqs']=req_ids
            if now_running:st['notified_complete']=False

            if new_reqs:
                pending_wakes.append((sid,new_reqs[-1][1]))
            elif st['running'] and not now_running and not reqs and not entry.get('userStopped') and not st['notified_complete']:
                st['notified_complete']=True
                pending_wakes.append((sid,'completed'))

            st['running']=now_running
            if (now_running and (not reqs or now_sub_busy)) or now_bg_busy:any_busy=True

        for url in polled_bridge_streams:
            bstream=bridge_streams.get(url)
            if bstream is not None:bstream.dirty=False

        if pending_wakes:
            send_wake_batch(pkg,wake_token,pending_wakes,watch_gen)

        for dead in list(states.keys()):
            if dead not in active_keys:states.pop(dead,None)
        for dead_url in list(bridge_streams.keys()):
            if dead_url not in active_bridge_urls:
                bridge_streams.pop(dead_url).close()
        for dead_mk in list(codex_monitors.keys()):
            if dead_mk not in active_codex_keys:
                codex_monitors.pop(dead_mk).close()

        if any_busy:
            idle_since=None
        else:
            if idle_since is None:idle_since=time.monotonic()
            elif time.monotonic()-idle_since>=idle_exit_sec:break

        if not needs_http_poll and inotify.fd is not None:
            wait_sec=event_wait_sec
            http_sleep=tick_sleep
        else:
            if changed_any or pending_wakes:
                http_sleep=tick_sleep
            wait_sec=http_sleep
            if not changed_any and not pending_wakes:
                http_sleep=min(max_http_sleep,http_sleep*1.5)

        if idle_since is not None:
            rem=idle_exit_sec-(time.monotonic()-idle_since)
            if rem<=0:break
            wait_sec=min(wait_sec,max(0.02,rem))

        wait_fds=[]
        if inotify.fd is not None:wait_fds.append(inotify.fd)
        for mon in codex_monitors.values():
            if mon.sock is not None:wait_fds.append(mon.sock)
        for bstream in bridge_streams.values():
            if bstream.sock is not None:wait_fds.append(bstream.sock)

        if wait_fds:
            try:
                ready,_,_=select.select(wait_fds,[],[],wait_sec)
            except OSError:
                ready=[]
                time.sleep(min(wait_sec,tick_sleep))
            if inotify.fd is not None and inotify.fd in ready:
                inotify.drain()
            for bstream in bridge_streams.values():
                if bstream.sock is not None and bstream.sock in ready:
                    bstream.pump()
        else:
            time.sleep(wait_sec)
finally:
    inotify.close()
    for bstream in bridge_streams.values():bstream.close()
    for mon in codex_monitors.values():mon.close()
""".trimIndent()
}

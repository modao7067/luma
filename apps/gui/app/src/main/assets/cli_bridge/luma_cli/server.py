import fcntl
import hmac
import json
import os
import re
import shutil
import signal
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from .i18n import set_lang, tr
from .transport import Rejected, Conflict
from .base import ensure_root_media_cache_access
from .pi import PiSession
from .pi_native import PiNativeSession, probe_pi_native
from .acp import AcpSession
from .dsh import DshSession
from .kimi import KimiSession
from .kimi_web import KimiWebSession
from .kimi_web_transport import (
    PROTOCOL as KIMI_WEB_PROTOCOL,
    KimiWebIncompatible,
    has_live_kimi_gateway,
    reap_idle_kimi_gateway,
)
from .claude import ClaudeSession
from .claude_pty import CLAUDE_PTY_PROTOCOL, CLAUDE_STREAM_PROTOCOL, ClaudePtySession
from .antigravity import AntigravitySession
from .antigravity_agy_acp import AntigravityAgyAcpSession, AGY_ACP_PROTOCOL
from .antigravity_cli import AntigravityCliSession, PROTOCOL as AGY_PROTOCOL
from .qoder import QoderSession
from .codebuddy import CodeBuddySession
from .grok import GrokSession
from .native_owner import foreground_terminal_protects, reap_recorded_owner, recorded_owner_alive, stop_recorded_owner

ADAPTERS = {"pi": PiSession, "DeepSeek Harness": DshSession,
            "Kimi Code": KimiSession, "Claude Code": ClaudeSession, "Antigravity": AntigravitySession,
            "Qoder CLI": QoderSession, "Grok Build": GrokSession, "CodeBuddy Code": CodeBuddySession}
NATIVE_PROTOCOLS = {"CodeBuddy Code": ["codebuddy-http-acp"], "Antigravity": [AGY_ACP_PROTOCOL, AGY_PROTOCOL, "antigravity-acp"],
    "Claude Code": [CLAUDE_STREAM_PROTOCOL, CLAUDE_PTY_PROTOCOL],
    "Kimi Code": [KIMI_WEB_PROTOCOL, "kimi-acp"],
    "pi": ["pi-rpc", "pi-native-sdk-v1"],
    "Grok Build": ["grok-acp", "grok-private-leader-v1"]}

def carrier_revision():
    directory = Path(__file__).absolute().parent.parent.name
    return directory if re.fullmatch(r"cli-bridge-v[0-9]+", directory) else None

def default_new_protocol(cli):
    if cli == "Grok Build":
        return "grok-private-leader-v1", ""
    if cli == "Claude Code":
        return CLAUDE_STREAM_PROTOCOL, ""
    if cli == "pi":
        found, compatible, _, _ = probe_pi_native()
        if found and not compatible:
            return "pi-rpc", tr("当前已安装 Pi 与原生双端 SDK 契约不匹配，已自动使用原生 RPC 协议。", "Installed Pi does not match the native dual-end SDK contract; automatically using native RPC.")
        return "pi-native-sdk-v1", ""
    return None, ""

def _write_json_atomic(path, payload):
    tmp = path.parent / f".{path.name}.{os.getpid()}.{threading.get_ident()}.tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False)
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
    except BaseException:
        try:
            tmp.unlink(missing_ok=True)
        except OSError:
            pass
        raise
    directory_fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)

class Manager:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        ensure_root_media_cache_access(self.directory)
        self.sessions = {}
        self.save_locks = {}
        self.lock = threading.RLock()
        self.reap_lock = threading.Lock()
        self.active_connects = 0
        self.active_requests = 0
        self.last_activity = time.monotonic()
        self.adopted = set()
        self.destroying = set()
        self.detached_owner_idle = {}
        self.observer_locks = {}
        self.shutting_down = False
        self.hook_base_url = None
        self._hook_routes = {}
        self._fallback_hook_server = None
        self._watch_cond = threading.Condition(threading.Lock())
        self._watch_seq = 0

    def notify_watch(self):
        with self._watch_cond:
            self._watch_seq += 1
            self._watch_cond.notify_all()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()

    def __del__(self):
        try:
            locks = getattr(self, "observer_locks", None)
            if not locks:
                return
            handles = list(locks.values())
            locks.clear()
        except Exception:
            return
        for handle in handles:
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
            except Exception:
                pass
            try:
                handle.close()
            except Exception:
                pass

    def close(self):
        with self.lock:
            self.shutting_down = True
            sessions = list(self.sessions.items())
            self.sessions.clear()
            lock_keys = list(self.observer_locks)
            self._hook_routes.clear()
            fallback_server = self._fallback_hook_server
            self._fallback_hook_server = None
        self.notify_watch()
        for _, sess in sessions:
            try:
                sess.retire_observer()
            except Exception:
                pass
        if fallback_server is not None:
            try:
                fallback_server.shutdown()
                fallback_server.server_close()
            except Exception:
                pass
        with self.lock:
            for key in set(lock_keys) | set(self.observer_locks):
                self._release_observer_lock(key)

    def _ensure_hook_base_url(self):
        with self.lock:
            if self.hook_base_url:
                return self.hook_base_url
            manager_ref = self
            class HookOnlyHandler(BaseHTTPRequestHandler):
                protocol_version = "HTTP/1.1"
                def log_message(self, *args): pass
                def do_POST(self):
                    path = urlparse(self.path).path
                    if self.headers.get("Origin") or not path.startswith("/hooks/"):
                        body = b'{"message":"Forbidden"}'
                        self.send_response(403)
                        self.send_header("Content-Type", "application/json")
                        self.send_header("Content-Length", str(len(body)))
                        self.end_headers()
                        self.wfile.write(body)
                        return
                    hook_key = path[len("/hooks/"):].strip("/")
                    try:
                        length = int(self.headers.get("Content-Length", "0"))
                        raw = self.rfile.read(length) if 0 < length <= 4 * 1024 * 1024 else b"{}"
                        payload = json.loads(raw) if raw.strip() else {}
                    except (ValueError, OSError):
                        payload = {}
                    status, result = manager_ref.dispatch_hook(hook_key, self.headers.get("Authorization", ""), payload)
                    data = json.dumps(result, ensure_ascii=False).encode("utf-8")
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    try: self.wfile.write(data)
                    except (BrokenPipeError, ConnectionResetError): pass
            srv = ThreadingHTTPServer(("127.0.0.1", 0), HookOnlyHandler)
            srv.daemon_threads = True
            self._fallback_hook_server = srv
            self.hook_base_url = f"http://127.0.0.1:{srv.server_address[1]}"
            threading.Thread(target=srv.serve_forever, daemon=True, name="LumaHookFallback").start()
            return self.hook_base_url

    def register_hook(self, key, token, callback):
        base_url = self._ensure_hook_base_url()
        with self.lock:
            self._hook_routes[key] = (token, callback)
        return f"{base_url}/hooks/{key}"

    def unregister_hook(self, key, token=None):
        with self.lock:
            entry = self._hook_routes.get(key)
            if entry is not None and (token is None or entry[0] == token):
                self._hook_routes.pop(key, None)

    def dispatch_hook(self, key, auth_header, payload):
        with self.lock:
            if self.shutting_down:
                return 200, {}
            entry = self._hook_routes.get(key)
        if entry is None:
            return 404, {"message": "Unknown hook session"}
        expected_token, callback = entry
        got = (auth_header or "").encode("utf-8", "surrogateescape")
        want = ("Bearer " + expected_token).encode("utf-8")
        if not hmac.compare_digest(got, want):
            return 401, {"message": "Authentication required"}
        try:
            result = callback(payload if isinstance(payload, dict) else {})
        except Exception:
            return 200, {}
        return 200, result if isinstance(result, dict) else {}

    def _open_and_flock(self, key, mode, retries=1, delay=0.002):
        lock_path = self.directory / f".{key}.observer"
        for _ in range(3):
            handle = open(lock_path, "a+b")
            try:
                os.chmod(lock_path, 0o600)
            except OSError:
                pass
            locked = False
            for attempt in range(max(1, retries)):
                try:
                    fcntl.flock(handle.fileno(), mode | fcntl.LOCK_NB)
                    locked = True
                    break
                except BlockingIOError:
                    if attempt + 1 < retries:
                        time.sleep(delay)
                except Exception:
                    handle.close()
                    raise
            if not locked:
                handle.close()
                return None
            try:
                if os.stat(lock_path).st_ino == os.fstat(handle.fileno()).st_ino:
                    return handle
            except OSError:
                pass
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
            except OSError:
                pass
            handle.close()
        return None

    def _release_flock_handle(self, key, handle, unlink=False):
        lock_path = self.directory / f".{key}.observer"
        if unlink:
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                try:
                    if lock_path.exists() and os.stat(lock_path).st_ino == os.fstat(handle.fileno()).st_ino:
                        lock_path.unlink(missing_ok=True)
                except OSError:
                    pass
            except OSError:
                pass
        try:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        except OSError:
            pass
        try:
            handle.close()
        except OSError:
            pass

    def _acquire_observer_lock(self, key):
        with self.lock:
            if self.shutting_down or key in self.destroying:
                raise Conflict(tr("会话正在关闭或销毁，请重试", "Session is closing or being destroyed; retry"))
            if key in self.observer_locks:
                return
            handle = self._open_and_flock(key, fcntl.LOCK_SH, retries=3, delay=0.002)
            if handle is None:
                raise Conflict(tr("该会话正由另一 CLI 桥服务观察", "Session is currently observed by another CLI bridge service"))
            self.observer_locks[key] = handle

    def _release_observer_lock(self, key, unlink=False):
        with self.lock:
            handle = self.observer_locks.pop(key, None)
        if handle is not None:
            self._release_flock_handle(key, handle, unlink=unlink)

    def _has_remote_observer(self, key):
        with self.lock:
            if key in self.sessions or key in self.observer_locks or key in self.destroying:
                return True
        lock_path = self.directory / f".{key}.observer"
        try:
            handle = open(lock_path, "rb")
        except OSError:
            return False
        try:
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                return True
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
            except OSError:
                pass
            return False
        finally:
            try:
                handle.close()
            except OSError:
                pass

    def touch(self):
        self.last_activity = time.monotonic()

    def connect(self, p, background=False, adopt=False):
        key = p.get("id", "")
        if not adopt:
            with self.lock: self.adopted.discard(key)
        if not re.fullmatch(r"[a-zA-Z0-9_-]{1,128}", key): raise Rejected(tr("无效本地会话标识", "Invalid local session ID"))
        cli, cwd = p.get("cli"), p.get("cwd", "")
        if cli not in ADAPTERS: raise Rejected(tr("未知 CLI", "Unknown CLI"))
        if not os.path.isabs(cwd) or not os.path.isdir(cwd): raise Rejected(tr("会话工作目录不存在", "Session working directory does not exist"))
        native = p.get("nativeId")
        if native and not re.fullmatch(r"[a-zA-Z0-9_-]{1,128}", native): raise Rejected(tr("无效原生会话标识", "Invalid native session ID"))
        probed = None
        if (cli in ("pi", "Grok Build", "Claude Code")
                and not native and not p.get("nativeProtocol")
                and not (self.directory / (key + ".json")).exists()):
            probed = default_new_protocol(cli)
        with self.lock:
            if self.shutting_down or key in self.destroying:
                raise Conflict(tr("会话正在关闭或销毁，请重试", "Session is closing or being destroyed; retry"))
            if p.get("nativeProtocol") and p["nativeProtocol"] not in NATIVE_PROTOCOLS.get(cli, []):
                raise Rejected(tr("未知原生协议", "Unknown native protocol"))
            old = self.sessions.get(key)
            if old:
                if (old.config["cli"], old.config["cwd"]) != (cli, cwd): raise Rejected(tr("会话归属不可变更", "Session ownership cannot be changed"))
                if native and old.config.get("nativeId") and native != old.config["nativeId"]: raise Rejected(tr("原生会话身份不匹配", "Native session identity mismatch"))
                if p.get("nativeProtocol") and p["nativeProtocol"] != old.config.get("nativeProtocol", "antigravity-acp" if cli == "Antigravity" else None):
                    raise Rejected(tr("原生协议身份不可变更", "Native protocol identity cannot be changed"))
                if not old.close_pending:
                    if not old.released and (old.data.get("connecting") or (old.data["connected"] and old.peer and not old.peer.closed)):
                        old.touch()
                        return old
                    if old.peer and old.peer.proc.poll() is None and not getattr(old.peer, "reconnectable", False):
                        raise Rejected(tr("旧 CLI 进程仍存活但协议已断开；请在终端核对，不能启动第二个执行进程", "Previous CLI process is still alive with disconnected protocol; check in terminal instead of starting a second process"))
            path = self.directory / (key + ".json")
            config = json.loads(path.read_text()) if path.exists() else dict(p)
            had_saved_protocol = bool(config.get("nativeProtocol"))
            if path.exists() and p.get("nativeProtocol") and p["nativeProtocol"] != config.get("nativeProtocol"):
                raise Rejected(tr("已有会话原生协议不可变更", "Existing session native protocol cannot be changed"))
            if not path.exists() and not native and not config.get("nativeProtocol") and cli in ("pi", "Grok Build", "Claude Code"):
                protocol, fallback_notice = probed or default_new_protocol(cli)
                config["nativeProtocol"] = protocol
                if fallback_notice:
                    config["protocolFallbackNotice"] = fallback_notice
            kimi_fallback_allowed = cli == "Kimi Code" and not p.get("nativeProtocol") and not had_saved_protocol
            if native and not path.exists() and config.get("nativeProtocol", "").endswith(("-sdk-v1", "-leader-v1")):
                raise Rejected(tr("该原生双端入口仅连接本 App 已保存的执行宿主；不能另起相同 ID 的第二个进程", "This native dual-end entry only attaches to an execution host saved by Luma; cannot launch a second process with the same ID"))
            config["ownerDirectory"] = str(self.directory / (key + ".native"))
            adapter = ADAPTERS[cli]
            if cli == "Kimi Code" and config.get("nativeProtocol") in (None, KIMI_WEB_PROTOCOL): adapter = KimiWebSession
            if cli == "pi" and config.get("nativeProtocol") == "pi-native-sdk-v1": adapter = PiNativeSession
            if cli == "Claude Code" and config.get("nativeProtocol") == CLAUDE_PTY_PROTOCOL: adapter = ClaudePtySession
            if cli == "Antigravity":
                protocol = config.get("nativeProtocol") or ("antigravity-acp" if path.exists() or old or native else AGY_ACP_PROTOCOL)
                if protocol not in ("antigravity-acp", AGY_PROTOCOL, AGY_ACP_PROTOCOL): raise Rejected(tr("未知 Antigravity 原生协议", "Unknown Antigravity native protocol"))
                if p.get("nativeProtocol") and p["nativeProtocol"] != protocol: raise Rejected(tr("原生协议身份不可变更", "Native protocol identity cannot be changed"))
                config["nativeProtocol"] = protocol
                if protocol == AGY_ACP_PROTOCOL: adapter = AntigravityAgyAcpSession
                elif protocol == AGY_PROTOCOL: adapter = AntigravityCliSession
            if (config["cli"], config["cwd"]) != (cli, cwd): raise Rejected(tr("会话索引与原有运行环境不符", "Session record does not match existing runtime environment"))
            if config.get("nativeId") and native and native != config["nativeId"]: raise Rejected(tr("原生会话身份不匹配", "Native session identity mismatch"))
            identity = config.get("nativeId") or native
            if identity: config["nativeId"] = identity
            for other_key, other in self.sessions.items():
                same_protocol = cli != "Antigravity" or other.config.get("nativeProtocol", "antigravity-acp") == config["nativeProtocol"]
                if same_protocol and other_key != key and identity and other.config["cli"] == cli and other.config.get("nativeId") == identity:
                    if other.data.get("connecting") or (other.peer and other.peer.proc.poll() is None):
                        raise Rejected(tr("该原生会话已在此服务接入，请打开已有会话", "This native session is already attached here; open the existing session"))
            if identity:
                for other_path in self.directory.glob("*.json"):
                    if other_path == path: continue
                    try: other_config = json.loads(other_path.read_text())
                    except (OSError, ValueError): continue
                    if other_config.get("cli") != cli or other_config.get("nativeId") != identity or not other_config.get("nativeOwner"): continue
                    same_protocol = cli != "Antigravity" or other_config.get("nativeProtocol", "antigravity-acp") == config["nativeProtocol"]
                    if same_protocol and recorded_owner_alive(other_config) is not False:
                        raise Rejected(tr("该原生会话仍由已有记录的原生宿主持有，请打开已有会话；不能启动第二个执行进程", "This native session is still held by a recorded native host; open the existing session instead of starting a second process"))
            config["browserConfig"] = p.get("browserConfig", config.get("browserConfig", ""))
            save_lock = self.save_locks.setdefault(key, threading.RLock())
            def save():
                with save_lock:
                    _write_json_atomic(path, config)
            self._acquire_observer_lock(key)
            if old:
                old.retire_observer()
                if self.sessions.get(key) is old:
                    del self.sessions[key]
            try:
                save()
                session = adapter(config, save)
                session._watch_notify = self.notify_watch
                if hasattr(session, "attach_hook_manager"):
                    session.attach_hook_manager(self)
                session.touch()
                session.update(connecting=True, **({"nativeProtocol": config["nativeProtocol"]} if config.get("nativeProtocol") else {}))
                self.sessions[key] = session
            except Exception:
                if key not in self.sessions:
                    self._release_observer_lock(key)
                raise
        with self.lock:
            self.active_connects += 1
            self.touch()
        def establish():
            nonlocal session
            try:
                try:
                    session.connect()
                except KimiWebIncompatible:
                    if not kimi_fallback_allowed:
                        raise
                    if session.peer:
                        session.peer.close()
                    notice = tr("当前 Kimi Web 协议不兼容，已自动回退到 ACP 协议。", "Current Kimi Web protocol is incompatible; automatically fell back to ACP.")
                    config["nativeProtocol"] = "kimi-acp"
                    config["protocolFallbackNotice"] = notice
                    save()
                    fallback = KimiSession(config, save)
                    fallback._watch_notify = self.notify_watch
                    fallback.touch()
                    fallback.update(connecting=True, nativeProtocol="kimi-acp", permissionNotice=notice)
                    with self.lock:
                        if self.sessions.get(key) is session:
                            self.sessions[key] = fallback
                    session = fallback
                    session.connect()
                if config.get("initialTitle") and not config.get("initialTitleApplied") and session.data["capabilities"].get("rename"):
                    session.rename(config["initialTitle"])
                    config["initialTitleApplied"] = True; save()
                session.update(connecting=False)
                session.start_delivery_worker()
            except Exception as exc:
                session.diagnostic("connect-failed", exc)
                if session.peer: session.peer.close()
                session.update(connected=False, connecting=False, error=str(exc))
                if not background:
                    with self.lock:
                        if self.sessions.get(key) is session:
                            del self.sessions[key]
                            self._release_observer_lock(key)
                    raise
            finally:
                session.touch()
                with self.lock:
                    self.active_connects = max(0, self.active_connects - 1)
                    self.touch()
        if background: threading.Thread(target=establish, daemon=True, name="LumaCliConnect").start()
        else: establish()
        return session

    def adopt_orphans(self):
        for path in sorted(self.directory.glob("*.json")):
            try: cfg = json.loads(path.read_text())
            except (OSError, ValueError): continue
            if not isinstance(cfg, dict) or (cfg.get("nativeOwner") or {}).get("kind") != "grok-leader": continue
            key = path.stem
            with self.lock:
                if key in self.sessions or self._has_remote_observer(key): continue
            if recorded_owner_alive(cfg) is not True: continue
            payload = {k: cfg[k] for k in ("cli", "cwd", "nativeId", "nativeProtocol", "browserConfig") if cfg.get(k)}
            payload["id"] = key
            try:
                with self.lock:
                    if key in self.sessions: continue
                    self.adopted.add(key)
                session = self.connect(payload, background=False, adopt=True)
            except Exception:
                with self.lock: self.adopted.discard(key)
                continue
            threading.Thread(target=self.release_adopted, args=(key, session), daemon=True, name="LumaAdoptRelease").start()

    def release_adopted(self, key, session):
        time.sleep(10)
        until = time.monotonic() + 120
        while time.monotonic() < until:
            with self.lock:
                if key not in self.adopted or self.sessions.get(key) is not session:
                    return
            if session.release_blocker() is None:
                try:
                    self.release(key, {"nativeId": session.config.get("nativeId"), "nativeProtocol": session.config.get("nativeProtocol")})
                except Conflict:
                    time.sleep(10)
                    continue
                except Exception:
                    with self.lock:
                        self.adopted.discard(key)
                    return
                with self.lock:
                    self.adopted.discard(key)
                return
            time.sleep(10)
        try:
            with self.lock:
                if key not in self.adopted or self.sessions.get(key) is not session or session.data.get("running"):
                    return
            session.retire_observer()
            with self.lock:
                if self.sessions.get(key) is session:
                    del self.sessions[key]
                    self._release_observer_lock(key)
        except Conflict:
            return
        except Exception:
            with self.lock:
                self.adopted.discard(key)
        else:
            with self.lock:
                self.adopted.discard(key)

    def release_all_idle(self, deadline):
        with self.lock: candidates = list(self.sessions.items())
        def one(sess):
            try:
                if sess.release_blocker() is None:
                    sess.release_resources(sess.config.get("nativeId"), sess.config.get("nativeProtocol"))
            except Exception: pass
        threads = [threading.Thread(target=one, args=(sess,), daemon=True) for _, sess in candidates]
        for t in threads: t.start()
        for t in threads: t.join(max(0, deadline - time.monotonic()))
        reap_idle_kimi_gateway(self.directory, 0)

    def reap_detached_owners(self, max_client_idle_sec):
        with self.reap_lock:
            seen_instances = set()
            for path in sorted(self.directory.glob("*.json")):
                key = path.stem
                try:
                    cfg = json.loads(path.read_text())
                except (OSError, ValueError):
                    continue
                if not isinstance(cfg, dict) or not isinstance(cfg.get("nativeOwner"), dict):
                    continue
                instance_id = cfg["nativeOwner"].get("instanceId")
                with self.lock:
                    if key in self.sessions or key in self.observer_locks or key in self.destroying:
                        if isinstance(instance_id, str) and instance_id:
                            self.detached_owner_idle.pop(instance_id, None)
                        continue
                reap_handle = self._open_and_flock(key, fcntl.LOCK_EX, retries=1)
                if reap_handle is None:
                    if isinstance(instance_id, str) and instance_id:
                        self.detached_owner_idle.pop(instance_id, None)
                    continue
                try:
                    with self.lock:
                        if key in self.sessions or key in self.observer_locks or key in self.destroying:
                            if isinstance(instance_id, str) and instance_id:
                                self.detached_owner_idle.pop(instance_id, None)
                            continue
                        save_lock = self.save_locks.setdefault(key, threading.RLock())
                    if isinstance(instance_id, str) and instance_id:
                        seen_instances.add(instance_id)
                    if not reap_recorded_owner(cfg, max_client_idle_sec, self.detached_owner_idle):
                        continue
                    with save_lock:
                        if not path.exists():
                            continue
                        try:
                            latest = json.loads(path.read_text())
                        except (OSError, ValueError):
                            continue
                        if not isinstance(latest, dict):
                            continue
                        latest_owner = latest.get("nativeOwner")
                        if not isinstance(latest_owner, dict) or latest_owner.get("instanceId") != instance_id:
                            continue
                        latest.pop("nativeOwner", None)
                        _write_json_atomic(path, latest)
                finally:
                    self._release_flock_handle(key, reap_handle, unlink=True)
            for stale in [k for k in list(self.detached_owner_idle) if k not in seen_instances]:
                self.detached_owner_idle.pop(stale, None)
            reap_idle_kimi_gateway(self.directory, max_client_idle_sec)

    def has_live_detached_owners(self):
        if has_live_kimi_gateway(self.directory):
            return True
        for path in sorted(self.directory.glob("*.json")):
            key = path.stem
            if self._has_remote_observer(key):
                continue
            try:
                cfg = json.loads(path.read_text())
            except (OSError, ValueError):
                continue
            if isinstance(cfg, dict) and isinstance(cfg.get("nativeOwner"), dict) and recorded_owner_alive(cfg) is True:
                return True
        return False

    def get(self, key):
        with self.lock:
            self.touch()
            if key not in self.sessions: raise Conflict(tr("会话运行资源尚未连接，请使用原会话身份重新连接", "Session runtime resources not attached; reconnect using original session identity"))
            session = self.sessions[key]
            session.touch()
            return session

    def peek(self, key):
        with self.lock:
            if key not in self.sessions: raise Conflict(tr("会话运行资源尚未连接，请使用原会话身份重新连接", "Session runtime resources not attached; reconnect using original session identity"))
            return self.sessions[key]

    def release(self, key, payload):
        with self.lock:
            self.touch()
            session = self.get(key)
        result = session.release_resources(payload.get("nativeId"), payload.get("nativeProtocol"))
        with self.lock:
            if self.sessions.get(key) is session:
                del self.sessions[key]
                self._release_observer_lock(key)
            self.touch()
        self.notify_watch()
        return result

    def destroy(self, key, payload=None):
        if not re.fullmatch(r"[a-zA-Z0-9_-]{1,128}", key): raise Rejected(tr("无效本地会话标识", "Invalid local session ID"))
        delete_state = bool((payload or {}).get("deleteState"))
        path = self.directory / (key + ".json")
        reap_handle = None
        had_session = False
        session = None
        with self.lock:
            self.touch()
            if self.shutting_down or key in self.destroying:
                raise Conflict(tr("会话正在关闭或销毁，请重试", "Session is closing or being destroyed; retry"))
            self.destroying.add(key)
            session = self.sessions.pop(key, None)
            had_session = session is not None or key in self.observer_locks
        try:
            if not had_session:
                reap_handle = self._open_and_flock(key, fcntl.LOCK_EX, retries=3, delay=0.002)
                if reap_handle is None:
                    raise Conflict(tr("该会话正由另一 CLI 桥服务观察", "Session is currently observed by another CLI bridge service"))
            recorded = None
            if path.exists():
                try: recorded = json.loads(path.read_text())
                except (OSError, ValueError): recorded = None
            if session is not None:
                session.destroy_resources()
            elif recorded:
                stop_recorded_owner(recorded)
            with self.lock:
                save_lock = self.save_locks.pop(key, None) if delete_state else self.save_locks.setdefault(key, threading.RLock())
            if delete_state:
                if save_lock:
                    with save_lock:
                        path.unlink(missing_ok=True)
                        path.with_suffix(".tmp").unlink(missing_ok=True)
                else:
                    path.unlink(missing_ok=True)
                    path.with_suffix(".tmp").unlink(missing_ok=True)
                native_dir = self.directory / (key + ".native")
                if not native_dir.is_symlink():
                    shutil.rmtree(native_dir, ignore_errors=True)
            elif path.exists() and isinstance(recorded, dict) and "nativeOwner" in recorded:
                with save_lock:
                    try:
                        latest = json.loads(path.read_text()) if path.exists() else None
                    except (OSError, ValueError):
                        latest = None
                    target_cfg = latest if isinstance(latest, dict) else recorded
                    if isinstance(target_cfg, dict) and "nativeOwner" in target_cfg:
                        target_cfg.pop("nativeOwner", None)
                        _write_json_atomic(path, target_cfg)
            return {"destroyed": True, "id": key}
        finally:
            if had_session:
                self._release_observer_lock(key, unlink=delete_state)
            elif reap_handle is not None:
                self._release_flock_handle(key, reap_handle, unlink=delete_state)
            with self.lock:
                self.destroying.discard(key)
                self.touch()

_USER_CLI_BIN_DIRS = (
    ".local/share/pnpm/bin",
    ".local/bin",
    ".kimi-code/bin",
    ".opencode/bin",
    ".grok/bin",
    ".qoder/bin",
    ".codebuddy/bin",
    ".cargo/bin",
    ".bun/bin",
)

def _ensure_user_cli_path():
    home = Path.home()
    entries = [p for p in os.environ.get("PATH", "").split(os.pathsep) if p]
    seen = set(entries)
    added = [str(home / rel) for rel in _USER_CLI_BIN_DIRS if str(home / rel) not in seen]
    if added:
        os.environ["PATH"] = os.pathsep.join([*entries, *added])

def serve(port, password_file, directory):
    _ensure_user_cli_path()
    token = Path(password_file).read_text().strip()
    if len(token) < 32: raise ValueError("private capability token is missing")
    manager = Manager(directory)
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        def log_message(self, *args): pass
        def reply(self, status, value):
            data = json.dumps(value, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try: self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError): pass
        def handle_request(self):
            path = urlparse(self.path).path
            if self.command == "POST" and path.startswith("/hooks/"):
                if self.headers.get("Origin"):
                    self.close_connection = True; self.reply(403, {"message": "Origin rejected"}); return
                hook_key = path[len("/hooks/"):].strip("/")
                if "/" in hook_key or not hook_key:
                    self.reply(404, {"message": "Unknown hook endpoint"}); return
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 0 <= length <= 4 * 1024 * 1024:
                        self.close_connection = True; self.reply(413, {"message": "Hook payload too large"}); return
                    raw = self.rfile.read(length) if length > 0 else b"{}"
                    body = json.loads(raw) if raw.strip() else {}
                except (ValueError, OSError):
                    self.reply(400, {"message": "Invalid hook payload"}); return
                status, result = manager.dispatch_hook(hook_key, self.headers.get("Authorization", ""), body)
                self.reply(status, result)
                return
            auth_header = self.headers.get("Authorization", "").encode("utf-8", "surrogateescape")
            expected_header = ("Bearer " + token).encode("utf-8")
            if self.headers.get("Origin") or not hmac.compare_digest(auth_header, expected_header):
                self.close_connection = True; self.reply(401, {"message": "Authentication required"}); return
            set_lang(self.headers.get("X-Luma-Lang"))
            watch_peek = self.command == "GET" and self.headers.get("X-Luma-Watch") == "1"
            if not watch_peek:
                with manager.lock:
                    manager.active_requests += 1
                    manager.touch()
            try:
                if self.command == "GET" and path == "/health":
                    self.reply(200, {"protocol": 1, "deliveryProtocol": 2, "queueTakebackProtocol": 1, "clis": list(ADAPTERS),
                        "nativeProtocols": NATIVE_PROTOCOLS, "carrierRevision": carrier_revision()}); return
                if self.command == "GET" and path == "/watch/events":
                    self.close_connection = True
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream")
                    self.send_header("Cache-Control", "no-cache")
                    self.send_header("Connection", "close")
                    self.end_headers()
                    try:
                        self.wfile.write(b": ok\n\n")
                        self.wfile.flush()
                        last_seq = manager._watch_seq
                        deadline = time.monotonic() + 300.0
                        while not manager.shutting_down and time.monotonic() < deadline:
                            with manager._watch_cond:
                                if manager._watch_seq == last_seq and not manager.shutting_down:
                                    manager._watch_cond.wait(timeout=30.0)
                                cur_seq = manager._watch_seq
                            if manager.shutting_down:
                                break
                            if cur_seq != last_seq:
                                last_seq = cur_seq
                                self.wfile.write(f"data: {cur_seq}\n\n".encode("ascii"))
                                self.wfile.flush()
                    except OSError:
                        pass
                    return
                payload = {}
                if self.command == "POST":
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 0 < length <= 32 * 1024 * 1024:
                        self.close_connection = True
                        raise Rejected(tr("请求大小无效", "Invalid request size"))
                    payload = json.loads(self.rfile.read(length))
                if path == "/sessions/connect" and self.command == "POST":
                    session = manager.connect(payload, background=True); result = session.snapshot()
                else:
                    parts = path.strip("/").split("/")
                    if len(parts) not in (2, 3) or parts[0] != "sessions": raise Rejected(tr("未知接口", "Unknown endpoint"))
                    if self.command == "POST" and len(parts) == 3 and parts[2] == "release":
                        self.reply(200, manager.release(parts[1], payload)); return
                    if self.command == "POST" and len(parts) == 3 and parts[2] == "destroy":
                        self.reply(200, manager.destroy(parts[1], payload)); return
                    if self.command == "GET" and len(parts) == 2:
                        session = manager.peek(parts[1]) if watch_peek else manager.get(parts[1])
                        after = parse_qs(urlparse(self.path).query).get("after", [None])[0]
                        result = session.snapshot(int(after) if after is not None else None, touch=not watch_peek)
                    else:
                        session = manager.get(parts[1])
                        if self.command == "POST" and len(parts) == 3:
                            try: outcome = session.action(parts[2], payload) or {}
                            except Exception as exc:
                                session.diagnostic("action-failed", exc); raise
                            result = {**session.snapshot(), **outcome}
                        else: raise Rejected(tr("请求方法错误", "Method not allowed"))
                self.reply(200, result)
            except Conflict as exc: self.reply(409, {"message": str(exc), "rejected": True})
            except Rejected as exc: self.reply(422, {"message": str(exc), "rejected": True})
            except Exception as exc: self.reply(500, {"message": str(exc), "rejected": False})
            finally:
                if not watch_peek:
                    with manager.lock:
                        manager.active_requests = max(0, manager.active_requests - 1)
                        manager.touch()
        do_GET = handle_request
        do_POST = handle_request
    server = ThreadingHTTPServer(("127.0.0.1", int(port)), Handler)
    server.daemon_threads = True
    manager.hook_base_url = f"http://127.0.0.1:{server.server_address[1]}"
    idle_exit_sec = int(os.environ.get("LUMA_BRIDGE_IDLE_EXIT_SEC", "300"))
    if idle_exit_sec > 0:
        def idle_reaper():
            while True:
                time.sleep(min(15, max(1, idle_exit_sec // 4)))
                now = time.monotonic()
                with manager.lock:
                    if manager.active_connects > 0:
                        manager.touch()
                    candidates = [(sid, sess) for sid, sess in manager.sessions.items() if now - sess.last_activity >= idle_exit_sec]
                for sid, sess in candidates:
                    if time.monotonic() - sess.last_activity < idle_exit_sec:
                        continue
                    if foreground_terminal_protects(sess.config):
                        sess.touch()
                        manager.touch()
                        continue
                    if sess.release_blocker() is not None:
                        continue
                    try:
                        if (not sess.close_pending
                                and not sess.data.get("connecting")
                                and not sess.data.get("connected")
                                and (not sess.peer or sess.peer.closed or sess.peer.proc.poll() is not None)):
                            sess.retire_observer()
                        else:
                            sess.release_resources(sess.config.get("nativeId"), sess.config.get("nativeProtocol"))
                        with manager.lock:
                            if manager.sessions.get(sid) is sess:
                                del manager.sessions[sid]
                                manager._release_observer_lock(sid)
                    except Exception:
                        pass
                try:
                    manager.reap_detached_owners(idle_exit_sec)
                except Exception:
                    pass
                with manager.lock:
                    if manager.sessions or manager.active_connects > 0 or manager.active_requests > 0:
                        continue
                    if time.monotonic() - manager.last_activity < idle_exit_sec:
                        continue
                try:
                    if manager.has_live_detached_owners():
                        continue
                except Exception:
                    continue
                with manager.lock:
                    if manager.sessions or manager.active_connects > 0 or manager.active_requests > 0:
                        continue
                    if time.monotonic() - manager.last_activity < idle_exit_sec:
                        continue
                    manager.shutting_down = True
                manager.notify_watch()
                server.shutdown()
                return
        threading.Thread(target=idle_reaper, daemon=True, name="LumaBridgeIdleReaper").start()
    term_event = threading.Event()
    def term_shutdown():
        term_event.wait()
        with manager.lock:
            manager.shutting_down = True
        manager.notify_watch()
        server.shutdown()
    threading.Thread(target=term_shutdown, daemon=True, name="LumaTermShutdown").start()
    def on_term(signum, frame):
        signal.signal(signal.SIGTERM, signal.SIG_DFL)
        term_event.set()
    signal.signal(signal.SIGTERM, on_term)
    def startup_housekeeping():
        manager.adopt_orphans()
        if idle_exit_sec > 0:
            try:
                manager.reap_detached_owners(idle_exit_sec)
            except Exception:
                pass
    threading.Thread(target=startup_housekeeping, daemon=True, name="LumaAdoptOrphans").start()
    try:
        server.serve_forever()
    finally:
        if term_event.is_set():
            try:
                manager.release_all_idle(time.monotonic() + 4)
            except Exception:
                pass
        server.server_close()
        manager.close()

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
from .transport import Rejected, Conflict
from .base import ensure_root_media_cache_access
from .pi import PiSession
from .pi_native import PiNativeSession, probe_pi_native
from .acp import AcpSession
from .dsh import DshSession
from .kimi import KimiSession
from .claude import ClaudeSession
from .antigravity import AntigravitySession
from .antigravity_agy_acp import AntigravityAgyAcpSession, AGY_ACP_PROTOCOL
from .antigravity_cli import AntigravityCliSession, PROTOCOL as AGY_PROTOCOL
from .qoder import QoderSession
from .codebuddy import CodeBuddySession
from .grok import GrokSession
from .native_owner import recorded_owner_alive, stop_recorded_owner

ADAPTERS = {"pi": PiSession, "DeepSeek Harness": DshSession,
            "Kimi Code": KimiSession, "Claude Code": ClaudeSession, "Antigravity": AntigravitySession,
            "Qoder CLI": QoderSession, "Grok Build": GrokSession, "CodeBuddy Code": CodeBuddySession}
NATIVE_PROTOCOLS = {"CodeBuddy Code": ["codebuddy-http-acp"], "Antigravity": [AGY_ACP_PROTOCOL, AGY_PROTOCOL, "antigravity-acp"],
    "pi": ["pi-rpc", "pi-native-sdk-v1"],
    "Grok Build": ["grok-acp", "grok-private-leader-v1"]}

def carrier_revision():
    directory = Path(__file__).resolve().parent.parent.name
    return directory if re.fullmatch(r"cli-bridge-v[0-9]+", directory) else None

def default_new_protocol(cli):
    if cli == "Grok Build":
        return "grok-private-leader-v1", ""
    if cli == "pi":
        found, compatible, _, _ = probe_pi_native()
        if found and not compatible:
            return "pi-rpc", "当前已安装 Pi 与原生双端 SDK 契约不匹配，已自动使用原生 RPC 协议。"
        return "pi-native-sdk-v1", ""
    return None, ""

class Manager:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        ensure_root_media_cache_access(self.directory)
        self.sessions = {}
        self.save_locks = {}
        self.lock = threading.RLock()
        self.active_connects = 0
        self.last_activity = time.monotonic()
        self.adopted = set()

    def touch(self):
        self.last_activity = time.monotonic()

    def connect(self, p, background=False, adopt=False):
        key = p.get("id", "")
        if not adopt:
            with self.lock: self.adopted.discard(key)
        if not re.fullmatch(r"[a-zA-Z0-9_-]{1,128}", key): raise Rejected("无效本地会话标识")
        cli, cwd = p.get("cli"), p.get("cwd", "")
        if cli not in ADAPTERS: raise Rejected("未知 CLI")
        if not os.path.isabs(cwd) or not os.path.isdir(cwd): raise Rejected("会话工作目录不存在")
        native = p.get("nativeId")
        if native and not re.fullmatch(r"[a-zA-Z0-9_-]{1,128}", native): raise Rejected("无效原生会话标识")
        probed = None
        if (cli in ("pi", "Grok Build")
                and not native and not p.get("nativeProtocol")
                and not (self.directory / (key + ".json")).exists()):
            probed = default_new_protocol(cli)
        with self.lock:
            old = self.sessions.get(key)
            if old:
                if (old.config["cli"], old.config["cwd"]) != (cli, cwd): raise Rejected("会话归属不可变更")
                if native and old.config.get("nativeId") and native != old.config["nativeId"]: raise Rejected("原生会话身份不匹配")
                if p.get("nativeProtocol") and p["nativeProtocol"] != old.config.get("nativeProtocol", "antigravity-acp" if cli == "Antigravity" else None):
                    raise Rejected("原生协议身份不可变更")
                if old.close_pending:
                    old.retire_observer()
                else:
                    if not old.released and (old.data.get("connecting") or (old.data["connected"] and old.peer and not old.peer.closed)): return old
                    if old.peer and old.peer.proc.poll() is None and not getattr(old.peer, "reconnectable", False):
                        raise Rejected("旧 CLI 进程仍存活但协议已断开；请在终端核对，不能启动第二个执行进程")
                    old.retire_observer()
            path = self.directory / (key + ".json")
            config = json.loads(path.read_text()) if path.exists() else dict(p)
            if p.get("nativeProtocol") and p["nativeProtocol"] not in NATIVE_PROTOCOLS.get(cli, []): raise Rejected("未知原生协议")
            if path.exists() and p.get("nativeProtocol") and p["nativeProtocol"] != config.get("nativeProtocol"):
                raise Rejected("已有会话原生协议不可变更")
            if not path.exists() and not native and not config.get("nativeProtocol") and cli in ("pi", "Grok Build"):
                protocol, fallback_notice = probed or default_new_protocol(cli)
                config["nativeProtocol"] = protocol
                if fallback_notice:
                    config["protocolFallbackNotice"] = fallback_notice
            if native and not path.exists() and config.get("nativeProtocol", "").endswith(("-sdk-v1", "-leader-v1")):
                raise Rejected("该原生双端入口仅连接本 App 已保存的执行宿主；不能另起相同 ID 的第二个进程")
            config["ownerDirectory"] = str(self.directory / (key + ".native"))
            adapter = ADAPTERS[cli]
            if cli == "pi" and config.get("nativeProtocol") == "pi-native-sdk-v1": adapter = PiNativeSession
            if cli == "Antigravity":
                protocol = config.get("nativeProtocol") or ("antigravity-acp" if path.exists() or old or native else AGY_ACP_PROTOCOL)
                if protocol not in ("antigravity-acp", AGY_PROTOCOL, AGY_ACP_PROTOCOL): raise Rejected("未知 Antigravity 原生协议")
                if p.get("nativeProtocol") and p["nativeProtocol"] != protocol: raise Rejected("原生协议身份不可变更")
                config["nativeProtocol"] = protocol
                if protocol == AGY_ACP_PROTOCOL: adapter = AntigravityAgyAcpSession
                elif protocol == AGY_PROTOCOL: adapter = AntigravityCliSession
            if (config["cli"], config["cwd"]) != (cli, cwd): raise Rejected("会话索引与原有运行环境不符")
            if config.get("nativeId") and native and native != config["nativeId"]: raise Rejected("原生会话身份不匹配")
            identity = config.get("nativeId") or native
            if identity: config["nativeId"] = identity
            for other_key, other in self.sessions.items():
                same_protocol = cli != "Antigravity" or other.config.get("nativeProtocol", "antigravity-acp") == config["nativeProtocol"]
                if same_protocol and other_key != key and identity and other.config["cli"] == cli and other.config.get("nativeId") == identity:
                    if other.data.get("connecting") or (other.peer and other.peer.proc.poll() is None):
                        raise Rejected("该原生会话已在此服务接入，请打开已有会话")
            if identity:
                for other_path in self.directory.glob("*.json"):
                    if other_path == path: continue
                    try: other_config = json.loads(other_path.read_text())
                    except (OSError, ValueError): continue
                    if other_config.get("cli") != cli or other_config.get("nativeId") != identity or not other_config.get("nativeOwner"): continue
                    same_protocol = cli != "Antigravity" or other_config.get("nativeProtocol", "antigravity-acp") == config["nativeProtocol"]
                    if same_protocol and recorded_owner_alive(other_config) is not False:
                        raise Rejected("该原生会话仍由已有记录的原生宿主持有，请打开已有会话；不能启动第二个执行进程")
            config["browserConfig"] = p.get("browserConfig", config.get("browserConfig", ""))
            save_lock = self.save_locks.setdefault(key, threading.RLock())
            def save():
                with save_lock:
                    tmp = path.with_suffix(".tmp")
                    with open(tmp, "w", encoding="utf-8") as f:
                        os.chmod(tmp, 0o600); json.dump(config, f, ensure_ascii=False); f.flush(); os.fsync(f.fileno())
                    os.replace(tmp, path)
                    directory_fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
                    try: os.fsync(directory_fd)
                    finally: os.close(directory_fd)
            save()
            session = adapter(config, save)
            session.update(connecting=True, **({"nativeProtocol": config["nativeProtocol"]} if config.get("nativeProtocol") else {}))
            self.sessions[key] = session
        with self.lock:
            self.active_connects += 1
            self.touch()
        def establish():
            try:
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
                if not background: raise
            finally:
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
                if key in self.sessions: continue
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
                if key not in self.adopted or self.sessions.get(key) is not session: return
                if session.release_blocker() is None:
                    try: self.release(key, {"nativeId": session.config.get("nativeId"), "nativeProtocol": session.config.get("nativeProtocol")})
                    except Exception: pass
                    self.adopted.discard(key)
                    return
            time.sleep(10)

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

    def get(self, key):
        with self.lock:
            self.touch()
            if key not in self.sessions: raise Conflict("会话运行资源尚未连接，请使用原会话身份重新连接")
            return self.sessions[key]

    def release(self, key, payload):
        with self.lock:
            self.touch()
            session = self.get(key)
            result = session.release_resources(payload.get("nativeId"), payload.get("nativeProtocol"))
            del self.sessions[key]
            self.touch()
            return result

    def destroy(self, key, payload=None):
        if not re.fullmatch(r"[a-zA-Z0-9_-]{1,128}", key): raise Rejected("无效本地会话标识")
        with self.lock:
            self.touch()
            session = self.sessions.pop(key, None)
            path = self.directory / (key + ".json")
            recorded = None
            if path.exists():
                try: recorded = json.loads(path.read_text())
                except (OSError, ValueError): recorded = None
        if session is not None:
            session.destroy_resources()
        elif recorded:
            stop_recorded_owner(recorded)
        if (payload or {}).get("deleteState"):
            with self.lock:
                save_lock = self.save_locks.pop(key, None)
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
        elif path.exists() and recorded and "nativeOwner" in recorded:
            with self.lock:
                save_lock = self.save_locks.setdefault(key, threading.RLock())
            with save_lock:
                recorded.pop("nativeOwner", None)
                tmp = path.with_suffix(".tmp")
                with open(tmp, "w", encoding="utf-8") as f:
                    os.chmod(tmp, 0o600); json.dump(recorded, f, ensure_ascii=False); f.flush(); os.fsync(f.fileno())
                os.replace(tmp, path)
        with self.lock:
            self.touch()
        return {"destroyed": True, "id": key}

def serve(port, password_file, directory):
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
            auth_header = self.headers.get("Authorization", "").encode("utf-8", "surrogateescape")
            expected_header = ("Bearer " + token).encode("utf-8")
            if self.headers.get("Origin") or not hmac.compare_digest(auth_header, expected_header):
                self.close_connection = True; self.reply(401, {"message": "Authentication required"}); return
            path = urlparse(self.path).path
            manager.touch()
            try:
                if self.command == "GET" and path == "/health":
                    self.reply(200, {"protocol": 1, "deliveryProtocol": 2, "queueTakebackProtocol": 1, "clis": list(ADAPTERS),
                        "nativeProtocols": NATIVE_PROTOCOLS, "carrierRevision": carrier_revision()}); return
                payload = {}
                if self.command == "POST":
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 0 < length <= 32 * 1024 * 1024:
                        self.close_connection = True
                        raise Rejected("请求大小无效")
                    payload = json.loads(self.rfile.read(length))
                if path == "/sessions/connect" and self.command == "POST":
                    session = manager.connect(payload, background=True); result = session.snapshot()
                else:
                    parts = path.strip("/").split("/")
                    if len(parts) not in (2, 3) or parts[0] != "sessions": raise Rejected("未知接口")
                    if self.command == "POST" and len(parts) == 3 and parts[2] == "release":
                        self.reply(200, manager.release(parts[1], payload)); return
                    if self.command == "POST" and len(parts) == 3 and parts[2] == "destroy":
                        self.reply(200, manager.destroy(parts[1], payload)); return
                    session = manager.get(parts[1])
                    if self.command == "GET" and len(parts) == 2:
                        after = parse_qs(urlparse(self.path).query).get("after", [None])[0]
                        result = session.snapshot(int(after) if after is not None else None)
                    elif self.command == "POST" and len(parts) == 3:
                        try: outcome = session.action(parts[2], payload) or {}
                        except Exception as exc:
                            session.diagnostic("action-failed", exc); raise
                        result = {**session.snapshot(), **outcome}
                    else: raise Rejected("请求方法错误")
                self.reply(200, result)
            except Conflict as exc: self.reply(409, {"message": str(exc), "rejected": True})
            except Rejected as exc: self.reply(422, {"message": str(exc), "rejected": True})
            except Exception as exc: self.reply(500, {"message": str(exc), "rejected": False})
        do_GET = handle_request
        do_POST = handle_request
    server = ThreadingHTTPServer(("127.0.0.1", int(port)), Handler)
    server.daemon_threads = True
    idle_exit_sec = int(os.environ.get("LUMA_BRIDGE_IDLE_EXIT_SEC", "300"))
    if idle_exit_sec > 0:
        def idle_reaper():
            while True:
                time.sleep(min(15, max(1, idle_exit_sec // 4)))
                with manager.lock:
                    if manager.active_connects > 0:
                        manager.touch()
                        continue
                    if time.monotonic() - manager.last_activity < idle_exit_sec:
                        continue
                    candidates = list(manager.sessions.items())
                for sid, sess in candidates:
                    if sess.release_blocker() is not None:
                        continue
                    try:
                        sess.release_resources(sess.config.get("nativeId"), sess.config.get("nativeProtocol"))
                        with manager.lock:
                            if manager.sessions.get(sid) is sess:
                                del manager.sessions[sid]
                    except Exception:
                        pass
                with manager.lock:
                    if manager.sessions or manager.active_connects > 0:
                        continue
                    if time.monotonic() - manager.last_activity < idle_exit_sec:
                        continue
                server.shutdown()
                return
        threading.Thread(target=idle_reaper, daemon=True, name="LumaBridgeIdleReaper").start()
    def on_term(signum, frame):
        signal.signal(signal.SIGTERM, signal.SIG_DFL)
        manager.release_all_idle(time.monotonic() + 4)
        raise SystemExit(0)
    signal.signal(signal.SIGTERM, on_term)
    threading.Thread(target=manager.adopt_orphans, daemon=True, name="LumaAdoptOrphans").start()
    server.serve_forever()

import base64
import fcntl
import hashlib
import http.client
import json
import os
import re
import secrets
import select
import shutil
import socket
import struct
import subprocess
import threading
import time
from pathlib import Path
from .i18n import tr
from .native_owner import process_start
from .transport import Rejected, spawn_process

PROTOCOL = "kimi-web-v1"
_GATEWAYS = {}
_GATEWAYS_LOCK = threading.Lock()
MAX_RESPONSE = 64 * 1024 * 1024

def _server_version_tuple(raw):
    parts = re.findall(r"\d+", str(raw or "").split("-", 1)[0])
    return tuple(int(p) for p in parts[:3]) if parts else ()

class KimiWebRejected(Rejected):
    def __init__(self, code, message):
        self.code = code
        super().__init__(message)

class KimiWebIncompatible(Rejected):
    pass

class KimiWebHttp:
    def __init__(self, port, token):
        self.port, self.token = port, token

    def request(self, method, path, body=None, binary=False):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=30)
        try:
            headers = {"Authorization": "Bearer " + self.token}
            if body is not None:
                headers["Content-Type"] = "application/json"
            connection.request(method, "/api/v1" + path,
                None if body is None else json.dumps(body, ensure_ascii=False).encode(),
                headers)
            response = connection.getresponse()
            raw = response.read(MAX_RESPONSE + 1)
            if len(raw) > MAX_RESPONSE:
                raise ValueError(tr("Kimi Web 响应过大", "Kimi Web response too large"))
            if binary and response.status == 200:
                return raw, response.getheader("Content-Type", "application/octet-stream")
            if response.status == 204 and not raw:
                return {}
            result = json.loads(raw)
            if response.status >= 500:
                raise ConnectionError(tr("Kimi Web 服务错误，结果需同步", "Kimi Web server error; resync required"))
            if not 200 <= response.status < 300 or result.get("code") != 0:
                raise KimiWebRejected(result.get("code", response.status), result.get("msg", "Kimi Web HTTP " + str(response.status)))
            return result["data"]
        finally:
            connection.close()

    def upload_file(self, filename, mime_type, content):
        safe_name = re.sub(r'[\r\n"\\]', "_", filename or "") or "image"
        boundary = "----LumaKimiUpload" + secrets.token_hex(12)
        head = (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="{safe_name}"\r\n'
            f"Content-Type: {mime_type}\r\n\r\n"
        ).encode()
        tail = f"\r\n--{boundary}--\r\n".encode()
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=60)
        try:
            connection.request("POST", "/api/v1/files", head + content + tail,
                {"Authorization": "Bearer " + self.token, "Content-Type": f"multipart/form-data; boundary={boundary}"})
            response = connection.getresponse()
            raw = response.read(MAX_RESPONSE + 1)
            if len(raw) > MAX_RESPONSE:
                raise ValueError(tr("Kimi Web 响应过大", "Kimi Web response too large"))
            result = json.loads(raw)
            if response.status >= 500:
                raise ConnectionError(tr("Kimi Web 服务错误，结果需同步", "Kimi Web server error; resync required"))
            if not 200 <= response.status < 300 or result.get("code") != 0:
                raise KimiWebRejected(result.get("code", response.status), result.get("msg", "Kimi Web HTTP " + str(response.status)))
            return result["data"]
        finally:
            connection.close()

class KimiWebSocket:
    def __init__(self, api):
        self.sock = socket.create_connection(("127.0.0.1", api.port), timeout=30)
        self.reader = self.sock.makefile("rb")
        self.write_lock = threading.Lock()
        self.closed = False
        try:
            key = base64.b64encode(secrets.token_bytes(16)).decode()
            request = ("GET /api/v1/ws HTTP/1.1\r\nHost: 127.0.0.1:" + str(api.port) +
                "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: " +
                key + "\r\nAuthorization: Bearer " + api.token + "\r\n\r\n")
            self.sock.sendall(request.encode())
            status = self.reader.readline(8192)
            headers = {}
            while True:
                line = self.reader.readline(8192)
                if line == b"\r\n":
                    break
                if not line:
                    raise ConnectionError("Kimi WebSocket handshake closed")
                name, value = line.decode().split(":", 1)
                headers[name.lower()] = value.strip()
            expected = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
            if status.split()[1:2] != [b"101"] or headers.get("sec-websocket-accept") != expected:
                raise ConnectionError(tr("Kimi Web 事件连接被拒绝", "Kimi Web event connection rejected"))
            self.sock.settimeout(None)
        except BaseException:
            self.close()
            raise

    def send(self, value, opcode=1):
        raw = json.dumps(value, ensure_ascii=False).encode() if opcode == 1 else value
        mask = secrets.token_bytes(4)
        size = len(raw)
        header = bytes([0x80 | opcode, 0x80 | (size if size < 126 else 126 if size < 65536 else 127)])
        if size >= 126:
            header += struct.pack("!H" if size < 65536 else "!Q", size)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(raw))
        with self.write_lock:
            if self.closed:
                raise ConnectionError("Kimi WebSocket closed")
            self.sock.sendall(header + mask + masked)

    def read_exact(self, size):
        out = bytearray()
        while len(out) < size:
            part = self.reader.read(size - len(out))
            if not part:
                raise ConnectionError(tr("Kimi Web 事件连接已断开", "Kimi Web event connection disconnected"))
            out.extend(part)
        return bytes(out)

    def receive(self):
        fragments = bytearray()
        started = False
        while not self.closed:
            first, second = self.read_exact(2)
            opcode = first & 15
            size = second & 127
            if first & 0x70 or second & 0x80:
                raise ValueError("Unsupported Kimi WebSocket frame")
            if size == 126:
                size = struct.unpack("!H", self.read_exact(2))[0]
            elif size == 127:
                size = struct.unpack("!Q", self.read_exact(8))[0]
            if size > MAX_RESPONSE or len(fragments) + size > MAX_RESPONSE:
                raise ValueError("Kimi WebSocket frame too large")
            raw = self.read_exact(size)
            if opcode == 8:
                raise ConnectionError(tr("Kimi Web 事件连接已关闭", "Kimi Web event connection closed"))
            if opcode == 9:
                self.send(raw, 10)
                continue
            if opcode == 10:
                continue
            if opcode == 1 and not started:
                started = True
            elif opcode != 0 or not started:
                raise ValueError("Unexpected Kimi WebSocket continuation")
            fragments.extend(raw)
            if first & 0x80:
                return json.loads(fragments.decode())
        raise ConnectionError("Kimi WebSocket closed")

    def close(self):
        if self.closed:
            return
        self.closed = True
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()
        self.reader.close()

def resolve_kimi_binary():
    found = shutil.which("kimi")
    if found:
        return found
    home = Path.home()
    fallback = os.pathsep.join(str(home / rel) for rel in (".kimi-code/bin", ".local/bin"))
    return shutil.which("kimi", path=fallback)

class GatewayProcess:
    def __init__(self, gateway):
        self.gateway = gateway
        self.pid = gateway.handle["pid"]

    def poll(self):
        return None if self.gateway.alive() else 0

class KimiWebGateway:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.handle = {}
        self.lock = threading.RLock()
        self.observers = set()
        self.idle_timer = None
        self.uses_turns_api = False
        self.is_kap = False
        self.capabilities = {}

    def alive(self):
        return bool(self.handle.get("pid") and process_start(self.handle["pid"]) == self.handle.get("processStart"))

    def _save_handle_locked(self):
        file = self.directory / "gateway.json"
        tmp = file.with_suffix(".tmp")
        with tmp.open("w") as out:
            os.chmod(out.name, 0o600)
            json.dump(self.handle, out)
            out.flush()
            os.fsync(out.fileno())
        os.replace(tmp, file)

    def attach(self, session):
        with self.lock:
            if self.idle_timer:
                self.idle_timer.cancel()
                self.idle_timer = None
            lock_file = self.directory / "gateway.lock"
            with lock_file.open("a+b") as locked:
                os.chmod(lock_file, 0o600)
                fcntl.flock(locked, fcntl.LOCK_EX)
                file = self.directory / "gateway.json"
                try:
                    self.handle = json.loads(file.read_text()) if file.exists() else {}
                except (OSError, ValueError):
                    self.handle = {}
                if self.alive():
                    self.api = KimiWebHttp(self.handle["port"], self.handle["token"])
                    self.validate()
                    if "detachedAt" in self.handle:
                        self.handle.pop("detachedAt", None)
                        self._save_handle_locked()
                else:
                    self.launch(session)
            self.observers.add(session.config["id"])
            return self

    def validate(self):
        try:
            meta = self.api.request("GET", "/meta")
        except KimiWebRejected as error:
            raise KimiWebIncompatible(str(error)) from error
        if meta.get("dangerous_bypass_auth") is not False:
            raise Rejected(tr("Kimi Web 必须启用认证", "Kimi Web authentication must be enabled"))
        capabilities = meta.get("capabilities")
        if not isinstance(capabilities, dict):
            capabilities = {}
        if not capabilities.get("websocket"):
            raise KimiWebIncompatible(tr("Kimi Web 协议能力不匹配", "Kimi Web protocol capabilities do not match"))
        kap = _server_version_tuple(meta.get("kimi_agent_protocol_version"))
        if kap:
            if kap[0] != 1:
                raise KimiWebIncompatible(tr("Kimi Web 服务版本不兼容", "Incompatible Kimi Web server version"))
            self.is_kap = True
            self.uses_turns_api = True
        else:
            if meta.get("backend") != "v2":
                raise KimiWebIncompatible(tr("Kimi Web 协议能力不匹配", "Kimi Web protocol capabilities do not match"))
            ver = _server_version_tuple(meta.get("server_version"))
            if ver and ver >= (3, 0):
                raise KimiWebIncompatible(tr("Kimi Web 服务版本不兼容", "Incompatible Kimi Web server version"))
            self.is_kap = False
            self.uses_turns_api = bool(ver and ver >= (2, 6))
        self.capabilities = capabilities

    def launch(self, session):
        binary = resolve_kimi_binary()
        if not binary:
            raise Rejected(tr("未找到 Kimi Code，请先在当前环境安装", "Kimi Code not found; install it in the current environment"))
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0))
            port = reserved.getsockname()[1]
        proc = spawn_process([binary, "web", "--no-open", "--host", "127.0.0.1", "--port", str(port)],
            cwd="/", env=os.environ.copy(), stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        raw = bytearray()
        deadline = time.monotonic() + 40
        try:
            match = None
            while proc.poll() is None and time.monotonic() < deadline:
                ready, _, _ = select.select([proc.stdout], [], [], .2)
                if not ready:
                    continue
                block = os.read(proc.stdout.fileno(), 8192)
                if not block:
                    break
                raw.extend(block)
                del raw[:-65536]
                match = re.search(rb"http://127\.0\.0\.1:(\d+)/?#token=([A-Za-z0-9_-]+)", raw)
                if match:
                    break
            if not match:
                raise KimiWebIncompatible(tr("Kimi Web 未返回已认证的本地入口", "Kimi Web did not return an authenticated local endpoint"))
            self.handle = {"pid": proc.pid, "processStart": process_start(proc.pid),
                "port": int(match[1]), "token": match[2].decode()}
            self.api = KimiWebHttp(self.handle["port"], self.handle["token"])
            self.validate()
            self._save_handle_locked()
        except BaseException:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)
            proc.stdout.close()
            raise
        def drain():
            try:
                while proc.stdout.read(8192):
                    pass
            finally:
                proc.stdout.close()
                proc.wait()
        threading.Thread(target=drain, daemon=True, name="LumaKimiWebGateway").start()

    def detach(self, ident):
        with self.lock:
            if ident:
                self.observers.discard(ident)
            if not self.observers:
                try:
                    with (self.directory / "gateway.lock").open("a+b") as locked:
                        os.chmod(self.directory / "gateway.lock", 0o600)
                        fcntl.flock(locked, fcntl.LOCK_EX)
                        file = self.directory / "gateway.json"
                        if file.exists() and not self.handle:
                            self.handle = json.loads(file.read_text())
                        if self.handle and "detachedAt" not in self.handle:
                            self.handle["detachedAt"] = time.time()
                            self._save_handle_locked()
                except (OSError, ValueError):
                    pass
                if self.idle_timer is None:
                    self.idle_timer = threading.Timer(300, self.stop_if_idle)
                    self.idle_timer.daemon = True
                    self.idle_timer.start()

    def _try_shutdown_locked(self):
        page = self.api.request("GET", "/sessions?busy=true&page_size=1")
        if page["items"]:
            return False
        use_if_idle = self.is_kap
        if not use_if_idle:
            try:
                connections = self.api.request("GET", "/connections")
                if connections["connections"]:
                    return False
            except KimiWebRejected as error:
                if error.code != 404:
                    raise
                use_if_idle = True
        result = self.api.request("POST", "/shutdown?if_idle=true" if use_if_idle else "/shutdown", {})
        if use_if_idle and isinstance(result, dict) and result.get("ok") is False:
            return False
        deadline = time.monotonic() + 3
        while self.alive() and time.monotonic() < deadline:
            time.sleep(.05)
        if self.alive():
            return False
        (self.directory / "gateway.json").unlink(missing_ok=True)
        self.handle = {}
        return True

    def stop_if_idle(self):
        with self.lock:
            self.idle_timer = None
            if self.observers or not self.alive():
                return
            try:
                with (self.directory / "gateway.lock").open("a+b") as locked:
                    fcntl.flock(locked, fcntl.LOCK_EX)
                    stopped = self._try_shutdown_locked()
                if not stopped:
                    self.detach("")
            except (OSError, ValueError, Rejected):
                self.detach("")

def _gateway_instance(state_dir):
    root = str(Path(state_dir) / ".kimi-web")
    with _GATEWAYS_LOCK:
        return _GATEWAYS.setdefault(root, KimiWebGateway(root))

def gateway_for(session):
    return _gateway_instance(Path(session.config["ownerDirectory"]).parent).attach(session)

def reap_idle_kimi_gateway(state_dir, max_idle_sec=300):
    root = Path(state_dir) / ".kimi-web"
    file = root / "gateway.json"
    if not file.exists():
        return
    gateway = _gateway_instance(state_dir)
    with gateway.lock:
        if gateway.observers:
            return
        try:
            with (root / "gateway.lock").open("a+b") as locked:
                os.chmod(root / "gateway.lock", 0o600)
                fcntl.flock(locked, fcntl.LOCK_EX)
                if not file.exists():
                    return
                gateway.handle = json.loads(file.read_text())
                if not gateway.alive():
                    file.unlink(missing_ok=True)
                    gateway.handle = {}
                    return
                detached_at = gateway.handle.get("detachedAt")
                if not isinstance(detached_at, (int, float)):
                    detached_at = file.stat().st_mtime
                if max_idle_sec > 0 and time.time() - detached_at < max_idle_sec:
                    return
                gateway.api = KimiWebHttp(gateway.handle["port"], gateway.handle["token"])
                if gateway._try_shutdown_locked() and gateway.idle_timer:
                    gateway.idle_timer.cancel()
                    gateway.idle_timer = None
        except (OSError, ValueError, KeyError, Rejected):
            return

def has_live_kimi_gateway(state_dir):
    root = Path(state_dir) / ".kimi-web"
    file = root / "gateway.json"
    if not file.exists():
        return False
    gateway = _gateway_instance(state_dir)
    with gateway.lock:
        if gateway.observers:
            return True
        try:
            gateway.handle = json.loads(file.read_text())
        except (OSError, ValueError):
            return False
        return gateway.alive()

class KimiWebPeer:
    reconnectable = True
    pending = {}

    def __init__(self, gateway, session, changed, exited):
        self.gateway, self.session = gateway, session
        self.closed = False
        self.ready = threading.Event()
        self.uses_turns_api = getattr(gateway, "uses_turns_api", False) is True
        self.subscribed_agents = set()
        self.sub_lock = threading.Lock()
        self.proc = GatewayProcess(gateway)
        self.socket = KimiWebSocket(gateway.api)
        self.changed, self.exited = changed, exited
        self.thread = threading.Thread(target=self.read, daemon=True, name="LumaKimiWebEvents")

    def start_read(self):
        self.thread.start()

    def enable_turns_api(self):
        with self.sub_lock:
            if self.closed:
                return
            self.uses_turns_api = True
            need_main = "main" not in self.subscribed_agents
            if need_main:
                self.subscribed_agents.add("main")
        if need_main:
            try:
                self.socket.send({
                    "type": "subscribe",
                    "request_id": "subscribe",
                    "session_id": self.session.config["nativeId"],
                    "agent_ids": ["main"],
                })
            except OSError:
                with self.sub_lock:
                    self.subscribed_agents.discard("main")

    def ensure_agent_subscriptions(self, agent_ids):
        if self.closed:
            return
        if not self.uses_turns_api:
            if getattr(self.gateway, "uses_turns_api", False) is not True:
                return
            self.enable_turns_api()
        new_ids = []
        with self.sub_lock:
            for aid in agent_ids:
                if aid and aid not in self.subscribed_agents:
                    self.subscribed_agents.add(aid)
                    new_ids.append(aid)
        if new_ids:
            try:
                self.socket.send({
                    "type": "subscribe",
                    "request_id": "sub:" + ",".join(new_ids),
                    "session_id": self.session.config["nativeId"],
                    "agent_ids": new_ids,
                })
            except OSError:
                with self.sub_lock:
                    for aid in new_ids:
                        self.subscribed_agents.discard(aid)

    def read(self):
        try:
            if self.uses_turns_api:
                with self.sub_lock:
                    need_main = "main" not in self.subscribed_agents
                    if need_main:
                        self.subscribed_agents.add("main")
                if need_main:
                    self.socket.send({
                        "type": "subscribe",
                        "request_id": "subscribe",
                        "session_id": self.session.config["nativeId"],
                        "agent_ids": ["main"],
                    })
            while not self.closed:
                frame = self.socket.receive()
                kind = frame.get("type")
                if kind == "server_hello":
                    if frame.get("payload", {}).get("protocol_version") != 2:
                        raise KimiWebIncompatible(tr("Kimi Web 事件版本不兼容", "Incompatible Kimi Web event version"))
                    self.socket.send({"type": "client_hello", "id": "hello", "payload": {"client_id": "luma"}})
                    self.socket.send({"type": "subscribe_v2", "id": "subscribe", "payload":
                        {"session_id": self.session.config["nativeId"], "transcript": {"*": "delta"}}})
                elif kind == "ping":
                    self.socket.send({"type": "pong", "payload": frame["payload"]} if "payload" in frame else {"type": "pong"})
                elif kind == "response":
                    if frame.get("request_id") == "subscribe":
                        if frame.get("code"):
                            raise Rejected(frame.get("msg") or tr("Kimi Web 订阅失败", "Kimi Web subscription failed"))
                        self.ready.set()
                    elif frame.get("code") and not self.ready.is_set():
                        raise KimiWebIncompatible(frame.get("msg") or tr("Kimi Web 事件版本不兼容", "Incompatible Kimi Web event version"))
                elif kind == "ack":
                    if frame.get("code"):
                        raise Rejected(tr("Kimi Web 订阅失败", "Kimi Web subscription failed"))
                    if frame.get("id") == "subscribe":
                        if self.session.config["nativeId"] not in frame.get("payload", {}).get("accepted", []):
                            raise Rejected(tr("Kimi Web 未接受原会话订阅", "Kimi Web did not accept the original session subscription"))
                        self.ready.set()
                elif kind == "error":
                    raise Rejected(tr("Kimi Web 事件同步失败", "Kimi Web event sync failed"))
                else:
                    self.changed(frame)
        except Exception as error:
            if not self.closed:
                self.close()
                self.exited(error)

    def close(self):
        if self.closed:
            return
        self.closed = True
        self.ready.set()
        self.socket.close()
        self.gateway.detach(self.session.config["id"])

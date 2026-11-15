import http.client
import json
import queue
import threading
from .i18n import tr
from .native_owner import OwnerProcess
from .transport import Peer, Rejected

class FrameReader:
    def __init__(self): self.frames = queue.Queue()
    def readline(self, limit):
        value = self.frames.get()
        return b"" if value is None else (json.dumps(value, ensure_ascii=False) + "\n").encode()
    def close(self): self.frames.put(None)

class CodeBuddyHttpPeer(Peer):
    reconnectable = True
    def __init__(self, port, password, owner, event, exited, waiting_for_user=lambda: False, connection_id=None):
        self.event, self.exited, self.waiting_for_user = event, exited, waiting_for_user
        self.pending = {}; self.lock = threading.RLock(); self.write_lock = threading.Lock()
        self.chunks = None; self.closed = False; self.diagnostic = bytearray()
        self.proc = OwnerProcess(owner); self.reader = FrameReader(); self.proc.stdout = self.reader
        self.port, self.password = port, password
        self.connections = set(); self.connection_id = connection_id; self.resumed_connection = False
        connection, response = self.request("GET", "/api/v1/auth/status")
        try:
            if response.status != 200 or json.loads(response.read(1024 * 1024)).get("authEnabled") is not True:
                raise Rejected(tr("CodeBuddy 原生 HTTP 入口尚未开启认证；不能连接未认证的执行 API", "CodeBuddy native HTTP endpoint does not have auth enabled; refusing unauthenticated execution API"))
        finally: self.finish(connection)
        if self.connection_id:
            connection,response=self.request("GET","/api/v1/acp")
            if response.status==409:
                self.finish(connection);self.connection_id=None
            elif response.status!=200:
                self.finish(connection);raise ConnectionError(tr("CodeBuddy 原生观察连接无法恢复", "CodeBuddy native observer connection could not be restored"))
            else:self.notification_response=(connection,response);self.resumed_connection=True
        if not self.connection_id:
            connection,response=self.request("POST","/api/v1/acp/connect",{})
            try:
                if response.status!=200:raise Rejected(tr("CodeBuddy 未开放原生 ACP 连接", "CodeBuddy did not open native ACP connection"))
                self.connection_id=json.loads(response.read(1024*1024))["connectionId"]
            finally:self.finish(connection)
        self.notification_thread = threading.Thread(target=self.notifications, daemon=True, name="LumaCodeBuddyEvents")
        self.notification_thread.start()

    def request(self, method, path, frame=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=45)
        headers = {"X-CodeBuddy-Request": "1", "X-CodeBuddy-Web-Ui": "1", "Authorization": "Bearer " + self.password,
                   "Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        if self.connection_id: headers["acp-connection-id"] = self.connection_id
        with self.lock:
            if self.closed and not (
                getattr(self, "_closing_thread", None) is threading.current_thread()
                and method == "DELETE"
                and path == "/api/v1/acp"
            ):
                raise ConnectionError(tr("CodeBuddy 观察连接已关闭", "CodeBuddy observer connection closed"))
            self.connections.add(connection)
        try:
            connection.request(method, path, None if frame is None else json.dumps(frame), headers)
            return connection, connection.getresponse()
        except Exception:
            self.finish(connection)
            raise

    def finish(self, connection):
        with self.lock: self.connections.discard(connection)
        connection.close()

    def read_response(self, connection, response):
        if response.status in (202, 204): response.read(); return
        if response.status != 200:
            raise ConnectionError("CodeBuddy ACP HTTP " + str(response.status))
        if not response.getheader("Content-Type", "").startswith("text/event-stream"):
            data = response.read(64 * 1024 * 1024 + 1)
            if len(data) > 64 * 1024 * 1024: raise ValueError(tr("CodeBuddy ACP 响应过大", "CodeBuddy ACP response too large"))
            if data: self.reader.frames.put(json.loads(data))
            return
        if connection.sock: connection.sock.settimeout(None)
        data = []
        while not self.closed:
            line = response.readline(64 * 1024 * 1024 + 1)
            if not line: return
            if len(line) > 64 * 1024 * 1024: raise ValueError(tr("CodeBuddy ACP 单帧过大", "CodeBuddy ACP frame too large"))
            line = line.rstrip(b"\r\n")
            if line.startswith(b"data:"):
                data.append(line[5:].lstrip(b" "))
                if sum(map(len, data)) > 64 * 1024 * 1024: raise ValueError(tr("CodeBuddy ACP 单帧过大", "CodeBuddy ACP frame too large"))
            elif not line and data:
                self.reader.frames.put(json.loads(b"\n".join(data))); data = []

    def notifications(self):
        connection = None
        try:
            connection,response=getattr(self,"notification_response",None) or self.request("GET","/api/v1/acp")
            self.read_response(connection,response)
            if not self.closed: raise ConnectionError(tr("CodeBuddy 原生事件通道已关闭", "CodeBuddy native event stream closed"))
        except Exception:
            if not self.closed: self.reader.close()
        finally:
            if connection: self.finish(connection)

    def send(self, frame):
        if self.closed: raise ConnectionError(tr("CodeBuddy 观察连接已关闭", "CodeBuddy observer connection closed"))
        def dispatch():
            connection = None
            try:
                connection, response = self.request("POST", "/api/v1/acp", frame)
                self.read_response(connection, response)
            except Exception as error:
                with self.lock: waiter = self.pending.get(frame.get("id"))
                if waiter:
                    try: waiter.put_nowait(error)
                    except queue.Full: pass
                elif not self.closed: self.reader.close()
            finally:
                if connection: self.finish(connection)
        threading.Thread(target=dispatch, daemon=True, name="LumaCodeBuddyRpc").start()

    def close(self):
        with self.lock:
            if self.closed: return
            self.closed = True
            self._closing_thread = threading.current_thread()
        try:
            connection, response = self.request("DELETE", "/api/v1/acp")
            self.finish(connection)
        except Exception: pass
        finally:
            with self.lock:
                self._closing_thread = None
                connections = list(self.connections)
        for connection in connections:
            try:
                if connection.sock: connection.sock.shutdown(2)
            except OSError: pass
            connection.close()
        self.reader.close()
        thread = getattr(self, "reader_thread", None)
        if thread and thread is not threading.current_thread(): thread.join(timeout=2)

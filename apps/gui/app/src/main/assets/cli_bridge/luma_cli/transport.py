import base64
import json
import queue
import subprocess
import threading
import time
import uuid

class Rejected(Exception):
    pass

class RpcRejected(Rejected):
    def __init__(self, error):
        self.code = error.get("code")
        self.data = error.get("data")
        super().__init__(error.get("message", "CLI 拒绝操作"))

class Conflict(Rejected):
    pass

_SPAWN_QUEUE = queue.Queue()

def _process_spawner():
    while True:
        call, result = _SPAWN_QUEUE.get()
        try:
            result.put((True, call()))
        except BaseException as exc:
            result.put((False, exc))

threading.Thread(target=_process_spawner, daemon=True, name="LumaProcessHost").start()

def spawn_process(*args, **kwargs):
    result = queue.Queue(maxsize=1)
    _SPAWN_QUEUE.put((lambda: subprocess.Popen(*args, **kwargs), result))
    ok, value = result.get()
    if ok:
        return value
    raise value

def start_stderr_drain(stream, diagnostic, name):
    def drain():
        while True:
            block = stream.read1(2048)
            if not block:
                break
            diagnostic.extend(block)
            del diagnostic[:-8192]
    thread = threading.Thread(target=drain, daemon=True, name=name)
    thread.start()
    return thread

class Peer:
    def __init__(self, argv, cwd, event, exited, env=None, waiting_for_user=lambda: False):
        self.event, self.exited = event, exited
        self.waiting_for_user = waiting_for_user
        self.pending = {}
        self.lock = threading.RLock()
        self.write_lock = threading.Lock()
        self.chunks = None
        self.closed = False
        self.proc = spawn_process(argv, cwd=cwd, env=env, stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.diagnostic = bytearray()
        self.stderr_thread = start_stderr_drain(self.proc.stderr, self.diagnostic, "LumaCliDiagnostic")

    def start_read(self):
        self.reader_thread = threading.Thread(target=self._read, daemon=True, name="LumaCliRead")
        self.reader_thread.start()

    def send(self, frame):
        data = (json.dumps(frame, ensure_ascii=False, separators=(",", ":")) + "\n").encode()
        with self.write_lock:
            if self.closed or self.proc.poll() is not None:
                raise ConnectionError("CLI 进程已退出，请重新连接")
            self.proc.stdin.write(data)
            self.proc.stdin.flush()

    def call(self, method, params=None, dialect="rpc", timeout=45, pause_for_user=True, on_uncertain=None):
        key = str(uuid.uuid4())
        waiter = queue.Queue(maxsize=1)
        with self.lock:
            self.pending[key] = waiter
        try:
            if dialect == "pi":
                frame = {"id": key, "type": method, **(params or {})}
            elif dialect == "claude":
                frame = {"type": "control_request", "request_id": key,
                         "request": {"subtype": method, **(params or {})}}
            else:
                frame = {"jsonrpc": "2.0", "id": key, "method": method, "params": params or {}}
            remaining = timeout
            try:
                self.send(frame)
            except OSError as error:
                if on_uncertain is None or self.closed or self.proc.poll() is not None: raise
                on_uncertain(error)
                remaining = None
            while True:
                started = time.monotonic()
                try:
                    response = waiter.get(timeout=min(remaining, 0.25) if remaining is not None else None)
                    break
                except queue.Empty:
                    if not pause_for_user or not self.waiting_for_user(): remaining -= time.monotonic() - started
                    if remaining <= 0:
                        error = TimeoutError(f"{method} 未收到确认；不要自动重发")
                        if on_uncertain is None: raise error from None
                        on_uncertain(error)
                        remaining = None
            if isinstance(response, Exception):
                raise response
            if response.get("error") or response.get("success") is False:
                error = response.get("error", "CLI 拒绝操作")
                if isinstance(error, dict): raise RpcRejected(error)
                raise Rejected(str(error))
            return response.get("data", {}) if dialect == "pi" else response.get("result", {})
        finally:
            with self.lock:
                self.pending.pop(key, None)

    def _frame(self, frame):
        if frame.get("type") == "rpc_chunk":
            if self.chunks is None:
                if frame.get("index") != 0 or not 0 < frame.get("byteLength", 0) <= 64 * 1024 * 1024:
                    raise ValueError("RPC 分块起点或长度无效")
                self.chunks = [frame["chunkId"], frame["count"], frame["byteLength"], []]
            ident, count, size, blocks = self.chunks
            if (frame.get("chunkId"), frame.get("count"), frame.get("byteLength"), frame.get("index")) != (ident, count, size, len(blocks)):
                raise ValueError("RPC 分块顺序冲突")
            blocks.append(base64.b64decode(frame["data"], validate=True))
            if sum(map(len, blocks)) > size:
                raise ValueError("RPC 分块超过声明长度")
            if len(blocks) < count:
                return None
            raw = b"".join(blocks)
            self.chunks = None
            if len(raw) != size:
                raise ValueError("RPC 分块不完整")
            return json.loads(raw.decode("utf-8"))
        if self.chunks is not None:
            raise ValueError("RPC 分块被其他帧打断")
        return frame

    def _read(self):
        error = ConnectionError("CLI 输出已关闭；执行结果需重新同步")
        try:
            while True:
                line = self.proc.stdout.readline(64 * 1024 * 1024 + 1)
                if not line:
                    break
                if len(line) > 64 * 1024 * 1024:
                    raise ValueError("CLI 单帧超过 64 MiB")
                if not line.strip():
                    continue
                frame = self._frame(json.loads(line.decode("utf-8")))
                if frame is None:
                    continue
                if frame.get("type") == "control_response":
                    response = frame["response"]
                    frame = {"id": response["request_id"], **(
                        {"error": response.get("error", "Claude Code 拒绝操作")}
                        if response.get("subtype") == "error" else {"result": response.get("response", {})})}
                key = frame.get("id")
                response = (not frame.get("type") and ("result" in frame or "error" in frame)) or frame.get("type") == "response"
                with self.lock:
                    waiter = self.pending.get(key) if response else None
                if waiter is not None:
                    if waiter.empty(): waiter.put_nowait(frame)
                elif not response:
                    self.event(frame)
        except Exception as exc:
            error = exc
        finally:
            with self.lock:
                self.closed = True
                for waiter in self.pending.values():
                    if waiter.empty():
                        waiter.put_nowait(error)
            code = self.proc.poll()
            if code is not None: error = ConnectionError(f"CLI 进程退出（{code}），请在终端检查安装、模型与登录配置")
            self.exited(error)

    def close(self):
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(timeout=5)

        for thread in (getattr(self, "reader_thread", None), self.stderr_thread):
            if thread and thread is not threading.current_thread(): thread.join(timeout=2)
        if self.proc.stdin and not self.proc.stdin.closed: self.proc.stdin.close()
        for stream, thread in ((self.proc.stdout, getattr(self, "reader_thread", None)), (self.proc.stderr, self.stderr_thread)):
            if stream and not stream.closed:
                if thread and thread is not threading.current_thread() and thread.is_alive():
                    raise OSError("CLI 输出读取线程尚未退出")
                stream.close()

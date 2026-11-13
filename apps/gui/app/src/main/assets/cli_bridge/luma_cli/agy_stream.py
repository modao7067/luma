import json
import os
import signal
import subprocess
import threading
from .transport import spawn_process, start_stderr_drain

class AgyPeer:
    def __init__(self, argv, cwd, env, event, exited):
        self.event, self.exited = event, exited
        self.closed = False
        self.expected_close = False
        self.write_lock = threading.Lock()
        self.close_lock = threading.Lock()
        self.diagnostic = bytearray()
        self.proc = spawn_process(argv, cwd=cwd, env=env, stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        self.stderr_thread = start_stderr_drain(self.proc.stderr, self.diagnostic, "LumaAgyDiagnostic")

    def start_read(self):
        self.reader_thread = threading.Thread(target=self._read, daemon=True, name="LumaAgyRead")
        self.reader_thread.start()

    def _read(self):
        error = ConnectionError("agy 输出已关闭；未确认的执行结果仍为未知")
        try:
            while True:
                line = self.proc.stdout.readline(64 * 1024 * 1024 + 1)
                if not line: break
                if len(line) > 64 * 1024 * 1024: raise ValueError("agy 单帧超过 64 MiB")
                if not line.strip(): continue
                frame = json.loads(line.decode("utf-8"))
                if not isinstance(frame, dict): raise ValueError("agy 返回了非对象事件")
                self.event(frame)
        except Exception as exc:
            error = exc
        finally:
            self.closed = True
            try: self.proc.wait(timeout=.2)
            except subprocess.TimeoutExpired: pass
            self.exited(error)

    def send(self, frame):
        with self.write_lock:
            if self.closed or self.proc.poll() is not None: raise ConnectionError("agy 已退出，请重连")
            self.proc.stdin.write((json.dumps(frame, ensure_ascii=False) + "\n").encode())
            self.proc.stdin.flush()

    def _signal(self, value):
        try: os.killpg(self.proc.pid, value)
        except ProcessLookupError: pass

    def close(self, interrupt=False):
        with self.close_lock:
            self.expected_close = True
            if self.proc.poll() is None:
                if interrupt: self._signal(signal.SIGINT)
                else:
                    try: self.proc.stdin.close()
                    except (OSError, ValueError): pass
                try: self.proc.wait(timeout=1 if interrupt else 8)
                except subprocess.TimeoutExpired:
                    self._signal(signal.SIGTERM)
                    try: self.proc.wait(timeout=1)
                    except subprocess.TimeoutExpired:
                        self._signal(signal.SIGKILL); self.proc.wait(timeout=5)
            if interrupt: self._signal(signal.SIGKILL)
            for thread in (getattr(self, "reader_thread", None), self.stderr_thread):
                if thread and thread is not threading.current_thread(): thread.join(timeout=2)
            for stream in (self.proc.stdin, self.proc.stdout, self.proc.stderr):
                if stream and not stream.closed: stream.close()

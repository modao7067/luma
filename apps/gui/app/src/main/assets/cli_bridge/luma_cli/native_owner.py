import json
import os
from pathlib import Path
import queue
import shlex
import shutil
import signal
import socket
import subprocess
import threading
import time
import uuid
import zipfile
from .transport import Peer, Rejected

def process_start(pid):
    try:
        fields = Path(f"/proc/{int(pid)}/stat").read_text().rsplit(")", 1)[1].split()
        return None if fields[0] == "Z" else fields[19]
    except (OSError, ValueError, IndexError): return None

def grok_lock_leader(socket_path):
    if not socket_path: return None
    lock_path = Path(socket_path).with_suffix(".lock")
    try:
        pid = int(lock_path.read_text().strip())
    except (OSError, ValueError): return None
    start = process_start(pid)
    if not start or not Path(socket_path).exists(): return None
    try:
        cmdline = Path(f"/proc/{pid}/cmdline").read_bytes().replace(b"\x00", b" ").decode("utf-8", "ignore")
    except OSError: return None
    return (pid, start) if "grok" in cmdline and "leader" in cmdline else None

def recorded_owner_alive(config):
    handle = config.get("nativeOwner", {})
    if handle.get("kind") in ("grok-leader", "codebuddy-server"):
        pid, start = handle.get("pid"), handle.get("processStart")
        if pid and start and process_start(pid) == start:
            return True
        if handle.get("kind") == "grok-leader" and handle.get("instanceId"):
            sock = Path("/tmp") / ("luma-native-" + handle["instanceId"]) / "control.sock"
            if grok_lock_leader(sock): return True
        return False
    if handle.get("kind") != "tmux": return False
    command, socket_path = handle.get("tmuxCommand"), handle.get("terminalSocket")
    if not command or not socket_path: return None
    try:
        result = subprocess.run([*command, "-S", socket_path, "-f", "/dev/null", "display-message", "-p", "-t", "owner", "#{pane_dead}"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10)
        return result.returncode == 0 and result.stdout.strip() == b"0"
    except (OSError, subprocess.TimeoutExpired): return None

def stop_recorded_owner(config):
    handle = config.get("nativeOwner")
    if not isinstance(handle, dict): return
    kind = handle.get("kind")
    instance_id = handle.get("instanceId")
    directory = Path("/tmp") / ("luma-native-" + instance_id) if isinstance(instance_id, str) and instance_id else None
    if kind == "tmux":
        command, socket_path = handle.get("tmuxCommand"), handle.get("terminalSocket")
        if command and socket_path:
            try:
                pane = subprocess.run([*command, "-S", socket_path, "-f", "/dev/null", "display-message", "-p", "-t", "owner", "#{pane_pid}"],
                                      stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5, check=False)
                subprocess.run([*command, "-S", socket_path, "-f", "/dev/null", "set-option", "-t", "owner", "remain-on-exit", "off"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5, check=False)
                pane_pid = int(pane.stdout.strip()) if pane.returncode == 0 and pane.stdout.strip().isdigit() else None
            except (OSError, subprocess.TimeoutExpired, ValueError):
                pane_pid = None
            start = process_start(pane_pid) if pane_pid else None
            if pane_pid and start:
                try: os.kill(pane_pid, signal.SIGTERM)
                except ProcessLookupError: pass
                until = time.monotonic() + 3
                while process_start(pane_pid) == start and time.monotonic() < until: time.sleep(.05)
                if process_start(pane_pid) == start:
                    try: os.kill(pane_pid, signal.SIGKILL)
                    except ProcessLookupError: pass
            try:
                subprocess.run([*command, "-S", socket_path, "-f", "/dev/null", "kill-server"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5, check=False)
            except (OSError, subprocess.TimeoutExpired): pass
    elif kind in ("grok-leader", "codebuddy-server"):
        pid, start = handle.get("pid"), handle.get("processStart")
        if kind == "grok-leader" and (not pid or not start or process_start(pid) != start) and directory:
            locked = grok_lock_leader(str(directory / "control.sock"))
            if locked: pid, start = locked
        if pid and start and process_start(pid) == start:
            try: os.kill(pid, signal.SIGTERM)
            except ProcessLookupError: pass
            until = time.monotonic() + 3
            while process_start(pid) == start and time.monotonic() < until: time.sleep(.05)
            if process_start(pid) == start:
                try: os.kill(pid, signal.SIGKILL)
                except ProcessLookupError: pass
    if directory and not directory.is_symlink():
        shutil.rmtree(directory, ignore_errors=True)

class NativeOwner:
    def __init__(self, session):
        self.session = session
        owner = session.config.get("nativeOwner")
        if not owner:
            owner = {"instanceId": uuid.uuid4().hex}
            session.config["nativeOwner"] = owner
            session.save()
        self.handle = owner
        self.directory = Path("/tmp") / ("luma-native-" + owner["instanceId"])
        self.directory.mkdir(mode=0o700, exist_ok=True)
        if self.directory.is_symlink() or self.directory.stat().st_uid != os.getuid():
            raise Rejected("原生宿主目录归属不符")
        os.chmod(self.directory, 0o700)
        self.socket = str(self.directory / "control.sock")
        self.tmux_socket = str(self.directory / "terminal.sock")
        if owner.get("kind") and not self.alive():
            if not session.config.get("nativeId"):
                raise Rejected("原生创建结果未知，不能自动再建会话")
            if session.config["cli"] == "pi":
                saved = session.config.get("sessionFile")
                accepted = "accepted" in session.config.get("receipts", {}).values()
                if accepted and (not saved or not Path(saved).is_file()):
                    raise Rejected("原生会话尚未可靠落盘，不能用新会话替代恢复")
            if owner.get("kind") == "tmux":
                self.tmux("kill-server", check=False)
            shutil.rmtree(self.directory, ignore_errors=True)
            session.config["nativeOwner"] = {"instanceId": uuid.uuid4().hex}
            session.save()
            self.__init__(session)

    def stage_native(self, entry_name):
        source_dir = Path(__file__).parent.parent / "native"
        target_dir = self.directory / "native"
        target_dir.mkdir(mode=0o700, exist_ok=True)
        for item in source_dir.glob("*.mjs"):
            dest = target_dir / item.name
            dest.write_bytes(item.read_bytes())
            os.chmod(dest, 0o600)
        return target_dir / entry_name

    def tmux(self, *args, check=True):
        result = subprocess.run([*self.tmux_command(), "-S", self.tmux_socket, "-f", "/dev/null", *args],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
        if check and result.returncode: raise Rejected("原生终端宿主操作失败（tmux）")
        return result

    def tmux_command(self):
        if self.handle.get("tmuxCommand"): return self.handle["tmuxCommand"]
        command = shutil.which("tmux")
        if command: return [command]
        base = Path(self.session.config["ownerDirectory"]).parent / "tmux-debian-3.5a"
        if not (base / "tmux").is_file():
            archive = Path(__file__).parent.parent / "native/tmux-debian-arm64.zip"
            if not archive.is_file(): raise Rejected("App 缺少原生终端运行依赖 tmux")
            base.mkdir(mode=0o700, parents=True, exist_ok=True)
            with zipfile.ZipFile(archive) as z: z.extractall(base)
            os.chmod(base / "tmux", 0o700)
        return ["/lib/ld-linux-aarch64.so.1", "--library-path", str(base / "lib"), str(base / "tmux")]

    def alive(self):
        if self.handle.get("kind") == "tmux":
            state = self.tmux("display-message", "-p", "-t", "owner", "#{pane_dead}", check=False)
            return state.returncode == 0 and state.stdout.strip() == b"0"
        pid, start = self.handle.get("pid"), self.handle.get("processStart")
        if pid and start and process_start(pid) == start:
            return True
        if self.handle.get("kind") == "grok-leader":
            locked = grok_lock_leader(self.socket)
            if locked:
                self.handle.update(pid=locked[0], processStart=locked[1])
                self.session.save()
                return True
        return False

    def launch_tui(self, argv, env=None):
        if self.handle.get("kind"):
            if self.alive(): return False
            raise Rejected("原生宿主已退出，请重新连接核对保存的身份")
        if Path(self.socket).exists(): raise Rejected("原生控制端点已存在，拒绝覆盖")
        Path(self.socket + ".error").unlink(missing_ok=True)
        command = ["env", *[f"{k}={v}" for k, v in (env or {}).items()], *argv]
        self.tmux("new-session", "-d", "-s", "owner", "-x", "110", "-y", "40", "-c", self.session.config["cwd"], shlex.join(command),
                  ";", "set-option", "-t", "owner", "remain-on-exit", "failed")
        self.handle.update(kind="tmux", socket=self.socket, terminalSocket=self.tmux_socket, tmuxCommand=self.tmux_command())
        self.session.save()
        self.tmux("set-option", "-g", "status", "off")
        self.tmux("set-option", "-g", "prefix", "None")
        self.tmux("unbind-key", "-a")
        self.tmux("set-option", "-g", "history-limit", "2000")
        return True

    def terminal(self):
        if not self.alive(): raise Rejected("原生执行宿主已退出")
        return {"nativeId": self.session.data["nativeId"], "instanceId": self.handle["instanceId"],
                "argv": [*self.tmux_command(), "-S", self.tmux_socket, "attach-session", "-t", "owner:0"]}

    def has_attached_client(self):
        kind = self.handle.get("kind")
        if kind == "tmux":
            if not self.handle.get("tmuxCommand") or not self.handle.get("terminalSocket"): return False
            state = self.tmux("display-message", "-p", "-t", "owner", "#{session_attached}", check=False)
            return state.returncode == 0 and state.stdout.strip() not in (b"", b"0")
        if kind == "grok-leader":
            leader_pid = self.handle.get("pid")
            needle = self.socket.encode("utf-8", "ignore") if self.socket else b""
            if not needle: return False
            try: entries = os.listdir("/proc")
            except OSError: return False
            self_pid = os.getpid()
            for name in entries:
                if not name.isdigit(): continue
                pid = int(name)
                if pid in (leader_pid, self_pid): continue
                try: cmdline = Path(f"/proc/{pid}/cmdline").read_bytes()
                except OSError: continue
                if needle in cmdline: return True
        return False

    def stop(self, force=False):
        kind = self.handle.get("kind")
        if not kind:
            if not self.directory.is_symlink():
                shutil.rmtree(self.directory, ignore_errors=True)
            return True
        if not force and self.has_attached_client():
            return False
        if kind == "tmux":
            if self.handle.get("tmuxCommand") and self.handle.get("terminalSocket"):
                pane = self.tmux("display-message", "-p", "-t", "owner", "#{pane_pid}", check=False)
                self.tmux("set-option", "-t", "owner", "remain-on-exit", "off", check=False)
                raw_pid = pane.stdout.strip() if pane.returncode == 0 else b""
                pane_pid = int(raw_pid) if raw_pid.isdigit() else None
                start = process_start(pane_pid) if pane_pid else None
                if pane_pid and start:
                    try: os.kill(pane_pid, signal.SIGTERM)
                    except ProcessLookupError: pass
                    until = time.monotonic() + 5
                    while process_start(pane_pid) == start and time.monotonic() < until: time.sleep(.05)
                    if force and process_start(pane_pid) == start:
                        try: os.kill(pane_pid, signal.SIGKILL)
                        except ProcessLookupError: pass
                        until = time.monotonic() + 1
                        while process_start(pane_pid) == start and time.monotonic() < until: time.sleep(.05)
                    if not force and process_start(pane_pid) == start:
                        raise OSError("原生终端宿主进程尚未退出")
                self.tmux("kill-server", check=False)
                until = time.monotonic() + 1
                while self.alive() and time.monotonic() < until: time.sleep(.05)
                if not force and self.alive():
                    raise OSError("原生终端宿主尚未退出")
        elif kind == "grok-leader":
            pid, start = self.handle.get("pid"), self.handle.get("processStart")
            if not pid or not start or process_start(pid) != start:
                locked = grok_lock_leader(self.socket)
                if locked: pid, start = locked
            if pid and start and process_start(pid) == start:
                try: os.kill(pid, signal.SIGTERM)
                except ProcessLookupError: pass
                until = time.monotonic() + 5
                while process_start(pid) == start and time.monotonic() < until: time.sleep(.05)
                if process_start(pid) == start:
                    try: os.kill(pid, signal.SIGKILL)
                    except ProcessLookupError: pass
                    until = time.monotonic() + 1
                    while process_start(pid) == start and time.monotonic() < until: time.sleep(.05)
                if not force and process_start(pid) == start:
                    raise OSError("Grok 私有 leader 进程尚未退出")
        if not self.directory.is_symlink():
            shutil.rmtree(self.directory, ignore_errors=True)
        self.session.config.pop("nativeOwner", None)
        if not force:
            self.session.save()
        return True

    def connect(self, event, exited):
        error_file = Path(self.socket + ".error")
        end = time.monotonic() + 35
        while time.monotonic() < end:
            if error_file.is_file():
                try: detail = error_file.read_text(encoding="utf-8", errors="replace").strip()[:240]
                except OSError: detail = ""
                error_file.unlink(missing_ok=True)
                raise Rejected(detail or "原生双端宿主契约校验失败")
            if not self.alive(): raise Rejected("原生 TUI 启动失败或已退出")
            try: return SocketPeer(self.socket, self, event, exited)
            except (FileNotFoundError, ConnectionRefusedError): time.sleep(.1)
        raise Rejected("原生 TUI 尚未开放控制通道；请打开会话终端处理原生启动确认")

class OwnerProcess:
    def __init__(self, owner): self.owner = owner
    def poll(self): return None if self.owner.alive() else 1

class SocketPeer(Peer):
    reconnectable = True
    def __init__(self, path, owner, event, exited):
        self.event, self.exited = event, exited
        self.waiting_for_user = lambda: False
        self.pending = {}; self.lock = threading.RLock(); self.write_lock = threading.Lock()
        self.chunks = None; self.closed = False; self.diagnostic = bytearray()
        self.socket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.socket.connect(path)
        self.proc = OwnerProcess(owner)
        self.reader = self.socket.makefile("rb")
        self.proc.stdout = self.reader

    def send(self, frame):
        encoded = (json.dumps(frame, ensure_ascii=False, separators=(",", ":")) + "\n").encode()
        with self.write_lock:
            if self.closed: raise ConnectionError("原生观察连接已关闭")
            self.socket.sendall(encoded)

    def close(self):
        self.closed = True
        try: self.socket.shutdown(socket.SHUT_RDWR)
        except OSError: pass
        self.socket.close()
        thread = getattr(self, "reader_thread", None)
        if thread and thread is not threading.current_thread(): thread.join(timeout=2)
        self.reader.close()

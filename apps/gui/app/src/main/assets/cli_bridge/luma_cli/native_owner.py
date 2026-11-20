import json
import os
from pathlib import Path
import queue
import re
import shlex
import shutil
import signal
import socket
import subprocess
import threading
import time
import uuid
import zipfile
from .i18n import lang_code, tr
from .transport import Peer, Rejected

def process_start(pid):
    try:
        fields = Path(f"/proc/{int(pid)}/stat").read_text().rsplit(")", 1)[1].split()
        return None if fields[0] == "Z" else fields[19]
    except (OSError, ValueError, IndexError): return None

_MCP_NAME_RE = re.compile(r"^(?:.*[-_])?mcp(?:[-_.].*)?$")
_SHELL_NAMES = frozenset({"sh", "bash", "zsh", "dash", "ash", "ksh", "fish"})
_EXEC_CODE_FLAGS = frozenset({"-c", "-e", "--eval"})
_SKIP_VALUE_FLAGS = frozenset({"--cwd", "-C", "--dir", "--workdir", "--log", "--output", "-o"})
_HARNESS_NAMES = ("supervisor.mjs", "localharness")

def _read_proc_cmdline_args(pid):
    try:
        raw = Path(f"/proc/{int(pid)}/cmdline").read_bytes()
    except OSError:
        return []
    return [part.decode("utf-8", "ignore") for part in raw.split(b"\x00") if part]

def _read_proc_cmdline(pid):
    return " ".join(_read_proc_cmdline_args(pid))

def _cmdline_non_inline_args(args):
    if not args:
        return []
    exe = os.path.basename(args[0]).lower()
    is_shell = exe in _SHELL_NAMES
    is_node = exe.startswith("node")
    out = [args[0]]
    i = 1
    while i < len(args):
        arg = args[i]
        low = arg.lower()
        if low in _EXEC_CODE_FLAGS or (is_node and low in ("-p", "-pe", "--print")) or (not low.startswith("--") and low.startswith("-") and "c" in low[1:] and (is_shell or exe.startswith("python"))):
            out.append(arg)
            i += 2
            continue
        if low.startswith(("-c", "-e")) and len(low) > 2 and (is_shell or exe.startswith(("python", "node", "perl", "ruby"))):
            i += 1
            continue
        if low.startswith("--eval="):
            i += 1
            continue
        out.append(arg)
        i += 1
    return out

def _iter_cmdline_tokens(args):
    skip_next = False
    for idx, raw in enumerate(_cmdline_non_inline_args(args)):
        if skip_next:
            skip_next = False
            continue
        if raw in _SKIP_VALUE_FLAGS:
            skip_next = True
            continue
        low = raw.lower()
        if low.startswith(("--cwd=", "--dir=", "--workdir=")):
            continue
        for tok in low.split():
            yield idx, tok

def _cmdline_has_harness_helper(args):
    for _, tok in _iter_cmdline_tokens(args):
        if any(name in tok for name in _HARNESS_NAMES):
            return True
    return False

def _cmdline_is_acp_agent(args):
    for _, tok in _iter_cmdline_tokens(args):
        base = os.path.basename(tok.rstrip("/"))
        if base == "acp" or base.endswith(("-acp", "_acp")) or "localharness" in base:
            return True
    return False

def _cmdline_is_mcp_helper(args):
    for idx, tok in _iter_cmdline_tokens(args):
        if tok in ("--mcp", "--mcp-server", "mcp", "mcp-server", "mcp_server", "mcp-proxy"):
            return True
        if "modelcontextprotocol" in tok:
            return True
        if idx == 0 or not tok.startswith("-"):
            clean = tok.rstrip("/")
            base = os.path.basename(clean)
            if _MCP_NAME_RE.match(base):
                return True
            if "/node_modules/" in clean:
                after = clean.split("/node_modules/", 1)[1].split("/")
                pkg = after[1] if after and after[0].startswith("@") and len(after) > 1 else (after[0] if after else "")
                if pkg and _MCP_NAME_RE.match(pkg):
                    return True
    return False

def _fd_flags(pid, fd_name):
    try:
        for line in Path(f"/proc/{int(pid)}/fdinfo/{fd_name}").read_text().splitlines():
            if line.startswith("flags:"):
                return int(line.split(":", 1)[1].strip(), 8)
    except (OSError, ValueError):
        return None
    return None

def _read_proc_stdio_state(pid):
    write_pipes = set()
    all_sockets = set()
    has_nonstdio_rw_socket = False
    fd_dir = Path(f"/proc/{int(pid)}/fd")
    try:
        entries = list(fd_dir.iterdir())
    except OSError:
        return write_pipes, all_sockets, has_nonstdio_rw_socket
    for entry in entries:
        fd_name = entry.name
        try:
            target = os.readlink(entry)
        except OSError:
            continue
        if target.startswith("pipe:["):
            if fd_name != "0":
                flags = _fd_flags(pid, fd_name)
                if flags is not None and (flags & os.O_ACCMODE) in (os.O_WRONLY, os.O_RDWR):
                    write_pipes.add(target)
        elif target.startswith("socket:["):
            all_sockets.add(target)
            if fd_name not in ("0", "1", "2"):
                flags = _fd_flags(pid, fd_name)
                if flags is not None and (flags & os.O_ACCMODE) in (os.O_WRONLY, os.O_RDWR):
                    has_nonstdio_rw_socket = True
    return write_pipes, all_sockets, has_nonstdio_rw_socket

def _is_stdio_mcp_helper(parent_stdio, child_pid, args):
    if not _cmdline_is_mcp_helper(args):
        return False
    try:
        child_fd0 = os.readlink(f"/proc/{int(child_pid)}/fd/0")
    except OSError:
        return False
    write_pipes, all_sockets, has_nonstdio_rw_socket = parent_stdio
    if child_fd0.startswith("pipe:["):
        return child_fd0 in write_pipes
    if child_fd0.startswith("socket:["):
        return child_fd0 not in all_sockets and has_nonstdio_rw_socket
    return False

def _proc_children_map(exclude_pids):
    by_ppid = {}
    pid_starts = {}
    try:
        entries = os.listdir("/proc")
    except OSError:
        return by_ppid, pid_starts
    for name in entries:
        if not name.isdigit():
            continue
        child_pid = int(name)
        if child_pid in exclude_pids:
            continue
        try:
            fields = Path(f"/proc/{child_pid}/stat").read_text().rsplit(")", 1)[1].split()
            if fields[0] != "Z":
                by_ppid.setdefault(int(fields[1]), []).append(child_pid)
                pid_starts[child_pid] = fields[19]
        except (OSError, ValueError, IndexError):
            continue
    return by_ppid, pid_starts

def _proc_child_live_args(child_pid, pid_starts):
    args = _read_proc_cmdline_args(child_pid)
    if not args or process_start(child_pid) != pid_starts.get(child_pid):
        return None
    return args

def _has_live_non_helper_descendant(parent_pid, by_ppid, pid_starts, visited, inherited_stdio=None):
    children = by_ppid.get(parent_pid, ())
    if not children:
        return False
    parent_stdio = inherited_stdio if inherited_stdio is not None else _read_proc_stdio_state(parent_pid)
    for child_pid in children:
        if child_pid in visited:
            continue
        visited.add(child_pid)
        args = _proc_child_live_args(child_pid, pid_starts)
        if args is None:
            continue
        if _is_stdio_mcp_helper(parent_stdio, child_pid, args):
            if _has_live_non_helper_descendant(child_pid, by_ppid, pid_starts, visited, inherited_stdio=parent_stdio):
                return True
            continue
        if _cmdline_has_harness_helper(args):
            if _has_live_non_helper_descendant(child_pid, by_ppid, pid_starts, visited):
                return True
            continue
        return True
    return False

def non_mcp_background_child_alive(pid):
    if not pid:
        return False
    target = int(pid)
    by_ppid, pid_starts = _proc_children_map({target, os.getpid()})
    return _has_live_non_helper_descendant(target, by_ppid, pid_starts, {target})

def tcp_port_has_established_client(port):
    if type(port) is not int or not 1 <= port <= 65535:
        return False
    targets_v4 = {f"0100007F:{port:04X}"}
    targets_v6 = {
        f"0000000000000000FFFF00000100007F:{port:04X}",
        f"00000000000000000000000001000000:{port:04X}",
    }
    for proc_file, targets in (("/proc/net/tcp", targets_v4), ("/proc/net/tcp6", targets_v6)):
        try:
            lines = Path(proc_file).read_text(encoding="utf-8", errors="ignore").splitlines()[1:]
        except OSError:
            continue
        for line in lines:
            parts = line.split()
            if len(parts) >= 4 and parts[1].upper() in targets and parts[3] == "01":
                return True
    return False

def process_has_children(pid):
    if not pid:
        return False
    target = int(pid)
    try:
        entries = os.listdir("/proc")
    except OSError:
        return False
    for name in entries:
        if not name.isdigit():
            continue
        child_pid = int(name)
        if child_pid == target:
            continue
        try:
            fields = Path(f"/proc/{child_pid}/stat").read_text().rsplit(")", 1)[1].split()
            if fields[0] != "Z" and int(fields[1]) == target:
                return True
        except (OSError, ValueError, IndexError):
            continue
    return False

def _is_pi_host_cmdline(pid):
    return "pi-host.mjs" in " ".join(_cmdline_non_inline_args(_read_proc_cmdline_args(pid)))

def _tmux_host_kind(handle):
    if isinstance(handle, dict) and isinstance(handle.get("hostKind"), str) and handle["hostKind"]:
        return handle["hostKind"]
    return "pi-sdk"

def _is_expected_tmux_pane(pid, handle):
    if _tmux_host_kind(handle) == "pi-sdk":
        return _is_pi_host_cmdline(pid)
    args = _cmdline_non_inline_args(_read_proc_cmdline_args(pid))
    if not args:
        return False
    binary_name = handle.get("binaryName") if isinstance(handle, dict) else ""
    if isinstance(binary_name, str) and binary_name:
        return any(os.path.basename(arg.rstrip("/")) == binary_name or binary_name in arg for arg in args)
    return True

def _query_tmux_pane_pid(command, socket_path):
    pane = subprocess.run([*command, "-S", socket_path, "-f", "/dev/null", "display-message", "-p", "-t", "owner", "#{pane_dead}\t#{pane_pid}"],
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5, check=False)
    if pane.returncode != 0:
        return None, None
    parts = pane.stdout.decode("utf-8", "ignore").strip().split("\t", 1)
    if len(parts) != 2 or parts[0] != "0" or not parts[1].isdigit():
        return None, None
    pane_pid = int(parts[1])
    start = process_start(pane_pid)
    return (pane_pid, start) if start else (None, None)

def _verified_tmux_pane_pid(command, socket_path, handle):
    expected_start = handle.get("processStart") if isinstance(handle, dict) else None
    if not isinstance(expected_start, str) or not expected_start:
        return None, None
    expected_pid = handle.get("pid")
    try:
        pane_pid, start = _query_tmux_pane_pid(command, socket_path)
    except (OSError, subprocess.TimeoutExpired, ValueError):
        return None, None
    if not pane_pid or start != expected_start:
        return None, None
    if isinstance(expected_pid, int) and expected_pid != pane_pid:
        return None, None
    if not _is_expected_tmux_pane(pane_pid, handle):
        return None, None
    return pane_pid, start

def pi_process_has_live_subprocesses(pid):
    if not pid:
        return False
    target = int(pid)
    by_ppid, pid_starts = _proc_children_map({target, os.getpid()})
    children = by_ppid.get(target, ())
    if not children:
        return False
    root_stdio = _read_proc_stdio_state(target)
    visited = {target}
    for c1 in children:
        if c1 in visited:
            continue
        visited.add(c1)
        args1 = _proc_child_live_args(c1, pid_starts)
        if args1 is None:
            continue
        if _is_stdio_mcp_helper(root_stdio, c1, args1):
            if _has_live_non_helper_descendant(c1, by_ppid, pid_starts, visited, inherited_stdio=root_stdio):
                return True
            continue
        if _cmdline_has_harness_helper(args1):
            sup_stdio = _read_proc_stdio_state(c1)
            for c2 in by_ppid.get(c1, ()):
                if c2 in visited:
                    continue
                visited.add(c2)
                args2 = _proc_child_live_args(c2, pid_starts)
                if args2 is None:
                    continue
                if _is_stdio_mcp_helper(sup_stdio, c2, args2):
                    if _has_live_non_helper_descendant(c2, by_ppid, pid_starts, visited, inherited_stdio=sup_stdio):
                        return True
                    continue
                if _cmdline_is_acp_agent(args2):
                    if _has_live_non_helper_descendant(c2, by_ppid, pid_starts, visited):
                        return True
                    continue
                return True
            continue
        return True
    return False

def tmux_pi_has_live_subprocesses(command, socket_path):
    if not command or not socket_path:
        return False
    try:
        pane_pid, _ = _query_tmux_pane_pid(command, socket_path)
    except subprocess.TimeoutExpired:
        return True
    except (OSError, ValueError):
        return False
    if not pane_pid or not _is_pi_host_cmdline(pane_pid):
        return False
    return pi_process_has_live_subprocesses(pane_pid)

def tmux_pty_has_live_subprocesses(command, socket_path, handle):
    if not command or not socket_path:
        return False
    try:
        pane_pid, _ = _query_tmux_pane_pid(command, socket_path)
    except subprocess.TimeoutExpired:
        return True
    except (OSError, ValueError):
        return False
    if not pane_pid or not _is_expected_tmux_pane(pane_pid, handle):
        return False
    return non_mcp_background_child_alive(pane_pid)

def pty_owner_settled(directory, startup_grace_sec=5):
    if directory is None:
        return True
    state_file = directory / "runtime-state.json"
    if not state_file.is_file():
        if directory.exists():
            try:
                if time.time() - directory.stat().st_mtime < startup_grace_sec:
                    return False
            except OSError:
                return False
        return True
    try:
        data = json.loads(state_file.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return False
    if not isinstance(data, dict):
        return False
    if data.get("busy") or data.get("waitingForStartup"):
        return False
    for key in ("pendingRequests", "sessionCrons", "backgroundTasks"):
        count = data.get(key)
        if isinstance(count, int) and count > 0:
            return False
    return True

def grok_socket_has_client(socket_path, leader_pid):
    needle = socket_path.encode("utf-8", "ignore") if socket_path else b""
    if not needle:
        return False
    try:
        entries = os.listdir("/proc")
    except OSError:
        return False
    self_pid = os.getpid()
    for name in entries:
        if not name.isdigit():
            continue
        pid = int(name)
        if pid in (leader_pid, self_pid):
            continue
        try:
            cmdline = Path(f"/proc/{pid}/cmdline").read_bytes()
        except OSError:
            continue
        if needle in cmdline:
            return True
    return False

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

def _owner_directory(handle):
    if not isinstance(handle, dict):
        return None
    recorded = handle.get("directory")
    if isinstance(recorded, str) and recorded:
        return Path(recorded)
    instance_id = handle.get("instanceId")
    if isinstance(instance_id, str) and instance_id:
        legacy = Path("/tmp") / ("luma-native-" + instance_id)
        if legacy.exists():
            return legacy
        return Path("/tmp") / ("ln-" + instance_id[:16])
    return None

def _owner_control_socket(handle, directory=None):
    if isinstance(handle, dict) and isinstance(handle.get("socket"), str) and handle["socket"]:
        return handle["socket"]
    target = directory if directory is not None else _owner_directory(handle)
    if target is None:
        return ""
    if (target / "control.sock").exists():
        return str(target / "control.sock")
    return str(target / "c.sock")

def _owner_terminal_socket(handle, directory=None):
    if isinstance(handle, dict) and isinstance(handle.get("terminalSocket"), str) and handle["terminalSocket"]:
        return handle["terminalSocket"]
    target = directory if directory is not None else _owner_directory(handle)
    if target is None:
        return ""
    if (target / "terminal.sock").exists():
        return str(target / "terminal.sock")
    return str(target / "t.sock")

def recorded_owner_alive(config):
    handle = config.get("nativeOwner", {})
    if handle.get("kind") in ("grok-leader", "codebuddy-server"):
        pid, start = handle.get("pid"), handle.get("processStart")
        if pid and start and process_start(pid) == start:
            return True
        if handle.get("kind") == "grok-leader" and handle.get("instanceId"):
            sock = _owner_control_socket(handle)
            if sock and grok_lock_leader(sock): return True
        return False
    if handle.get("kind") != "tmux": return False
    command, socket_path = handle.get("tmuxCommand"), handle.get("terminalSocket")
    if not command or not socket_path: return None
    try:
        result = subprocess.run([*command, "-S", socket_path, "-f", "/dev/null", "display-message", "-p", "-t", "owner", "#{pane_dead}"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10)
        return result.returncode == 0 and result.stdout.strip() == b"0"
    except (OSError, subprocess.TimeoutExpired): return None

def _stop_tmux_pane(command, socket_path, handle, sigterm_timeout=3, force=True, raise_on_alive=False):
    pane_pid, start = _verified_tmux_pane_pid(command, socket_path, handle)
    try:
        subprocess.run([*command, "-S", socket_path, "-f", "/dev/null", "set-option", "-t", "owner", "remain-on-exit", "off"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5, check=False)
    except (OSError, subprocess.TimeoutExpired):
        pass
    if pane_pid and start:
        try: os.kill(pane_pid, signal.SIGTERM)
        except ProcessLookupError: pass
        until = time.monotonic() + sigterm_timeout
        while process_start(pane_pid) == start and time.monotonic() < until: time.sleep(.05)
        if force and process_start(pane_pid) == start:
            try: os.kill(pane_pid, signal.SIGKILL)
            except ProcessLookupError: pass
            if raise_on_alive:
                until = time.monotonic() + 1
                while process_start(pane_pid) == start and time.monotonic() < until: time.sleep(.05)
        if raise_on_alive and not force and process_start(pane_pid) == start:
            raise OSError(tr("原生终端宿主进程尚未退出", "Native terminal host process has not exited yet"))
    try:
        subprocess.run([*command, "-S", socket_path, "-f", "/dev/null", "kill-server"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5, check=False)
    except (OSError, subprocess.TimeoutExpired):
        pass

def stop_recorded_owner(config):
    handle = config.get("nativeOwner")
    if not isinstance(handle, dict): return
    kind = handle.get("kind")
    directory = _owner_directory(handle)
    if kind == "tmux":
        command, socket_path = handle.get("tmuxCommand"), handle.get("terminalSocket")
        if command and socket_path:
            _stop_tmux_pane(command, socket_path, handle, sigterm_timeout=3, force=True, raise_on_alive=False)
    elif kind in ("grok-leader", "codebuddy-server"):
        pid, start = handle.get("pid"), handle.get("processStart")
        if kind == "grok-leader" and (not pid or not start or process_start(pid) != start) and directory:
            locked = grok_lock_leader(_owner_control_socket(handle, directory))
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

def tmux_client_idle_seconds(command, socket_path):
    try:
        result = subprocess.run([*command, "-S", socket_path, "-f", "/dev/null", "list-clients", "-t", "owner", "-F", "#{client_activity}"],
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode != 0:
        return None
    timestamps = [int(line) for line in result.stdout.splitlines() if line.strip().isdigit()]
    if not timestamps:
        return float("inf")
    return max(0.0, time.time() - max(timestamps))

def _host_or_local_process_start(pid):
    if not pid or int(pid) <= 0:
        return None
    for prefix in ("/proc", "/proc/1/root/proc"):
        try:
            fields = Path(f"{prefix}/{int(pid)}/stat").read_text().rsplit(")", 1)[1].split()
            return None if fields[0] == "Z" else fields[19]
        except (OSError, ValueError, IndexError):
            continue
    return None

def _process_stat_readable(pid):
    for prefix in ("/proc", "/proc/1/root/proc"):
        if Path(f"{prefix}/{int(pid)}/stat").exists():
            return True
    return False

def unix_socket_has_connected_client(socket_path):
    if not socket_path:
        return False
    path_obj = Path(socket_path)
    if not path_obj.exists():
        return False
    targets = {str(socket_path)}
    try:
        targets.add(os.path.realpath(socket_path))
    except OSError:
        pass
    try:
        lines = Path("/proc/net/unix").read_text(encoding="utf-8", errors="ignore").splitlines()[1:]
    except OSError:
        return False
    for line in lines:
        parts = line.split(None, 7)
        if len(parts) >= 8 and parts[5] == "03" and parts[7].rstrip("\x00") in targets:
            return True
    return False

def foreground_terminal_protects(config):
    if not isinstance(config, dict):
        return False
    owner_dir = config.get("ownerDirectory")
    if not isinstance(owner_dir, str) or not owner_dir:
        return False
    candidates = []
    seen = set()
    for base in (Path(owner_dir), Path(owner_dir).resolve()):
        for parent in list(base.parents)[:4]:
            candidate = parent / "foreground-terminal.json"
            key = str(candidate)
            if key not in seen:
                seen.add(key)
                candidates.append(candidate)
    data = None
    for candidate in candidates:
        if not candidate.is_file():
            continue
        try:
            data = json.loads(candidate.read_text(encoding="utf-8"))
            break
        except (OSError, ValueError):
            continue
    if not isinstance(data, dict):
        return False
    updated_at = data.get("updatedAt")
    if not isinstance(updated_at, (int, float)) or time.time() * 1000 - updated_at > 180_000:
        return False
    pid = data.get("pid")
    expected_start = data.get("processStart")
    if isinstance(pid, int) and pid > 0 and isinstance(expected_start, str) and expected_start:
        if _process_stat_readable(pid):
            if _host_or_local_process_start(pid) != expected_start:
                return False
        elif Path("/proc/self/stat").exists():
            return False
    instance_id = (config.get("nativeOwner") or {}).get("instanceId")
    session_id = config.get("id")
    if isinstance(instance_id, str) and instance_id and data.get("instanceId") == instance_id:
        return True
    if isinstance(session_id, str) and session_id and data.get("sessionId") == session_id:
        return True
    return False

def control_socket_settled(socket_path, directory=None, startup_grace_sec=5):
    if not socket_path or not Path(socket_path).exists():
        if directory is not None and directory.exists():
            try:
                if time.time() - directory.stat().st_mtime < startup_grace_sec:
                    return False
            except OSError:
                return False
        return True
    if unix_socket_has_connected_client(socket_path):
        return False
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    reader = None
    connected_probe = False
    try:
        sock.settimeout(1.5)
        sock.connect(socket_path)
        connected_probe = True
        sock.sendall(b'{"id":"luma-reap-probe","type":"get_state"}\n')
        reader = sock.makefile("rb")
        deadline = time.monotonic() + 1.5
        while time.monotonic() < deadline:
            remaining = max(0.05, deadline - time.monotonic())
            sock.settimeout(remaining)
            line = reader.readline()
            if not line:
                return False
            try:
                frame = json.loads(line.decode("utf-8", "ignore"))
            except ValueError:
                continue
            if not isinstance(frame, dict) or frame.get("id") != "luma-reap-probe":
                continue
            if frame.get("success") is not True:
                return False
            data = frame.get("data")
            if not isinstance(data, dict):
                return False
            if isinstance(data.get("observerCount"), int) and data["observerCount"] > 1:
                return False
            if data.get("isSettled") is not True:
                return False
            if data.get("isStreaming") or data.get("isCompacting") or data.get("pendingMessageCount"):
                return False
            queued = data.get("queuedMessages")
            has_queued = any(bool(v) for v in queued.values()) if isinstance(queued, dict) else bool(queued)
            if has_queued or data.get("requests"):
                return False
            receipts = data.get("receipts")
            if isinstance(receipts, dict):
                for entry in receipts.values():
                    if isinstance(entry, dict) and entry.get("state") in ("pending", "unknown", "dispatching", "queued"):
                        return False
            return True
        return False
    except (OSError, ValueError):
        return False
    finally:
        if reader is not None:
            try: reader.close()
            except OSError: pass
        try: sock.close()
        except OSError: pass
        if connected_probe:
            drain_until = time.monotonic() + 0.15
            while unix_socket_has_connected_client(socket_path) and time.monotonic() < drain_until:
                time.sleep(0.005)

def reap_recorded_owner(config, max_client_idle_sec, idle_tracker=None):
    handle = config.get("nativeOwner")
    if not isinstance(handle, dict):
        return False
    kind = handle.get("kind")
    instance_id = handle.get("instanceId")
    alive = recorded_owner_alive(config)
    if alive is False:
        if idle_tracker is not None and isinstance(instance_id, str) and instance_id:
            idle_tracker.pop(instance_id, None)
        stop_recorded_owner(config)
        return True
    if foreground_terminal_protects(config):
        if idle_tracker is not None and isinstance(instance_id, str) and instance_id:
            idle_tracker.pop(instance_id, None)
        return False
    if alive is True and kind == "grok-leader":
        directory = _owner_directory(handle)
        sock_path = _owner_control_socket(handle, directory)
        pid, start = handle.get("pid"), handle.get("processStart")
        if (not pid or not start or process_start(pid) != start) and sock_path:
            locked = grok_lock_leader(sock_path)
            if locked:
                pid, _ = locked
        if not pid or grok_socket_has_client(sock_path, pid) or process_has_children(pid):
            if idle_tracker is not None and isinstance(instance_id, str) and instance_id:
                idle_tracker.pop(instance_id, None)
            return False
        if idle_tracker is not None and isinstance(instance_id, str) and instance_id:
            since = idle_tracker.setdefault(instance_id, time.monotonic())
            if time.monotonic() - since < max_client_idle_sec:
                return False
            idle_tracker.pop(instance_id, None)
        elif max_client_idle_sec > 0:
            return False
        stop_recorded_owner(config)
        return recorded_owner_alive(config) is False
    elif alive is True and kind == "codebuddy-server":
        pid, start, port = handle.get("pid"), handle.get("processStart"), handle.get("port")
        if not pid or not start or process_start(pid) != start or type(port) is not int or port <= 0:
            if idle_tracker is not None and isinstance(instance_id, str) and instance_id:
                idle_tracker.pop(instance_id, None)
            return False
        if tcp_port_has_established_client(port) or non_mcp_background_child_alive(pid):
            if idle_tracker is not None and isinstance(instance_id, str) and instance_id:
                idle_tracker.pop(instance_id, None)
            return False
        if idle_tracker is not None and isinstance(instance_id, str) and instance_id:
            since = idle_tracker.setdefault(instance_id, time.monotonic())
            if time.monotonic() - since < max_client_idle_sec:
                return False
            idle_tracker.pop(instance_id, None)
        elif max_client_idle_sec > 0:
            return False
        stop_recorded_owner(config)
        return recorded_owner_alive(config) is False
    if alive is not True or kind != "tmux":
        return False
    command, term_sock = handle.get("tmuxCommand"), handle.get("terminalSocket")
    if not command or not term_sock:
        return False
    host_kind = _tmux_host_kind(handle)
    ctrl_sock = handle.get("socket") if host_kind == "pi-sdk" else None
    if ctrl_sock and unix_socket_has_connected_client(ctrl_sock):
        if idle_tracker is not None and isinstance(instance_id, str) and instance_id:
            idle_tracker.pop(instance_id, None)
        return False
    idle_for = tmux_client_idle_seconds(command, term_sock)
    if idle_for is None or idle_for < max_client_idle_sec:
        if idle_tracker is not None and isinstance(instance_id, str) and instance_id:
            idle_tracker.pop(instance_id, None)
        return False
    if idle_for == float("inf") and max_client_idle_sec > 0:
        if idle_tracker is None or not isinstance(instance_id, str) or not instance_id:
            return False
        since = idle_tracker.setdefault(instance_id, time.monotonic())
        if time.monotonic() - since < max_client_idle_sec:
            return False
    has_subprocesses = (
        tmux_pi_has_live_subprocesses(command, term_sock)
        if host_kind == "pi-sdk"
        else tmux_pty_has_live_subprocesses(command, term_sock, handle)
    )
    if has_subprocesses:
        if idle_tracker is not None and isinstance(instance_id, str) and instance_id:
            idle_tracker.pop(instance_id, None)
        return False
    directory = _owner_directory(handle)
    if host_kind == "pi-sdk":
        grace = min(30, max(1, max_client_idle_sec)) if config.get("nativeProtocol") == "pi-native-sdk-v1" else 0
        settled = control_socket_settled(ctrl_sock, directory, grace)
    else:
        grace = min(30, max(1, max_client_idle_sec)) if max_client_idle_sec > 0 else 0
        settled = pty_owner_settled(directory, grace)
    if not settled:
        if idle_tracker is not None and isinstance(instance_id, str) and instance_id:
            idle_tracker.pop(instance_id, None)
        return False
    if foreground_terminal_protects(config):
        if idle_tracker is not None and isinstance(instance_id, str) and instance_id:
            idle_tracker.pop(instance_id, None)
        return False
    if ctrl_sock and unix_socket_has_connected_client(ctrl_sock):
        if idle_tracker is not None and isinstance(instance_id, str) and instance_id:
            idle_tracker.pop(instance_id, None)
        return False
    recheck_idle = tmux_client_idle_seconds(command, term_sock)
    if recheck_idle is None or recheck_idle < max_client_idle_sec:
        if idle_tracker is not None and isinstance(instance_id, str) and instance_id:
            idle_tracker.pop(instance_id, None)
        return False
    recheck_subprocesses = (
        tmux_pi_has_live_subprocesses(command, term_sock)
        if host_kind == "pi-sdk"
        else tmux_pty_has_live_subprocesses(command, term_sock, handle)
    )
    if recheck_subprocesses:
        if idle_tracker is not None and isinstance(instance_id, str) and instance_id:
            idle_tracker.pop(instance_id, None)
        return False
    if idle_tracker is not None and isinstance(instance_id, str) and instance_id:
        idle_tracker.pop(instance_id, None)
    if recheck_idle != float("inf"):
        try:
            subprocess.run([*command, "-S", term_sock, "-f", "/dev/null", "detach-client", "-s", "owner"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5, check=False)
        except (OSError, subprocess.TimeoutExpired):
            pass
    stop_recorded_owner(config)
    return recorded_owner_alive(config) is False

class NativeOwner:
    def __init__(self, session):
        self.session = session
        owner = session.config.get("nativeOwner")
        if not owner:
            instance_id = uuid.uuid4().hex
            owner = {"instanceId": instance_id, "directory": str(Path("/tmp") / ("ln-" + instance_id[:16]))}
            session.config["nativeOwner"] = owner
            session.save()
        self.handle = owner
        self.directory = _owner_directory(owner)
        self.directory.mkdir(mode=0o700, exist_ok=True)
        if self.directory.is_symlink() or self.directory.stat().st_uid != os.getuid():
            raise Rejected(tr("原生宿主目录归属不符", "Native host directory ownership mismatch"))
        os.chmod(self.directory, 0o700)
        self.socket = _owner_control_socket(owner, self.directory)
        self.tmux_socket = _owner_terminal_socket(owner, self.directory)
        if owner.get("kind") and not self.alive():
            if not session.config.get("nativeId"):
                raise Rejected(tr("原生创建结果未知，不能自动再建会话", "Native session creation result is unknown; cannot recreate automatically"))
            if session.config["cli"] == "pi":
                saved = session.config.get("sessionFile")
                accepted = "accepted" in session.config.get("receipts", {}).values()
                if accepted and (not saved or not Path(saved).is_file()):
                    raise Rejected(tr("原生会话尚未可靠落盘，不能用新会话替代恢复", "Native session has not been persisted reliably; cannot replace with a new session"))
            if owner.get("kind") == "tmux":
                self.tmux("kill-server", check=False)
            shutil.rmtree(self.directory, ignore_errors=True)
            next_id = uuid.uuid4().hex
            session.config["nativeOwner"] = {"instanceId": next_id, "directory": str(Path("/tmp") / ("ln-" + next_id[:16]))}
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

    def tmux(self, *args, check=True, input_bytes=None):
        result = subprocess.run([*self.tmux_command(), "-S", self.tmux_socket, "-f", "/dev/null", *args],
                                input=input_bytes, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
        if check and result.returncode: raise Rejected(tr("原生终端宿主操作失败（tmux）", "Native terminal host operation failed (tmux)"))
        return result

    def tmux_command(self):
        if self.handle.get("tmuxCommand"): return self.handle["tmuxCommand"]
        command = shutil.which("tmux")
        if command: return [command]
        base = Path(self.session.config["ownerDirectory"]).parent / "tmux-debian-3.5a"
        if not (base / "tmux").is_file():
            archive = Path(__file__).parent.parent / "native/tmux-debian-arm64.zip"
            if not archive.is_file(): raise Rejected(tr("App 缺少原生终端运行依赖 tmux", "App is missing required native terminal dependency: tmux"))
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

    def launch_tui(self, argv, env=None, *, host_kind="pi-sdk", binary_name=""):
        if self.handle.get("kind"):
            if self.alive(): return False
            raise Rejected(tr("原生宿主已退出，请重新连接核对保存的身份", "Native host has exited; please reconnect to verify saved identity"))
        if host_kind == "pi-sdk" and Path(self.socket).exists():
            raise Rejected(tr("原生控制端点已存在，拒绝覆盖", "Native control endpoint already exists; refusing to overwrite"))
        Path(self.socket + ".error").unlink(missing_ok=True)
        merged_env = {"LUMA_LANG": lang_code(), **(env or {})}
        command = ["env", *[f"{k}={v}" for k, v in merged_env.items()], *argv]
        self.tmux("new-session", "-d", "-s", "owner", "-x", "110", "-y", "40", "-c", self.session.config["cwd"], shlex.join(command),
                  ";", "set-option", "-t", "owner", "remain-on-exit", "failed")
        tmux_cmd = self.tmux_command()
        handle_data = {"kind": "tmux", "hostKind": host_kind, "terminalSocket": self.tmux_socket, "tmuxCommand": tmux_cmd}
        if host_kind == "pi-sdk":
            handle_data["socket"] = self.socket
        if binary_name:
            handle_data["binaryName"] = binary_name
        try:
            pane_pid, pane_start = _query_tmux_pane_pid(tmux_cmd, self.tmux_socket)
        except (OSError, subprocess.TimeoutExpired, ValueError):
            pane_pid, pane_start = None, None
        if pane_pid and pane_start:
            handle_data["pid"] = pane_pid
            handle_data["processStart"] = pane_start
        self.handle.update(handle_data)
        self.session.save()
        self.tmux("set-option", "-g", "status", "off")
        self.tmux("set-option", "-g", "prefix", "None")
        self.tmux("unbind-key", "-a")
        self.tmux("set-option", "-g", "history-limit", "2000")
        return True

    def write_private_file(self, name, content, mode=0o600):
        target = self.directory / name
        tmp = self.directory / f".{name}.{uuid.uuid4().hex}.tmp"
        raw = content.encode("utf-8") if isinstance(content, str) else content
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
        with os.fdopen(fd, "wb") as handle:
            handle.write(raw)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(tmp, mode)
        os.replace(tmp, target)
        return target

    def write_runtime_state(self, **state):
        try:
            self.write_private_file("runtime-state.json", json.dumps(state, ensure_ascii=False))
        except OSError:
            pass

    def pane_pid(self):
        if self.handle.get("kind") != "tmux":
            return self.handle.get("pid")
        cmd = self.handle.get("tmuxCommand")
        sock = self.handle.get("terminalSocket")
        if not cmd or not sock:
            return None
        pid, _ = _verified_tmux_pane_pid(cmd, sock, self.handle)
        if pid:
            return pid
        try:
            pid, _ = _query_tmux_pane_pid(cmd, sock)
        except (OSError, subprocess.TimeoutExpired, ValueError):
            return None
        return pid if pid and _is_expected_tmux_pane(pid, self.handle) else None

    def inject_text(self, body, submit=True, submit_delay=0.12):
        if not self.alive():
            raise Rejected(tr("原生执行宿主已退出", "Native execution host has exited"))
        buf = "luma-" + uuid.uuid4().hex[:12]
        self.tmux("load-buffer", "-b", buf, "-", input_bytes=body.encode("utf-8"))
        self.tmux("paste-buffer", "-b", buf, "-p", "-d", "-t", "owner")
        if submit:
            if submit_delay > 0:
                time.sleep(submit_delay)
            self.tmux("send-keys", "-t", "owner", "Enter")

    def send_keys(self, *keys, literal=False):
        if not self.alive():
            raise Rejected(tr("原生执行宿主已退出", "Native execution host has exited"))
        flags = ("-l",) if literal else ()
        self.tmux("send-keys", "-t", "owner", *flags, *keys)

    def capture_pane(self, lines=60):
        if self.handle.get("kind") != "tmux":
            return ""
        try:
            result = self.tmux("capture-pane", "-p", "-t", "owner", "-S", f"-{int(lines)}", check=False)
        except (OSError, subprocess.TimeoutExpired):
            return ""
        if result.returncode != 0:
            return ""
        return result.stdout.decode("utf-8", "replace")

    def client_idle_seconds(self):
        if self.handle.get("kind") != "tmux":
            return float("inf")
        cmd = self.handle.get("tmuxCommand")
        sock = self.handle.get("terminalSocket")
        if not cmd or not sock:
            return float("inf")
        idle = tmux_client_idle_seconds(cmd, sock)
        return float("inf") if idle is None else idle

    def terminal(self):
        if not self.alive(): raise Rejected(tr("原生执行宿主已退出", "Native execution host has exited"))
        return {"nativeId": self.session.data["nativeId"], "instanceId": self.handle["instanceId"],
                "argv": [*self.tmux_command(), "-S", self.tmux_socket, "attach-session", "-t", "owner:0"]}

    def has_attached_client(self):
        kind = self.handle.get("kind")
        if kind == "tmux":
            if not self.handle.get("tmuxCommand") or not self.handle.get("terminalSocket"): return False
            state = self.tmux("display-message", "-p", "-t", "owner", "#{session_attached}", check=False)
            return state.returncode == 0 and state.stdout.strip() not in (b"", b"0")
        if kind == "grok-leader":
            return grok_socket_has_client(self.socket, self.handle.get("pid"))
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
                _stop_tmux_pane(self.handle["tmuxCommand"], self.handle["terminalSocket"], self.handle,
                                sigterm_timeout=5, force=force, raise_on_alive=True)
                until = time.monotonic() + 1
                while self.alive() and time.monotonic() < until: time.sleep(.05)
                if not force and self.alive():
                    raise OSError(tr("原生终端宿主尚未退出", "Native terminal host has not exited yet"))
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
                    raise OSError(tr("Grok 私有 leader 进程尚未退出", "Grok private leader process has not exited yet"))
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
                raise Rejected(detail or tr("原生双端宿主契约校验失败", "Native host contract validation failed"))
            if not self.alive(): raise Rejected(tr("原生 TUI 启动失败或已退出", "Native TUI failed to start or has exited"))
            try: return SocketPeer(self.socket, self, event, exited)
            except (FileNotFoundError, ConnectionRefusedError): time.sleep(.1)
        raise Rejected(tr("原生 TUI 尚未开放控制通道；请打开会话终端处理原生启动确认", "Native TUI control channel is not open yet; open session terminal to complete startup confirmation"))

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
            if self.closed: raise ConnectionError(tr("原生观察连接已关闭", "Native observer connection is closed"))
            self.socket.sendall(encoded)

    def close(self):
        self.closed = True
        try: self.socket.shutdown(socket.SHUT_RDWR)
        except OSError: pass
        self.socket.close()
        thread = getattr(self, "reader_thread", None)
        if thread and thread is not threading.current_thread(): thread.join(timeout=2)
        self.reader.close()

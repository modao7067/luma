import os
from pathlib import Path
import secrets
import shutil
import threading
import time
from .base import Session
from .i18n import tr
from .native_owner import (
    NativeOwner,
    OwnerProcess,
    foreground_terminal_protects,
    non_mcp_background_child_alive,
)
from .transport import Rejected

def tmux_available():
    if shutil.which("tmux"):
        return True
    return (Path(__file__).parent.parent / "native/tmux-debian-arm64.zip").is_file()

class PtyOwnerPeer:
    reconnectable = True

    def __init__(self, owner, on_close):
        self.owner = owner
        self._on_close = on_close
        self._closed = False
        self._close_lock = threading.Lock()
        self.proc = OwnerProcess(owner)
        self.pending = {}

    @property
    def closed(self):
        return self._closed or not self.owner.alive()

    def close(self):
        with self._close_lock:
            if self._closed:
                return
            self._closed = True
        if self._on_close is not None:
            self._on_close()

class PtyTuiSession(Session):
    input_evidence_protocol = 1
    correlated_delivery = False
    check_peer_background_children = False
    pty_binary_name = ""

    def __init__(self, config, save):
        super().__init__(config, save)
        self.owner = None
        self.hook_manager = None
        self.hook_token = ""
        self.hook_url = ""
        self.tail_stop = threading.Event()
        self.tail_wake = threading.Event()
        self._tail_thread = None
        self._tail_state = {}
        self._force_sync = False
        self._hook_waiters = {}
        self._waiter_lock = threading.Lock()
        self._startup_waiting = False
        self._owner_exited_notified = False

    def attach_hook_manager(self, manager):
        self.hook_manager = manager

    def ensure_owner(self):
        if self.owner is None:
            self.owner = NativeOwner(self)
        return self.owner

    def setup_hook_endpoint(self):
        owner = self.ensure_owner()
        token = owner.handle.get("hookToken")
        if not isinstance(token, str) or len(token) < 24:
            token = secrets.token_urlsafe(32)
            owner.handle["hookToken"] = token
            self.save()
        self.hook_token = token
        if self.hook_manager is not None:
            self.hook_url = self.hook_manager.register_hook(self.config["id"], token, self._dispatch_hook)
        else:
            self.hook_url = ""
        return self.hook_url, self.hook_token

    def _dispatch_hook(self, payload):
        self.touch()
        result = self.on_hook(payload if isinstance(payload, dict) else {})
        return result if isinstance(result, dict) else {}

    def on_hook(self, payload):
        return {}

    def is_terminal_active(self):
        if foreground_terminal_protects(self.config):
            return True
        if self.owner is not None and self.owner.has_attached_client():
            return self.owner.client_idle_seconds() < 15.0
        return False

    def owner_runtime_state_extra(self):
        return {}

    def sync_owner_runtime_state(self):
        owner = self.owner
        if owner is None:
            return
        with self._waiter_lock:
            pending = max(len(self._hook_waiters), len(self.pending_requests))
        extra = self.owner_runtime_state_extra()
        state = {
            "busy": bool(self.data.get("running")),
            "pendingRequests": pending,
            "waitingForStartup": bool(self._startup_waiting),
        }
        if isinstance(extra, dict):
            state.update(extra)
        owner.write_runtime_state(**state)

    def wait_hook_decision(
        self,
        req_id,
        raw,
        title,
        body,
        choices=None,
        questions=None,
        *,
        timeout=580,
        submit_choice=None,
        skip_choice=None,
        fallback_response=None,
    ):
        fallback = {} if fallback_response is None else fallback_response
        if self.is_terminal_active() or self.tail_stop.is_set() or self.close_pending or self.released:
            return fallback
        key = str(req_id)
        waiter = {"event": threading.Event(), "response": None, "resolved": False}
        with self._waiter_lock:
            self._hook_waiters[key] = waiter
        self.request(
            key,
            raw,
            title,
            body,
            choices=choices,
            questions=questions,
            submit_choice=submit_choice,
            skip_choice=skip_choice,
        )
        self.sync_owner_runtime_state()
        deadline = time.monotonic() + max(1, timeout)
        try:
            while time.monotonic() < deadline:
                if waiter["event"].wait(0.5):
                    break
                if (
                    self.tail_stop.is_set()
                    or self.close_pending
                    or self.released
                    or self.is_terminal_active()
                    or (self.owner is not None and not self.owner.alive())
                ):
                    break
            if waiter["resolved"] and waiter["response"] is not None:
                return waiter["response"]
            return fallback
        finally:
            with self._waiter_lock:
                if self._hook_waiters.get(key) is waiter:
                    self._hook_waiters.pop(key, None)
            self.answered(key)
            self.sync_owner_runtime_state()

    def resolve_hook_waiter(self, req_id, response):
        key = str(req_id)
        with self._waiter_lock:
            waiter = self._hook_waiters.get(key)
            if waiter is None:
                return False
            waiter["response"] = response
            waiter["resolved"] = True
            waiter["event"].set()
            return True

    def handoff_requests_to_tui(self):
        with self._waiter_lock:
            items = list(self._hook_waiters.items())
            self._hook_waiters.clear()
        for key, waiter in items:
            waiter["response"] = {}
            waiter["resolved"] = True
            waiter["event"].set()
            self.answered(key)
        self.sync_owner_runtime_state()

    def watched_transcript_paths(self):
        return []

    def sync_transcript(self):
        pass

    def should_poll_sync(self):
        return False

    def on_tail_tick(self):
        pass

    def wake_tailer(self, force=False):
        if force:
            self._force_sync = True
        self.tail_wake.set()

    def start_tailer(self):
        self.tail_stop.clear()
        self.tail_wake.clear()
        self._owner_exited_notified = False
        if self._tail_thread is not None and self._tail_thread.is_alive():
            return
        self._tail_thread = threading.Thread(target=self._tail_loop, daemon=True, name="LumaPtyTail")
        self._tail_thread.start()

    def _tail_loop(self):
        while not self.tail_stop.is_set():
            if self.close_pending or self.released:
                return
            if self.owner is not None and not self.owner.alive():
                self.on_owner_exited()
                return
            with self._waiter_lock:
                has_waiters = bool(self._hook_waiters)
            if has_waiters and self.is_terminal_active():
                self.handoff_requests_to_tui()
            changed = self._force_sync or self.should_poll_sync()
            self._force_sync = False
            for path in self.watched_transcript_paths():
                if not path:
                    continue
                try:
                    st = os.stat(path)
                    sig = (st.st_ino, st.st_mtime_ns, st.st_size)
                except OSError:
                    sig = None
                key = str(path)
                if self._tail_state.get(key) != sig:
                    self._tail_state[key] = sig
                    changed = True
            if changed:
                try:
                    self.sync_transcript()
                except Exception as exc:
                    self.diagnostic("pty-transcript-sync-failed", exc)
            try:
                self.on_tail_tick()
            except Exception as exc:
                self.diagnostic("pty-tail-tick-failed", exc)
            interval = 0.25 if (self.data.get("running") or self._startup_waiting) else 1.5
            self.tail_wake.wait(interval)
            self.tail_wake.clear()

    def on_owner_exited(self):
        with self.lock:
            if self._owner_exited_notified or self.close_pending or self.released:
                return
            self._owner_exited_notified = True
            self._startup_waiting = False
        self.handoff_requests_to_tui()
        try:
            self.sync_transcript()
        except Exception:
            pass
        self.update(
            connected=False,
            canOpenTui=False,
            running=False,
            requests=[],
            error=tr("原生 TUI 进程已退出，请重新连接恢复会话", "Native TUI process exited; reconnect to resume the session"),
        )

    def close_pty_observer(self):
        self.tail_stop.set()
        self.tail_wake.set()
        self.handoff_requests_to_tui()
        if self.hook_manager is not None and self.config.get("id"):
            self.hook_manager.unregister_hook(self.config["id"], self.hook_token or None)
        thread = self._tail_thread
        if thread is not None and thread is not threading.current_thread() and thread.is_alive():
            thread.join(timeout=2)
        self._tail_thread = None

    def terminal(self):
        if self.owner is None or not self.owner.alive():
            raise Rejected(tr("原生执行宿主已退出", "Native execution host has exited"))
        self.handoff_requests_to_tui()
        return self.owner.terminal()

    def release_mode(self):
        return "process" if self.data.get("nativeId") and self.data.get("historyReady") else None

    def fast_release_blocker(self):
        with self.lock:
            blocker = super().fast_release_blocker()
            if blocker is not None:
                return blocker
            if self._startup_waiting:
                return tr("原生终端正在等待启动确认", "Native terminal is awaiting startup confirmation")
            with self._waiter_lock:
                if self._hook_waiters:
                    return tr("原生请求等待处理", "Native request is awaiting user action")
            return None

    def release_blocker(self):
        blocker = super().release_blocker()
        if blocker is not None:
            return blocker
        owner = self.owner
        if owner is None:
            return None
        if owner.has_attached_client():
            return tr("会话终端仍处于附着状态", "Session terminal is still attached")
        pid = owner.pane_pid()
        if not pid:
            return None
        return self.cached_snapshot_probe(
            ("pty-owner-bg", pid),
            lambda: tr("后台子进程仍在运行", "Background subprocess is still running")
            if non_mcp_background_child_alive(pid)
            else None,
            pending_value=tr("正在确认后台子进程状态", "Checking background subprocess status"),
        )

    def destroy_resources(self):
        self.close_pty_observer()
        super().destroy_resources()

    def finish_observer_close(self):
        self.close_pty_observer()
        super().finish_observer_close()

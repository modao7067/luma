import json
import os
from pathlib import Path
import re
import select
import shlex
import shutil
import signal
import subprocess
import sys
import threading
import time
import uuid
from .base import item
from .claude import (
    ClaudeProjectionMixin,
    canonical_permission,
    permission_options,
)
from .i18n import tr
from .native_owner import non_mcp_background_child_alive
from .pty_tui import PtyOwnerPeer, PtyTuiSession, tmux_available
from .transport import Rejected

CLAUDE_PTY_PROTOCOL = "claude-tui-pty-v1"
CLAUDE_STREAM_PROTOCOL = "claude-stream-json"

_UUID_RE = re.compile(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
_CATALOG_LOCK = threading.Lock()
_CATALOG_CACHE = {}
_CATALOG_TTL_SEC = 300.0
_CATALOG_PROBE_TIMEOUT_SEC = 15.0
_TERMINAL_BG_STATUSES = {"completed", "failed", "stopped", "killed", "cancelled"}

_HOOK_RELAY_SCRIPT = """import json, os, sys, urllib.request
raw = sys.stdin.read()
start_file = os.environ.get("LUMA_START_FILE", "")
if start_file:
    tmp = start_file + ".tmp"
    try:
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            out.write(raw)
            out.flush()
            os.fsync(out.fileno())
        os.replace(tmp, start_file)
    except OSError:
        pass
url = os.environ.get("LUMA_HOOK_URL", "")
token = os.environ.get("LUMA_HOOK_TOKEN", "")
if url and raw.strip():
    req = urllib.request.Request(
        url,
        data=raw.encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=3) as resp:
            resp.read()
    except Exception:
        pass
"""

def probe_claude_pty(binary=None):
    binary = binary or shutil.which("claude")
    if not binary:
        return False, False, tr("未找到 claude", "claude binary not found"), None
    if not tmux_available():
        return True, False, tr(
            "当前运行环境缺少 tmux；已自动使用 stream-json 协议。",
            "tmux is unavailable in the current environment; automatically using stream-json.",
        ), binary
    try:
        result = subprocess.run(
            [binary, "--help"],
            capture_output=True,
            encoding="utf-8",
            errors="replace",
            timeout=10,
            check=False,
        )
        help_output = result.stdout if result.returncode == 0 else ""
    except (OSError, subprocess.TimeoutExpired):
        help_output = ""
    if not all(flag in help_output for flag in ("--settings", "--session-id", "--resume")):
        return True, False, tr(
            "当前 Claude Code 版本缺少双端 TUI 所需参数；已自动使用 stream-json 协议。",
            "Installed Claude Code lacks flags required for dual-end TUI; automatically using stream-json.",
        ), binary
    return True, True, "", binary

def _probe_claude_catalog(binary, cwd, force=False, timeout=None):
    key = (str(binary), os.path.realpath(cwd))
    now = time.monotonic()
    if not force:
        with _CATALOG_LOCK:
            cached = _CATALOG_CACHE.get(key)
            if cached is not None and now - cached["ts"] < _CATALOG_TTL_SEC:
                return cached["data"]
    limit = _CATALOG_PROBE_TIMEOUT_SEC if timeout is None else max(0.05, float(timeout))
    proc = None
    try:
        proc = subprocess.Popen(
            [
                binary,
                "--print",
                "--input-format",
                "stream-json",
                "--output-format",
                "stream-json",
                "--verbose",
                "--no-session-persistence",
            ],
            cwd=cwd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        init_req = json.dumps({
            "type": "control_request",
            "request_id": "cat-init",
            "request": {"subtype": "initialize", "hooks": None},
        }) + "\n"
        mcp_req = json.dumps({
            "type": "control_request",
            "request_id": "cat-mcp",
            "request": {"subtype": "mcp_status"},
        }) + "\n"
        proc.stdin.write((init_req + mcp_req).encode("utf-8"))
        proc.stdin.flush()
        info = {}
        mcp_info = {}
        fd = proc.stdout.fileno()
        buf = bytearray()
        deadline = time.monotonic() + limit
        while not info or not mcp_info:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            ready, _, _ = select.select([fd], [], [], remaining)
            if not ready:
                break
            chunk = os.read(fd, 65536)
            if not chunk:
                break
            buf.extend(chunk)
            while b"\n" in buf:
                line, _, rest = buf.partition(b"\n")
                buf = bytearray(rest)
                try:
                    frame = json.loads(line.decode("utf-8", "replace"))
                except ValueError:
                    continue
                if frame.get("type") != "control_response":
                    continue
                resp = frame.get("response", {})
                rid = resp.get("request_id")
                if rid == "cat-init" and resp.get("subtype") == "success":
                    info = resp.get("response") or {}
                elif rid == "cat-mcp" and resp.get("subtype") == "success":
                    mcp_info = resp.get("response") or {}
        if not info:
            return None
        data = {"init": info, "mcp": mcp_info}
        with _CATALOG_LOCK:
            _CATALOG_CACHE[key] = {"ts": time.monotonic(), "data": data}
        return data
    except (OSError, ValueError):
        return None
    finally:
        if proc is not None:
            for stream in (proc.stdin, proc.stdout):
                if stream is not None:
                    try:
                        stream.close()
                    except OSError:
                        pass
            if proc.poll() is None:
                try:
                    os.killpg(proc.pid, signal.SIGKILL)
                except OSError:
                    try:
                        proc.kill()
                    except OSError:
                        pass
                try:
                    proc.wait(timeout=2)
                except Exception:
                    pass

def _build_claude_hooks_settings(hook_url, relay_command):
    def http_hook(timeout):
        return {
            "type": "http",
            "url": hook_url,
            "headers": {"Authorization": "Bearer $LUMA_HOOK_TOKEN"},
            "allowedEnvVars": ["LUMA_HOOK_TOKEN"],
            "timeout": timeout,
        }
    return {
        "hooks": {
            "SessionStart": [{"hooks": [{"type": "command", "command": relay_command, "timeout": 10}]}],
            "UserPromptSubmit": [{"hooks": [http_hook(10)]}],
            "PreToolUse": [{"matcher": "*", "hooks": [http_hook(600)]}],
            "PermissionRequest": [{"matcher": "*", "hooks": [http_hook(600)]}],
            "PostToolUse": [{"matcher": "*", "hooks": [http_hook(10)]}],
            "PostToolUseFailure": [{"matcher": "*", "hooks": [http_hook(10)]}],
            "SubagentStart": [{"hooks": [http_hook(10)]}],
            "SubagentStop": [{"hooks": [http_hook(10)]}],
            "Stop": [{"hooks": [http_hook(10)]}],
            "StopFailure": [{"hooks": [http_hook(10)]}],
            "Notification": [{"hooks": [http_hook(10)]}],
            "SessionEnd": [{"hooks": [http_hook(5)]}],
        }
    }

def _pane_looks_like_startup_gate(text_snapshot):
    low = (text_snapshot or "").lower()
    markers = (
        "trust this folder",
        "do you trust the files",
        "yes, proceed",
        "choose the text style",
        "dark mode",
        "select login method",
        "sign in",
        "anthropic api key",
    )
    return any(marker in low for marker in markers)

def _transcript_entry_text(entry):
    msg = entry.get("message")
    if not isinstance(msg, dict):
        return ""
    body = msg.get("content")
    if isinstance(body, str):
        return body.strip()
    if isinstance(body, list):
        parts = [
            block["text"]
            for block in body
            if isinstance(block, dict) and block.get("type") == "text" and isinstance(block.get("text"), str)
        ]
        return "\n".join(parts).strip()
    return ""

def _interrupt_marker_count(text_snapshot):
    low = (text_snapshot or "").lower()
    return (
        low.count("what should claude do instead")
        + low.count("interrupted ·")
        + low.count("[request interrupted by user")
    )

def _pane_shows_new_interrupt(pane, baseline):
    return _interrupt_marker_count(pane) > _interrupt_marker_count(baseline)

def _parse_pane_permission_mode(pane_text):
    if not pane_text:
        return None
    lines = [line.strip() for line in pane_text.splitlines() if line.strip()]
    if not lines:
        return None
    divider_idx = -1
    for idx, line in enumerate(lines):
        if len(line) >= 8 and set(line) <= {"─", "━", "-", "="}:
            divider_idx = idx
    footer = lines[divider_idx + 1 :] if divider_idx >= 0 else lines[-5:]
    joined = "\n".join(footer).lower()
    if "bypass permissions on" in joined:
        return "bypassPermissions"
    if "accept edits on" in joined:
        return "acceptEdits"
    if "plan mode on" in joined:
        return "plan"
    if "auto mode on" in joined:
        return "auto"
    if "don't ask on" in joined or "dont ask on" in joined:
        return "dontAsk"
    if "manual mode on" in joined:
        return "default"
    return None

def _extract_tui_confirm_dialog(pane_text):
    if not pane_text:
        return None, False
    raw_lines = pane_text.splitlines()
    divider_idx = -1
    for idx, line in enumerate(raw_lines):
        stripped = line.strip()
        if len(stripped) >= 8 and set(stripped) <= {"─", "━", "-", "="}:
            divider_idx = idx
    if divider_idx < 0:
        return None, False
    below = [line.strip() for line in raw_lines[divider_idx + 1 :]]
    non_empty = [(i, l) for i, l in enumerate(below) if l]
    if not non_empty:
        return None, False
    hint_markers = ("enter to continue", "enter to confirm", "enter to accept", "press enter")
    hint_pos = None
    hint_line = ""
    for i, l in reversed(non_empty[-2:]):
        low = l.lower()
        if any(m in low for m in hint_markers):
            hint_pos = i
            hint_line = l
            break
    if hint_pos is None:
        return None, False
    body_lines = [l for l in below[:hint_pos]]
    while body_lines and not body_lines[0]:
        body_lines.pop(0)
    while body_lines and not body_lines[-1]:
        body_lines.pop()
    body = "\n".join(body_lines).strip()
    if not body:
        return None, False
    has_cancel = "esc" in hint_line.lower()
    return body, has_cancel

_PTY_PERMISSION_ORDER = ("default", "acceptEdits", "plan", "auto", "bypassPermissions")

def _pane_prompt_draft(pane_text):
    if not pane_text:
        return ""
    lines = pane_text.splitlines()
    for line in reversed(lines[-10:]):
        cleaned = line.replace("\xa0", " ").strip()
        if cleaned.startswith("❯"):
            return cleaned[1:].strip()
    return ""

class ClaudePtySession(ClaudeProjectionMixin, PtyTuiSession):
    pty_binary_name = "claude"
    sync_permission_from_history = False
    startup_wait_sec = 8.0
    interrupt_settle_grace_sec = 0.4
    interrupt_settle_wait_sec = 1.5
    permission_cycle_step_timeout_sec = 0.7
    prompt_confirm_wait_sec = 1.2

    def __init__(self, config, save):
        super().__init__(config, save)
        self.permission_modes, self.allow_bypass = permission_options("", False)
        self.native_models = []
        self.commands = set()
        self.usage_message = {}
        self.usage_models = {}
        self.usage_rows = []
        self.submitted_user_text = {}
        self.background_tasks = {}
        self.session_crons = []
        self._stop_background_tasks = []
        self.current_submission = None
        self.result_usage_authoritative = False
        self._transcript_path = ""
        self._binary = ""
        self._session_model = ""
        self._session_started = threading.Event()
        self._pending_submission = None
        self._awaiting_transcript_user = []
        self._handled_question_tools = set()
        self._pending_agent_tools = []
        self._agent_id_to_tool = {}
        self._subagent_hook_tools = {}
        self._subagent_transcript_paths = {}
        self._subagent_jsonl_cache = {}
        self._identity_mismatched = False
        self._seen_turn_end_markers = set()
        self._turn_seq = 0
        self._interrupt_requested_at = None
        self._interrupt_turn_seq = None
        self._interrupt_pane_baseline = ""
        self._catalog_refresh_inflight = False
        self._catalog_refresh_pending_force = False
        self._last_pane_perm_sync = 0.0
        self._pane_perm_dirty = True
        self._last_tui_prompt_scan = 0.0
        self._tui_prompt_requests = {}

    def detect_permissions(self):
        super().detect_permissions()
        by_id = {m["id"]: m for m in self.permission_modes}
        self.permission_modes = [
            by_id[mid]
            for mid in _PTY_PERMISSION_ORDER
            if mid in by_id and (mid != "bypassPermissions" or self.allow_bypass)
        ]

    def _clear_tui_prompts(self):
        with self.lock:
            ids = list(self._tui_prompt_requests.keys())
            self._tui_prompt_requests.clear()
        if not ids:
            return
        for req_id in ids:
            self.answered(req_id)
        self.sync_owner_runtime_state()

    def handoff_requests_to_tui(self):
        self._clear_tui_prompts()
        super().handoff_requests_to_tui()

    def _open_tui_prompt_request(self, title, body, *, notif_type="", message="", has_cancel=True):
        if self.is_terminal_active() or self.tail_stop.is_set() or self.close_pending or self.released:
            return False
        with self._waiter_lock:
            if self._hook_waiters:
                return False
        clean_title = (title or "").strip() or tr("Claude 终端等待确认", "Claude terminal awaits confirmation")
        clean_body = (body or "").strip() or (message or "").strip() or clean_title
        sig = (notif_type, message.strip(), clean_body)
        with self.lock:
            for existing in self._tui_prompt_requests.values():
                if existing.get("sig") == sig:
                    return True
            stale = list(self._tui_prompt_requests.keys())
            self._tui_prompt_requests.clear()
        for req_id in stale:
            self.answered(req_id)
        req_id = f"pty-tui-{uuid.uuid4().hex[:12]}"
        raw = {
            "tuiPrompt": True,
            "notificationType": notif_type,
            "message": message.strip(),
            "body": clean_body,
            "sig": sig,
            "openedAt": time.monotonic(),
        }
        choices = [{"id": "allow", "label": tr("继续 (Enter)", "Continue (Enter)")}]
        if has_cancel:
            choices.append({"id": "deny", "label": tr("取消 (Esc)", "Cancel (Esc)")})
        with self.lock:
            self._tui_prompt_requests[req_id] = raw
        self.request(req_id, raw, clean_title, clean_body, choices=choices)
        self.sync_owner_runtime_state()
        return True

    def _sync_tui_confirm_prompts(self, *, force=False):
        owner = self.owner
        if owner is None or self._startup_waiting or self._identity_mismatched or not owner.alive():
            return
        now = time.monotonic()
        if not force and now - self._last_tui_prompt_scan < 0.8:
            return
        self._last_tui_prompt_scan = now
        with self.lock:
            active_prompts = list(self._tui_prompt_requests.values())
        if active_prompts and self.is_terminal_active():
            self._clear_tui_prompts()
            return
        body, has_cancel = _extract_tui_confirm_dialog(owner.capture_pane(30))
        if active_prompts:
            if body is None:
                newest = max((p.get("openedAt", 0.0) for p in active_prompts), default=0.0)
                if now - newest >= 1.5:
                    self._clear_tui_prompts()
            return
        if body and self.data.get("running"):
            self._open_tui_prompt_request(
                tr("Claude 终端等待确认", "Claude terminal awaits confirmation"),
                body,
                notif_type="pane_confirm",
                message=body.splitlines()[0][:120],
                has_cancel=has_cancel,
            )

    def _sync_pane_permission(self, *, force=False):
        owner = self.owner
        if owner is None or self._startup_waiting or self._identity_mismatched:
            return None
        if not force and not self._pane_perm_dirty:
            return None
        now = time.monotonic()
        if not force and now - self._last_pane_perm_sync < 1.2:
            return None
        self._last_pane_perm_sync = now
        self._pane_perm_dirty = False
        if not owner.alive():
            return None
        mode = _parse_pane_permission_mode(owner.capture_pane(12))
        if mode and mode != self.data.get("permission"):
            self.update(permission=mode)
        return mode

    def on_tail_tick(self):
        if not self.data.get("connected") or self._startup_waiting:
            return
        if self.data.get("running") or self._tui_prompt_requests:
            self._pane_perm_dirty = True
            self._sync_tui_confirm_prompts(force=False)
        if not self.data.get("running"):
            self._sync_pane_permission(force=False)

    def owner_runtime_state_extra(self):
        with self.lock:
            return {
                "sessionCrons": len(self.session_crons),
                "backgroundTasks": len(self.background_tasks) + len(self._stop_background_tasks),
            }

    def should_poll_sync(self):
        with self.lock:
            return bool(self._interrupt_requested_at is not None and self.data.get("running"))

    def fast_release_blocker(self):
        with self.lock:
            blocker = super().fast_release_blocker()
            if blocker in ("工具状态尚未确认结束", "Tool status has not been confirmed finished") and (
                self.background_tasks or self._stop_background_tasks
            ):
                return tr("后台任务尚未确认结束", "Background task has not been confirmed finished")
            if blocker is not None:
                return blocker
            if self.session_crons:
                return tr("会话定时唤醒任务仍在运行", "Session scheduled wakeup task is still active")
            if self.background_tasks or self._stop_background_tasks:
                return tr("后台任务尚未确认结束", "Background task has not been confirmed finished")
            return None

    def _sync_stop_keepalive(self, payload):
        if not isinstance(payload, dict):
            return
        with self.lock:
            if "session_crons" in payload and isinstance(payload["session_crons"], list):
                self.session_crons = [entry for entry in payload["session_crons"] if entry]
            if "background_tasks" in payload and isinstance(payload["background_tasks"], list):
                self._stop_background_tasks = [
                    entry
                    for entry in payload["background_tasks"]
                    if isinstance(entry, dict) and entry.get("status", "running") not in _TERMINAL_BG_STATUSES
                ]

    def _scan_transcript_turn_end(self, *, record_only=False):
        path = self.history_path()
        if not path or not path.is_file():
            return None
        try:
            raw_lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            return None
        native_id = self.data.get("nativeId")
        unseen_interrupt = False
        unseen_turn_end = False
        for idx, line in enumerate(raw_lines):
            if not line.strip():
                continue
            try:
                entry = json.loads(line)
            except (ValueError, TypeError):
                continue
            if not isinstance(entry, dict):
                continue
            if entry.get("isSidechain"):
                continue
            if entry.get("sessionId") not in (None, native_id):
                continue
            entry_type = entry.get("type")
            is_interrupt = entry_type == "user" and _transcript_entry_text(entry).startswith("[Request interrupted by user")
            is_turn_duration = entry_type == "system" and entry.get("subtype") == "turn_duration"
            if not is_interrupt and not is_turn_duration:
                continue
            marker = entry.get("uuid") or f"line:{idx}:{entry_type}"
            if marker in self._seen_turn_end_markers:
                continue
            self._seen_turn_end_markers.add(marker)
            if not record_only:
                if is_interrupt:
                    unseen_interrupt = True
                elif is_turn_duration:
                    unseen_turn_end = True
        if record_only:
            return None
        if unseen_interrupt:
            return "cancelled"
        if unseen_turn_end:
            return "unknown"
        return None

    def _settle_unconfirmed_submissions(self):
        with self.lock:
            deliveries = self.config.get("deliveries", {})
            receipts = self.config.get("receipts", {})
            consumed = self.config.get("consumedSubmissions", {})
            to_reject = []
            cancelled_any = False
            for sub_id, entry in list(deliveries.items()):
                state = entry.get("state")
                if state == "queued":
                    entry["state"] = "cancelled"
                    cancelled_any = True
                elif state in ("dispatching", "unknown") and not consumed.get(sub_id):
                    to_reject.append(sub_id)
            for sub_id, r_state in list(receipts.items()):
                if r_state in ("unknown", "pending", "dispatching", "queued") and not consumed.get(sub_id):
                    if sub_id not in to_reject:
                        to_reject.append(sub_id)
            if cancelled_any:
                try:
                    self._save_delivery_state("cancellation")
                except Exception:
                    self.save()
                self.seq += 1
        for sub_id in to_reject:
            try:
                self.receipt(sub_id, "rejected")
            except Exception:
                pass

    def _settle_turn(self, *, error=None, row_status="unknown", keep_error=False, settle_unconfirmed=False):
        with self.lock:
            self._pending_submission = None
            self.current_submission = None
            self._interrupt_requested_at = None
            self._interrupt_turn_seq = None
            self._interrupt_pane_baseline = ""
        self.handoff_requests_to_tui()
        if settle_unconfirmed:
            self._settle_unconfirmed_submissions()
        updates = {"running": False}
        if error is not None:
            updates["error"] = error
        elif not keep_error:
            updates["error"] = None
        self.update(**updates)
        self.settle_incomplete_rows(row_status)
        for tool_key in self.background_tasks.values():
            if tool_key and tool_key in self.rows and self.rows[tool_key].get("status") in ("unknown", "cancelled"):
                self.row({**self.rows[tool_key], "status": "running"})
        self.sync_owner_runtime_state()
        self.wake_tailer(force=True)

    def _try_settle_interrupted_turn(self):
        with self.lock:
            if not self.data.get("running") and self.current_submission is None and self._pending_submission is None:
                self._interrupt_requested_at = None
                self._interrupt_turn_seq = None
                self._interrupt_pane_baseline = ""
                return True
            req_at = self._interrupt_requested_at
            req_seq = self._interrupt_turn_seq
            cur_seq = self._turn_seq
            baseline = self._interrupt_pane_baseline
        settled = self._scan_transcript_turn_end(record_only=False)
        if settled is not None:
            self._settle_turn(row_status=settled, settle_unconfirmed=(settled == "cancelled"))
            return True
        if req_at is None or req_seq != cur_seq:
            return False
        owner = self.owner
        if owner is None or not owner.alive():
            self._settle_turn(row_status="cancelled", settle_unconfirmed=True)
            return True
        pane = owner.capture_pane(20)
        if _pane_shows_new_interrupt(pane, baseline):
            self._settle_turn(row_status="cancelled", settle_unconfirmed=True)
            return True
        if time.monotonic() - req_at >= self.interrupt_settle_grace_sec:
            pane_low = (pane or "").lower()
            pid = owner.pane_pid()
            child_busy = bool(pid and non_mcp_background_child_alive(pid))
            if "esc to interrupt" not in pane_low and not child_busy:
                self._settle_turn(row_status="cancelled", settle_unconfirmed=True)
                return True
        return False

    def history_path(self):
        if self._transcript_path:
            return Path(self._transcript_path)
        return super().history_path()

    def watched_transcript_paths(self):
        paths = [self.history_path()]
        if self.owner is not None:
            paths.append(self.owner.directory / "session-start.json")
        with self.lock:
            sub_ids = [aid for aid, a in self.subagents.items() if a.get("status") not in ("succeeded", "completed", "failed", "cancelled")]
        for aid in sub_ids:
            sub_path = self._subagent_transcript_path(aid)
            if sub_path is not None:
                paths.append(sub_path)
        return paths

    def _mapped_user_row_prefix(self, entry, body_text):
        uid = entry.get("uuid")
        if not uid:
            return None
        mapping = self.config.setdefault("ptyUserMessages", {})
        if uid in mapping:
            return mapping[uid]
        if self._awaiting_transcript_user:
            target = (body_text or "").strip()
            for idx, (sub_id, expected) in enumerate(self._awaiting_transcript_user):
                if expected.strip() == target and sub_id not in mapping.values():
                    mapping[uid] = sub_id
                    self._awaiting_transcript_user.pop(idx)
                    self.save()
                    return sub_id
        return None

    def _apply_session_start_payload(self, payload):
        if not isinstance(payload, dict):
            return False
        sid = payload.get("session_id")
        if isinstance(sid, str) and sid and self.data.get("nativeId") and sid != self.data["nativeId"]:
            self._handle_identity_mismatch()
            return False
        tpath = payload.get("transcript_path")
        if isinstance(tpath, str) and tpath:
            self._transcript_path = tpath
        model = payload.get("model")
        updates = {}
        if isinstance(model, str) and model:
            self._session_model = model
            resolved = self.model_id(model)
            updates["model"] = resolved
            if not self.data.get("models"):
                updates["models"] = [{"id": resolved, "label": resolved, "supportsImages": False, "efforts": []}]
        perm = payload.get("permission_mode")
        if isinstance(perm, str) and perm:
            updates["permission"] = canonical_permission(perm)
        was_waiting = self._startup_waiting
        self._startup_waiting = False
        if updates:
            self.update(**updates)
            self.publish_usage()
        self._session_started.set()
        if was_waiting and not self._identity_mismatched:
            self.update(
                connected=True,
                canOpenTui=True,
                historyReady=True,
                error=None,
                nativeProtocol=CLAUDE_PTY_PROTOCOL,
            )
            self.sync_owner_runtime_state()
            self.start_delivery_worker()
            self._refresh_catalog_async()
        self.wake_tailer(force=True)
        return True

    def _read_start_file(self):
        if self.owner is None:
            return None
        start_file = self.owner.directory / "session-start.json"
        if not start_file.is_file():
            return None
        try:
            return json.loads(start_file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None

    def _handle_identity_mismatch(self):
        self._identity_mismatched = True
        self._startup_waiting = False
        self.handoff_requests_to_tui()
        self.update(
            connected=False,
            canOpenTui=False,
            running=False,
            requests=[],
            error=tr(
                "原生前台会话已改变；当前 GUI 仍保留原会话身份",
                "Native foreground session changed; GUI retains the original session identity",
            ),
        )
        self.sync_owner_runtime_state()

    def connect(self):
        self.usage_message = {}
        self.usage_models = {}
        self.usage_rows = []
        self.current_submission = None
        self.submitted_user_text = {}
        self.background_tasks = {}
        self.session_crons = []
        self._stop_background_tasks = []
        self._pending_submission = None
        self._awaiting_transcript_user = []
        self._handled_question_tools = set()
        self._pending_agent_tools = []
        self._agent_id_to_tool = {}
        self._subagent_hook_tools = {}
        self._subagent_transcript_paths = {}
        self._subagent_jsonl_cache = {}
        self._session_model = ""
        self._identity_mismatched = False
        self._seen_turn_end_markers = set()
        self._turn_seq = 0
        self._interrupt_requested_at = None
        self._interrupt_turn_seq = None
        self._interrupt_pane_baseline = ""
        self._last_pane_perm_sync = 0.0
        self._pane_perm_dirty = True
        self._last_tui_prompt_scan = 0.0
        self._tui_prompt_requests = {}
        self._session_started.clear()

        found, compatible, reason, binary = probe_claude_pty()
        if not found or not compatible:
            raise Rejected(reason)
        self._binary = binary

        native = self.config.get("nativeId")
        if native and not _UUID_RE.fullmatch(native):
            raise Rejected(tr("Claude Code 会话 ID 必须是原生 UUID", "Claude Code session ID must be a native UUID"))
        if not native:
            native = str(uuid.uuid4())
            self.update(nativeId=native)

        path = self.history_path()
        if not path.exists() and self.config.get("hasNativeHistory"):
            raise Rejected(tr(
                "Claude Code 原生会话文件缺失，无法恢复；请在终端核对，不能自动建立替代会话",
                "Claude Code native session file is missing and cannot be resumed; check in terminal instead of creating a replacement session",
            ))

        self.detect_permissions()
        selected_permission = self.config.get("selectedPermission")
        if selected_permission:
            self.validate_permission(selected_permission)
        self.load_history()

        owner = self.ensure_owner()
        hook_url, hook_token = self.setup_hook_endpoint()
        start_file = owner.directory / "session-start.json"
        launched = False
        if not owner.handle.get("kind"):
            start_file.unlink(missing_ok=True)
            relay_path = owner.write_private_file("hook-relay.py", _HOOK_RELAY_SCRIPT, mode=0o700)
            relay_cmd = shlex.join([sys.executable, "-I", str(relay_path)])
            settings_payload = _build_claude_hooks_settings(hook_url, relay_cmd)
            settings_path = owner.write_private_file(
                "claude-settings.json",
                json.dumps(settings_payload, ensure_ascii=False),
                mode=0o600,
            )
            argv = [binary, "--settings", str(settings_path)]
            if self.allow_bypass:
                argv += ["--allow-dangerously-skip-permissions"]
            argv += ["--resume" if path.exists() else "--session-id", native]
            for key, flag in (
                ("selectedModel", "--model"),
                ("selectedEffort", "--effort"),
                ("selectedPermission", "--permission-mode"),
            ):
                if self.config.get(key):
                    argv += [flag, self.config[key]]
            env = {
                "TERM": "xterm-256color",
                "LUMA_HOOK_URL": hook_url,
                "LUMA_HOOK_TOKEN": hook_token,
                "LUMA_START_FILE": str(start_file),
            }
            if self.config.get("browserConfig"):
                env["LUMA_BROWSER_CONFIG"] = self.config["browserConfig"]
            launched = bool(owner.launch_tui(argv, env, host_kind="pty-tui", binary_name="claude"))

        def cleanup_launched():
            if launched:
                try:
                    owner.stop(force=True)
                    self.save()
                except Exception:
                    pass

        self.peer = PtyOwnerPeer(owner, self.close_pty_observer)
        self.update(
            nativeProtocol=CLAUDE_PTY_PROTOCOL,
            canOpenTui=True,
            ownerInstanceId=owner.handle["instanceId"],
            permissions=self.permission_modes,
            capabilities={
                "queue": False,
                "steer": False,
                "stop": True,
                "images": False,
                "skills": bool(self.data["skills"]),
                "maxSkills": 1,
                "compact": True,
            },
        )
        self.start_tailer()

        existing_start = self._read_start_file()
        if existing_start:
            self._apply_session_start_payload(existing_start)
        if not self._session_started.is_set():
            deadline = time.monotonic() + self.startup_wait_sec
            while time.monotonic() < deadline and not self._session_started.is_set():
                if not owner.alive():
                    cleanup_launched()
                    raise Rejected(tr("Claude Code TUI 启动失败或已退出", "Claude Code TUI failed to start or exited"))
                disk_start = self._read_start_file()
                if disk_start:
                    self._apply_session_start_payload(disk_start)
                    break
                self._session_started.wait(0.2)

        if self._identity_mismatched:
            cleanup_launched()
            raise Rejected(self.data.get("error") or tr("原生前台会话已改变", "Native foreground session changed"))

        if not self._session_started.is_set():
            if not owner.alive():
                cleanup_launched()
                raise Rejected(tr("Claude Code TUI 启动失败或已退出", "Claude Code TUI failed to start or exited"))
            pane = owner.capture_pane(40)
            self._startup_waiting = True
            self.sync_owner_runtime_state()
            msg = (
                tr(
                    "Claude Code 正在终端等待启动确认（目录信任或登录）；请点开 Dashboard → 终端完成后点击刷新",
                    "Claude Code is awaiting startup confirmation (workspace trust or sign-in) in terminal; open Dashboard -> Terminal to complete it, then tap Reconnect",
                )
                if _pane_looks_like_startup_gate(pane)
                else tr(
                    "Claude Code TUI 尚未完成启动；请点开 Dashboard → 终端查看状态",
                    "Claude Code TUI has not finished starting yet; open Dashboard -> Terminal to inspect",
                )
            )
            self.update(connected=False, canOpenTui=True, error=msg)
            return

        if selected_permission and not self.data.get("permission"):
            self.update(permission=selected_permission)
        if self.config.get("selectedEffort") and not self.data.get("effort"):
            self.update(effort=self.config["selectedEffort"])
        self.update(
            connected=True,
            canOpenTui=True,
            historyReady=True,
            nativeProtocol=CLAUDE_PTY_PROTOCOL,
            ownerInstanceId=owner.handle["instanceId"],
            error=None,
        )
        self.sync_owner_runtime_state()
        self.load_history()
        self._sync_pane_permission(force=True)
        self._scan_transcript_turn_end(record_only=True)
        self._refresh_catalog_async()

    def _refresh_catalog_async(self, force=False):
        binary = self._binary or shutil.which("claude")
        if not binary:
            return
        with self.lock:
            if self._catalog_refresh_inflight:
                if force:
                    self._catalog_refresh_pending_force = True
                return
            self._catalog_refresh_inflight = True
            self._catalog_refresh_pending_force = False
        cwd = self.config["cwd"]
        def run():
            try:
                catalog = _probe_claude_catalog(binary, cwd, force=force)
                if not catalog or self.tail_stop.is_set() or self.close_pending or self.released:
                    return
                info = catalog.get("init") or {}
                mcp_info = catalog.get("mcp") or {}
                self.commands = {c["name"] for c in info.get("commands", []) if isinstance(c, dict) and c.get("name")}
                self.native_models = info.get("models", []) if isinstance(info.get("models"), list) else []
                models = [
                    {
                        "id": m["value"],
                        "label": m.get("displayName", m["value"]),
                        "supportsImages": False,
                        "efforts": list(dict.fromkeys(e for e in m.get("supportedEffortLevels", []) if isinstance(e, str)))
                        if m.get("supportsEffort") is not False
                        else [],
                    }
                    for m in self.native_models
                    if isinstance(m, dict) and m.get("value")
                ]
                skills = [
                    {"id": c["name"], "name": c["name"], "path": ""}
                    for c in info.get("commands", [])
                    if isinstance(c, dict) and isinstance(c.get("name"), str) and c.get("name")
                ]
                updates = {}
                if models:
                    updates["models"] = models
                    if self.data.get("model"):
                        updates["model"] = self.model_id(self.data["model"])
                if not self.data.get("permission") and info.get("current_permission_mode"):
                    updates["permission"] = canonical_permission(info["current_permission_mode"])
                if skills:
                    updates["skills"] = skills
                    updates["capabilities"] = {
                        **self.data["capabilities"],
                        "skills": True,
                        "maxSkills": 1,
                        "compact": "compact" in self.commands or True,
                    }
                servers = mcp_info.get("mcpServers")
                if isinstance(servers, list):
                    updates["mcpAvailable"] = True
                    updates["mcp"] = [
                        {"name": m["name"], "detail": m.get("status", "")}
                        for m in servers
                        if isinstance(m, dict) and m.get("name")
                    ]
                if updates:
                    self.update(**updates)
            finally:
                retrigger = False
                with self.lock:
                    self._catalog_refresh_inflight = False
                    if self._catalog_refresh_pending_force and not (self.tail_stop.is_set() or self.close_pending or self.released):
                        self._catalog_refresh_pending_force = False
                        retrigger = True
                if retrigger:
                    self._refresh_catalog_async(force=True)
        threading.Thread(target=run, daemon=True, name="LumaClaudeCatalog").start()

    def sync_transcript(self):
        if self._identity_mismatched:
            return
        if self._startup_waiting:
            disk_start = self._read_start_file()
            if disk_start:
                self._apply_session_start_payload(disk_start)
        seq_before = self.seq
        self.load_history()
        if self.seq != seq_before:
            self._pane_perm_dirty = True
        if self.data.get("running"):
            self._pane_perm_dirty = True
            self._try_settle_interrupted_turn()
        else:
            self._scan_transcript_turn_end(record_only=True)
            self._sync_pane_permission(force=False)

    def _claim_agent_tool_meta(self, agent_id, agent_type, prompt_id):
        with self.lock:
            linked = self._agent_id_to_tool.pop(agent_id, None)
            if linked is not None:
                return linked
            if not self._pending_agent_tools:
                return None
            for idx, meta in enumerate(self._pending_agent_tools):
                if prompt_id and meta.get("promptId") == prompt_id and (not agent_type or not meta.get("role") or meta.get("role") == agent_type):
                    return self._pending_agent_tools.pop(idx)
            for idx, meta in enumerate(self._pending_agent_tools):
                if prompt_id and meta.get("promptId") == prompt_id:
                    return self._pending_agent_tools.pop(idx)
            return self._pending_agent_tools.pop(0)

    def on_hook(self, payload):
        if self._identity_mismatched or self.close_pending or self.released:
            return {}
        sid = payload.get("session_id")
        if isinstance(sid, str) and sid and self.data.get("nativeId") and sid != self.data["nativeId"]:
            self._handle_identity_mismatch()
            return {}
        tpath = payload.get("transcript_path")
        if isinstance(tpath, str) and tpath:
            self._transcript_path = tpath
        perm = payload.get("permission_mode")
        if isinstance(perm, str) and perm:
            self.update(permission=canonical_permission(perm))
        effort_obj = payload.get("effort")
        if isinstance(effort_obj, dict) and isinstance(effort_obj.get("level"), str) and effort_obj["level"]:
            self.update(effort=effort_obj["level"])

        event_name = payload.get("hook_event_name", "")
        if event_name == "SessionStart":
            self._apply_session_start_payload(payload)
            return {}

        if event_name == "UserPromptSubmit":
            prompt_text = payload.get("prompt") if isinstance(payload.get("prompt"), str) else ""
            self._scan_transcript_turn_end(record_only=True)
            with self.lock:
                self._turn_seq += 1
                self._interrupt_requested_at = None
                self._interrupt_turn_seq = None
                self._interrupt_pane_baseline = ""
                pending = self._pending_submission
                if pending and prompt_text.strip() == pending["text"].strip():
                    sub_id = pending["id"]
                    self._pending_submission = None
                    self._awaiting_transcript_user.append((sub_id, prompt_text))
                    self.row(item(f"{sub_id}:0", "user", prompt_text, status="completed", origin="native"))
                    self.consumed(sub_id)
                    self.receipt(sub_id, "accepted")
            self.update(running=True, error=None, permissionNotice="")
            self.sync_owner_runtime_state()
            self.wake_tailer(force=True)
            return {}

        if event_name == "PreToolUse":
            tool_name = payload.get("tool_name") or ""
            tool_input = payload.get("tool_input") if isinstance(payload.get("tool_input"), dict) else {}
            tool_use_id = payload.get("tool_use_id") or ""
            sub_agent_id = payload.get("agent_id") if isinstance(payload.get("agent_id"), str) and payload.get("agent_id") else ""
            if sub_agent_id:
                if tool_use_id and sub_agent_id in self.subagents and tool_name != "SubagentHandback":
                    tkey = "tool-" + tool_use_id
                    with self.lock:
                        bucket = self._subagent_hook_tools.setdefault(sub_agent_id, {})
                        old_t = bucket.get(tkey, {})
                        bucket[tkey] = item(
                            tkey,
                            "tool",
                            tool_name or tr("工具", "Tool"),
                            kind="tool",
                            toolName=tool_name,
                            input=json.dumps(tool_input, ensure_ascii=False),
                            status="running",
                            outcome=old_t.get("outcome", ""),
                            timestampMs=old_t.get("timestampMs") or int(time.time() * 1000),
                        )
                    self._sync_subagent_transcripts()
            elif tool_use_id:
                key = "tool-" + tool_use_id
                old = self.rows.get(key, {})
                self.row(item(
                    key,
                    "tool",
                    tool_name or tr("工具", "Tool"),
                    kind="tool",
                    toolName=tool_name,
                    input=json.dumps(tool_input, ensure_ascii=False),
                    status="running",
                    outcome=old.get("outcome", ""),
                    media=old.get("media", []),
                ))
                if tool_name in ("Agent", "Task"):
                    with self.lock:
                        self._pending_agent_tools.append({
                            "toolUseId": tool_use_id,
                            "promptId": payload.get("prompt_id") or "",
                            "name": tool_input.get("name", "") if isinstance(tool_input.get("name"), str) else "",
                            "role": tool_input.get("subagent_type", "") if isinstance(tool_input.get("subagent_type"), str) else "",
                            "task": (
                                tool_input.get("description")
                                if isinstance(tool_input.get("description"), str) and tool_input.get("description")
                                else (tool_input.get("prompt", "") if isinstance(tool_input.get("prompt"), str) else "")
                            ),
                        })
            if not sub_agent_id:
                self.update(running=True)
                self.sync_owner_runtime_state()
            self.wake_tailer(force=True)
            if tool_name == "AskUserQuestion":
                req_id = f"pty-q-{tool_use_id or uuid.uuid4().hex}"
                title, body, choices, questions, submit_choice, skip_choice = self._permission_request_spec(
                    "AskUserQuestion", tool_input,
                )
                raw = {"hookEvent": "PreToolUse", "toolName": "AskUserQuestion", "input": tool_input, "toolUseId": tool_use_id}
                if tool_use_id:
                    self._handled_question_tools.add(tool_use_id)
                return self.wait_hook_decision(
                    req_id,
                    raw,
                    title,
                    body,
                    choices=choices,
                    questions=questions,
                    timeout=580,
                    submit_choice=submit_choice,
                    skip_choice=skip_choice,
                )
            return {}

        if event_name == "PermissionRequest":
            tool_name = payload.get("tool_name") or ""
            tool_input = payload.get("tool_input") if isinstance(payload.get("tool_input"), dict) else {}
            tool_use_id = payload.get("tool_use_id") or ""
            if tool_name == "AskUserQuestion" and tool_use_id and tool_use_id in self._handled_question_tools:
                return {}
            req_id = f"pty-perm-{tool_use_id or uuid.uuid4().hex}"
            title, body, choices, questions, submit_choice, skip_choice = self._permission_request_spec(
                tool_name, tool_input,
            )
            raw = {"hookEvent": "PermissionRequest", "toolName": tool_name, "input": tool_input, "toolUseId": tool_use_id}
            return self.wait_hook_decision(
                req_id,
                raw,
                title,
                body,
                choices=choices,
                questions=questions,
                timeout=580,
                submit_choice=submit_choice,
                skip_choice=skip_choice,
            )

        if event_name in ("PostToolUse", "PostToolUseFailure"):
            tool_use_id = payload.get("tool_use_id") or ""
            tool_name = payload.get("tool_name") or ""
            sub_agent_id = payload.get("agent_id") if isinstance(payload.get("agent_id"), str) and payload.get("agent_id") else ""
            is_interrupt = bool(payload.get("is_interrupt")) if event_name == "PostToolUseFailure" else False
            if sub_agent_id:
                if tool_use_id and sub_agent_id in self.subagents:
                    tkey = "tool-" + tool_use_id
                    next_status = "cancelled" if is_interrupt else ("failed" if event_name == "PostToolUseFailure" else "completed")
                    resp = payload.get("tool_response")
                    out_txt = ""
                    if isinstance(resp, dict):
                        out_txt = resp.get("stdout") or resp.get("output") or resp.get("text") or ""
                    elif isinstance(resp, str):
                        out_txt = resp
                    with self.lock:
                        bucket = self._subagent_hook_tools.get(sub_agent_id)
                        if bucket and tkey in bucket:
                            updates = {"status": next_status, "completedAtMs": int(time.time() * 1000)}
                            if isinstance(out_txt, str) and out_txt and not bucket[tkey].get("outcome"):
                                updates["outcome"] = out_txt
                            bucket[tkey] = {**bucket[tkey], **updates}
                    self._sync_subagent_transcripts()
                self.wake_tailer(force=True)
                return {}
            if tool_use_id:
                key = "tool-" + tool_use_id
                old = self.rows.get(key)
                if old:
                    next_status = "cancelled" if is_interrupt else ("failed" if event_name == "PostToolUseFailure" else "completed")
                    self.row({**old, "status": next_status})
            if event_name == "PostToolUse" and tool_name in ("Agent", "Task") and tool_use_id:
                resp = payload.get("tool_response")
                linked_id = (resp.get("agentId") or resp.get("agent_id")) if isinstance(resp, dict) else None
                if isinstance(linked_id, str) and linked_id:
                    with self.lock:
                        matched = None
                        for idx, meta in enumerate(self._pending_agent_tools):
                            if meta.get("toolUseId") == tool_use_id:
                                matched = self._pending_agent_tools.pop(idx)
                                break
                        if matched is not None:
                            self._agent_id_to_tool[linked_id] = matched
            if event_name == "PostToolUse" and tool_name in ("CronCreate", "ScheduleWakeup"):
                with self.lock:
                    marker_id = tool_use_id or uuid.uuid4().hex
                    if not any(isinstance(c, dict) and c.get("id") == marker_id for c in self.session_crons):
                        self.session_crons.append({"id": marker_id, "tool": tool_name})
                self.sync_owner_runtime_state()
            if is_interrupt:
                self._settle_turn(row_status="cancelled", settle_unconfirmed=True)
                return {}
            self.wake_tailer(force=True)
            return {}

        if event_name == "SubagentStart":
            self._clear_tui_prompts()
            agent_id = payload.get("agent_id")
            agent_type = payload.get("agent_type", "") if isinstance(payload.get("agent_type"), str) else ""
            prompt_id = payload.get("prompt_id") or ""
            if isinstance(agent_id, str) and agent_id:
                meta = self._claim_agent_tool_meta(agent_id, agent_type, prompt_id)
                if meta is not None:
                    self.subagent(
                        agent_id,
                        itemId="tool-" + meta["toolUseId"],
                        name=meta.get("name", ""),
                        role=agent_type or meta.get("role", ""),
                        task=meta.get("task", ""),
                        status="running",
                        nativeStatus="started",
                        messages=[],
                    )
            self.wake_tailer(force=True)
            return {}

        if event_name == "SubagentStop":
            self._sync_stop_keepalive(payload)
            agent_id = payload.get("agent_id")
            agent_tp = payload.get("agent_transcript_path")
            if isinstance(agent_id, str) and agent_id and isinstance(agent_tp, str) and agent_tp:
                self._subagent_transcript_paths[agent_id] = agent_tp
            if isinstance(agent_id, str) and agent_id and agent_id in self.subagents:
                fields = {"status": "succeeded", "nativeStatus": "completed"}
                last_msg = payload.get("last_assistant_message")
                if isinstance(last_msg, str) and last_msg.strip():
                    fields["detail"] = last_msg.strip()
                self.subagent(agent_id, **fields)
                self._sync_subagent_transcripts()
            self.sync_owner_runtime_state()
            self.wake_tailer(force=True)
            return {}

        if event_name == "Stop":
            self._sync_stop_keepalive(payload)
            self.update(permissionNotice="")
            self._settle_turn(row_status="unknown", keep_error=False)
            return {}

        if event_name == "StopFailure":
            self._sync_stop_keepalive(payload)
            err_detail = payload.get("error_details")
            err_code = payload.get("error")
            err_msg = (
                err_detail.strip()
                if isinstance(err_detail, str) and err_detail.strip()
                else (
                    err_code.strip()
                    if isinstance(err_code, str) and err_code.strip()
                    else tr("Claude 原生回合失败", "Claude native turn failed")
                )
            )
            self.update(permissionNotice="")
            self._settle_turn(error=err_msg, row_status="unknown", settle_unconfirmed=True)
            return {}

        if event_name == "Notification":
            notif_type = payload.get("notification_type") or ""
            if notif_type in ("agent_needs_input", "elicitation_dialog"):
                msg = payload.get("message") if isinstance(payload.get("message"), str) else ""
                title = payload.get("title") if isinstance(payload.get("title"), str) else ""
                dialog_body = None
                has_cancel = True
                owner = self.owner
                if owner is not None and owner.alive():
                    for delay in (0.0, 0.1, 0.15):
                        if delay:
                            time.sleep(delay)
                        dialog_body, has_cancel = _extract_tui_confirm_dialog(owner.capture_pane(30))
                        if dialog_body:
                            break
                self._open_tui_prompt_request(
                    title or msg or tr("Claude 终端等待确认", "Claude terminal awaits confirmation"),
                    dialog_body or msg,
                    notif_type=notif_type,
                    message=msg,
                    has_cancel=has_cancel,
                )
            self.wake_tailer(force=True)
            return {}

        if event_name == "SessionEnd":
            with self.lock:
                self.session_crons = []
                self._stop_background_tasks = []
                self.background_tasks.clear()
                self._pending_agent_tools.clear()
                self._agent_id_to_tool.clear()
            self._settle_turn(row_status="unknown", keep_error=True)
            return {}

        return {}

    def answer(self, p):
        req_id = p.get("id")
        raw = self.pending_requests.get(req_id)
        if not raw:
            return super().answer(p)
        choice = p.get("choice")
        if choice not in ("allow", "deny"):
            raise Rejected(tr("无效审批选项", "Invalid approval choice"))
        if raw.get("tuiPrompt"):
            owner = self.owner
            if owner is None or not owner.alive():
                raise Rejected(tr("原生执行宿主已退出", "Native execution host has exited"))
            owner.send_keys("Enter" if choice == "allow" else "Escape")
            with self.lock:
                self._tui_prompt_requests.pop(req_id, None)
            self.answered(req_id)
            self.sync_owner_runtime_state()
            self.wake_tailer(force=True)
            return {}
        hook_event = raw.get("hookEvent")
        tool_name = raw.get("toolName", "")
        args = raw.get("input", {})
        if hook_event == "PreToolUse":
            if choice == "allow":
                updated = self._resolve_question_answers(args, p) if tool_name == "AskUserQuestion" else args
                decision_payload = {
                    "hookSpecificOutput": {
                        "hookEventName": "PreToolUse",
                        "permissionDecision": "allow",
                        "updatedInput": updated,
                    }
                }
            else:
                decision_payload = {
                    "hookSpecificOutput": {
                        "hookEventName": "PreToolUse",
                        "permissionDecision": "deny",
                        "permissionDecisionReason": "User denied this request",
                    }
                }
        else:
            if choice == "allow":
                decision = {"behavior": "allow"}
                if tool_name == "AskUserQuestion":
                    decision["updatedInput"] = self._resolve_question_answers(args, p)
            else:
                decision = {"behavior": "deny", "message": "User denied this request"}
            decision_payload = {
                "hookSpecificOutput": {
                    "hookEventName": "PermissionRequest",
                    "decision": decision,
                }
            }
        if not self.resolve_hook_waiter(req_id, decision_payload):
            raise Rejected(tr("审批不存在或已过期", "Approval request does not exist or has expired"))
        self.answered(req_id)
        return {}

    def validate_prompt(self, p):
        super().validate_prompt(p)
        if len(p.get("skills", [])) > 1:
            raise Rejected(tr("Claude Code 每条消息最多选择一个 Skill", "Claude Code allows at most one skill per message"))
        if any(k not in {s["id"] for s in self.data["skills"]} for k in p.get("skills", [])):
            raise Rejected(tr("Skill 不在原生目录中", "Skill is not in the native catalog"))
        if not p.get("skills") and p.get("text", "").lstrip().split(maxsplit=1)[0] in ("/clear", "/reset", "/new"):
            raise Rejected(tr(
                "此原生命令会更换会话 ID；请使用 GUI 新建会话，保留当前会话的身份与历史",
                "This native command replaces the session ID; start a new session in GUI to preserve current identity and history",
            ))

    def prompt(self, p):
        body = p["text"]
        if p.get("skills"):
            body = "/" + p["skills"][0] + " " + body
        sub_id = p["submissionId"]
        self._scan_transcript_turn_end(record_only=True)
        with self.lock:
            self._turn_seq += 1
            self._interrupt_requested_at = None
            self._interrupt_turn_seq = None
            self._interrupt_pane_baseline = ""
            self.submitted_user_text[sub_id] = body
            self.current_submission = sub_id
            self._pending_submission = {"id": sub_id, "text": body, "ts": time.monotonic()}
        self.update(running=True, error=None)
        self.sync_owner_runtime_state()
        try:
            self.owner.inject_text(body, submit=True, submit_delay=0.22)
        except Exception:
            with self.lock:
                self._pending_submission = None
                self.current_submission = None
            self.update(running=False)
            self.sync_owner_runtime_state()
            raise
        wait_limit = max(0.0, float(self.prompt_confirm_wait_sec))
        if wait_limit > 0 and self.owner is not None:
            started_at = time.monotonic()
            deadline = started_at + wait_limit
            retried = False
            first_line = body.strip().splitlines()[0][:24] if body.strip() else ""
            while time.monotonic() < deadline:
                with self.lock:
                    if self._pending_submission is None or self._pending_submission.get("id") != sub_id:
                        break
                    if not self.data.get("running"):
                        break
                if not retried and time.monotonic() - started_at >= 0.55 and first_line:
                    draft = _pane_prompt_draft(self.owner.capture_pane(10))
                    if draft and (first_line in draft or draft in body):
                        try:
                            self.owner.send_keys("Enter")
                        except Exception:
                            pass
                        retried = True
                time.sleep(0.08)
        self.wake_tailer(force=True)
        return False

    def _cycle_tui_permission(self, target):
        owner = self.owner
        if owner is None or not owner.alive():
            raise Rejected(tr("原生执行宿主已退出", "Native execution host has exited"))
        current = _parse_pane_permission_mode(owner.capture_pane(12)) or self.data.get("permission") or "default"
        if current == target:
            return target
        seen = {current}
        last_mode = current
        step_timeout = max(0.08, float(self.permission_cycle_step_timeout_sec))
        for _ in range(6):
            owner.send_keys("BTab")
            deadline = time.monotonic() + step_timeout
            next_mode = None
            while time.monotonic() < deadline:
                cand = _parse_pane_permission_mode(owner.capture_pane(12))
                if cand and cand != last_mode:
                    next_mode = cand
                    break
                time.sleep(0.05)
            if not next_mode:
                break
            last_mode = next_mode
            if next_mode == target:
                return next_mode
            if next_mode in seen:
                break
            seen.add(next_mode)
        if last_mode and last_mode != self.data.get("permission"):
            self.update(permission=last_mode)
        raise Rejected(
            tr(
                "当前 Claude TUI 的 Shift+Tab 权限循环不包含此模式：",
                "This mode is not in Claude TUI's Shift+Tab permission cycle: ",
            ) + target
        )

    @staticmethod
    def _validate_slash_value(value, label):
        if not isinstance(value, str) or not value or any(ch.isspace() or ord(ch) < 0x20 or ord(ch) == 0x7f for ch in value):
            raise Rejected(tr(f"无效的 {label} 参数", f"Invalid {label} value"))

    @staticmethod
    def _pane_rejection_line(before_text, after_text, command_text):
        before_lines = [ln.strip() for ln in (before_text or "").splitlines() if ln.strip()]
        after_lines = [ln.strip() for ln in (after_text or "").splitlines() if ln.strip()]
        overlap = 0
        if before_lines and after_lines:
            max_k = min(len(before_lines), len(after_lines))
            for k in range(max_k, 0, -1):
                if before_lines[-k:] == after_lines[:k]:
                    overlap = k
                    break
        new_lines = after_lines[overlap:]
        pattern = re.compile(
            r"(?i)(?:invalid\s+(?:model|effort)|unknown\s+(?:model|effort|command)|"
            r"not\s+(?:supported|available|valid|found)|cannot\s+set|failed\s+to\s+set|"
            r"unsupported\s+(?:model|effort)|model\s+not\s+found|disabled\s+by\s+policy|"
            r"^\s*error\s*:)"
        )
        for line in new_lines:
            if line == command_text:
                continue
            if pattern.search(line):
                return line
        return None

    def _inject_slash_setting(self, command_text, label):
        before = self.owner.capture_pane(20)
        self.owner.inject_text(command_text, submit=True, submit_delay=0.18)
        for _ in range(2):
            time.sleep(0.15)
            after = self.owner.capture_pane(20)
            rejected_line = self._pane_rejection_line(before, after, command_text)
            if rejected_line:
                raise Rejected(tr(f"Claude Code 拒绝更改 {label}：{rejected_line}", f"Claude Code rejected {label} change: {rejected_line}"))

    def configure(self, p):
        if self.data["running"]:
            raise Rejected(tr("请等待回合结束再更改设置", "Wait for the current turn to finish before changing settings"))
        if p.get("speed"):
            raise Rejected(tr("当前 Claude 双端协议未提供原生速度设置接口；保留 CLI 配置", "This Claude dual-end protocol has no native speed setting; CLI config is preserved"))
        if p.get("model"):
            self._validate_slash_value(p["model"], "model")
        if p.get("effort"):
            self._validate_slash_value(p["effort"], "effort")
        if p.get("permission"):
            value = p["permission"]
            self.validate_permission(value)
            actual = self._cycle_tui_permission(value)
            self.config["selectedPermission"] = actual
            self.update(permission=actual)
            self.save()
        if p.get("effort"):
            target_model = p.get("model") or self.data["model"]
            if self.data.get("models"):
                self.validate_effort(p["effort"], target_model)
        if p.get("model"):
            value = p["model"]
            if self.data.get("models") and value not in {m["id"] for m in self.data["models"]}:
                raise Rejected(tr("模型不在原生目录中", "Model is not in the native catalog"))
            if value != self.data["model"]:
                self._inject_slash_setting(f"/model {value}", "model")
                self.config["selectedModel"] = value
                for m in self.native_models:
                    if isinstance(m, dict) and m.get("value") == value:
                        resolved_m = m.get("resolvedModel") or m.get("displayName") or value
                        if isinstance(resolved_m, str) and resolved_m:
                            self._session_model = resolved_m
                        break
                self.update(model=value)
                self.publish_usage()
                self.save()
        if p.get("effort"):
            value = p["effort"]
            if value != self.data.get("effort"):
                self._inject_slash_setting(f"/effort {value}", "effort")
                self.config["selectedEffort"] = value
                self.update(effort=value)
                self.save()
        return {}

    def stop(self):
        owner = self.owner
        with self.lock:
            has_unconfirmed = any(
                d.get("state") in ("queued", "dispatching", "unknown")
                for d in self.config.get("deliveries", {}).values()
            ) or any(
                r in ("unknown", "pending", "dispatching", "queued")
                for r in self.config.get("receipts", {}).values()
            )
            active = bool(self.data.get("running") or self.current_submission or self._pending_submission or has_unconfirmed)
        if owner is None or not owner.alive():
            if active:
                self._settle_turn(row_status="cancelled", settle_unconfirmed=True)
            return {}
        baseline = owner.capture_pane(20)
        self.handoff_requests_to_tui()
        owner.send_keys("Escape")
        if has_unconfirmed and _pane_prompt_draft(owner.capture_pane(10)):
            owner.send_keys("C-u")
        with self.lock:
            if active:
                self._interrupt_requested_at = time.monotonic()
                self._interrupt_turn_seq = self._turn_seq
                self._interrupt_pane_baseline = baseline
        if not active:
            return {}
        deadline = time.monotonic() + max(0.05, float(self.interrupt_settle_wait_sec))
        while time.monotonic() < deadline:
            if self._try_settle_interrupted_turn():
                break
            time.sleep(0.05)
        with self.lock:
            still_running = bool(self.data.get("running"))
        if not still_running:
            self._settle_unconfirmed_submissions()
        self.wake_tailer(force=True)
        return {}

    def compact(self):
        return self.submit({"submissionId": str(uuid.uuid4()), "text": "/compact"})

    def refresh(self):
        if self.owner is not None and not self.owner.alive():
            self.on_owner_exited()
            return {}
        self.sync_transcript()
        self._sync_pane_permission(force=True)
        self._refresh_catalog_async(force=True)
        return {}

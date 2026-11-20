import base64
import copy
import json
import mimetypes
import os
import pathlib
import re
import shutil
import subprocess
import threading
import urllib.parse
from .acp import AcpSession
from .antigravity import AntigravitySession
from .antigravity_cli import (
    _SWARM_BOARD_PING_RE,
    _matching_bg_task_ids,
    _settle_terminal_child_messages,
    AgyChildTailMixin,
    agy_timer_condition,
    child_log_path,
    clean_agy_tool_output,
    extract_agy_bg_task_id,
    extract_agy_task_result,
    extract_agy_tool_timestamps,
    invoke_child,
    is_agy_oneshot_timer,
    parse_system_message,
    read_full_step,
    read_transcript,
    transcript_args,
)
from .base import display_content, image_blocks, item, parse_ts_ms, text
from .external_browser import BrowserRelay
from .i18n import lang_code, tr
from .metrics import agy_acp_usage, agy_session_metrics
from .transport import Rejected

AGY_ACP_PROTOCOL = "antigravity-agy-acp"
FULL_ACCESS = "always-proceed"

def _full_access_option():
    return {
        "id": FULL_ACCESS,
        "label": "Always proceed",
        "description": tr("启用原生 --dangerously-skip-permissions，自动批准工具与命令执行",
                          "Enable native --dangerously-skip-permissions to auto-approve tools and commands"),
    }
_CRON_CANCELLED_RE = re.compile(r"^\[([^\]]+)\]\s+Cron schedule cancelled\b", re.M)
_CRON_CANCELLED_MSG_RE = re.compile(r'^Cron schedule "([^"]+)" cancelled\b', re.M)
_BG_CANCELLED_RE = re.compile(r'^Task id "[^"]+" (?:was |has been )?(?:killed|cancell?ed)\b', re.I)
_USER_REQ_RE = re.compile(r"<USER_REQUEST>(?:\r?\n)?([\s\S]*?)(?:\r?\n)?</USER_REQUEST>")
_ADD_META_RE = re.compile(r"<ADDITIONAL_METADATA>[\s\S]*?</ADDITIONAL_METADATA>")

def _extract_agy_user_text(raw):
    if not isinstance(raw, str) or not raw:
        return "", False
    match = _USER_REQ_RE.search(raw)
    if match:
        return match.group(1), True
    if "<CONTEXT_SUMMARY>" in raw:
        return "", False
    cleaned = _ADD_META_RE.sub("", raw).strip("\r\n")
    return cleaned, bool(cleaned)

def _has_local_transcript(native_id):
    path = child_log_path(native_id, "")
    if not path:
        return False
    try:
        with open(path, "rb") as source:
            for line in source:
                if not line.endswith(b"\n"):
                    break
                try:
                    parsed = json.loads(line)
                except ValueError:
                    continue
                if isinstance(parsed, dict):
                    return True
    except OSError:
        return False
    return False

def _plan_tool_kind(name, title=""):
    for candidate in (name, title):
        if candidate in ("exit_plan_mode", "enter_plan_mode"):
            return candidate
    return ""

def _plan_tool_title(plan_kind):
    if plan_kind == "exit_plan_mode":
        return tr("退出规划 · 交由执行模型", "Exit plan · Hand off to executor")
    if plan_kind == "enter_plan_mode":
        return tr("回交规划模型复盘", "Hand back to planner")
    return ""

def _plan_tool_outcome(plan_kind, params, cleaned_outcome, status=""):
    params = params if isinstance(params, dict) else {}
    stripped = (cleaned_outcome or "").strip()
    if status == "failed" and stripped:
        return cleaned_outcome
    if plan_kind == "exit_plan_mode":
        plan_body = params.get("plan")
        if isinstance(plan_body, str) and plan_body.strip():
            if stripped == "Plan mode is off." or (status in ("completed", "succeeded") and not stripped):
                return plan_body.strip()
    elif plan_kind == "enter_plan_mode":
        reason = params.get("reason")
        if isinstance(reason, str) and reason.strip() and stripped in ("", "Plan mode is on."):
            return reason.strip()
    return cleaned_outcome

class AntigravityAgyAcpSession(AgyChildTailMixin, AntigravitySession):
    argv = ["agy", "acp", "--stdio"]
    supports_forms = True
    defer_connected = True
    desired_model_on_create = False
    replay_release = True
    release_empty = True

    def _primary_mode_option(self, options=None):
        opts = getattr(self, "options", []) if options is None else options
        return next((o for o in opts if o.get("type") == "select" and o.get("category", o.get("id")) == "mode"), None)

    def _plan_supported_in_mode_option(self, mode_opt=None):
        if mode_opt is None:
            mode_opt = self._primary_mode_option()
        if not mode_opt:
            return False
        return "plan" in {v["value"] for v in self.flatten(mode_opt.get("options", []))}

    def _default_non_plan_mode(self, mode_opt=None):
        if mode_opt is None:
            mode_opt = self._primary_mode_option()
        if not mode_opt:
            return "default"
        values = [v["value"] for v in self.flatten(mode_opt.get("options", [])) if v["value"] != "plan"]
        if "default" in values:
            return "default"
        return values[0] if values else ""

    def _saved_skip_permissions(self):
        if "skipPermissions" in self.config:
            return bool(self.config.get("skipPermissions"))
        return self.config.get("selectedPermission") == FULL_ACCESS

    def _saved_plan_mode(self):
        if "selectedPlanMode" in self.config:
            val = self.config.get("selectedPlanMode")
            return bool(val) if val is not None else None
        if self.config.get("selectedNativeMode") == "plan" or self.config.get("selectedPermission") == "plan":
            return True
        return None

    def _saved_base_mode(self):
        if "selectedBaseMode" in self.config:
            return self.config.get("selectedBaseMode") or ""
        for key in ("selectedNativeMode", "selectedPermission"):
            perm = self.config.get(key) or ""
            if perm and perm not in (FULL_ACCESS, "plan"):
                return perm
        return ""

    def _effective_agy_permission(self):
        if getattr(self, "skip_permissions_active", False):
            return FULL_ACCESS
        base = getattr(self, "base_mode", "")
        if base:
            return base
        native_mode = getattr(self, "native_mode", "")
        if native_mode and native_mode != "plan":
            return native_mode
        return self.data.get("permission", "") or self._default_non_plan_mode()

    def __init__(self, config, save):
        super().__init__(config, save)
        self.tail_stop = threading.Event()
        self.tail_wake = threading.Event()
        self.tail_thread = None
        self._reset_parent_tracking()

    def _reset_parent_tracking(self):
        with self.lock:
            self.transcript_versions = {}
            self.parent_offset = 0
            self.parent_calls = []
            self.parent_children = set()
            self.parent_turn_settled = False
            self.background_tasks = set()
            self.settled_background_tasks = set()
            self.background_task_rows = {}
            self.oneshot_timers = set()
            self.conditional_timers = {}
            self.in_autonomous_turn = False
            self.autonomous_running = False
            self.autonomous_streamed = False
            self.autonomous_open_tools = []
            self.autonomous_row_ids = []
            self.autonomous_catchup = []
            self.catchup_tool_ids = set()
            self.catchup_tool_rows = {}
            self.child_replies = {}
            self.child_call_args = {}
            self.swarm_groups = {}
            self.swarm_workers = set()
            self.swarm_call_args = {}
            self.swarm_revived_steps = {}
            self.swarm_stopped_steps = {}
            self.child_step_highs = {}
            self.handled_swarm_rows = set()
            self.steer_submission = None
            self.active_prompts = []
            self.steered_submissions = set()
            self.pending_echo_submissions = []
            self._resumed_from_transcript = False

    def _ensure_prompt_tracking(self):
        if not isinstance(getattr(self, "active_prompts", None), list):
            self.active_prompts = []
        if not isinstance(getattr(self, "steered_submissions", None), set):
            self.steered_submissions = set()
        if not isinstance(getattr(self, "pending_echo_submissions", None), list):
            self.pending_echo_submissions = []

    def _has_active_prompt(self):
        return bool(
            getattr(self, "active_prompts", None)
            or getattr(self, "current_submission", None) is not None
            or getattr(self, "steer_submission", None) is not None
        )

    def _wake_transcript_tail(self):
        wake = getattr(self, "tail_wake", None)
        if wake is not None:
            wake.set()

    def _admit_steered_submission_locked(self, ident, media_override=None, timestamp_ms=0):
        self._ensure_prompt_tracking()
        if ident in self.pending_echo_submissions:
            self.pending_echo_submissions.remove(ident)
        if ident in self.steered_submissions:
            self.steered_submissions.discard(ident)
            self.finish_chunk("completed")
            self.chunk_kind = None
            old = self.rows.pop(ident, None)
            base_row = old if old is not None else item(ident, "user", "")
            extra_media = {"media": media_override} if media_override and not (old or {}).get("media") else {}
            extra_ts = {"timestampMs": timestamp_ms} if timestamp_ms and not (old or {}).get("timestampMs") else {}
            self.row({**base_row, **extra_media, **extra_ts, "status": "completed", "origin": "native"})
        self.receipt(ident, "accepted")
        self.consumed(ident)

    def update(self, **fields):
        with self.lock:
            if fields.get("running") is False and (getattr(self, "autonomous_running", False) or self._has_active_prompt()) and self.data.get("connected"):
                fields["running"] = True
            super().update(**fields)

    def fast_release_blocker(self):
        with self.lock:
            blocker = super().fast_release_blocker()
            if blocker in ("工具状态尚未确认结束", "Tool status has not been confirmed finished") and self.background_tasks:
                return tr("后台任务尚未确认结束", "Background task has not been confirmed finished")
            if blocker:
                return blocker
            if self._has_active_prompt():
                return tr("原生回合尚未结算", "Native turn has not settled")
            if self.autonomous_running or self.in_autonomous_turn:
                return tr("后台唤醒回合尚未结算", "Background wake-up turn has not settled")
            if self.background_tasks:
                return tr("后台任务尚未确认结束", "Background task has not been confirmed finished")
            return None

    def release_blocker(self):
        return super().release_blocker()

    def discover_binary(self):
        if not shutil.which(self.argv[0]):
            raise Rejected(tr("未找到 agy；请在当前运行环境的普通终端安装 agy（1.5.0 及以上）并完成原生登录。",
                              "agy not found; install agy (1.5.0 or newer) in a regular terminal of the current environment and sign in."))
        env = os.environ.copy()
        env["LUMA_LANG"] = lang_code()
        if self.config.get("browserConfig"):
            env["LUMA_BROWSER_CONFIG"] = self.config["browserConfig"]
        try:
            version = subprocess.run([self.argv[0], "--version"], env=env, cwd=self.config["cwd"],
                                     capture_output=True, timeout=10, check=False)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise Rejected(tr("agy 版本探测失败；请在普通终端核对 agy 安装状态。",
                              "Failed to probe agy version; check agy installation in a regular terminal.")) from exc
        match = re.search(rb"(\d+)\.(\d+)\.(\d+)", version.stdout)
        if version.returncode or not match or tuple(map(int, match.groups())) < (1, 5, 0):
            raise Rejected(tr("当前 agy 版本未提供稳定官方 acp 接口；请升级至 1.5.0 或更新版本。",
                              "Current agy version lacks a stable official acp interface; upgrade to 1.5.0 or newer."))

    def start(self, argv):
        self.authenticating = False
        self.auth_cancelled = False
        self.auth_url_rejected = False
        self.skip_permissions_active = self._saved_skip_permissions()
        self.native_mode = ""
        self.base_mode = self._saved_base_mode()
        self.auth_browser = BrowserRelay(self.open_auth_browser, self.reject_auth_browser)
        command = [
            *argv,
            *(["--dangerously-skip-permissions"] if self.skip_permissions_active else []),
            "--add-dir",
            self.config["cwd"],
        ]
        try:
            AcpSession.start(self, command, self.auth_browser.xdg_environment(os.environ.get("PATH", "")))
        except Exception:
            self.auth_browser.close()
            raise

    def _close_current_peer(self):
        self.tail_stop.set()
        self.tail_wake.set()
        self._clear_live_process_tasks()
        peer, self.peer = self.peer, None
        browser = getattr(self, "auth_browser", None)
        self.auth_browser = None
        if peer:
            peer.event = lambda _frame: None
            peer.exited = lambda _error: None
            with peer.write_lock:
                peer.closed = True
                if peer.proc.stdin and not peer.proc.stdin.closed:
                    try:
                        peer.proc.stdin.close()
                    except OSError:
                        pass
            try:
                peer.proc.wait(timeout=1)
            except subprocess.TimeoutExpired:
                pass
            peer.close()
        if browser:
            browser.close()

    def authenticate(self, info):
        try:
            super().authenticate(info)
        except Rejected as exc:
            if (not self.auth_cancelled and not self.auth_url_rejected
                    and re.search(r"web browser|unauthenticated|not logged in|login required", str(exc), re.I)):
                raise Rejected(tr("agy 尚未登录；请在当前运行环境的普通终端运行 agy acp --login（或 agy）完成原生登录后重连。",
                                  "agy is not signed in; run `agy acp --login` (or `agy`) in a regular terminal of the current environment, then reconnect.")) from None
            raise

    def apply_options(self, options):
        super().apply_options(options)
        mode_opt = self._primary_mode_option(options)
        plan_supported = self._plan_supported_in_mode_option(mode_opt)
        if mode_opt is not None:
            self.native_mode = mode_opt.get("currentValue", "")
            non_plan_values = {v["value"] for v in self.flatten(mode_opt.get("options", [])) if v["value"] != "plan"}
            if self.native_mode and self.native_mode != "plan":
                self.base_mode = self.native_mode
            elif getattr(self, "base_mode", "") not in non_plan_values:
                saved_base = self._saved_base_mode()
                self.base_mode = saved_base if saved_base in non_plan_values else self._default_non_plan_mode(mode_opt)
        skip_active = bool(getattr(self, "skip_permissions_active", False))
        multi_group = len(self.permission_options()) > 1
        permissions = [
            dict(p) for p in self.data.get("permissions", [])
            if not (plan_supported and p.get("id") == "plan" and (not p.get("configId") or (mode_opt and p.get("configId") == mode_opt["id"])))
        ]
        if not any(p.get("id") == FULL_ACCESS for p in permissions):
            full_opt = _full_access_option()
            if multi_group and mode_opt is not None:
                full_opt.update(configId=mode_opt["id"], configName=mode_opt.get("name", mode_opt["id"]))
                insert_at = max((i + 1 for i, p in enumerate(permissions) if p.get("configId") == mode_opt["id"]), default=len(permissions))
                permissions.insert(insert_at, full_opt)
            else:
                permissions.append(full_opt)
        effective_perm = self._effective_agy_permission()
        if multi_group and mode_opt is not None:
            for permission in permissions:
                if permission.get("configId") == mode_opt["id"]:
                    permission["selected"] = permission["id"] == effective_perm
        self.update(
            permissions=permissions,
            permission=effective_perm,
            planModeSupported=plan_supported,
            planMode=bool(plan_supported and self.native_mode == "plan"),
            skipPermissionsActive=skip_active,
        )

    def _settle_autonomous_open_tools(self, status="cancelled"):
        while self.autonomous_open_tools:
            rid = self.autonomous_open_tools.pop(0)
            row = self.rows.get(rid)
            if (
                row
                and rid not in set(self.background_task_rows.values())
                and row.get("status") in ("running", "in_progress", "pending")
            ):
                self.row({**row, "status": status})

    def _settle_turn_streamed_tools(self):
        bg_row_ids = set(self.background_task_rows.values())
        preexisting = getattr(self, "preexisting_tool_ids", set())
        for rid, r in list(self.rows.items()):
            if (
                rid.startswith("tool-")
                and rid[5:] not in preexisting
                and rid not in bg_row_ids
                and r.get("role") == "tool"
                and r.get("status") in ("running", "in_progress", "pending")
            ):
                self.row({**r, "status": "completed"})

    def _clear_live_process_tasks(self, settle_rows=False):
        with self.lock:
            if settle_rows:
                for tid in list(self.background_tasks):
                    self._settle_background_task(tid, "cancelled")
                self._settle_autonomous_open_tools("cancelled")
            self.background_tasks.clear()
            if not settle_rows:
                self.settled_background_tasks.clear()
            self.background_task_rows.clear()
            self.oneshot_timers.clear()
            self.conditional_timers.clear()
            self.in_autonomous_turn = False
            self.autonomous_running = False
            self.autonomous_open_tools.clear()
            self.steer_submission = None
            if hasattr(self, "active_prompts"):
                self.active_prompts.clear()
            if hasattr(self, "steered_submissions"):
                self.steered_submissions.clear()
            if hasattr(self, "pending_echo_submissions"):
                self.pending_echo_submissions.clear()

    def exited(self, error):
        self.tail_stop.set()
        self.tail_wake.set()
        was_autonomous = False
        with self.lock:
            was_autonomous = self.autonomous_running and not self._has_active_prompt()
            self._clear_live_process_tasks()
        super().exited(error)
        if was_autonomous:
            self.update(running=False)

    def finish_observer_close(self):
        self.tail_stop.set()
        self.tail_wake.set()
        tail_thread = getattr(self, "tail_thread", None)
        if tail_thread is not None and tail_thread.is_alive() and threading.current_thread() is not tail_thread:
            tail_thread.join(timeout=2.0)
        with self.lock:
            self._clear_live_process_tasks()
        super().finish_observer_close()

    def stop(self):
        result = super().stop()
        with self.lock:
            if not self._has_active_prompt() and (self.autonomous_running or self.in_autonomous_turn):
                self.autonomous_running = False
                self.in_autonomous_turn = False
                self.autonomous_open_tools.clear()
                for rid in self.autonomous_row_ids:
                    row = self.rows.get(rid)
                    if row and row.get("status") in ("running", "in_progress", "pending"):
                        self.row({**row, "status": "cancelled"})
                self.update(running=False)
        return result

    def _accept_catchup(self):
        if self.current_submission:
            self.receipt(self.current_submission, "accepted")
            self.consumed(self.current_submission)
        return True

    @staticmethod
    def _tool_params_match(expected_params, incoming_params):
        if not isinstance(expected_params, dict) or not isinstance(incoming_params, dict):
            return False
        if not expected_params and not incoming_params:
            return True
        if not expected_params or not incoming_params:
            return False
        shared = set(expected_params) & set(incoming_params)
        if not shared or (shared != set(expected_params) and shared != set(incoming_params)):
            return False
        for k in shared:
            ev, iv = str(expected_params[k]), str(incoming_params[k])
            if ev == iv:
                continue
            if "<truncated" in ev:
                prefix = ev.split("<truncated", 1)[0].rstrip("\n\"\\")
                if prefix and iv.startswith(prefix[:60]):
                    continue
            return False
        return True
    def _consume_autonomous_catchup(self, u):
        kind = u.get("sessionUpdate")
        if kind in ("tool_call", "tool_call_update"):
            tc_id = u.get("toolCallId")
            if tc_id and tc_id in self.catchup_tool_ids:
                mapped_rid = self.catchup_tool_rows.get(tc_id, "")
                if mapped_rid and mapped_rid in self.rows:
                    old_auto = self.rows[mapped_rid]
                    if (
                        u.get("status") in ("completed", "failed", "cancelled")
                        and old_auto.get("status") in ("running", "in_progress", "pending")
                        and mapped_rid not in set(self.background_task_rows.values())
                    ):
                        changes = {"status": u["status"]}
                        if "content" in u and not old_auto.get("outcome"):
                            changes["outcome"] = clean_agy_tool_output(display_content(u["content"]))
                        self.row({**old_auto, **changes})
                return self._accept_catchup()
            if tc_id and f"tool-{tc_id}" in self.rows:
                old = self.rows[f"tool-{tc_id}"]
                if u.get("status") in ("completed", "failed", "cancelled") and old.get("status") in ("running", "in_progress", "pending"):
                    changes = {"status": u["status"]}
                    if "content" in u and not old.get("outcome"):
                        changes["outcome"] = clean_agy_tool_output(display_content(u["content"]))
                    self.row({**old, **changes})
                self.catchup_tool_ids.add(tc_id)
                return self._accept_catchup()
            incoming_name = u.get("name") or ""
            incoming_params = u.get("rawInput") if isinstance(u.get("rawInput"), dict) else (
                u.get("args") if isinstance(u.get("args"), dict) else None
            )
            for idx, expected in enumerate(self.autonomous_catchup):
                if (
                    expected["kind"] == "tool"
                    and bool(incoming_name)
                    and incoming_name == expected["name"]
                    and self._tool_params_match(expected.get("params"), incoming_params)
                ):
                    self.autonomous_catchup.pop(idx)
                    for prev in list(self.autonomous_catchup[:idx]):
                        if prev["kind"] == "reasoning" and prev.get("matched"):
                            self.autonomous_catchup.remove(prev)
                    if tc_id:
                        self.catchup_tool_ids.add(tc_id)
                        if expected.get("row_id"):
                            self.catchup_tool_rows[tc_id] = expected["row_id"]
                    return self._accept_catchup()
            return False

        if kind in ("agent_thought_chunk", "agent_message_chunk"):
            role = "reasoning" if kind == "agent_thought_chunk" else "assistant"
            chunk = text(u.get("content"))
            if not chunk:
                if any(entry["kind"] == role for entry in self.autonomous_catchup):
                    return True
                return False
            idx = 0
            while idx < len(self.autonomous_catchup):
                entry = self.autonomous_catchup[idx]
                if entry["kind"] == role:
                    combined = entry["matched"] + chunk
                    target = entry["text"]
                    if (target.startswith(combined)
                            or target.lstrip().startswith(combined.lstrip())
                            or target.strip().startswith(combined.strip())
                            or combined.strip() == target.strip()):
                        entry["matched"] = combined
                        if combined == target or combined.strip() == target.strip():
                            self.autonomous_catchup.pop(idx)
                        return self._accept_catchup()
                    if entry["matched"]:
                        self.autonomous_catchup.pop(idx)
                        continue
                idx += 1
            return False

        return False

    def event(self, e):
        if (e.get("method") == "session/update"
                and e.get("params", {}).get("update", {}).get("sessionUpdate") == "current_mode_update"):
            native = self.data["nativeId"] or self.config.get("nativeId", "")
            p = e.get("params", {})
            if native and p.get("sessionId") and p["sessionId"] != native:
                return
            mode_id = p.get("update", {}).get("currentModeId", "")
            if isinstance(mode_id, str) and mode_id:
                mode_opt = self._primary_mode_option()
                groups = self.permission_options()
                if len(groups) > 1:
                    try:
                        target = self.permission_option(mode_id)
                    except Rejected:
                        return
                    if mode_opt is None or target["id"] != mode_opt["id"]:
                        return AcpSession.event(self, e)
                self.native_mode = mode_id
                if mode_id != "plan":
                    self.base_mode = mode_id
                if mode_opt is not None:
                    mode_opt["currentValue"] = mode_id
                    self.apply_options(self.options)
                skip_active = bool(getattr(self, "skip_permissions_active", False))
                effective = self._effective_agy_permission()
                plan_supported = bool(self.data.get("planModeSupported"))
                plan_active = bool(plan_supported and mode_id == "plan")
                if getattr(self, "history_loaded", False):
                    has_saved_prefs = any(k in self.config for k in ("skipPermissions", "selectedBaseMode", "selectedPlanMode", "selectedNativeMode", "selectedPermission"))
                    if skip_active or has_saved_prefs:
                        changed = False
                        if plan_supported and self.config.get("selectedPlanMode") != plan_active:
                            self.config["selectedPlanMode"] = plan_active
                            changed = True
                        if mode_id != "plan" and self.config.get("selectedBaseMode") != mode_id:
                            self.config["selectedBaseMode"] = mode_id
                            changed = True
                        if self.config.get("selectedNativeMode") != mode_id:
                            self.config["selectedNativeMode"] = mode_id
                            changed = True
                        if self.config.get("selectedPermission") != effective:
                            self.config["selectedPermission"] = effective
                            changed = True
                        if changed:
                            try:
                                self.save()
                            except OSError:
                                pass
                self.update(permission=effective, planMode=plan_active, skipPermissionsActive=skip_active)
            return
        prev_tool_outcome = ""
        prev_tool_status = ""
        if e.get("method") == "session/update":
            u = e.get("params", {}).get("update", {})
            kind = u.get("sessionUpdate")
            with self.lock:
                if kind in ("agent_message_chunk", "agent_thought_chunk", "tool_call", "tool_call_update"):
                    if getattr(self, "history_loaded", False) and getattr(self, "steered_submissions", None):
                        self.refresh_parent_log()
                    if getattr(self, "history_loaded", False) and (not self._has_active_prompt() or self.in_autonomous_turn):
                        if self.in_autonomous_turn and (self.autonomous_catchup or self.catchup_tool_ids):
                            if self._consume_autonomous_catchup(u):
                                return
                        self.autonomous_streamed = True
                    elif self._has_active_prompt() and (self.autonomous_catchup or self.catchup_tool_ids):
                        if self._consume_autonomous_catchup(u):
                            return
                        if kind in ("tool_call", "tool_call_update") or bool(text(u.get("content"))):
                            self.autonomous_catchup.clear()
            if kind in ("tool_call", "tool_call_update") and u.get("toolCallId"):
                existing_tool = self.rows.get("tool-" + u["toolCallId"], {})
                prev_tool_outcome = existing_tool.get("outcome", "")
                prev_tool_status = existing_tool.get("status", "")
                plan_kind = _plan_tool_kind(u.get("name") or existing_tool.get("toolName", ""), u.get("title", ""))
                if plan_kind:
                    e = {
                        **e,
                        "params": {
                            **e["params"],
                            "update": {**u, "name": plan_kind, "title": _plan_tool_title(plan_kind)},
                        },
                    }
        super().event(e)
        if e.get("method") == "session/update":
            u = e.get("params", {}).get("update", {})
            if u.get("sessionUpdate") in ("tool_call", "tool_call_update") and u.get("toolCallId"):
                ident = "tool-" + u["toolCallId"]
                row = self.rows.get(ident, {})
                tool_name = row.get("toolName") or u.get("name", "")
                raw_params = u.get("rawInput") if isinstance(u.get("rawInput"), dict) else (
                    u.get("args") if isinstance(u.get("args"), dict) else None
                )
                if raw_params is None:
                    raw_in = row.get("input")
                    if not raw_in:
                        raw_params = {}
                    else:
                        try:
                            parsed_in = json.loads(raw_in)
                            raw_params = parsed_in if isinstance(parsed_in, dict) else {}
                        except (ValueError, TypeError):
                            raw_params = {}
                if tool_name == "invoke_subagent":
                    child_id, uri = invoke_child(row.get("outcome", ""))
                    if child_id:
                        self.register_child(
                            child_id,
                            ident,
                            self.current_submission or getattr(self, "steer_submission", None) or "",
                            raw_params.get("TypeName") or "",
                            raw_params.get("Role") or "",
                            raw_params.get("Prompt") or "",
                            uri,
                            raw_params.get("Model", ""),
                        )
                    else:
                        self._reanchor_subagents()
                        if getattr(self, "history_loaded", False):
                            self.tail_wake.set()
                elif tool_name == "manage_subagents" and row.get("status") == "completed":
                    self.note_manage(raw_params)
                elif tool_name == "swarm":
                    if row.get("status") in ("completed", "succeeded"):
                        with self.lock:
                            unhandled = ident not in self.handled_swarm_rows
                        if unhandled and (row.get("outcome") or raw_params.get("action") == "kill"):
                            turn_id = row.get("turnId") or self.current_submission or getattr(self, "steer_submission", None) or ""
                            with self.lock:
                                if self.note_swarm_tool(ident, turn_id, raw_params, row.get("outcome", ""), allow_live=getattr(self, "history_loaded", False)):
                                    self.handled_swarm_rows.add(ident)
                        else:
                            self._reanchor_subagents()
                    elif raw_params.get("action") == "add":
                        self._reanchor_subagents()
                elif tool_name == "post_message" and row.get("status") in ("completed", "succeeded"):
                    with self.lock:
                        if ident not in self.handled_swarm_rows:
                            allow_revive = getattr(self, "history_loaded", False)
                            if self.note_post_message(raw_params, row.get("outcome", ""), allow_revive=allow_revive):
                                self.handled_swarm_rows.add(ident)
                if row.get("status") in ("completed", "succeeded") and not row.get("outcome"):
                    if getattr(self, "history_loaded", False):
                        self.ensure_child_tail()
                        self.tail_wake.set()
                raw_out_obj = u.get("rawOutput")
                has_raw_exit = isinstance(raw_out_obj, dict) and raw_out_obj.get("exit_code") is not None
                raw_outcome = row.get("outcome", "") or ""
                tid = "" if has_raw_exit else extract_agy_bg_task_id(raw_outcome, tool_name)
                cleaned_outcome = clean_agy_tool_output(raw_outcome)
                plan_kind = _plan_tool_kind(tool_name, u.get("title", ""))
                with self.lock:
                    keep_running = False
                    preserve_settled = False
                    if tid:
                        if tid not in self.settled_background_tasks:
                            self.background_tasks.add(tid)
                            self.background_task_rows[tid] = ident
                            if is_agy_oneshot_timer(raw_outcome, tool_name, raw_params):
                                self.oneshot_timers.add(tid)
                                cond = agy_timer_condition(raw_params)
                                if cond:
                                    self.conditional_timers[tid] = cond
                            keep_running = True
                        else:
                            preserve_settled = True
                            if not cleaned_outcome and prev_tool_outcome:
                                cleaned_outcome = prev_tool_outcome
                    cur = self.rows.get(ident)
                    if cur:
                        updates = {}
                        final_outcome = (
                            _plan_tool_outcome(plan_kind, raw_params, cleaned_outcome, cur.get("status", ""))
                            if plan_kind
                            else cleaned_outcome
                        )
                        if plan_kind:
                            expected_title = _plan_tool_title(plan_kind)
                            if cur.get("text") != expected_title:
                                updates["text"] = expected_title
                            if cur.get("toolName") != plan_kind:
                                updates["toolName"] = plan_kind
                        if final_outcome != raw_outcome:
                            updates["outcome"] = final_outcome
                        if keep_running and cur.get("status") in ("completed", "succeeded"):
                            updates["status"] = "running"
                        elif preserve_settled and prev_tool_status in ("completed", "cancelled", "failed") and cur.get("status") != prev_tool_status:
                            updates["status"] = prev_tool_status
                        if updates:
                            self.row({**cur, **updates})
            with self.lock:
                if u.get("sessionUpdate") == "user_message_chunk" and self.chunk_id in getattr(self, "steered_submissions", ()):
                    self.steered_submissions.discard(self.chunk_id)
                has_unconsumed = bool(getattr(self, "pending_echo_submissions", None)) or bool(
                    self.current_submission and not self.config.get("consumedSubmissions", {}).get(self.current_submission)
                )
            if getattr(self, "history_loaded", False) and has_unconsumed:
                self._wake_transcript_tail()

    def _find_invoke_row_id(self, child_id, params):
        existing = self.subagents.get(child_id, {}).get("itemId", "")
        if existing and existing in self.rows:
            return existing
        claimed = {
            a.get("itemId")
            for cid, a in self.subagents.items()
            if cid != child_id and a.get("itemId")
        }
        candidates = [
            r for r in self.rows.values()
            if r.get("toolName") == "invoke_subagent" and r["id"] not in claimed
        ]
        wanted = (params.get("TypeName") or "", params.get("Role") or "", params.get("Prompt") or "")
        if any(wanted):
            for row in candidates:
                try:
                    raw = json.loads(row.get("input") or "{}")
                except (ValueError, TypeError):
                    raw = {}
                if (raw.get("TypeName") or "", raw.get("Role") or "", raw.get("Prompt") or "") == wanted:
                    return row["id"]
        return candidates[0]["id"] if candidates else ""

    def _find_swarm_row_id(self, child_id, entry):
        existing = self.subagents.get(child_id, {}).get("itemId", "")
        if existing and existing in self.rows:
            return existing
        if isinstance(entry, dict) and "params" in entry:
            params = entry.get("params") or {}
            siblings = entry.get("siblings") or {child_id}
        else:
            params = entry if isinstance(entry, dict) else {}
            siblings = {child_id}
        for sib in siblings:
            if sib != child_id:
                sib_item = self.subagents.get(sib, {}).get("itemId", "")
                if sib_item and sib_item in self.rows:
                    return sib_item
        claimed_by_others = {
            a.get("itemId")
            for cid, a in self.subagents.items()
            if cid not in siblings and a.get("itemId")
        }
        candidates = [
            r for r in self.rows.values()
            if r.get("toolName") == "swarm"
        ]
        for row in candidates:
            outcome = row.get("outcome") or ""
            if child_id and child_id in outcome:
                return row["id"]
        if params:
            for prefer_unclaimed in (True, False):
                for row in candidates:
                    if prefer_unclaimed and row["id"] in claimed_by_others:
                        continue
                    raw_in = row.get("input")
                    if not raw_in:
                        continue
                    try:
                        parsed = json.loads(raw_in)
                    except (ValueError, TypeError):
                        continue
                    if isinstance(parsed, dict) and parsed.get("action") == "add" and self._tool_params_match(params, parsed):
                        return row["id"]
        add_candidates = []
        for row in candidates:
            if row["id"] in claimed_by_others:
                continue
            raw_in = row.get("input")
            if not raw_in:
                add_candidates.append(row["id"])
                continue
            try:
                parsed = json.loads(raw_in)
            except (ValueError, TypeError):
                continue
            if isinstance(parsed, dict) and parsed.get("action") == "add":
                add_candidates.append(row["id"])
        if len(add_candidates) == 1:
            return add_candidates[0]
        return ""

    def _reanchor_subagents(self):
        with self.lock:
            if self.close_pending or self.released:
                return
            for child_id, agent in list(self.subagents.items()):
                if agent.get("itemId") and agent["itemId"] in self.rows:
                    continue
                if child_id in self.swarm_call_args:
                    matched = self._find_swarm_row_id(child_id, self.swarm_call_args[child_id])
                else:
                    matched = self._find_invoke_row_id(child_id, self.child_call_args.get(child_id, {}))
                if matched:
                    self.subagent(child_id, itemId=matched)

    def register_swarm_child(self, child_id, ident, turn, worker, params=None, siblings=None, step_index=None):
        with self.lock:
            self.swarm_call_args[child_id] = {
                "params": dict(params) if isinstance(params, dict) else {},
                "siblings": set(siblings) if siblings else {child_id},
            }
            old = self.subagents.get(child_id, {})
            anchor = ident or self._find_swarm_row_id(child_id, self.swarm_call_args[child_id])
            wname = worker.get("name", "") or old.get("name", "")
            wrole = worker.get("role", "") or old.get("role", "") or "swarm"
            wtask = worker.get("task", "") or old.get("task", "")
            wmodel = worker.get("model", "")
            fields = {
                "name": wname,
                "role": wrole,
                "task": wtask,
                "itemId": anchor,
                "turnId": turn or old.get("turnId", ""),
                "model": wmodel if isinstance(wmodel, str) and wmodel else (old.get("model") or "inherit"),
            }
            path = child_log_path(child_id, "")
            if path:
                fields["logPath"] = path
            if old.get("status", "unknown") == "unknown":
                fields["status"] = "running"
            self.subagent(child_id, **fields)
        if path:
            self.ensure_child_tail()

    def register_child(self, child_id, ident, turn, type_name, role, task, uri, model=""):
        with self.lock:
            saved_args = self.child_call_args.setdefault(child_id, {})
            if type_name:
                saved_args["TypeName"] = type_name
            if role:
                saved_args["Role"] = role
            if task:
                saved_args["Prompt"] = task
            if model:
                saved_args["Model"] = model
            old = self.subagents.get(child_id, {})
            anchor = ident or self._find_invoke_row_id(child_id, saved_args)
            display_name = role or type_name or old.get("name", "")
            display_role = (
                type_name
                if role and type_name and role != type_name
                else old.get("role", "")
            )
            fields = {
                "name": display_name,
                "role": display_role,
                "task": task or old.get("task", ""),
                "itemId": anchor,
                "turnId": turn,
                "model": model if isinstance(model, str) and model else (old.get("model") or "inherit"),
            }
            path = child_log_path(child_id, uri)
            if path:
                fields["logPath"] = path
            if old.get("status", "unknown") == "unknown":
                fields["status"] = "running"
            self.subagent(child_id, **fields)
        if path:
            self.ensure_child_tail()

    def restart_child_tail(self):
        self.tail_stop.set()
        self.tail_wake.set()
        self.tail_stop = threading.Event()
        self.tail_wake = threading.Event()
        self.tail_thread = threading.Thread(
            target=self.tail_children,
            args=(self.tail_stop, self.tail_wake),
            name="LumaAgyAcpChildLog",
            daemon=True,
        )
        self.tail_thread.start()

    def _record_autonomous_row(self, row_id, catchup_spec):
        spec = {**catchup_spec, "row_id": row_id} if catchup_spec else None
        if row_id not in self.autonomous_row_ids:
            self.autonomous_row_ids.append(row_id)
            if spec:
                self.autonomous_catchup.append(spec)
        elif spec:
            for existing in self.autonomous_catchup:
                if existing.get("row_id") == row_id:
                    for k in ("text", "name", "params"):
                        if k in spec:
                            existing[k] = spec[k]
                    break

    def _settle_background_task(self, tid, status="completed", outcome="", settled_at_ms=0):
        self.settled_background_tasks.add(tid)
        self.background_tasks.discard(tid)
        self.oneshot_timers.discard(tid)
        self.conditional_timers.pop(tid, None)
        rid = self.background_task_rows.pop(tid, "")
        if rid and rid in self.rows:
            cur = self.rows[rid]
            updates = {}
            if cur.get("status") in ("running", "in_progress", "pending"):
                updates["status"] = status
                if settled_at_ms > 0:
                    updates["completedAtMs"] = settled_at_ms
            if outcome:
                existing = (cur.get("outcome") or "").strip()
                updates["outcome"] = f"{existing}\n{outcome}" if existing and outcome not in existing else outcome
            if updates:
                self.row({**cur, **updates})

    def _note_manage_task(self, params, state, output):
        if not isinstance(params, dict):
            return
        action = params.get("Action")
        task_id = params.get("TaskId")
        lower_out = (output or "").lower()
        if action == "kill" and isinstance(task_id, str) and task_id:
            if state == "DONE" or "not running" in lower_out or "not found" in lower_out:
                for matched in (_matching_bg_task_ids(task_id, self.background_tasks) or [task_id]):
                    self._settle_background_task(matched, "cancelled")
        elif action == "status" and isinstance(task_id, str) and task_id and state == "DONE":
            if any(term in lower_out for term in ("completed", "finished", "exited", "not running", "not found", "cancelled", "canceled", "killed")):
                t_state = "cancelled" if ("cancel" in lower_out or "killed" in lower_out) else "completed"
                for matched in (_matching_bg_task_ids(task_id, self.background_tasks) or [task_id]):
                    self._settle_background_task(matched, t_state)
        elif action == "list" and state == "DONE":
            if "no running" in lower_out or "no background" in lower_out:
                for tid in list(self.background_tasks):
                    self._settle_background_task(tid, "completed")
                return
            try:
                parsed = json.loads(output or "")
            except (ValueError, TypeError):
                return
            if isinstance(parsed, list):
                live = {
                    str(entry.get("taskId") or entry.get("TaskId") or entry.get("id") or "")
                    for entry in parsed
                    if isinstance(entry, dict)
                }
                live.discard("")
                for tid in list(self.background_tasks):
                    if tid not in live and not any(tid.endswith("/" + l) or l.endswith("/" + tid) for l in live):
                        self._settle_background_task(tid, "completed")

    def _sync_autonomous_running(self):
        if not self.data.get("connected") or self.close_pending or self.released:
            return
        if self.autonomous_running and not self.data.get("running"):
            self.update(running=True)
        elif not self.autonomous_running and not self._has_active_prompt() and self.data.get("running"):
            self.update(running=False)

    def _resolve_transcript_media(self, raw_media):
        if not isinstance(raw_media, list) or not raw_media:
            return []
        resolved = []
        for entry in raw_media:
            if not isinstance(entry, dict):
                continue
            raw_uri = entry.get("uri") or entry.get("path") or ""
            if not isinstance(raw_uri, str) or not raw_uri:
                continue
            local_path = urllib.parse.unquote(raw_uri[7:]) if raw_uri.startswith("file://") else raw_uri
            raw_mime = (entry.get("mime_type") or entry.get("mimeType") or mimetypes.guess_type(local_path)[0] or "").split(";", 1)[0].strip().lower()
            norm_mime = "image/jpeg" if raw_mime == "image/jpg" else raw_mime
            p = pathlib.Path(local_path)
            if norm_mime in ("image/png", "image/jpeg", "image/webp", "image/gif") and p.is_file():
                try:
                    size = p.stat().st_size
                    if 0 < size <= 20 * 1024 * 1024:
                        raw_bytes = p.read_bytes()
                        block = {"type": "image", "data": base64.b64encode(raw_bytes).decode(), "mimeType": norm_mime}
                        resolved.extend(self.media([block]))
                        continue
                except OSError:
                    pass
            resolved.append({"source": local_path, "name": p.name, "mimeType": norm_mime or "application/octet-stream"})
        return resolved

    def session_attach_method(self, native, caps):
        can_resume = (
            "resume" in caps.get("sessionCapabilities", {})
            or "resume" in caps.get("session_capabilities", {})
        )
        if native and can_resume and _has_local_transcript(native):
            self._resumed_from_transcript = True
            return "session/resume"
        self._resumed_from_transcript = False
        return super().session_attach_method(native, caps)

    def refresh_parent_log(self, hydrate_history=False):
        with self.lock:
            if self.close_pending or self.released:
                return
            native_id = self.data.get("nativeId") or self.config.get("nativeId", "")
            path = child_log_path(native_id, "")
            if not path:
                return
            full_path = str(pathlib.Path(path).with_name("transcript_full.jsonl"))
            before_bg = set(self.background_tasks)
            full_handle = None
            full_eof = False
            full_peek = None

            def _next_full_step(target_index):
                nonlocal full_handle, full_eof, full_peek
                if type(target_index) is not int:
                    return None
                if full_peek is not None:
                    pidx = full_peek.get("step_index")
                    if pidx == target_index:
                        res = full_peek
                        full_peek = None
                        return res
                    if type(pidx) is int and pidx > target_index:
                        return read_full_step(full_path, target_index)
                    full_peek = None
                if full_handle is None and not full_eof:
                    try:
                        full_handle = open(full_path, "rb")
                    except OSError:
                        full_eof = True
                        return None
                if full_handle is not None and not full_eof:
                    while True:
                        fline = full_handle.readline()
                        if not fline or not fline.endswith(b"\n"):
                            full_eof = True
                            break
                        try:
                            fstep = json.loads(fline)
                        except ValueError:
                            continue
                        if not isinstance(fstep, dict):
                            continue
                        fidx = fstep.get("step_index")
                        if fidx == target_index:
                            return fstep
                        if type(fidx) is int and fidx > target_index:
                            full_peek = fstep
                            return read_full_step(full_path, target_index)
                return read_full_step(full_path, target_index)

            try:
                with open(path, "rb") as source:
                    if os.fstat(source.fileno()).st_size < self.parent_offset:
                        self.parent_offset = 0
                        self.parent_calls = []
                        self.parent_children.clear()
                        self.parent_turn_settled = False
                        self.background_tasks.clear()
                        self.settled_background_tasks.clear()
                        self.background_task_rows.clear()
                        self.oneshot_timers.clear()
                        self.conditional_timers.clear()
                        self.in_autonomous_turn = False
                        self.autonomous_running = False
                        self.autonomous_open_tools.clear()
                        self.swarm_groups.clear()
                        self.swarm_workers.clear()
                        self.swarm_call_args.clear()
                        self.swarm_revived_steps.clear()
                        self.swarm_stopped_steps.clear()
                        self.child_step_highs.clear()
                        self.handled_swarm_rows.clear()
                    source.seek(self.parent_offset)
                    while True:
                        line = source.readline()
                        if not line or not line.endswith(b"\n"):
                            break
                        self.parent_offset = source.tell()
                        try:
                            step = json.loads(line)
                        except ValueError:
                            continue
                        if not isinstance(step, dict):
                            continue
                        if step.get("truncated_fields"):
                            step = _next_full_step(step.get("step_index")) or step
                        kind = step.get("type")
                        state = step.get("status")
                        index = step.get("step_index")
                        step_ts = parse_ts_ms(step.get("created_at"))
                        if kind == "USER_INPUT":
                            self.parent_calls = []
                            self.parent_turn_settled = False
                            self.in_autonomous_turn = False
                            self.autonomous_running = False
                            self._settle_autonomous_open_tools("cancelled")
                            if hydrate_history:
                                user_body, has_req = _extract_agy_user_text(step.get("content"))
                                media_list = self._resolve_transcript_media(step.get("media"))
                                if has_req or user_body or media_list:
                                    self.row({
                                        **item(f"agy-hist:{index}:user", "user", user_body, status="completed", timestampMs=step_ts),
                                        **({"media": media_list} if media_list else {}),
                                        "origin": "native",
                                    })
                            elif getattr(self, "history_loaded", False):
                                user_body, has_req = _extract_agy_user_text(step.get("content"))
                                raw_media = step.get("media")
                                if has_req or user_body or raw_media:
                                    self._ensure_prompt_tracking()
                                    consumed_map = self.config.get("consumedSubmissions", {})
                                    deliveries = self.config.get("deliveries", {})
                                    candidates = []
                                    if self.current_submission and not consumed_map.get(self.current_submission):
                                        candidates.append(self.current_submission)
                                    for cid in list(self.pending_echo_submissions):
                                        if cid and cid not in candidates and not consumed_map.get(cid):
                                            candidates.append(cid)
                                    matched_id = None
                                    for cid in candidates:
                                        existing = self.rows.get(cid)
                                        delivery_payload = deliveries.get(cid, {}).get("payload", {})
                                        local_text = ((existing.get("text") if existing else None) or delivery_payload.get("text") or "").strip()
                                        body_stripped = user_body.strip()
                                        if (local_text and body_stripped and local_text == body_stripped) or (
                                            not local_text and not body_stripped and bool(raw_media)
                                        ):
                                            matched_id = cid
                                            break
                                    if matched_id:
                                        existing = self.rows.get(matched_id)
                                        delivery_payload = deliveries.get(matched_id, {}).get("payload", {})
                                        has_existing_media = bool(existing and existing.get("media")) or bool(delivery_payload.get("images"))
                                        media_list = [] if has_existing_media else self._resolve_transcript_media(raw_media)
                                        if matched_id in self.steered_submissions:
                                            self._admit_steered_submission_locked(matched_id, media_override=media_list, timestamp_ms=step_ts)
                                        else:
                                            if matched_id in self.pending_echo_submissions:
                                                self.pending_echo_submissions.remove(matched_id)
                                            if matched_id == self.current_submission:
                                                self.echoed_user_submission = True
                                            base_row = existing if existing is not None else item(
                                                matched_id,
                                                "user",
                                                delivery_payload.get("text") or user_body,
                                            )
                                            extra_media = {"media": media_list} if media_list else {}
                                            extra_ts = {"timestampMs": step_ts} if step_ts and not base_row.get("timestampMs") else {}
                                            self.row({**base_row, **extra_media, **extra_ts, "status": "completed", "origin": "native"})
                                            self.receipt(matched_id, "accepted")
                                            self.consumed(matched_id)
                        elif kind == "SYSTEM_MESSAGE":
                            sender, body = parse_system_message(step.get("content"))
                            if sender == "system" and "All your subagents" in body and "have been stopped" in body:
                                for cid in self.parent_children:
                                    if cid in self.subagents and self.subagents[cid].get("status") == "running":
                                        settled_msgs = _settle_terminal_child_messages(self.subagents[cid].get("messages", []), "cancelled")
                                        self.subagent(cid, status="cancelled", nativeStatus="server_restart", messages=settled_msgs)
                                for tid in list(self.background_tasks):
                                    self._settle_background_task(tid, "cancelled", settled_at_ms=step_ts)
                                self.in_autonomous_turn = False
                                self.autonomous_running = False
                                self._settle_autonomous_open_tools("cancelled")
                            else:
                                if sender and body.startswith(f'Task id "{sender}" '):
                                    bg_state = "cancelled" if _BG_CANCELLED_RE.match(body) else "completed"
                                    self._settle_background_task(sender, bg_state, extract_agy_task_result(body), settled_at_ms=step_ts)
                                elif sender and sender in self.oneshot_timers:
                                    self._settle_background_task(sender, "completed", settled_at_ms=step_ts)
                                elif self.conditional_timers and (sender or (body and not _SWARM_BOARD_PING_RE.match(body.strip()))):
                                    for ctid, cond in list(self.conditional_timers.items()):
                                        if cond.lower() == "any" or (sender and sender == cond):
                                            self._settle_background_task(ctid, "completed", settled_at_ms=step_ts)
                                for match in _CRON_CANCELLED_RE.finditer(body):
                                    self._settle_background_task(match.group(1).strip(), "completed", settled_at_ms=step_ts)
                                for match in _CRON_CANCELLED_MSG_RE.finditer(body):
                                    for matched in (_matching_bg_task_ids(match.group(1).strip(), self.background_tasks) or [match.group(1).strip()]):
                                        self._settle_background_task(matched, "completed", settled_at_ms=step_ts)
                                self._settle_autonomous_open_tools("cancelled")
                                if sender and body and not body.lstrip().startswith("[swarm]"):
                                    self.child_replies[sender] = body[:500]
                                    if sender in self.subagents and self.subagents[sender].get("status") == "succeeded":
                                        self.subagent(sender, detail=body[:500])
                                if (
                                    getattr(self, "history_loaded", False)
                                    and not self._has_active_prompt()
                                    and (self.parent_turn_settled or self.in_autonomous_turn)
                                ):
                                    if not self.in_autonomous_turn:
                                        self.autonomous_streamed = False
                                    self.in_autonomous_turn = True
                                    self.autonomous_running = True
                                self.parent_turn_settled = False
                        elif kind == "PLANNER_RESPONSE":
                            self._settle_autonomous_open_tools("cancelled")
                            if (
                                getattr(self, "history_loaded", False)
                                and not self._has_active_prompt()
                                and self.parent_turn_settled
                                and not self.in_autonomous_turn
                            ):
                                self.in_autonomous_turn = True
                                self.autonomous_streamed = False
                            calls = [c for c in step.get("tool_calls", []) if isinstance(c, dict)]
                            self.parent_calls = list(calls)
                            emit_rows = hydrate_history or (self.in_autonomous_turn and not self.autonomous_streamed)
                            row_prefix = "agy-hist" if hydrate_history else "agy-auto"
                            if emit_rows:
                                thinking = step.get("thinking")
                                if isinstance(thinking, str) and thinking.strip():
                                    rid = f"{row_prefix}:{index}:think"
                                    self.row({**item(rid, "reasoning", thinking, status="completed", kind="reasoning", timestampMs=step_ts, completedAtMs=step_ts), "origin": "native"})
                                    if not hydrate_history:
                                        self._record_autonomous_row(rid, {"kind": "reasoning", "text": thinking, "matched": ""})
                                reply = step.get("content")
                                if isinstance(reply, str) and reply.strip():
                                    rid = f"{row_prefix}:{index}:text"
                                    msg_status = "running" if state in ("RUNNING", "ACTIVE") else ("failed" if state == "ERROR" else "completed")
                                    self.row({**item(rid, "assistant", reply, status=msg_status, timestampMs=step_ts), "origin": "native"})
                                    if not hydrate_history:
                                        self._record_autonomous_row(rid, {"kind": "assistant", "text": reply, "matched": ""})
                                for num, call in enumerate(calls):
                                    cname = call.get("name") or "tool"
                                    params = transcript_args(call)
                                    rid = f"{row_prefix}:{index}:tool:{num}"
                                    plan_kind = _plan_tool_kind(cname)
                                    title = _plan_tool_title(plan_kind) if plan_kind else (
                                        params.get("toolSummary") or params.get("CommandLine") or params.get("Path") or params.get("query") or cname
                                    )
                                    tool_init_status = "failed" if state == "ERROR" else "running"
                                    init_outcome = _plan_tool_outcome(plan_kind, params, "", tool_init_status) if plan_kind else ""
                                    inferred_kind = {
                                        "run_command": "execute",
                                        "send_command_input": "execute",
                                        "read_file": "read",
                                        "view_file": "read",
                                        "list_dir": "read",
                                        "edit_file": "edit",
                                        "write_file": "edit",
                                        "grep_search": "search",
                                        "find_by_name": "search",
                                        "search_web": "search",
                                        "read_url_content": "fetch",
                                    }.get(cname, "")
                                    self.row({
                                        **item(rid, "tool", str(title), status=tool_init_status, kind="tool", timestampMs=step_ts),
                                        "toolName": plan_kind or cname,
                                        **({"toolKind": inferred_kind} if inferred_kind else {}),
                                        "input": json.dumps(params, ensure_ascii=False),
                                        **({"outcome": init_outcome} if init_outcome else {}),
                                        "origin": "native",
                                    })
                                    if not hydrate_history:
                                        self._record_autonomous_row(rid, {"kind": "tool", "name": cname, "params": params})
                                    self.autonomous_open_tools.append(rid)
                            if state in ("DONE", "ERROR", "CANCELLED") and not calls:
                                self.parent_turn_settled = not self._has_active_prompt()
                                if self.in_autonomous_turn:
                                    if self.autonomous_streamed:
                                        chunk_status = "failed" if state == "ERROR" else ("cancelled" if state == "CANCELLED" else "completed")
                                        self.finish_chunk(chunk_status)
                                    if not self._has_active_prompt():
                                        self._settle_turn_streamed_tools()
                                    self.in_autonomous_turn = False
                                    self.autonomous_running = False
                                    self._settle_autonomous_open_tools("failed" if state == "ERROR" else "cancelled")
                            else:
                                self.parent_turn_settled = False
                                if self.in_autonomous_turn:
                                    self.autonomous_running = True
                        elif kind == "GENERIC":
                            output = step.get("content") if isinstance(step.get("content"), str) else ""
                            cleaned_output = clean_agy_tool_output(output)
                            t_created, t_completed = extract_agy_tool_timestamps(output)
                            done_ts = t_completed or step_ts
                            head_call = self.parent_calls[0] if self.parent_calls else {}
                            head_name = head_call.get("name") or ""
                            head_params = transcript_args(head_call) if head_call else {}
                            tid = extract_agy_bg_task_id(output, head_name, generic_state=state)
                            tool_row_id = ""
                            if (hydrate_history or self.in_autonomous_turn) and self.autonomous_open_tools:
                                tool_row_id = self.autonomous_open_tools.pop(0)
                                old_tool = self.rows.get(tool_row_id)
                                if old_tool:
                                    if old_tool.get("status") in ("running", "in_progress", "pending") or (tid and tid not in self.settled_background_tasks):
                                        tool_status = "failed" if state == "ERROR" else ("running" if (tid and tid not in self.settled_background_tasks) or state in ("RUNNING", "ACTIVE") else "completed")
                                    else:
                                        tool_status = old_tool["status"]
                                    plan_kind = _plan_tool_kind(old_tool.get("toolName", ""))
                                    old_params = {}
                                    if plan_kind and old_tool.get("input"):
                                        try:
                                            parsed = json.loads(old_tool["input"])
                                            if isinstance(parsed, dict):
                                                old_params = parsed
                                        except (ValueError, TypeError):
                                            pass
                                    final_out = _plan_tool_outcome(plan_kind, old_params, cleaned_output, tool_status) if plan_kind else cleaned_output
                                    if not final_out and old_tool.get("outcome"):
                                        final_out = old_tool["outcome"]
                                    gen_media = self._resolve_transcript_media(step.get("media")) if hydrate_history and step.get("media") else []
                                    tool_updates = {
                                        "status": tool_status,
                                        "outcome": final_out,
                                        **({"media": gen_media} if gen_media else {}),
                                    }
                                    start_ts = old_tool.get("timestampMs") or t_created or step_ts
                                    if start_ts:
                                        tool_updates["timestampMs"] = start_ts
                                    if tool_status not in ("running", "in_progress", "pending") and done_ts:
                                        tool_updates["completedAtMs"] = done_ts
                                    self.row({**old_tool, **tool_updates})
                            if tid:
                                if tid not in self.settled_background_tasks:
                                    self.background_tasks.add(tid)
                                    if is_agy_oneshot_timer(output, head_name, head_params):
                                        self.oneshot_timers.add(tid)
                                        cond = agy_timer_condition(head_params)
                                        if cond:
                                            self.conditional_timers[tid] = cond
                                    if tool_row_id:
                                        self.background_task_rows[tid] = tool_row_id
                                    elif tid not in self.background_task_rows:
                                        claimed = set(self.background_task_rows.values())
                                        for r in self.rows.values():
                                            if (r.get("role") == "tool"
                                                    and r["id"] not in claimed
                                                    and r.get("toolName") in ("run_command", "schedule", "run_workflow", "")
                                                    and r.get("status") in ("running", "in_progress", "pending")):
                                                self.background_task_rows[tid] = r["id"]
                                                break
                            if self.parent_calls:
                                call = self.parent_calls.pop(0)
                                params = transcript_args(call)
                                cname = call.get("name")
                                if not tool_row_id and cname and (cleaned_output or cname in ("swarm", "post_message")):
                                    fallback_id = ""
                                    for r in self.rows.values():
                                        if r.get("role") != "tool" or r.get("toolName") != cname:
                                            continue
                                        needs_outcome = bool(cleaned_output) and not r.get("outcome")
                                        unhandled_swarm = cname in ("swarm", "post_message") and r["id"] not in self.handled_swarm_rows
                                        if not needs_outcome and not unhandled_swarm:
                                            continue
                                        raw_in = r.get("input")
                                        if not raw_in:
                                            r_params = {}
                                        else:
                                            try:
                                                parsed_in = json.loads(raw_in)
                                            except (ValueError, TypeError):
                                                continue
                                            if not isinstance(parsed_in, dict):
                                                continue
                                            r_params = parsed_in
                                        if self._tool_params_match(params, r_params):
                                            if needs_outcome:
                                                tool_row_id = r["id"]
                                                fb_updates = {"outcome": cleaned_output}
                                                if not r.get("timestampMs") and (t_created or step_ts):
                                                    fb_updates["timestampMs"] = t_created or step_ts
                                                if r.get("status") not in ("running", "in_progress", "pending") and done_ts:
                                                    fb_updates["completedAtMs"] = done_ts
                                                self.row({**r, **fb_updates})
                                                break
                                            if not fallback_id:
                                                fallback_id = r["id"]
                                    if not tool_row_id and fallback_id:
                                        tool_row_id = fallback_id
                                if cname == "manage_task":
                                    self._note_manage_task(params, state, output)
                                elif state == "DONE":
                                    if cname == "invoke_subagent":
                                        child_id, uri = invoke_child(output)
                                        if child_id:
                                            self.parent_children.add(child_id)
                                            self.register_child(
                                                child_id,
                                                tool_row_id,
                                                self.current_submission or getattr(self, "steer_submission", None) or "",
                                                params.get("TypeName") or "",
                                                params.get("Role") or "",
                                                params.get("Prompt") or "",
                                                uri,
                                                params.get("Model", ""),
                                            )
                                    elif cname == "manage_subagents":
                                        self.note_manage(params, self.parent_children)
                                    elif cname == "send_message":
                                        recipient = params.get("Recipient")
                                        if isinstance(recipient, str) and recipient in self.subagents:
                                            if self.subagents[recipient].get("nativeStatus") in ("manage_subagents", "server_restart", "process_exited"):
                                                self.subagent(recipient, status="running", nativeStatus="revived")
                                    elif cname == "swarm":
                                        if not tool_row_id or tool_row_id not in self.handled_swarm_rows:
                                            turn_id = self.rows.get(tool_row_id, {}).get("turnId") or self.current_submission or getattr(self, "steer_submission", None) or ""
                                            if self.note_swarm_tool(tool_row_id, turn_id, params, output, allow_live=getattr(self, "history_loaded", False)) and tool_row_id:
                                                self.handled_swarm_rows.add(tool_row_id)
                                    elif cname == "post_message":
                                        if not tool_row_id or tool_row_id not in self.handled_swarm_rows:
                                            if self.note_post_message(params, output, allow_revive=getattr(self, "history_loaded", False)) and tool_row_id:
                                                self.handled_swarm_rows.add(tool_row_id)
                        elif kind == "ERROR_MESSAGE":
                            err_raw = (
                                step.get("error")
                                if isinstance(step.get("error"), str) and step.get("error").strip()
                                else (step.get("content") if isinstance(step.get("content"), str) else "")
                            )
                            if (
                                getattr(self, "history_loaded", False)
                                and not self._has_active_prompt()
                                and (self.parent_turn_settled or self.in_autonomous_turn)
                            ):
                                if not self.in_autonomous_turn:
                                    self.autonomous_streamed = False
                                self.in_autonomous_turn = True
                            if (hydrate_history or (self.in_autonomous_turn and not self.autonomous_streamed)) and err_raw.strip():
                                err_text = err_raw if err_raw.lstrip().startswith("⚠") else f"\n⚠ {err_raw.strip()}\n"
                                row_prefix = "agy-hist" if hydrate_history else "agy-auto"
                                rid = f"{row_prefix}:{index}:error"
                                self.row({**item(rid, "assistant", err_text, status="failed", timestampMs=step_ts), "origin": "native"})
                                if not hydrate_history:
                                    self._record_autonomous_row(rid, {"kind": "assistant", "text": err_text, "matched": ""})
                            if re.search(r"\battempt\s+\d+\b", err_raw, re.I):
                                if self.in_autonomous_turn:
                                    self.autonomous_running = True
                            else:
                                self.parent_turn_settled = not self._has_active_prompt()
                                self.in_autonomous_turn = False
                                self.autonomous_running = False
                                self._settle_autonomous_open_tools("failed")
            except OSError:
                return
            finally:
                if full_handle is not None:
                    try:
                        full_handle.close()
                    except OSError:
                        pass
            if before_bg != self.background_tasks:
                self.seq += 1
            if getattr(self, "history_loaded", False):
                self._sync_autonomous_running()

    def refresh_child_log(self, ident, path):
        with self.lock:
            if self.close_pending or self.released:
                return
        steps = self.changed_transcript(path)
        if steps is None:
            return
        with self.lock:
            if self.close_pending or self.released:
                return
            reply_detail = self.child_replies.get(ident, "")
            self.apply_child_transcript(
                ident,
                steps,
                reply_detail=reply_detail,
                protected=("manage_subagents", "server_restart", "swarm:killed"),
            )

    def sync_transcripts_once(self, settle_exited=False, hydrate_history=False):
        with self.lock:
            if self.close_pending or self.released:
                return
        if hydrate_history:
            self.refresh_parent_log(hydrate_history=True)
        else:
            self.refresh_parent_log()
        self._reanchor_subagents()
        with self.lock:
            if self.close_pending or self.released:
                return
            jobs = [(ident, child.get("logPath")) for ident, child in self.subagents.items() if child.get("logPath")]
        for ident, path in jobs:
            with self.lock:
                if self.close_pending or self.released:
                    return
            self.refresh_child_log(ident, path)
        if settle_exited:
            with self.lock:
                if self.close_pending or self.released:
                    return
                self._clear_live_process_tasks(settle_rows=True)
                for ident, child in list(self.subagents.items()):
                    cstatus = child.get("status")
                    if cstatus in ("running", "pending", "unknown"):
                        settled_msgs = _settle_terminal_child_messages(child.get("messages", []), "cancelled")
                        self.subagent(ident, status="cancelled", nativeStatus="process_exited", messages=settled_msgs)
                    elif cstatus in ("closed", "cancelled", "failed"):
                        settled_msgs = _settle_terminal_child_messages(child.get("messages", []), cstatus)
                        if settled_msgs != child.get("messages", []):
                            self.subagent(ident, messages=settled_msgs)

    def tail_children(self, stop, wake):
        while not stop.is_set():
            wake.wait(0.25)
            wake.clear()
            if stop.is_set():
                return
            self.sync_transcripts_once()

    def _restore_native_options(self, is_new):
        wanted_model = self.config.get("selectedModel") or (self.config.get("model") if is_new else None)
        if wanted_model:
            catalog_models = {m["id"] for m in self.data["models"]}
            if is_new or wanted_model in catalog_models:
                if wanted_model != self.data["model"]:
                    AcpSession.configure(self, {"model": wanted_model})
                self.config["selectedModel"] = self.data["model"]
                self.save()
        wanted_effort = self.config.get("selectedEffort")
        efforts = next((m.get("efforts", []) for m in self.data["models"] if m["id"] == self.data["model"]), [])
        if not efforts:
            if "selectedEffort" in self.config:
                self.config.pop("selectedEffort", None)
                self.save()
        elif wanted_effort and wanted_effort in efforts and wanted_effort != self.data["effort"]:
            AcpSession.configure(self, {"effort": wanted_effort})
        mode_opt = self._primary_mode_option()
        if mode_opt is not None:
            native_modes = {v["value"] for v in self.flatten(mode_opt.get("options", []))}
            saved_base = self._saved_base_mode()
            if saved_base and saved_base in native_modes and saved_base != "plan":
                self.base_mode = saved_base
            saved_plan = self._saved_plan_mode()
            wanted_native = ""
            if saved_plan is True and "plan" in native_modes:
                wanted_native = "plan"
            elif saved_plan is False:
                wanted_native = self.base_mode or self._default_non_plan_mode(mode_opt)
            elif saved_base and saved_base in native_modes and not self.skip_permissions_active:
                wanted_native = saved_base
            if wanted_native and wanted_native in native_modes and wanted_native != self.native_mode:
                AcpSession.configure(self, {"permission": wanted_native, "permissionConfigId": mode_opt["id"]})
            legacy_plan = ("selectedPlanMode" not in self.config) and (
                self.config.get("selectedPermission") == "plan" or self.config.get("selectedNativeMode") == "plan"
            )
            if legacy_plan:
                self.config["selectedPlanMode"] = True
                self.config["selectedBaseMode"] = self.base_mode or self._default_non_plan_mode(mode_opt)
                self.config["selectedPermission"] = self._effective_agy_permission()
                try:
                    self.save()
                except OSError:
                    pass
        self.apply_options(self.options)

    def _connect_acp(self, is_new):
        self.history_loaded = False
        with self.lock:
            self._reset_parent_tracking()
            self.subagents.clear()
        try:
            AcpSession.connect(self)
            self._restore_native_options(is_new)
        except Exception:
            self._close_current_peer()
            raise
        hydrate = bool(getattr(self, "_resumed_from_transcript", False))
        self._resumed_from_transcript = False
        self.sync_transcripts_once(settle_exited=True, hydrate_history=hydrate)
        self.settle_incomplete_rows("unknown")
        self._refresh_agy_metrics()
        self.history_loaded = True
        self.restart_child_tail()
        self.update(
            connected=True,
            capabilities={**self.data["capabilities"], "steer": True},
            historyReady=True,
        )

    def _refresh_agy_metrics(self, acp_usage_dict=None):
        native_id = self.data.get("nativeId") or self.config.get("nativeId", "")
        model = self.data.get("model") or self.config.get("selectedModel") or self.config.get("model", "")
        rows = agy_session_metrics(native_id, model=model, acp_usage_dict=acp_usage_dict)
        if not rows and isinstance(acp_usage_dict, dict):
            rows = agy_acp_usage(acp_usage_dict, model=model)
        if rows:
            self.acp_metrics = []
            self.request_metrics = rows
            self.update(metrics=self.request_metrics)

    def apply_usage(self, update):
        self.acp_metrics = []
        self._refresh_agy_metrics(acp_usage_dict=update)

    def invalidate_context(self):
        self.acp_metrics = []
        self.request_metrics = [
            r for r in getattr(self, "request_metrics", [])
            if r.get("label") not in ("上下文", "Context")
        ]
        self.update(metrics=self.request_metrics)

    def refresh(self):
        self.sync_transcripts_once()
        self._refresh_agy_metrics()
        return {}

    def connect(self):
        self.update(historyReady=False)
        if self.config.get("nativeProtocol") != AGY_ACP_PROTOCOL:
            raise Rejected(tr("此会话不属于 Google 官方 agy ACP；请使用原入口恢复，不能切换协议或会话存储。",
                              "This session does not belong to official Google agy ACP; resume via its original entry without switching protocol or storage."))
        if self.config.get("creationUnknown") and not self.config.get("nativeId"):
            raise Rejected(tr("上次原生创建结果未知；请从 CLI 会话列表取得原 ID 后接入，不能自动再建",
                              "Previous native session creation result is unknown; attach using the original ID from CLI session list instead of recreating"))
        self.discover_binary()
        is_new = not bool(self.config.get("nativeId"))
        self._connect_acp(is_new)

    def _validate_effort_against_current_options(self, effort_value):
        effort_opt = next((o for o in self.options if o.get("category", o["id"]) in ("thought_level", "reasoning_effort")), None)
        if not effort_opt:
            raise Rejected(tr("CLI 未提供此设置接口", "CLI does not support this setting"))
        if effort_value not in {v["value"] for v in self.flatten(effort_opt.get("options", []))}:
            raise Rejected(tr("选项不在原生目录中", "Option is not in the native catalog"))

    def configure(self, p):
        if self.data["running"]:
            raise Rejected(tr("请等待回合结束再更改设置", "Wait for the current turn to finish before changing settings"))
        if p.get("speed"):
            return AcpSession.configure(self, p)
        if p.get("model") and p["model"] not in {m["id"] for m in self.data["models"]}:
            raise Rejected(tr("选项不在原生目录中", "Option is not in the native catalog"))
        target_perm = p.get("permission")
        if target_perm and target_perm not in {perm["id"] for perm in self.data["permissions"]}:
            raise Rejected(tr("选项不在原生目录中", "Option is not in the native catalog"))
        has_plan = "planMode" in p
        if has_plan and (not isinstance(p["planMode"], bool) or not self.data.get("planModeSupported")):
            raise Rejected(tr("当前 Antigravity 会话未提供独立规划模式切换", "Independent Plan mode switching is unavailable in this Antigravity session"))
        mode_opt = self._primary_mode_option()
        secondary_perm = False
        if target_perm == FULL_ACCESS:
            if p.get("permissionConfigId") and (mode_opt is None or p["permissionConfigId"] != mode_opt["id"]):
                raise Rejected(tr("选项不在原生目录中", "Option is not in the native catalog"))
        elif target_perm:
            target_group = self.permission_option(target_perm, p.get("permissionConfigId"))
            if mode_opt is None or target_group["id"] != mode_opt["id"]:
                if not has_plan:
                    return AcpSession.configure(self, p)
                secondary_perm = True

        cur_skip = bool(getattr(self, "skip_permissions_active", False))
        cur_plan = bool(self.data.get("planMode", False))
        cur_base = getattr(self, "base_mode", "") or self._default_non_plan_mode(mode_opt)
        next_skip = cur_skip
        next_plan = p["planMode"] if has_plan else cur_plan
        next_base = cur_base
        if target_perm and not secondary_perm:
            if target_perm == FULL_ACCESS:
                next_skip = True
            else:
                next_skip = False
                next_base = target_perm
        next_native = "plan" if next_plan else next_base
        need_relaunch = next_skip != cur_skip

        if p.get("effort") and not p.get("model") and not need_relaunch:
            self._validate_effort_against_current_options(p["effort"])

        if not need_relaunch:
            if p.get("model"):
                AcpSession.configure(self, {"model": p["model"]})
                self.config["selectedModel"] = self.data["model"]
            if p.get("effort"):
                self._validate_effort_against_current_options(p["effort"])
                AcpSession.configure(self, {"effort": p["effort"]})
                self.config["selectedEffort"] = self.data["effort"]
            if secondary_perm:
                AcpSession.configure(self, {"permission": target_perm, **({"permissionConfigId": p["permissionConfigId"]} if p.get("permissionConfigId") else {})})
            if (target_perm and not secondary_perm) or has_plan:
                native_modes = {v["value"] for v in self.flatten(mode_opt.get("options", []))} if mode_opt else set()
                if next_native and next_native in native_modes and next_native != self.native_mode:
                    AcpSession.configure(self, {"permission": next_native, "permissionConfigId": mode_opt["id"]})
                    if self.native_mode != next_native:
                        raise Rejected(tr("Antigravity 尚未确认模式切换", "Antigravity has not confirmed the mode change"))
                self.base_mode = next_base
                self.config["skipPermissions"] = next_skip
                if next_base:
                    self.config["selectedBaseMode"] = next_base
                if has_plan or "selectedPlanMode" in self.config:
                    self.config["selectedPlanMode"] = next_plan
                if next_native:
                    self.config["selectedNativeMode"] = next_native
                self.apply_options(self.options)
                self.config["selectedPermission"] = self.data["permission"]
            cur_efforts = next((m.get("efforts", []) for m in self.data["models"] if m["id"] == self.data["model"]), [])
            if not cur_efforts or not self.data.get("effort"):
                self.config.pop("selectedEffort", None)
            elif self.config.get("selectedEffort") and self.config["selectedEffort"] not in cur_efforts:
                self.config["selectedEffort"] = self.data["effort"]
            self.save()
            return {}

        prev_saved = {
            k: self.config.get(k)
            for k in ("selectedModel", "selectedEffort", "selectedPermission", "skipPermissions", "selectedBaseMode", "selectedPlanMode", "selectedNativeMode")
        }
        prev_rows = copy.deepcopy(self.rows)
        prev_subagents = copy.deepcopy(self.subagents)
        next_model = p.get("model") or self.config.get("selectedModel") or self.data["model"]
        next_effort = p.get("effort") or self.config.get("selectedEffort") or self.data["effort"]
        if next_model:
            self.config["selectedModel"] = next_model
        if next_effort:
            self.config["selectedEffort"] = next_effort
        self.config["skipPermissions"] = next_skip
        if next_base:
            self.config["selectedBaseMode"] = next_base
        self.config["selectedPlanMode"] = next_plan
        self.config["selectedNativeMode"] = next_native
        self.config["selectedPermission"] = FULL_ACCESS if next_skip else next_base
        self.save()

        self.update(historyReady=False)
        self._close_current_peer()
        with self.lock:
            self.rows.clear()
        try:
            self._connect_acp(is_new=False)
            if p.get("effort"):
                self._validate_effort_against_current_options(p["effort"])
                if self.data.get("effort") != p["effort"]:
                    AcpSession.configure(self, {"effort": p["effort"]})
                self.config["selectedEffort"] = self.data["effort"]
                self.save()
        except Exception:
            for k, v in prev_saved.items():
                if v is None:
                    self.config.pop(k, None)
                else:
                    self.config[k] = v
            try:
                self.save()
            except OSError:
                pass
            with self.lock:
                self.rows.clear()
                self.rows.update(prev_rows)
                self.subagents.clear()
                self.subagents.update(prev_subagents)
            self.update(connected=False, historyReady=True)
            raise
        return {}

    def can_steer(self, payload):
        if not super().can_steer(payload):
            return False
        if not self.data.get("running") or not self._has_active_prompt():
            return False
        if self.steer_submission is not None:
            return False
        if self.data.get("requests"):
            return False
        txt = (payload.get("text") or "").strip()
        if txt.startswith("/"):
            return False
        return True

    def validate_prompt(self, p):
        super().validate_prompt(p)
        command = p.get("text", "").strip().split(maxsplit=1)[0].lower() if p.get("text", "").strip() else ""
        if command in ("/clear", "/reset", "/new"):
            raise Rejected(tr("请使用新建会话；此命令会清空当前原生会话历史", "Start a new session instead; this command clears the current native session history"))

    def _steer_uncertain(self, ident, error):
        with self.lock:
            self.diagnostic("agy-acp-steer-result-unknown", error)
            self.update(error=tr(
                f"ACP 插话结果待确认（{type(error).__name__}）",
                f"ACP steer result pending confirmation ({type(error).__name__})",
            ))

    def prompt(self, p):
        ident = p["submissionId"]
        is_steer = bool(p.get("steer"))
        text_body = p.get("text", "")
        if not is_steer:
            command = text_body.strip().split(maxsplit=1)
            if command and command[0] in ("/compact", "/compress") and any(c.get("name") == command[0][1:] for c in self.commands):
                self.invalidate_context()
            self.sync_transcripts_once()
        blocks = ([{"type": "text", "text": text_body}] if text_body else []) + image_blocks(p.get("images", []))
        with self.lock:
            self._ensure_prompt_tracking()
            if is_steer:
                self.steer_submission = ident
                if ident not in self.active_prompts:
                    self.active_prompts.append(ident)
                self.steered_submissions.add(ident)
                if ident not in self.pending_echo_submissions:
                    self.pending_echo_submissions.append(ident)
                self.row(item(ident, "user", text_body, origin="local"))
            else:
                self.in_autonomous_turn = False
                self.autonomous_running = False
                self.parent_turn_settled = False
                self._settle_autonomous_open_tools("cancelled")
                if not self._has_active_prompt() and self.data.get("error") and getattr(self, "delivery_storage_error", None) is None:
                    self.update(error=None)
                self.preexisting_tool_ids = {k[5:] for k in self.rows if k.startswith("tool-")}
                self.echoed_user_submission = False
                self.current_submission = ident
                if ident not in self.active_prompts:
                    self.active_prompts.append(ident)
                self.prompt_wait_error = None
                self.chunk_kind = None
                self.update(running=True)
                self.row(item(ident, "user", text_body, origin="local"))
        self._wake_transcript_tail()

        def dispatch():
            try:
                params = {"sessionId": self.data["nativeId"], "prompt": blocks}
                if getattr(self, "initialize_info", {}).get("_meta", {}).get("lumaSubmissions", {}).get("protocol") == 1:
                    params["_meta"] = {"lumaSubmissionId": ident}
                result = self.call(
                    "session/prompt",
                    params,
                    timeout=self.prompt_wait_timeout,
                    on_uncertain=lambda err: (
                        self._steer_uncertain(ident, err)
                        if (is_steer and self.current_submission != ident)
                        else self.prompt_uncertain(ident, err)
                    ),
                )
                with self.lock:
                    if not isinstance(result, dict) or not isinstance(result.get("stopReason"), str) or not result["stopReason"]:
                        raise ValueError("ACP prompt response has no stopReason")
                    self._ensure_prompt_tracking()
                    if ident not in self.active_prompts and self.current_submission != ident and self.steer_submission != ident:
                        return
                    was_current = self.current_submission == ident
                    if ident in self.active_prompts:
                        self.active_prompts.remove(ident)
                    cleared_steer = False
                    if self.steer_submission == ident:
                        self.steer_submission = None
                        cleared_steer = True
                    has_other_current = self.current_submission is not None and not was_current
                    if self.active_prompts:
                        if not was_current:
                            self._admit_steered_submission_locked(ident)
                            if cleared_steer:
                                self.delivery_wake.set()
                            return
                        if is_steer or ident in self.steered_submissions:
                            self._admit_steered_submission_locked(ident)
                        self.receipt(ident, "accepted")
                        self.consumed(ident)
                        self.refresh_parent_log()
                        next_id = self.active_prompts[0]
                        self.current_submission = next_id
                        if self.steer_submission == next_id:
                            self.steer_submission = None
                            cleared_steer = True
                        self._admit_steered_submission_locked(next_id)
                        self.echoed_user_submission = True
                        self.update(running=True)
                        if cleared_steer:
                            self.delivery_wake.set()
                        return
                    if has_other_current:
                        self._admit_steered_submission_locked(ident)
                        if cleared_steer:
                            self.delivery_wake.set()
                        return
                    if is_steer or ident in self.steered_submissions:
                        self._admit_steered_submission_locked(ident)
                    self.apply_prompt_result(result)
                    self.receipt(ident, "accepted")
                    self.consumed(ident)
                    self.current_submission = None
                    self.steer_submission = None
                    cancelled = result["stopReason"] == "cancelled"
                    self.finish_chunk("cancelled" if cancelled else "completed")
                    if cancelled:
                        self.settle_incomplete_rows("cancelled")
                        self.pending_requests.clear()
                        self.update(requests=[])
                    error = self.data.get("error")
                    if error == self.prompt_wait_error:
                        error = None
                    self.prompt_wait_error = None
                    self.update(running=False, error=error)
            except Rejected as exc:
                with self.lock:
                    self._ensure_prompt_tracking()
                    was_current = self.current_submission == ident
                    if ident in self.active_prompts:
                        self.active_prompts.remove(ident)
                    self.steered_submissions.discard(ident)
                    if ident in self.pending_echo_submissions:
                        self.pending_echo_submissions.remove(ident)
                    cleared_steer = False
                    if self.steer_submission == ident:
                        self.steer_submission = None
                        cleared_steer = True
                    if (is_steer or not was_current) and not self.config.get("consumedSubmissions", {}).get(ident):
                        existing = self.rows.get(ident)
                        if existing and existing.get("origin") == "local":
                            self.rows.pop(ident, None)
                            self.touch()
                            self.seq += 1
                    self.receipt(ident, "rejected")
                    if self.active_prompts:
                        if was_current:
                            next_id = self.active_prompts[0]
                            self.current_submission = next_id
                            if self.steer_submission == next_id:
                                self.steer_submission = None
                                cleared_steer = True
                            self.update(running=True, error=str(exc))
                        else:
                            self.update(error=str(exc))
                        if cleared_steer:
                            self.delivery_wake.set()
                        return
                    if self.current_submission is not None and not was_current:
                        self.update(error=str(exc))
                        if cleared_steer:
                            self.delivery_wake.set()
                        return
                    self.current_submission = None
                    self.steer_submission = None
                    self.prompt_wait_error = None
                    self.finish_chunk("failed")
                    self.settle_incomplete_rows("unknown")
                    self.pending_requests.clear()
                    self.update(running=False, requests=[], error=str(exc))
            except Exception as exc:
                with self.lock:
                    uncertain_steer = is_steer and self.current_submission != ident
                if uncertain_steer:
                    self._steer_uncertain(ident, exc)
                else:
                    self.prompt_uncertain(ident, exc)

        threading.Thread(target=dispatch, daemon=True, name="LumaAgyAcpSteer" if is_steer else "LumaAgyAcpPrompt").start()
        return False

    def apply_prompt_result(self, result):
        with self.lock:
            self.autonomous_catchup.clear()
            self.catchup_tool_ids.clear()
            self.catchup_tool_rows.clear()
            self.in_autonomous_turn = False
            self.autonomous_running = False
            self._settle_autonomous_open_tools("cancelled")
            self.refresh_parent_log()
            if isinstance(result, dict) and result.get("stopReason") == "end_turn":
                self._settle_turn_streamed_tools()
            self.parent_turn_settled = True
        self._refresh_agy_metrics(result.get("usage"))

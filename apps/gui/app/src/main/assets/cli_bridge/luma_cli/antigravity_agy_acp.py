import copy
import json
import os
import pathlib
import re
import shutil
import subprocess
import threading
from .acp import AcpSession
from .antigravity import AntigravitySession
from .antigravity_cli import (
    AgyChildTailMixin,
    child_log_path,
    child_transcript_state,
    clean_agy_tool_output,
    extract_agy_task_result,
    invoke_child,
    parse_system_message,
    project_child_transcript,
    read_full_step,
    read_transcript,
    transcript_args,
)
from .base import display_content, item, text
from .external_browser import BrowserRelay
from .metrics import agy_acp_usage, agy_session_metrics
from .transport import Rejected

AGY_ACP_PROTOCOL = "antigravity-agy-acp"
FULL_ACCESS = "always-proceed"
FULL_ACCESS_OPTION = {
    "id": FULL_ACCESS,
    "label": "全权限（跳过审批）",
    "description": "启用原生 --dangerously-skip-permissions，自动批准工具与命令执行",
}
_BG_TASK_RE = re.compile(r"Tool is running as a background task with task id:\s*(\S+)")
_CRON_CANCELLED_RE = re.compile(r"^\[([^\]]+)\]\s+Cron schedule cancelled\b", re.M)
_BG_CANCELLED_RE = re.compile(r'^Task id "[^"]+" (?:was |has been )?(?:killed|cancell?ed)\b', re.I)

def _oneshot_timer(output, tool_name="", params=None):
    if "Task Description: Timer:" in (output or ""):
        return True
    params = params if isinstance(params, dict) else {}
    return tool_name == "schedule" and bool(params.get("DurationSeconds")) and not params.get("CronExpression")

class AntigravityAgyAcpSession(AgyChildTailMixin, AntigravitySession):
    argv = ["agy", "acp", "--stdio"]
    supports_forms = True
    defer_connected = True
    desired_model_on_create = False
    replay_release = True
    release_empty = True
    notice = ("Google 官方 agy ACP；共享当前环境 agy CLI 的原生登录、会话与子代理目录，原生 load 重放历史。"
              "支持原生模式切换与 --dangerously-skip-permissions 全权限跳过审批（保留提问表单）。"
              "无可管理待发队列、逐消息 Skill、MCP 清单或实时 TUI 接管。")

    def __init__(self, config, save):
        super().__init__(config, save)
        self.tail_stop = threading.Event()
        self.tail_wake = threading.Event()
        self.tail_thread = None
        self._reset_parent_tracking()

    def _reset_parent_tracking(self):
        self.transcript_versions = {}
        self.parent_offset = 0
        self.parent_calls = []
        self.parent_children = set()
        self.parent_turn_settled = False
        self.background_tasks = set()
        self.settled_background_tasks = set()
        self.background_task_rows = {}
        self.oneshot_timers = set()
        self.in_autonomous_turn = False
        self.autonomous_running = False
        self.autonomous_streamed = False
        self.autonomous_open_tools = []
        self.autonomous_row_ids = []
        self.autonomous_catchup = []
        self.catchup_tool_ids = set()
        self.child_replies = {}
        self.child_call_args = {}

    def update(self, **fields):
        with self.lock:
            if fields.get("running") is False and getattr(self, "autonomous_running", False) and self.data.get("connected"):
                fields["running"] = True
            super().update(**fields)

    def release_blocker(self):
        blocker = super().release_blocker()
        if blocker == "工具状态尚未确认结束" and self.background_tasks:
            return "后台任务尚未确认结束"
        if blocker:
            return blocker
        if self.autonomous_running or self.in_autonomous_turn:
            return "后台唤醒回合尚未结算"
        if self.background_tasks:
            return "后台任务尚未确认结束"
        return None

    def discover_binary(self):
        if not shutil.which(self.argv[0]):
            raise Rejected("未找到 agy；请在当前运行环境的普通终端安装 agy（1.5.0 及以上）并完成原生登录。")
        env = os.environ.copy()
        if self.config.get("browserConfig"):
            env["LUMA_BROWSER_CONFIG"] = self.config["browserConfig"]
        try:
            version = subprocess.run([self.argv[0], "--version"], env=env, cwd=self.config["cwd"],
                                     capture_output=True, timeout=10, check=False)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise Rejected("agy 版本探测失败；请在普通终端核对 agy 安装状态。") from exc
        match = re.search(rb"(\d+)\.(\d+)\.(\d+)", version.stdout)
        if version.returncode or not match or tuple(map(int, match.groups())) < (1, 5, 0):
            raise Rejected("当前 agy 版本未提供稳定官方 acp 接口；请升级至 1.5.0 或更新版本。")

    def start(self, argv):
        self.authenticating = False
        self.auth_cancelled = False
        self.auth_url_rejected = False
        self.skip_permissions_active = self.config.get("selectedPermission") == FULL_ACCESS
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
                raise Rejected("agy 尚未登录；请在当前运行环境的普通终端运行 agy acp --login（或 agy）完成原生登录后重连。") from None
            raise

    def apply_options(self, options):
        super().apply_options(options)
        permissions = list(self.data.get("permissions", []))
        if not any(p.get("id") == FULL_ACCESS for p in permissions):
            permissions.append(dict(FULL_ACCESS_OPTION))
        fields = {"permissions": permissions}
        if getattr(self, "skip_permissions_active", False):
            fields["permission"] = FULL_ACCESS
        self.update(**fields)

    def _clear_live_process_tasks(self):
        with self.lock:
            self.background_tasks.clear()
            self.settled_background_tasks.clear()
            self.background_task_rows.clear()
            self.oneshot_timers.clear()
            self.in_autonomous_turn = False
            self.autonomous_running = False
            self.autonomous_open_tools.clear()

    def exited(self, error):
        self.tail_stop.set()
        self.tail_wake.set()
        was_autonomous = False
        with self.lock:
            was_autonomous = self.autonomous_running and self.current_submission is None
            self._clear_live_process_tasks()
        super().exited(error)
        if was_autonomous:
            self.update(running=False)

    def finish_observer_close(self):
        self.tail_stop.set()
        self.tail_wake.set()
        self._clear_live_process_tasks()
        super().finish_observer_close()

    def stop(self):
        result = super().stop()
        with self.lock:
            if self.current_submission is None and (self.autonomous_running or self.in_autonomous_turn):
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
                return self._accept_catchup()
            if tc_id and f"tool-{tc_id}" in self.rows:
                old = self.rows[f"tool-{tc_id}"]
                if u.get("status") in ("completed", "failed", "cancelled") and old.get("status") in ("running", "in_progress", "pending"):
                    changes = {"status": u["status"]}
                    if "content" in u and not old.get("outcome"):
                        changes["outcome"] = display_content(u["content"])
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
        if (getattr(self, "skip_permissions_active", False)
                and e.get("method") == "session/update"
                and e.get("params", {}).get("update", {}).get("sessionUpdate") == "current_mode_update"):
            return
        if e.get("method") == "session/update":
            u = e.get("params", {}).get("update", {})
            kind = u.get("sessionUpdate")
            with self.lock:
                if kind in ("agent_message_chunk", "agent_thought_chunk", "tool_call", "tool_call_update"):
                    if getattr(self, "history_loaded", False) and (self.current_submission is None or self.in_autonomous_turn):
                        self.autonomous_streamed = True
                    elif self.current_submission is not None and (self.autonomous_catchup or self.catchup_tool_ids):
                        if self._consume_autonomous_catchup(u):
                            return
                        if kind in ("tool_call", "tool_call_update") or bool(text(u.get("content"))):
                            self.autonomous_catchup.clear()
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
                            self.current_submission or "",
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
                if row.get("status") in ("completed", "succeeded") and not row.get("outcome"):
                    if getattr(self, "history_loaded", False):
                        self.ensure_child_tail()
                        self.tail_wake.set()
                raw_outcome = row.get("outcome", "") or ""
                bg_match = _BG_TASK_RE.search(raw_outcome)
                cleaned_outcome = clean_agy_tool_output(raw_outcome)
                with self.lock:
                    keep_running = False
                    if bg_match:
                        tid = bg_match.group(1).strip()
                        if tid not in self.settled_background_tasks:
                            self.background_tasks.add(tid)
                            self.background_task_rows[tid] = ident
                            if _oneshot_timer(raw_outcome, tool_name, raw_params):
                                self.oneshot_timers.add(tid)
                            keep_running = True
                    cur = self.rows.get(ident)
                    if cur:
                        updates = {}
                        if cleaned_outcome != raw_outcome:
                            updates["outcome"] = cleaned_outcome
                        if keep_running and cur.get("status") in ("completed", "succeeded"):
                            updates["status"] = "running"
                        if updates:
                            self.row({**cur, **updates})

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

    def _reanchor_subagents(self):
        with self.lock:
            for child_id, agent in list(self.subagents.items()):
                if agent.get("itemId") and agent["itemId"] in self.rows:
                    continue
                matched = self._find_invoke_row_id(child_id, self.child_call_args.get(child_id, {}))
                if matched:
                    self.subagent(child_id, itemId=matched)

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

    def _settle_background_task(self, tid, status="completed", outcome=""):
        self.settled_background_tasks.add(tid)
        self.background_tasks.discard(tid)
        self.oneshot_timers.discard(tid)
        rid = self.background_task_rows.pop(tid, "")
        if rid and rid in self.rows:
            cur = self.rows[rid]
            updates = {}
            if cur.get("status") in ("running", "in_progress", "pending"):
                updates["status"] = status
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
                self._settle_background_task(task_id, "cancelled")
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
                    if tid not in live:
                        self._settle_background_task(tid, "completed")

    def _sync_autonomous_running(self):
        if not self.data.get("connected") or self.close_pending or self.released:
            return
        if self.autonomous_running and not self.data.get("running"):
            self.update(running=True)
        elif not self.autonomous_running and self.current_submission is None and self.data.get("running"):
            self.update(running=False)

    def refresh_parent_log(self):
        with self.lock:
            native_id = self.data.get("nativeId") or self.config.get("nativeId", "")
            path = child_log_path(native_id, "")
            if not path:
                return
            full_path = str(pathlib.Path(path).with_name("transcript_full.jsonl"))
            before_bg = set(self.background_tasks)
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
                        self.in_autonomous_turn = False
                        self.autonomous_running = False
                        self.autonomous_open_tools.clear()
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
                            step = read_full_step(full_path, step.get("step_index")) or step
                        kind = step.get("type")
                        state = step.get("status")
                        index = step.get("step_index")
                        if kind == "USER_INPUT":
                            self.parent_calls = []
                            self.parent_turn_settled = False
                            self.in_autonomous_turn = False
                            self.autonomous_running = False
                            self.autonomous_open_tools.clear()
                        elif kind == "SYSTEM_MESSAGE":
                            sender, body = parse_system_message(step.get("content"))
                            if sender == "system" and "All your subagents" in body and "have been stopped" in body:
                                for cid in self.parent_children:
                                    if cid in self.subagents and self.subagents[cid].get("status") == "running":
                                        self.subagent(cid, status="cancelled", nativeStatus="server_restart")
                                for tid in list(self.background_tasks):
                                    self._settle_background_task(tid, "cancelled")
                                self.in_autonomous_turn = False
                                self.autonomous_running = False
                                self.autonomous_open_tools.clear()
                            else:
                                if sender and body.startswith(f'Task id "{sender}" '):
                                    bg_state = "cancelled" if _BG_CANCELLED_RE.match(body) else "completed"
                                    self._settle_background_task(sender, bg_state, extract_agy_task_result(body))
                                elif sender and sender in self.oneshot_timers:
                                    self._settle_background_task(sender, "completed")
                                for match in _CRON_CANCELLED_RE.finditer(body):
                                    self._settle_background_task(match.group(1).strip(), "completed")
                                if sender and body:
                                    self.child_replies[sender] = body[:500]
                                    if sender in self.subagents and self.subagents[sender].get("status") == "succeeded":
                                        self.subagent(sender, detail=body[:500])
                                if (
                                    getattr(self, "history_loaded", False)
                                    and self.current_submission is None
                                    and (self.parent_turn_settled or self.in_autonomous_turn)
                                ):
                                    if not self.in_autonomous_turn:
                                        self.autonomous_streamed = False
                                    self.in_autonomous_turn = True
                                    self.autonomous_running = True
                                self.parent_turn_settled = False
                        elif kind == "PLANNER_RESPONSE":
                            if (
                                getattr(self, "history_loaded", False)
                                and self.current_submission is None
                                and self.parent_turn_settled
                                and not self.in_autonomous_turn
                            ):
                                self.in_autonomous_turn = True
                                self.autonomous_streamed = False
                            calls = [c for c in step.get("tool_calls", []) if isinstance(c, dict)]
                            self.parent_calls = list(calls)
                            if self.in_autonomous_turn and not self.autonomous_streamed:
                                thinking = step.get("thinking")
                                if isinstance(thinking, str) and thinking.strip():
                                    rid = f"agy-auto:{index}:think"
                                    self.row({**item(rid, "reasoning", thinking, status="completed", kind="reasoning"), "origin": "native"})
                                    self._record_autonomous_row(rid, {"kind": "reasoning", "text": thinking, "matched": ""})
                                reply = step.get("content")
                                if isinstance(reply, str) and reply.strip():
                                    rid = f"agy-auto:{index}:text"
                                    msg_status = "running" if state in ("RUNNING", "ACTIVE") else "completed"
                                    self.row({**item(rid, "assistant", reply, status=msg_status), "origin": "native"})
                                    self._record_autonomous_row(rid, {"kind": "assistant", "text": reply, "matched": ""})
                                for num, call in enumerate(calls):
                                    cname = call.get("name") or "tool"
                                    params = transcript_args(call)
                                    rid = f"agy-auto:{index}:tool:{num}"
                                    title = params.get("toolSummary") or params.get("CommandLine") or params.get("Path") or cname
                                    self.row({
                                        **item(rid, "tool", str(title), status="running", kind="tool"),
                                        "toolName": cname,
                                        "input": json.dumps(params, ensure_ascii=False),
                                        "origin": "native",
                                    })
                                    self._record_autonomous_row(rid, {"kind": "tool", "name": cname, "params": params})
                                    self.autonomous_open_tools.append(rid)
                            if state == "DONE" and not calls:
                                self.parent_turn_settled = self.current_submission is None
                                if self.in_autonomous_turn:
                                    if self.autonomous_streamed:
                                        self.finish_chunk("completed")
                                    self.in_autonomous_turn = False
                                    self.autonomous_running = False
                                    self.autonomous_open_tools.clear()
                            else:
                                self.parent_turn_settled = False
                                if self.in_autonomous_turn:
                                    self.autonomous_running = True
                        elif kind == "GENERIC":
                            output = step.get("content") if isinstance(step.get("content"), str) else ""
                            cleaned_output = clean_agy_tool_output(output)
                            bg_match = _BG_TASK_RE.search(output)
                            tool_row_id = ""
                            if self.in_autonomous_turn and not self.autonomous_streamed and self.autonomous_open_tools:
                                tool_row_id = self.autonomous_open_tools.pop(0)
                                old_tool = self.rows.get(tool_row_id)
                                if old_tool:
                                    tool_status = "failed" if state == "ERROR" else ("running" if bg_match else "completed")
                                    self.row({**old_tool, "status": tool_status, "outcome": cleaned_output})
                            if bg_match:
                                tid = bg_match.group(1).strip()
                                if tid not in self.settled_background_tasks:
                                    self.background_tasks.add(tid)
                                    head_call = self.parent_calls[0] if self.parent_calls else {}
                                    if _oneshot_timer(output, head_call.get("name") or "", transcript_args(head_call) if head_call else {}):
                                        self.oneshot_timers.add(tid)
                                    if tool_row_id:
                                        self.background_task_rows[tid] = tool_row_id
                                    elif tid not in self.background_task_rows:
                                        claimed = set(self.background_task_rows.values())
                                        for r in self.rows.values():
                                            if (r.get("role") == "tool"
                                                    and r["id"] not in claimed
                                                    and r.get("toolName") in ("run_command", "schedule", "")
                                                    and r.get("status") in ("running", "in_progress", "pending")):
                                                self.background_task_rows[tid] = r["id"]
                                                break
                            if self.parent_calls:
                                call = self.parent_calls.pop(0)
                                params = transcript_args(call)
                                cname = call.get("name")
                                if cleaned_output and not tool_row_id and cname:
                                    for r in self.rows.values():
                                        if r.get("role") == "tool" and r.get("toolName") == cname and not r.get("outcome"):
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
                                                tool_row_id = r["id"]
                                                self.row({**r, "outcome": cleaned_output})
                                                break
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
                                                self.current_submission or "",
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
                        elif kind == "ERROR_MESSAGE":
                            err_raw = (
                                step.get("error")
                                if isinstance(step.get("error"), str) and step.get("error").strip()
                                else (step.get("content") if isinstance(step.get("content"), str) else "")
                            )
                            if (
                                getattr(self, "history_loaded", False)
                                and self.current_submission is None
                                and (self.parent_turn_settled or self.in_autonomous_turn)
                            ):
                                if not self.in_autonomous_turn:
                                    self.autonomous_streamed = False
                                self.in_autonomous_turn = True
                            if self.in_autonomous_turn and not self.autonomous_streamed and err_raw.strip():
                                err_text = err_raw if err_raw.lstrip().startswith("⚠") else f"\n⚠ {err_raw.strip()}\n"
                                rid = f"agy-auto:{index}:error"
                                self.row({**item(rid, "assistant", err_text, status="failed"), "origin": "native"})
                                self._record_autonomous_row(rid, {"kind": "assistant", "text": err_text, "matched": ""})
                            if re.search(r"\battempt\s+\d+\b", err_raw, re.I):
                                if self.in_autonomous_turn:
                                    self.autonomous_running = True
                            else:
                                self.parent_turn_settled = self.current_submission is None
                                self.in_autonomous_turn = False
                                self.autonomous_running = False
                                self.autonomous_open_tools.clear()
            except OSError:
                return
            if before_bg != self.background_tasks:
                self.seq += 1
            if getattr(self, "history_loaded", False):
                self._sync_autonomous_running()

    def refresh_child_log(self, ident, path):
        steps = self.changed_transcript(path)
        if steps is None:
            return
        messages = project_child_transcript(ident, steps)
        state = child_transcript_state(steps)
        detail = ""
        with self.lock:
            reply_detail = self.child_replies.get(ident, "")
        if state == "idle" and reply_detail:
            detail = reply_detail
        else:
            for message in reversed(messages):
                if message.get("role") == "assistant" and message.get("text"):
                    detail = message["text"][:500]
                    break
                if message.get("status") == "running" and message.get("toolName"):
                    detail = message["toolName"]
                    break
            if not detail and reply_detail:
                detail = reply_detail
        with self.lock:
            old = self.subagents.get(ident)
            if not old:
                return
            fields = {"messages": messages, "detail": detail}
            if state and old.get("nativeStatus") not in ("manage_subagents", "server_restart"):
                fields.update(
                    status="succeeded" if state == "idle" else state,
                    nativeStatus="transcript:" + state,
                )
            if all(old.get(key) == value for key, value in fields.items()):
                return
            self.subagent(ident, **fields)

    def sync_transcripts_once(self, settle_exited=False):
        self.refresh_parent_log()
        self._reanchor_subagents()
        with self.lock:
            jobs = [(ident, child.get("logPath")) for ident, child in self.subagents.items() if child.get("logPath")]
        for ident, path in jobs:
            self.refresh_child_log(ident, path)
        if settle_exited:
            self._clear_live_process_tasks()
            with self.lock:
                for ident, child in list(self.subagents.items()):
                    if child.get("status") in ("running", "pending", "unknown"):
                        self.subagent(ident, status="cancelled", nativeStatus="process_exited")

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
        if wanted_effort and wanted_effort in efforts and wanted_effort != self.data["effort"]:
            AcpSession.configure(self, {"effort": wanted_effort})
        wanted_perm = self.config.get("selectedPermission")
        if wanted_perm and wanted_perm != FULL_ACCESS:
            native_modes = {p["id"] for p in self.data["permissions"] if p["id"] != FULL_ACCESS}
            if wanted_perm in native_modes and wanted_perm != self.data["permission"]:
                AcpSession.configure(self, {"permission": wanted_perm})

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
        self.settle_incomplete_rows("completed")
        self.sync_transcripts_once(settle_exited=True)
        self._refresh_agy_metrics()
        self.history_loaded = True
        self.restart_child_tail()
        self.update(connected=True, notice=self.notice, historyReady=True)

    def _refresh_agy_metrics(self, acp_usage_dict=None):
        native_id = self.data.get("nativeId") or self.config.get("nativeId", "")
        model = self.data.get("model") or self.config.get("selectedModel") or self.config.get("model", "")
        rows = agy_session_metrics(native_id, model=model, acp_usage_dict=acp_usage_dict)
        if not rows and isinstance(acp_usage_dict, dict):
            rows = agy_acp_usage(acp_usage_dict, model=model)
        if rows:
            self.request_metrics = rows
            self.update(metrics=getattr(self, "acp_metrics", []) + self.request_metrics)

    def refresh(self):
        self.sync_transcripts_once()
        self._refresh_agy_metrics()
        return {}

    def connect(self):
        self.update(notice=self.notice, historyReady=False)
        if self.config.get("nativeProtocol") != AGY_ACP_PROTOCOL:
            raise Rejected("此会话不属于 Google 官方 agy ACP；请使用原入口恢复，不能切换协议或会话存储。")
        if self.config.get("creationUnknown") and not self.config.get("nativeId"):
            raise Rejected("上次原生创建结果未知；请从 CLI 会话列表取得原 ID 后接入，不能自动再建")
        self.discover_binary()
        is_new = not bool(self.config.get("nativeId"))
        self._connect_acp(is_new)

    def configure(self, p):
        if self.data["running"]:
            raise Rejected("请等待回合结束再更改设置")
        if p.get("speed"):
            return AcpSession.configure(self, p)
        if p.get("model") and p["model"] not in {m["id"] for m in self.data["models"]}:
            raise Rejected("选项不在原生目录中")
        if p.get("effort"):
            effort_opt = next((o for o in self.options if o.get("category", o["id"]) in ("thought_level", "reasoning_effort")), None)
            if not effort_opt:
                raise Rejected("CLI 未提供此设置接口")
            if p["effort"] not in {v["value"] for v in self.flatten(effort_opt.get("options", []))}:
                raise Rejected("选项不在原生目录中")
        target_perm = p.get("permission")
        if target_perm and target_perm not in {perm["id"] for perm in self.data["permissions"]}:
            raise Rejected("选项不在原生目录中")

        need_relaunch = bool(target_perm) and ((target_perm == FULL_ACCESS) != getattr(self, "skip_permissions_active", False))
        if not need_relaunch:
            acp_patch = {k: v for k, v in p.items() if k in ("model", "effort") or (k == "permission" and v != FULL_ACCESS)}
            if acp_patch:
                AcpSession.configure(self, acp_patch)
            if p.get("model"):
                self.config["selectedModel"] = self.data["model"]
            if p.get("effort"):
                self.config["selectedEffort"] = self.data["effort"]
            if target_perm:
                self.config["selectedPermission"] = target_perm
                if target_perm == FULL_ACCESS:
                    self.update(permission=FULL_ACCESS)
            self.save()
            return {}

        prev_saved = {k: self.config.get(k) for k in ("selectedModel", "selectedEffort", "selectedPermission")}
        prev_rows = copy.deepcopy(self.rows)
        prev_subagents = copy.deepcopy(self.subagents)
        next_model = p.get("model") or self.config.get("selectedModel") or self.data["model"]
        next_effort = p.get("effort") or self.config.get("selectedEffort") or self.data["effort"]
        if next_model:
            self.config["selectedModel"] = next_model
        if next_effort:
            self.config["selectedEffort"] = next_effort
        self.config["selectedPermission"] = target_perm
        self.save()

        self.update(historyReady=False)
        self._close_current_peer()
        with self.lock:
            self.rows.clear()
        try:
            self._connect_acp(is_new=False)
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

    def validate_prompt(self, p):
        super().validate_prompt(p)
        command = p.get("text", "").strip().split(maxsplit=1)[0].lower() if p.get("text", "").strip() else ""
        if command in ("/clear", "/reset", "/new"):
            raise Rejected("请使用新建会话；此命令会清空当前原生会话历史")

    def prompt(self, p):
        self.sync_transcripts_once()
        with self.lock:
            self.in_autonomous_turn = False
            self.autonomous_running = False
            self.parent_turn_settled = False
            self.autonomous_open_tools.clear()
        return super().prompt(p)

    def apply_prompt_result(self, result):
        with self.lock:
            self.autonomous_catchup.clear()
            self.catchup_tool_ids.clear()
            self.in_autonomous_turn = False
            self.autonomous_running = False
            self.autonomous_open_tools.clear()
            self.refresh_parent_log()
            self.parent_turn_settled = True
        self._refresh_agy_metrics(result.get("usage"))

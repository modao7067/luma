import json
import mimetypes
import os
import re
import subprocess
import time
import unicodedata
import uuid
from pathlib import Path
from .base import Session, item, text, user_text
from .i18n import tr
from .transport import Rejected
from .metrics import cache_ratio, claude_result, claude_context, estimate_cost, number
from .claude_history import (_sanitize_path, _parse_transcript_entries, _build_conversation_chain,
                             _is_visible_message, _is_internal_message, _is_human_turn)

def _permission_modes():
    return [
        {"id": "default", "label": tr("逐次审批", "Ask each time")},
        {"id": "acceptEdits", "label": tr("自动接受编辑", "Auto-accept edits")},
        {"id": "auto", "label": tr("自动审批", "Auto-approve")},
        {"id": "plan", "label": tr("规划模式", "Plan mode")},
        {"id": "dontAsk", "label": tr("不询问（自动拒绝）", "Don't ask (auto-deny)")},
        {"id": "bypassPermissions", "label": tr("跳过审批", "Bypass permissions")},
    ]

def permission_options(help_output, is_root):
    modes = {"default", "acceptEdits", "plan"}
    block = re.search(r"--permission-mode\b(?:(?!\n\s+--)[\s\S])*", help_output)
    choices = re.search(r"\(choices:\s*([^)]*)\)", block[0]) if block else None
    if choices: modes.update(re.findall(r'"([^"]+)"', choices[1]))
    bypass = "bypassPermissions" in modes and "--allow-dangerously-skip-permissions" in help_output and not is_root
    return [dict(mode) for mode in _permission_modes() if mode["id"] in modes and
            (mode["id"] != "bypassPermissions" or bypass)], bypass

def canonical_permission(value):
    return "default" if value == "manual" else value

class ClaudeSession(Session):
    def __init__(self, config, save):
        super().__init__(config, save)
        self.permission_modes, self.allow_bypass = permission_options("", False)
        self.background_tasks = {}
        self.autonomous_running = False
        self.last_task_completed_at = None

    def release_mode(self):
        return "process" if self.data.get("nativeId") and self.history_path().is_file() else None

    def fast_release_blocker(self):
        with self.lock:
            blocker = super().fast_release_blocker()
            if blocker in ("工具状态尚未确认结束", "Tool status has not been confirmed finished") and getattr(self, "background_tasks", None):
                return tr("后台任务尚未确认结束", "Background task has not been confirmed finished")
            if blocker:
                return blocker
            last_completed = getattr(self, "last_task_completed_at", None)
            if getattr(self, "autonomous_running", False) or (last_completed is not None and time.monotonic() - last_completed < 5.0):
                return tr("后台唤醒回合尚未结算", "Background wake-up turn has not settled")
            if getattr(self, "background_tasks", None):
                return tr("后台任务尚未确认结束", "Background task has not been confirmed finished")
            return None

    def release_blocker(self):
        return super().release_blocker()

    def exited(self, error):
        getattr(self, "background_tasks", {}).clear()
        was_auto = getattr(self, "autonomous_running", False) and getattr(self, "current_submission", None) is None
        self.autonomous_running = False
        self.last_task_completed_at = None
        super().exited(error)
        if was_auto:
            self.update(running=False)

    def finish_observer_close(self):
        getattr(self, "background_tasks", {}).clear()
        self.autonomous_running = False
        self.last_task_completed_at = None
        super().finish_observer_close()

    input_evidence_protocol = 1
    dialect = "claude"
    permission_modes, allow_bypass = permission_options("", False)
    permission_revision = 0

    def detect_permissions(self):
        try:
            result = subprocess.run(["claude", "--help"], cwd=self.config["cwd"], capture_output=True,
                                    encoding="utf-8", errors="replace", timeout=10)
            help_output = result.stdout if result.returncode == 0 else ""
        except (OSError, subprocess.TimeoutExpired):
            help_output = ""
        self.permission_modes, self.allow_bypass = permission_options(help_output, os.geteuid() == 0)

    def validate_permission(self, value):
        if value == "bypassPermissions" and os.geteuid() == 0:
            raise Rejected(tr("Claude Code 原生禁止 Root/sudo 使用“跳过审批”（bypassPermissions）",
                              "Claude Code natively forbids `bypassPermissions` when running as root/sudo"))
        if value not in {v["id"] for v in self.permission_modes}:
            raise Rejected(tr("当前 Claude Code 未提供此原生权限模式：", "Current Claude Code does not offer this native permission mode: ") + value)

    def set_native_permission(self, value):
        result = self.call("set_permission_mode", {"mode": value})
        actual = canonical_permission(result.get("mode", value))
        if actual != value:
            self.update(permission=actual)
            raise Rejected(tr("Claude Code 未切换到所选权限模式；原生当前值：", "Claude Code did not switch to the selected permission mode; native value: ") + str(actual))
        return actual

    def model_id(self, actual):
        catalog = getattr(self, "native_models", [])
        for wanted in (self.config.get("selectedModel"), self.data["model"], actual):
            if wanted and any(m.get("value") == wanted and actual in (wanted, m.get("resolvedModel")) for m in catalog):
                return wanted
        return next((m["value"] for m in catalog if m.get("resolvedModel") == actual), actual)

    def read_settings(self, optional=False):
        try:
            result = self.call("get_settings")
        except Rejected as exc:
            if optional and any(word in str(exc).lower() for word in ("unknown", "unsupported", "not supported", "unrecognized")):
                return False
            raise
        applied = result.get("applied")
        if not isinstance(applied, dict): return False
        fields = {}
        if isinstance(applied.get("model"), str): fields["model"] = self.model_id(applied["model"])
        if "effort" in applied: fields["effort"] = applied["effort"] or ""
        self.update(**fields)
        return "effort" in applied

    def validate_effort(self, value, model):
        entry = next((m for m in self.data["models"] if m["id"] == model), {})
        if value not in entry.get("efforts", []):
            raise Rejected(tr("当前 Claude Code 模型未提供此思考强度：", "Current Claude Code model does not support this effort level: ") + value)

    def set_native_effort(self, value):
        self.call("apply_flag_settings", {"settings": {"effortLevel": value}})
        if not self.read_settings() or self.data["effort"] != value:
            raise Rejected(tr("Claude Code 未应用所选思考强度；原生当前值：", "Claude Code did not apply the selected effort level; native value: ") + self.data["effort"])

    def history_path(self):
        home = Path(unicodedata.normalize("NFC", os.environ.get("CLAUDE_CONFIG_DIR", str(Path.home() / ".claude"))))
        return home / "projects" / _sanitize_path(str(Path(self.config["cwd"]).resolve())) / (self.data["nativeId"] + ".jsonl")

    def connect(self):
        self.usage_message = {}
        self.usage_models = {}
        self.usage_rows = []
        self.current_submission = None
        self.stream_id = None
        self.stream_blocks = {}
        self.finalized_blocks = set()
        self.commands = set()
        self.submitted_user_text = {}
        self.background_tasks = {}
        self.autonomous_running = False
        self.last_task_completed_at = None
        native = self.config.get("nativeId")
        if native and not re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", native):
            raise Rejected(tr("Claude Code 会话 ID 必须是原生 UUID", "Claude Code session ID must be a native UUID"))
        if not native:
            native = str(uuid.uuid4())
            self.update(nativeId=native)
        path = self.history_path()
        if not path.exists() and self.config.get("hasNativeHistory"):
            raise Rejected(tr("Claude Code 原生会话文件缺失，无法恢复；请在终端核对，不能自动建立替代会话",
                              "Claude Code native session file is missing and cannot be resumed; check in terminal instead of creating a replacement session"))
        self.detect_permissions()
        selected_permission = self.config.get("selectedPermission")
        if selected_permission: self.validate_permission(selected_permission)
        argv = ["claude", "--print", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                "--permission-prompt-tool", "stdio", "--include-partial-messages", "--replay-user-messages"]
        if self.allow_bypass: argv += ["--allow-dangerously-skip-permissions"]
        argv += ["--resume" if path.exists() else "--session-id", native]
        for key, flag in (("selectedModel", "--model"), ("selectedPermission", "--permission-mode")):
            if self.config.get(key): argv += [flag, self.config[key]]
        self.load_history()
        self.start(argv)
        info = self.call("initialize", {"hooks": None})
        current_permission = canonical_permission(info.get("current_permission_mode", ""))
        if selected_permission and current_permission != selected_permission:
            current_permission = self.set_native_permission(selected_permission)
        self.commands = {c["name"] for c in info.get("commands", []) if c.get("name")}
        self.native_models = info.get("models", [])
        supports_settings = self.read_settings(optional=True)
        models = [{"id": m["value"], "label": m.get("displayName", m["value"]),
                   "supportsImages": False,
                   "efforts": list(dict.fromkeys(e for e in m.get("supportedEffortLevels", []) if isinstance(e, str)))
                       if supports_settings and m.get("supportsEffort") is not False else []}
                  for m in self.native_models]
        self.update(models=models)
        selected_effort = self.config.get("selectedEffort")
        if selected_effort:
            self.validate_effort(selected_effort, self.data["model"])
            self.set_native_effort(selected_effort)
        self.update(connected=True, models=models, permission=current_permission,
                    permissions=self.permission_modes, capabilities={"queue": False, "steer": False, "stop": True,
                    "images": False, "skills": bool(self.data["skills"]), "maxSkills": 1,
                    "compact": "compact" in self.commands},
                    notice=tr("使用 Claude Code 原生 stream-json 审批与权限；历史读取原生会话链。无可管理待发队列、运行中引导或实时 TUI 接管。模型目录未声明图片能力；思考强度取原生模型目录并通过会话级设置读回；速度保留原生配置。Skill 在 CLI 初始化事件返回清单后可逐消息调用。",
                              "Uses Claude Code native stream-json approvals and permissions; history reads native session transcripts. No managed queue, live steering, or live TUI takeover. Image capability is not advertised; effort comes from the native model catalog; speed keeps native config. Skills can be attached per message once reported by CLI init."))
        self.refresh_mcp()

    def _restore_unflushed_accepted_rows(self, prev_rows, transcript_uuids):
        receipts = self.config.get("receipts", {})
        submitted_texts = getattr(self, "submitted_user_text", {})
        deliveries = self.config.get("deliveries", {})
        for uid, state in receipts.items():
            if state != "accepted" or uid in transcript_uuids:
                continue
            user_row_id = f"{uid}:0"
            if user_row_id in prev_rows:
                started = False
                for rk, rv in prev_rows.items():
                    if rk == user_row_id:
                        started = True
                    if started and rk not in self.rows:
                        self.row(rv)
            else:
                body = submitted_texts.get(uid)
                payload = deliveries.get(uid, {}).get("payload", {})
                images = payload.get("images", []) if isinstance(payload, dict) else []
                if body is None and isinstance(payload, dict) and isinstance(payload.get("text"), str):
                    body = payload["text"]
                    skills = payload.get("skills") or []
                    if skills:
                        body = f"/{skills[0]} {body}".strip()
                if (body is not None or images) and user_row_id not in self.rows:
                    media = [{"source": p, "mimeType": mimetypes.guess_type(p)[0] or "image/*"} for p in images] if images else []
                    self.row(item(user_row_id, "user", body or "", media=media, status="completed", origin="native"))

    def load_history(self):
        path = self.history_path()
        if not path.is_file():
            with self.lock:
                self._restore_unflushed_accepted_rows(dict(self.rows), set())
            return
        if path.stat().st_size > 64 * 1024 * 1024: raise Rejected(tr("Claude 原生历史超过 64 MiB，请在原生 CLI 查看", "Claude native history exceeds 64 MiB; view it in the native CLI"))
        content = path.read_text(encoding="utf-8")
        entries = _parse_transcript_entries(content)
        transcript_uuids = {e.get("uuid") for e in entries if isinstance(e, dict) and e.get("uuid")}
        chain = _build_conversation_chain(entries)
        with self.lock:
            prev_rows = dict(self.rows)
            self.rows.clear()
            self.usage_message = {}
            for e in chain:
                if _is_visible_message(e): self.project(e)
            self._restore_unflushed_accepted_rows(prev_rows, transcript_uuids)
            for line in content.splitlines():
                try: e = json.loads(line)
                except (ValueError, TypeError): continue
                if e.get("sessionId") not in (None, self.data["nativeId"]): continue
                if e.get("type") == "custom-title" and isinstance(e.get("customTitle"), str): self.update(title=e["customTitle"])
                elif e.get("type") == "system" and e.get("subtype", "").startswith("task_"): self.task_event(e, from_history=True)
                elif e.get("type") == "summary" and not self.data["title"]: self.update(title=e.get("summary", ""))
            if not self.data["running"]: self.settle_incomplete_rows("unknown")
            for tool_key in getattr(self, "background_tasks", {}).values():
                if tool_key and tool_key in self.rows and self.rows[tool_key].get("status") in ("unknown", "in_progress", "pending", "completed"):
                    self.row({**self.rows[tool_key], "status": "running"})
            if not getattr(self, "usage_rows", None):
                by_msg = {}
                for e in chain:
                    msg = e.get("message") if isinstance(e, dict) else None
                    if isinstance(msg, dict) and msg.get("role") == "assistant" and isinstance(msg.get("usage"), dict):
                        mid = msg.get("id") or e.get("uuid")
                        if mid:
                            by_msg[mid] = (msg.get("model"), msg["usage"])
                if by_msg:
                    tot_in = sum(number(u.get("input_tokens")) or 0 for _, u in by_msg.values())
                    tot_read = sum(number(u.get("cache_read_input_tokens")) or 0 for _, u in by_msg.values())
                    tot_write = sum(number(u.get("cache_creation_input_tokens")) or 0 for _, u in by_msg.values())
                    tot_out = sum(number(u.get("output_tokens")) or 0 for _, u in by_msg.values())
                    last_m = next((m for m, _ in reversed(list(by_msg.values())) if m), self.data.get("model"))
                    self.usage_rows = (
                        cache_ratio(tot_read, tot_in + tot_read + tot_write, tr("累计输入缓存命中", "Total input cache hit"))
                        + estimate_cost(last_m, uncached_input=tot_in, cache_read=tot_read, cache_write=tot_write, output=tot_out)
                    )
            self.publish_usage()
        if chain:
            self.config["hasNativeHistory"] = True
            self.save()

    def project(self, e):
        if _is_internal_message(e): return
        message = e.get("message", {})
        if not isinstance(message, dict): return
        role = message.get("role", e.get("type", "assistant"))
        if role == "assistant" and isinstance(message.get("usage"), dict) and message.get("model"):
            self.usage_message = message
            self.publish_usage()
        body = message.get("content", [])
        if isinstance(body, str): body = [{"type": "text", "text": body}]
        prefix = e.get("uuid", message.get("id", str(uuid.uuid4())))
        if role == "user" and _is_human_turn(e) and not any(b.get("type") == "tool_result" for b in body):
            self.row(item(f"{prefix}:0", "user", user_text(body), media=self.media(body), status="completed"))
            return
        for index, b in enumerate(body):
            kind = b.get("type")
            if kind in ("text", "thinking"):
                if role == "user" and not _is_human_turn(e): continue
                key = f"{prefix}:{index}"
                self.row(item(key, "reasoning" if kind == "thinking" else role, text(b),
                              kind="reasoning" if kind == "thinking" else "", status="completed"))
            elif kind == "tool_use":
                key = "tool-" + b["id"]
                old = self.rows.get(key, {})
                self.row(item(key, "tool", b.get("name", tr("工具", "Tool")), kind="tool", toolName=b.get("name", ""),
                              input=json.dumps(b.get("input", {}), ensure_ascii=False),
                              status=old.get("status", "running"), outcome=old.get("outcome", ""), media=old.get("media", [])))
            elif kind == "tool_result":
                key = "tool-" + b["tool_use_id"]
                old = self.rows.get(key, item(key, "tool", tr("工具", "Tool"), kind="tool"))
                keep_bg = not b.get("is_error") and key in getattr(self, "background_tasks", {}).values()
                self.row({**old, "outcome": text(b.get("content", "")), "media": self.media(b.get("content")),
                          "status": "failed" if b.get("is_error") else ("running" if keep_bg else "completed")})

            elif kind in ("image", "document"):
                if role != "user" or _is_human_turn(e):
                    self.row(item(f"{prefix}:{index}", role, "", media=self.media(b), status="completed"))

    def publish_usage(self):
        self.update(metrics=claude_context(getattr(self, "usage_message", {}), getattr(self, "usage_models", {})) + getattr(self, "usage_rows", []))

    def result_usage(self, result):
        usage = result.get("usage") or {}
        placeholder = (result.get("subtype") == "error_during_execution" and
                       not any((number(v) or 0) > 0 for v in usage.values()) and
                       not (number(result.get("total_cost_usd")) or 0) and not result.get("modelUsage"))
        if not placeholder:
            self.usage_models = result.get("modelUsage") or {}
            self.usage_rows = claude_result(result)
        self.publish_usage()

    def stream(self, event):
        kind = event.get("type")
        if kind == "message_start":
            self.stream_id = event["message"]["id"]
            self.stream_blocks = {}
            self.finalized_blocks = set()
        elif kind == "content_block_start" and self.stream_id:
            b = event["content_block"]; index = event["index"]
            self.stream_blocks[index] = {**b, "partial": ""}
            self.stream_row(index)
        elif kind == "content_block_delta" and self.stream_id:
            index = event["index"]; b = self.stream_blocks.get(index)
            if b is None: return
            delta = event["delta"]; typ = delta.get("type")
            if typ == "text_delta": b["text"] = b.get("text", "") + delta.get("text", "")
            elif typ == "thinking_delta": b["thinking"] = b.get("thinking", "") + delta.get("thinking", "")
            elif typ == "input_json_delta": b["partial"] += delta.get("partial_json", "")
            else: return
            self.stream_row(index)
        elif kind == "content_block_stop" and event.get("index") in self.stream_blocks:
            self.stream_row(event["index"], completed=True)

    def stream_row(self, index, completed=False):
        if index in self.finalized_blocks: return
        b = self.stream_blocks[index]; typ = b.get("type")
        if typ in ("text", "thinking"):
            self.row(item(f"{self.stream_id}:{index}", "reasoning" if typ == "thinking" else "assistant", text(b),
                          kind="reasoning" if typ == "thinking" else "", status="completed" if completed else "running"))
        elif typ == "tool_use":
            key = "tool-" + b["id"]; old = self.rows.get(key, {})
            self.row(item(key, "tool", b.get("name", tr("工具", "Tool")), kind="tool", toolName=b.get("name", ""),
                          input=b.get("partial") or json.dumps(b.get("input", {}), ensure_ascii=False),
                          status=old.get("status", "running"), outcome=old.get("outcome", ""), media=old.get("media", [])))

        elif typ in ("image", "document"):
            self.row(item(f"{self.stream_id}:{index}", "assistant", "", media=self.media(b), status="completed" if completed else "running"))

    def event(self, e):
        native = e.get("session_id")
        if native and self.data["nativeId"] and native != self.data["nativeId"]:
            self.update(error=tr("Claude Code 返回了不同会话身份，已拒绝同步", "Claude Code returned a different session identity; sync refused"))
            return
        typ = e.get("type")
        if typ == "system" and e.get("subtype", "").startswith("task_"):
            self.task_event(e)
            return
        if e.get("parent_tool_use_id") and typ in ("stream_event", "assistant", "user"):
            return
        if typ == "control_request":
            req = e.get("request", {})
            if req.get("subtype") == "can_use_tool": self.permission_request(e)
            else: self.peer.send({"type": "control_response", "response": {"subtype": "error", "request_id": e["request_id"], "error": "Unsupported client control request"}})
        elif typ == "control_cancel_request": self.answered(e["request_id"])
        elif typ == "system" and e.get("subtype") == "compact_boundary":
            self.usage_message = {}; self.publish_usage()
        elif typ == "stream_event":
            ev = e.get("event", {})
            if ev.get("type") == "message_start" and self.current_submission is None:
                self.autonomous_running = True
                self.last_task_completed_at = None
                if not self.data["running"]:
                    self.update(running=True)
            self.stream(ev)
        elif typ in ("assistant", "user"):
            if typ == "assistant" and e.get("message", {}).get("id") == self.stream_id:
                for block in e["message"].get("content", []):
                    for index, partial in self.stream_blocks.items():
                        same_type = block.get("type") == partial.get("type")
                        same_content = (block.get("type") in ("text", "thinking") and text(block) == text(partial) or
                                        block.get("type") in ("image", "document") and block.get("source") == partial.get("source") and block.get("data") == partial.get("data"))
                        if same_type and same_content:
                            with self.lock:
                                self.rows.pop(f"{self.stream_id}:{index}", None)
                                self.finalized_blocks.add(index)
            self.project(e)
            ident = e.get("uuid")
            if typ == "user" and _is_human_turn(e) and ident in self.config.get("receipts", {}):
                self.receipt(ident, "accepted"); self.consumed(ident)
                self.config["hasNativeHistory"] = True; self.save()
        elif typ == "system" and e.get("subtype") == "init":
            skills = [{"id": name, "name": name, "path": ""} for name in e.get("skills", []) if isinstance(name, str)]
            self.permission_revision += 1
            self.update(model=self.model_id(e.get("model", self.data["model"])),
                        **({"effort": e["effort"] or ""} if "effort" in e else {}), permission=canonical_permission(e.get("permissionMode", self.data["permission"])), skills=skills,
                        capabilities={**self.data["capabilities"], "skills": bool(skills), "maxSkills": 1})
        elif typ == "system" and e.get("subtype") == "status" and e.get("permissionMode"):
            self.permission_revision += 1
            self.update(permission=canonical_permission(e["permissionMode"]))
        elif typ == "result":
            if not _is_human_turn(e):
                self.result_usage(e)
                if self.current_submission is None and getattr(self, "autonomous_running", False):
                    self.autonomous_running = False
                    self.last_task_completed_at = None
                    self.update(running=False, error=("\n".join(e.get("errors", [])) or e.get("result", tr("Claude 原生回合失败", "Claude native turn failed"))) if e.get("is_error") else None)
                    try:
                        self.load_history()
                    finally:
                        if e.get("is_error"): self.settle_incomplete_rows("unknown")
                return
            was_autonomous = self.current_submission is None
            if self.current_submission:
                self.receipt(self.current_submission, "accepted"); self.consumed(self.current_submission)
                self.current_submission = None
            if was_autonomous:
                self.autonomous_running = False
                self.last_task_completed_at = None
            self.result_usage(e)
            self.update(running=False, error=("\n".join(e.get("errors", [])) or e.get("result", tr("Claude 原生回合失败", "Claude native turn failed"))) if e.get("is_error") else None)
            try:
                self.load_history()
            finally:
                if e.get("is_error"): self.settle_incomplete_rows("unknown")

    def task_event(self, e, from_history=False):
        ident = e.get("task_id")
        if not ident: return
        subtype = e.get("subtype")
        old = self.subagents.get(ident, {})
        if subtype == "task_started":
            if e.get("task_type") != "local_agent":
                tool_key = "tool-" + e["tool_use_id"] if e.get("tool_use_id") else ""
                bg = getattr(self, "background_tasks", None)
                if bg is None:
                    bg = {}
                    self.background_tasks = bg
                bg[ident] = tool_key
                if tool_key and tool_key in self.rows:
                    if not from_history or self.rows[tool_key].get("status") != "failed":
                        self.row({**self.rows[tool_key], "status": "running"})
                elif not from_history:
                    self.seq += 1
                return
            tool = e.get("tool_use_id", "")
            row = self.rows.get("tool-" + tool, {})
            try: args = json.loads(row.get("input", "{}"))
            except (ValueError, TypeError): args = {}
            self.subagent(ident, itemId="tool-" + tool if tool else "", task=e.get("prompt", e.get("description", "")),
                          name=args.get("name", ""), role=e.get("subagent_type", args.get("subagent_type", "")), status="running", nativeStatus="started")
        elif not old and ident in getattr(self, "background_tasks", {}) and subtype in ("task_progress", "task_notification", "task_updated"):
            patch = e.get("patch") if isinstance(e.get("patch"), dict) else {}
            native = e.get("status") if subtype == "task_notification" else patch.get("status")
            if native in ("completed", "failed", "stopped", "killed"):
                tool_key = self.background_tasks.pop(ident, "")
                if not from_history:
                    self.last_task_completed_at = time.monotonic()
                if tool_key and tool_key in self.rows:
                    done_status = "completed" if native == "completed" else ("failed" if native == "failed" else "cancelled")
                    updates = {"status": done_status}
                    if e.get("summary") and not self.rows[tool_key].get("outcome"):
                        updates["outcome"] = e["summary"]
                    self.row({**self.rows[tool_key], **updates})
                else:
                    self.seq += 1
            return
        elif old and subtype in ("task_progress", "task_notification", "task_updated"):
            patch = e.get("patch") if isinstance(e.get("patch"), dict) else {}
            native = e.get("status") if subtype == "task_notification" else patch.get("status")
            fields = {}
            if native is not None:
                fields.update(nativeStatus=native, status={"pending":"pending", "running":"running", "completed":"succeeded",
                              "failed":"failed", "stopped":"cancelled", "killed":"cancelled"}.get(native, "unknown"))
            if e.get("summary") is not None: fields["detail"] = e["summary"]
            elif patch.get("error") is not None: fields["detail"] = str(patch["error"])
            if e.get("description") is not None: fields["task"] = e["description"]
            self.subagent(ident, **fields)

    def permission_request(self, e):
        p = e["request"]; args = p.get("input", {})
        if p.get("tool_name") == "AskUserQuestion":
            questions = [{"id": str(i), "header": q.get("header", ""), "prompt": q["question"],
                          "options": [o["label"] for o in q.get("options", [])], "multiple": q.get("multiSelect", False)}
                         for i, q in enumerate(args.get("questions", []))]
            self.request(e["request_id"], e, "Answer", p.get("description", ""),
                         [{"id": "allow", "label": tr("提交", "Submit")}, {"id": "deny", "label": tr("取消", "Cancel")}], questions,
                         submit_choice="allow", skip_choice="deny")
        else:
            self.request(e["request_id"], e, p.get("title", p.get("tool_name", tr("工具授权", "Tool permission"))),
                         json.dumps(args, ensure_ascii=False), [{"id": "allow", "label": tr("允许一次", "Allow once")}, {"id": "deny", "label": tr("拒绝", "Deny")}])

    def answer(self, p):
        raw = self.pending_requests.get(p["id"])
        if not raw: return super().answer(p)
        choice = p["choice"]
        if choice not in ("allow", "deny"): raise Rejected(tr("无效审批选项", "Invalid approval choice"))
        request = raw["request"]; args = request.get("input", {})
        if choice == "allow":
            if request.get("tool_name") == "AskUserQuestion":
                answers = {}
                for i, q in enumerate(args.get("questions", [])):
                    values = p.get("answers", {}).get(str(i), [])
                    if not values: raise Rejected(tr("请回答：", "Please answer: ") + q["question"])
                    if not q.get("multiSelect") and len(values) != 1: raise Rejected(tr("此问题只允许一个回答", "Only one answer is allowed for this question"))
                    answers[q["question"]] = ", ".join(values)
                args = {**args, "answers": answers}
            response = {"behavior": "allow", "updatedInput": args}
        else: response = {"behavior": "deny", "message": "User denied this request"}
        self.peer.send({"type": "control_response", "response": {"subtype": "success", "request_id": raw["request_id"], "response": response}})
        self.answered(p["id"])
        return {}

    def validate_prompt(self, p):
        super().validate_prompt(p)
        if len(p.get("skills", [])) > 1: raise Rejected(tr("Claude Code 每条消息最多选择一个 Skill", "Claude Code allows at most one skill per message"))
        if any(k not in {s["id"] for s in self.data["skills"]} for k in p.get("skills", [])): raise Rejected(tr("Skill 不在原生目录中", "Skill is not in the native catalog"))
        if not p.get("skills") and p.get("text", "").lstrip().split(maxsplit=1)[0] in ("/clear", "/reset", "/new"):
            raise Rejected(tr("此原生命令会更换会话 ID；请使用 GUI 新建会话，保留当前会话的身份与历史", "This native command replaces the session ID; start a new session in GUI to preserve current identity and history"))

    def prompt(self, p):
        body = p["text"]
        if p.get("skills"): body = "/" + p["skills"][0] + " " + body
        if not hasattr(self, "submitted_user_text"):
            self.submitted_user_text = {}
        self.submitted_user_text[p["submissionId"]] = body
        self.current_submission = p["submissionId"]
        self.update(running=True, error=None)
        self.peer.send({"type": "user", "uuid": p["submissionId"], "session_id": self.data["nativeId"],
                        "message": {"role": "user", "content": [{"type": "text", "text": body}]}})
        return False

    def configure(self, p):
        if self.data["running"]: raise Rejected(tr("请等待回合结束再更改设置", "Wait for the current turn to finish before changing settings"))
        if p.get("speed"): raise Rejected(tr("此 stream-json 适配没有原生速度设置接口；保留 CLI 配置", "This stream-json adapter has no native speed setting; CLI config is preserved"))
        if p.get("effort"): self.validate_effort(p["effort"], p.get("model") or self.data["model"])
        if p.get("model"):
            value = p["model"]
            if value not in {m["id"] for m in self.data["models"]}: raise Rejected(tr("模型不在原生目录中", "Model is not in the native catalog"))
            if value != self.data["model"]:
                reply = self.call("set_model", {"model": value})
                self.config["selectedModel"] = value
                self.update(model=self.model_id(reply.get("model", value)))
                self.read_settings(optional=True)
                entry = next(m for m in self.data["models"] if m["id"] == value)
                if self.config.get("selectedEffort") and self.config["selectedEffort"] not in entry["efforts"] and not p.get("effort"):
                    self.call("apply_flag_settings", {"settings": {"effortLevel": None}})
                    if not self.read_settings(): raise RuntimeError(tr("Claude Code 思考强度重置未获确认", "Claude Code effort reset was not confirmed"))
                    self.config.pop("selectedEffort", None)
                self.save()
        if p.get("effort"):
            try:
                self.set_native_effort(p["effort"])
            except Rejected:
                raise
            except Exception:
                self.update(effort="")
                raise
            previous = self.config.get("selectedEffort")
            self.config["selectedEffort"] = p["effort"]
            try:
                self.save()
            except Exception as exc:
                if previous is None: self.config.pop("selectedEffort", None)
                else: self.config["selectedEffort"] = previous
                raise RuntimeError(tr("思考强度已生效，但会话选择保存失败", "Effort level took effect, but saving session selection failed")) from exc
        if p.get("permission"):
            value = p["permission"]
            self.validate_permission(value)
            revision = self.permission_revision
            try:
                actual = self.set_native_permission(value)
            except Rejected:
                raise
            except Exception:
                if revision == self.permission_revision: self.update(permission="")
                raise
            self.update(permission=actual)
            previous = self.config.get("selectedPermission")
            self.config["selectedPermission"] = actual
            try:
                self.save()
            except Exception as exc:
                if previous is None: self.config.pop("selectedPermission", None)
                else: self.config["selectedPermission"] = previous
                raise RuntimeError(tr("权限已在当前 Claude 进程生效，但会话选择保存失败；重启仍使用上次保存值",
                                      "Permission took effect in the current Claude process, but saving session selection failed; restart will use previous value")) from exc
        return {}

    def stop(self):
        self.call("interrupt")
        return {}

    def compact(self):
        if "compact" not in self.commands: return super().compact()
        return self.submit({"submissionId": str(uuid.uuid4()), "text": "/compact"})

    def refresh_mcp(self):
        result = self.call("mcp_status")
        self.update(mcpAvailable=True, mcp=[{"name": m["name"], "detail": m.get("status", "")}
                    for m in result.get("mcpServers", []) if m.get("name")])

    def refresh(self):
        if not self.data["running"]:
            self.load_history()
            self.read_settings(optional=True)
        self.refresh_mcp()
        return {}

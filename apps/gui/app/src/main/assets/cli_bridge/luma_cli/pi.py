import os
import json
import re
import threading
import uuid
from pathlib import Path
from .base import Session, item, text, user_text, image_blocks
from .transport import Peer, Rejected
from .metrics import pi_usage

_PI_THINKING_LEVELS = ("off", "minimal", "low", "medium", "high", "xhigh", "max")

_PI_IMAGE_HINT_TAIL_RE = re.compile(
    r"(?:\s*(?:\[Image: original \d+x\d+, displayed at \d+x\d+\. Multiply coordinates by \d+(?:\.\d+)? to map to original image\.\]"
    r"|\[Image converted from [^\r\n\]]+ to [^\r\n\]]+\.\]"
    r"|\[Image omitted: [^\r\n\]]+\]))+\s*$"
)

def strip_pi_image_hints(body):
    if not isinstance(body, str) or "[Image" not in body:
        return body
    return _PI_IMAGE_HINT_TAIL_RE.sub("", body)

def _pi_model_efforts(m, current_levels, is_current):
    if is_current and current_levels:
        return current_levels
    if isinstance(m.get("thinkingLevels"), list):
        return m["thinkingLevels"]
    if not m.get("reasoning"):
        return ["off"]
    level_map = m.get("thinkingLevelMap") or {}
    out = []
    for lv in _PI_THINKING_LEVELS:
        if lv in level_map and level_map[lv] is None:
            continue
        if lv in ("xhigh", "max") and lv not in level_map:
            continue
        out.append(lv)
    return out

def _pi_tool_title(name, raw_args):
    base = name or "工具"
    if not name:
        return base
    args = raw_args
    if isinstance(args, str):
        try:
            args = json.loads(args)
        except Exception:
            args = None
    if not isinstance(args, dict):
        return base
    target = ""
    if name in ("read", "write", "edit", "ls", "glob"):
        target = str(args.get("path") or ("." if name == "ls" and args else "")).strip()
    elif name in ("bash", "powershell"):
        cmd = str(args.get("command") or "").strip()
        target = next((line.strip() for line in cmd.splitlines() if line.strip()), "")
    elif name in ("grep", "find"):
        pat = str(args.get("pattern") or "").strip()
        scope = str(args.get("path") or "").strip()
        target = f"{pat} ({scope})" if pat and scope else (pat or scope)
    elif name == "web_search":
        target = str(args.get("query") or "").strip()
    elif name == "eval":
        target = str(args.get("title") or args.get("language") or "").strip()
    return f"{name} · {target}" if target else base

def _tool_patch_or_text(container):
    details = container.get("details") if isinstance(container, dict) else None
    if isinstance(details, dict):
        patch = details.get("patch")
        if isinstance(patch, str) and patch.strip():
            return patch
        diff = details.get("diff")
        if isinstance(diff, str) and diff.strip():
            return diff
    return text((container or {}).get("content"))

def _stream_snapshot(message):
    """Copy one level so accumulating deltas never mutates the SDK's live message blocks."""
    content = message.get("content")
    if isinstance(content, list):
        return {**message, "content": [dict(block) if isinstance(block, dict) else block for block in content]}
    return dict(message)

class PiSession(Session):
    dialect = "pi"

    def release_mode(self):
        saved = self.config.get("sessionFile")
        has_saved = bool(saved and Path(saved).is_file())
        empty_managed_pi = (
            bool(self.config.get("nativeId"))
            and self.data.get("connected")
            and not self.rows
            and not self.config.get("receipts")
        )
        if not has_saved and not empty_managed_pi: return None
        if getattr(self.peer, "reconnectable", False): return "observer"
        return "process" if getattr(self, "queue_readable", False) else None

    def connect(self):
        self.ready = threading.Event()
        self.active_message = None
        self.active_stream_rows = set()
        self.run_revision = 0
        self.message_revision = 0
        saved_file = self.config.get("sessionFile")
        has_saved_file = bool(saved_file and Path(saved_file).is_file())
        argv = ["pi", "--mode", "rpc"]
        if not self.config.get("nativeId"):
            self.config["managedIdentity"] = True
            self.update(nativeId=str(uuid.uuid4()))
        if self.config.get("nativeId"):
            if self.config.get("managedIdentity") and not has_saved_file:
                argv += ["--session-id", self.config["nativeId"]]
            else:
                argv += ["--session", saved_file if has_saved_file else self.config["nativeId"]]
        if self.config.get("browserConfig"):
            browser_skill = os.environ.get("LUMA_BROWSER_SKILL", "/opt/luma/agent/skills/luma-browser/SKILL.md")
            argv += ["--skill", browser_skill, "--skill", str(Path(browser_skill).parent.parent / "luma-sessions/SKILL.md")]
        if self.config.get("model") and not has_saved_file:
            argv += ["--model", self.config["model"]]
        if self.config.get("effort") and not has_saved_file:
            argv += ["--thinking", self.config["effort"]]
        self.start(argv)
        self.refresh()
        commands = self.call("get_commands").get("commands", [])
        skills = []
        for command in commands:
            if command.get("source") == "skill":
                skills.append({"id": command["name"], "name": command["name"].removeprefix("skill:"),
                               "path": (command.get("sourceInfo") or {}).get("path", "")})
        self.update(connected=True, skills=skills,
                    capabilities={"queue": True, "steer": True, "stop": True, "images": self.supports_images,
                                  "skills": bool(skills), "maxSkills": 1, "rename": True, "compact": True},
                    notice=self.capability_notice())

    def capability_notice(self):
        prefix = self.config.get("protocolFallbackNotice", "")
        base = ("使用 pi 原生行为，不增加工具权限拦截；仅处理 CLI/扩展实际发出的确认。" +
                "无统一权限档位；官方 TUI 没有此进程的实时接管接口。" +
                ("Pi 提供原生待发队列快照；不支持逐项编辑、删除或转引导。" if getattr(self, "queue_readable", False)
                 else "当前 Pi RPC 仅提供待发数量，不提供逐项队列读取或编辑。"))
        return (prefix + " " + base).strip() if prefix else base

    def apply_running(self, state, revision):
        with self.lock:
            if getattr(self, "run_revision", 0) == revision:
                is_running = not state["isSettled"] if "isSettled" in state else bool(state.get("isStreaming") or state.get("isCompacting"))
                if not is_running:
                    self._finish_incomplete_rows()
                self.update(running=is_running)

    def _drop_stale_stream_rows(self, keep=()):
        with self.lock:
            prev = getattr(self, "active_stream_rows", None)
            if not prev:
                return
            keep_set = set(keep)
            for rid in prev - keep_set:
                if rid in self.rows:
                    self.rows.pop(rid, None)
                    self.seq += 1

    def _finish_active_stream(self):
        with self.lock:
            prev = getattr(self, "active_stream_rows", None)
            if prev:
                for rid in list(prev):
                    row = self.rows.get(rid)
                    if not row or row.get("status") not in ("running", "in_progress", "pending"):
                        continue
                    if str(row.get("text") or "").strip() or row.get("media"):
                        self.row({**row, "status": "completed"})
                    else:
                        self.rows.pop(rid, None)
                        self.seq += 1
            self.active_stream_rows = set()

    def _finish_incomplete_rows(self):
        with self.lock:
            self.active_message = None
            self.active_stream_rows = set()
            for rid, row in list(self.rows.items()):
                if row.get("status") not in ("running", "in_progress", "pending"):
                    continue
                if row.get("kind") == "reasoning" or row.get("role") == "assistant":
                    if str(row.get("text") or "").strip() or row.get("media"):
                        self.row({**row, "status": "completed"})
                    else:
                        self.rows.pop(rid, None)
                        self.seq += 1
                else:
                    self.row({**row, "status": "unknown"})

    def _record_session_file(self, sf):
        if not sf or not Path(sf).is_file():
            return
        if self.config.get("sessionFile") != sf:
            self.config["sessionFile"] = sf
            self.save()

    def _schedule_post_turn_sync(self):
        if not isinstance(getattr(self, "peer", None), Peer) or self.peer.closed:
            return
        def worker():
            try:
                state = self.call("get_state", timeout=15, pause_for_user=False)
                stats = self.call("get_session_stats", timeout=15, pause_for_user=False)
                with self.lock:
                    self._record_session_file(state.get("sessionFile"))
                    self.update(nativeQueuePending=bool(state.get("pendingMessageCount", 0)),
                                metrics=pi_usage(stats, state))
            except Exception:
                pass
        threading.Thread(target=worker, daemon=True, name="LumaPiPostTurnSync").start()

    def refresh(self):
        revision = getattr(self, "run_revision", 0)
        state = self.call("get_state")
        self.update(nativeQueuePending=bool(state.get("pendingMessageCount", 0)))
        self.queue_readable = getattr(self, "queue_readable", False) or "queuedMessages" in state
        self.apply_running(state, revision)
        native = state["sessionId"]
        if self.config.get("nativeId") and native != self.config["nativeId"]:
            raise Rejected("CLI 返回了其他会话，已拒绝接入")
        self._record_session_file(state.get("sessionFile"))
        model = state.get("model") or {}
        key = model.get("provider", "") + "/" + model["id"] if model.get("id") else ""
        levels = self.call("get_available_thinking_levels").get("levels", [])
        self.supports_images = "image" in model.get("input", [])
        models = []
        for m in self.call("get_available_models").get("models", []):
            ident = m["provider"] + "/" + m["id"]
            models.append({"id": ident, "label": m.get("name", ident),
                           "efforts": _pi_model_efforts(m, levels, ident == key),
                           "supportsImages": "image" in m.get("input", [])})
        self.update(nativeId=native, title=state.get("sessionName", ""), model=key, effort=state.get("thinkingLevel", ""),
                    models=models)
        msg_rev = getattr(self, "message_revision", 0)
        messages = self.call("get_messages").get("messages", [])
        with self.lock:
            if getattr(self, "message_revision", 0) == msg_rev:
                if not self.data["running"]:
                    self.rows.clear()
                    self.active_message = None
                    self.active_stream_rows = set()
                for n, message in enumerate(messages):
                    self.project(message, f"message-{n}")
        if "queuedMessages" in state:
            self.project_queue(state["queuedMessages"])
        elif state.get("pendingMessageCount", 0) == 0:
            self.update(queue=[])
        stats = self.call("get_session_stats")
        metrics = pi_usage(stats, state)
        self.update(metrics=metrics, capabilities={**self.data["capabilities"], "images": self.supports_images}, notice=self.capability_notice())
        return {}

    def project(self, m, fallback, done=True):
        ident = str(m.get("id") or m.get("timestamp") or fallback)
        role = m.get("role", "system")
        projected = set()
        if role == "system" and ("sections" in m or not str(m.get("content") or "").strip()):
            return projected
        if role == "custom":
            if m.get("display") is False:
                return projected
            role = "user" if m.get("attribution") == "user" else "assistant"
        if role in ("compactionSummary", "branchSummary"):
            summary = str(m.get("summary") or "")
            self.row(item(ident, "tool", "上下文压缩" if role == "compactionSummary" else "分支摘要",
                          detail=summary, outcome=summary, kind="compaction", status="completed"))
            projected.add(ident)
            return projected
        if role == "bashExecution":
            cmd = str(m.get("command") or "")
            out = str(m.get("output") or "")
            status = "cancelled" if m.get("cancelled") else ("failed" if m.get("exitCode") not in (0, None) else "completed")
            row_id = "bash-" + ident
            self.row(item(row_id, "tool", _pi_tool_title("bash", {"command": cmd}), kind="tool",
                          toolName="bash", input=json.dumps({"command": cmd}, ensure_ascii=False),
                          detail=out, outcome=out, status=status))
            projected.add(row_id)
            return projected
        if role in ("toolResult", "tool"):
            tool_id = "tool-" + str(m.get("toolCallId", ident))
            old = self.rows.get(tool_id, {})
            resolved_name = m.get("toolName") or old.get("toolName", "")
            resolved_input = old.get("input", "")
            label = _pi_tool_title(resolved_name, resolved_input) if resolved_name else old.get("text", "工具")
            out = _tool_patch_or_text(m)
            self.row(item(tool_id, "tool", label,
                          detail=out, outcome=out, media=self.media(m.get("content")),
                          input=resolved_input, toolName=resolved_name,
                          status="failed" if m.get("isError") else "completed", kind="tool"))
        elif role == "user":
            skill = m.get("_skillBlock") if isinstance(m.get("_skillBlock"), dict) else None
            if not skill and m.get("customType") == "skill-prompt" and isinstance(m.get("details"), dict) and m["details"].get("name"):
                skill = {"name": "skill:" + str(m["details"]["name"]), "userMessage": str(m["details"].get("args") or "")}
            if skill and skill.get("name"):
                u_msg = strip_pi_image_hints(str(skill.get("userMessage") or ""))
                body = f"/{skill['name']} {u_msg}".strip()
            else:
                body = strip_pi_image_hints(user_text(m.get("content")))
            self.row(item(ident, "user", body, media=self.media(m.get("content"))))
            projected.add(ident)
        elif isinstance(m.get("content"), str):
            if m["content"].strip() or role == "user":
                self.row(item(ident, role, m["content"]))
                projected.add(ident)
        else:
            for n, block in enumerate(m.get("content") or []):
                bid = f"{ident}-{n}"
                kind = block.get("type")
                if kind in ("text", "thinking"):
                    block_done = done or bool(block.get("_done"))
                    body_val = text(block)
                    if not body_val.strip() and (kind == "text" or block_done):
                        with self.lock:
                            if bid in self.rows:
                                self.rows.pop(bid, None)
                                self.seq += 1
                        continue
                    self.row(item(bid, "reasoning" if kind == "thinking" else role, body_val, kind="reasoning" if kind == "thinking" else "", status="completed" if block_done else "running"))
                    projected.add(bid)
                elif kind in ("toolCall", "tool_use"):
                    tool_id = "tool-" + str(block.get("id", bid))
                    old = self.rows.get(tool_id, {})
                    resolved_name = block.get("name") or old.get("toolName", "")
                    raw_args = block.get("arguments", block.get("input"))
                    encoded_input = json.dumps(raw_args, ensure_ascii=False) if raw_args is not None else old.get("input", "{}")
                    self.row(item(tool_id, "tool", _pi_tool_title(resolved_name, raw_args if raw_args is not None else encoded_input), kind="tool",
                                  toolName=resolved_name,
                                  input=encoded_input,
                                  status=old.get("status", "running"), outcome=old.get("outcome", ""), media=old.get("media", [])))
                elif kind in ("image", "audio", "document", "file", "attachment", "resource", "resource_link", "image_url"):
                    self.row(item(bid, role, "", media=self.media(block), status="completed" if done else "running"))
                    projected.add(bid)
            if m.get("errorMessage"):
                err_id = ident + "-error"
                self.row(item(err_id, "system", m["errorMessage"], status="failed"))
                projected.add(err_id)
        return projected

    def event(self, e):
        t = e.get("type")
        if t == "ready":
            self.versions = e.get("supportedProtocolVersions", [1]); self.ready.set()
        elif t in ("agent_start", "compaction_start", "auto_retry_start"):
            with self.lock:
                self.run_revision = getattr(self, "run_revision", 0) + 1
                self.message_revision = getattr(self, "message_revision", 0) + 1
                self.update(running=True)
        elif t in ("session_settled", "agent_settled"):
            with self.lock:
                self.run_revision = getattr(self, "run_revision", 0) + 1
                self.message_revision = getattr(self, "message_revision", 0) + 1
                self._finish_incomplete_rows()
                self.update(running=False)
            if not getattr(self, "native_stats_pushed", False):
                self._schedule_post_turn_sync()
        elif t == "agent_end":
            if not e.get("willRetry"):
                with self.lock:
                    self.run_revision = getattr(self, "run_revision", 0) + 1
                    self.message_revision = getattr(self, "message_revision", 0) + 1
                    self._finish_incomplete_rows()
                    self.update(running=False)
        elif t == "compaction_end":
            if not e.get("willRetry"):
                with self.lock:
                    self.run_revision = getattr(self, "run_revision", 0) + 1
                    self.message_revision = getattr(self, "message_revision", 0) + 1
                    self._finish_incomplete_rows()
                    self.update(running=False)
                if not getattr(self, "native_stats_pushed", False):
                    self._schedule_post_turn_sync()
        elif t == "auto_retry_end" and not e.get("success"):
            with self.lock:
                self.run_revision = getattr(self, "run_revision", 0) + 1
                self._finish_incomplete_rows()
                self.update(running=False)
        elif t == "session_info_changed":
            self.update(title=e.get("name") or "")
        elif t == "thinking_level_changed":
            if e.get("level"): self.update(effort=e["level"])
        elif t == "message_start":
            m = dict(e["message"])
            with self.lock:
                self.message_revision = getattr(self, "message_revision", 0) + 1
                if m.get("role") == "assistant":
                    self._finish_active_stream()
                    self.active_message = _stream_snapshot(m)
                    self.active_stream_rows = self.project(self.active_message, "stream", done=False)
                else:
                    self.project(m, "stream", done=False)
        elif t == "message_end":
            m = dict(e["message"])
            with self.lock:
                self.message_revision = getattr(self, "message_revision", 0) + 1
                if m.get("role") == "assistant":
                    final_rows = self.project(m, "stream", done=True)
                    self._drop_stale_stream_rows(final_rows)
                    self.active_message = None
                    self.active_stream_rows = set()
                else:
                    self.project(m, "stream", done=True)
        elif t == "message_update":
            with self.lock:
                self.message_revision = getattr(self, "message_revision", 0) + 1
                if e.get("message"):
                    prev_blocks = (self.active_message or {}).get("content") if isinstance(self.active_message, dict) else None
                    self.active_message = _stream_snapshot(e["message"])
                    if isinstance(prev_blocks, list) and isinstance(self.active_message.get("content"), list):
                        for idx, prev_b in enumerate(prev_blocks):
                            if idx < len(self.active_message["content"]) and isinstance(prev_b, dict) and prev_b.get("_done") and isinstance(self.active_message["content"][idx], dict):
                                self.active_message["content"][idx]["_done"] = True
                    delta = e.get("assistantMessageEvent", {})
                    if delta.get("type") in ("text_end", "thinking_end"):
                        idx = delta.get("contentIndex", 0)
                        if isinstance(self.active_message.get("content"), list) and idx < len(self.active_message["content"]) and isinstance(self.active_message["content"][idx], dict):
                            self.active_message["content"][idx]["_done"] = True
                elif self.active_message:
                    if e.get("_messageId") and not self.active_message.get("id"):
                        self.active_message["id"] = e["_messageId"]
                    delta = e.get("assistantMessageEvent", {})
                    n = delta.get("contentIndex", 0)
                    blocks = self.active_message.setdefault("content", [])
                    while len(blocks) <= n: blocks.append({})
                    block = blocks[n]; kind = delta.get("type", "")
                    if kind in ("text_delta", "thinking_delta"):
                        field = "text" if kind == "text_delta" else "thinking"
                        block["type"] = field
                        block[field] = block.get(field, "") + delta.get("delta", "")
                        for prev_b in blocks[:n]:
                            if isinstance(prev_b, dict) and prev_b.get("type") == "thinking":
                                prev_b["_done"] = True
                    elif kind in ("text_end", "thinking_end"):
                        field = "text" if kind == "text_end" else "thinking"
                        block["type"] = field
                        if isinstance(delta.get("content"), str):
                            block[field] = delta["content"]
                        block["_done"] = True
                    elif kind in ("text_start", "thinking_start"):
                        block.setdefault("type", "text" if kind == "text_start" else "thinking")
                        for prev_b in blocks[:n]:
                            if isinstance(prev_b, dict) and prev_b.get("type") == "thinking":
                                prev_b["_done"] = True
                    elif kind == "toolcall_start":
                        block["type"] = "toolCall"
                        if delta.get("id"): block["id"] = delta["id"]
                        if delta.get("toolName"): block["name"] = delta["toolName"]
                        for prev_b in blocks[:n]:
                            if isinstance(prev_b, dict) and prev_b.get("type") in ("thinking", "text"):
                                prev_b["_done"] = True
                    elif kind == "toolcall_end": blocks[n] = delta.get("toolCall", {})
                if self.active_message:
                    new_rows = self.project(self.active_message, "stream", done=False)
                    self._drop_stale_stream_rows(new_rows)
                    self.active_stream_rows = new_rows
        elif t in ("tool_execution_start", "tool_execution_update", "tool_execution_end"):
            with self.lock:
                self.message_revision = getattr(self, "message_revision", 0) + 1
            ident = "tool-" + e["toolCallId"]; old = self.rows.get(ident, {})
            resolved_name = e.get("toolName") or old.get("toolName", "")
            encoded_input = json.dumps(e["args"], ensure_ascii=False) if "args" in e else old.get("input", "")
            label = _pi_tool_title(resolved_name, e["args"] if "args" in e else encoded_input) if resolved_name else old.get("text", "工具")
            has_result = bool(e.get("result") or e.get("partialResult"))
            result = e.get("result") or e.get("partialResult") or {}
            outcome = _tool_patch_or_text(result) if has_result else old.get("outcome", "")
            media = self.media(result.get("content")) if has_result else old.get("media", [])
            self.row(item(ident, "tool", label, kind="tool",
                          toolName=resolved_name,
                          input=encoded_input,
                          outcome=outcome, detail=outcome, media=media,
                          status=("failed" if e.get("isError") else "completed") if t.endswith("end") else "running"))
        elif t == "queue_update": self.project_queue(e)
        elif t == "extension_ui_request": self.ui_request(e)

    def project_queue(self, e):
        entries = []
        previous = getattr(self, "queue_native", {})
        self.queue_native = {}
        for name in ("steering", "followUp"):
            rows = e.get(name, [])
            for raw in rows:
                body = raw if isinstance(raw, str) else raw.get("message", raw.get("text", ""))
                ident = next((i for i, pair in previous.items() if pair == (name, body) and i not in self.queue_native), str(uuid.uuid4()))
                self.queue_native[ident] = (name, body)
                entries.append({"id": ident, "text": strip_pi_image_hints(body), "status": "引导" if name == "steering" else "待执行",
                                "canEdit": False, "canRemove": False, "canSteer": False})
        self.update(queue=entries)

    def validate_prompt(self, p):
        super().validate_prompt(p)
        if len(p.get("skills", [])) > 1: raise Rejected("此 CLI 每条消息支持一个原生 Skill 命令，请只选一个")
        known = {s["id"] for s in self.data["skills"]}
        if any(s not in known for s in p.get("skills", [])): raise Rejected("Skill 已不可用，请刷新")

    def prompt(self, p):
        message = p["text"]
        if p.get("skills"): message = "/" + p["skills"][0] + " " + message
        args = {"message": message, "streamingBehavior": "steer" if p.get("steer") else "followUp"}
        if p.get("images"):
            args["images"] = image_blocks(p["images"])
        self.call("prompt", args)
        self.receipt(p["submissionId"], "accepted")
        revision = getattr(self, "run_revision", 0)
        try:
            state = self.call("get_state")
            self.apply_running(state, revision)
        except Exception as exc:
            self.update(error="输入已接收，状态读取失败，请重新同步：" + str(exc))
        return True

    def configure(self, p):
        if p.get("permission") or p.get("speed"): return super().configure(p)
        if p.get("model"):
            if p["model"] not in {m["id"] for m in self.data["models"]}: raise Rejected("请选择 CLI 返回的模型")
            provider, model = p["model"].split("/", 1)
            self.call("set_model", {"provider": provider, "modelId": model})
        if p.get("effort"):
            levels = self.call("get_available_thinking_levels").get("levels", [])
            if p["effort"] not in levels: raise Rejected("当前模型不支持此思考强度")
            self.call("set_thinking_level", {"level": p["effort"]})
        return self.refresh()

    def stop(self):
        self.call("abort"); return self.refresh()
    def rename(self, title):
        self.call("set_session_name", {"name": title}); return self.refresh()
    def compact(self):
        if self.data["running"]: raise Rejected("请等待任务结束再压缩")
        self.call("compact", timeout=300); return self.refresh()

    def ui_request(self, e):
        method, ident = e["method"], str(e.get("id", ""))
        if method == "cancel": self.answered(str(e.get("targetId", ident))); return
        if method == "setTitle": return
        if method == "notify": self.update(notice=e.get("message", "")); return
        choices = []; questions = []
        if method == "confirm": choices = [{"id": "yes", "label": "确认"}, {"id": "no", "label": "拒绝"}]
        elif method == "select": choices = [{"id": str(i), "label": value} for i, value in enumerate(e.get("options", []))]
        elif method in ("input", "editor"):
            prompt_text = e.get("placeholder") if e.get("placeholder") is not None else e.get("prefill", "")
            questions = [{"id": "value", "header": e.get("title", "输入"), "prompt": prompt_text, "secret": e.get("secret", False)}]
        elif method == "ask":
            questions = [{"id": q["id"], "header": q.get("header", ""), "prompt": q["question"],
                          "options": [o["label"] for o in q.get("options", [])], "multiple": q.get("multi", False)} for q in e["questions"]]
        else: return
        choices.append({"id": "cancel", "label": "取消"})
        self.request(ident, e, e.get("title", "Answer" if questions else "CLI 请求"), e.get("message", ""), choices, questions, submit_choice="submit", skip_choice="cancel")

    def answer(self, p):
        raw = self.pending_requests.get(p["id"])
        if not raw: return super().answer(p)
        response = {"type": "extension_ui_response", "id": raw["id"]}
        choice = p.get("choice")
        if choice == "cancel": response["cancelled"] = True
        elif raw["method"] == "confirm":
            if choice not in ("yes", "no"): raise Rejected("无效确认选项")
            response["confirmed"] = choice == "yes"
        elif raw["method"] == "select":
            if choice not in {str(i) for i in range(len(raw["options"]))}: raise Rejected("无效选择")
            response["value"] = raw["options"][int(choice)]
        elif raw["method"] == "ask":
            response["answers"] = []
            for q in raw["questions"]:
                values = p.get("answers", {}).get(q["id"], [])
                labels = {o["label"] for o in q.get("options", [])}
                response["answers"].append({"id": q["id"], "selectedOptions": [v for v in values if v in labels],
                                            "customInput": "\n".join(v for v in values if v not in labels)})
        else: response["value"] = "\n".join(p.get("answers", {}).get("value", []))
        self.peer.send(response); self.answered(p["id"]); return {}

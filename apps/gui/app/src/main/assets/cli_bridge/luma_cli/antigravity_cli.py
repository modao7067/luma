import copy
import json
import os
import pathlib
import re
import shutil
import subprocess
import threading
import time
import urllib.parse
from .agy_stream import AgyPeer
from .base import Session, item
from .transport import Rejected
from .metrics import agy_usage, agy_steps, agy_session_metrics

PROTOCOL = "antigravity-cli-stream-json"
FULL_ACCESS = "always-proceed"
_ID = re.compile(r"[a-zA-Z0-9_-]{1,128}")
_CHILD_TERMINAL = {"succeeded", "failed", "cancelled", "closed"}
_LOG_TAIL = ("brain", ".system_generated", "logs", "transcript.jsonl")
_ROW_INDEX = re.compile(r"agy:(.+):(\d+):(agent_response|tool)")
STREAM_STALL_SECONDS = 3.0

def native_error(value):
    message = value if isinstance(value, str) else "agy 返回了错误"
    if re.search(r"auth|sign.?in|log.?in", message, re.I):
        return "agy 尚未登录；请在当前运行环境的终端运行 agy，完成原生登录后重连。"
    return message

def json_values(text):
    if not isinstance(text, str): return
    decoder, index = json.JSONDecoder(), 0
    while True:
        start = text.find("{", index)
        if start < 0: return
        try: value, index = decoder.raw_decode(text, start)
        except json.JSONDecodeError: index = start + 1
        else:
            if isinstance(value, dict): yield value

def invoke_child(output):
    for value in json_values(output):
        ident = value.get("conversationId")
        if isinstance(ident, str) and _ID.fullmatch(ident):
            uri = value.get("logAbsoluteUri")
            return ident, uri if isinstance(uri, str) else ""
    return "", ""

def child_log_path(ident, uri):
    if not _ID.fullmatch(ident or ""): return ""
    candidates = []
    if isinstance(uri, str) and uri.startswith("file://"):
        raw = urllib.parse.unquote(uri[len("file://"):])
        if raw.startswith("/") and not raw.startswith("//"): candidates.append(pathlib.Path(raw))
    homes = []
    for key in ("GEMINI_HOME",):
        if os.environ.get(key): homes.append(pathlib.Path(os.environ[key]))
    homes.append(pathlib.Path.home() / ".gemini")
    for home in homes:
        root = home if home.name == "antigravity-cli" else home / "antigravity-cli"
        candidates.append(root / "brain" / ident / ".system_generated" / "logs" / "transcript.jsonl")
    for path in candidates:
        try: resolved = path.resolve(strict=False)
        except OSError: continue
        parts = resolved.parts
        if len(parts) >= 5 and parts[-5] == "brain" and parts[-4] == ident and parts[-3:] == _LOG_TAIL[1:]:
            return str(resolved)
    return ""

def transcript_args(call):
    args = call.get("args") if isinstance(call.get("args"), dict) else {}
    decoded = {}
    for key, value in args.items():
        try: decoded[key] = json.loads(value) if isinstance(value, str) else value
        except (ValueError, TypeError): decoded[key] = value
    return decoded

def read_full_step(path, target_index):
    if type(target_index) is not int: return None
    try:
        with open(path, "rb") as handle:
            for line in handle:
                try: value = json.loads(line)
                except ValueError: continue
                if isinstance(value, dict) and value.get("step_index") == target_index:
                    return value
    except OSError: pass
    return None

def read_transcript(path):
    try:
        with open(path, "rb") as handle:
            handle.seek(0, 2)
            size = handle.tell()
            if size > 2_000_000:
                handle.seek(-2_000_000, 2); handle.readline()
            else: handle.seek(0)
            raw = handle.read()
    except OSError: return []
    steps = []
    for line in raw.decode("utf-8", "replace").splitlines():
        try: value = json.loads(line)
        except ValueError: continue
        if isinstance(value, dict): steps.append(value)
    return steps

def child_transcript_state(steps):
    for step in reversed(steps):
        kind, state = step.get("type"), step.get("status")
        if kind == "ERROR_MESSAGE": return "failed"
        if kind in ("SYSTEM_MESSAGE", "USER_INPUT"): return "running"
        if kind == "PLANNER_RESPONSE":
            return "idle" if state == "DONE" and not step.get("tool_calls") else "running"
        if kind == "GENERIC": return "running"
    return ""

_AGY_DIRECTIVE_RE = re.compile(
    r"(?m)^[ \t]*YOU MUST TAKE ONE OF THE FOLLOWING TWO ACTIONS:[\s\S]*?(?:Never mix two or more languages in the same response\.?|\Z)"
)
_AGY_BG_META_RE = re.compile(
    r"^(?:(?:Created|Completed|Updated) At:\s+\d{4}-\d{2}-\d{2}T\S+|Task Description:\s+.*|Tool is running as a background task with task id:\s*\S+.*|Task logs are available at:\s*file://\S+.*)$"
)
_AGY_TASK_RESULT_HEADER_RE = re.compile(
    r'^Task id "[^"]+" [^\n]*?(?::\s*\n|\n|$)'
)

def clean_agy_tool_output(raw):
    if not isinstance(raw, str) or not raw.strip():
        return ""
    has_bg = (
        "Tool is running as a background task with task id:" in raw
        or "Task logs are available at: file://" in raw
        or "YOU MUST TAKE ONE OF THE FOLLOWING TWO ACTIONS:" in raw
    )
    if not has_bg:
        return raw.rstrip() if raw.strip() else ""
    stripped = _AGY_DIRECTIVE_RE.sub("", raw)
    lines = [line for line in stripped.splitlines() if not _AGY_BG_META_RE.match(line.strip())]
    joined = "\n".join(lines).rstrip()
    return joined if joined.strip() else ""

def extract_agy_task_result(body):
    if not isinstance(body, str) or not body.strip():
        return ""
    rest = _AGY_TASK_RESULT_HEADER_RE.sub("", body, count=1)
    return clean_agy_tool_output(rest)

def parse_system_message(content):
    if not isinstance(content, str): return "", ""
    blocks = re.findall(r"<SYSTEM_MESSAGE>\s*([\s\S]*?)\s*</SYSTEM_MESSAGE>", content)
    raw = (blocks[-1].rsplit("<SYSTEM_MESSAGE>", 1)[-1] if blocks else content).strip()
    match = re.match(r"^\[Message\][^\n]*?\bsender=(\S+)(?:\s+priority=\S+)?\s+content=([\s\S]*)$", raw)
    if match: return match.group(1), match.group(2).strip()
    return "", raw

def project_child_transcript(ident, steps):
    messages, open_tools = [], []
    for step in steps:
        if not isinstance(step, dict): continue
        index, kind, state = step.get("step_index"), step.get("type"), step.get("status")
        status = "running" if state in ("RUNNING", "ACTIVE") else "failed" if state == "ERROR" else "completed"
        base = f"agy:{ident}:{index}:{kind}"
        if kind == "PLANNER_RESPONSE":
            thinking = step.get("thinking")
            if isinstance(thinking, str) and thinking:
                messages.append(item(base + ":think", "tool", thinking[:16000], kind="reasoning", status=status, turnId=ident))
            calls = step.get("tool_calls") if isinstance(step.get("tool_calls"), list) else []
            if calls:
                for number, call in enumerate(calls):
                    if not isinstance(call, dict): continue
                    name = call.get("name") if isinstance(call.get("name"), str) else ""
                    args = transcript_args(call)
                    raw = json.dumps(args, ensure_ascii=False)
                    messages.append(item(f"{base}:tool:{number}", "tool", name, kind="tool", toolName=name,
                        input=raw[:16000], status="failed" if status == "failed" else "running", turnId=ident))
                    open_tools.append(messages[-1]["id"])
                continue
            content = step.get("content") if isinstance(step.get("content"), str) else ""
            if content:
                messages.append(item(base + ":text", "assistant", content[:16000], status=status, turnId=ident))
                open_tools = []
        elif kind == "GENERIC":
            content = step.get("content") if isinstance(step.get("content"), str) else ""
            if open_tools:
                cleaned = clean_agy_tool_output(content)
                for number, message in enumerate(messages):
                    if message["id"] == open_tools[0]:
                        messages[number] = {**message, "outcome": cleaned[:16000], "status": status}
                        break
                if status != "running": open_tools.pop(0)
            elif content:
                messages.append(item(base + ":out", "assistant", content[:16000], status=status, turnId=ident))
        elif kind == "USER_INPUT":
            content = step.get("content") if isinstance(step.get("content"), str) else ""
            if content: messages.append(item(base + ":user", "user", content[:16000], status="completed", turnId=ident))
        elif kind == "ERROR_MESSAGE":
            content = step.get("content") if isinstance(step.get("content"), str) else ""
            messages.append(item(base + ":error", "assistant", content[:16000] or "agy 子代理错误", status="failed", turnId=ident))
        elif kind == "SYSTEM_MESSAGE":
            _, body = parse_system_message(step.get("content"))
            if body: messages.append(item(base + ":system", "user", body[:16000], status="completed", turnId=ident))
    return messages[-500:]

class AgyChildTailMixin:
    def note_manage(self, params, known_children=None):
        if not isinstance(params, dict):
            return
        action = params.get("Action")
        if action == "kill_all":
            idents = list(self.subagents if known_children is None else known_children)
        elif action == "kill":
            idents = params.get("ConversationIds") if isinstance(params.get("ConversationIds"), list) else []
        else:
            return
        with self.lock:
            for ident in idents:
                if ident in self.subagents and self.subagents[ident].get("status") not in ("cancelled", "closed"):
                    self.subagent(ident, status="cancelled", nativeStatus="manage_subagents")

    def ensure_child_tail(self):
        if self.tail_thread and self.tail_thread.is_alive() and not self.tail_stop.is_set():
            return
        self.restart_child_tail()

    def changed_transcript(self, path):
        try:
            stat = pathlib.Path(path).stat()
        except OSError:
            return None
        version = (stat.st_mtime_ns, stat.st_size)
        if self.transcript_versions.get(path) == version:
            return None
        steps = read_transcript(path)
        self.transcript_versions[path] = version
        return steps

class AntigravityCliSession(AgyChildTailMixin, Session):
    input_evidence_protocol = 1
    argv = ["agy"]
    startup_timeout = 30

    def __init__(self, config, save):
        super().__init__(config, save)
        self.condition = threading.Condition(self.lock)
        self.ready = None
        self.startup_error = None
        self.current_submission = None
        self.turn_steps = {}
        self.last_checkpoint = 0
        self.checkpoint_timer = None
        self.checkpoint_generation = 0
        self.last_result_turn = None
        self.requested_model = ""
        self.turn_has_output = False
        self.tail_stop = threading.Event()
        self.tail_thread = None
        self.transcript_versions = {}
        self.parent_offset = 0
        self.parent_calls = []
        self.parent_children = set()
        self.stream_high = None
        self.stream_turn = None
        self.last_frame = 0.0
        self.pending_transcript = {}
        self.index_rows = {}
        self.restored_rows = set()
        self.restored_replay_buf = {}
    def environment(self):
        env = os.environ.copy()
        if self.config.get("browserConfig"): env["LUMA_BROWSER_CONFIG"] = self.config["browserConfig"]
        return env

    def discover(self):
        if not shutil.which(self.argv[0]): raise Rejected("未找到 agy；请在当前运行环境安装并完成 agy 原生登录。")
        try:
            version = subprocess.run([*self.argv, "--version"], env=self.environment(), cwd=self.config["cwd"],
                capture_output=True, timeout=10, check=False)
            match = re.search(rb"(\d+)\.(\d+)\.(\d+)", version.stdout)
            if version.returncode or not match or tuple(map(int, match.groups())) < (1, 1, 15):
                raise Rejected("此 agy 未提供所需 stream-json 接口；请使用 1.1.15 或更新版本。")
            result = subprocess.run([*self.argv, "models"], env=self.environment(), cwd=self.config["cwd"],
                capture_output=True, timeout=10, check=False)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise Rejected("agy 目录探测失败；请在终端核对安装与原生登录。") from exc
        if result.returncode: raise Rejected("无法读取 agy 模型目录；请在终端运行 agy 完成原生登录并核对配置。")
        models = []
        for line in result.stdout.decode("utf-8").strip().splitlines():
            parts = line.split("\t")
            if len(parts) != 2 or not all(parts) or any(m["id"] == parts[0] for m in models):
                raise Rejected("agy 模型目录格式无效")
            models.append({"id": parts[0], "label": parts[1], "efforts": [], "supportsImages": False})
        if not models: raise Rejected("agy 未返回可用模型")
        self.update(models=models)

    def restore_projection(self):
        saved = self.config.get("localProjection")
        if not saved: return
        if (saved.get("version"), saved.get("nativeProtocol"), saved.get("nativeId")) != (1, PROTOCOL, self.data["nativeId"]):
            raise Rejected("agy 本地投影与原生会话身份不匹配")
        for row in saved.get("messages", []):
            value = dict(row)
            if value.get("status") in ("running", "in_progress", "pending"): value["status"] = "unknown"
            if value.get("toolName") == "invoke_subagent" and value["id"].endswith(":subagent"):
                value["id"] = value["id"].removesuffix(":subagent") + ":tool"
                value = {**self.rows.get(value["id"], {}), **value}
            self.row(value)
            if value.get("role") == "assistant" and value.get("id"):
                self.restored_rows.add(value["id"])
            match = _ROW_INDEX.fullmatch(value.get("id", ""))
            if match:
                index = int(match.group(2))
                self.index_rows[(index, match.group(3))] = value["id"]
                if self.stream_high is None or index >= self.stream_high:
                    self.stream_high, self.stream_turn = index, match.group(1)
        for child in saved.get("subAgents", []):
            fields = {k: v for k, v in child.items() if k != "id"}
            if fields.get("itemId", "").endswith(":subagent"):
                fields["itemId"] = fields["itemId"].removesuffix(":subagent") + ":tool"
            if not fields.get("logPath"):
                fields["logPath"] = child_log_path(child["id"], "")
            self.subagent(child["id"], **fields)
        self.last_result_turn = saved.get("lastResultTurn")
        self.update(metrics=saved.get("metrics", []), historySource="luma-observed")
        if saved.get("unsettledSubmission"):
            self.update(error="上次 agy 回合终态未确认；仅恢复本地已观察内容，未重发消息。")

    def checkpoint(self, force=False, strict=False):
        with self.lock:
            left = 1 - (time.monotonic() - self.last_checkpoint)
            if not force and left > 0:
                if self.checkpoint_timer is None:
                    self.checkpoint_timer = threading.Timer(left, self.flush_projection, args=(self.checkpoint_generation,))
                    self.checkpoint_timer.daemon = True; self.checkpoint_timer.start()
                return
            self.checkpoint_generation += 1
            if self.checkpoint_timer:
                self.checkpoint_timer.cancel(); self.checkpoint_timer = None
            self.config["localProjection"] = copy.deepcopy({"version": 1, "nativeProtocol": PROTOCOL,
                "nativeId": self.data["nativeId"], "messages": list(self.rows.values()),
                "subAgents": list(self.subagents.values()), "metrics": self.data["metrics"],
                "lastResultTurn": self.last_result_turn, "unsettledSubmission": self.current_submission})
            try: self.save()
            except OSError:
                self.update(error="无法保存 agy 本地投影；原生执行状态仍以实际事件为准。")
                if strict: raise Rejected("无法保存发送状态，尚未写入 agy") from None
            else: self.last_checkpoint = time.monotonic()

    def flush_projection(self, generation):
        with self.lock:
            if generation != self.checkpoint_generation: return
            self.checkpoint_timer = None
            self.checkpoint(force=True)

    def connect(self):
        if self.config.get("nativeProtocol") != PROTOCOL: raise Rejected("此会话不属于 agy stream-json，不能切换原生协议")
        if self.config.get("creationUnknown") and not self.data["nativeId"]:
            raise Rejected("上次 agy 创建结果未知；请在原生会话列表核对 ID，不能自动再建")
        self.restore_projection()
        self.discover()
        model = self.config.get("model", "")
        if model and model not in {m["id"] for m in self.data["models"]}: raise Rejected("原会话模型不在 agy 当前目录中，请在终端核对")
        self.launch(model)

    def launch(self, model):
        native = self.data["nativeId"]
        self.requested_model = model
        args = [*self.argv, "--input-format", "stream-json", "--output-format", "stream-json",
                "--add-dir", self.config["cwd"], "--disable-slash-commands", "--dangerously-skip-permissions"]
        if model: args += ["--model", model]
        if native: args += ["--conversation", native]
        if not native:
            self.config["creationUnknown"] = True; self.save()
        with self.condition:
            self.ready = None; self.startup_error = None
            try:
                peer = AgyPeer(args, self.config["cwd"], self.environment(),
                    lambda frame: self.event(frame) if self.peer is peer else None,
                    lambda error: self.peer_exited(peer, error))
            except OSError:
                if not native: self.config["creationUnknown"] = False; self.save()
                raise
            self.peer = peer; peer.start_read()
            deadline = time.monotonic() + self.startup_timeout
            while self.ready is None and self.startup_error is None:
                left = deadline - time.monotonic()
                if left <= 0: self.startup_error = "agy 未在 30 秒内返回原生 init；不要自动重建或重发"
                else: self.condition.wait(left)
            failure = self.startup_error
        if failure:
            peer.close(interrupt=True); raise Rejected(failure)
        if peer.closed or peer.proc.poll() is not None:
            peer.close(); raise Rejected("agy 在初始化后退出，请在终端核对原生登录与配置")
        self.config["model"] = model; self.save()
        self.update(connected=True, running=False, model=self.ready.get("model") or "", effort="", notice="",
            permission=FULL_ACCESS, permissions=[{"id": FULL_ACCESS, "label": "全权限（跳过审批）"}],
            historySource="luma-observed", historyReady=True,
            capabilities={"queue": False, "steer": False, "stop": True, "images": False,
                          "skills": False, "compact": False, "rename": False})
        self.restart_child_tail()
        self.checkpoint(force=True)

    def event(self, frame):
        with self.condition:
            kind = frame.get("event")
            if kind == "init":
                if self.ready is not None: raise ValueError("agy 重复 init")
                ident, info = frame.get("conversation_id"), frame.get("init")
                if not isinstance(ident, str) or not _ID.fullmatch(ident) or not isinstance(info, dict): raise ValueError("agy init 身份无效")
                if self.data["nativeId"] and ident != self.data["nativeId"]: raise ValueError("agy 恢复了不同原生会话，已拒绝接入")
                if info.get("permission_mode") != FULL_ACCESS: raise ValueError("agy 未确认已选全权限模式")
                if self.requested_model and info.get("model") != self.requested_model: raise ValueError("agy 未确认所选原生模型")
                self.config["creationUnknown"] = False
                self.update(nativeId=ident)
                self.ready = info; self.condition.notify_all(); return
            payload = frame.get("step_update" if kind == "step_update" else "result")
            if not isinstance(payload, dict): return
            if self.ready is None:
                if kind == "result" and payload.get("status") == "ERROR":
                    if payload.get("conversation_id") == "" and not self.data["nativeId"]:
                        self.config["creationUnknown"] = False; self.save()
                    self.startup_error = native_error(payload.get("error")); self.condition.notify_all()
                return
            if payload.get("conversation_id") != self.data["nativeId"]: return
            if kind == "step_update": self.step(payload)
            elif kind == "result" and self.current_submission: self.result(payload)

    def accept_submission(self):
        ident = self.current_submission
        if not ident: return
        if self.config.get("receipts", {}).get(ident) != "accepted": self.receipt(ident, "accepted")
        self.consumed(ident)
        if ident in self.rows: self.row({**self.rows[ident], "status": "completed"})

    def step(self, step):
        index, state, kind = step.get("step_index"), step.get("state"), step.get("step_type")
        if type(index) is not int or index < 0 or state not in ("ACTIVE", "DONE", "ERROR"): raise ValueError("agy step 身份或状态无效")
        turn = self.current_submission or "background"
        row_kind = "tool" if kind == "subagent" else kind
        self.last_frame = time.monotonic()
        if row_kind in ("agent_response", "tool"):
            if self.stream_high is None or index >= self.stream_high:
                self.stream_high, self.stream_turn = index, turn
            self.pending_transcript.pop(index, None)
        filled = self.index_rows.get((index, row_kind))
        ident = filled or f"agy:{turn}:{index}:{row_kind}"
        if filled is None and row_kind in ("agent_response", "tool"): self.index_rows[(index, row_kind)] = ident
        if kind in ("user_input", "agent_response", "tool", "subagent"): self.accept_submission()
        status = {"ACTIVE": "running", "DONE": "completed", "ERROR": "failed"}[state]
        if kind == "agent_response":
            delta = step.get("text_delta", "")
            if not isinstance(delta, str): raise ValueError("agy text_delta 无效")
            old = self.rows.get(ident, item(ident, "assistant", "", turnId=turn))
            if old.get("source") == "transcript":
                delta = ""
            elif ident in self.restored_rows:
                prev_text = old.get("text", "")
                buf = self.restored_replay_buf.get(ident, "") + delta
                self.restored_replay_buf[ident] = buf
                if prev_text.startswith(buf):
                    new_text = prev_text
                elif buf.startswith(prev_text):
                    new_text = buf
                    self.restored_rows.discard(ident)
                    self.restored_replay_buf.pop(ident, None)
                else:
                    new_text = prev_text
                if state != "ACTIVE":
                    self.restored_rows.discard(ident)
                    self.restored_replay_buf.pop(ident, None)
                self.row({**old, "text": new_text, "status": status})
                self.turn_has_output = True
                self.checkpoint(force=state != "ACTIVE")
                return
            if delta or ident in self.rows:
                self.row({**old, "text": old["text"] + delta, "status": status})
                self.turn_has_output = True
        elif kind in ("tool", "subagent"):
            info = step.get("tool_info") or {}; name = info.get("name") or step.get("tool_name", "")
            old = self.rows.get(ident, item(ident, "tool", name, kind="tool", turnId=turn))
            changes = {"toolName": name or old.get("toolName", ""), "status": "failed" if info.get("error") else status}
            if "parameters" in info: changes["input"] = json.dumps(info["parameters"], ensure_ascii=False)
            if "output" in info: changes["outcome"] = info["output"]
            if info.get("error"): changes["outcome"] = json.dumps(info["error"], ensure_ascii=False)
            self.row({**old, **changes})
            if not info.get("error") and state != "ERROR":
                for child in (step.get("subagent_info") or {}).get("subagents", []):
                    child_id = child.get("conversation_id")
                    if isinstance(child_id, str) and _ID.fullmatch(child_id):
                        self.register_child(child_id, ident, turn, child.get("type_name", ""),
                            child.get("role", ""), child.get("initial_prompt", ""), child.get("log_uri", ""))
                if name == "invoke_subagent": self.note_invoke(ident, turn, info)
                elif name == "manage_subagents" and state == "DONE": self.note_manage(info.get("parameters") or json.loads(old.get("input") or "{}"))
        if state == "DONE" and isinstance(step.get("usage"), dict):
            self.turn_steps[index] = step["usage"]
            total = agy_steps(list(self.turn_steps.values()))
            self.usage(total, "本回合")
        self.checkpoint(force=state != "ACTIVE")

    def note_invoke(self, ident, turn, info):
        params = info.get("parameters") if isinstance(info.get("parameters"), dict) else {}
        child_id, uri = invoke_child(info.get("output"))
        if not child_id: return
        self.register_child(child_id, ident, turn, params.get("TypeName") or "",
            params.get("Role") or "", params.get("Prompt") or "", uri, params.get("Model", ""))

    def register_child(self, child_id, ident, turn, name, role, task, uri, model=""):
        fields = {"name": name, "role": role, "task": task, "itemId": ident, "turnId": turn}
        if isinstance(model, str) and model: fields["model"] = model
        path = child_log_path(child_id, uri)
        if path: fields["logPath"] = path
        if self.subagents.get(child_id, {}).get("status", "unknown") == "unknown": fields["status"] = "running"
        self.subagent(child_id, **fields)
        if path: self.ensure_child_tail()

    def restart_child_tail(self):
        self.tail_stop.set()
        self.tail_stop = threading.Event()
        self.transcript_versions = {}
        self.tail_thread = threading.Thread(target=self.tail_children, args=(self.tail_stop,), name="LumaAgyChildLog", daemon=True)
        self.tail_thread.start()

    def refresh_parent_log(self):
        path = child_log_path(self.data["nativeId"], "")
        if not path: return
        full_path = str(pathlib.Path(path).with_name("transcript_full.jsonl"))
        changed = False
        try:
            with open(path, "rb") as source:
                if os.fstat(source.fileno()).st_size < self.parent_offset:
                    self.parent_offset = 0; self.parent_calls = []; self.parent_children.clear()
                source.seek(self.parent_offset)
                while True:
                    line = source.readline()
                    if not line or not line.endswith(b"\n"): break
                    self.parent_offset = source.tell()
                    try: step = json.loads(line)
                    except ValueError: continue
                    if not isinstance(step, dict): continue
                    if "content" in (step.get("truncated_fields") or []):
                        step = read_full_step(full_path, step.get("step_index")) or step
                    self.note_transcript_step(step)
                    if step.get("type") == "PLANNER_RESPONSE":
                        self.parent_calls = [c for c in step.get("tool_calls", []) if isinstance(c, dict)]
                    elif step.get("type") == "GENERIC" and self.parent_calls:
                        call = self.parent_calls[0]
                        if step.get("status") not in ("RUNNING", "ACTIVE"): self.parent_calls.pop(0)
                        if step.get("status") != "DONE": continue
                        index = step.get("step_index")
                        info = {"parameters": transcript_args(call), "output": step.get("content", "")}
                        with self.lock:
                            if call.get("name") == "invoke_subagent":
                                child_id, _ = invoke_child(info["output"])
                                if child_id: self.parent_children.add(child_id)
                                suffixes = (f":{index}:tool", f":{index}:subagent")
                                anchor = next((r for key, r in self.rows.items() if key.endswith(suffixes)), {})
                                self.note_invoke(anchor.get("id", ""), anchor.get("turnId", ""), info)
                                changed = bool(child_id) or changed
                            elif call.get("name") == "manage_subagents":
                                self.note_manage(info["parameters"], self.parent_children)
                                changed = True
        except OSError: return
        if self.backfill_held_steps(): changed = True
        if changed: self.checkpoint()

    def note_transcript_step(self, step):
        index, kind = step.get("step_index"), step.get("type")
        if type(index) is not int or kind not in ("PLANNER_RESPONSE", "GENERIC"): return
        call = None
        if kind == "GENERIC":
            if not self.parent_calls: return
            call = self.parent_calls[0]
        elif not (step.get("status") == "DONE" and isinstance(step.get("content"), str) and step["content"].strip()):
            return
        with self.lock:
            if self.stream_high is None or index <= self.stream_high: return
            self.pending_transcript[index] = (step, call)

    def backfill_held_steps(self):
        with self.lock:
            if self.stream_high is not None:
                for index in [i for i in self.pending_transcript if i <= self.stream_high]:
                    del self.pending_transcript[index]
            if not self.pending_transcript or time.monotonic() - self.last_frame < STREAM_STALL_SECONDS: return False
            turn = self.current_submission or self.stream_turn or "background"
            for index in sorted(self.pending_transcript):
                step, call = self.pending_transcript[index]
                state = step.get("status")
                status = "running" if state in ("RUNNING", "ACTIVE") else "failed" if state == "ERROR" else "completed"
                content = step.get("content") if isinstance(step.get("content"), str) else ""
                if step.get("type") == "PLANNER_RESPONSE":
                    key = (index, "agent_response")
                    ident = self.index_rows.setdefault(key, f"agy:{turn}:{index}:agent_response")
                    old = self.rows.get(ident, item(ident, "assistant", "", turnId=turn))
                    self.row({**old, "text": content, "status": status, "source": "transcript"})
                    self.turn_has_output = True
                else:
                    key = (index, "tool")
                    ident = self.index_rows.setdefault(key, f"agy:{turn}:{index}:tool")
                    name = call.get("name") if isinstance(call.get("name"), str) else ""
                    old = self.rows.get(ident, item(ident, "tool", name, kind="tool", toolName=name, turnId=turn))
                    changes = {"toolName": old.get("toolName") or name, "status": status, "source": "transcript"}
                    if not old.get("input"): changes["input"] = json.dumps(transcript_args(call), ensure_ascii=False)[:16000]
                    if content and status != "running": changes["outcome"] = content[:16000]
                    self.row({**old, **changes})
            self.pending_transcript.clear()
            return True

    def tail_children(self, stop):
        while not stop.wait(0.2):
            self.refresh_parent_log()
            with self.lock:
                jobs = [(ident, child.get("logPath")) for ident, child in self.subagents.items() if child.get("logPath")]
            for ident, path in jobs:
                if stop.is_set(): return
                self.refresh_child_log(ident, path)

    def refresh_child_log(self, ident, path):
        steps = self.changed_transcript(path)
        if steps is None: return
        messages = project_child_transcript(ident, steps)
        detail = ""
        for message in reversed(messages):
            if message.get("role") == "assistant" and message.get("text"):
                detail = message["text"][:500]; break
            if message.get("status") == "running" and message.get("toolName"):
                detail = message["toolName"]; break
        with self.lock:
            old = self.subagents.get(ident)
            if not old: return
            fields = {"messages": messages, "detail": detail}
            state = child_transcript_state(steps)
            if state and old.get("nativeStatus") != "manage_subagents":
                fields.update(status="succeeded" if state == "idle" else state, nativeStatus="transcript:" + state)
            if all(old.get(key) == value for key, value in fields.items()): return
            self.subagent(ident, **fields)
        self.checkpoint()

    def usage(self, usage, scope):
        native_id = self.data.get("nativeId") or self.config.get("nativeId", "")
        model = self.data.get("model") or self.config.get("model", "")
        rows = agy_session_metrics(
            native_id,
            model=model,
            step_usages=list(self.turn_steps.values()) if self.turn_steps else None,
            result_usage=usage if scope == "CLI 累计" else None,
        )
        if not rows:
            rows = agy_usage(usage, scope, model=model)
        self.update(metrics=rows)

    def result(self, result):
        status = result.get("status")
        if status not in ("SUCCESS", "ERROR", "CANCELED", "INTERRUPTED", "INVALID", "WAITING", "RUNNING"):
            raise ValueError("agy result 状态无效")
        if status in ("WAITING", "RUNNING"):
            self.update(error="agy 返回了非终态结果（" + status + "）；请核对，不会自动重发。")
            self.checkpoint(force=True); return
        if status == "SUCCESS": self.accept_submission()
        turn = self.current_submission
        cancelled = status in ("CANCELED", "INTERRUPTED") or (status == "ERROR" and
            (result.get("error") == "interrupted" or result.get("finished_reason") == "interrupted"))
        if not self.turn_has_output and result.get("response"):
            self.row(item(f"agy:{turn}:result", "assistant", result["response"], turnId=turn,
                          status="cancelled" if cancelled else "completed" if status == "SUCCESS" else "failed"))
        for ident, row in list(self.rows.items()):
            if row.get("turnId") == turn and row.get("status") == "running" and row.get("role") == "assistant":
                self.row({**row, "status": "cancelled" if cancelled else "completed" if status == "SUCCESS" else "failed"})
        self.settle_incomplete_rows("unknown")
        if isinstance(result.get("usage"), dict): self.usage(result["usage"], "CLI 累计")
        self.last_result_turn = result.get("num_turns")
        self.current_submission = None
        error = None if status == "SUCCESS" or cancelled else native_error(result.get("error"))
        if result.get("denied_actions"):
            error = "agy 原生策略拒绝了操作：" + "、".join(str(a.get("display_name") or a.get("action", "")) for a in result["denied_actions"])
        self.update(running=False, error=error)
        self.checkpoint(force=True)

    def prompt(self, payload):
        self.current_submission = payload["submissionId"]; self.turn_steps = {}; self.turn_has_output = False
        self.update(running=True, error=None)
        self.row(item(payload["submissionId"], "user", payload["text"], turnId=payload["submissionId"], status="pending", origin="local"))
        try:
            self.checkpoint(force=True, strict=True)
            self.peer.send({"event": "user", "message": {"content": payload["text"]}})
        except Rejected:
            self.current_submission = None; self.update(running=False); raise
        except Exception:
            self.update(connected=not self.peer.closed, running=not self.peer.closed,
                        error="提交到 agy 的结果未知；请核对，不要自动重发。")
            self.checkpoint(force=True)
            raise
        return False

    def peer_exited(self, peer, error):
        with self.condition:
            if self.peer is not peer: return
            if self.ready is None:
                self.startup_error = self.startup_error or str(error); self.condition.notify_all()
            active = self.current_submission is not None
            if active: self.settle_incomplete_rows("unknown")
            self.tail_stop.set()
            self.update(connected=False, running=False, **({"error": "agy 已退出，本回合未收到原生结算；结果未知，请核对后重连。"} if active else
                {} if peer.expected_close else {"error": str(error)}))
            self.checkpoint(force=True)

    def configure(self, payload):
        if self.data["running"]: raise Rejected("请等待 agy 当前回合结束")
        if payload.get("effort") or payload.get("speed"): raise Rejected("agy 强度随原生模型 ID 选择；没有独立强度或速度接口")
        if payload.get("permission") and payload["permission"] != FULL_ACCESS: raise Rejected("此 agy 入口只支持已选全权限模式")
        model = payload.get("model")
        if model and model != self.data["model"]:
            if model not in {m["id"] for m in self.data["models"]}: raise Rejected("模型不在 agy 原生目录中")
            self.update(connected=False)
            self.peer.close()
            self.launch(model)
        return {}

    def refresh(self):
        self.discover()
        rows = agy_session_metrics(self.data.get("nativeId"), model=self.data.get("model") or self.config.get("model", ""))
        if rows:
            self.update(metrics=rows)
        return {}

    def stop(self):
        with self.operation:
            if not self.current_submission: return {}
            self.update(connecting=True, connected=False)
            try:
                self.peer.close(interrupt=True)
                if self.current_submission is None and self.peer.proc.poll() is not None:
                    self.launch(self.config.get("model", ""))
            finally:
                self.update(connecting=False)
        return {}

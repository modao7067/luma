import hashlib
import json
import os
import re
import threading
import time
from pathlib import Path
from .acp import AcpSession
from .i18n import tr
from .media import display_content
from .transport import Rejected

_TERMINAL_AGENT_STATUSES = {"succeeded", "completed", "failed", "cancelled", "closed", "aborted"}
_PROMPT_PREFIX_RE = re.compile(r"^(?:\s*<(?:git-context|system-reminder)>[\s\S]*?</(?:git-context|system-reminder)>\s*)+")
_LAUNCHING_TITLE_RE = re.compile(r"^Launching\s+\S+\s+agent:\s*(.*)$", re.DOTALL)
_OUTCOME_KV_RE = re.compile(r"^(agent_id|actual_subagent_type|status|stop_reason|task_id):\s*(.*)$")

def _unwrap_outcome_text(raw):
    if not isinstance(raw, str) or not raw:
        return ""
    stripped = raw.strip()
    if stripped.startswith(('"', "{", "[")):
        try:
            parsed = json.loads(stripped)
        except ValueError:
            return raw
        if isinstance(parsed, str):
            return parsed
        if isinstance(parsed, dict):
            if isinstance(parsed.get("output"), str):
                return parsed["output"]
            if "content" in parsed:
                return display_content(parsed["content"])
        if isinstance(parsed, list):
            return display_content(parsed)
    return raw

def _parse_agent_output(raw):
    text_val = _unwrap_outcome_text(raw)
    if not text_val:
        return {}
    meta = {}
    lines = text_val.splitlines()
    summary_start = None
    for idx, line in enumerate(lines):
        if line.strip() == "[summary]":
            summary_start = idx + 1
            break
        m = _OUTCOME_KV_RE.match(line.strip())
        if m:
            meta[m.group(1)] = m.group(2).strip()
    if summary_start is not None:
        tail = "\n".join(lines[summary_start:])
        tail = re.split(r"\n+resume_hint:", tail, maxsplit=1)[0].strip()
        if tail:
            meta["summary"] = tail
    return meta

def _parse_json_dict(raw):
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str):
        s = raw.strip()
        if s.startswith("{"):
            try:
                parsed = json.loads(s)
            except ValueError:
                return {}
            if isinstance(parsed, dict):
                return parsed
    return {}

def _map_subagent_status(native_status, fallback_tool_status=""):
    raw = (native_status or "").strip().lower()
    if raw in ("completed", "done", "succeeded"):
        return "succeeded", "completed"
    if raw in ("failed", "error"):
        return "failed", raw
    if raw in ("cancelled", "canceled", "aborted", "interrupted"):
        return "cancelled", raw
    if raw in ("running", "in_progress", "started"):
        return "running", "running"
    if raw == "pending":
        return "pending", "pending"
    tool = (fallback_tool_status or "").strip().lower()
    if tool == "completed":
        return "succeeded", "completed"
    if tool == "failed":
        return "failed", "failed"
    if tool in ("cancelled", "canceled"):
        return "cancelled", "cancelled"
    if tool in ("running", "in_progress", "pending"):
        return "running", "running"
    return "unknown", "unknown"

def _kimi_tool_kind(name, display):
    if isinstance(display, dict) and display.get("kind") == "command":
        return "execute"
    if name in ("Bash", "Shell"):
        return "execute"
    if name in ("Read", "ListDir"):
        return "read"
    if name in ("Glob", "Grep", "WebSearch"):
        return "search"
    if name in ("Write", "Edit", "MultiEdit", "NotebookEdit"):
        return "edit"
    if name == "WebFetch":
        return "fetch"
    return ""

def _read_jsonl_incremental(path, cursor):
    try:
        st = path.stat()
    except OSError:
        return [], False
    reset = False
    if cursor["inode"] != st.st_ino or st.st_size < cursor["offset"]:
        cursor["inode"] = st.st_ino
        cursor["offset"] = 0
        reset = True
    if st.st_size == cursor["offset"]:
        return [], reset
    try:
        with open(path, "rb") as f:
            f.seek(cursor["offset"])
            chunk = f.read()
    except OSError:
        return [], reset
    last_nl = chunk.rfind(b"\n")
    if last_nl < 0:
        return [], reset
    cursor["offset"] += last_nl + 1
    records = []
    for line in chunk[:last_nl + 1].decode("utf-8", errors="replace").splitlines():
        s = line.strip()
        if not s:
            continue
        try:
            obj = json.loads(s)
        except ValueError:
            continue
        if isinstance(obj, dict):
            records.append(obj)
    return records, reset

class KimiSession(AcpSession):
    replay_release = True
    argv = ["kimi", "acp"]
    supports_forms = True
    builtins = {"compact", "status", "usage", "mcp", "tasks", "help"}

    def __init__(self, config, save):
        super().__init__(config, save)
        self._subagent_sync_lock = threading.Lock()
        self._reset_subagent_tracking()

    def _reset_subagent_tracking(self):
        self._call_to_agent = {}
        self._call_meta = {}
        self._main_wire_cursor = {"inode": None, "offset": 0}
        self._state_stat = None
        self._agent_wires = {}
        self._kimi_session_dir = None
        self._kimi_session_dir_id = ""
        self._last_subagent_sync = 0.0
        self._was_running = False

    def connect(self):
        with self._subagent_sync_lock:
            self._reset_subagent_tracking()
        if self.config.get("nativeProtocol"):
            self.update(nativeProtocol=self.config["nativeProtocol"])
        if self.config.get("protocolFallbackNotice"):
            self.update(permissionNotice=self.config["protocolFallbackNotice"])
        super().connect()
        self._sync_subagents(force=True)

    def apply_commands(self, commands):
        super().apply_commands(commands)
        skills = [{"id": c["name"], "name": c["name"], "path": ""}
                  for c in commands if c.get("name") and c["name"] not in self.builtins]
        self.update(skills=skills, capabilities={**self.data["capabilities"], "skills": bool(skills), "maxSkills": 1})

    def validate_prompt(self, p):
        super().validate_prompt(p)
        if len(p.get("skills", [])) > 1: raise Rejected(tr("Kimi Code 每条消息最多选一个 Skill", "Kimi Code allows at most one Skill per message"))
        if any(k not in {s["id"] for s in self.data["skills"]} for k in p.get("skills", [])):
            raise Rejected(tr("Skill 不在原生目录中", "Skill is not in the native catalog"))

    def prompt(self, p):
        if p.get("skills"):
            p = {**p, "text": "/" + p["skills"][0] + " " + p["text"]}
        return super().prompt(p)

    def event(self, e):
        super().event(e)
        if e.get("method") != "session/update":
            return
        u = e.get("params", {}).get("update")
        if not isinstance(u, dict):
            return
        kind = u.get("sessionUpdate")
        call_id = u.get("toolCallId")
        if kind in ("tool_call", "tool_call_update") and isinstance(call_id, str) and call_id:
            self._on_tool_event(call_id)

    def snapshot(self, since=None, touch=True):
        with self.lock:
            should_check = (
                self.data.get("connected")
                and not self.released
                and not self.close_pending
            )
            running = bool(self.data.get("running"))
            has_active_sub = any(
                a.get("status") not in _TERMINAL_AGENT_STATUSES
                for a in self.subagents.values()
            )
        if should_check:
            if running or has_active_sub:
                if running:
                    self._was_running = True
                if time.monotonic() - self._last_subagent_sync >= 0.35:
                    self._sync_subagents(force=False)
            elif self._was_running:
                self._was_running = False
                self._sync_subagents(force=True)
        return super().snapshot(since, touch)

    def _resolve_session_dir(self):
        native_id = self.data.get("nativeId") or self.config.get("nativeId") or ""
        if not native_id:
            return None
        if (
            self._kimi_session_dir is not None
            and self._kimi_session_dir_id == native_id
            and self._kimi_session_dir.is_dir()
        ):
            return self._kimi_session_dir
        folder = native_id if native_id.startswith("session_") else "session_" + native_id
        homes = []
        for candidate in (
            os.environ.get("KIMI_CODE_HOME"),
            os.environ.get("KIMI_SHARE_DIR"),
            str(Path.home() / ".kimi-code"),
            str(Path.home() / ".kimi"),
        ):
            if candidate and candidate not in homes:
                homes.append(candidate)
        cwd = self.config.get("cwd", "")
        hashes = []
        for path_variant in (cwd, os.path.realpath(cwd) if cwd else ""):
            if not path_variant:
                continue
            digest = hashlib.sha256(path_variant.encode("utf-8")).hexdigest()[:12]
            if digest not in hashes:
                hashes.append(digest)
        for home in homes:
            sessions_root = Path(home) / "sessions"
            if not sessions_root.is_dir():
                continue
            for digest in hashes:
                for match in sessions_root.glob(f"wd_*_{digest}/{folder}"):
                    if match.is_dir():
                        self._kimi_session_dir = match
                        self._kimi_session_dir_id = native_id
                        return match
            for match in sessions_root.glob(f"*/{folder}"):
                if match.is_dir():
                    self._kimi_session_dir = match
                    self._kimi_session_dir_id = native_id
                    return match
        return None

    def _pick_model(self, alias, raw_model):
        known = {m["id"] for m in self.data.get("models", []) if isinstance(m, dict) and isinstance(m.get("id"), str)}
        if isinstance(alias, str) and alias in known:
            return alias
        if isinstance(raw_model, str) and raw_model in known:
            return raw_model
        if isinstance(alias, str) and alias:
            return alias
        if isinstance(raw_model, str) and raw_model:
            return raw_model
        return ""

    def _bind_call_agent_locked(self, call_id, agent_id, name="", role="", task="", model="", status="", native_status="", detail=""):
        if not agent_id:
            return
        if call_id:
            self._call_to_agent[call_id] = agent_id
        pending_data = {}
        if call_id:
            with self.lock:
                popped = self.subagents.pop("pending:" + call_id, None)
                if popped is not None:
                    pending_data = popped
                    self.touch()
                    self.seq += 1
        fields = {}
        if call_id:
            fields["itemId"] = "tool-" + call_id
        resolved_name = name or pending_data.get("name", "")
        resolved_role = role or pending_data.get("role", "")
        resolved_task = task or pending_data.get("task", "")
        resolved_model = model or pending_data.get("model", "")
        if resolved_name:
            fields["name"] = resolved_name
        if resolved_role:
            fields["role"] = resolved_role
        if resolved_task:
            fields["task"] = resolved_task
        if resolved_model:
            fields["model"] = resolved_model
        if status:
            fields["status"] = status
        if native_status:
            fields["nativeStatus"] = native_status
        if detail:
            fields["detail"] = detail
        self.subagent(agent_id, **fields)

    def _on_tool_event(self, call_id):
        row_id = "tool-" + call_id
        with self._subagent_sync_lock:
            with self.lock:
                row = dict(self.rows.get(row_id, {}))
            if not row:
                return
            if not row.get("toolName") and isinstance(row.get("text"), str) and _LAUNCHING_TITLE_RE.match(row["text"]):
                with self.lock:
                    cur = self.rows.get(row_id)
                    if cur and not cur.get("toolName"):
                        self.row({**cur, "toolName": "Agent"})
                        row = dict(self.rows.get(row_id, row))
            args = _parse_json_dict(row.get("input"))
            if not args:
                args = _parse_json_dict(row.get("outcome"))
            out_meta = _parse_agent_output(row.get("outcome", ""))
            is_agent_tool = (
                row.get("toolName") in ("Agent", "AgentSwarm")
                or ("agent_id" in out_meta and "actual_subagent_type" in out_meta)
                or call_id in self._call_to_agent
            )
            if not is_agent_tool:
                return
            cached = self._call_meta.get(call_id, {})
            name = (
                (args.get("description") if isinstance(args.get("description"), str) else "")
                or cached.get("name", "")
            )
            if not name and isinstance(row.get("text"), str):
                m = _LAUNCHING_TITLE_RE.match(row["text"].strip())
                if m:
                    name = m.group(1).strip()
            role = (
                out_meta.get("actual_subagent_type")
                or (args.get("subagent_type") if isinstance(args.get("subagent_type"), str) else "")
                or cached.get("role", "")
            )
            task = (
                (args.get("prompt") if isinstance(args.get("prompt"), str) else "")
                or cached.get("task", "")
            )
            model = (
                (args.get("model") if isinstance(args.get("model"), str) else "")
                or cached.get("model", "")
            )
            summary = out_meta.get("summary", "")
            self._call_meta[call_id] = {"name": name, "role": role, "task": task, "model": model}
            status, native_status = _map_subagent_status(out_meta.get("status", ""), row.get("status", ""))
            if status == "unknown" and not out_meta.get("status") and not row.get("status"):
                status, native_status = "running", "running"
            agent_id = (
                out_meta.get("agent_id")
                or (args.get("resume") if isinstance(args.get("resume"), str) else "")
                or self._call_to_agent.get(call_id, "")
            )
            if not agent_id:
                self._sync_subagents_locked(force=True)
                agent_id = self._call_to_agent.get(call_id, "")
            if agent_id:
                self._bind_call_agent_locked(
                    call_id,
                    agent_id,
                    name=name,
                    role=role,
                    task=task,
                    model=model,
                    status=status,
                    native_status=native_status,
                    detail=summary,
                )
            else:
                temp_fields = {
                    "itemId": row_id,
                    "name": name,
                    "role": role,
                    "task": task,
                    "status": status if status != "unknown" else "running",
                    "nativeStatus": native_status if native_status != "unknown" else "running",
                }
                if model:
                    temp_fields["model"] = model
                self.subagent("pending:" + call_id, **temp_fields)
            self._sync_subagents_locked(force=True)

    def _sync_subagents(self, force=False):
        with self._subagent_sync_lock:
            self._sync_subagents_locked(force=force)

    def _sync_subagents_locked(self, force=False):
        self._last_subagent_sync = time.monotonic()
        sdir = self._resolve_session_dir()
        if sdir is None:
            return
        main_wire = sdir / "agents" / "main" / "wire.jsonl"
        main_records, _ = _read_jsonl_incremental(main_wire, self._main_wire_cursor)
        for rec in main_records:
            rtype = rec.get("type")
            if rtype == "context.append_loop_event":
                ev = rec.get("event")
                if not isinstance(ev, dict) or ev.get("type") != "tool.call":
                    continue
                tc_id = ev.get("toolCallId")
                tname = ev.get("name")
                if isinstance(tc_id, str) and tc_id and isinstance(tname, str) and tname:
                    with self.lock:
                        cur = self.rows.get("tool-" + tc_id)
                        if cur and not cur.get("toolName"):
                            self.row({**cur, "toolName": tname})
                    if tname in ("Agent", "AgentSwarm") and isinstance(ev.get("args"), dict):
                        args = ev["args"]
                        prev_meta = self._call_meta.get(tc_id, {})
                        self._call_meta[tc_id] = {
                            "name": args.get("description") if isinstance(args.get("description"), str) else prev_meta.get("name", ""),
                            "role": args.get("subagent_type") if isinstance(args.get("subagent_type"), str) else prev_meta.get("role", ""),
                            "task": args.get("prompt") if isinstance(args.get("prompt"), str) else prev_meta.get("task", ""),
                            "model": args.get("model") if isinstance(args.get("model"), str) else prev_meta.get("model", ""),
                        }
            elif rtype == "subagent.spawned":
                aid = rec.get("subagentId")
                if not isinstance(aid, str) or not aid:
                    continue
                ptc = rec.get("parentToolCallId") if isinstance(rec.get("parentToolCallId"), str) else ""
                cached = self._call_meta.get(ptc, {}) if ptc else {}
                name = (rec.get("description") if isinstance(rec.get("description"), str) else "") or cached.get("name", "")
                role = (rec.get("subagentName") if isinstance(rec.get("subagentName"), str) else "") or cached.get("role", "")
                task = cached.get("task", "")
                model = self._pick_model(rec.get("model"), None) or cached.get("model", "")
                with self.lock:
                    existing_status = self.subagents.get(aid, {}).get("status", "")
                spawn_status = existing_status if existing_status in _TERMINAL_AGENT_STATUSES else "running"
                spawn_native = "completed" if spawn_status == "succeeded" else (existing_status or "running")
                self._bind_call_agent_locked(
                    ptc,
                    aid,
                    name=name,
                    role=role,
                    task=task,
                    model=model,
                    status=spawn_status,
                    native_status=spawn_native,
                )
            elif rtype == "subagent.completed":
                aid = rec.get("subagentId")
                if not isinstance(aid, str) or not aid:
                    continue
                fields = {"status": "succeeded", "nativeStatus": "completed"}
                if isinstance(rec.get("resultSummary"), str) and rec["resultSummary"]:
                    fields["detail"] = rec["resultSummary"]
                self.subagent(aid, **fields)
            elif rtype in ("subagent.failed", "subagent.cancelled"):
                aid = rec.get("subagentId")
                if not isinstance(aid, str) or not aid:
                    continue
                st = "failed" if rtype == "subagent.failed" else "cancelled"
                fields = {"status": st, "nativeStatus": st}
                err_detail = rec.get("error") or rec.get("resultSummary") or ""
                if err_detail:
                    fields["detail"] = str(err_detail)
                self.subagent(aid, **fields)

        state_file = sdir / "state.json"
        try:
            st = state_file.stat()
            sig = (st.st_mtime_ns, st.st_size)
        except OSError:
            sig = None
        if sig is not None and sig != self._state_stat:
            self._state_stat = sig
            try:
                state_obj = json.loads(state_file.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                state_obj = {}
            agents_map = state_obj.get("agents") if isinstance(state_obj, dict) else None
            if isinstance(agents_map, dict):
                for aid, meta in agents_map.items():
                    if aid == "main" or not isinstance(meta, dict) or meta.get("type") == "main":
                        continue
                    labels = meta.get("labels") if isinstance(meta.get("labels"), dict) else {}
                    profile = labels.get("profileName") if isinstance(labels.get("profileName"), str) else ""
                    with self.lock:
                        existing = self.subagents.get(aid)
                    if existing is None:
                        self.subagent(aid, role=profile, status="unknown")
                    elif profile and not existing.get("role"):
                        self.subagent(aid, role=profile)

        with self.lock:
            candidate_ids = [aid for aid in self.subagents if not aid.startswith("pending:")]
        for aid in candidate_ids:
            wire_path = sdir / "agents" / aid / "wire.jsonl"
            if not wire_path.is_file():
                continue
            ws = self._agent_wires.get(aid)
            if ws is None:
                ws = {
                    "cursor": {"inode": None, "offset": 0},
                    "model": "",
                    "messages": [],
                    "task": "",
                    "detail": "",
                    "status": "",
                    "native_status": "",
                    "last_step_uuid": "",
                }
                self._agent_wires[aid] = ws
            records, reset = _read_jsonl_incremental(wire_path, ws["cursor"])
            if reset:
                ws["model"] = ""
                ws["messages"] = []
                ws["task"] = ""
                ws["detail"] = ""
                ws["status"] = ""
                ws["native_status"] = ""
                ws["last_step_uuid"] = ""
            if not records and not reset:
                continue
            status_dirty = False
            for rec in records:
                rtype = rec.get("type")
                if rtype == "profile.bind":
                    picked = self._pick_model(rec.get("modelAlias"), rec.get("model"))
                    if picked and not ws["model"]:
                        ws["model"] = picked
                elif rtype == "llm.request":
                    picked = self._pick_model(rec.get("modelAlias"), rec.get("model"))
                    if picked:
                        ws["model"] = picked
                elif rtype == "turn.prompt":
                    raw_parts = []
                    for blk in rec.get("input", []):
                        if isinstance(blk, dict) and isinstance(blk.get("text"), str):
                            raw_parts.append(blk["text"])
                    clean_prompt = _PROMPT_PREFIX_RE.sub("", "\n".join(raw_parts)).strip()
                    if clean_prompt:
                        if not ws["task"]:
                            ws["task"] = clean_prompt
                        if not (ws["messages"] and ws["messages"][-1].get("role") == "user" and ws["messages"][-1].get("text") == clean_prompt):
                            ws["messages"].append({"id": f"{aid}:user:{len(ws['messages'])}", "role": "user", "text": clean_prompt})
                elif rtype == "agent.turn.started":
                    ws["status"] = "running"
                    ws["native_status"] = "running"
                    status_dirty = True
                elif rtype == "context.append_loop_event":
                    ev = rec.get("event")
                    if not isinstance(ev, dict):
                        continue
                    etype = ev.get("type")
                    if etype == "content.part":
                        part = ev.get("part") if isinstance(ev.get("part"), dict) else {}
                        ptype = part.get("type")
                        step_uuid = ev.get("stepUuid") if isinstance(ev.get("stepUuid"), str) else ""
                        if ptype == "think" and isinstance(part.get("think"), str) and part["think"]:
                            if (
                                ws["messages"]
                                and ws["messages"][-1].get("kind") == "reasoning"
                                and step_uuid
                                and ws["last_step_uuid"] == step_uuid
                            ):
                                ws["messages"][-1] = {**ws["messages"][-1], "text": ws["messages"][-1]["text"] + part["think"]}
                            else:
                                ws["messages"].append({
                                    "id": f"{aid}:think:{ev.get('uuid') or len(ws['messages'])}",
                                    "role": "tool",
                                    "kind": "reasoning",
                                    "text": part["think"],
                                    "status": "completed",
                                })
                            ws["last_step_uuid"] = step_uuid
                        elif ptype == "text" and isinstance(part.get("text"), str) and part["text"]:
                            if (
                                ws["messages"]
                                and ws["messages"][-1].get("role") == "assistant"
                                and step_uuid
                                and ws["last_step_uuid"] == step_uuid
                            ):
                                ws["messages"][-1] = {**ws["messages"][-1], "text": ws["messages"][-1]["text"] + part["text"]}
                            else:
                                ws["messages"].append({
                                    "id": f"{aid}:msg:{ev.get('uuid') or len(ws['messages'])}",
                                    "role": "assistant",
                                    "text": part["text"],
                                    "status": "completed",
                                })
                            ws["detail"] = ws["messages"][-1]["text"]
                            ws["last_step_uuid"] = step_uuid
                    elif etype == "tool.call":
                        ws["last_step_uuid"] = ""
                        tc_id = ev.get("toolCallId") or ev.get("uuid") or str(len(ws["messages"]))
                        tname = ev.get("name") if isinstance(ev.get("name"), str) else ""
                        args = ev.get("args") if isinstance(ev.get("args"), dict) else {}
                        disp = ev.get("display") if isinstance(ev.get("display"), dict) else {}
                        tkind = _kimi_tool_kind(tname, disp)
                        label = (
                            disp.get("command")
                            or args.get("command")
                            or args.get("path")
                            or args.get("file_path")
                            or args.get("pattern")
                            or args.get("url")
                            or args.get("query")
                            or tname
                            or tr("工具", "Tool")
                        )
                        row_id = f"{aid}:tool-{tc_id}"
                        tool_row = {
                            "id": row_id,
                            "role": "tool",
                            "kind": "tool",
                            "text": str(label),
                            "toolName": tname,
                            "toolKind": tkind,
                            "input": json.dumps(args, ensure_ascii=False) if args else "",
                            "status": "running",
                        }
                        idx = next((i for i, m in enumerate(ws["messages"]) if m.get("id") == row_id), -1)
                        if idx >= 0:
                            ws["messages"][idx] = {**ws["messages"][idx], **tool_row}
                        else:
                            ws["messages"].append(tool_row)
                    elif etype == "tool.result":
                        tc_id = ev.get("toolCallId")
                        if isinstance(tc_id, str) and tc_id:
                            res = ev.get("result") if isinstance(ev.get("result"), dict) else {}
                            out = res.get("output", "")
                            out_str = out if isinstance(out, str) else json.dumps(out, ensure_ascii=False)
                            done_st = "failed" if res.get("isError") else "completed"
                            row_id = f"{aid}:tool-{tc_id}"
                            idx = next((i for i, m in enumerate(ws["messages"]) if m.get("id") == row_id), -1)
                            if idx >= 0:
                                ws["messages"][idx] = {**ws["messages"][idx], "status": done_st, "outcome": out_str}
                elif rtype in ("turn.ended", "prompt.completed"):
                    reason = rec.get("reason", "completed")
                    mapped, native_st = _map_subagent_status(reason)
                    if mapped != "unknown":
                        ws["status"] = mapped
                        ws["native_status"] = native_st
                        status_dirty = True
                    settle_tool = "cancelled" if mapped == "cancelled" else "completed"
                    for i, m in enumerate(ws["messages"]):
                        if m.get("role") == "tool" and m.get("kind") == "tool" and m.get("status") == "running":
                            ws["messages"][i] = {**m, "status": settle_tool}
            with self.lock:
                existing = self.subagents.get(aid, {})
            update_fields = {"messages": [dict(m) for m in ws["messages"]]}
            if ws["model"]:
                update_fields["model"] = ws["model"]
            if ws["task"] and not existing.get("task"):
                update_fields["task"] = ws["task"]
            if ws["detail"] and not existing.get("detail"):
                update_fields["detail"] = ws["detail"]
            if status_dirty and ws["status"]:
                update_fields["status"] = ws["status"]
                update_fields["nativeStatus"] = ws["native_status"]
            self.subagent(aid, **update_fields)

import base64
import json
import mimetypes
import os
import threading
import time
from pathlib import Path
from urllib.parse import quote, urlencode
from .base import Session, item, _same_json
from .i18n import tr
from .kimi import _parse_agent_output
from .kimi_web_transport import PROTOCOL, KimiWebPeer, KimiWebRejected, gateway_for
from .media import display_content
from .metrics import ensure_pricing_catalog_async, kimi_web_metrics
from .transport import Rejected

def encoded(value):
    return quote(str(value), safe="")

def frame_status(state):
    return {"done": "completed", "error": "failed", "completed": "completed",
        "failed": "failed", "cancelled": "cancelled", "interrupted": "cancelled"}.get(state, "running")

def _web_tool_kind(name, display):
    if isinstance(display, dict) and display.get("kind") == "command":
        return "execute"
    if name in ("Bash", "Shell"):
        return "execute"
    if name in ("Read", "ReadFile", "ReadMediaFile", "ListDir"):
        return "read"
    if name in ("Glob", "Grep", "SearchWeb", "WebSearch"):
        return "search"
    if name in ("Write", "WriteFile", "Edit", "StrReplaceFile", "MultiEdit", "NotebookEdit"):
        return "edit"
    if name in ("FetchURL", "WebFetch"):
        return "fetch"
    return ""

def _web_tool_label(name, args, display):
    disp = display if isinstance(display, dict) else {}
    inp = args if isinstance(args, dict) else {}
    if _web_tool_kind(name, disp) == "execute":
        skip = (name or "").lower()
        for key in ("displaySummary", "toolSummary", "description", "summary"):
            val = inp.get(key)
            if isinstance(val, str):
                cleaned = " ".join(val.split()).strip()
                if cleaned and cleaned.lower() != skip:
                    return cleaned
    label = (
        disp.get("command")
        or inp.get("command")
        or inp.get("path")
        or inp.get("file_path")
        or inp.get("pattern")
        or inp.get("url")
        or inp.get("query")
        or inp.get("description")
        or name
        or tr("工具", "Tool")
    )
    return str(label)

class KimiWebSession(Session):
    input_evidence_protocol = 1
    check_peer_background_children = False

    def __init__(self, config, save):
        super().__init__(config, save)
        self.refresh_lock = threading.RLock()
        self.refresh_wake = threading.Event()
        self.attachment_cache = {}
        self.subagent_cache = {}
        self._last_tool_frames = {}
        self._last_agent_anchors = {}
        self.agent_state = {}
        self.usage_state = {}
        self.ws_interactions = {}
        self.ws_notices = []
        self.ws_notice_seq = 0

    def connect(self):
        self.update(historyReady=False, **({"nativeProtocol": PROTOCOL} if self.config.get("nativeProtocol") == PROTOCOL else {}))
        self.gateway = gateway_for(self)
        self.api = self.gateway.api
        try:
            native = self.config.get("nativeId")
            creating = not native
            if native:
                record = self.api.request("GET", "/sessions/" + encoded(native))
                if record.get("id") != native:
                    raise Rejected(tr("Kimi Web 返回了不同会话身份", "Kimi Web returned a different session identity"))
                cwd = record.get("metadata", {}).get("cwd")
                if not cwd or os.path.realpath(cwd) != os.path.realpath(self.config["cwd"]):
                    raise Rejected(tr("Kimi 会话工作目录与原记录不符", "Kimi session working directory does not match the original record"))
            else:
                if self.config.get("creationUnknown"):
                    raise Rejected(tr("上次 Kimi 会话创建结果未知，请使用原生 ID 接入；不会再次创建", "Previous Kimi session creation is unconfirmed; attach using its native ID instead of creating again"))
                self.config["creationUnknown"] = True
                self.save()
                payload = {"metadata": {"cwd": self.config["cwd"]}}
                if self.config.get("model"):
                    payload["agent_config"] = {"model": self.config["model"]}
                if self.config.get("initialTitle"):
                    payload["title"] = self.config["initialTitle"]
                try:
                    record = self.api.request("POST", "/sessions", payload)
                except Rejected:
                    self.config["creationUnknown"] = False
                    self.save()
                    raise
                self.config["nativeId"] = record["id"]
                self.config["creationUnknown"] = False
                self.save()
            self.update(nativeId=self.config["nativeId"])
            self.path = "/sessions/" + encoded(self.config["nativeId"])
            self.models = self.api.request("GET", "/models")["items"]
            native_model = record.get("agent_config", {}).get("model")
            model = self.config.get("model") if creating else native_model
            if not native_model:
                model = self.config.get("model") or self.api.request("GET", "/config").get("default_model")
            if model and model != native_model:
                configured = self.api.request("POST", self.path + "/profile", {"agent_config": {"model": model}})
                configured_model = configured.get("agent_config", {}).get("model")
                equiv = {model}
                for m in self.models:
                    names = {m.get("alias"), m.get("id"), m.get("model")} - {None, ""}
                    if model in names:
                        equiv |= names
                if configured_model not in equiv:
                    raise Rejected(tr("Kimi Web 未确认请求的模型", "Kimi Web did not confirm the requested model"))
            self.peer = KimiWebPeer(self.gateway, self, self.event, self.exited)
            if type(self.peer) is KimiWebPeer and "cli-sessions" in self.config.get("ownerDirectory", ""):
                ensure_pricing_catalog_async()
            self.peer.start_read()
            if not self.peer.ready.wait(30) or self.peer.closed:
                raise ConnectionError(tr("Kimi Web 会话订阅未确认", "Kimi Web session subscription was not confirmed"))
            self.refresh()
            if self.peer.closed:
                raise ConnectionError(tr("Kimi Web 事件连接已断开", "Kimi Web event connection disconnected"))
            if self.config.get("nativeProtocol") != PROTOCOL:
                self.config["nativeProtocol"] = PROTOCOL
                self.save()
            self.update(connected=True, historyReady=True, nativeProtocol=PROTOCOL, canOpenTui=False,
                capabilities={"queue": True, "steer": True, "stop": True, "images": True,
                    "skills": False, "compact": True, "rename": True})
            self.refresh_thread = threading.Thread(target=self.observe, daemon=True, name="LumaKimiWebSync")
            self.refresh_thread.start()
        except BaseException:
            if self.peer:
                self.peer.close()
            else:
                self.gateway.detach(self.config["id"])
            raise

    def observe(self):
        try:
            while self.peer and not self.peer.closed:
                if not self.refresh_wake.wait(30):
                    continue
                self.refresh_wake.clear()
                if self.peer.closed:
                    break
                time.sleep(.2)
                self.refresh()
        except Exception as error:
            if self.peer and not self.peer.closed:
                self.peer.close()
                self.exited(error)

    def event(self, frame):
        if frame.get("session_id") != self.config.get("nativeId"):
            return
        ftype = frame.get("type")
        if ftype == "interaction":
            iid = frame.get("interaction_id")
            if iid:
                with self.lock:
                    if frame.get("status") == "pending":
                        self.ws_interactions[iid] = {
                            "interactionId": iid,
                            "interactionKind": frame.get("kind", ""),
                            "agentId": frame.get("agent_id", "main"),
                            "state": "pending",
                            "toolCallId": frame.get("tool_call_id", ""),
                            "request": frame.get("request") or {},
                        }
                    else:
                        self.ws_interactions.pop(iid, None)
            self.refresh_wake.set()
            return
        if frame.get("agent_id") == "main":
            if ftype == "agent":
                with self.lock:
                    self.agent_state = dict(frame)
            elif ftype == "usage":
                with self.lock:
                    self.usage_state = dict(frame)
            elif ftype == "notice":
                notice_obj = frame.get("notice") if isinstance(frame.get("notice"), dict) else frame
                title = notice_obj.get("title") or notice_obj.get("message") or ""
                detail = notice_obj.get("detail") or ""
                msg = (title + ("\n" + detail if detail else "")).strip()
                lvl = notice_obj.get("level", "info")
                if msg:
                    with self.lock:
                        if not self.ws_notices or self.ws_notices[-1][1] != msg:
                            nid = "ws-notice:" + str(self.ws_notice_seq)
                            self.ws_notice_seq += 1
                            self.ws_notices.append((nid, msg, lvl))
                            del self.ws_notices[:-32]
        self.refresh_wake.set()

    def turns_transcript(self, agent="main"):
        query = {"page_size": 200}
        turns = []
        while True:
            page = self.api.request("GET", self.path + "/agents/" + encoded(agent) + "/turns?" + urlencode(query))
            page_turns = page.get("turns", [])
            turns = page_turns + turns
            if not page.get("has_more"):
                break
            first_ord = page_turns[0].get("ordinal") if page_turns else None
            if first_ord is None or query.get("before_ordinal") == first_ord:
                raise ValueError(tr("Kimi 历史分页未前进", "Kimi history pagination did not advance"))
            query["before_ordinal"] = first_ord
        return {"turns": turns}

    def fetch_snapshot(self, agent="main"):
        gateway = getattr(self, "gateway", None)
        peer = getattr(self, "peer", None)
        if getattr(gateway, "uses_turns_api", False) is True:
            if peer is not None and getattr(peer, "uses_turns_api", None) is False:
                enable = getattr(peer, "enable_turns_api", None)
                if callable(enable):
                    enable()
                elif hasattr(peer, "uses_turns_api"):
                    peer.uses_turns_api = True
            return self.turns_transcript(agent)
        try:
            return self.transcript(agent)
        except KimiWebRejected as error:
            if error.code != 404:
                raise
            if gateway is not None:
                gateway.uses_turns_api = True
            if peer is not None:
                enable = getattr(peer, "enable_turns_api", None)
                if callable(enable):
                    enable()
                elif hasattr(peer, "uses_turns_api"):
                    peer.uses_turns_api = True
            return self.turns_transcript(agent)

    def _resolve_turn_attachment(self, att):
        fid = att.get("file_id")
        if fid:
            key = ("turn_file", fid)
            if key in self.attachment_cache:
                return self.attachment_cache[key]
            try:
                content, mime = self.api.request("GET", "/files/" + encoded(fid), binary=True)
            except KimiWebRejected:
                content, mime = self.api.request("GET", self.path + "/media/" + encoded(fid), binary=True)
            result = self.media({"type": "resource", "resource": {"uri": att.get("name", ""),
                "mimeType": mime or att.get("media_type", ""), "blob": base64.b64encode(content).decode()}})
            self.attachment_cache[key] = result
            return result
        if att.get("url"):
            return self.attachment({"source": {"kind": "url", "url": att["url"]},
                "name": att.get("name", ""), "mediaType": att.get("media_type", "")})
        return []

    def transcript(self, agent="main"):
        query = {"agent_id": agent, "page_size": 100}
        result = None
        items = []
        while True:
            page = self.api.request("GET", self.path + "/transcript?" + urlencode(query))
            if result is None:
                result = dict(page)
            items = page["items"] + items
            if not page["has_more"]:
                break
            turns = [v for v in page["items"] if v.get("kind") == "turn"]
            if not turns or query.get("before_turn") == turns[0]["turnId"]:
                raise ValueError(tr("Kimi 历史分页未前进", "Kimi history pagination did not advance"))
            query["before_turn"] = turns[0]["turnId"]
        result["items"] = items
        return result

    def attachment(self, raw):
        source = raw.get("source") or {}
        key = (source.get("kind"), source.get("fileId"), source.get("url"))
        if key in self.attachment_cache:
            return self.attachment_cache[key]
        kind = source.get("kind")
        if kind == "session_media":
            content, mime = self.api.request("GET", self.path + "/media/" + encoded(source["fileId"]), binary=True)
            result = self.media({"type": "resource", "resource": {"uri": raw.get("name", ""),
                "mimeType": mime, "blob": base64.b64encode(content).decode()}})
        elif kind == "file":
            content, mime = self.api.request("GET", "/files/" + encoded(source["fileId"]), binary=True)
            result = self.media({"type": "resource", "resource": {"uri": raw.get("name", ""),
                "mimeType": mime, "blob": base64.b64encode(content).decode()}})
        elif kind == "url":
            result = self.media({"type": "resource_link", "uri": source["url"],
                "mimeType": raw.get("mediaType", ""), "name": raw.get("name", "")})
        else:
            result = [{"source": "luma-error:" + tr("Kimi 附件来源不可用", "Kimi attachment source unavailable"),
                "name": raw.get("name", ""), "mimeType": raw.get("mediaType", "")}]
        self.attachment_cache[key] = result
        return result

    def project(self, snapshot, prefix=""):
        if "turns" in snapshot and "items" not in snapshot:
            return self.project_turns(snapshot, prefix)
        rows = {}
        tool_frames = {}
        agent_anchors = {}
        turn_errors = {t["error"] for t in snapshot["items"] if t.get("kind") == "turn" and t.get("error")}
        attachments = {
            a["attachmentId"]: a
            for a in snapshot.get("attachments", []) or []
            if isinstance(a, dict) and isinstance(a.get("attachmentId"), str) and a.get("attachmentId")
        }
        prompts = {
            p["promptId"]: p.get("content") or []
            for p in snapshot.get("prompts", []) or []
            if isinstance(p, dict) and p.get("promptId")
        }
        def media(ids):
            out = []
            for ident in ids or []:
                raw = attachments.get(ident)
                if raw is not None:
                    out.extend(self.attachment(raw))
            return out
        def user(ident, body, ids, turn_id):
            resources = media(ids)
            for part in prompts.get(ident.removeprefix(prefix) if prefix else ident, []):
                if part.get("type") != "image":
                    continue
                source = part.get("source") or {}
                if source.get("kind") == "base64":
                    extra = self.media({"type": "image", "data": source["data"], "mimeType": source["media_type"]})
                elif source.get("kind") == "url":
                    extra = self.media({"type": "image", "url": source["url"]})
                elif source.get("kind") == "session_media":
                    extra = self.attachment({"source": {"kind": "session_media", "fileId": source["file_id"]}, "name": part.get("name", "")})
                elif source.get("kind") == "file":
                    extra = self.attachment({"source": {"kind": "file", "fileId": source["file_id"]}, "name": part.get("name", "")})
                else:
                    extra = []
                resources.extend(r for r in extra if not any(old["source"] == r["source"] for old in resources))
            rows[ident] = item(ident, "user", body, origin="native", media=resources, status="completed", turnId=turn_id)
        for turn in snapshot["items"]:
            if turn.get("kind") == "marker" and turn.get("marker") == "notice":
                notice = turn.get("payload") or {}
                message = notice.get("message")
                if message and not (notice.get("level") == "error" and message in turn_errors):
                    ident = prefix + turn["markerId"]
                    rows[ident] = item(ident, "tool", message, kind="notice",
                        status="failed" if notice.get("level") == "error" else "completed")
                continue
            if turn.get("kind") != "turn":
                continue
            turn_id = prefix + turn["turnId"]
            origin = turn.get("origin") or {}
            if origin.get("kind") == "user":
                ident = prefix + (turn.get("triggerPromptId") or turn["turnId"] + ":input")
                if turn.get("prompt") or turn.get("attachmentIds") or any(p.get("type") == "image" for p in prompts.get(ident.removeprefix(prefix) if prefix else ident, [])):
                    user(ident, turn.get("prompt", ""), turn.get("attachmentIds"), turn_id)
            for step in turn.get("steps", []):
                for frame in step["frames"]:
                    ident = prefix + frame["frameId"]
                    kind = frame["kind"]
                    state = frame_status(frame.get("state") if kind == "tool" else step.get("state", turn["state"]))
                    if kind == "text":
                        if frame["role"] == "user":
                            if (frame.get("origin") or {}).get("kind", "user") in ("user", "skill_activation"):
                                ids = frame.get("promptIds") or []
                                user(prefix + (ids[0] if ids else frame["frameId"]), frame["text"], frame.get("attachmentIds"), turn_id)
                        else:
                            rows[ident] = item(ident, "assistant", frame["text"], status=state, turnId=turn_id)
                    elif kind == "thinking":
                        rows[ident] = item(ident, "tool", frame["text"], kind="reasoning",
                            status=state, turnId=turn_id)
                    elif kind == "tool":
                        tname = frame.get("name", "")
                        raw_input = frame.get("input")
                        inp = raw_input if isinstance(raw_input, dict) else {}
                        disp = frame.get("display")
                        out_text = display_content(frame.get("output"))
                        rows[ident] = item(ident, "tool", _web_tool_label(tname, inp, disp), kind="tool",
                            toolName=tname, toolKind=_web_tool_kind(tname, disp),
                            input=json.dumps(raw_input, ensure_ascii=False) if raw_input is not None else frame.get("inputText", ""),
                            detail=frame.get("error") or out_text,
                            status=state, turnId=turn_id)
                        if not prefix:
                            tc_id = frame.get("toolCallId") or ""
                            if tc_id:
                                tool_frames[tc_id] = {"itemId": ident, "turnId": turn_id, "input": inp}
                            out_meta = _parse_agent_output(out_text) if tname in ("Agent", "Task", "AgentSwarm") and out_text else {}
                            ref_ids = [r["agentId"] for r in (frame.get("agentRefs") or []) if isinstance(r, dict) and r.get("agentId")]
                            if out_meta.get("agent_id") and out_meta["agent_id"] not in ref_ids:
                                ref_ids.append(out_meta["agent_id"])
                            for aid in ref_ids:
                                agent_anchors[aid] = {
                                    "itemId": ident,
                                    "turnId": turn_id,
                                    "role": out_meta.get("actual_subagent_type") or inp.get("subagent_type", ""),
                                    "task": inp.get("description") or inp.get("prompt", ""),
                                    "detail": out_meta.get("summary", ""),
                                }
                    elif kind == "notice":
                        rows[ident] = item(ident, "tool", frame["message"], kind="notice", status="completed", turnId=turn_id)
            if turn.get("error"):
                ident = turn_id + ":error"
                rows[ident] = item(ident, "tool", turn["error"], kind="notice", status="failed", turnId=turn_id)
        if not prefix:
            self._last_tool_frames = tool_frames
            self._last_agent_anchors = agent_anchors
        return rows

    @staticmethod
    def _turn_state(turn):
        if turn.get("status") == "running":
            return "running"
        if turn.get("end_reason") in ("user_cancelled", "aborted"):
            return "cancelled"
        outcome = turn.get("outcome")
        if outcome == "failed":
            return "failed"
        if outcome == "interrupted":
            return "cancelled"
        return "completed"

    @staticmethod
    def _item_state(it, turn_state):
        if it.get("status") == "running":
            return "running"
        outcome = it.get("outcome")
        if outcome == "failed":
            return "failed"
        if outcome == "interrupted":
            return "cancelled"
        return turn_state if turn_state != "running" else "completed"

    def project_turns(self, snapshot, prefix=""):
        rows = {}
        tool_frames = {}
        agent_anchors = {}
        for turn in snapshot.get("turns", []):
            turn_id = prefix + turn["turn_id"]
            turn_state = self._turn_state(turn)
            for it in turn.get("items", []):
                itype = it.get("type")
                state = self._item_state(it, turn_state)
                if itype == "user_message":
                    origin_kind = (it.get("origin") or {}).get("kind", "user")
                    if origin_kind not in ("user", "skill", "plugin_command", "skill_activation"):
                        continue
                    pids = it.get("prompt_ids") or []
                    ident = prefix + (pids[0] if pids else it["item_id"])
                    resources = []
                    for att in it.get("attachments") or []:
                        resources.extend(self._resolve_turn_attachment(att))
                    rows[ident] = item(ident, "user", it.get("text", ""), origin="native",
                        media=resources, status="completed", turnId=turn_id)
                elif itype == "assistant_message":
                    ident = prefix + it["item_id"]
                    rows[ident] = item(ident, "assistant", it.get("text", ""), status=state, turnId=turn_id)
                elif itype == "reasoning":
                    ident = prefix + it["item_id"]
                    rows[ident] = item(ident, "tool", it.get("text", ""), kind="reasoning",
                        status=state, turnId=turn_id)
                elif itype == "tool_call":
                    ident = prefix + it["item_id"]
                    tname = it.get("name", "")
                    raw_input = it.get("input")
                    inp = raw_input if isinstance(raw_input, dict) else {}
                    disp = it.get("display")
                    out_text = display_content(it.get("output"))
                    rows[ident] = item(ident, "tool", _web_tool_label(tname, inp, disp), kind="tool",
                        toolName=tname, toolKind=_web_tool_kind(tname, disp),
                        input=json.dumps(raw_input, ensure_ascii=False) if raw_input is not None else it.get("input_text", ""),
                        detail=it.get("error") or out_text,
                        status=state, turnId=turn_id)
                    if not prefix:
                        tool_frames[it["item_id"]] = {"itemId": ident, "turnId": turn_id, "input": inp}
                        out_meta = _parse_agent_output(out_text) if tname in ("Agent", "Task", "AgentSwarm") and out_text else {}
                        ref_ids = [
                            r.get("agent_id") or r.get("agentId")
                            for r in (it.get("agent_refs") or [])
                            if isinstance(r, dict) and (r.get("agent_id") or r.get("agentId"))
                        ]
                        if out_meta.get("agent_id") and out_meta["agent_id"] not in ref_ids:
                            ref_ids.append(out_meta["agent_id"])
                        for aid in ref_ids:
                            agent_anchors[aid] = {
                                "itemId": ident,
                                "turnId": turn_id,
                                "role": out_meta.get("actual_subagent_type") or inp.get("subagent_type", ""),
                                "task": inp.get("description") or inp.get("prompt", ""),
                                "detail": out_meta.get("summary", ""),
                            }
                elif itype == "notification":
                    msg = "\n".join(p for p in (it.get("title", ""), it.get("body", "")) if p).strip()
                    if msg:
                        ident = prefix + it["item_id"]
                        rows[ident] = item(ident, "tool", msg, kind="notice",
                            status="failed" if it.get("severity") == "error" else "completed", turnId=turn_id)
                elif itype == "system" and it.get("kind") == "compaction" and it.get("summary"):
                    ident = prefix + it["item_id"]
                    rows[ident] = item(ident, "tool", it["summary"], kind="notice",
                        status="completed", turnId=turn_id)
            err = turn.get("error")
            err_msg = err.get("message", "") if isinstance(err, dict) else (err if isinstance(err, str) else "")
            if err_msg:
                ident = turn_id + ":error"
                rows[ident] = item(ident, "tool", err_msg, kind="notice", status="failed", turnId=turn_id)
        if not prefix:
            with self.lock:
                ws_notices = list(self.ws_notices)
            for nid, msg, lvl in ws_notices:
                rows[nid] = item(nid, "tool", msg, kind="notice",
                    status="failed" if lvl == "error" else "completed")
            self._last_tool_frames = tool_frames
            self._last_agent_anchors = agent_anchors
        return rows

    def refresh(self):
        with self.refresh_lock:
            record = self.api.request("GET", self.path)
            if record["id"] != self.config["nativeId"]:
                raise Rejected(tr("Kimi Web 会话身份改变", "Kimi Web session identity changed"))
            native_queue = self.api.request("GET", self.path + "/prompts")
            snapshot = self.fetch_snapshot()
            rows = self.project(snapshot)
            known = self.config.get("deliveries", {}).keys() | self.config.get("receipts", {}).keys()
            for prompt in ([native_queue["active"]] if native_queue.get("active") else []) + native_queue.get("queued", []):
                if prompt["prompt_id"] in known:
                    self.receipt(prompt["prompt_id"], "accepted")
            for prompt in snapshot.get("prompts", []):
                if prompt["promptId"] in known and prompt.get("status") != "aborted":
                    self.receipt(prompt["promptId"], "accepted")
            for ident, row in rows.items():
                if ident in known and row["role"] == "user":
                    self.receipt(ident, "accepted")
                    self.consumed(ident)
            for turn in snapshot.get("items", []):
                for step in turn.get("steps", []):
                    for frame in step["frames"]:
                        if frame.get("kind") == "text" and frame.get("role") == "user":
                            for ident in frame.get("promptIds", []):
                                if ident in known:
                                    self.receipt(ident, "accepted")
                                    self.consumed(ident)
            for turn in snapshot.get("turns", []):
                for it in turn.get("items", []):
                    if it.get("type") == "user_message":
                        for ident in it.get("prompt_ids", []):
                            if ident in known:
                                self.receipt(ident, "accepted")
                                self.consumed(ident)
            with self.lock:
                if not _same_json(list(self.rows.values()), list(rows.values())):
                    self.rows = rows
                    self.seq += 1
                ws_ints = list(self.ws_interactions.values())
            interactions = list(snapshot.get("interactions", []))
            children = {}
            child_snapshots = []
            if "agents" in snapshot:
                agent_entries = [
                    (a["agentId"], a.get("label"), a.get("profile"), bool(a.get("busy")))
                    for a in snapshot["agents"]
                    if a.get("agentId") not in (None, "main") and a.get("type") != "independent"
                ]
            else:
                agent_entries = [
                    (aid, None, None, None)
                    for aid in self._last_agent_anchors
                    if aid != "main"
                ]
                ensure_sub = getattr(getattr(self, "peer", None), "ensure_agent_subscriptions", None)
                if ensure_sub and agent_entries:
                    ensure_sub([aid for aid, _, _, _ in agent_entries])
            for aid, agent_label, agent_profile, agent_busy in agent_entries:
                cached = self.subagent_cache.get(aid)
                if cached is not None and agent_busy is False:
                    child = cached
                elif cached is not None and agent_busy is None:
                    c_turns = cached.get("turns", [])
                    has_pending = any(i.get("agentId") == aid and i.get("state") == "pending" for i in ws_ints)
                    if c_turns and all(t.get("status") == "ended" for t in c_turns) and not has_pending:
                        child = cached
                    else:
                        child = self.fetch_snapshot(aid)
                else:
                    child = self.fetch_snapshot(aid)
                if "turns" in child and "items" not in child:
                    c_turns = child.get("turns", [])
                    child_state = self._turn_state(c_turns[-1]) if c_turns else "unknown"
                    has_pending = any(i.get("agentId") == aid and i.get("state") == "pending" for i in ws_ints)
                    if c_turns and all(t.get("status") == "ended" for t in c_turns) and not has_pending:
                        self.subagent_cache[aid] = child
                    else:
                        self.subagent_cache.pop(aid, None)
                else:
                    c_items_turns = [t for t in child.get("items", []) if t.get("kind") == "turn"]
                    child_state = c_items_turns[-1]["state"] if c_items_turns else "unknown"
                    if child_state in ("completed", "failed", "cancelled") and not any(i.get("state") == "pending" for i in child.get("interactions", [])):
                        self.subagent_cache[aid] = child
                    else:
                        self.subagent_cache.pop(aid, None)
                    c_turns = c_items_turns
                child_snapshots.append(child)
                state = child_state
                child_messages = list(self.project(child, aid + ":").values())
                anchor = self._last_agent_anchors.get(aid, {})
                if "turns" in child and "items" not in child:
                    child_task = next(
                        (it.get("text", "") for t in c_turns for it in t.get("items", []) if it.get("type") == "user_message" and it.get("text")),
                        "",
                    ) or anchor.get("task", "")
                else:
                    child_task = next((t.get("prompt", "") for t in c_turns if t.get("prompt")), "") or anchor.get("task", "")
                children[aid] = {"id": aid, "name": agent_label or anchor.get("role") or aid,
                    "role": anchor.get("role") or agent_profile or "",
                    "itemId": anchor.get("itemId", ""), "turnId": anchor.get("turnId", ""),
                    "task": child_task,
                    "detail": anchor.get("detail") or next((m["text"] for m in reversed(child_messages) if m.get("role") == "assistant" and m.get("text")), ""),
                    "status": {"completed": "succeeded", "running": "running", "failed": "failed", "cancelled": "cancelled"}.get(state, "unknown"),
                    "nativeStatus": state, "messages": child_messages}
                interactions.extend(child.get("interactions", []))
            if ws_ints:
                by_id = {i["interactionId"]: i for i in interactions if i.get("interactionId")}
                for i in ws_ints:
                    by_id[i["interactionId"]] = i
                interactions = list(by_id.values())
            self.project_requests({"interactions": interactions})
            with self.lock:
                if not _same_json(self.subagents, children):
                    self.subagents = children
                    self.seq += 1
                ws_usage = dict(self.usage_state)
                ws_agent = dict(self.agent_state)
            settings = {**record.get("agent_config", {}), **ws_agent}
            active_model = settings.get("model") or ""
            metrics = kimi_web_metrics(record, snapshot, child_snapshots, self.models, ws_usage, active_model)
            running = bool(record.get("main_turn_active", record.get("busy")) or ws_agent.get("status") == "running" or ws_agent.get("compacting"))
            self.update(title=record.get("title", ""), model=active_model,
                effort=settings.get("thinking") or "", permission=settings.get("permission_mode", "manual"),
                planModeSupported=True, planMode=bool(settings.get("plan_mode", False)),
                permissions=[{"id": ident, "label": label, "approval": approval}
                    for ident, label, approval in (
                        ("manual", "Always Ask", "always"),
                        ("yolo", "Ask When Needed", "when_needed"),
                        ("auto", "Never Ask", "never"))],
                models=[{"id": m.get("alias", m.get("id", m.get("model", ""))),
                    "label": m.get("display_name") or m.get("alias") or m.get("model", ""),
                    "efforts": m.get("support_efforts", []), "supportsImages": "image_in" in m.get("capabilities", [])}
                    for m in self.models],
                running=running,
                nativeQueuePending=bool(native_queue.get("queued")),
                queue=[{"id": p["prompt_id"], "text": display_content(p["content"]),
                    "clientUserMessageId": p["prompt_id"], "status": tr("待发", "Queued"),
                    "canRemove": True, "canSteer": True, "canEdit": False} for p in native_queue.get("queued", [])],
                metrics=metrics)
            return {}

    def project_requests(self, snapshot):
        pending = {}
        for interaction in snapshot.get("interactions", []):
            if interaction["state"] != "pending":
                continue
            ident = interaction["interactionId"]
            request = interaction.get("request") or {}
            kind = interaction["interactionKind"]
            pending[ident] = interaction
            if kind == "approval":
                tc_id = interaction.get("toolCallId") or request.get("tool_call_id") or request.get("toolCallId") or ""
                frame_meta = self._last_tool_frames.get(tc_id, {})
                inp = frame_meta.get("input") if isinstance(frame_meta.get("input"), dict) and frame_meta.get("input") else None
                disp = request.get("tool_input_display") or request.get("toolInputDisplay")
                if inp:
                    body = json.dumps(inp, ensure_ascii=False)
                elif isinstance(disp, dict) and disp:
                    body = json.dumps(disp, ensure_ascii=False)
                else:
                    body = request.get("action", "")
                self.request(ident, interaction, request.get("toolName") or request.get("tool_name") or tr("审批", "Approval"),
                    body, [{"id": "approved", "label": tr("允许", "Allow")},
                    {"id": "session", "label": tr("本会话允许", "Allow for session")},
                    {"id": "rejected", "label": tr("拒绝", "Reject")}])
            else:
                questions = []
                for q in request.get("questions", []):
                    opts = q.get("options", [])
                    questions.append({"id": q["id"], "header": q.get("header", ""), "prompt": q.get("question", ""),
                        "options": [o["label"] for o in opts],
                        "optionValues": [o["id"] for o in opts],
                        "multiple": q.get("multi_select", q.get("multiSelect", False)),
                        "allowOther": q.get("allow_other", q.get("allowOther", False)), "required": True})
                self.request(ident, interaction, "Answer", "",
                    [{"id": "submit", "label": tr("提交", "Submit")}, {"id": "cancel", "label": tr("终止提问", "Cancel prompt")}],
                    questions, submit_choice="submit", skip_choice="cancel")
        for ident in list(self.pending_requests):
            if ident not in pending:
                self.answered(ident)

    def prompt(self, payload):
        ident = payload["submissionId"]
        content = [{"type": "text", "text": payload["text"]}] if payload.get("text") else []
        uploaded_ids = []
        try:
            for raw_path in payload.get("images", []):
                p = Path(raw_path)
                mime = mimetypes.guess_type(raw_path)[0]
                if mime not in ("image/png", "image/jpeg", "image/webp", "image/gif"):
                    raise Rejected(tr("不支持的图片类型", "Unsupported image type"))
                if not p.is_file() or p.stat().st_size > 20 * 1024 * 1024:
                    raise Rejected(tr("图片不可读或超过 20 MiB", "Image is unreadable or exceeds 20 MiB"))
                uploaded = self.api.upload_file(p.name or "image", mime, p.read_bytes())
                file_id = uploaded.get("id") or ""
                if not file_id:
                    raise KimiWebRejected(422, tr("Kimi Web 未返回上传文件 ID", "Kimi Web did not return uploaded file ID"))
                uploaded_ids.append(file_id)
                block = {"type": "image", "source": {"kind": "file", "file_id": file_id}}
                if p.name:
                    block["name"] = p.name
                content.append(block)
            result = self.api.request("POST", self.path + ("/steer" if payload.get("steer") else "/prompts"),
                {"prompt_id": ident, "content": content})
        except Rejected:
            for fid in uploaded_ids:
                try:
                    self.api.request("DELETE", "/files/" + encoded(fid))
                except Exception:
                    pass
            raise
        if result.get("prompt_id") != ident or result.get("user_message_id") != ident:
            raise ConnectionError(tr("Kimi Web 返回的提交身份不一致，结果待确认", "Kimi Web returned a different submission identity; result unconfirmed"))
        self.refresh_wake.set()
        return True

    def answer(self, payload):
        raw = self.pending_requests.get(payload["id"])
        if not raw:
            raise Rejected(tr("Kimi 请求已结束", "Kimi request has ended"))
        kind = raw["interactionKind"]
        choice = payload["choice"]
        if kind == "approval":
            if choice not in ("approved", "rejected", "session"):
                raise Rejected(tr("无效审批选项", "Invalid approval choice"))
            body = {"kind": "approval", "decision": "approved" if choice == "session" else choice}
            if choice == "session":
                body["scope"] = "session"
        elif choice == "cancel":
            body = {"kind": "question", "dismissed": True}
        elif choice == "submit":
            answers = {}
            for question in raw["request"]["questions"]:
                values = payload.get("answers", {}).get(question["id"], [])
                opts = question.get("options", [])
                by_id = {o["id"]: o["id"] for o in opts}
                by_label = {o["label"]: o["id"] for o in opts}
                selected = []
                other = []
                valid = bool(values)
                for v in values:
                    if not isinstance(v, str):
                        valid = False
                        break
                    if v.startswith("\u0000raw:"):
                        raw_text = v[5:].strip()
                        if not raw_text:
                            valid = False
                            break
                        other.append(raw_text)
                    elif not v.strip():
                        valid = False
                        break
                    elif v in by_id:
                        if by_id[v] not in selected:
                            selected.append(by_id[v])
                    elif v in by_label:
                        if by_label[v] not in selected:
                            selected.append(by_label[v])
                    else:
                        other.append(v)
                multiple = question.get("multi_select", question.get("multiSelect", False))
                allow_other = question.get("allow_other", question.get("allowOther", False))
                if not valid or (not multiple and len(values) != 1) or (other and not allow_other):
                    raise Rejected(tr("Kimi 答案无效或不完整", "Invalid or incomplete Kimi answer"))
                if other:
                    answer = {"kind": "multi_with_other", "option_ids": selected, "other_text": "\n".join(other)} if selected else {"kind": "other", "text": "\n".join(other)}
                else:
                    answer = {"kind": "multi", "option_ids": selected} if multiple else {"kind": "single", "option_id": selected[0]}
                answers[question["id"]] = answer
            body = {"kind": "question", "answers": answers}
        else:
            raise Rejected(tr("无效提问选项", "Invalid question choice"))
        self.api.request("POST", self.path + "/interactions/" + encoded(payload["id"]), body)
        with self.lock:
            self.ws_interactions.pop(payload["id"], None)
        self.answered(payload["id"])
        self.refresh_wake.set()
        return {}

    def configure(self, payload):
        settings = {}
        for key, target in (("model", "model"), ("effort", "thinking"), ("permission", "permission_mode"), ("planMode", "plan_mode")):
            if key in payload and (key != "effort" or payload[key]):
                settings[target] = payload[key]
        self.api.request("POST", self.path + "/profile", {"agent_config": settings})
        return self.refresh()

    def rename(self, title):
        self.api.request("POST", self.path + "/profile", {"title": title})
        return self.refresh()

    def compact(self):
        self.api.request("POST", self.path + ":compact", {})
        self.refresh_wake.set()
        return {}

    def stop(self):
        self.api.request("POST", self.path + ":abort", {})
        self.refresh_wake.set()
        return {}

    def queue_change(self, payload):
        action = "steer" if payload.get("steer") else "abort"
        self.api.request("POST", self.path + "/prompts/" + encoded(payload["id"]) + ":" + action, {})
        return self.refresh()

    def release_mode(self):
        return "observer" if self.data.get("historyReady") else None

    def stop_observation(self):
        if self.peer:
            self.peer.close()
        self.refresh_wake.set()
        worker = getattr(self, "refresh_thread", None)
        if worker and worker is not threading.current_thread():
            worker.join(timeout=35)
            if worker.is_alive():
                raise OSError(tr("Kimi Web 历史同步尚未停止", "Kimi Web history sync has not stopped"))

    def finish_observer_close(self):
        self.stop_observation()
        super().finish_observer_close()

    def destroy_resources(self):
        self.stop_observation()
        super().destroy_resources()

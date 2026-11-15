import json
import threading
import uuid
from .base import Session, item, text, image_blocks
from .i18n import tr
from .transport import Rejected
from .metrics import acp_usage
from .media import display_content, display_object

class AcpSession(Session):
    input_evidence_protocol = 1
    argv = ["dsh", "--profile", "acp"]
    supports_forms = False
    permission_categories = ("mode",)
    desired_model_on_create = False
    prompt_wait_timeout = 86400
    replay_release = False
    release_empty = False

    def release_mode(self):
        if not self.replay_release or not getattr(self, "native_caps", {}).get("loadSession") or not self.data.get("historyReady"):
            return None
        if not self.release_empty and not any(r.get("role") == "user" and r.get("origin") != "local" for r in self.rows.values()):
            return None
        return "process"

    def connect(self):
        self.update(historyReady=False)
        self.options = []
        self.chunk_kind = None
        self.chunk_id = None
        self.current_submission = None
        self.echoed_user_submission = False
        self.pending_echo_submissions = []
        self.preexisting_tool_ids = set()
        self.start(self.argv)
        client_caps = {"fs": {"readTextFile": False, "writeTextFile": False}, "terminal": False}
        if self.supports_forms: client_caps["elicitation"] = {"form": {}}
        info = self.call("initialize", {"protocolVersion": 1, "clientInfo": {"name": "luma", "version": "1"},
                                        "clientCapabilities": client_caps})
        self.initialize_info = info
        if info.get("protocolVersion") != 1: raise Rejected(tr("不兼容的 ACP 版本", "Incompatible ACP version"))
        caps = info.get("agentCapabilities", {})
        meta = info.get("_meta", {})
        self.native_caps = caps
        self.commands = meta.get("availableCommands", [])
        self.authenticate(info)
        args = {"cwd": self.config["cwd"], "mcpServers": []}
        native = self.config.get("nativeId")
        if native:
            args["sessionId"] = native
            method = "session/load" if caps.get("loadSession") else "session/resume"
            if method == "session/resume" and "resume" not in caps.get("sessionCapabilities", {}):
                raise Rejected(tr("此 CLI 未提供原生会话恢复", "This CLI does not support native session resume"))
        else:
            if self.config.get("creationUnknown"):
                raise Rejected(tr("上次原生创建结果未知；请从 CLI 会话列表取得原 ID 后接入，不能自动再建", "Previous native session creation result is unknown; attach using the original ID from CLI session list instead of recreating"))
            self.config["creationUnknown"] = True; self.save()
            method = "session/new"
        try:
            result = self.call(method, args)
        except Rejected:
            if not native: self.config["creationUnknown"] = False; self.save()
            raise
        self.finish_chunk()
        self.chunk_id = self.chunk_kind = None
        ident = result.get("sessionId", native)
        if not ident or (native and ident != native): raise Rejected(tr("ACP 返回了不同会话身份", "ACP returned a mismatched session identity"))
        self.config["creationUnknown"] = False
        self.update(nativeId=ident)
        self.apply_options(result.get("configOptions", []))
        if self.desired_model_on_create and not native and self.config.get("model"):
            self.configure({"model": self.config["model"]})
        self.update(connected=not getattr(self, "defer_connected", False), capabilities={"queue": False, "steer": False, "stop": True,
                    "images": caps.get("promptCapabilities", {}).get("image", False), "skills": False,
                    "compact": any(c.get("name") == "compact" for c in self.commands)},
                    notice=(tr("使用原生 ACP 审批。无可管理待发队列、逐消息 Skill 附件或实时 TUI 接管。", "Uses native ACP approvals. No managed queue, per-message skills, or live TUI takeover.") +
                            (tr("原生恢复不重放历史，重启托管服务后只显示本次连接输出。", " Native resume does not replay history; only output from the current connection is shown after host restart.") if not caps.get("loadSession") else "")))
        self.apply_commands(self.commands)
        self.update(historyReady=True)

    def authenticate(self, info):
        pass

    @staticmethod
    def flatten(options):
        result = []
        for o in options:
            if "options" in o: result += AcpSession.flatten(o["options"])
            elif "value" in o: result.append(o)
        return result

    def apply_options(self, options):
        previous_model = self.data["model"]
        self.options = options
        models = self.data["models"]
        efforts = []
        for o in options:
            if o.get("type") != "select": continue
            values = self.flatten(o.get("options", []))
            cat = o.get("category", o["id"])
            if cat == "model":
                models = [{"id": v["value"], "label": v.get("name", v["value"]), "supportsImages": self.native_caps.get("promptCapabilities", {}).get("image", False), "efforts": []} for v in values]
                self.update(model=o.get("currentValue", ""))
            elif cat in ("thought_level", "reasoning_effort"):
                efforts = [v["value"] for v in values]; self.update(effort=o.get("currentValue", ""))
            elif cat in self.permission_categories:
                self.update(permission=o.get("currentValue", ""), permissions=[{"id": v["value"], "label": v.get("name", v["value"]), "description": v.get("description", "")} for v in values])
        if previous_model and self.data["model"] != previous_model: self.invalidate_context()
        for m in models: m["efforts"] = efforts if m["id"] == self.data["model"] else []
        self.update(models=models, **({"effort": ""} if not efforts else {}))

    def apply_commands(self, commands):
        self.commands = commands
        self.update(capabilities={**self.data["capabilities"], "compact": any(c.get("name") == "compact" for c in commands)})

    @staticmethod
    def form_option_pairs(v):
        if v.get("type") == "boolean": return [("true", "true"), ("false", "false")]
        source = v.get("items", {}) if v.get("type") == "array" else v
        if "enum" in source:
            pairs = [(str(x), x) for x in source["enum"]]
        else:
            pairs = []
            for o in source.get("oneOf", source.get("anyOf", [])):
                if "const" not in o: continue
                raw = o["const"]
                title = o.get("title")
                label = title.strip() if isinstance(title, str) and title.strip() else str(raw)
                pairs.append((label, raw))
        if len({label for label, _ in pairs}) < len(pairs):
            pairs = [(f"{label} ({raw})" if str(raw) != label else label, raw) for label, raw in pairs]
        return pairs

    @classmethod
    def form_options(cls, v):
        return [label for label, _ in cls.form_option_pairs(v)]

    @classmethod
    def _has_companion_other(cls, props, key):
        other = props.get(key + "_other")
        return (isinstance(other, dict) and other.get("type") == "string"
                and other.get("title") == "Other" and not cls.form_option_pairs(other))

    def event(self, e):
        method, p = e.get("method"), e.get("params", {})
        native = self.data["nativeId"] or self.config.get("nativeId", "")
        if native and p.get("sessionId") and p["sessionId"] != native: return
        if self.supports_forms and method == "elicitation/create" and "id" in e:
            schema = p.get("requestedSchema", {})
            props = schema.get("properties", {})
            if p.get("mode", "form") != "form" or schema.get("type") != "object" or any(
                    not isinstance(v, dict) or v.get("type") not in ("string", "array", "boolean", "integer", "number")
                    for v in props.values()):
                self.peer.send({"jsonrpc": "2.0", "id": e["id"], "error": {"code": -32602, "message": "Unsupported elicitation schema"}})
                return
            folded_others = {key + "_other" for key in props if self._has_companion_other(props, key)}
            required_list = schema.get("required")
            questions = []
            for key, v in props.items():
                if key in folded_others: continue
                options = self.form_options(v)
                has_other = (key + "_other") in folded_others
                required = (key in required_list) if isinstance(required_list, list) else True
                questions.append({
                    "id": key,
                    "header": v.get("title", key),
                    "prompt": v.get("description", v.get("title", key)),
                    "options": options,
                    "multiple": v.get("type") == "array",
                    "secret": v.get("format") == "password",
                    "allowOther": has_other or not options,
                    "required": required,
                })
            self.request(str(e["id"]), e, "Answer", p.get("message", ""),
                         [{"id": "accept", "label": tr("提交", "Submit")}, {"id": "decline", "label": tr("不回答", "Decline")}, {"id": "cancel", "label": tr("终止提问", "Cancel")}],
                         questions, submit_choice="accept", skip_choice="decline")
            return
        if method == "session/update":
            u = p["update"]; kind = u.get("sessionUpdate")
            if kind in ("agent_message_chunk", "agent_thought_chunk", "user_message_chunk"):
                role = {"agent_message_chunk": "assistant", "agent_thought_chunk": "reasoning", "user_message_chunk": "user"}[kind]
                if self.chunk_kind != kind:
                    self.finish_chunk()
                    bind_current = False
                    matched_echo = None
                    chunk_text = text(u.get("content")) if kind == "user_message_chunk" else ""
                    with self.lock:
                        if kind == "user_message_chunk" and self.current_submission and not getattr(self, "echoed_user_submission", False):
                            existing = self.rows.get(self.current_submission, {})
                            local_text = existing.get("text", "") if existing.get("role") == "user" else ""
                            if not local_text or not chunk_text or local_text.startswith(chunk_text) or chunk_text.startswith(local_text):
                                bind_current = True
                        if not bind_current and kind == "user_message_chunk":
                            pending_echoes = getattr(self, "pending_echo_submissions", None)
                            if pending_echoes:
                                for cand_id in list(pending_echoes):
                                    existing = self.rows.get(cand_id, {})
                                    local_text = existing.get("text", "") if existing.get("role") == "user" else ""
                                    if not local_text or not chunk_text or local_text.startswith(chunk_text) or chunk_text.startswith(local_text):
                                        matched_echo = cand_id
                                        pending_echoes.remove(cand_id)
                                        break
                        if bind_current:
                            self.chunk_id = self.current_submission
                        elif matched_echo:
                            self.chunk_id = matched_echo
                        else:
                            self.chunk_id = str(uuid.uuid4())
                        self.chunk_kind = kind
                        if bind_current:
                            self.echoed_user_submission = True
                            self.row(item(self.chunk_id, "user", "", origin="native"))
                            self.consumed(self.current_submission)
                        elif matched_echo:
                            self.row(item(self.chunk_id, "user", "", origin="native"))
                            self.receipt(matched_echo, "accepted")
                            self.consumed(matched_echo)
                old = self.rows.get(self.chunk_id, item(self.chunk_id, role, kind="reasoning" if role == "reasoning" else ""))
                self.row({**old, "text": old["text"] + text(u.get("content")), "media": old.get("media", []) + self.media(u.get("content")), "status": "running"})
            elif kind in ("tool_call", "tool_call_update"):
                self.finish_chunk()
                self.chunk_kind = None
                if kind == "tool_call" and getattr(self, "preexisting_tool_ids", None) is not None:
                    self.preexisting_tool_ids.discard(u["toolCallId"])
                ident = "tool-" + u["toolCallId"]; old = self.rows.get(ident, item(ident, "tool", tr("工具", "Tool"), kind="tool"))
                changes = {}
                if "title" in u: changes["text"] = u["title"]
                if isinstance(u.get("name"), str) and u["name"]: changes["toolName"] = u["name"]
                if u.get("kind") is not None: changes["toolKind"] = u["kind"]
                if "rawInput" in u: changes["input"] = json.dumps(u["rawInput"], ensure_ascii=False)
                if "status" in u: changes["status"] = u["status"]
                if "content" in u:
                    changes["outcome"] = display_content(u["content"])
                    changes["media"] = self.media(u["content"])
                if "rawOutput" in u:
                    changes["outcome"] = json.dumps(display_object(u["rawOutput"]), ensure_ascii=False)
                    changes["media"] = changes.get("media", []) + self.media(u["rawOutput"].get("content") if isinstance(u["rawOutput"], dict) else None)
                self.row({**old, **changes})
            elif kind == "config_option_update": self.apply_options(u.get("configOptions", []))
            elif kind == "current_mode_update": self.update(permission=u["currentModeId"])
            elif kind == "available_commands_update":
                self.apply_commands(u.get("availableCommands", []))
            elif kind == "session_info_update" and "title" in u: self.update(title=u["title"])
            elif kind == "usage_update":
                self.apply_usage(u)
            if self.current_submission and kind in ("user_message_chunk", "agent_message_chunk", "agent_thought_chunk", "tool_call", "tool_call_update"):
                has_luma_submissions = getattr(self, "initialize_info", {}).get("_meta", {}).get("lumaSubmissions", {}).get("protocol") == 1
                can_accept = False
                if kind == "user_message_chunk":
                    can_accept = self.chunk_id == self.current_submission
                elif not has_luma_submissions:
                    if kind == "tool_call_update":
                        can_accept = u.get("toolCallId") not in getattr(self, "preexisting_tool_ids", set())
                    else:
                        can_accept = True
                if can_accept:
                    self.receipt(self.current_submission, "accepted")
                    self.consumed(self.current_submission)
        elif method == "session/request_permission" and "id" in e:
            tool = p.get("toolCall") if isinstance(p.get("toolCall"), dict) else {}
            call_id = tool.get("toolCallId")
            prior = self.rows.get("tool-" + call_id, {}) if isinstance(call_id, str) and call_id else {}
            if "rawInput" in tool:
                body = json.dumps(tool["rawInput"], ensure_ascii=False)
            elif isinstance(prior.get("input"), str):
                body = prior["input"]
            else:
                body = ""
            if tool.get("content"):
                display = "\n".join(text(c.get("content")) if c.get("type") == "content" else json.dumps(c, ensure_ascii=False) for c in tool.get("content", []))
            elif isinstance(prior.get("outcome"), str):
                display = prior["outcome"]
            else:
                display = ""
            raw_title = tool.get("title") if isinstance(tool.get("title"), str) else ""
            prior_text = prior.get("text") if isinstance(prior.get("text"), str) else ""
            prior_name = prior.get("toolName") if isinstance(prior.get("toolName"), str) else ""
            resolved_title = (
                raw_title.strip()
                or (prior_text.strip() if prior_text.strip() not in ("", "工具", "Tool") else "")
                or prior_name.strip()
                or tr("工具授权", "Tool permission")
            )
            option_ids = {o["optionId"] for o in p.get("options", [])}
            cancel = "cancel"
            while cancel in option_ids: cancel = "luma:" + cancel
            self.request(str(e["id"]), {**e, "_cancelChoice": cancel}, resolved_title,
                         "\n".join(v for v in (body, display) if v),
                         [{"id": o["optionId"], "label": o["name"]} for o in p.get("options", [])] + [{"id": cancel, "label": tr("取消", "Cancel")}])
        elif "id" in e and method:
            self.peer.send({"jsonrpc": "2.0", "id": e["id"], "error": {"code": -32601, "message": "Client capability not advertised"}})

    def invalidate_context(self):
        self.acp_metrics = [r for r in getattr(self, "acp_metrics", [])
                            if r["label"] not in ("上下文", "Context") and "缓存命中" not in r["label"] and "cache hit" not in r["label"].lower()]
        self.update(metrics=self.acp_metrics + getattr(self, "request_metrics", []))

    def apply_usage(self, update):
        self.acp_metrics = acp_usage(update, model=self.data.get("model") or self.config.get("model"))
        self.update(metrics=self.acp_metrics + getattr(self, "request_metrics", []))

    def apply_prompt_result(self, result):
        pass

    def finish_chunk(self, status="completed"):
        chunk_id = getattr(self, "chunk_id", None)
        if chunk_id and chunk_id in self.rows and self.rows[chunk_id].get("status") in ("running", "in_progress", "pending"):
            self.row({**self.rows[chunk_id], "status": status})

    def prompt(self, p):
        command = p.get("text", "").strip().split(maxsplit=1)
        if command and command[0] in ("/compact", "/compress") and any(c.get("name") == command[0][1:] for c in self.commands):
            self.invalidate_context()
        blocks = ([{"type": "text", "text": p["text"]}] if p["text"] else []) + image_blocks(p.get("images", []))
        with self.lock:
            if self.current_submission is None and self.data.get("error") and getattr(self, "delivery_storage_error", None) is None:
                self.update(error=None)
            self.preexisting_tool_ids = {k[5:] for k in self.rows if k.startswith("tool-")}
            self.echoed_user_submission = False
            self.current_submission = p["submissionId"]
            self.prompt_wait_error = None
            self.chunk_kind = None
            self.update(running=True)
            self.row(item(p["submissionId"], "user", p["text"], origin="local"))
        def execute():
            try:
                params = {"sessionId": self.data["nativeId"], "prompt": blocks}
                if getattr(self, 'initialize_info', {}).get('_meta', {}).get('lumaSubmissions', {}).get('protocol') == 1:
                    params['_meta'] = {'lumaSubmissionId': p['submissionId']}
                result = self.call("session/prompt", params, timeout=self.prompt_wait_timeout,
                    on_uncertain=lambda error: self.prompt_uncertain(p["submissionId"], error))
                with self.lock:
                    if self.current_submission != p["submissionId"]: return
                    if not isinstance(result, dict) or not isinstance(result.get("stopReason"), str) or not result["stopReason"]:
                        raise ValueError("ACP prompt response has no stopReason")
                    self.apply_prompt_result(result)
                    self.receipt(p["submissionId"], "accepted")
                    self.consumed(p["submissionId"])
                    self.current_submission = None
                    cancelled = result["stopReason"] == "cancelled"
                    self.finish_chunk("cancelled" if cancelled else "completed")
                    if cancelled:
                        self.settle_incomplete_rows("cancelled")
                        self.pending_requests.clear()
                        self.update(requests=[])
                    error = self.data.get("error")
                    if error == self.prompt_wait_error: error = None
                    self.prompt_wait_error = None
                    self.update(running=False, error=error)
            except Rejected as exc:
                with self.lock:
                    if self.current_submission != p["submissionId"]: return
                    self.current_submission = None
                    self.prompt_wait_error = None
                    self.finish_chunk("failed")
                    self.settle_incomplete_rows("unknown")
                    self.pending_requests.clear()
                    self.update(running=False, requests=[], error=str(exc))
            except Exception as exc: self.prompt_uncertain(p["submissionId"], exc)

        threading.Thread(target=execute, daemon=True, name="LumaAcpPrompt").start()
        return False

    def prompt_uncertain(self, ident, error):
        with self.lock:
            if self.current_submission != ident: return
            self.prompt_wait_error = tr(f"ACP 回合结果待确认（{type(error).__name__}）", f"ACP turn result pending confirmation ({type(error).__name__})")
            self.diagnostic("acp-prompt-result-unknown", error)
            self.update(error=self.prompt_wait_error)

    def configure(self, p):
        if self.data["running"]: raise Rejected(tr("请等待回合结束再更改设置", "Wait for the current turn to finish before changing settings"))
        if p.get("speed"): return super().configure(p)
        for key, cats in (("model", ("model",)), ("effort", ("thought_level", "reasoning_effort")), ("permission", self.permission_categories)):
            value = p.get(key)
            if not value: continue
            option = next((o for o in self.options if o.get("category", o["id"]) in cats), None)
            if option:
                if value not in {v["value"] for v in self.flatten(option.get("options", []))}: raise Rejected(tr("选项不在原生目录中", "Option is not in the native catalog"))
                result = self.call("session/set_config_option", {"sessionId": self.data["nativeId"], "configId": option["id"], "value": value})
                self.apply_options(result["configOptions"])
                actual = next((o for o in self.options if o.get("id") == option["id"]), None)
                if not actual or actual.get("currentValue") != value:
                    raise Rejected(tr("CLI 尚未确认所选设置，请按原生实际状态核对", "CLI has not confirmed the selected setting; verify native state"))
            else: raise Rejected(tr("CLI 未提供此设置接口", "CLI does not support this setting"))
        return {}

    def stop(self):
        self.peer.send({"jsonrpc": "2.0", "method": "session/cancel", "params": {"sessionId": self.data["nativeId"]}})
        with self.answer_lock, self.lock:
            for key, raw in list(self.pending_requests.items()):
                self.answer({"id": key, "choice": raw.get("_cancelChoice", "cancel"), "answers": {}})
        return {}

    def compact(self):
        if not any(c.get("name") == "compact" for c in self.commands): return super().compact()
        return self.submit({"submissionId": str(uuid.uuid4()), "text": "/compact"})

    def answer(self, p):
        raw = self.pending_requests.get(p["id"])
        if not raw: return super().answer(p)
        if raw.get("method") == "elicitation/create":
            choice = p["choice"]
            if choice not in ("accept", "decline", "cancel"): raise Rejected(tr("无效表单动作", "Invalid form action"))
            result = {"action": choice}
            if choice == "accept":
                schema = raw["params"]["requestedSchema"]
                props = schema.get("properties", {})
                folded_others = {key + "_other" for key in props if self._has_companion_other(props, key)}
                required_keys = set(schema.get("required", []))
                submitted = p.get("answers", {})
                values = {}
                for key, spec in props.items():
                    if key in folded_others: continue
                    other_key = key + "_other"
                    has_other = other_key in folded_others
                    entries = [e for e in submitted.get(key, []) if isinstance(e, str) and e != ""]
                    direct_other = [e for e in submitted.get(other_key, []) if isinstance(e, str) and e.strip()] if has_other else []
                    pairs = self.form_option_pairs(spec)
                    kind = spec.get("type")
                    if pairs:
                        by_label = dict(pairs)
                        raw_lookup = {}
                        for _, raw_val in pairs:
                            raw_str = str(raw_val)
                            raw_lookup[raw_str] = None if raw_str in raw_lookup else raw_val
                        matched = []
                        free_text = list(direct_other)
                        for entry in entries:
                            if entry in by_label:
                                matched.append(by_label[entry])
                            elif entry in raw_lookup and raw_lookup[entry] is not None:
                                matched.append(raw_lookup[entry])
                            elif has_other and entry.strip():
                                free_text.append(entry.strip())
                            else:
                                raise Rejected(tr("回答不在原生选项中", "Answer is not among native options"))
                        if not matched and not free_text:
                            if key in required_keys: raise Rejected(tr("请回答：", "Please answer: ") + spec.get("title", key))
                            continue
                        if matched:
                            try:
                                if kind == "array": values[key] = matched
                                elif kind == "boolean":
                                    if matched[0] not in ("true", "false", True, False): raise ValueError()
                                    values[key] = matched[0] in ("true", True)
                                elif kind == "integer": values[key] = int(matched[0])
                                elif kind == "number": value = float(matched[0]); values[key] = value
                                else: values[key] = matched[0]
                            except ValueError:
                                raise Rejected(tr("回答格式无效：", "Invalid answer format: ") + spec.get("title", key)) from None
                        if free_text:
                            values[other_key] = ", ".join(free_text)
                    else:
                        if not entries:
                            if key in required_keys: raise Rejected(tr("请回答：", "Please answer: ") + spec.get("title", key))
                            continue
                        try:
                            if kind == "array": value = entries
                            elif kind == "boolean":
                                if entries[0] not in ("true", "false"): raise ValueError()
                                value = entries[0] == "true"
                            elif kind == "integer": value = int(entries[0])
                            elif kind == "number": value = float(entries[0])
                            else: value = entries[0]
                        except ValueError:
                            raise Rejected(tr("回答格式无效：", "Invalid answer format: ") + spec.get("title", key)) from None
                        values[key] = value
                result["content"] = values
            self.peer.send({"jsonrpc": "2.0", "id": raw["id"], "result": result})
            self.answered(p["id"])
            return {}
        choice = p["choice"]
        if choice == raw.get("_cancelChoice", "cancel"): outcome = {"outcome": "cancelled"}
        elif choice in {o["optionId"] for o in raw["params"].get("options", [])}: outcome = {"outcome": "selected", "optionId": choice}
        else: raise Rejected(tr("无效审批选项", "Invalid approval choice"))
        self.peer.send({"jsonrpc": "2.0", "id": raw["id"], "result": {"outcome": outcome}})
        self.answered(p["id"]); return {}

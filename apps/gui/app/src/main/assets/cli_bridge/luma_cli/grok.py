import json
import os
import queue
import re
import shutil
import subprocess
import time
from pathlib import Path
import threading
import uuid
from .acp import AcpSession
from .base import item, text, image_blocks
from .i18n import lang_code, tr
from .metrics import grok_context, grok_usage
from .media import display_content, display_object
from .transport import Rejected, RpcRejected, spawn_process
from .native_owner import NativeOwner, grok_lock_leader, non_mcp_background_child_alive, process_start

EXT_UPDATES = {"x.ai/session_notification", "x.ai/session/update"}
CONTENT = {"agent_message_chunk": "assistant", "agent_thought_chunk": "reasoning", "user_message_chunk": "user"}

def _grok_permissions():
    return [
        {"id": "default", "label": "Default"},
        {"id": "auto", "label": "Auto"},
        {"id": "always-approve", "label": "Always approve"},
    ]

def safe_reason(value):
    if not isinstance(value, str): return tr("Grok 未返回可显示的错误原因", "Grok did not return a displayable error reason")
    raw_redacted = tr("[已隐藏]", "[redacted]")
    value = value[:4096]
    value = re.sub(r"[\x00-\x08\x0b-\x1f\x7f]", "", value)
    value = re.sub(r"(?i)\bBearer\s+\S+", f"Bearer {raw_redacted}", value)
    value = re.sub(r'''(?ix)(["']?(?:access[_-]?token|refresh[_-]?token|api[_-]?key|authorization|password|secret|cookie)["']?\s*[:=]\s*)["']?[^\s,;\n}]+''', rf"\1{raw_redacted}", value)
    value = re.sub(r"\b(?:xai-|sk-)[A-Za-z0-9_-]+", raw_redacted, value)
    value = re.sub(r"\beyJ[A-Za-z0-9_.-]+", raw_redacted, value)
    value = re.sub(r"https?://[^\s<>]+", tr("[服务地址]", "[endpoint]"), value)
    value = re.sub(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", tr("[账号已隐藏]", "[account redacted]"), value)
    return value.strip()[:1200] or tr("Grok 执行失败", "Grok execution failed")

def error_info(error, kind="request"):
    code = error.code if isinstance(error, RpcRejected) else None
    data = error.data if isinstance(error, RpcRejected) else None
    detail = data.get("message") if isinstance(data, dict) else data
    message = safe_reason(detail if isinstance(detail, str) and detail else str(error))
    status = data.get("http_status") if isinstance(data, dict) else None
    if not isinstance(status, int): status = None
    lower = message.lower()
    if code == -32000 or status == 401 or any(s in lower for s in ("authentication required", "session expired", "no cached auth", "unauthorized", "re-authentication")):
        kind = "authentication"
    elif any(s in lower for s in ("model is no longer available", "model unavailable", "unknown model", "not available for your account", "no available models")):
        kind = "model"
    elif status == 403 or any(s in lower for s in ("subscription", "allow_access", "preferred_method", "disabled by your administrator")):
        kind = "configuration"
    elif kind != "configuration" and isinstance(error, (ConnectionError, TimeoutError, OSError)):
        kind = "transport"
    info = {"kind": kind, "message": message}
    if type(code) is int: info["code"] = code
    if status is not None: info["httpStatus"] = status
    return info

def extension_result(value):
    if isinstance(value, dict) and value.get("error"):
        raise Rejected(str(value["error"]))
    return value.get("result", value)

class GrokSession(AcpSession):
    replay_release = True
    defer_connected = True
    correlated_delivery = True
    busy_default_steer = False

    def extra_host_queue(self):
        return [{"id": entry["id"], "text": entry["text"], "status": tr("插话 · 等待当前步骤结束", "Steer · Waiting for current step"),
                 "clientUserMessageId": entry["id"], "canRemove": False, "canSteer": False,
                 "canEdit": False, "hostOwned": True}
                for entry in self.config.get("pendingInterjections", [])]

    def _has_active_step_wait(self):
        if self.pending_requests: return True
        background = set(self.background_tools.values())
        terminal = {"completed", "failed", "cancelled", "succeeded", "aborted", "unknown"}
        return any(
            key not in background and row.get("role") == "tool" and row.get("kind") == "tool" and row.get("status") not in terminal
            for key, row in self.rows.items()
        )

    def _admit_interjection_row(self, entry):
        ident = entry["id"]
        self.finish_chunk()
        self.chunk_id = self.chunk_kind = None
        self.row(item(ident, "user", entry["text"], turnId=self.current_submission or "", origin="local"))
        if ident in self.config.get("receipts", {}):
            self.config.setdefault("admittedDisplaySubmissions", {})[ident] = True

    def _flush_interjections(self, *, force=False):
        with self.lock:
            pending = self.config.get("pendingInterjections")
            if not pending: return
            ready = list(pending) if force else [e for e in pending if e.get("armed") and not self._has_active_step_wait()]
            if not ready: return
            ready_ids = {e["id"] for e in ready}
            self.config["pendingInterjections"] = [e for e in pending if e["id"] not in ready_ids]
            for entry in ready:
                self._admit_interjection_row(entry)
            self._checkpoint_native_evidence("interjection-admit")
            self.seq += 1

    def lifecycle(self, phase, reason="", message="", retryable=False):
        self.update(lifecycle={"phase": phase, "reason": reason, "retryable": retryable, "message": message})

    def fail(self, error, kind="request"):
        info = error_info(error, kind)
        if info["kind"] == "request" and self.data.get("lifecycle", {}).get("phase") == "blocked":
            return self.data.get("error") or self.data["lifecycle"]["message"]
        if info["kind"] == "authentication" and "手动重连" not in info["message"] and "reconnect manually" not in info["message"].lower():
            info["message"] += tr("；请在普通终端核对 Grok 登录/认证配置，完成后手动重连。",
                                  "; check Grok sign-in/auth config in a regular terminal, then reconnect manually.")
        self.update(error=info["message"], errorInfo=info)
        if info["kind"] in ("authentication", "model", "configuration"):
            self.lifecycle("blocked", info["kind"], info["message"])
        elif info["kind"] == "transport":
            self.lifecycle("disconnected", "transport", info["message"], True)
        return info["message"]

    def exited(self, error):
        if self.data.get("lifecycle", {}).get("phase") != "blocked": self.fail(error, "transport")
        self.diagnostic("observer-disconnected", error)
        getattr(self, "autonomous_prompts", set()).clear()
        getattr(self, "observed_prompts", set()).clear()
        self.last_task_completed_at = None
        fields = {"connected": False, "requests": []}
        if getattr(self, "current_submission", None) is None and self.data.get("running"):
            fields["running"] = False
        self.update(**fields)

    def release_mode(self):
        if self.config.get("pendingInterjections"): return None
        consumed = self.config.get("consumedSubmissions", {})
        admitted = self.config.get("admittedDisplaySubmissions", {})
        if any(value == "accepted" and not consumed.get(ident) and not admitted.get(ident)
               for ident, value in self.config.get("receipts", {}).items()): return None
        mode = super().release_mode()
        return "observer" if mode and hasattr(self, "owner") else mode

    def fast_release_blocker(self):
        with self.lock:
            blocker = super().fast_release_blocker()
            if blocker in ("工具状态尚未确认结束", "Tool status has not been confirmed finished"):
                if any(
                    self.rows.get(ident, {}).get("status") in ("running", "in_progress", "pending", "unknown")
                    for ident in getattr(self, "background_tools", {}).values()
                ):
                    return tr("后台任务尚未确认结束", "Background task has not been confirmed finished")
            if blocker:
                return blocker
            last_completed = getattr(self, "last_task_completed_at", None)
            if getattr(self, "autonomous_prompts", None) or (last_completed is not None and time.monotonic() - last_completed < 5.0):
                return tr("后台唤醒回合尚未结算", "Background wake-up turn has not settled")
            return None

    def release_blocker(self):
        blocker = super().release_blocker()
        if blocker is not None:
            return blocker
        with self.lock:
            owner = getattr(self, "owner", None)
            cand = owner.handle.get("pid") if owner else None
            pid = cand if isinstance(cand, int) and cand > 0 else None
        if pid is None:
            return None
        return self.cached_snapshot_probe(
            ("grok-owner-bg", pid),
            lambda: tr("后台子进程仍在运行", "Background subprocess is still running")
            if non_mcp_background_child_alive(pid) else None,
            pending_value=tr("正在确认后台子进程状态", "Checking background subprocess status"),
        )
    argv = ["grok", "--no-auto-update", "agent", "--no-leader", "stdio"]
    desired_model_on_create = True
    permission_categories = ()

    def start(self, argv, env_overrides=None):
        if argv == self.argv and argv[0] == "grok":
            command = shutil.which("grok")
            installed = Path(os.environ.get("GROK_BIN_DIR") or str(Path.home() / ".grok/bin")) / "grok"
            if not command and installed.is_file() and os.access(installed, os.X_OK): command = str(installed)
            if command: argv = [command, *argv[1:]]
        if self.config.get("nativeProtocol") == "grok-private-leader-v1":
            binary = argv[0]
            self.owner = NativeOwner(self)
            if not self.owner.handle.get("kind"):
                env = os.environ.copy()
                env["LUMA_LANG"] = lang_code()
                if env_overrides: env.update(env_overrides)
                leader = spawn_process([binary, "--no-auto-update", "agent", "--leader-socket", self.owner.socket,
                    "leader", "--no-exit-on-disconnect", "--relay-on-demand"], cwd=self.config["cwd"], env=env,
                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, start_new_session=True)
                diagnostic = bytearray()
                def drain():
                    while True:
                        block = leader.stderr.read1(2048)
                        if not block: break
                        diagnostic.extend(block); del diagnostic[:-8192]
                threading.Thread(target=drain, daemon=True, name="LumaGrokLeaderDiagnostic").start()
                self.owner.handle.update(kind="grok-leader", pid=leader.pid, processStart=process_start(leader.pid), binary=binary)
                self.save()
                end = time.monotonic() + 15
                while time.monotonic() < end and not Path(self.owner.socket).exists() and leader.poll() is None: time.sleep(.05)
                locked = grok_lock_leader(self.owner.socket)
                if locked:
                    self.owner.handle.update(pid=locked[0], processStart=locked[1])
                    self.save()
                if (leader.poll() is not None and not locked) or not Path(self.owner.socket).exists():
                    reason = (
                        tr("；当前原生 sandbox 不支持 leader，请保留原权限并使用原入口", "; native sandbox does not support leader, keep original permissions and entry")
                        if b"sandbox" in diagnostic.lower() else
                        tr("；请在普通终端检查 Grok 原生配置", "; verify native Grok config in a regular terminal")
                    )
                    raise Rejected(tr("Grok 私有 leader 未启动", "Grok private leader failed to start") + reason)
            elif not self.owner.alive():
                raise Rejected(tr("Grok 私有 leader 已退出，不能自动建立替代执行进程", "Grok private leader has exited; cannot automatically spawn a replacement process"))
            argv = [binary, "--no-auto-update", "agent", "--leader", "--leader-socket", self.owner.socket, "stdio"]
        try:
            result = super().start(argv, env_overrides)
            if self.config.get("nativeProtocol") == "grok-private-leader-v1" and hasattr(self, "owner"):
                self.owner.alive()
            return result
        except FileNotFoundError as exc:
            raise Rejected(tr("未找到 Grok Build：grok", "Grok Build binary not found: grok")) from exc

    def connect(self):
        self.lifecycle("connecting")
        self.update(catalogState="loading")
        self.initializing = True
        try:
            self._connect()
        except Exception as exc:
            if self.data.get("lifecycle", {}).get("phase") != "blocked":
                self.fail(exc, "configuration" if isinstance(exc, Rejected) else "transport")
            self.update(connected=False, catalogState="error")
            raise Rejected(self.data["error"]) from None
        finally:
            self.initializing = False

    def _connect(self):
        restored = bool(self.config.get("nativeId"))
        self.permission_revision = 0
        self.plan_mode_active = False
        self.auto_mode_active = False
        self.native_model_state = {}
        self.grok_context_tokens = None
        self.grok_process_usage = {}
        self.grok_last_usage = {}
        self.seen_events = set()
        self.replay_frames = []
        self.processing_replay = False
        self.replay_started = False
        self.loading = bool(self.config.get("nativeId"))
        self.turns = {}
        self.chunk_prompt = None
        self.background_tools = {}
        self.autonomous_prompts = set()
        self.observed_prompts = set()
        self.last_task_completed_at = None
        self.roster_running = None
        self.child_sessions = {}
        self.spawn_tools = []
        self.metrics_lock = threading.Lock()
        self.update(historyReady=False)
        try:
            super().connect()
        finally:
            self.loading = False
        trailing = self.replay_frames
        trailing_kind = trailing[-1].get("params", {}).get("update", {}).get("sessionUpdate") if trailing else None
        unfinished_replay = bool(trailing) and trailing_kind != "turn_completed"
        unfinished_prompt = None
        if unfinished_replay:
            for frame in reversed(trailing):
                params = frame.get("params", {})
                meta = params.get("_meta", {})
                pid = meta.get("promptId") or params.get("update", {}).get("prompt_id") or params.get("update", {}).get("promptId")
                if isinstance(pid, str) and pid:
                    unfinished_prompt = pid
                    break
        self.flush_replay()
        self.finish_chunk()
        self.chunk_id = self.chunk_kind = None
        self.update(historyReady=True, capabilities={**self.data["capabilities"],
                    "steer": self.is_grok, "queue": False, "skills": False, "compact": False})
        if hasattr(self, "owner"): self.update(canOpenTui=True, ownerInstanceId=self.owner.handle["instanceId"])
        if self.is_grok:
            raw_perm = self.config.get("selectedPermission") if restored else None
            legacy_plan = restored and raw_perm == "plan"
            selected_perm = None if raw_perm == "plan" else raw_perm
            selected_plan = self.config.get("selectedPlanMode") if (restored and "selectedPlanMode" in self.config) else (True if legacy_plan else None)
            roster_ok = False
            try:
                self.refresh_roster()
                roster_ok = True
            except (Rejected, ConnectionError, TimeoutError):
                unreadable = tr("Grok 权限状态暂不可读；未更改原生权限。", "Grok permission state is temporarily unreadable; native permissions unchanged.")
                self.update(permission="", permissions=[], planModeSupported=False, planMode=False, notice=unreadable, permissionNotice=unreadable)
                if selected_perm or selected_plan is not None:
                    raise Rejected(tr("无法确认已保存的 Grok 权限；请核对原生权限后手动重连", "Unable to confirm saved Grok permission; verify native permissions and reconnect manually"))
            if roster_ok:
                if selected_perm:
                    self.set_native_permission(selected_perm)
                if selected_plan is not None:
                    self.set_native_plan_mode(bool(selected_plan))
                if legacy_plan:
                    self.config.pop("selectedPermission", None)
                    self.config["selectedPlanMode"] = True
                    try:
                        self.save()
                    except Exception:
                        pass
        if unfinished_replay and hasattr(self, "owner") and getattr(self, "roster_running", None) is not False and not self.data.get("running"):
            if unfinished_prompt:
                self._note_observed_prompt(unfinished_prompt)
            else:
                self.update(running=True)
        if not self.data["running"] and self.config.get("pendingInterjections"):
            with self.lock:
                for entry in self.config.pop("pendingInterjections", []):
                    if self.config.get("receipts", {}).get(entry["id"]) == "accepted":
                        self.config.setdefault("admittedDisplaySubmissions", {})[entry["id"]] = True
                self._checkpoint_native_evidence("interjection-reconcile")
        if restored and not self.data["running"] and not self.data["queue"] and not self.config.get("pendingInterjections"):
            for ident, value in list(self.config.get("receipts", {}).items()):
                if value == "accepted":
                    self.consumed(ident)
        if self.peer.closed: raise ConnectionError(tr("Grok 原生连接已断开，请重连", "Grok native connection dropped; please reconnect"))
        if self.data.get("catalogState") != "ready":
            raise Rejected(tr("Grok 未返回会话模型目录，请核对原生模型配置后手动重连", "Grok did not return a session model catalog; verify native model config and reconnect manually"))
        self.update(connected=True)
        if self.data.get("lifecycle", {}).get("phase") != "blocked":
            if not self.data["models"]:
                self.fail(Rejected(tr("Grok 当前没有可用模型，请核对账号/模型配置", "Grok currently has no available models; verify account/model configuration")), "model")
            else:
                self.lifecycle("ready")
        threading.Thread(target=self.refresh_metrics, daemon=True, name="LumaGrokUsage").start()

    def flush_replay(self):
        batch, self.replay_frames = self.replay_frames, []
        if not batch: return
        update = batch[-1].get("params", {}).get("update", {})
        ident = update.get("prompt_id") or update.get("promptId") if update.get("sessionUpdate") == "turn_completed" else None
        if ident in self.config.get("receipts", {}):
            for index, frame in enumerate(batch):
                params = frame.get("params", {})
                if params.get("update", {}).get("sessionUpdate") == "user_message_chunk":
                    batch[index] = {**frame, "params": {**params, "_meta": {**params.get("_meta", {}), "lumaReplayPromptId": ident}}}
        self.processing_replay = True
        try:
            for frame in batch: self.event(frame)
        finally: self.processing_replay = False

    def call(self, method, params=None, **kwargs):
        if method == "initialize":
            params = {**params, "_meta": {"clientType": "extension"}}
        result = super().call(method, params, **kwargs)
        if method == "initialize":
            self.is_grok = result.get("_meta", {}).get("grokShell") is True
            if not self.is_grok: raise Rejected(tr("不是官方 Grok Build ACP 服务", "Not an official Grok Build ACP server"))
            self.native_model_state = result.get("_meta", {}).get("modelState", {})
        elif method in ("session/new", "session/load", "session/resume"):
            self.native_model_state = result.get("models", self.native_model_state)
        return result

    def authenticate(self, info):
        methods = {m.get("id") for m in info.get("authMethods", [])}
        meta = info.get("_meta", {})
        selected = meta.get("defaultAuthMethodId")
        if selected == "cached_token": return
        if not selected and "xai.api_key" in methods:
            selected = "xai.api_key"
        if selected != "xai.api_key":
            message = (tr("Grok 需要用户登录；请在普通终端运行 grok login，完成后手动重连。",
                          "Grok requires sign-in; run `grok login` in a regular terminal, then reconnect manually.")
                       if not selected or selected in ("grok.com", "oidc") else
                       tr("Grok 返回了当前入口不支持的默认认证方式；请在普通终端核对原生认证配置后手动重连。",
                          "Grok returned an unsupported default auth method; verify native auth config in a regular terminal, then reconnect manually."))
            self.fail(Rejected(message), "authentication" if not selected or selected in ("grok.com", "oidc") else "configuration")
            raise Rejected(self.data["error"])
        result = self.call("authenticate", {"methodId": selected})
        gate = result.get("_meta", {}).get("gate")
        if isinstance(gate, dict) and gate.get("message"):
            message = self.fail(Rejected(safe_reason(gate["message"])), "configuration")
            raise Rejected(message)

    def _effective_permission(self, yolo=None):
        if yolo is None:
            yolo = self.data.get("permission") == "always-approve"
        if yolo:
            return "always-approve"
        if getattr(self, "auto_mode_active", False):
            return "auto"
        return "default"

    def refresh_roster(self):
        value = extension_result(self.call("_x.ai/sessions/list", {}))
        actual = self.apply_roster(value.get("sessions", []))
        if actual is None: raise Rejected(tr("Grok 未返回当前会话的权限状态", "Grok did not return permission state for the current session"))
        return actual

    def apply_roster(self, sessions):
        for entry in sessions:
            if entry.get("sessionId") != self.data["nativeId"]: continue
            if entry.get("title"): self.update(title=entry["title"])
            active_prompt = entry.get("runningPromptId") or entry.get("activePromptId")
            active_flag = next((entry[k] for k in ("running", "isRunning", "isProcessing") if isinstance(entry.get(k), bool)), None)
            if active_flag is None and isinstance(entry.get("status"), str):
                status = entry["status"].lower()
                if status in ("running", "busy", "processing", "streaming", "in_progress"): active_flag = True
                elif status in ("idle", "ready", "completed", "stopped"): active_flag = False
            if active_flag is not None or (isinstance(active_prompt, str) and active_prompt):
                self.roster_running = bool(active_flag or active_prompt)
                if self.roster_running:
                    if isinstance(active_prompt, str) and active_prompt:
                        self._note_observed_prompt(active_prompt)
                    elif not self.data.get("running"):
                        self.update(running=True)
                elif getattr(self, "current_submission", None) is None and self.data.get("running"):
                    with self.lock:
                        getattr(self, "observed_prompts", set()).clear()
                        getattr(self, "autonomous_prompts", set()).clear()
                        self._flush_interjections(force=True)
                        self.update(running=False)
            if isinstance(entry.get("yolo"), bool):
                if entry["yolo"]:
                    self.auto_mode_active = False
                self.permission_revision += 1
                self.update(permission=self._effective_permission(entry["yolo"]),
                            permissions=_grok_permissions(),
                            planModeSupported=True,
                            planMode=bool(getattr(self, "plan_mode_active", False)),
                            permissionNotice=None)
                return self.data["permission"]
        return None

    def set_native_permission(self, value):
        if value not in {p["id"] for p in self.data["permissions"]}:
            raise Rejected(tr("权限不在原生目录中", "Permission is not in the native catalog"))
        revision = self.permission_revision
        prev_perm = self.data.get("permission", "")
        prev_known = prev_perm in {"default", "auto", "always-approve"}
        prev_yolo = prev_perm == "always-approve"
        prev_auto = getattr(self, "auto_mode_active", False)
        prev_perm_mode = "always-approve" if prev_yolo else ("auto" if prev_auto else "ask")
        sid = self.data["nativeId"]
        yolo_sent = False

        def rollback_native():
            if yolo_sent and prev_known:
                try:
                    self.peer.send({"jsonrpc": "2.0", "method": "_x.ai/yolo_mode_changed", "params": {
                        "sessionId": sid, "yolo_mode": prev_yolo, "auto_mode": prev_auto,
                        "permission_mode": prev_perm_mode}})
                except Exception:
                    pass

        try:
            want_yolo = value == "always-approve"
            want_auto = value == "auto"
            perm_mode = "always-approve" if want_yolo else ("auto" if want_auto else "ask")
            self.auto_mode_active = want_auto
            self.peer.send({"jsonrpc": "2.0", "method": "_x.ai/yolo_mode_changed", "params": {
                "sessionId": sid, "yolo_mode": want_yolo, "auto_mode": want_auto,
                "permission_mode": perm_mode}})
            yolo_sent = True
            actual = self.refresh_roster()
        except Exception:
            self.auto_mode_active = prev_auto
            rollback_native()
            if revision == self.permission_revision:
                self.update(permission="")
            raise
        if actual != value:
            self.auto_mode_active = prev_auto
            rollback_native()
            if yolo_sent and prev_known:
                try:
                    self.refresh_roster()
                except Exception:
                    self.update(permission="")
            else:
                actual_yolo = actual == "always-approve"
                self.update(permission=self._effective_permission(actual_yolo) if prev_known else "default")
            raise Rejected(tr("Grok 尚未确认权限设置", "Grok has not confirmed the permission setting"))
        return actual

    def set_native_plan_mode(self, enabled):
        if not isinstance(enabled, bool):
            raise Rejected(tr("规划模式参数无效", "Invalid Plan mode parameter"))
        sid = self.data["nativeId"]
        self.call("session/set_mode", {"sessionId": sid, "modeId": "plan" if enabled else "build"})
        self.plan_mode_active = enabled
        self.update(planModeSupported=True, planMode=enabled)
        return enabled

    def terminal(self):
        if not hasattr(self, "owner"): return super().terminal()
        if not self.owner.alive(): raise Rejected(tr("Grok 私有 leader 已退出", "Grok private leader has exited"))
        return {"nativeId": self.data["nativeId"], "instanceId": self.owner.handle["instanceId"], "argv": [
            self.owner.handle["binary"], "--no-auto-update", "--leader", "--leader-socket", self.owner.socket,
            "--resume", self.data["nativeId"]]}

    def configure(self, payload):
        if not getattr(self, "initializing", False) and self.data.get("lifecycle", {}).get("phase") != "ready":
            raise Rejected(self.data.get("error") or tr("Grok 尚未就绪，请先完成连接或核对原生配置", "Grok is not ready yet; finish connecting or verify native config first"))
        try:
            return self._configure(payload)
        except (RpcRejected, ConnectionError, TimeoutError) as exc:
            message = self.fail(exc, "configuration")
            if isinstance(exc, RpcRejected): raise Rejected(message) from None
            raise type(exc)(message) from None

    def _configure(self, payload):
        value = payload.get("permission")
        has_plan = "planMode" in payload
        plan_value = payload.get("planMode")
        rest = {k: v for k, v in payload.items() if k not in ("permission", "planMode")}
        result = super().configure(rest)
        if value:
            actual = self.set_native_permission(value)
            previous = self.config.get("selectedPermission")
            self.config["selectedPermission"] = actual
            try:
                self.save()
            except Exception as exc:
                if previous is None: self.config.pop("selectedPermission", None)
                else: self.config["selectedPermission"] = previous
                raise RuntimeError(tr("权限已在当前 Grok 进程生效，但会话选择保存失败；重启仍使用上次保存值",
                                      "Permission took effect in the current Grok process, but saving session selection failed; restart will use previous value")) from exc
        if has_plan:
            actual_plan = self.set_native_plan_mode(plan_value)
            prev_plan = self.config.get("selectedPlanMode")
            self.config["selectedPlanMode"] = actual_plan
            try:
                self.save()
            except Exception as exc:
                if prev_plan is None: self.config.pop("selectedPlanMode", None)
                else: self.config["selectedPlanMode"] = prev_plan
                raise RuntimeError(tr("规划模式已在当前 Grok 进程生效，但会话选择保存失败；重启仍使用上次保存值",
                                      "Plan mode took effect in the current Grok process, but saving session selection failed; restart will use previous value")) from exc
        return result

    def apply_options(self, options):
        previous = self.data["model"]
        super().apply_options(options)
        if any(o.get("type") == "select" and o.get("category", o.get("id")) == "model" for o in options):
            self.update(catalogState="ready")
        if previous and self.data["model"] != previous: self.grok_context_tokens = None
        self.render_metrics()

    def apply_commands(self, commands):
        super().apply_commands(commands)
        self.update(capabilities={**self.data["capabilities"], "compact": False})

    def render_metrics(self):
        model = next((m for m in self.native_model_state.get("availableModels", [])
                      if m.get("modelId") == self.data["model"]), {})
        window = model.get("_meta", {}).get("totalContextTokens")
        model_id = self.data.get("model") or self.config.get("model")
        self.update(metrics=grok_context(self.grok_context_tokens, window, model=model_id) + grok_usage(self.grok_process_usage, model=model_id),
                    grokUsage={"process": self.grok_process_usage, "lastTurn": self.grok_last_usage})

    def refresh_metrics(self):
        if not self.metrics_lock.acquire(blocking=False): return
        try:
            result = extension_result(self.call("_x.ai/session/usage", {"sessionId": self.data["nativeId"]}))
            if isinstance(result.get("usage"), dict):
                self.grok_process_usage = result["usage"]
                self.render_metrics()
        except (Rejected, ConnectionError, TimeoutError):
            self.grok_process_usage = {}
            self.render_metrics()
        finally: self.metrics_lock.release()

    def validate_prompt(self, payload):
        state = self.data.get("lifecycle", {})
        if state and state.get("phase") != "ready":
            raise Rejected(state.get("message") or tr("Grok 尚未就绪，请等待原生连接完成", "Grok is not ready yet; wait for native connection"))
        super().validate_prompt(payload)
        first = payload.get("text", "").strip().split(maxsplit=1)
        if first and first[0].lower() in ("/clear", "/reset", "/new", "/rewind", "/compact", "/always-approve"):
            raise Rejected(tr("此命令由 Grok 原生界面管理，不能作为托管会话消息执行", "This command is managed by the native Grok UI and cannot be sent as a managed message"))

    def prompt(self, payload):
        ident = payload["submissionId"]
        blocks = ([{"type": "text", "text": payload["text"]}] if payload["text"] else []) + image_blocks(payload.get("images", []))
        if payload.get("steer"):
            def interject():
                try:
                    value = extension_result(self.call("_x.ai/interject", {"sessionId": self.data["nativeId"],
                        "text": payload["text"], "content": blocks, "interjectionId": ident}))
                    if not isinstance(value, dict) or value.get("status") != "queued":
                        self.receipt(ident, "rejected")
                        self.update(error=tr("Grok 插话未确认", "Grok interjection was not confirmed"))
                except Rejected as exc:
                    self.receipt(ident, "rejected")
                    self.fail(exc)
                except Exception as exc:
                    self.fail(exc)
            threading.Thread(target=interject, daemon=True, name="LumaGrokInterject").start()
            return False
        turn = {"event": threading.Event(), "result": None}
        self.turns[ident] = turn
        self.current_submission = ident
        self.chunk_kind = None
        self.update(running=True)
        self.row(item(ident, "user", payload["text"], turnId=ident, origin="local"))
        def execute():
            key = str(uuid.uuid4())
            waiter = queue.Queue(maxsize=1)
            with self.peer.lock: self.peer.pending[key] = waiter
            try:
                self.peer.send({"jsonrpc": "2.0", "id": key, "method": "session/prompt", "params": {
                    "sessionId": self.data["nativeId"], "prompt": blocks,
                    "_meta": {"promptId": ident, "requestId": ident}}})
                while not turn["event"].is_set():
                    try: response = waiter.get(timeout=.25)
                    except queue.Empty: continue
                    if isinstance(response, Exception): raise response
                    if "error" in response:
                        error = response["error"]
                        raise RpcRejected(error) if isinstance(error, dict) else Rejected(str(error))
                    self.complete_turn(ident, response.get("result", {}))
            except Exception as exc:
                if not turn["event"].is_set():
                    with self.lock:
                        if self.current_submission == ident:
                            self.finish_chunk("unknown")
                            self.current_submission = None
                            self.update(running=False)
                            self.fail(exc)
            finally:
                with self.peer.lock: self.peer.pending.pop(key, None)
                self.turns.pop(ident, None)
        threading.Thread(target=execute, daemon=True, name="LumaGrokPrompt").start()
        return False

    def _note_autonomous_prompt(self, prompt):
        if not isinstance(prompt, str) or not prompt.startswith("task-completed-"):
            return
        if getattr(self, "processing_replay", False):
            return
        with self.lock:
            prompts = getattr(self, "autonomous_prompts", None)
            if prompts is None:
                prompts = set()
                self.autonomous_prompts = prompts
            prompts.add(prompt)
            self.last_task_completed_at = None
            if getattr(self, "current_submission", None) is None and not self.data.get("running"):
                self.update(running=True)

    def _note_observed_prompt(self, prompt):
        if not isinstance(prompt, str) or not prompt:
            return
        if prompt == getattr(self, "current_submission", None) or prompt.startswith("interject-fallback-"):
            return
        if getattr(self, "processing_replay", False):
            return
        if prompt.startswith("task-completed-"):
            self._note_autonomous_prompt(prompt)
            return
        with self.lock:
            prompts = getattr(self, "observed_prompts", None)
            if prompts is None:
                prompts = set()
                self.observed_prompts = prompts
            prompts.add(prompt)
            if getattr(self, "current_submission", None) is None and not self.data.get("running"):
                self.update(running=True)

    def complete_turn(self, ident, result, replay=False):
        if not ident: return
        if replay:
            if ident in self.config.get("receipts", {}): self.receipt(ident, "accepted")
            if ident in self.config.get("receipts", {}): self.consumed(ident)
            return
        if ident.startswith("task-completed-"):
            stop = result.get("stopReason", result.get("stop_reason", "end_turn"))
            status = "cancelled" if stop == "cancelled" else "failed" if stop == "error" else "completed"
            settled_auto = False
            with self.lock:
                prompts = getattr(self, "autonomous_prompts", None)
                was_tracked = bool(prompts and ident in prompts)
                if prompts is not None:
                    prompts.discard(ident)
                self.last_task_completed_at = None
                if self.chunk_prompt == ident:
                    self.finish_chunk(status)
                    self.chunk_id = self.chunk_kind = None
                if getattr(self, "current_submission", None) is None and not prompts and not getattr(self, "observed_prompts", None) and (was_tracked or self.data.get("running")):
                    self._flush_interjections(force=True)
                    self.update(running=False)
                    settled_auto = True
            if settled_auto:
                threading.Thread(target=self.refresh_metrics, daemon=True, name="LumaGrokUsage").start()
            return
        if ident in self.config.get("receipts", {}): self.receipt(ident, "accepted")
        if ident in self.config.get("receipts", {}): self.consumed(ident)
        turn = self.turns.get(ident)
        if turn: turn["result"] = result; turn["event"].set()
        if self.current_submission != ident:
            observed = getattr(self, "observed_prompts", None)
            was_observed = bool(observed and ident in observed)
            if not was_observed and (getattr(self, "current_submission", None) is not None or not self.data.get("running") or not hasattr(self, "owner") or observed):
                return
            stop = result.get("stopReason", result.get("stop_reason", "end_turn"))
            status = "cancelled" if stop == "cancelled" else "failed" if stop == "error" else "completed"
            settled_observed = False
            with self.lock:
                if observed is not None:
                    observed.discard(ident)
                if self.chunk_prompt == ident:
                    self.finish_chunk(status)
                    self.chunk_id = self.chunk_kind = None
                if stop in ("cancelled", "error"):
                    background = set(self.background_tools.values())
                    for key, row in list(self.rows.items()):
                        if key not in background and row.get("turnId") == ident and row.get("status") in ("running", "in_progress", "pending"):
                            self.row({**row, "status": "cancelled" if stop == "cancelled" else "unknown"})
                if getattr(self, "current_submission", None) is None and not observed and not getattr(self, "autonomous_prompts", None) and self.data.get("running"):
                    self._flush_interjections(force=True)
                    self.update(running=False)
                    settled_observed = True
                if stop == "error":
                    self.fail(Rejected(safe_reason(result.get("agent_result") or result.get("agentResult") or tr("Grok 执行失败", "Grok execution failed"))))
            if settled_observed:
                threading.Thread(target=self.refresh_metrics, daemon=True, name="LumaGrokUsage").start()
            return
        getattr(self, "observed_prompts", set()).discard(ident)
        stop = result.get("stopReason", result.get("stop_reason", "end_turn"))
        status = "cancelled" if stop == "cancelled" else "failed" if stop == "error" else "completed"
        with self.lock:
            if self.chunk_prompt == ident: self.finish_chunk(status)
            if stop in ("cancelled", "error"):
                background = set(self.background_tools.values())
                for key, row in list(self.rows.items()):
                    if key not in background and row.get("turnId") == ident and row.get("status") in ("running", "in_progress", "pending"):
                        self.row({**row, "status": "cancelled" if stop == "cancelled" else "unknown"})
            for key, raw in list(self.pending_requests.items()):
                if raw.get("params", {}).get("sessionId") == self.data["nativeId"]: self.answered(key)
            self._flush_interjections(force=True)
            self.current_submission = None
            self.update(running=False)
            if stop == "error":
                self.fail(Rejected(safe_reason(result.get("agent_result") or result.get("agentResult") or tr("Grok 执行失败", "Grok execution failed"))))
        threading.Thread(target=self.refresh_metrics, daemon=True, name="LumaGrokUsage").start()

    def fold_user_echo(self, prompt, body, content, replay):
        ident = prompt or self.current_submission
        if not ident or ident not in self.config.get("receipts", {}): return False
        old = self.rows.get(ident)
        if not old or old.get("role") != "user": return False
        merged = old.get("text") or ""
        if body and merged and body != merged and not merged.startswith(body) and not body.startswith(merged):
            return False
        if not merged or (body.startswith(merged) and body != merged): merged = body
        self.finish_chunk()
        self.chunk_id = ident
        self.chunk_kind = "user_message_chunk"
        self.chunk_prompt = prompt or ident
        shown = {**old, "text": merged, "media": old.get("media", []) + self.media(content), "turnId": old.get("turnId") or ident}
        if replay: shown["status"] = "completed"
        self.row(shown)
        if prompt and (prompt == self.current_submission or (replay and prompt in self.config.get("receipts", {}))):
            self.receipt(prompt, "accepted")
            self.consumed(prompt)
        return True

    def stop(self):
        self.peer.send({"jsonrpc": "2.0", "method": "session/cancel", "params": {
            "sessionId": self.data["nativeId"], "_meta": {"cancelTrigger": "ctrl_c"}}})
        return {}

    def event(self, frame):
        params = frame.get("params", {})
        method = frame.get("method", "").removeprefix("_")
        if method == "x.ai/sessions/changed":
            self.apply_roster(params.get("upserted", [])); return
        native = self.data["nativeId"]
        if params.get("sessionId") and native and params["sessionId"] != native:
            child = self.child_sessions.get(params["sessionId"])
            if not child: return
            if method not in ("session/request_permission", "x.ai/ask_user_question", "x.ai/exit_plan_mode"):
                meta = params.get("_meta", {})
                event_id = meta.get("eventId") if isinstance(meta, dict) else None
                if event_id:
                    if event_id in self.seen_events: return
                    self.seen_events.add(event_id)
                if method in EXT_UPDATES or method == "session/update":
                    self.project_child(child, params.get("update", {}))
                return
        if self.loading and not self.processing_replay and params.get("_meta", {}).get("isReplay") and (method == "session/update" or method in EXT_UPDATES):
            if not self.replay_started:
                with self.lock: self.rows.clear()
                self.replay_started = True
            self.replay_frames.append(frame)
            if params.get("update", {}).get("sessionUpdate") == "turn_completed": self.flush_replay()
            return
        meta = params.get("_meta", {})
        event_id = meta.get("eventId")
        if event_id:
            if event_id in self.seen_events: return
            self.seen_events.add(event_id)
        if method in EXT_UPDATES or method == "session/update":
            update = params.get("update", {})
            kind = update.get("sessionUpdate")
            if "totalTokens" in meta and not meta.get("isReplay"):
                self.grok_context_tokens = meta["totalTokens"]; self.render_metrics()
            if kind == "turn_completed":
                if meta.get("isReplay"):
                    self.finish_chunk()
                    self.chunk_id = self.chunk_kind = None
                if isinstance(update.get("usage"), dict): self.grok_last_usage = update["usage"]
                self.complete_turn(update.get("prompt_id") or update.get("promptId"), update, replay=bool(meta.get("isReplay")))
                return
            if kind == "session_summary_generated":
                if update.get("session_summary"): self.update(title=update["session_summary"])
                return
            if kind == "model_changed":
                if update.get("model_id") != self.data["model"]: self.grok_context_tokens = None
                self.update(model=update.get("model_id", self.data["model"]), effort=update.get("reasoning_effort", ""))
                self.render_metrics(); return
            if kind == "model_auto_switched":
                if meta.get("isReplay"): return
                reason = safe_reason(update.get("reason") or tr("Grok 原生模型已变更", "Grok native model changed"))
                replacement = update.get("new_model_id")
                if isinstance(replacement, str) and replacement:
                    self.grok_context_tokens = None
                    self.update(model=replacement, notice=reason)
                    self.render_metrics()
                else:
                    self.fail(Rejected(reason), "model")
                return
            if kind == "retry_state":
                if meta.get("isReplay"): return
                if update.get("type") == "failed":
                    tag = update.get("error_type")
                    kind = "authentication" if tag == "auth" else "model" if tag == "model" else "request"
                    self.fail(Rejected(safe_reason(update.get("message") or tr("Grok 执行失败", "Grok execution failed"))), kind)
                elif update.get("message"):
                    self.update(notice=safe_reason(update["message"]))
                return
            if self.project_task(update): return
            prompt = meta.get("promptId") or meta.get("lumaReplayPromptId")
            if kind == "response_completed":
                if not meta.get("isReplay"):
                    with self.lock:
                        self.finish_chunk()
                        self.chunk_id = self.chunk_kind = None
                        self._flush_interjections()
                        for entry in self.config.get("pendingInterjections", []):
                            entry["armed"] = True
                        if self.config.get("pendingInterjections"):
                            self._checkpoint_native_evidence("interjection-armed")
                return
            if kind == "tool_call_delta_chunk":
                if not meta.get("isReplay"):
                    self._flush_interjections()
                return
            if kind in CONTENT:
                if update.get("_meta", {}).get("hideFromScrollback") is True: return
                content = update.get("content", {})
                body = content.get("_meta", {}).get("displayText") or text(content)
                if not body and not self.media(content): return
                if kind == "user_message_chunk" and self.fold_user_echo(prompt, body, content, bool(meta.get("isReplay"))):
                    return
                if kind != "user_message_chunk" and not meta.get("isReplay"):
                    if isinstance(prompt, str) and prompt.startswith("interject-fallback-") and not self.current_submission:
                        self._flush_interjections(force=True)
                        self.current_submission = prompt
                        self.update(running=True)
                    else:
                        self._flush_interjections()
                        self._note_observed_prompt(prompt)
                discrete = kind == "user_message_chunk"
                reuse_prompt = (
                    kind == "user_message_chunk"
                    and bool(prompt)
                    and prompt in self.config.get("receipts", {})
                    and prompt not in self.rows
                )
                if self.chunk_kind != kind or self.chunk_prompt != prompt or discrete:
                    self.finish_chunk()
                    self.chunk_id = prompt if reuse_prompt else "grok:" + (str(event_id) if event_id else str(uuid.uuid4()))
                    self.chunk_kind = kind
                    self.chunk_prompt = prompt
                    self.row(item(self.chunk_id, CONTENT[kind], "", turnId=prompt or "", kind="reasoning" if kind == "agent_thought_chunk" else ""))
                old = self.rows[self.chunk_id]
                self.row({**old, "text": old["text"] + body, "media": old.get("media", []) + self.media(content), "status": "completed" if meta.get("isReplay") else "running"})
                if prompt and (prompt == self.current_submission or (meta.get("isReplay") and prompt in self.config.get("receipts", {}))) and (kind != "user_message_chunk" or self.chunk_id == prompt):
                    self.receipt(prompt, "accepted")
                    self.consumed(prompt)
                return
            if kind in ("tool_call", "tool_call_update"):
                if not meta.get("isReplay"):
                    if isinstance(prompt, str) and prompt.startswith("interject-fallback-") and not self.current_submission:
                        self._flush_interjections(force=True)
                        self.current_submission = prompt
                        self.update(running=True)
                    else:
                        self._flush_interjections()
                        self._note_observed_prompt(prompt)
                if prompt == self.current_submission and prompt:
                    self.receipt(prompt, "accepted")
                    self.consumed(prompt)
            if kind in ("tool_call", "tool_call_update"):
                self.finish_chunk()
                self.chunk_id = self.chunk_kind = None
                ident = "tool-" + update["toolCallId"]
                old = self.rows.get(ident, item(ident, "tool", tr("工具", "Tool"), kind="tool"))
                fields = {"turnId": prompt} if prompt else {}
                for source, target in (("title", "text"), ("kind", "toolKind"), ("status", "status")):
                    if source in update: fields[target] = update[source]
                if fields.get("status") == "completed" and old.get("status") == "running" and ident in self.background_tools.values():
                    fields.pop("status")
                if "rawInput" in update: fields["input"] = json.dumps(update["rawInput"], ensure_ascii=False)
                if "content" in update:
                    fields["outcome"] = display_content(update["content"])
                    fields["media"] = self.media(update["content"])
                if "rawOutput" in update: fields["outcome"] = json.dumps(display_object(update["rawOutput"]), ensure_ascii=False)
                adjust = self.native_tool(update, old)
                if adjust.pop("dropKind", False): fields.pop("toolKind", None)
                fields.update(adjust)
                self.row({**old, **fields})
                self.note_spawn(update)
            elif kind == "current_mode_update":
                mode_id = update.get("currentModeId")
                if mode_id in ("plan", "build"):
                    self.plan_mode_active = mode_id == "plan"
                    self.update(planMode=self.plan_mode_active)
                    if not getattr(self, "processing_replay", False) and self.config.get("selectedPlanMode") is not None and self.config.get("selectedPlanMode") != self.plan_mode_active:
                        self.config["selectedPlanMode"] = self.plan_mode_active
                        try:
                            self.save()
                        except Exception:
                            pass
            elif kind == "config_option_update": self.apply_options(update.get("configOptions", []))
            elif kind == "available_commands_update": self.apply_commands(update.get("availableCommands", []))
            elif kind == "session_info_update" and update.get("title"): self.update(title=update["title"])
            elif kind == "usage_update": self.apply_usage(update)
            return
        if method == "x.ai/queue/changed":
            running_prompt = params.get("runningPromptId")
            if isinstance(running_prompt, str) and running_prompt:
                if running_prompt == self.current_submission and running_prompt in self.config.get("receipts", {}):
                    self.receipt(running_prompt, "accepted")
                    self.consumed(running_prompt)
                elif running_prompt.startswith("interject-fallback-") and not self.current_submission:
                    self._flush_interjections(force=True)
                    self.current_submission = running_prompt
                    self.update(running=True)
                else:
                    self._note_observed_prompt(running_prompt)
            return
        if method == "x.ai/session/prompt_complete":
            self.complete_turn(params.get("promptId"), params); return
        if method == "x.ai/session/interjection":
            ident = params.get("interjectionId")
            if ident:
                with self.lock:
                    entry = {"id": ident, "text": params.get("text", ""), "armed": self._has_active_step_wait()}
                    if not self.data["running"]:
                        self._admit_interjection_row(entry)
                    else:
                        pending = self.config.setdefault("pendingInterjections", [])
                        if all(e["id"] != ident for e in pending):
                            pending.append(entry)
                        self.seq += 1
                if ident in self.config.get("receipts", {}):
                    self.receipt(ident, "accepted")
                else:
                    self._checkpoint_native_evidence("interjection-queued")
            return
        if method == "x.ai/ask_user_question" and "id" in frame:
            questions = params.get("questions", [])
            self.request(str(frame["id"]), frame, "Answer", "", [{"id": "accept", "label": tr("确认", "Confirm")}, {"id": "cancel", "label": tr("取消", "Cancel")}],
                [{"id": str(i), "header": q.get("header", ""), "prompt": q.get("question", ""),
                  "options": [o["label"] for o in q.get("options", [])], "multiple": bool(q.get("multiSelect"))} for i,q in enumerate(questions)],
                submit_choice="accept", skip_choice="cancel")
            return
        if method == "x.ai/exit_plan_mode" and "id" in frame:
            self.request(str(frame["id"]), frame, tr("计划", "Plan"), params.get("planContent") or "", [
                {"id": "approved", "label": tr("执行", "Execute")}, {"id": "cancelled", "label": tr("取消", "Cancel")}, {"id": "abandoned", "label": tr("放弃", "Abandon")}]); return
        if method == "session/request_permission" and "id" in frame:
            tool = params.get("toolCall", {})
            cancel = "luma:cancel"
            options = params.get("options", [])
            while cancel in {o["optionId"] for o in options}: cancel = "luma:" + cancel
            face = self.permission_face(tool if isinstance(tool, dict) else {})
            if face: title, body = face
            else:
                title = tool.get("title", tr("工具授权", "Tool permission")) if isinstance(tool, dict) else tr("工具授权", "Tool permission")
                body = json.dumps(tool.get("rawInput", {}), ensure_ascii=False) if isinstance(tool, dict) else ""
            self.request(str(frame["id"]), {**frame, "_cancelChoice": cancel}, title, body,
                         [{"id":o["optionId"], "label":o["name"]} for o in options] + [{"id":cancel,"label":tr("取消", "Cancel")}])
            return
        if method in ("x.ai/task_backgrounded", "x.ai/task_completed"):
            self.project_task(params.get("update", {"sessionUpdate": method.split("/")[-1], **params})); return
        super().event(frame)

    def permission_face(self, tool):
        meta = tool.get("_meta") if isinstance(tool.get("_meta"), dict) else {}
        native = meta.get("x.ai/tool") if isinstance(meta.get("x.ai/tool"), dict) else {}
        raw = tool.get("rawInput") if isinstance(tool.get("rawInput"), dict) else {}
        name = native.get("name") if isinstance(native.get("name"), str) else ""
        variant = raw.get("variant") if isinstance(raw.get("variant"), str) else ""
        if name != "spawn_subagent" and variant != "Task": return None
        description = raw.get("description") if isinstance(raw.get("description"), str) else ""
        prompt = raw.get("prompt") if isinstance(raw.get("prompt"), str) else ""
        if not description and not prompt: return None
        return tr("启动子代理", "Spawn subagent"), "\n\n".join(part for part in (description, prompt) if part)

    def native_tool(self, update, old):
        meta = update.get("_meta") if isinstance(update.get("_meta"), dict) else {}
        native = meta.get("x.ai/tool") if isinstance(meta.get("x.ai/tool"), dict) else {}
        out = {}
        native_kind = native.get("kind") if isinstance(native.get("kind"), str) else ""
        native_name = native.get("name") if isinstance(native.get("name"), str) else ""
        reported = update.get("kind") if isinstance(update.get("kind"), str) else ""
        stored = old.get("toolKind") if isinstance(old.get("toolKind"), str) else ""
        if reported == "other" and stored and stored != "other":
            out["dropKind"] = True
        elif reported == "other" and native_kind:
            out["toolKind"] = native_kind
        elif not reported and native_kind and not stored:
            out["toolKind"] = native_kind
        if native_name: out["toolName"] = native_name
        return out

    def note_spawn(self, update):
        meta = update.get("_meta") if isinstance(update.get("_meta"), dict) else {}
        native = meta.get("x.ai/tool") if isinstance(meta.get("x.ai/tool"), dict) else {}
        if native.get("name") != "spawn_subagent": return
        call = update.get("toolCallId")
        if not isinstance(call, str) or not call: return
        raw = update.get("rawInput") if isinstance(update.get("rawInput"), dict) else {}
        description = raw.get("description") if isinstance(raw.get("description"), str) else ""
        prompt = raw.get("prompt") if isinstance(raw.get("prompt"), str) else ""
        ident = "tool-" + call
        tool = next((item for item in self.spawn_tools if item["id"] == ident), None)
        if tool is None:
            tool = {"id": ident, "description": description, "prompt": prompt, "bound": ""}
            self.spawn_tools.append(tool)
        else:
            if description: tool["description"] = description
            if prompt: tool["prompt"] = prompt
        self.link_spawn_tool(tool)

    def agent_bound(self, ident):
        if any(item.get("bound") == ident for item in self.spawn_tools): return True
        return bool(self.subagents.get(ident, {}).get("itemId"))

    def link_spawn_tool(self, tool):
        if tool.get("bound"):
            self.apply_spawn_link(tool["bound"], tool); return
        name = tool.get("description") or ""
        chosen = ""
        for ident, agent in self.subagents.items():
            if self.agent_bound(ident): continue
            if name:
                if (agent.get("name") or "") == name:
                    chosen = ident; break
            else:
                chosen = ident; break
        if not chosen: return
        tool["bound"] = chosen
        self.apply_spawn_link(chosen, tool)

    def claim_spawn(self, ident, name):
        if self.agent_bound(ident): return
        tool = None
        if name:
            for item in self.spawn_tools:
                if item.get("bound"): continue
                if item.get("description") == name:
                    tool = item; break
        if tool is None:
            for item in self.spawn_tools:
                if item.get("bound"): continue
                if not item.get("description"):
                    tool = item; break
        if tool is None: return
        tool["bound"] = ident
        self.apply_spawn_link(ident, tool)

    def apply_spawn_link(self, ident, tool):
        agent = self.subagents.get(ident)
        if not isinstance(agent, dict): return
        fields = {}
        if not agent.get("itemId"): fields["itemId"] = tool["id"]
        prompt = tool.get("prompt") or ""
        if prompt and agent.get("task") != prompt: fields["task"] = prompt
        if prompt:
            messages = [dict(m) for m in (agent.get("messages") or [])]
            if not any(m.get("role") == "user" and m.get("text") == prompt for m in messages):
                messages.insert(0, {"id": ident + ":task", "role": "user", "text": prompt})
                fields["messages"] = messages
        if fields: self.subagent(ident, **fields)

    def child_messages(self, ident):
        return [dict(m) for m in (self.subagents.get(ident, {}).get("messages") or [])]

    def chunk_body(self, update):
        if isinstance(update.get("_meta"), dict) and update["_meta"].get("hideFromScrollback") is True: return None
        content = update.get("content", {})
        if not isinstance(content, dict): return text(content)
        meta = content.get("_meta") if isinstance(content.get("_meta"), dict) else {}
        return meta.get("displayText") or text(content)

    def project_child(self, ident, update):
        if not isinstance(update, dict): return
        kind = update.get("sessionUpdate")
        if kind in ("user_message_chunk", "agent_thought_chunk", "agent_message_chunk"):
            self.child_text(ident, kind, update)
        elif kind in ("tool_call", "tool_call_update"):
            self.child_tool(ident, update)

    def child_text(self, ident, kind, update):
        body = self.chunk_body(update)
        if not body: return
        messages = self.child_messages(ident)
        agent = self.subagents.get(ident, {})
        if kind == "user_message_chunk":
            if body == (agent.get("task") or ""): return
            if any(m.get("role") == "user" and m.get("text") == body for m in messages): return
            messages.append({"id": ident + ":user:" + str(len(messages)), "role": "user", "text": body})
        elif kind == "agent_thought_chunk":
            if messages and messages[-1].get("kind") == "reasoning":
                if messages[-1].get("text") == body: return
                messages[-1]["text"] = (messages[-1].get("text") or "") + body
            else:
                messages.append({"id": ident + ":thought:" + str(len(messages)), "role": "tool", "text": body, "kind": "reasoning"})
        elif messages and messages[-1].get("role") == "assistant":
            if messages[-1].get("text") == body: return
            messages[-1]["text"] = (messages[-1].get("text") or "") + body
        else:
            messages.append({"id": ident + ":assistant:" + str(len(messages)), "role": "assistant", "text": body})
        self.subagent(ident, messages=messages)

    def child_tool(self, ident, update):
        call = update.get("toolCallId")
        if not isinstance(call, str) or not call: return
        messages = self.child_messages(ident)
        row_id = ident + ":tool-" + call
        idx = next((i for i, m in enumerate(messages) if m.get("id") == row_id), -1)
        old = messages[idx] if idx >= 0 else {"id": row_id, "role": "tool", "text": tr("工具", "Tool"), "kind": "tool"}
        fields = {}
        for source, target in (("title", "text"), ("kind", "toolKind"), ("status", "status")):
            if source in update: fields[target] = update[source]
        adjust = self.native_tool(update, old)
        if adjust.pop("dropKind", False): fields.pop("toolKind", None)
        fields.update(adjust)
        if "rawInput" in update: fields["input"] = json.dumps(update["rawInput"], ensure_ascii=False)
        if "content" in update: fields["outcome"] = display_content(update["content"])
        if "rawOutput" in update: fields["outcome"] = json.dumps(display_object(update["rawOutput"]), ensure_ascii=False)
        nxt = {**old, **fields}
        if idx >= 0: messages[idx] = nxt
        else: messages.append(nxt)
        self.subagent(ident, messages=messages)

    def ensure_child_output(self, ident, output):
        if not output: return
        messages = self.child_messages(ident)
        if any(m.get("role") == "assistant" and m.get("text") == output for m in messages): return
        messages.append({"id": ident + ":output", "role": "assistant", "text": output})
        self.subagent(ident, messages=messages)

    def project_task(self, update):
        kind = update.get("sessionUpdate", "")
        if kind in ("subagent_spawned", "subagent_progress", "subagent_finished"):
            ident = update.get("subagent_id") or update.get("child_session_id")
            child = update.get("child_session_id", ident)
            if child: self.child_sessions[child] = ident
            fields = {}
            if kind == "subagent_spawned":
                fields.update(name=update.get("description", ""), task=update.get("description", ""),
                              role=update.get("subagent_type", ""), status="running", nativeStatus="running",
                              turnId=update.get("parent_prompt_id", ""))
                model = update.get("model")
                if isinstance(model, str) and model: fields["model"] = model
            elif kind == "subagent_finished":
                status = update.get("status", "unknown")
                fields.update(status="succeeded" if status == "completed" else status, nativeStatus=status,
                              detail=update.get("output") or update.get("error") or "")
            elif kind == "subagent_progress":
                fields.update(status="running", nativeStatus="running")
            self.subagent(ident, **fields)
            if ident and kind == "subagent_spawned":
                description = update.get("description") if isinstance(update.get("description"), str) else ""
                self.claim_spawn(ident, description)
            elif ident and kind == "subagent_finished":
                output = update.get("output") if isinstance(update.get("output"), str) else ""
                self.ensure_child_output(ident, output)
            return True
        if kind == "task_backgrounded":
            ident = "tool-" + update["tool_call_id"]
            self.background_tools[update["task_id"]] = ident
            self.row({**self.rows.get(ident, item(ident, "tool", update.get("command", ""), kind="tool")), "status": "running", "toolKind": "execute"}); return True
        if kind == "task_completed":
            task = update.get("task_snapshot", {})
            ident = self.background_tools.get(task.get("id") or task.get("task_id"))
            if not getattr(self, "processing_replay", False) and not getattr(self, "loading", False):
                self.last_task_completed_at = time.monotonic()
            if ident and ident in self.rows:
                self.row({**self.rows[ident], "status": "failed" if task.get("exit_code") not in (None, 0) or task.get("signal") else "completed", "outcome": text(task.get("output"))})
            return True
        if kind == "background_tasks":
            present = set()
            for task in update.get("tasks", []):
                task_id = task.get("task_id")
                if not task_id: continue
                present.add(task_id)
                ident = self.background_tools.setdefault(task_id, "grok-bg:" + task_id)
                old = self.rows.get(ident, item(ident, "tool", task.get("display_command") or task.get("command", ""), kind="tool"))
                self.row({**old, "status": task.get("status", "unknown"), "toolKind": "execute"})
            if not update.get("truncated"):
                for task_id, ident in self.background_tools.items():
                    if task_id not in present and self.rows.get(ident, {}).get("status") in ("running", "in_progress", "pending"):
                        self.row({**self.rows[ident], "status": "unknown"})
            return True
        if kind == "monitor_event":
            ident = self.background_tools.get(update.get("task_id"))
            if ident and ident in self.rows:
                old = self.rows[ident]; self.row({**old, "outcome": old.get("outcome", "") + update.get("event_text", "") + "\n"})
            return True
        return False

    def answer(self, payload):
        raw = self.pending_requests.get(payload["id"])
        method = (raw or {}).get("method", "").removeprefix("_")
        if method == "x.ai/ask_user_question":
            if payload["choice"] == "cancel": result = {"outcome": "cancelled"}
            elif payload["choice"] == "accept":
                answers, annotations = {}, {}
                for i,q in enumerate(raw["params"].get("questions", [])):
                    values = payload.get("answers", {}).get(str(i), [])
                    if not values or any(not isinstance(v,str) or not v.strip() for v in values): raise Rejected(tr("请回答问题", "Please answer the question"))
                    if not q.get("multiSelect") and len(values) != 1: raise Rejected(tr("此问题只支持一个答案", "Only one answer is supported for this question"))
                    selected = [v for v in values if v in {o["label"] for o in q.get("options", [])}]
                    notes = [v for v in values if v not in selected]
                    answers[q["question"]] = selected or ["Other"]
                    annotation = {}
                    if notes: annotation["notes"] = "\n".join(notes)
                    preview = next((o.get("preview") for o in q.get("options", []) if o["label"] in selected and o.get("preview")), None)
                    if preview and not q.get("multiSelect"): annotation["preview"] = preview
                    if annotation: annotations[q["question"]] = annotation
                result = {"outcome": "accepted", "answers": answers, **({"annotations": annotations} if annotations else {})}
            else: raise Rejected(tr("无效回答", "Invalid answer"))
        elif method == "x.ai/exit_plan_mode":
            if payload["choice"] not in ("approved", "cancelled", "abandoned"): raise Rejected(tr("无效计划选项", "Invalid plan option"))
            result = {"outcome": payload["choice"]}
        else: return super().answer(payload)
        self.peer.send({"jsonrpc": "2.0", "id": raw["id"], "result": result})
        self.answered(payload["id"]); return {}

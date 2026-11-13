import json
import os
from pathlib import Path
import re
import shutil
import uuid
from .pi import PiSession, strip_pi_image_hints
from .metrics import pi_usage
from .native_owner import NativeOwner
from .transport import Rejected

REQUIRED_SDK_EXPORTS = (
    "createAgentSessionRuntime",
    "createAgentSessionServices",
    "createAgentSessionFromServices",
    "SessionManager",
    "InteractiveMode",
    "ExtensionEditorComponent",
    "SettingsManager",
    "ProjectTrustStore",
    "hasTrustRequiringProjectResources",
)

def probe_pi_native(binary=None):
    binary = binary or shutil.which("pi")
    if not binary:
        return False, False, "未找到 pi", None
    try:
        resolved = Path(binary).resolve()
    except OSError:
        return True, False, "无法解析已安装 Pi 路径", None
    package = next((p for p in resolved.parents if (p / "package.json").is_file()), None)
    if package is None:
        return True, False, "无法定位已安装 Pi SDK", None
    try:
        version = str(json.loads((package / "package.json").read_text(encoding="utf-8"))["version"])
    except (OSError, KeyError, ValueError):
        return True, False, "无法核对 Pi SDK 版本", package
    match = re.search(r"(\d+)\.(\d+)\.(\d+)", version)
    if not match or tuple(map(int, match.groups())) < (1, 5, 0):
        return True, False, "原生双端 Pi 需要 1.5.0 或更新的 SDK；原有 RPC 会话入口保持可用", package
    index_js = package / "dist/index.js"
    core_trust = package / "dist/core/project-trust.js"
    cli_trust = package / "dist/cli/project-trust.js"
    agent_session = package / "dist/core/agent-session.js"
    if not (index_js.is_file() and core_trust.is_file() and cli_trust.is_file()):
        return True, False, "当前 Pi SDK 目录缺少原生双端所需模块；原有 RPC 会话入口保持可用", package
    try:
        index_src = index_js.read_text(encoding="utf-8", errors="ignore")
        core_trust_src = core_trust.read_text(encoding="utf-8", errors="ignore")
        cli_trust_src = cli_trust.read_text(encoding="utf-8", errors="ignore")
        session_src = agent_session.read_text(encoding="utf-8", errors="ignore") if agent_session.is_file() else index_src
    except OSError:
        return True, False, "无法读取已安装 Pi SDK 模块", package
    if (not all(sym in index_src for sym in REQUIRED_SDK_EXPORTS)
            or "resolveProjectTrusted" not in core_trust_src
            or "createProjectTrustContext" not in cli_trust_src
            or ("preflightResult" not in session_src and "preflightResult" not in index_src)):
        return True, False, "当前 Pi SDK 内部契约已变更（缺少双端同步或预检回执接口）；原有 RPC 会话入口保持可用", package
    return True, True, "", package

class PiNativeSession(PiSession):
    input_evidence_protocol = 1
    correlated_delivery = True
    native_stats_pushed = True

    def start(self, argv, env_overrides=None):
        found, compatible, reason, package = probe_pi_native()
        if not found or not compatible:
            raise Rejected(reason)
        self.owner = NativeOwner(self)
        directory = Path(self.config["ownerDirectory"]); directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.config.setdefault("nativeId", str(uuid.uuid4()))
        skills = []
        if self.config.get("browserConfig"):
            skill = Path(os.environ.get("LUMA_BROWSER_SKILL", "/opt/luma/agent/skills/luma-browser/SKILL.md"))
            skills = [str(skill), str(skill.parent.parent / "luma-sessions/SKILL.md")]
        saved_file = self.config.get("sessionFile")
        has_saved_file = bool(saved_file and Path(saved_file).is_file())
        options = {"socket": self.owner.socket, "receipts": str(directory / "receipts.json"),
                   "sdk": str(package / "dist/index.js"), "nativeId": self.config["nativeId"],
                   "sessionFile": saved_file if has_saved_file else None,
                   "model": self.config.get("model"), "effort": self.config.get("effort"), "skills": skills,
                   "agentDir": os.environ.get("PI_CODING_AGENT_DIR", str(Path.home() / ".pi/agent"))}
        file = directory / "host.json"
        if not self.owner.handle.get("kind"):
            file.write_text(json.dumps(options)); os.chmod(file, 0o600)
            env = {"TERM": "xterm-256color"}
            if self.config.get("browserConfig"): env["LUMA_BROWSER_CONFIG"] = self.config["browserConfig"]
            host_script = self.owner.stage_native("pi-host.mjs")
            self.owner.launch_tui([shutil.which("node") or "node", str(host_script), str(file)], env)
        self.update(canOpenTui=True, ownerInstanceId=self.owner.handle["instanceId"])
        self.peer = self.owner.connect(self.event, self.exited)
        self.peer.start_read()

    def call(self, method, params=None, **kwargs):
        return super().call(method, {**(params or {}), "nativeId": self.config.get("nativeId")}, **kwargs)

    def connect(self):
        super().connect()
        self.update(canOpenTui=True, ownerInstanceId=self.owner.handle["instanceId"])

    def refresh(self):
        result = super().refresh()
        state = self.call("get_state")
        for ident, receipt in state.get("receipts", {}).items():
            if ident in self.config.get("receipts", {}) and receipt.get("sessionId") == self.config["nativeId"]:
                self.receipt(ident, receipt["state"])
                if receipt.get("consumed"): self.consumed(ident)
        for request in state.get("requests", []): self.ui_request(request)
        return result

    def capability_notice(self): return ""

    def event(self, event):
        native = event.get("_nativeId")
        if native and self.config.get("nativeId") and native != self.config["nativeId"]:
            self.update(connected=False, canOpenTui=False, requests=[], error="原生前台会话已改变；当前 GUI 仍保留原会话身份")
            return
        if event.get("type") == "luma_state":
            model = event.get("model") or {}
            key = model.get("provider", "") + "/" + model["id"] if model.get("id") else ""
            fields = {"model": key, "effort": event.get("thinkingLevel", "")}
            if "sessionName" in event:
                fields["title"] = event.get("sessionName") or ""
            if "input" in model:
                self.supports_images = "image" in (model.get("input") or [])
                if self.data.get("capabilities"):
                    fields["capabilities"] = {**self.data["capabilities"], "images": self.supports_images}
            self.update(**fields)
        elif event.get("type") == "luma_stats":
            self._record_session_file(event.get("sessionFile"))
            self.update(metrics=pi_usage(event.get("stats") or {}, {"contextUsage": event.get("contextUsage"), "model": event.get("model")}))
        elif event.get("type") == "luma_queue": self.project_queue(event.get("queue", {}))
        elif event.get("type") == "queue_update": return
        elif event.get("type") == "luma_receipt":
            self.receipt(event["submissionId"], event["state"])
            if event.get("consumed"): self.consumed(event["submissionId"])
        else:
            if event.get("_submissionId") and event.get("message"):
                event = {**event, "message": {**event["message"], "id":event["_submissionId"]}}
            super().event(event)

    def project_queue(self, value):
        self.queue_native = {}
        entries = []
        for name in ("steering", "followUp"):
            rows = [r for r in value.get(name, []) if isinstance(r, dict) and r.get("id")]
            for raw in rows:
                body = raw.get("message", "")
                self.queue_native[raw["id"]] = (name, body)
                entries.append({"id":raw["id"], "text":strip_pi_image_hints(body), "status":"引导" if name == "steering" else "待执行",
                    "canEdit":False, "canRemove":False,
                    "canSteer":False,
                    **({"clientUserMessageId":raw["clientUserMessageId"]} if raw.get("clientUserMessageId") else {})})
        self.update(queue=entries)

    def prompt(self, p):
        from .base import image_blocks
        message = p["text"]
        if p.get("skills"): message = "/" + p["skills"][0] + " " + message
        result = self.call("prompt", {"submissionId": p["submissionId"], "message": message,
            "images": image_blocks(p.get("images", [])), "streamingBehavior": "steer" if p.get("steer") else "followUp"})
        if result.get("state") == "rejected": raise Rejected(result.get("error", "Pi 拒绝输入"))
        revision = self.run_revision
        try:
            state = self.call("get_state")
            self.apply_running(state, revision)
            if "queuedMessages" in state: self.project_queue(state["queuedMessages"])
        except Exception as exc:
            self.update(error="输入已接收，原生状态待同步：" + str(exc))
        return result.get("state") == "accepted"

    def answer(self, p):
        raw = self.pending_requests.get(p["id"])
        if not raw: return super().answer(p)
        choice = p.get("choice")
        response = {"requestId": p["id"], "cancelled": choice == "cancel"}
        if raw["method"] == "confirm":
            if choice not in ("yes", "no", "cancel"): raise Rejected("无效确认选项")
            response["confirmed"] = choice == "yes"
        elif raw["method"] == "select" and choice != "cancel":
            if choice not in {str(i) for i in range(len(raw["options"]))}: raise Rejected("无效选择")
            response["value"] = raw["options"][int(choice)]
        else: response["value"] = "\n".join(p.get("answers", {}).get("value", []))
        result = self.call("extension_ui_response", response)
        self.answered(p["id"])
        if not result.get("answered"): raise Rejected("原生请求已由另一端处理或过期")
        return {}

    def terminal(self):
        if self.peer:
            state = self.call("get_state")
            if state["sessionId"] != self.config["nativeId"]: raise Rejected("原生前台会话已改变")
        return self.owner.terminal()

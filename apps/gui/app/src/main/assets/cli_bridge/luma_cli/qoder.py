import shutil
from .acp import AcpSession
from .base import item, text
from .transport import Rejected

class QoderSession(AcpSession):
    replay_release = True
    release_empty = True
    argv = ["qoder", "--acp"]
    desired_model_on_create = True

    def start(self, argv, env_overrides=None):
        if argv == self.argv and argv == ["qoder", "--acp"]:
            command = shutil.which("qoder") or shutil.which("qodercli")
            if not command:
                raise Rejected("未找到 Qoder CLI：qoder / qodercli")
            argv = [command, "--acp"]
        return super().start(argv, env_overrides)

    def connect(self):
        self.update(historyReady=False)
        self.replaying_history = bool(self.config.get("nativeId"))
        self.replayed_rows = set()
        try:
            super().connect()
        finally:
            self.replaying_history = False
        self.finish_chunk()
        self.chunk_id = self.chunk_kind = None
        self.settle_incomplete_rows("unknown")
        self.update(historyReady=True, notice="")

    def call(self, method, params=None, **kwargs):
        submission = self.current_submission if method == "session/prompt" else None
        result = super().call(method, params, **kwargs)
        if submission and result.get("userMessageId"):
            if not self.remember_user_message(result["userMessageId"], submission):
                raise Rejected("Qoder 返回了已绑定其它消息的原生 ID")
        return result

    def remember_user_message(self, native_id, submission_id):
        with self.lock:
            mapping = self.config.setdefault("qoderUserMessages", {})
            if native_id not in mapping:
                if submission_id in mapping.values():
                    return False
                mapping[native_id] = submission_id
                self.save()
            return mapping[native_id] == submission_id
    def validate_prompt(self, payload):
        super().validate_prompt(payload)
        words = payload.get("text", "").strip().split(maxsplit=1)
        if words and words[0].lower() in ("/clear", "/reset", "/new", "/rewind"):
            raise Rejected("此命令会改写托管会话历史；请使用新建会话")

    def event(self, frame):
        params = frame.get("params", {})
        native = self.data["nativeId"]
        if native and params.get("sessionId") and params["sessionId"] != native:
            return
        if frame.get("method") == "session/update":
            update = params.get("update", {})
            kind = update.get("sessionUpdate")
            role = {"user_message_chunk": "user", "agent_message_chunk": "assistant",
                    "agent_thought_chunk": "reasoning"}.get(kind)
            message_id = update.get("messageId")
            if role and message_id:
                if role == "user" and self.current_submission:
                    mapping = self.config.get("qoderUserMessages", {})
                    if message_id in mapping:
                        if mapping[message_id] != self.current_submission:
                            return
                    elif self.current_submission not in mapping.values():
                        local_text = self.rows.get(self.current_submission, {}).get("text", "")
                        chunk_text = text(update.get("content"))
                        if not local_text or not chunk_text or local_text.startswith(chunk_text) or chunk_text.startswith(local_text):
                            if self.remember_user_message(message_id, self.current_submission):
                                self.row(item(self.current_submission, "user", ""))
                ident = (self.config.get("qoderUserMessages", {}).get(message_id)
                         if role == "user" else None) or f"qoder:{role}:{message_id}"
                if self.chunk_id != ident:
                    self.finish_chunk()
                self.chunk_id, self.chunk_kind = ident, kind
                if self.replaying_history and ident not in self.replayed_rows:
                    self.replayed_rows.add(ident)
                    self.row(item(ident, role, "", kind="reasoning" if role == "reasoning" else ""))
        super().event(frame)

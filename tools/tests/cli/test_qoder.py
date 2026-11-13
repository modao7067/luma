#!/usr/bin/env python3
import copy
import sys
import threading
from pathlib import Path
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "apps/gui/app/src/main/assets/cli_bridge"))
from luma_cli.acp import AcpSession
from luma_cli.qoder import QoderSession
from luma_cli.transport import Rejected

OPTIONS = [
    {"id": "mode", "type": "select", "category": "mode", "currentValue": "default",
     "options": [{"value": v, "name": v} for v in ("default", "acceptEdits", "auto", "dontAsk", "yolo")]},
    {"id": "model", "type": "select", "category": "model", "currentValue": "fixture",
     "options": [{"value": v, "name": v} for v in ("fixture", "other")]},
    {"id": "reasoning_effort", "type": "select", "category": "thought_level", "currentValue": "low",
     "options": [{"value": v, "name": v} for v in ("low", "high")]},
]

def session(native="native"):
    s = QoderSession({"cli": "qoder", "cwd": "/tmp", **({"nativeId": native} if native else {})}, lambda: None)
    s.current_submission = s.chunk_kind = s.chunk_id = None
    s.replaying_history = False
    s.replayed_rows = set()
    s.native_caps = {"promptCapabilities": {"image": True}}
    s.apply_options(copy.deepcopy(OPTIONS))
    s.update(connected=True)
    return s

def update(kind, **kw):
    return {"method": "session/update", "params": {"sessionId": "native", "update": {"sessionUpdate": kind, **kw}}}

class QoderTests(unittest.TestCase):
    def test_official_entry_does_not_inject_permissions_or_auth(self):
        self.assertEqual(QoderSession.argv, ["qoder", "--acp"])
        with patch("luma_cli.qoder.shutil.which", side_effect=["/bin/qoder"]), patch.object(AcpSession, "start") as start:
            session().start(QoderSession.argv)
            start.assert_called_once_with(["/bin/qoder", "--acp"], None)

    def test_official_legacy_name_only_when_modern_not_installed(self):
        with patch("luma_cli.qoder.shutil.which", side_effect=[None, "/bin/qodercli"]), patch.object(AcpSession, "start") as start:
            session().start(QoderSession.argv)
            start.assert_called_once_with(["/bin/qodercli", "--acp"], None)
        with patch("luma_cli.qoder.shutil.which", return_value=None):
            self.assertRaises(Rejected, session().start, QoderSession.argv)

    def test_never_relaunch_another_binary_after_start_failure(self):
        with patch("luma_cli.qoder.shutil.which", return_value="/bin/qoder") as which, patch.object(AcpSession, "start", side_effect=PermissionError):
            self.assertRaises(PermissionError, session().start, QoderSession.argv)
            self.assertEqual(which.call_count, 1)

    def test_modes_models_effort_follow_native_catalog(self):
        s = session()
        self.assertEqual([p["id"] for p in s.data["permissions"]], ["default", "acceptEdits", "auto", "dontAsk", "yolo"])
        self.assertEqual(s.data["models"][0]["efforts"], ["low", "high"])
        self.assertEqual(s.data["models"][1]["efforts"], [])
        s.apply_options([copy.deepcopy(OPTIONS[0]), {**OPTIONS[1], "options": []}])
        self.assertEqual(s.data["models"], [])
        self.assertEqual(s.data["effort"], "")

    def test_setting_requires_native_actual_ack(self):
        s = session()
        calls = []
        def call(method, params):
            calls.append((method, params))
            opts = copy.deepcopy(OPTIONS)
            next(o for o in opts if o["id"] == params["configId"])["currentValue"] = params["value"]
            return {"configOptions": opts}
        s.call = call
        s.configure({"permission": "auto"})
        self.assertEqual(s.data["permission"], "auto")
        self.assertEqual(calls[0][0], "session/set_config_option")
        s.call = lambda *a, **k: {"configOptions": copy.deepcopy(OPTIONS)}
        self.assertRaises(Rejected, s.configure, {"permission": "yolo"})
        self.assertEqual(s.data["permission"], "default")

    def test_setting_timeout_does_not_claim_ack_or_retry(self):
        s = session()
        with patch.object(s, "call", side_effect=TimeoutError("unknown")) as call:
            self.assertRaises(TimeoutError, s.configure, {"model": "other"})
            call.assert_called_once()
            self.assertEqual(s.data["model"], "fixture")

    def test_history_ids_separate_adjacent_users_and_thought_from_text(self):
        s = session(); s.replaying_history = True
        for kind, ident, body in [("user_message_chunk", "u1", "same"), ("user_message_chunk", "u2", "same"),
                                   ("agent_thought_chunk", "a1", "thinking"), ("agent_message_chunk", "a1", "answer")]:
            s.event(update(kind, messageId=ident, content={"type": "text", "text": body}))
        self.assertEqual(len(s.rows), 4)
        self.assertEqual(s.rows["qoder:reasoning:a1"]["text"], "thinking")
        self.assertEqual(s.rows["qoder:assistant:a1"]["text"], "answer")
        s.replayed_rows.clear()
        s.event(update("user_message_chunk", messageId="u1", content={"type": "text", "text": "same"}))
        self.assertEqual(s.rows["qoder:user:u1"]["text"], "same")

    def test_confirmed_native_user_id_survives_reload_without_text_matching(self):
        s = session(); s.current_submission = "client-1"
        with patch.object(AcpSession, "call", return_value={"stopReason": "end_turn", "userMessageId": "native-u"}):
            s.call("session/prompt", {})
        s.current_submission = None; s.replaying_history = True
        s.event(update("user_message_chunk", messageId="native-u", content={"type": "text", "text": "hello"}))
        self.assertEqual(s.config["qoderUserMessages"], {"native-u": "client-1"})
        self.assertEqual(s.rows["client-1"]["text"], "hello")

    def test_foreign_session_cannot_settle_current_submission(self):
        s = session(); s.current_submission = "ours"
        e = update("user_message_chunk", messageId="foreign", content={"type": "text", "text": "foreign"})
        e["params"]["sessionId"] = "another"
        s.event(e)
        self.assertEqual(s.rows, {})
        self.assertNotIn("qoderUserMessages", s.config)
        self.assertNotIn("receipts", s.config)

    def test_live_user_identity_does_not_append_to_optimistic_text(self):
        s = session(); s.current_submission = "client-1"
        s.rows["client-1"] = {"id": "client-1", "role": "user", "text": "hello"}
        for text in ("hel", "lo"):
            s.event(update("user_message_chunk", messageId="native-u", content={"type": "text", "text": text}))
        self.assertEqual(s.rows["client-1"]["text"], "hello")
        self.assertEqual(s.config["qoderUserMessages"], {"native-u": "client-1"})

    def test_late_old_user_echo_never_rebinds_or_accepts_new_submission(self):
        s = session(); s.remember_user_message("native-u", "client-1")
        s.current_submission = "client-2"
        s.chunk_id, s.chunk_kind = "current-answer", "agent_message_chunk"
        s.rows["client-1"] = {"id": "client-1", "role": "user", "text": "old", "status": "completed"}
        s.rows["client-2"] = {"id": "client-2", "role": "user", "text": "new", "status": "pending"}
        before = copy.deepcopy(s.rows)
        with patch.object(s, "receipt") as receipt:
            s.event(update("user_message_chunk", messageId="native-u", content={"type": "text", "text": "old"}))
            receipt.assert_not_called()
        self.assertEqual(s.config["qoderUserMessages"], {"native-u": "client-1"})
        self.assertEqual(s.rows, before)
        self.assertEqual((s.chunk_id, s.chunk_kind), ("current-answer", "agent_message_chunk"))

    def test_native_result_cannot_rebind_existing_message_identity(self):
        s = session(); s.remember_user_message("native-u", "client-1")
        s.current_submission = "client-2"
        with patch.object(AcpSession, "call", return_value={"stopReason": "end_turn", "userMessageId": "native-u"}):
            self.assertRaises(Rejected, s.call, "session/prompt", {})
        self.assertEqual(s.config["qoderUserMessages"], {"native-u": "client-1"})

    def test_stop_waits_for_native_settlement_before_settings_and_next_prompt(self):
        s = session(); release = threading.Event(); completed = threading.Event()
        original_update = s.update
        def updated(**changes):
            original_update(**changes)
            if changes.get("running") is False: completed.set()
        s.update = updated
        def call(method, params, **kwargs):
            if method == "session/prompt":
                self.assertTrue(release.wait(2))
                return {"stopReason": "cancelled", "userMessageId": "native-" + params["prompt"][0]["text"]}
            options = copy.deepcopy(OPTIONS)
            options[0]["currentValue"] = params["value"]
            return {"configOptions": options}
        with patch.object(AcpSession, "call", side_effect=call), patch.object(s, "peer") as peer:
            s.prompt({"submissionId": "first", "text": "hello"})
            s.stop()
            self.assertEqual(peer.send.call_args.args[0]["method"], "session/cancel")
            self.assertTrue(s.data["running"])
            self.assertRaises(Rejected, s.configure, {"permission": "auto"})
            release.set(); self.assertTrue(completed.wait(2))
            s.configure({"permission": "auto"})
            self.assertEqual(s.data["permission"], "auto")
            completed.clear()
            s.prompt({"submissionId": "second", "text": "again"})
            self.assertTrue(completed.wait(2))
            self.assertEqual(s.config["receipts"]["second"], "accepted")

    def test_native_permission_choices_are_forwarded_without_autoallow(self):
        s = session()
        e = {"jsonrpc": "2.0", "id": "approve", "method": "session/request_permission", "params": {"sessionId": "native", "toolCall": {"toolCallId": "tool", "title": "Write", "rawInput": {"path": "file"}}, "options": [{"optionId": "proceed_once", "name": "Allow", "kind": "allow_once"}, {"optionId": "cancel", "name": "Reject", "kind": "reject_once"}]}}
        s.event(e)
        self.assertEqual([v["id"] for v in s.data["requests"][0]["choices"]], ["proceed_once", "cancel", "luma:cancel"])
        with patch.object(s, "peer") as peer:
            s.answer({"id": "approve", "choice": "cancel"})
            self.assertEqual(peer.send.call_args.args[0]["result"]["outcome"], {"outcome": "selected", "optionId": "cancel"})

    def test_failed_load_never_replaces_native_id(self):
        s = session(); methods = []
        s.start = lambda argv: None
        def call(method, params):
            methods.append(method)
            if method == "initialize": return {"protocolVersion": 1, "agentCapabilities": {"loadSession": True}}
            raise Rejected("not found")
        s.call = call
        self.assertRaises(Rejected, s.connect)
        self.assertEqual(methods, ["initialize", "session/load"])
        self.assertEqual(s.config["nativeId"], "native")
        self.assertFalse(s.data["historyReady"])

    def test_native_queue_advertisement_is_not_manageable_queue_or_steer(self):
        s = session(None); s.start = lambda argv: None
        s.call = lambda method, params: ({"protocolVersion": 1, "agentCapabilities": {"loadSession": True, "promptCapabilities": {"image": True}, "_meta": {"qoder": {"promptQueueing": True}}}} if method == "initialize" else {"sessionId": "new", "configOptions": copy.deepcopy(OPTIONS)})
        s.connect()
        self.assertTrue(s.data["historyReady"])
        self.assertTrue(s.data["capabilities"]["images"])
        self.assertFalse(s.data["capabilities"]["queue"])
        self.assertFalse(s.data["capabilities"]["steer"])
        self.assertFalse(s.data["capabilities"]["skills"])
        self.assertEqual(s.data["notice"], "")

    def test_destructive_commands_do_not_rewrite_managed_history(self):
        s = session()
        for cmd in ("/clear", "/reset", "/new", "/rewind abc"):
            self.assertRaises(Rejected, s.validate_prompt, {"text": cmd})
        s.validate_prompt({"text": "please explain /rewind"})

    def test_second_new_user_message_id_in_turn_does_not_wipe_first_user_row(self):
        s = session(); s.current_submission = "client-1"; s.config["receipts"] = {"client-1": "unknown"}
        s.rows["client-1"] = {"id": "client-1", "role": "user", "text": "hello"}
        s.event(update("user_message_chunk", messageId="native-u1", content={"type": "text", "text": "hello"}))
        s.event(update("agent_message_chunk", messageId="a1", content={"type": "text", "text": "reply"}))
        s.event(update("user_message_chunk", messageId="native-u2", content={"type": "text", "text": "second"}))
        self.assertEqual(s.rows["client-1"]["text"], "hello")
        self.assertEqual(s.rows["qoder:user:native-u2"]["text"], "second")
        self.assertEqual(s.config["qoderUserMessages"], {"native-u1": "client-1"})

if __name__ == "__main__": unittest.main()

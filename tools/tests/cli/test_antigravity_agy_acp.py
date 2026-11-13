#!/usr/bin/env python3
import sys
sys.dont_write_bytecode = True
import json
import os
import pathlib
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "apps/gui/app/src/main/assets/cli_bridge"))
from luma_cli.antigravity import AntigravitySession
from luma_cli.antigravity_agy_acp import AntigravityAgyAcpSession, AGY_ACP_PROTOCOL
from luma_cli.antigravity_cli import AntigravityCliSession, PROTOCOL as STREAM_JSON_PROTOCOL
from luma_cli.server import Manager, NATIVE_PROTOCOLS
from luma_cli.transport import Conflict, Rejected

AGY_ACP_OPTIONS = [
    {
        "id": "model",
        "name": "Model",
        "category": "model",
        "type": "select",
        "currentValue": "gemini-4-argon",
        "options": [
            {"value": "gemini-4-argon", "name": "Gemini 4 Argon"},
            {"value": "claude-opus-5-5", "name": "Claude Opus 5.5"},
        ],
    },
    {
        "id": "thought_level",
        "name": "Thinking Level",
        "category": "thought_level",
        "type": "select",
        "currentValue": "medium",
        "options": [
            {"value": "low", "name": "Low"},
            {"value": "medium", "name": "Medium"},
            {"value": "high", "name": "High"},
        ],
    },
    {
        "id": "mode",
        "name": "Mode",
        "category": "mode",
        "type": "select",
        "currentValue": "default",
        "options": [
            {"value": "default", "name": "Default", "description": "Ask before edits and commands."},
            {"value": "accept-edits", "name": "Accept edits", "description": "Approve file edits in the workspace without asking."},
            {"value": "plan", "name": "Plan", "description": "Plan carefully before executing a task."},
        ],
    },
]

FIXTURE_AGY = r'''#!/usr/bin/python3
import copy, json, os, pathlib, subprocess, sys, time

args = sys.argv[1:]
if args == ["--version"]:
    sys.stdout.write(os.environ.get("LUMA_FIXTURE_AGY_VERSION", "1.5.2") + "\n")
    sys.exit(0)
assert args[:2] == ["acp", "--stdio"], args
assert "--add-dir" in args, args
skip_permissions = "--dangerously-skip-permissions" in args

store = pathlib.Path(os.environ["LUMA_FIXTURE_STORE"])
store.mkdir(parents=True, exist_ok=True)
with (store / "invocations.jsonl").open("a", encoding="utf-8") as log_out:
    log_out.write(json.dumps({"args": args, "skip": skip_permissions}) + "\n")
default_options = json.loads(os.environ["LUMA_FIXTURE_OPTIONS"])
session_options = copy.deepcopy(default_options)

def send(frame):
    sys.stdout.write(json.dumps(frame, ensure_ascii=False) + "\n")
    sys.stdout.flush()

for raw in sys.stdin:
    frame = json.loads(raw)
    method = frame.get("method")
    ident = frame.get("id")
    params = frame.get("params", {})
    if method == "initialize":
        assert params.get("clientCapabilities", {}).get("elicitation") == {"form": {}}
        send({"jsonrpc": "2.0", "id": ident, "result": {
            "protocolVersion": 1,
            "agentInfo": {"name": "Antigravity ACP Server", "version": "1.5.2"},
            "agentCapabilities": {"loadSession": True, "promptCapabilities": {"image": True, "embeddedContext": True}},
            "authMethods": [{"id": "oauth-personal", "name": "Log in with Google"}],
        }})
    elif method == "authenticate":
        assert params == {"methodId": "oauth-personal"}
        auth_mode = os.environ.get("LUMA_FIXTURE_AUTH_MODE", "ok")
        if auth_mode == "xdg_login":
            ready = store / "auth-ready"
            if not ready.exists():
                subprocess.run(["xdg-open", "https://accounts.google.com/o/oauth2/v2/auth?redirect_uri=http%3A%2F%2F127.0.0.1%3A45678%2F&state=agy"], check=True)
                while not ready.exists():
                    time.sleep(0.01)
        elif auth_mode == "headless_fail":
            send({"jsonrpc": "2.0", "id": ident, "error": {"code": -32000, "message": "couldn't open a web browser: exec: xdg-open: not found"}})
            continue
        send({"jsonrpc": "2.0", "id": ident, "result": {"_meta": {"antigravity": {"tier": {"name": "Google AI Ultra"}}}}})
    elif method == "session/new":
        sid = "agy-acp-session-1"
        session_options = copy.deepcopy(default_options)
        (store / f"{sid}.json").write_text(json.dumps({"turns": []}))
        send({"jsonrpc": "2.0", "id": ident, "result": {"sessionId": sid, "configOptions": session_options}})
    elif method == "session/load":
        sid = params["sessionId"]
        state = json.loads((store / f"{sid}.json").read_text())
        # Real agy acp resets configOptions to startup defaults on a fresh process session/load.
        session_options = copy.deepcopy(default_options)
        for turn in state["turns"]:
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "user_message_chunk", "content": {"type": "text", "text": turn["user"]}},
            }})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "agent_thought_chunk", "content": {"type": "text", "text": turn["thought"]}},
            }})
            for tc in turn.get("tool_calls", []):
                # Real agy acp session/load omits rawOutput/content on replayed tool_call frames.
                send({"jsonrpc": "2.0", "method": "session/update", "params": {
                    "sessionId": sid,
                    "update": {
                        "sessionUpdate": "tool_call",
                        "toolCallId": tc["id"],
                        "name": tc["name"],
                        "args": tc["args"],
                        "rawInput": tc["args"],
                        "status": "completed",
                        "kind": "other",
                        "title": "Run " + tc["name"],
                    },
                }})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": turn["reply"]}},
            }})
        send({"jsonrpc": "2.0", "id": ident, "result": {"sessionId": sid, "configOptions": session_options}})
    elif method == "session/set_config_option":
        if params["configId"] == "mode" and params["value"] == "always-proceed":
            send({"jsonrpc": "2.0", "id": ident, "error": {"code": -32602, "message": "Unknown mode: always-proceed"}})
            continue
        for opt in session_options:
            if opt["id"] == params["configId"]:
                opt["currentValue"] = params["value"]
        send({"jsonrpc": "2.0", "id": ident, "result": {"configOptions": session_options}})
    elif method == "session/cancel":
        with (store / "cancels.jsonl").open("a", encoding="utf-8") as cancel_out:
            cancel_out.write(json.dumps(params) + "\n")
    elif method == "session/prompt":
        sid = params["sessionId"]
        prompt_text = params["prompt"][0]["text"]
        if prompt_text == "bg_wakeup":
            gemini = pathlib.Path(os.environ["GEMINI_HOME"]) / "antigravity-cli" / "brain"
            parent_log = gemini / sid / ".system_generated" / "logs" / "transcript.jsonl"
            parent_full = gemini / sid / ".system_generated" / "logs" / "transcript_full.jsonl"
            parent_log.parent.mkdir(parents=True, exist_ok=True)
            tid = f"{sid}/bg-1"
            cmd_args = {"CommandLine": "pytest -q", "WaitMsBeforeAsync": 500, "toolSummary": "Run unit suite"}
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "agent_thought_chunk", "content": {"type": "text", "text": "launching bg test"}},
            }})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "tool_call", "toolCallId": "call_bg_1", "name": "run_command", "args": cmd_args, "rawInput": cmd_args, "status": "in_progress", "kind": "execute", "title": "Run unit suite"},
            }})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "Waiting for background test..."}},
            }})
            steps = [
                {"step_index": 0, "type": "USER_INPUT", "status": "DONE", "content": prompt_text},
                {"step_index": 1, "type": "PLANNER_RESPONSE", "status": "DONE", "thinking": "launching bg test", "tool_calls": [
                    {"name": "run_command", "args": {k: json.dumps(v) for k, v in cmd_args.items()}},
                ]},
                {"step_index": 2, "type": "GENERIC", "status": "RUNNING", "content": f"Tool is running as a background task with task id: {tid}"},
                {"step_index": 3, "type": "PLANNER_RESPONSE", "status": "DONE", "content": "Waiting for background test..."},
            ]
            parent_log.write_text("".join(json.dumps(s) + "\n" for s in steps), encoding="utf-8")
            parent_full.write_text("".join(json.dumps(s) + "\n" for s in steps), encoding="utf-8")
            send({"jsonrpc": "2.0", "id": ident, "result": {"stopReason": "end_turn"}})
        elif prompt_text == "after_wakeup":
            # Real agy acp flushes the previous turn's settled background tool_call_update AND unseen autonomous steps
            # as catch-up session/update frames at the start of the next session/prompt!
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {
                    "sessionUpdate": "tool_call_update",
                    "toolCallId": "call_bg_1",
                    "status": "completed",
                    "content": [{"type": "content", "content": {"type": "text", "text": f"Tool is running as a background task with task id: {sid}/bg-1"}}],
                },
            }})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "agent_thought_chunk", "content": {"type": "text", "text": "Inspecting full test report"}},
            }})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "tool_call", "toolCallId": "call_auto_read", "name": "read_file", "rawInput": {"Path": "/tmp/report.txt"}, "status": "in_progress", "kind": "read", "title": "/tmp/report.txt"},
            }})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "tool_call_update", "toolCallId": "call_auto_read", "status": "completed", "content": [{"type": "content", "content": {"type": "text", "text": "12 passed"}}]},
            }})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "\n⚠ API error (attempt 1): request failed: EOF\n"}},
            }})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "All 12 unit tests passed cleanly!"}},
            }})
            # Now stream the new prompt's own updates
            gemini = pathlib.Path(os.environ["GEMINI_HOME"]) / "antigravity-cli" / "brain"
            parent_log = gemini / sid / ".system_generated" / "logs" / "transcript.jsonl"
            with parent_log.open("a", encoding="utf-8") as f:
                f.write(json.dumps({"step_index": 8, "type": "USER_INPUT", "status": "DONE", "content": prompt_text}) + "\n")
                f.write(json.dumps({"step_index": 9, "type": "PLANNER_RESPONSE", "status": "DONE", "thinking": "thinking:after_wakeup", "content": "reply:after_wakeup"}) + "\n")
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "agent_thought_chunk", "content": {"type": "text", "text": "thinking:after_wakeup"}},
            }})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "reply:after_wakeup"}},
            }})
            send({"jsonrpc": "2.0", "id": ident, "result": {"stopReason": "end_turn"}})
        elif prompt_text == "spawn_subagents":
            gemini = pathlib.Path(os.environ["GEMINI_HOME"]) / "antigravity-cli" / "brain"
            parent_log = gemini / sid / ".system_generated" / "logs" / "transcript.jsonl"
            parent_log.parent.mkdir(parents=True, exist_ok=True)
            child1 = "11111111-2222-3333-4444-555555555551"
            child2 = "11111111-2222-3333-4444-555555555552"
            c1_log = gemini / child1 / ".system_generated" / "logs" / "transcript.jsonl"
            c2_log = gemini / child2 / ".system_generated" / "logs" / "transcript.jsonl"
            c1_log.parent.mkdir(parents=True, exist_ok=True)
            c2_log.parent.mkdir(parents=True, exist_ok=True)
            args1 = {"TypeName": "research", "Role": "Agent 1 (OS)", "Prompt": "Read /etc/os-release"}
            args2 = {"TypeName": "self", "Role": "Agent 2 (Kernel)", "Prompt": "Run uname", "Model": "flash"}
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "tool_call", "toolCallId": "call_sub_1", "name": "invoke_subagent", "args": args1, "rawInput": args1, "status": "completed", "kind": "other", "title": "Run invoke_subagent"},
            }})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "tool_call", "toolCallId": "call_sub_2", "name": "invoke_subagent", "args": args2, "rawInput": args2, "status": "completed", "kind": "other", "title": "Run invoke_subagent"},
            }})
            parent_steps = [
                {"step_index": 1, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                    {"name": "invoke_subagent", "args": {k: json.dumps(v) for k, v in args1.items()}},
                    {"name": "invoke_subagent", "args": {k: json.dumps(v) for k, v in args2.items()}},
                ]},
                {"step_index": 2, "type": "GENERIC", "status": "DONE", "content": "Created the following subagent:\n" + json.dumps({"conversationId": child1, "logAbsoluteUri": f"file://{c1_log}"})},
                {"step_index": 3, "type": "GENERIC", "status": "DONE", "content": "Created the following subagent:\n" + json.dumps({"conversationId": child2, "logAbsoluteUri": f"file://{c2_log}"})},
            ]
            parent_log.write_text("".join(json.dumps(s) + "\n" for s in parent_steps), encoding="utf-8")
            c1_log.write_text("".join(json.dumps(s) + "\n" for s in [
                {"step_index": 0, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": "The following is a <SYSTEM_MESSAGE> not actually sent by the user.\n\n<SYSTEM_MESSAGE>\n[Message] timestamp=2026-11-06T00:00:00Z sender=" + sid + " priority=MESSAGE_PRIORITY_HIGH content=Read /etc/os-release\n</SYSTEM_MESSAGE>"},
                {"step_index": 1, "type": "PLANNER_RESPONSE", "status": "DONE", "thinking": "Reading os-release", "tool_calls": [{"name": "read_file", "args": {"Path": '"/etc/os-release"'}}]},
            ]), encoding="utf-8")
            c2_log.write_text("".join(json.dumps(s) + "\n" for s in [
                {"step_index": 0, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": "The following is a <SYSTEM_MESSAGE> not actually sent by the user.\n\n<SYSTEM_MESSAGE>\n[Message] timestamp=2026-11-06T00:00:00Z sender=" + sid + " priority=MESSAGE_PRIORITY_HIGH content=Run uname\n</SYSTEM_MESSAGE>"},
                {"step_index": 1, "type": "PLANNER_RESPONSE", "status": "DONE", "content": "Linux arm64"},
            ]), encoding="utf-8")
            gate = store / "release-subagents"
            while not gate.exists():
                time.sleep(0.02)
            with c1_log.open("a", encoding="utf-8") as f:
                f.write(json.dumps({"step_index": 2, "type": "GENERIC", "status": "DONE", "content": "PRETTY_NAME=Ubuntu"}) + "\n")
                f.write(json.dumps({"step_index": 3, "type": "PLANNER_RESPONSE", "status": "DONE", "content": "Sent OS summary to parent."}) + "\n")
            with parent_log.open("a", encoding="utf-8") as f:
                f.write(json.dumps({"step_index": 4, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": "The following is a <SYSTEM_MESSAGE> not actually sent by the user.\n\n<SYSTEM_MESSAGE>\n[Message] timestamp=2026-11-06T00:00:05Z sender=" + child1 + " priority=MESSAGE_PRIORITY_HIGH content=Ubuntu 26.04.1 LTS\n</SYSTEM_MESSAGE>"}) + "\n")
            state = json.loads((store / f"{sid}.json").read_text())
            state["turns"].append({
                "user": prompt_text,
                "thought": "spawning",
                "tool_calls": [
                    {"id": "call_sub_1", "name": "invoke_subagent", "args": args1},
                    {"id": "call_sub_2", "name": "invoke_subagent", "args": args2},
                ],
                "reply": "both subagents done",
            })
            (store / f"{sid}.json").write_text(json.dumps(state))
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "both subagents done"}},
            }})
            send({"jsonrpc": "2.0", "id": ident, "result": {"stopReason": "end_turn"}})
        elif prompt_text == "run_cmd":
            if not skip_permissions:
                send({"jsonrpc": "2.0", "id": 901, "method": "session/request_permission", "params": {
                    "sessionId": sid,
                    "toolCall": {"toolCallId": "call_cmd_1", "title": "echo ok", "rawInput": {"CommandLine": "echo ok"}},
                    "options": [{"optionId": "allow_once", "name": "Allow once", "kind": "allow_once"}],
                }})
                perm_reply = json.loads(sys.stdin.readline())
                assert perm_reply["id"] == 901
                approved = perm_reply["result"]["outcome"].get("optionId") == "allow_once"
            else:
                approved = True
            reply_text = "CMD=" + ("AUTO" if skip_permissions else ("APPROVED" if approved else "CANCELLED"))
            state = json.loads((store / f"{sid}.json").read_text())
            state["turns"].append({"user": prompt_text, "thought": "thinking:cmd", "reply": reply_text})
            (store / f"{sid}.json").write_text(json.dumps(state))
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": reply_text}},
            }})
            send({"jsonrpc": "2.0", "id": ident, "result": {"stopReason": "end_turn"}})
        elif prompt_text == "ask":
            send({"jsonrpc": "2.0", "id": 900, "method": "elicitation/create", "params": {
                "sessionId": sid,
                "toolCallId": "call_ask_1",
                "mode": "form",
                "message": "The agent has 2 questions.",
                "requestedSchema": {
                    "type": "object",
                    "properties": {
                        "q1": {"type": "string", "title": "Color?", "oneOf": [{"const": "1", "title": "Red"}, {"const": "2", "title": "Blue"}]},
                        "q1_other": {"type": "string", "title": "Other", "description": "Optional free-form answer to: Color?"},
                        "q2": {"type": "array", "title": "Fruits?", "items": {"anyOf": [{"const": "1", "title": "Apple"}, {"const": "2", "title": "Pear"}, {"const": "3", "title": "Plum"}]}},
                        "q2_other": {"type": "string", "title": "Other", "description": "Optional free-form answer to: Fruits?"},
                    },
                },
            }})
            reply_frame = json.loads(sys.stdin.readline())
            assert reply_frame["id"] == 900
            answer_summary = json.dumps(reply_frame["result"], sort_keys=True, ensure_ascii=False)
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "tool_call", "toolCallId": "call_ask_1", "name": "ask_question", "title": "Asking 2 questions", "kind": "other", "status": "completed"},
            }})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "FORM=" + answer_summary}},
            }})
            send({"jsonrpc": "2.0", "id": ident, "result": {
                "stopReason": "end_turn",
                "usage": {"inputTokens": 1200, "outputTokens": 80, "thoughtTokens": 40, "cachedReadTokens": 300, "totalTokens": 1320},
            }})
        else:
            state = json.loads((store / f"{sid}.json").read_text())
            turn = {"user": prompt_text, "thought": "thinking:" + prompt_text, "reply": "reply:" + prompt_text}
            state["turns"].append(turn)
            (store / f"{sid}.json").write_text(json.dumps(state))
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "agent_thought_chunk", "content": {"type": "text", "text": turn["thought"]}},
            }})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": turn["reply"]}},
            }})
            send({"jsonrpc": "2.0", "id": ident, "result": {
                "stopReason": "end_turn",
                "usage": {"inputTokens": 500, "outputTokens": 50, "totalTokens": 550},
            }})
'''

class AntigravityAgyAcpTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="luma-agy-acp-")
        self.root = pathlib.Path(self.tmp.name)
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()
        self.agy = self.bin_dir / "agy"
        self.agy.write_text(FIXTURE_AGY, encoding="utf-8")
        self.agy.chmod(0o700)
        self.store = self.root / "native-store"
        self.sessions = []

    def tearDown(self):
        for s in self.sessions:
            if hasattr(s, "_close_current_peer"):
                s._close_current_peer()
            elif getattr(s, "peer", None):
                s.peer.close()
        self.tmp.cleanup()

    def env(self, **extra):
        return {
            "PATH": f"{self.bin_dir}:/usr/bin:/bin",
            "HOME": str(self.root),
            "GEMINI_HOME": str(self.root / ".gemini"),
            "LUMA_FIXTURE_STORE": str(self.store),
            "LUMA_FIXTURE_OPTIONS": json.dumps(AGY_ACP_OPTIONS),
            **extra,
        }

    def wait(self, predicate, timeout=3.0):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.01)
        self.fail("Condition did not settle in time")

    def test_protocol_catalog_and_default_routing_preserve_legacy_protocols(self):
        self.assertEqual(NATIVE_PROTOCOLS["Antigravity"], [AGY_ACP_PROTOCOL, STREAM_JSON_PROTOCOL, "antigravity-acp"])
        state_dir = self.root / "state"
        state_dir.mkdir()
        (state_dir / "legacy.json").write_text(json.dumps({"cli": "Antigravity", "cwd": str(self.root), "nativeId": "old-acp"}))
        (state_dir / "stream.json").write_text(json.dumps({"cli": "Antigravity", "cwd": str(self.root), "nativeId": "old-stream", "nativeProtocol": STREAM_JSON_PROTOCOL}))
        with patch.dict(os.environ, self.env()), \
             patch.object(AntigravitySession, "connect", lambda s: s.update(connected=True)), \
             patch.object(AntigravityCliSession, "connect", lambda s: s.update(connected=True)):
            manager = Manager(state_dir)
            fresh = manager.connect({"id": "fresh", "cli": "Antigravity", "cwd": str(self.root)})
            self.sessions.append(fresh)
            self.assertIs(type(fresh), AntigravityAgyAcpSession)
            self.assertEqual(fresh.snapshot()["nativeProtocol"], AGY_ACP_PROTOCOL)
            self.assertTrue(fresh.data["connected"])

            legacy = manager.connect({"id": "legacy", "cli": "Antigravity", "cwd": str(self.root)})
            self.assertIs(type(legacy), AntigravitySession)
            self.assertEqual(legacy.config["nativeProtocol"], "antigravity-acp")

            stream = manager.connect({"id": "stream", "cli": "Antigravity", "cwd": str(self.root)})
            self.assertIs(type(stream), AntigravityCliSession)
            self.assertEqual(stream.config["nativeProtocol"], STREAM_JSON_PROTOCOL)

    def test_version_gate_rejects_pre_1_5_agy_cleanly(self):
        with patch.dict(os.environ, self.env(LUMA_FIXTURE_AGY_VERSION="1.4.5")):
            manager = Manager(self.root / "state")
            with self.assertRaisesRegex(Rejected, "1.5.0"):
                manager.connect({"id": "old-ver", "cli": "Antigravity", "cwd": str(self.root)})

    def test_config_options_models_efforts_and_modes_roundtrip(self):
        with patch.dict(os.environ, self.env()):
            manager = Manager(self.root / "state")
            s = manager.connect({"id": "cfg", "cli": "Antigravity", "cwd": str(self.root), "model": "claude-opus-5-5"})
            self.sessions.append(s)
            self.assertEqual(s.data["model"], "claude-opus-5-5")
            self.assertEqual(s.data["effort"], "medium")
            self.assertEqual(s.data["permission"], "default")
            self.assertEqual([p["id"] for p in s.data["permissions"]], ["default", "accept-edits", "plan", "always-proceed"])
            s.configure({"effort": "high", "permission": "accept-edits"})
            self.assertEqual(s.data["effort"], "high")
            self.assertEqual(s.data["permission"], "accept-edits")
            self.assertTrue(s.data["capabilities"]["images"])

    def test_full_permissions_relaunch_persistence_and_elicitation(self):
        with patch.dict(os.environ, self.env()):
            manager = Manager(self.root / "state-perm")
            s = manager.connect({"id": "perm", "cli": "Antigravity", "cwd": str(self.root), "model": "claude-opus-5-5"})
            self.sessions.append(s)
            s.configure({"effort": "high"})
            s.submit({"submissionId": "turn-0", "text": "before-full"})
            self.wait(lambda: not s.data["running"])
            native_id = s.data["nativeId"]

            s.configure({"permission": "always-proceed"})
            self.assertTrue(s.data["connected"])
            self.assertIsNone(s.data["error"])
            self.assertEqual(s.data["permission"], "always-proceed")
            self.assertEqual(s.data["model"], "claude-opus-5-5")
            self.assertEqual(s.data["effort"], "high")
            s.configure({"effort": "low"})
            self.assertEqual(s.data["permission"], "always-proceed")
            self.assertEqual(s.data["effort"], "low")
            self.assertEqual(
                [(r["role"], r["text"]) for r in s.rows.values()],
                [("user", "before-full"), ("reasoning", "thinking:before-full"), ("assistant", "reply:before-full")],
            )

            s.submit({"submissionId": "turn-cmd", "text": "run_cmd"})
            self.wait(lambda: not s.data["running"])
            self.assertEqual(s.data["requests"], [])
            assistant_texts = [r["text"] for r in s.rows.values() if r["role"] == "assistant"]
            self.assertEqual(assistant_texts[-1], "CMD=AUTO")

            s.submit({"submissionId": "turn-ask", "text": "ask"})
            self.wait(lambda: bool(s.data["requests"]))
            req = s.data["requests"][0]
            self.assertEqual(req["title"], "Answer")
            s.answer({"id": req["id"], "choice": "accept", "answers": {"q1": ["Blue"], "q2": ["Pear"]}})
            self.wait(lambda: not s.data["running"])

            manager.release("perm", {"nativeId": native_id, "nativeProtocol": AGY_ACP_PROTOCOL})
            restored = manager.connect({
                "id": "perm",
                "cli": "Antigravity",
                "cwd": str(self.root),
                "nativeId": native_id,
                "nativeProtocol": AGY_ACP_PROTOCOL,
            })
            self.sessions.append(restored)
            self.assertEqual(restored.data["permission"], "always-proceed")
            self.assertEqual(restored.data["model"], "claude-opus-5-5")
            self.assertEqual(restored.data["effort"], "low")

            restored.configure({"permission": "accept-edits"})
            self.assertTrue(restored.data["connected"])
            self.assertIsNone(restored.data["error"])
            self.assertEqual(restored.data["permission"], "accept-edits")
            self.assertEqual(restored.data["model"], "claude-opus-5-5")
            self.assertEqual(restored.data["effort"], "low")

            restored.submit({"submissionId": "turn-cmd-2", "text": "run_cmd"})
            self.wait(lambda: bool(restored.data["requests"]))
            perm_req = restored.data["requests"][0]
            self.assertEqual(perm_req["title"], "echo ok")
            restored.answer({"id": perm_req["id"], "choice": "allow_once", "answers": {}})
            self.wait(lambda: not restored.data["running"])
            assistant_texts = [r["text"] for r in restored.rows.values() if r["role"] == "assistant"]
            self.assertEqual(assistant_texts[-1], "CMD=APPROVED")

            invocations = [json.loads(line)["skip"] for line in (self.store / "invocations.jsonl").read_text().splitlines()]
            self.assertEqual(invocations, [False, True, True, False])

    def test_elicitation_folds_companion_other_and_maps_titles_back_to_const(self):
        with patch.dict(os.environ, self.env()):
            manager = Manager(self.root / "state")
            s = manager.connect({"id": "ask", "cli": "Antigravity", "cwd": str(self.root)})
            self.sessions.append(s)
            s.submit({"submissionId": "sub-ask", "text": "ask"})
            self.wait(lambda: bool(s.data["requests"]))
            req = s.data["requests"][0]
            self.assertEqual(req["title"], "Answer")
            self.assertEqual((req["submitChoice"], req["skipChoice"]), ("accept", "decline"))
            self.assertEqual([q["id"] for q in req["questions"]], ["q1", "q2"])
            self.assertEqual(req["questions"][0]["options"], ["Red", "Blue"])
            self.assertTrue(req["questions"][0]["allowOther"])
            self.assertFalse(req["questions"][0]["multiple"])
            self.assertEqual(req["questions"][1]["options"], ["Apple", "Pear", "Plum"])
            self.assertTrue(req["questions"][1]["allowOther"])
            self.assertTrue(req["questions"][1]["multiple"])

            s.answer({
                "id": req["id"],
                "choice": "accept",
                "answers": {
                    "q1": ["CustomPurple"],
                    "q2": ["Apple", "Plum", "Mango"],
                },
            })
            self.wait(lambda: not s.data["running"])
            tool_row = s.rows["tool-call_ask_1"]
            self.assertEqual(tool_row["toolName"], "ask_question")
            assistant_rows = [r for r in s.rows.values() if r["role"] == "assistant"]
            payload = json.loads(assistant_rows[-1]["text"].removeprefix("FORM="))
            self.assertEqual(payload, {
                "action": "accept",
                "content": {
                    "q1_other": "CustomPurple",
                    "q2": ["1", "3"],
                    "q2_other": "Mango",
                },
            })
            self.assertIn({"label": "上下文", "value": "1500 / 1048576 · 0.1%"}, s.data["metrics"])
            self.assertIn({"label": "本回合 · 输入缓存命中", "value": "300 / 1500 · 20.0%"}, s.data["metrics"])
            self.assertIn({"label": "CLI 累计估算 · USD", "value": "0.00399"}, s.data["metrics"])

    def test_session_load_replays_multi_turn_history_and_settles_chunks(self):
        with patch.dict(os.environ, self.env()):
            manager = Manager(self.root / "state")
            s = manager.connect({"id": "hist", "cli": "Antigravity", "cwd": str(self.root)})
            self.sessions.append(s)
            for idx in (1, 2):
                s.submit({"submissionId": f"turn-{idx}", "text": f"hello-{idx}"})
                self.wait(lambda: not s.data["running"])
            native_id = s.data["nativeId"]
            self.assertEqual(s.release_mode(), "process")
            manager.release("hist", {"nativeId": native_id, "nativeProtocol": AGY_ACP_PROTOCOL})

            restored = manager.connect({
                "id": "hist",
                "cli": "Antigravity",
                "cwd": str(self.root),
                "nativeId": native_id,
                "nativeProtocol": AGY_ACP_PROTOCOL,
            })
            self.sessions.append(restored)
            rows = list(restored.rows.values())
            self.assertEqual(
                [(r["role"], r["text"], r["status"]) for r in rows],
                [
                    ("user", "hello-1", "completed"),
                    ("reasoning", "thinking:hello-1", "completed"),
                    ("assistant", "reply:hello-1", "completed"),
                    ("user", "hello-2", "completed"),
                    ("reasoning", "thinking:hello-2", "completed"),
                    ("assistant", "reply:hello-2", "completed"),
                ],
            )
            self.assertTrue(restored.data["historyReady"])

    def test_subagent_discovery_live_tailing_replay_and_kill(self):
        with patch.dict(os.environ, self.env()):
            manager = Manager(self.root / "state-sub")
            s = manager.connect({"id": "sub", "cli": "Antigravity", "cwd": str(self.root)})
            self.sessions.append(s)
            s.submit({"submissionId": "turn-sub", "text": "spawn_subagents"})

            c1 = "11111111-2222-3333-4444-555555555551"
            c2 = "11111111-2222-3333-4444-555555555552"
            self.wait(lambda: c1 in s.subagents and c2 in s.subagents and bool(s.subagents[c1].get("messages")))
            self.assertEqual(s.subagents[c1]["name"], "Agent 1 (OS)")
            self.assertEqual(s.subagents[c1]["role"], "research")
            self.assertEqual(s.subagents[c1]["model"], "inherit")
            self.assertEqual(s.subagents[c1]["itemId"], "tool-call_sub_1")
            self.assertEqual(s.subagents[c1]["status"], "running")
            self.assertEqual(s.subagents[c1]["messages"][0]["text"], "Read /etc/os-release")
            self.assertEqual(s.subagents[c2]["name"], "Agent 2 (Kernel)")
            self.assertEqual(s.subagents[c2]["role"], "self")
            self.assertEqual(s.subagents[c2]["model"], "flash")
            self.assertEqual(s.subagents[c2]["itemId"], "tool-call_sub_2")
            self.assertEqual(s.subagents[c2]["status"], "succeeded")

            (self.store / "release-subagents").touch()
            self.wait(lambda: not s.data["running"] and s.subagents[c1]["status"] == "succeeded" and s.subagents[c1]["detail"] == "Ubuntu 26.04.1 LTS")
            native_id = s.data["nativeId"]

            manager.release("sub", {"nativeId": native_id, "nativeProtocol": AGY_ACP_PROTOCOL})
            restored = manager.connect({
                "id": "sub",
                "cli": "Antigravity",
                "cwd": str(self.root),
                "nativeId": native_id,
                "nativeProtocol": AGY_ACP_PROTOCOL,
            })
            self.sessions.append(restored)
            snap = restored.snapshot()
            by_id = {a["id"]: a for a in snap["subAgents"]}
            self.assertEqual(by_id[c1]["itemId"], "tool-call_sub_1")
            self.assertEqual(by_id[c1]["status"], "succeeded")
            self.assertEqual(by_id[c1]["detail"], "Ubuntu 26.04.1 LTS")
            self.assertEqual(by_id[c2]["itemId"], "tool-call_sub_2")
            self.assertEqual(by_id[c2]["model"], "flash")

            restored.note_manage({"Action": "kill", "ConversationIds": [c2]})
            self.assertEqual(restored.subagents[c2]["status"], "cancelled")

    def test_xdg_open_auth_relay_and_headless_login_guidance(self):
        with patch.dict(os.environ, self.env(LUMA_FIXTURE_AUTH_MODE="xdg_login")):
            manager = Manager(self.root / "state-auth")
            s = manager.connect({"id": "oauth", "cli": "Antigravity", "cwd": str(self.root)}, background=True)
            self.sessions.append(s)
            self.wait(lambda: bool(s.data["requests"]))
            self.assertTrue(s.data["requests"][0]["externalUrl"].startswith("https://accounts.google.com/"))
            (self.store / "auth-ready").touch()
            self.wait(lambda: s.data["connected"])
            self.assertEqual(s.data["requests"], [])

        with patch.dict(os.environ, self.env(LUMA_FIXTURE_AUTH_MODE="headless_fail")):
            manager2 = Manager(self.root / "state-fail")
            with self.assertRaisesRegex(Rejected, "agy acp --login"):
                manager2.connect({"id": "fail", "cli": "Antigravity", "cwd": str(self.root)})

    def test_background_task_blocks_idle_release_and_projects_reactive_wakeup_without_duplicates(self):
        with patch.dict(os.environ, self.env()):
            manager = Manager(self.root / "state-bg")
            s = manager.connect({"id": "bg", "cli": "Antigravity", "cwd": str(self.root)})
            self.sessions.append(s)
            s.submit({"submissionId": "turn-bg", "text": "bg_wakeup"})
            self.wait(lambda: not s.data["running"])
            native_id = s.data["nativeId"]
            tid = f"{native_id}/bg-1"

            snap1 = s.snapshot()
            self.assertFalse(snap1["running"])
            self.assertFalse(snap1["releasable"])
            with self.assertRaisesRegex(Conflict, "后台任务尚未确认结束"):
                manager.release("bg", {"nativeId": native_id, "nativeProtocol": AGY_ACP_PROTOCOL})

            parent_dir = self.root / ".gemini" / "antigravity-cli" / "brain" / native_id / ".system_generated" / "logs"
            parent_log = parent_dir / "transcript.jsonl"
            parent_full = parent_dir / "transcript_full.jsonl"
            sys_msg = (
                "The following is a <SYSTEM_MESSAGE> not actually sent by the user.\n\n"
                f'<SYSTEM_MESSAGE>\n[Message] timestamp=2026-11-07T00:00:00Z sender={tid} '
                f'priority=MESSAGE_PRIORITY_HIGH content=Task id "{tid}" finished with result:\n'
                "Exit code: 0\n</SYSTEM_MESSAGE>"
            )
            step4 = {"step_index": 4, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": sys_msg}
            step5_compact = {
                "step_index": 5,
                "type": "PLANNER_RESPONSE",
                "status": "DONE",
                "thinking": "Inspecting...",
                "truncated_fields": ["thinking"],
                "tool_calls": [{"name": "read_file", "args": {"Path": '"/tmp/report.txt"'}}],
            }
            step5_full = {
                "step_index": 5,
                "type": "PLANNER_RESPONSE",
                "status": "DONE",
                "thinking": "Inspecting full test report",
                "tool_calls": [{"name": "read_file", "args": {"Path": '"/tmp/report.txt"'}}],
            }
            with parent_full.open("a", encoding="utf-8") as f:
                f.write(json.dumps(step4) + "\n")
                f.write(json.dumps(step5_full) + "\n")
            with parent_log.open("a", encoding="utf-8") as f:
                f.write(json.dumps(step4) + "\n")
                f.write(json.dumps(step5_compact) + "\n")
            s.tail_wake.set()

            self.wait(lambda: s.data["running"] and "agy-auto:5:tool:0" in s.rows)
            self.assertFalse(s.snapshot()["releasable"])
            self.assertEqual(s.rows["agy-auto:5:think"]["text"], "Inspecting full test report")
            self.assertEqual(s.rows["agy-auto:5:tool:0"]["status"], "running")
            with self.assertRaises(Conflict):
                manager.release("bg", {"nativeId": native_id, "nativeProtocol": AGY_ACP_PROTOCOL})

            step6 = {"step_index": 6, "type": "GENERIC", "status": "DONE", "content": "12 passed"}
            step65 = {
                "step_index": 65,
                "type": "ERROR_MESSAGE",
                "status": "DONE",
                "error": "API error (attempt 1): request failed: EOF",
            }
            step7_compact = {
                "step_index": 7,
                "type": "PLANNER_RESPONSE",
                "status": "DONE",
                "content": "All 12...",
                "truncated_fields": ["content"],
            }
            step7_full = {
                "step_index": 7,
                "type": "PLANNER_RESPONSE",
                "status": "DONE",
                "content": "All 12 unit tests passed cleanly!",
            }
            with parent_full.open("a", encoding="utf-8") as f:
                f.write(json.dumps(step6) + "\n")
                f.write(json.dumps(step65) + "\n")
                f.write(json.dumps(step7_full) + "\n")
            with parent_log.open("a", encoding="utf-8") as f:
                f.write(json.dumps(step6) + "\n")
                f.write(json.dumps(step65) + "\n")
                f.write(json.dumps(step7_compact) + "\n")
            s.tail_wake.set()

            self.wait(lambda: not s.data["running"] and "agy-auto:7:text" in s.rows)
            snap2 = s.snapshot()
            self.assertTrue(snap2["releasable"])
            self.assertEqual(s.rows["agy-auto:5:tool:0"]["status"], "completed")
            self.assertEqual(s.rows["agy-auto:5:tool:0"]["outcome"], "12 passed")
            self.assertEqual(s.rows["agy-auto:65:error"]["text"], "\n⚠ API error (attempt 1): request failed: EOF\n")
            self.assertEqual(s.rows["agy-auto:7:text"]["text"], "All 12 unit tests passed cleanly!")
            self.assertEqual(s.rows["agy-auto:7:text"]["status"], "completed")

            with parent_log.open("a", encoding="utf-8") as f:
                f.write(json.dumps({"step_index": 75, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": sys_msg}) + "\n")
            s.submit({"submissionId": "turn-after", "text": "after_wakeup"})
            self.wait(lambda: not s.data["running"] and any(r["text"] == "reply:after_wakeup" for r in s.rows.values()))
            self.assertFalse(s.background_tasks)
            self.assertTrue(s.snapshot()["releasable"])
            ordered = [(r["role"], r.get("toolName") or "", r["text"]) for r in s.snapshot()["messages"]]
            self.assertEqual(
                ordered,
                [
                    ("user", "", "bg_wakeup"),
                    ("reasoning", "", "launching bg test"),
                    ("tool", "run_command", "Run unit suite"),
                    ("assistant", "", "Waiting for background test..."),
                    ("reasoning", "", "Inspecting full test report"),
                    ("tool", "read_file", "/tmp/report.txt"),
                    ("assistant", "", "\n⚠ API error (attempt 1): request failed: EOF\n"),
                    ("assistant", "", "All 12 unit tests passed cleanly!"),
                    ("user", "", "after_wakeup"),
                    ("reasoning", "", "thinking:after_wakeup"),
                    ("assistant", "", "reply:after_wakeup"),
                ],
            )

    def test_manage_task_kill_and_autonomous_stop_settle_cleanly(self):
        with patch.dict(os.environ, self.env()):
            manager = Manager(self.root / "state-stop")
            s = manager.connect({"id": "stop", "cli": "Antigravity", "cwd": str(self.root)})
            self.sessions.append(s)
            s.submit({"submissionId": "turn-bg2", "text": "bg_wakeup"})
            self.wait(lambda: not s.data["running"])
            native_id = s.data["nativeId"]
            tid = f"{native_id}/bg-1"
            self.assertFalse(s.snapshot()["releasable"])

            parent_dir = self.root / ".gemini" / "antigravity-cli" / "brain" / native_id / ".system_generated" / "logs"
            parent_log = parent_dir / "transcript.jsonl"

            sys_msg = (
                "The following is a <SYSTEM_MESSAGE> not actually sent by the user.\n\n"
                f'<SYSTEM_MESSAGE>\n[Message] timestamp=2026-11-07T00:00:00Z sender={tid} '
                f'priority=MESSAGE_PRIORITY_HIGH content=Task id "{tid}" killed.\n</SYSTEM_MESSAGE>'
            )
            with parent_log.open("a", encoding="utf-8") as f:
                f.write(json.dumps({"step_index": 4, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": sys_msg}) + "\n")
                f.write(json.dumps({
                    "step_index": 5,
                    "type": "PLANNER_RESPONSE",
                    "status": "DONE",
                    "tool_calls": [{"name": "run_command", "args": {"CommandLine": '"sleep 60"', "toolSummary": '"Long check"'}}],
                }) + "\n")
            s.tail_wake.set()
            self.wait(lambda: s.data["running"] and "agy-auto:5:tool:0" in s.rows)
            self.assertFalse(s.snapshot()["releasable"])

            s.stop()
            self.assertFalse(s.data["running"])
            self.assertEqual(s.rows["agy-auto:5:tool:0"]["status"], "cancelled")
            self.assertTrue(s.snapshot()["releasable"])
            self.wait(lambda: (self.store / "cancels.jsonl").exists())

            s.background_tasks.add("manual-task-9")
            self.assertFalse(s.snapshot()["releasable"])
            with self.assertRaisesRegex(Conflict, "后台任务尚未确认结束"):
                manager.release("stop", {"nativeId": native_id, "nativeProtocol": AGY_ACP_PROTOCOL})
            s._note_manage_task({"Action": "kill", "TaskId": "manual-task-9"}, "DONE", "Killed task manual-task-9")
            self.assertTrue(s.snapshot()["releasable"])

    def test_oneshot_timer_and_canceled_task_settle_from_observed_native_messages(self):
        with patch.dict(os.environ, self.env()):
            manager = Manager(self.root / "state-timer")
            s = manager.connect({"id": "timer", "cli": "Antigravity", "cwd": str(self.root)})
            self.sessions.append(s)
            s.submit({"submissionId": "turn-timer", "text": "bg_wakeup"})
            self.wait(lambda: not s.data["running"])
            native_id = s.data["nativeId"]
            bg = f"{native_id}/bg-1"
            timer = f"{native_id}/task-164"
            parent_log = self.root / ".gemini" / "antigravity-cli" / "brain" / native_id / ".system_generated" / "logs" / "transcript.jsonl"

            def sysmsg(sender, content, priority="HIGH"):
                return ("\n\n<SYSTEM_MESSAGE>\n[Message] timestamp=2026-11-06T18:01:42Z "
                        f"sender={sender} priority=MESSAGE_PRIORITY_{priority} content={content}\n</SYSTEM_MESSAGE>")

            steps = [
                {"step_index": 4, "type": "USER_INPUT", "status": "DONE", "content": "wait"},
                {"step_index": 5, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                    {"name": "schedule", "args": {"DurationSeconds": "20", "Prompt": '"probe: timer fired"'}}]},
                {"step_index": 6, "type": "GENERIC", "status": "RUNNING", "content":
                    f"Created At: x\nTool is running as a background task with task id: {timer}\n"
                    "Task Description: Timer: 20s, Prompt: probe: timer fired\nTask logs are available at: file:///x"},
                {"step_index": 7, "type": "PLANNER_RESPONSE", "status": "DONE", "content": "waiting"},
            ]
            with parent_log.open("a", encoding="utf-8") as f:
                f.write("".join(json.dumps(x) + "\n" for x in steps))
            s.tail_wake.set()
            self.wait(lambda: timer in s.background_tasks and bg in s.background_tasks)
            self.assertIn(timer, s.oneshot_timers)

            with parent_log.open("a", encoding="utf-8") as f:
                f.write(json.dumps({"step_index": 8, "type": "SYSTEM_MESSAGE", "status": "DONE",
                                    "content": sysmsg(timer, "probe: timer fired")}) + "\n")
                f.write(json.dumps({"step_index": 9, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": sysmsg(
                    bg, f'Task id "{bg}" was canceled with result:\nTool execution was canceled', "LOW")}) + "\n")
                f.write(json.dumps({"step_index": 10, "type": "PLANNER_RESPONSE", "status": "DONE", "content": "done"}) + "\n")
            s.tail_wake.set()
            self.wait(lambda: not s.background_tasks and not s.data["running"])
            self.assertFalse(s.oneshot_timers)
            self.assertTrue(s.snapshot()["releasable"])
            bg_rows = [r for r in s.rows.values() if r.get("toolName") == "run_command"]
            self.assertTrue(bg_rows)
            self.assertEqual(bg_rows[0]["status"], "cancelled")

    def test_catchup_param_matching_rejects_empty_or_disjoint_params(self):
        from luma_cli.antigravity_agy_acp import AntigravityAgyAcpSession
        m = AntigravityAgyAcpSession._tool_params_match
        self.assertTrue(m({}, {}))
        self.assertFalse(m({"Path": "/a"}, {}))
        self.assertFalse(m({}, {"Path": "/a"}))
        self.assertFalse(m({"Path": "/a"}, None))
        self.assertFalse(m({"Path": "/a"}, {"Other": "1"}))
        self.assertTrue(m({"Path": "/a"}, {"Path": "/a"}))

if __name__ == "__main__":
    unittest.main(verbosity=2)

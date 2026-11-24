#!/usr/bin/env python3
import sys
sys.dont_write_bytecode = True
import copy
import json
import os
import pathlib
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "apps/gui/app/src/main/assets/cli_bridge"))
from luma_cli.antigravity import AntigravitySession
from luma_cli.antigravity_agy_acp import AntigravityAgyAcpSession, AGY_ACP_PROTOCOL
from luma_cli.antigravity_cli import AntigravityCliSession, PROTOCOL as STREAM_JSON_PROTOCOL, project_child_transcript
from luma_cli.base import parse_ts_ms
from luma_cli.claude import ClaudeSession
from luma_cli.grok import GrokSession
from luma_cli.i18n import tr
from luma_cli.pi import PiSession
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
            {"value": "gemini-pro-orchestrator", "name": "Orchestrator (Carbon)"},
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
    if method:
        with (store / "rpc_methods.jsonl").open("a", encoding="utf-8") as rpc_out:
            rpc_out.write(json.dumps({"method": method, "params": params}, ensure_ascii=False) + "\n")
    if method == "initialize":
        assert params.get("clientCapabilities", {}).get("elicitation") == {"form": {}}
        agent_caps = {"loadSession": True, "promptCapabilities": {"image": True, "embeddedContext": True}}
        if os.environ.get("LUMA_FIXTURE_RESUME_CAP") == "1":
            agent_caps["sessionCapabilities"] = {"resume": {}}
        send({"jsonrpc": "2.0", "id": ident, "result": {
            "protocolVersion": 1,
            "agentInfo": {"name": "Antigravity ACP Server", "version": "1.5.2"},
            "agentCapabilities": agent_caps,
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
    elif method == "session/resume":
        sid = params["sessionId"]
        session_options = copy.deepcopy(default_options)
        if not (store / f"{sid}.json").exists():
            (store / f"{sid}.json").write_text(json.dumps({"turns": []}))
        send({"jsonrpc": "2.0", "id": ident, "result": {"sessionId": sid, "configOptions": session_options}})
    elif method == "session/load":
        sid = params["sessionId"]
        state = json.loads((store / f"{sid}.json").read_text())
        session_options = copy.deepcopy(default_options)
        for turn in state["turns"]:
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "user_message_chunk", "content": {"type": "text", "text": turn["user"]}},
            }})
            if turn.get("thought"):
                send({"jsonrpc": "2.0", "method": "session/update", "params": {
                    "sessionId": sid,
                    "update": {"sessionUpdate": "agent_thought_chunk", "content": {"type": "text", "text": turn["thought"]}},
                }})
            for tc in turn.get("tool_calls", []):
                upd = {
                    "sessionUpdate": "tool_call",
                    "toolCallId": tc["id"],
                    "name": tc["name"],
                    "args": tc["args"],
                    "rawInput": tc["args"],
                    "status": tc.get("status", "completed"),
                    "kind": tc.get("kind", "other"),
                    "title": tc.get("title", "Run " + tc["name"]),
                }
                if "content" in tc:
                    upd["content"] = tc["content"]
                if "rawOutput" in tc:
                    upd["rawOutput"] = tc["rawOutput"]
                send({"jsonrpc": "2.0", "method": "session/update", "params": {
                    "sessionId": sid,
                    "update": upd,
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
        if params["configId"] == "model":
            if params["value"].startswith("gemini-pro-orchestrator"):
                session_options = [o for o in session_options if o["id"] != "thought_level"]
            elif not any(o["id"] == "thought_level" for o in session_options):
                default_tl = next((copy.deepcopy(o) for o in default_options if o["id"] == "thought_level"), None)
                if default_tl:
                    session_options.insert(1, default_tl)
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
        elif prompt_text == "orchestrator_steer_flow":
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "user_message_chunk", "content": {"type": "text", "text": prompt_text}},
            }})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "agent_thought_chunk", "content": {"type": "text", "text": "Planning phase"}},
            }})
            plan_doc = "## Execution Plan\n1. Refactor parser\n2. Run verification"
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {
                    "sessionUpdate": "tool_call",
                    "toolCallId": "call_exit_plan_1",
                    "name": "exit_plan_mode",
                    "title": "exit_plan_mode",
                    "rawInput": {"plan": plan_doc},
                    "status": "in_progress",
                    "kind": "other",
                },
            }})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {
                    "sessionUpdate": "tool_call_update",
                    "toolCallId": "call_exit_plan_1",
                    "status": "completed",
                    "content": [{"type": "content", "content": {"type": "text", "text": "Plan mode is off."}}],
                },
            }})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "Executor running..."}},
            }})
            steer_frame = json.loads(sys.stdin.readline())
            assert steer_frame["method"] == "session/prompt"
            steer_text = steer_frame["params"]["prompt"][0]["text"]
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "user_message_chunk", "content": {"type": "text", "text": steer_text}},
            }})
            send({"jsonrpc": "2.0", "id": steer_frame["id"], "result": {"stopReason": "completed"}})
            gate = store / "finish-main-orchestrator"
            while not gate.exists():
                time.sleep(0.01)
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {
                    "sessionUpdate": "tool_call",
                    "toolCallId": "call_enter_plan_1",
                    "name": "enter_plan_mode",
                    "title": "enter_plan_mode",
                    "rawInput": {"reason": "User steered execution toward approach B"},
                    "status": "completed",
                    "content": [{"type": "content", "content": {"type": "text", "text": "Plan mode is on."}}],
                },
            }})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "Replanned with approach B"}},
            }})
            send({"jsonrpc": "2.0", "id": ident, "result": {"stopReason": "end_turn"}})
        elif prompt_text == "real_agy_steer_flow":
            gemini = pathlib.Path(os.environ["GEMINI_HOME"]) / "antigravity-cli" / "brain"
            parent_log = gemini / sid / ".system_generated" / "logs" / "transcript.jsonl"
            parent_log.parent.mkdir(parents=True, exist_ok=True)
            with parent_log.open("a", encoding="utf-8") as f:
                f.write(json.dumps({
                    "step_index": 0,
                    "type": "USER_INPUT",
                    "status": "DONE",
                    "content": f"<USER_REQUEST>\n{prompt_text}\n</USER_REQUEST>",
                }) + "\n")
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {
                    "sessionUpdate": "tool_call",
                    "toolCallId": "call_real_1",
                    "name": "read_file",
                    "title": "/tmp/before.txt",
                    "kind": "read",
                    "status": "in_progress",
                },
            }})
            steer_frame = json.loads(sys.stdin.readline())
            assert steer_frame["method"] == "session/prompt"
            steer_text = steer_frame["params"]["prompt"][0]["text"]
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {
                    "sessionUpdate": "tool_call_update",
                    "toolCallId": "call_real_1",
                    "status": "completed",
                    "content": [{"type": "content", "content": {"type": "text", "text": "before body"}}],
                },
            }})
            with parent_log.open("a", encoding="utf-8") as f:
                f.write(json.dumps({
                    "step_index": 1,
                    "type": "PLANNER_RESPONSE",
                    "status": "DONE",
                    "tool_calls": [{"name": "read_file", "args": {"Path": '"/tmp/before.txt"'}}],
                }) + "\n")
                f.write(json.dumps({
                    "step_index": 2,
                    "type": "GENERIC",
                    "status": "DONE",
                    "content": "before body",
                }) + "\n")
                f.write(json.dumps({
                    "step_index": 3,
                    "type": "USER_INPUT",
                    "status": "DONE",
                    "content": f"<USER_REQUEST>\n{steer_text}\n</USER_REQUEST>",
                }) + "\n")
            send({"jsonrpc": "2.0", "id": ident, "result": {"stopReason": "end_turn"}})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {
                    "sessionUpdate": "tool_call",
                    "toolCallId": "call_real_2",
                    "name": "read_file",
                    "title": "/tmp/after.txt",
                    "kind": "read",
                    "status": "in_progress",
                },
            }})
            gate = store / "finish-real-agy-steer"
            while not gate.exists():
                time.sleep(0.01)
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {
                    "sessionUpdate": "tool_call_update",
                    "toolCallId": "call_real_2",
                    "status": "completed",
                    "content": [{"type": "content", "content": {"type": "text", "text": "after body"}}],
                },
            }})
            send({"jsonrpc": "2.0", "id": steer_frame["id"], "result": {"stopReason": "end_turn"}})
        elif prompt_text == "chained_steer_flow":
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {
                    "sessionUpdate": "tool_call",
                    "toolCallId": "call_chain_1",
                    "name": "read_file",
                    "title": "/tmp/chain1.txt",
                    "kind": "read",
                    "status": "in_progress",
                },
            }})
            steer_b = json.loads(sys.stdin.readline())
            assert steer_b["method"] == "session/prompt"
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {
                    "sessionUpdate": "tool_call_update",
                    "toolCallId": "call_chain_1",
                    "status": "completed",
                    "content": [{"type": "content", "content": {"type": "text", "text": "chain 1 done"}}],
                },
            }})
            send({"jsonrpc": "2.0", "id": ident, "result": {"stopReason": "end_turn"}})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {
                    "sessionUpdate": "tool_call",
                    "toolCallId": "call_chain_2",
                    "name": "read_file",
                    "title": "/tmp/chain2.txt",
                    "kind": "read",
                    "status": "in_progress",
                },
            }})
            steer_c = json.loads(sys.stdin.readline())
            assert steer_c["method"] == "session/prompt"
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {
                    "sessionUpdate": "tool_call_update",
                    "toolCallId": "call_chain_2",
                    "status": "completed",
                    "content": [{"type": "content", "content": {"type": "text", "text": "chain 2 done"}}],
                },
            }})
            send({"jsonrpc": "2.0", "id": steer_b["id"], "result": {"stopReason": "end_turn"}})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {
                    "sessionUpdate": "tool_call",
                    "toolCallId": "call_chain_3",
                    "name": "read_file",
                    "title": "/tmp/chain3.txt",
                    "kind": "read",
                    "status": "in_progress",
                },
            }})
            gate = store / "finish-chained-steer"
            while not gate.exists():
                time.sleep(0.01)
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {
                    "sessionUpdate": "tool_call_update",
                    "toolCallId": "call_chain_3",
                    "status": "completed",
                    "content": [{"type": "content", "content": {"type": "text", "text": "chain 3 done"}}],
                },
            }})
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": sid,
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "reply:chained_steer_done"}},
            }})
            send({"jsonrpc": "2.0", "id": steer_c["id"], "result": {"stopReason": "end_turn"}})
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
            self.assertTrue(s.data["planModeSupported"])
            self.assertFalse(s.data["planMode"])
            self.assertEqual([p["id"] for p in s.data["permissions"]], ["default", "accept-edits", "always-proceed"])
            s.configure({"effort": "high", "permission": "accept-edits", "planMode": True})
            self.assertEqual(s.data["effort"], "high")
            self.assertEqual(s.data["permission"], "accept-edits")
            self.assertTrue(s.data["planMode"])
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

    def test_plan_and_always_proceed_overlay_without_relaunch(self):
        with patch.dict(os.environ, self.env()):
            manager = Manager(self.root / "state-plan-overlay")
            s = manager.connect({"id": "overlay", "cli": "Antigravity", "cwd": str(self.root), "model": "claude-opus-5-5"})
            self.sessions.append(s)
            self.assertEqual(s.data["permission"], "default")
            self.assertTrue(s.data["planModeSupported"])
            self.assertFalse(s.data["planMode"])
            self.assertFalse(s.data["skipPermissionsActive"])
            self.assertEqual([p["id"] for p in s.data["permissions"]], ["default", "accept-edits", "always-proceed"])
            with self.assertRaises(Rejected):
                s.configure({"permission": "plan"})

            s.configure({"permission": "always-proceed"})
            self.assertEqual(s.data["permission"], "always-proceed")
            self.assertFalse(s.data["planMode"])
            self.assertTrue(s.data["skipPermissionsActive"])
            invocations_after_skip = [json.loads(line)["skip"] for line in (self.store / "invocations.jsonl").read_text().splitlines()]
            self.assertEqual(invocations_after_skip, [False, True])

            s.configure({"planMode": True})
            self.assertEqual(s.data["permission"], "always-proceed")
            self.assertTrue(s.data["planMode"])
            self.assertTrue(s.data["skipPermissionsActive"])
            invocations_after_plan = [json.loads(line)["skip"] for line in (self.store / "invocations.jsonl").read_text().splitlines()]
            self.assertEqual(invocations_after_plan, [False, True])

            s.submit({"submissionId": "turn-plan-auto", "text": "run_cmd"})
            self.wait(lambda: not s.data["running"])
            self.assertEqual([r["text"] for r in s.rows.values() if r["role"] == "assistant"][-1], "CMD=AUTO")

            s.event({"method": "session/update", "params": {"sessionId": s.data["nativeId"], "update": {"sessionUpdate": "current_mode_update", "currentModeId": "default"}}})
            self.assertEqual(s.data["permission"], "always-proceed")
            self.assertFalse(s.data["planMode"])
            self.assertTrue(s.data["skipPermissionsActive"])

            s.configure({"planMode": True})
            self.assertTrue(s.data["planMode"])
            self.assertEqual(s.data["permission"], "always-proceed")
            s.configure({"permission": "accept-edits"})
            self.assertTrue(s.data["planMode"])
            self.assertEqual(s.data["permission"], "accept-edits")
            self.assertFalse(s.data["skipPermissionsActive"])

            s.configure({"planMode": False})
            self.assertFalse(s.data["planMode"])
            self.assertEqual(s.data["permission"], "accept-edits")
            self.assertFalse(s.data["skipPermissionsActive"])

            s.configure({"planMode": True})
            self.assertTrue(s.data["planMode"])
            self.assertEqual(s.data["permission"], "accept-edits")
            self.assertFalse(s.data["skipPermissionsActive"])

            s.configure({"permission": "always-proceed"})
            self.assertTrue(s.data["planMode"])
            self.assertEqual(s.data["permission"], "always-proceed")
            self.assertTrue(s.data["skipPermissionsActive"])
            invocations_final = [json.loads(line)["skip"] for line in (self.store / "invocations.jsonl").read_text().splitlines()]
            self.assertEqual(invocations_final, [False, True, False, True])

    def test_additional_mode_group_does_not_replace_the_primary_mode_or_skip_flag(self):
        env = self.env()
        options = copy.deepcopy(AGY_ACP_OPTIONS)
        options.append({"id": "review", "category": "mode", "name": "Review", "type": "select", "currentValue": "off",
                        "options": [{"value": "off", "name": "Review off"}, {"value": "on", "name": "Review on"}]})
        env["LUMA_FIXTURE_OPTIONS"] = json.dumps(options)
        with patch.dict(os.environ, env):
            manager = Manager(self.root / "state-mode-groups")
            s = manager.connect({"id": "groups", "cli": "Antigravity", "cwd": str(self.root)})
            self.sessions.append(s)
            s.configure({"permission": "always-proceed", "permissionConfigId": "mode"})
            s.configure({"permission": "on", "permissionConfigId": "review"})
            self.assertTrue(s.data["skipPermissionsActive"])
            self.assertFalse(s.data["planMode"])
            self.assertEqual(s.native_mode, "default")
            self.assertEqual(s.data["permission"], "always-proceed")
            self.assertTrue(next(p for p in s.data["permissions"] if p["id"] == "always-proceed")["selected"])
            self.assertTrue(next(p for p in s.data["permissions"] if p["id"] == "on")["selected"])
            s.configure({"planMode": True})
            self.assertEqual(s.native_mode, "plan")
            self.assertTrue(s.data["planMode"])
            self.assertTrue(s.data["skipPermissionsActive"])
            self.assertTrue(next(p for p in s.data["permissions"] if p["id"] == "always-proceed")["selected"])
            self.assertTrue(next(p for p in s.data["permissions"] if p["id"] == "on")["selected"])
            s.event({"method": "session/update", "params": {"sessionId": s.data["nativeId"], "update": {"sessionUpdate": "current_mode_update", "currentModeId": "off"}}})
            self.assertEqual(s.native_mode, "plan")
            self.assertTrue(s.data["planMode"])
            self.assertTrue(next(p for p in s.data["permissions"] if p["id"] == "always-proceed")["selected"])
            self.assertTrue(next(p for p in s.data["permissions"] if p["id"] == "off")["selected"])

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
            self.assertEqual([m for m in s.data["metrics"] if m["label"] == tr("上下文", "Context")], [{"label": tr("上下文", "Context"), "value": "1500 / 256000 · 0.6%"}])
            self.assertIn({"label": tr("本回合 · 输入缓存命中", "This turn · input cache hit"), "value": "300 / 1500 · 20.0%"}, s.data["metrics"])
            self.assertIn({"label": tr("CLI 累计估算 · USD", "CLI estimated cost · USD"), "value": "0.00363"}, s.data["metrics"])

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
            self.wait(lambda: c1 in s.subagents and c2 in s.subagents and bool(s.subagents[c1].get("messages")) and bool(s.subagents[c2].get("messages")))
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
            with self.assertRaisesRegex(Conflict, tr("后台任务尚未确认结束", "Background task has not been confirmed finished")):
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
            with self.assertRaisesRegex(Conflict, tr("后台任务尚未确认结束", "Background task has not been confirmed finished")):
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

    def test_orchestrator_steer_plan_handoff_projection_and_configure_effort_switch(self):
        with patch.dict(os.environ, self.env()):
            manager = Manager(self.root / "state-orch")
            s = manager.connect({"id": "orch", "cli": "Antigravity", "cwd": str(self.root), "model": "gemini-pro-orchestrator"})
            self.sessions.append(s)
            self.assertTrue(s.data["capabilities"]["steer"])
            self.assertEqual(s.data["model"], "gemini-pro-orchestrator")
            self.assertEqual(s.data["effort"], "")
            self.assertNotIn("selectedEffort", s.config)
            self.assertFalse(s.can_steer({"text": "idle"}))

            s.configure({"model": "gemini-4-argon", "effort": "high"})
            self.assertEqual(s.data["model"], "gemini-4-argon")
            self.assertEqual(s.data["effort"], "high")
            self.assertEqual(s.config.get("selectedEffort"), "high")

            s.configure({"model": "gemini-pro-orchestrator"})
            self.assertEqual(s.data["model"], "gemini-pro-orchestrator")
            self.assertEqual(s.data["effort"], "")
            self.assertNotIn("selectedEffort", s.config)

            s.submit({"submissionId": "ask-check", "text": "ask"})
            self.wait(lambda: bool(s.data["requests"]))
            self.assertFalse(s.can_steer({"text": "blocked by question"}))
            req = s.data["requests"][0]
            s.answer({"id": req["id"], "choice": "accept", "answers": {"q1": ["Red"], "q2": ["Apple"]}})
            self.wait(lambda: not s.data["running"])

            s.submit({"submissionId": "main-orch", "text": "orchestrator_steer_flow"})
            self.wait(lambda: any(r.get("text") == "Executor running..." for r in s.rows.values()))
            self.assertTrue(s.data["running"])
            self.assertTrue(s.can_steer({"text": "steer: use approach B"}))
            self.assertFalse(s.can_steer({"text": "/compact"}))

            exit_row = s.rows["tool-call_exit_plan_1"]
            self.assertEqual(exit_row["text"], tr("退出规划 · 交由执行模型", "Exit plan · Hand off to executor"))
            self.assertEqual(exit_row["outcome"], "## Execution Plan\n1. Refactor parser\n2. Run verification")

            s.submit({"submissionId": "steer-1", "text": "steer: use approach B", "steer": True})
            self.wait(lambda: s.config.get("receipts", {}).get("steer-1") == "accepted" and s.steer_submission is None)
            self.assertTrue(s.data["running"])
            self.assertEqual(s.current_submission, "main-orch")
            self.assertTrue(s.config.get("consumedSubmissions", {}).get("steer-1"))
            steer_rows = [r for r in s.rows.values() if r["role"] == "user" and r["text"] == "steer: use approach B"]
            self.assertEqual(len(steer_rows), 1)
            self.assertEqual(steer_rows[0]["id"], "steer-1")
            self.assertEqual(steer_rows[0]["origin"], "native")

            (self.store / "finish-main-orchestrator").write_text("ok", encoding="utf-8")
            self.wait(lambda: not s.data["running"])
            enter_row = s.rows["tool-call_enter_plan_1"]
            self.assertEqual(enter_row["text"], tr("回交规划模型复盘", "Hand back to planner"))
            self.assertEqual(enter_row["outcome"], "User steered execution toward approach B")
            self.assertEqual(s.config.get("receipts", {}).get("main-orch"), "accepted")

    def test_real_agy_mid_turn_steer_handoff_via_transcript_without_user_chunk(self):
        with patch.dict(os.environ, self.env()):
            manager = Manager(self.root / "state-real-steer")
            s = manager.connect({"id": "real-steer", "cli": "Antigravity", "cwd": str(self.root)})
            self.sessions.append(s)
            s.action("submit", {"submissionId": "sub-main", "text": "real_agy_steer_flow"})
            self.wait(lambda: (
                s.config.get("receipts", {}).get("sub-main") == "accepted"
                and s.config.get("consumedSubmissions", {}).get("sub-main") is True
                and "tool-call_real_1" in s.rows
            ))
            s.action("submit", {"submissionId": "sub-steer", "text": "steer while working"})
            self.wait(lambda: (
                s.config.get("receipts", {}).get("sub-steer") == "accepted"
                and s.config.get("consumedSubmissions", {}).get("sub-steer") is True
                and "tool-call_real_2" in s.rows
            ))
            snap = s.snapshot()
            self.assertTrue(snap["running"])
            self.assertEqual(snap["hostQueue"], [])
            self.assertEqual(
                [(m["id"], m["role"], m["text"]) for m in snap["messages"]],
                [
                    ("sub-main", "user", "real_agy_steer_flow"),
                    ("tool-call_real_1", "tool", "/tmp/before.txt"),
                    ("sub-steer", "user", "steer while working"),
                    ("tool-call_real_2", "tool", "/tmp/after.txt"),
                ],
            )
            self.assertEqual(snap["messages"][0]["origin"], "native")
            self.assertEqual(snap["messages"][2]["origin"], "native")
            self.assertEqual(s.current_submission, "sub-steer")
            self.assertIsNotNone(s.fast_release_blocker())

            (self.store / "finish-real-agy-steer").write_text("ok", encoding="utf-8")
            self.wait(lambda: not s.data["running"])
            final_snap = s.snapshot()
            self.assertFalse(final_snap["running"])
            self.assertEqual(s.rows["tool-call_real_2"]["status"], "completed")
            self.assertIsNone(s.fast_release_blocker())
            self.assertTrue(final_snap["releasable"])

    def test_chained_steer_clears_running_and_allows_subsequent_slash_command(self):
        with patch.dict(os.environ, self.env()):
            manager = Manager(self.root / "state-chained-steer")
            s = manager.connect({"id": "chained-steer", "cli": "Antigravity", "cwd": str(self.root)})
            self.sessions.append(s)
            s.action("submit", {"submissionId": "sub-a", "text": "chained_steer_flow"})
            self.wait(lambda: (
                s.config.get("receipts", {}).get("sub-a") == "accepted"
                and "tool-call_chain_1" in s.rows
            ))
            s.action("submit", {"submissionId": "sub-b", "text": "steer b"})
            self.wait(lambda: (
                s.current_submission == "sub-b"
                and s.steer_submission is None
                and "tool-call_chain_2" in s.rows
            ))
            s.action("submit", {"submissionId": "sub-c", "text": "steer c"})
            self.wait(lambda: (
                s.current_submission == "sub-c"
                and s.steer_submission is None
                and "tool-call_chain_3" in s.rows
            ))
            self.assertTrue(s.data["running"])
            (self.store / "finish-chained-steer").write_text("ok", encoding="utf-8")
            self.wait(lambda: not s.data["running"])
            self.assertIsNone(s.current_submission)
            self.assertIsNone(s.steer_submission)
            self.assertEqual(s.active_prompts, [])
            self.assertIsNone(s.fast_release_blocker())
            self.assertTrue(s.snapshot()["releasable"])

            s.action("submit", {"submissionId": "sub-slash", "text": "/help"})
            self.wait(lambda: (
                s.config.get("receipts", {}).get("sub-slash") == "accepted"
                and not s.data["running"]
            ))
            snap = s.snapshot()
            self.assertEqual(snap["hostQueue"], [])
            self.assertIn("reply:/help", [r["text"] for r in snap["messages"] if r["role"] == "assistant"])

    def test_degraded_cold_start_without_model_catalog_rejects_and_preserves_saved_model_and_effort(self):
        state_dir = self.root / "state-degraded"
        mode_only = [o for o in AGY_ACP_OPTIONS if o["id"] == "mode"]
        with patch.dict(os.environ, self.env(LUMA_FIXTURE_RESUME_CAP="1")):
            manager = Manager(state_dir)
            s = manager.connect({"id": "cold", "cli": "Antigravity", "cwd": str(self.root), "model": "claude-opus-5-5"})
            self.sessions.append(s)
            s.configure({"effort": "high"})
            native_id = s.data["nativeId"]
            self.assertEqual(s.config.get("selectedModel"), "claude-opus-5-5")
            self.assertEqual(s.config.get("selectedEffort"), "high")
            manager.release("cold", {"nativeId": native_id, "nativeProtocol": AGY_ACP_PROTOCOL})

        with patch.dict(os.environ, self.env(LUMA_FIXTURE_OPTIONS=json.dumps(mode_only), LUMA_FIXTURE_RESUME_CAP="1")):
            bg = manager.connect({
                "id": "cold",
                "cli": "Antigravity",
                "cwd": str(self.root),
                "nativeId": native_id,
                "nativeProtocol": AGY_ACP_PROTOCOL,
            }, background=True)
            self.wait(lambda: not bg.data.get("connecting"))
            self.assertFalse(bg.data["connected"])
            self.assertEqual(bg.data["models"], [])
            self.assertIsNone(bg.peer)
            self.assertTrue(bg.data.get("error"))
            saved = json.loads((state_dir / "cold.json").read_text(encoding="utf-8"))
            self.assertEqual(saved.get("selectedModel"), "claude-opus-5-5")
            self.assertEqual(saved.get("selectedEffort"), "high")

        with patch.dict(os.environ, self.env(LUMA_FIXTURE_RESUME_CAP="1")):
            recovered = manager.connect({
                "id": "cold",
                "cli": "Antigravity",
                "cwd": str(self.root),
                "nativeId": native_id,
                "nativeProtocol": AGY_ACP_PROTOCOL,
            })
            self.sessions.append(recovered)
            self.assertTrue(recovered.data["connected"])
            self.assertEqual(recovered.data["model"], "claude-opus-5-5")
            self.assertEqual(recovered.data["effort"], "high")
            self.assertEqual([m["id"] for m in recovered.data["models"]], [
                "gemini-4-argon",
                "claude-opus-5-5",
                "gemini-pro-orchestrator",
            ])
            opus_entry = next(m for m in recovered.data["models"] if m["id"] == "claude-opus-5-5")
            self.assertEqual(opus_entry["efforts"], ["low", "medium", "high"])

    def test_new_session_degraded_catalog_retry_restores_initial_model_and_model_switch_syncs_effort(self):
        state_dir = self.root / "state-new-degraded"
        mode_only = [o for o in AGY_ACP_OPTIONS if o["id"] == "mode"]
        manager = Manager(state_dir)
        with patch.dict(os.environ, self.env(LUMA_FIXTURE_OPTIONS=json.dumps(mode_only))):
            with self.assertRaises(Rejected):
                manager.connect({"id": "newcold", "cli": "Antigravity", "cwd": str(self.root), "model": "claude-opus-5-5"})
        saved = json.loads((state_dir / "newcold.json").read_text(encoding="utf-8"))
        self.assertTrue(saved.get("nativeId"))
        self.assertEqual(saved.get("model"), "claude-opus-5-5")

        with patch.dict(os.environ, self.env()):
            recovered = manager.connect({
                "id": "newcold",
                "cli": "Antigravity",
                "cwd": str(self.root),
                "nativeId": saved["nativeId"],
                "nativeProtocol": AGY_ACP_PROTOCOL,
            })
            self.sessions.append(recovered)
            self.assertTrue(recovered.data["connected"])
            self.assertEqual(recovered.data["model"], "claude-opus-5-5")
            self.assertEqual(recovered.config.get("selectedModel"), "claude-opus-5-5")
            recovered.configure({"effort": "low"})
            self.assertEqual(recovered.config.get("selectedEffort"), "low")
            recovered.configure({"model": "gemini-4-argon"})
            self.assertEqual(recovered.data["model"], "gemini-4-argon")
            self.assertEqual(recovered.config.get("selectedEffort"), recovered.data["effort"])

    def test_permission_relaunch_without_model_catalog_rejects_and_preserves_effort_without_stale_models(self):
        state_dir = self.root / "state-relaunch-degraded"
        mode_only = [o for o in AGY_ACP_OPTIONS if o["id"] == "mode"]
        with patch.dict(os.environ, self.env()):
            manager = Manager(state_dir)
            s = manager.connect({"id": "rel", "cli": "Antigravity", "cwd": str(self.root), "model": "claude-opus-5-5"})
            self.sessions.append(s)
            s.configure({"effort": "high"})
            self.assertEqual(s.data["model"], "claude-opus-5-5")
            self.assertEqual(s.data["effort"], "high")
            with patch.dict(os.environ, self.env(LUMA_FIXTURE_OPTIONS=json.dumps(mode_only))):
                with self.assertRaises(Rejected):
                    s.configure({"permission": "always-proceed"})
            self.assertFalse(s.data["connected"])
            self.assertEqual(s.data["models"], [])
            saved = json.loads((state_dir / "rel.json").read_text(encoding="utf-8"))
            self.assertEqual(saved.get("selectedModel"), "claude-opus-5-5")
            self.assertEqual(saved.get("selectedEffort"), "high")
            self.assertFalse(saved.get("skipPermissions"))

    def test_attach_without_bridge_config_does_not_override_native_model_with_ui_cache(self):
        state_dir = self.root / "state-attach-cache"
        (self.store / "existing-native-id.json").parent.mkdir(parents=True, exist_ok=True)
        (self.store / "existing-native-id.json").write_text(json.dumps({"turns": []}), encoding="utf-8")
        with patch.dict(os.environ, self.env()):
            manager = Manager(state_dir)
            s = manager.connect({
                "id": "att",
                "cli": "Antigravity",
                "cwd": str(self.root),
                "nativeId": "existing-native-id",
                "nativeProtocol": AGY_ACP_PROTOCOL,
                "model": "claude-opus-5-5",
            })
            self.sessions.append(s)
            self.assertTrue(s.data["connected"])
            self.assertEqual(s.data["model"], "gemini-4-argon")
            rpc_methods = [json.loads(line)["method"] for line in (self.store / "rpc_methods.jsonl").read_text(encoding="utf-8").splitlines()]
            self.assertNotIn("session/set_config_option", rpc_methods)

    def test_pending_initial_model_missing_on_partial_retry_rejects_preserves_and_restores_later(self):
        state_dir = self.root / "state-partial-initial"
        mode_only = [o for o in AGY_ACP_OPTIONS if o["id"] == "mode"]
        partial_options = copy.deepcopy(AGY_ACP_OPTIONS)
        partial_options[0]["options"] = [o for o in partial_options[0]["options"] if o["value"] != "claude-opus-5-5"]
        manager = Manager(state_dir)
        with patch.dict(os.environ, self.env(LUMA_FIXTURE_OPTIONS=json.dumps(mode_only))):
            with self.assertRaises(Rejected):
                manager.connect({"id": "partinit", "cli": "Antigravity", "cwd": str(self.root), "model": "claude-opus-5-5"})
        saved = json.loads((state_dir / "partinit.json").read_text(encoding="utf-8"))
        self.assertEqual(saved.get("pendingInitialModel"), "claude-opus-5-5")

        with patch.dict(os.environ, self.env(LUMA_FIXTURE_OPTIONS=json.dumps(partial_options))):
            with self.assertRaisesRegex(Rejected, "claude-opus-5-5"):
                manager.connect({
                    "id": "partinit",
                    "cli": "Antigravity",
                    "cwd": str(self.root),
                    "nativeId": saved["nativeId"],
                    "nativeProtocol": AGY_ACP_PROTOCOL,
                })
        saved_after_partial = json.loads((state_dir / "partinit.json").read_text(encoding="utf-8"))
        self.assertEqual(saved_after_partial.get("pendingInitialModel"), "claude-opus-5-5")
        self.assertNotIn("selectedModel", saved_after_partial)

        with patch.dict(os.environ, self.env()):
            recovered = manager.connect({
                "id": "partinit",
                "cli": "Antigravity",
                "cwd": str(self.root),
                "nativeId": saved["nativeId"],
                "nativeProtocol": AGY_ACP_PROTOCOL,
            })
            self.sessions.append(recovered)
            self.assertTrue(recovered.data["connected"])
            self.assertEqual(recovered.data["catalogState"], "ready")
            self.assertEqual(recovered.data["model"], "claude-opus-5-5")
            self.assertEqual(recovered.config.get("selectedModel"), "claude-opus-5-5")
            self.assertNotIn("pendingInitialModel", recovered.config)

    def test_permission_relaunch_mid_handshake_snapshot_is_not_catalog_ready(self):
        state_dir = self.root / "state-relaunch-mid"
        with patch.dict(os.environ, self.env()):
            manager = Manager(state_dir)
            s = manager.connect({"id": "relmid", "cli": "Antigravity", "cwd": str(self.root), "model": "claude-opus-5-5"})
            self.sessions.append(s)
            s.configure({"effort": "high"})
            entered_init = threading.Event()
            release_init = threading.Event()
            orig_call = s.call

            def paused_call(method, params=None, **kw):
                if method == "initialize":
                    entered_init.set()
                    release_init.wait(timeout=3.0)
                return orig_call(method, params, **kw)

            error_box = []
            def do_relaunch():
                try:
                    s.configure({"permission": "always-proceed"})
                except Exception as exc:
                    error_box.append(exc)

            with patch.object(s, "call", side_effect=paused_call):
                t = threading.Thread(target=do_relaunch, daemon=True)
                t.start()
                self.assertTrue(entered_init.wait(timeout=3.0))
                mid = s.snapshot()
                self.assertFalse(mid["connected"])
                self.assertTrue(mid["connecting"])
                self.assertEqual(mid["catalogState"], "loading")
                self.assertEqual(mid["models"], [])
                release_init.set()
                t.join(timeout=5.0)
            self.assertEqual(error_box, [])
            final = s.snapshot()
            self.assertTrue(final["connected"])
            self.assertFalse(final["connecting"])
            self.assertEqual(final["catalogState"], "ready")
            self.assertEqual(final["model"], "claude-opus-5-5")
            self.assertEqual(final["effort"], "high")
            self.assertEqual(final["permission"], "always-proceed")

class SwarmProjectionAcpTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="luma-agy-swarm-")
        self.root = pathlib.Path(self.tmp.name)
        self.gemini_home = self.root / ".gemini"
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()
        self.agy = self.bin_dir / "agy"
        self.agy.write_text(FIXTURE_AGY, encoding="utf-8")
        self.agy.chmod(0o700)
        self.store = self.root / "native-store"

    def tearDown(self):
        self.tmp.cleanup()

    def _transcript_file(self, conv_id, full=False):
        name = "transcript_full.jsonl" if full else "transcript.jsonl"
        path = self.gemini_home / "antigravity-cli" / "brain" / conv_id / ".system_generated" / "logs" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        return path

    def _write_steps(self, conv_id, steps, full_steps=None):
        if full_steps is not None:
            full_path = self._transcript_file(conv_id, full=True)
            with full_path.open("w", encoding="utf-8") as handle:
                for step in full_steps:
                    handle.write(json.dumps(step, ensure_ascii=False) + "\n")
        path = self._transcript_file(conv_id)
        with path.open("w", encoding="utf-8") as handle:
            for step in steps:
                handle.write(json.dumps(step, ensure_ascii=False) + "\n")
        return path

    def _make_session(self, native_id, current_submission="sub-1"):
        s = AntigravityAgyAcpSession({"cli": "Antigravity", "cwd": str(self.root), "nativeId": native_id}, lambda: None)
        self.addCleanup(s.finish_observer_close)
        s.history_loaded = True
        s.current_submission = current_submission
        s.chunk_id = None
        s.chunk_kind = None
        s.chunk_seq = 0
        s.peer = None
        s.echoed_user_submission = False
        return s

    def test_swarm_single_and_batch_add_status_model_kill_and_revive(self):
        parent_id = "11111111-1111-4111-8111-111111111111"
        w_rev = "22222222-2222-4222-8222-222222222222"
        w_b1 = "33333333-3333-4333-8333-333333333333"
        w_b2 = "44444444-4444-4444-8444-444444444444"
        with patch.dict(os.environ, {"GEMINI_HOME": str(self.gemini_home)}):
            s = self._make_session(parent_id, current_submission="sub-1")

            s.event({"method": "session/update", "params": {"update": {
                "sessionUpdate": "tool_call", "toolCallId": "c-add-1", "name": "swarm",
                "title": "Run swarm", "kind": "other", "status": "in_progress",
                "rawInput": {"action": "add", "name": "reviewer", "brief": "Audit parser.py", "model": "gemini-3.1-pro-low", "workspace": "share"},
            }}})
            s.event({"method": "session/update", "params": {"update": {
                "sessionUpdate": "tool_call_update", "toolCallId": "c-add-1", "status": "completed",
            }}})
            s.event({"method": "session/update", "params": {"update": {
                "sessionUpdate": "tool_call", "toolCallId": "c-add-2", "name": "swarm",
                "title": "Run swarm", "kind": "other", "status": "completed",
                "rawInput": {"action": "add", "name": "batch", "brief": "Check modules", "tasks": ["alpha.py", "beta.py"]},
            }}})

            compact_steps = [
                {"step_index": 0, "type": "USER_INPUT", "status": "DONE", "content": "Go"},
                {
                    "step_index": 1, "type": "PLANNER_RESPONSE", "status": "DONE",
                    "truncated_fields": ["tool_calls"],
                    "tool_calls": [
                        {"name": "swarm", "args": {"action": "add", "name": "reviewer", "brief": "TRUNCATED"}},
                        {"name": "swarm", "args": {"action": "add", "name": "batch", "brief": "TRUNCATED"}},
                    ],
                },
                {"step_index": 2, "type": "GENERIC", "status": "DONE", "content": json.dumps({"status": "added", "name": "reviewer", "model": "gemini-3.1-pro-low", "conversation_id": w_rev})},
                {"step_index": 3, "type": "GENERIC", "status": "DONE", "content": json.dumps({"status": "added", "names": ["batch-1", "batch-2"], "model": "gemini-3.1-pro-low", "conversation_ids": [w_b1, w_b2]})},
            ]
            full_steps = json.loads(json.dumps(compact_steps))
            full_steps[1] = {
                "step_index": 1, "type": "PLANNER_RESPONSE", "status": "DONE",
                "tool_calls": [
                    {"name": "swarm", "args": {"action": "add", "name": "reviewer", "brief": "Audit parser.py", "model": "gemini-3.1-pro-low", "workspace": "share"}},
                    {"name": "swarm", "args": {"action": "add", "name": "batch", "brief": "Check modules", "tasks": json.dumps(["alpha.py", "beta.py"])}},
                ],
            }
            self._write_steps(parent_id, compact_steps, full_steps=full_steps)

            rev_steps = [
                {"step_index": 0, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": "<SYSTEM_MESSAGE>[swarm] new board message #1</SYSTEM_MESSAGE>"},
                {"step_index": 1, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": "<SYSTEM_MESSAGE>[Message] sender=coordinator content=You have just started.\nAudit parser.py</SYSTEM_MESSAGE>"},
                {"step_index": 2, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                    {"name": "post_message", "args": {"text": "@coordinator LGTM on parser.py"}},
                    {"name": "swarm_wait", "args": {"since": "1"}},
                ]},
                {"step_index": 3, "type": "GENERIC", "status": "DONE", "content": '{"status":"posted","seq":2}'},
                {"step_index": 4, "type": "GENERIC", "status": "DONE", "content": "Waiting for a message that calls on you by name, or on @all posted after #1."},
            ]
            self._write_steps(w_rev, rev_steps)
            b1_steps = [
                {"step_index": 0, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": "<SYSTEM_MESSAGE>[Message] sender=coordinator content=Assigned slice: alpha.py</SYSTEM_MESSAGE>"},
                {"step_index": 1, "type": "PLANNER_RESPONSE", "status": "RUNNING", "thinking": "Inspecting alpha.py"},
            ]
            self._write_steps(w_b1, b1_steps)
            self._write_steps(w_b2, [
                {"step_index": 0, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": "<SYSTEM_MESSAGE>[Message] sender=coordinator content=Assigned slice: beta.py</SYSTEM_MESSAGE>"},
            ])

            s.sync_transcripts_once()
            self.assertEqual(set(s.subagents), {w_rev, w_b1, w_b2})
            self.assertEqual(s.subagents[w_rev]["name"], "reviewer")
            self.assertEqual(s.subagents[w_rev]["role"], "swarm · share")
            self.assertEqual(s.subagents[w_rev]["itemId"], "tool-c-add-1")
            self.assertEqual(s.subagents[w_rev]["status"], "succeeded")
            self.assertEqual(s.subagents[w_rev]["nativeStatus"], "transcript:idle")
            self.assertEqual(s.subagents[w_rev]["detail"], "@coordinator LGTM on parser.py")
            rev_msgs = s.subagents[w_rev]["messages"]
            self.assertFalse(any("[swarm] new board message #1" in (m.get("text") or "") for m in rev_msgs))
            self.assertTrue(any("Audit parser.py" in (m.get("text") or "") for m in rev_msgs))

            self.assertEqual(s.subagents[w_b1]["name"], "batch-1")
            self.assertEqual(s.subagents[w_b1]["task"], "alpha.py\n\nCheck modules")
            self.assertEqual(s.subagents[w_b1]["itemId"], "tool-c-add-2")
            self.assertEqual(s.subagents[w_b2]["name"], "batch-2")
            self.assertEqual(s.subagents[w_b2]["task"], "beta.py\n\nCheck modules")
            self.assertEqual(s.subagents[w_b2]["itemId"], "tool-c-add-2")

            self._write_steps(parent_id, compact_steps + [
                {"step_index": 4, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": f"<SYSTEM_MESSAGE>[Message] sender={w_rev} content=[swarm] new board message #2</SYSTEM_MESSAGE>"},
                {"step_index": 5, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                    {"name": "swarm", "args": {"action": "status"}},
                    {"name": "swarm", "args": {"action": "model", "name": "reviewer", "model": "gemini-3.8-flash-low"}},
                ]},
                {"step_index": 6, "type": "GENERIC", "status": "DONE", "content": json.dumps({
                    "title": "Demo Swarm",
                    "members": [
                        {"name": "coordinator", "role": "coordinator", "model": "gemini-3.1-pro-low", "state": "working", "killed": False},
                        {"name": "reviewer", "role": "worker", "model": "gemini-3.1-pro-low", "state": "waiting", "killed": False},
                        {"name": "batch-1", "role": "worker", "model": "gemini-3.1-pro-low", "state": "stopped", "killed": False},
                        {"name": "batch-2", "role": "worker", "model": "gemini-3.1-pro-low", "state": "working", "killed": False},
                    ],
                })},
                {"step_index": 7, "type": "GENERIC", "status": "DONE", "content": json.dumps({"status": "switched", "name": "reviewer", "model": "gemini-3.8-flash-low"})},
            ], full_steps=full_steps + [
                {"step_index": 4, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": f"<SYSTEM_MESSAGE>[Message] sender={w_rev} content=[swarm] new board message #2</SYSTEM_MESSAGE>"},
                {"step_index": 5, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                    {"name": "swarm", "args": {"action": "status"}},
                    {"name": "swarm", "args": {"action": "model", "name": "reviewer", "model": "gemini-3.8-flash-low"}},
                ]},
                {"step_index": 6, "type": "GENERIC", "status": "DONE", "content": json.dumps({
                    "title": "Demo Swarm",
                    "members": [
                        {"name": "coordinator", "role": "coordinator", "model": "gemini-3.1-pro-low", "state": "working", "killed": False},
                        {"name": "reviewer", "role": "worker", "model": "gemini-3.1-pro-low", "state": "waiting", "killed": False},
                        {"name": "batch-1", "role": "worker", "model": "gemini-3.1-pro-low", "state": "stopped", "killed": False},
                        {"name": "batch-2", "role": "worker", "model": "gemini-3.1-pro-low", "state": "working", "killed": False},
                    ],
                })},
                {"step_index": 7, "type": "GENERIC", "status": "DONE", "content": json.dumps({"status": "switched", "name": "reviewer", "model": "gemini-3.8-flash-low"})},
            ])
            s.sync_transcripts_once()
            self.assertNotIn(w_rev, s.child_replies)
            self.assertEqual(s.subagents[w_rev]["detail"], "@coordinator LGTM on parser.py")
            self.assertEqual(set(s.subagents), {w_rev, w_b1, w_b2})
            self.assertEqual(s.subagents[w_rev]["model"], "gemini-3.8-flash-low")
            self.assertEqual(s.subagents[w_rev]["status"], "succeeded")
            self.assertEqual(s.subagents[w_b1]["status"], "succeeded")
            self.assertEqual(s.subagents[w_b1]["nativeStatus"], "swarm:stopped")
            self.assertEqual(s.subagents[w_b2]["status"], "running")

            s.event({"method": "session/update", "params": {"update": {
                "sessionUpdate": "tool_call", "toolCallId": "c-post-1", "name": "post_message",
                "title": "Run post_message", "kind": "other", "status": "completed",
                "rawInput": {"text": "@reviewer recheck edge case\n@batch sync up"},
                "rawOutput": [{"type": "content", "content": {"type": "text", "text": '{"status":"posted","seq":3}'}}],
            }}})
            self.assertEqual(s.subagents[w_rev]["status"], "running")
            self.assertEqual(s.subagents[w_rev]["nativeStatus"], "revived")
            self.assertEqual(s.subagents[w_b1]["status"], "running")
            self.assertEqual(s.subagents[w_b1]["nativeStatus"], "revived")
            self.assertEqual(s.subagents[w_b2]["status"], "running")
            self.assertEqual(s.subagents[w_b2]["nativeStatus"], "revived")

            self._write_steps(w_rev, rev_steps)
            s.sync_transcripts_once()
            self.assertEqual(s.subagents[w_rev]["status"], "running")
            self.assertEqual(s.subagents[w_rev]["nativeStatus"], "revived")

            self._write_steps(w_rev, rev_steps + [
                {"step_index": 5, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": "<SYSTEM_MESSAGE>[Message] sender=coordinator content=@reviewer recheck edge case</SYSTEM_MESSAGE>"},
                {"step_index": 6, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                    {"name": "post_message", "args": {"text": "@coordinator verified edge case"}},
                    {"name": "swarm_wait", "args": {"since": "3"}},
                ]},
                {"step_index": 7, "type": "GENERIC", "status": "DONE", "content": '{"status":"posted","seq":4}'},
                {"step_index": 8, "type": "GENERIC", "status": "DONE", "content": "Waiting for a message that calls on you by name, or on @all posted after #3."},
            ])
            s.sync_transcripts_once()
            self.assertEqual(s.subagents[w_rev]["status"], "succeeded")
            self.assertEqual(s.subagents[w_rev]["nativeStatus"], "transcript:idle")
            self.assertEqual(s.subagents[w_rev]["detail"], "@coordinator verified edge case")

            s.event({"method": "session/update", "params": {"update": {
                "sessionUpdate": "tool_call", "toolCallId": "c-kill-1", "name": "swarm",
                "title": "Run swarm", "kind": "other", "status": "completed",
                "rawInput": {"action": "kill", "name": "reviewer"},
            }}})
            self.assertEqual(s.subagents[w_rev]["status"], "closed")
            self.assertEqual(s.subagents[w_rev]["nativeStatus"], "swarm:killed")

            s.event({"method": "session/update", "params": {"update": {
                "sessionUpdate": "tool_call", "toolCallId": "c-post-all", "name": "post_message",
                "title": "Run post_message", "kind": "other", "status": "completed",
                "rawInput": {"text": "@all wrap up"},
            }}})
            self.assertEqual(s.subagents[w_rev]["status"], "closed")
            self.assertEqual(s.subagents[w_rev]["nativeStatus"], "swarm:killed")
            self.assertEqual(s.subagents[w_b1]["status"], "running")
            self.assertEqual(s.subagents[w_b1]["nativeStatus"], "revived")

            self._write_steps(w_rev, rev_steps + [
                {"step_index": 9, "type": "ERROR_MESSAGE", "status": "DONE", "content": "late write"},
            ])
            s.sync_transcripts_once()
            self.assertEqual(s.subagents[w_rev]["status"], "closed")
            self.assertEqual(s.subagents[w_rev]["nativeStatus"], "swarm:killed")

    def test_swarm_reanchor_when_transcript_arrives_before_acp_row(self):
        parent_id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        w1 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        with patch.dict(os.environ, {"GEMINI_HOME": str(self.gemini_home)}):
            s = self._make_session(parent_id, current_submission=None)
            self._write_steps(parent_id, [
                {"step_index": 0, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                    {"name": "swarm", "args": {"action": "add", "name": "scout", "brief": "Scan logs"}},
                ]},
                {"step_index": 1, "type": "GENERIC", "status": "DONE", "content": json.dumps({"status": "added", "name": "scout", "conversation_id": w1})},
            ])
            s.sync_transcripts_once()
            self.assertEqual(s.subagents[w1]["itemId"], "")

            s.event({"method": "session/update", "params": {"update": {
                "sessionUpdate": "tool_call", "toolCallId": "late-add-1", "name": "swarm",
                "title": "Run swarm", "kind": "other", "status": "in_progress",
                "rawInput": {"action": "add", "name": "scout", "brief": "Scan logs"},
            }}})
            self.assertEqual(s.subagents[w1]["itemId"], "tool-late-add-1")

    def test_acp_session_load_replay_keeps_settled_swarm_workers_settled(self):
        sid = "agy-acp-session-1"
        w_done = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        w_killed = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        self.store.mkdir(parents=True, exist_ok=True)
        (self.store / f"{sid}.json").write_text(json.dumps({
            "turns": [{
                "user": "coordinate swarm",
                "thought": "spawning helpers",
                "tool_calls": [
                    {"id": "load-add-1", "name": "swarm", "args": {"action": "add", "name": "worker-1", "brief": "Check A"}},
                    {"id": "load-add-2", "name": "swarm", "args": {"action": "add", "name": "worker-2", "brief": "Check B"}},
                    {"id": "load-post-1", "name": "post_message", "args": {"text": "@worker-1 start A"}},
                    {"id": "load-kill-2", "name": "swarm", "args": {"action": "kill", "name": "worker-2"}},
                ],
                "reply": "Coordinator done.",
            }],
        }), encoding="utf-8")
        self._write_steps(sid, [
            {"step_index": 0, "type": "USER_INPUT", "status": "DONE", "content": "coordinate swarm"},
            {"step_index": 1, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                {"name": "swarm", "args": {"action": "add", "name": "worker-1", "brief": "Check A"}},
                {"name": "swarm", "args": {"action": "add", "name": "worker-2", "brief": "Check B"}},
                {"name": "post_message", "args": {"text": "@worker-1 start A"}},
                {"name": "swarm", "args": {"action": "kill", "name": "worker-2"}},
            ]},
            {"step_index": 2, "type": "GENERIC", "status": "DONE", "content": json.dumps({"status": "added", "name": "worker-1", "conversation_id": w_done})},
            {"step_index": 3, "type": "GENERIC", "status": "DONE", "content": json.dumps({"status": "added", "name": "worker-2", "conversation_id": w_killed})},
            {"step_index": 4, "type": "GENERIC", "status": "DONE", "content": '{"status":"posted","seq":2}'},
            {"step_index": 5, "type": "GENERIC", "status": "DONE", "content": json.dumps({"status": "killed", "name": "worker-2"})},
        ])
        self._write_steps(w_done, [
            {"step_index": 0, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                {"name": "post_message", "args": {"text": "@coordinator A is green"}},
                {"name": "swarm_wait", "args": {"since": "1"}},
            ]},
            {"step_index": 1, "type": "GENERIC", "status": "DONE", "content": '{"status":"posted","seq":3}'},
            {"step_index": 2, "type": "GENERIC", "status": "DONE", "content": "Waiting for a message"},
        ])
        self._write_steps(w_killed, [
            {"step_index": 0, "type": "PLANNER_RESPONSE", "status": "RUNNING", "thinking": "Checking B"},
        ])
        env = {
            "PATH": f"{self.bin_dir}:/usr/bin:/bin",
            "HOME": str(self.root),
            "GEMINI_HOME": str(self.gemini_home),
            "LUMA_FIXTURE_STORE": str(self.store),
            "LUMA_FIXTURE_OPTIONS": json.dumps(AGY_ACP_OPTIONS),
        }
        state_dir = self.root / "state"
        state_dir.mkdir()
        (state_dir / "replay.json").write_text(json.dumps({
            "cli": "Antigravity", "cwd": str(self.root), "nativeId": sid, "nativeProtocol": AGY_ACP_PROTOCOL,
        }), encoding="utf-8")
        session = None
        try:
            with patch.dict(os.environ, env):
                manager = Manager(state_dir)
                session = manager.connect({"id": "replay", "cli": "Antigravity", "cwd": str(self.root)})
                self.assertEqual(session.subagents[w_done]["status"], "succeeded")
                self.assertEqual(session.subagents[w_done]["itemId"], "tool-load-add-1")
                self.assertEqual(session.subagents[w_done]["detail"], "@coordinator A is green")
                self.assertEqual(session.subagents[w_killed]["status"], "closed")
                self.assertEqual(session.subagents[w_killed]["nativeStatus"], "swarm:killed")
                self.assertEqual(session.subagents[w_killed]["itemId"], "tool-load-add-2")
        finally:
            if session is not None:
                session._close_current_peer()

    def test_acp_session_load_settles_incomplete_replay_tools_to_unknown(self):
        sid = "agy-acp-session-1"
        self.store.mkdir(parents=True, exist_ok=True)
        (self.store / f"{sid}.json").write_text(json.dumps({
            "turns": [{
                "user": "crashed turn",
                "thought": "running",
                "tool_calls": [
                    {"id": "crashed-1", "name": "read_file", "args": {"Path": "/x"}, "status": "in_progress"},
                ],
                "reply": "",
            }],
        }), encoding="utf-8")
        env = {
            "PATH": f"{self.bin_dir}:/usr/bin:/bin",
            "HOME": str(self.root),
            "GEMINI_HOME": str(self.gemini_home),
            "LUMA_FIXTURE_STORE": str(self.store),
            "LUMA_FIXTURE_OPTIONS": json.dumps(AGY_ACP_OPTIONS),
        }
        state_dir = self.root / "state-inc"
        state_dir.mkdir()
        (state_dir / "inc.json").write_text(json.dumps({
            "cli": "Antigravity", "cwd": str(self.root), "nativeId": sid, "nativeProtocol": AGY_ACP_PROTOCOL,
        }), encoding="utf-8")
        session = None
        try:
            with patch.dict(os.environ, env):
                manager = Manager(state_dir)
                session = manager.connect({"id": "inc", "cli": "Antigravity", "cwd": str(self.root)})
                self.assertEqual(session.rows["tool-crashed-1"]["status"], "unknown")
        finally:
            if session is not None:
                session._close_current_peer()

    def test_steer_rejected_reverts_optimistic_user_row_and_pending_echo(self):
        s = self._make_session("parent-steer-reject", current_submission="main-1")
        s.update(connected=True, running=True, capabilities={"steer": True})
        s.call = lambda *args, **kwargs: (_ for _ in ()).throw(Rejected("Steer rejected by ACP"))
        s.prompt({"submissionId": "steer-rej", "text": "abort path", "steer": True})
        deadline = time.monotonic() + 2.0
        while s.steer_submission is not None and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertIsNone(s.steer_submission)
        self.assertNotIn("steer-rej", s.rows)
        self.assertEqual(s.pending_echo_submissions, [])
        self.assertEqual(s.config.get("receipts", {}).get("steer-rej"), "rejected")
        self.assertIn("Steer rejected by ACP", s.data.get("error") or "")

    def test_promoted_steer_rejected_promotes_next_active_steer_and_settles(self):
        with patch.dict(os.environ, {"GEMINI_HOME": str(self.gemini_home)}):
            s = self._make_session("parent-chained-reject", current_submission=None)
            s.update(connected=True, capabilities={"steer": True})
            gate_a = threading.Event()
            gate_b = threading.Event()
            gate_c = threading.Event()
            entered = {}

            def fake_call(method, params, timeout=45, on_uncertain=None):
                txt = params["prompt"][0]["text"]
                entered[txt] = True
                if txt == "turn a":
                    gate_a.wait(3.0)
                    return {"stopReason": "end_turn"}
                if txt == "steer b":
                    gate_b.wait(3.0)
                    raise Rejected("B rejected")
                if txt == "steer c":
                    gate_c.wait(3.0)
                    return {"stopReason": "end_turn"}
                raise AssertionError(f"Unexpected prompt {txt}")

            s.call = fake_call
            s.prompt({"submissionId": "sub-a", "text": "turn a"})
            s.prompt({"submissionId": "sub-b", "text": "steer b", "steer": True})
            deadline = time.monotonic() + 2.0
            while not (entered.get("turn a") and entered.get("steer b")) and time.monotonic() < deadline:
                time.sleep(0.01)
            gate_a.set()

            deadline = time.monotonic() + 2.0
            while not (s.current_submission == "sub-b" and s.steer_submission is None) and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertEqual(s.current_submission, "sub-b")
            self.assertIsNone(s.steer_submission)
            self.assertTrue(s.data["running"])

            s.prompt({"submissionId": "sub-c", "text": "steer c", "steer": True})
            deadline = time.monotonic() + 2.0
            while not (entered.get("steer c") and "sub-c" in s.active_prompts) and time.monotonic() < deadline:
                time.sleep(0.01)
            gate_b.set()

            deadline = time.monotonic() + 2.0
            while not (s.current_submission == "sub-c" and s.steer_submission is None) and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertEqual(s.current_submission, "sub-c")
            self.assertIsNone(s.steer_submission)
            self.assertTrue(s.data["running"])
            self.assertEqual(s.config.get("receipts", {}).get("sub-b"), "accepted")
            self.assertIn("sub-b", s.rows)
            self.assertIn("B rejected", s.data.get("error") or "")

            gate_c.set()
            deadline = time.monotonic() + 2.0
            while s.data["running"] and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertFalse(s.data["running"])
            self.assertIsNone(s.current_submission)
            self.assertIsNone(s.steer_submission)
            self.assertEqual(s.active_prompts, [])
            self.assertEqual(s.config.get("receipts", {}).get("sub-c"), "accepted")

            s2 = self._make_session("parent-main-reject", current_submission=None)
            s2.update(connected=True, capabilities={"steer": True})
            gate_1 = threading.Event()
            gate_2 = threading.Event()
            entered2 = {}

            def fake_call2(method, params, timeout=45, on_uncertain=None):
                txt = params["prompt"][0]["text"]
                entered2[txt] = True
                if txt == "main turn":
                    gate_1.wait(3.0)
                    raise Rejected("Main rejected")
                if txt == "steer follow":
                    gate_2.wait(3.0)
                    return {"stopReason": "end_turn"}
                raise AssertionError(f"Unexpected prompt {txt}")

            s2.call = fake_call2
            s2.prompt({"submissionId": "m-1", "text": "main turn"})
            s2.prompt({"submissionId": "s-2", "text": "steer follow", "steer": True})
            deadline = time.monotonic() + 2.0
            while not (entered2.get("main turn") and entered2.get("steer follow")) and time.monotonic() < deadline:
                time.sleep(0.01)
            gate_1.set()
            deadline = time.monotonic() + 2.0
            while not (s2.current_submission == "s-2" and s2.steer_submission is None) and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue(s2.data["running"])
            self.assertEqual(s2.config.get("receipts", {}).get("m-1"), "rejected")
            self.assertIn("Main rejected", s2.data.get("error") or "")
            gate_2.set()
            deadline = time.monotonic() + 2.0
            while s2.data["running"] and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertFalse(s2.data["running"])
            self.assertIsNone(s2.current_submission)
            self.assertIsNone(s2.steer_submission)
            self.assertEqual(s2.active_prompts, [])
            self.assertEqual(s2.config.get("receipts", {}).get("s-2"), "accepted")

    def test_finish_observer_close_joins_tail_and_blocks_post_close_sync(self):
        parent_id = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        w1 = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        with patch.dict(os.environ, {"GEMINI_HOME": str(self.gemini_home)}):
            s = self._make_session(parent_id, current_submission="sub-close")
            self._write_steps(parent_id, [
                {"step_index": 0, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                    {"name": "swarm", "args": {"action": "add", "name": "worker", "brief": "Scan"}},
                ]},
                {"step_index": 1, "type": "GENERIC", "status": "DONE", "content": json.dumps({"status": "added", "name": "worker", "conversation_id": w1})},
            ])
            self._write_steps(w1, [{"step_index": 0, "type": "PLANNER_RESPONSE", "status": "RUNNING", "thinking": "work"}])
            entered = threading.Event()
            release = threading.Event()
            orig_refresh = s.refresh_parent_log

            def slow_refresh():
                entered.set()
                release.wait(2.0)
                orig_refresh()

            with patch.object(s, "refresh_parent_log", side_effect=slow_refresh):
                s.restart_child_tail()
                s.tail_wake.set()
                self.assertTrue(entered.wait(2.0))
                s.close_pending = True
                closer = threading.Thread(target=s.finish_observer_close)
                closer.start()
                time.sleep(0.05)
                self.assertTrue(closer.is_alive())
                release.set()
                closer.join(2.0)
                self.assertFalse(closer.is_alive())
                self.assertFalse(s.tail_thread.is_alive())
            self.assertTrue(s.released)
            self.assertEqual(s.rows, {})
            self.assertEqual(s.subagents, {})
            s.sync_transcripts_once()
            self.assertEqual(s.rows, {})
            self.assertEqual(s.subagents, {})

    def test_acp_post_message_mention_boundaries_and_early_event_before_worker_registration(self):
        parent_id = "12121212-1212-4121-8121-121212121212"
        w1 = "34343434-3434-4343-8343-343434343434"
        with patch.dict(os.environ, {"GEMINI_HOME": str(self.gemini_home)}):
            s = self._make_session(parent_id, current_submission="sub-mention")
            s.event({"method": "session/update", "params": {"update": {
                "sessionUpdate": "tool_call", "toolCallId": "early-post-1", "name": "post_message",
                "title": "Run post_message", "kind": "other", "status": "completed",
                "rawInput": {"text": "notify @worker. Check email user@worker.com."},
                "rawOutput": [{"type": "content", "content": {"type": "text", "text": '{"status":"posted","seq":1}'}}],
            }}})
            self.assertNotIn("tool-early-post-1", s.handled_swarm_rows)
            self._write_steps(parent_id, [
                {"step_index": 0, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                    {"name": "swarm", "args": {"action": "add", "name": "worker", "brief": "Audit"}},
                    {"name": "post_message", "args": {"text": "notify @worker. Check email user@worker.com."}},
                ]},
                {"step_index": 1, "type": "GENERIC", "status": "DONE", "content": json.dumps({"status": "added", "name": "worker", "conversation_id": w1})},
                {"step_index": 2, "type": "GENERIC", "status": "DONE", "content": '{"status":"posted","seq":1}'},
            ])
            s.sync_transcripts_once()
            self.assertEqual(s.subagents[w1]["status"], "running")
            self.assertEqual(s.subagents[w1]["nativeStatus"], "revived")
            self.assertIn("tool-early-post-1", s.handled_swarm_rows)
            s.note_swarm_tool("x", "sub-mention", {"action": "status"}, json.dumps({"members": [{"name": "worker", "role": "worker", "state": "waiting", "killed": False}]}))
            self.assertEqual(s.subagents[w1]["status"], "succeeded")
            self.assertFalse(s.note_post_message({"text": "mail user@all.example"}, '{"status":"posted"}', allow_revive=True))
            self.assertEqual(s.subagents[w1]["status"], "succeeded")
            self.assertTrue(s.note_post_message({"text": "done, @all."}, '{"status":"posted"}', allow_revive=True))
            self.assertEqual(s.subagents[w1]["status"], "running")

    def test_sync_output_mentioning_background_banner_stays_completed_live_and_across_replay(self):
        sid = "agy-sync-banner-1"
        false_banner = "397: text\n40:_BG_TASK_RE = Tool is running as a background task with task id: fake/999\n"
        self.store.mkdir(parents=True, exist_ok=True)
        (self.store / f"{sid}.json").write_text(json.dumps({
            "turns": [{
                "user": "grep source",
                "tool_calls": [
                    {
                        "id": "sync-grep-1",
                        "name": "run_command",
                        "args": {"CommandLine": "rg -n background_task file.py"},
                        "rawOutput": {"output": "The tool's output was truncated...\n" + false_banner, "exit_code": 0},
                    },
                ],
                "reply": "Done grepping.",
            }],
        }), encoding="utf-8")
        self._write_steps(sid, [
            {"step_index": 0, "type": "USER_INPUT", "status": "DONE", "content": "grep source"},
            {"step_index": 1, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                {"name": "run_command", "args": {"CommandLine": '"rg -n background_task file.py"'}},
            ]},
            {"step_index": 2, "type": "GENERIC", "status": "DONE", "content": "Created At: x\nCompleted At: y\n" + false_banner},
            {"step_index": 3, "type": "PLANNER_RESPONSE", "status": "DONE", "content": "Done grepping."},
        ])
        env = {
            "PATH": f"{self.bin_dir}:/usr/bin:/bin",
            "HOME": str(self.root),
            "GEMINI_HOME": str(self.gemini_home),
            "LUMA_FIXTURE_STORE": str(self.store),
            "LUMA_FIXTURE_OPTIONS": json.dumps(AGY_ACP_OPTIONS),
        }
        state_dir = self.root / "state-sync-banner"
        state_dir.mkdir()
        (state_dir / "syncb.json").write_text(json.dumps({
            "cli": "Antigravity", "cwd": str(self.root), "nativeId": sid, "nativeProtocol": AGY_ACP_PROTOCOL,
        }), encoding="utf-8")
        session = None
        try:
            with patch.dict(os.environ, env):
                manager = Manager(state_dir)
                session = manager.connect({"id": "syncb", "cli": "Antigravity", "cwd": str(self.root)})
                self.assertEqual(session.rows["tool-sync-grep-1"]["status"], "completed")
                self.assertFalse(session.background_tasks)
                self.assertTrue(session.snapshot()["releasable"])
                session.event({"method": "session/update", "params": {"update": {
                    "sessionUpdate": "tool_call_update",
                    "toolCallId": "live-grep-2",
                    "name": "run_command",
                    "status": "completed",
                    "rawOutput": {"output": false_banner, "exit_code": 0},
                }}})
                self.assertEqual(session.rows["tool-live-grep-2"]["status"], "completed")
                self.assertFalse(session.background_tasks)
                self.assertTrue(session.snapshot()["releasable"])
        finally:
            if session is not None:
                session._close_current_peer()

    def test_acp_session_load_replay_settles_historical_background_task_before_unknown_sweep(self):
        sid = "agy-replay-bg-1"
        tid = f"{sid}/task-121"
        self.store.mkdir(parents=True, exist_ok=True)
        (self.store / f"{sid}.json").write_text(json.dumps({
            "turns": [{
                "user": "run long check",
                "tool_calls": [
                    {
                        "id": "hist-bg-1",
                        "name": "run_command",
                        "args": {"CommandLine": "sleep 2"},
                        "content": [{"type": "content", "content": {"type": "text", "text": f"Created At: x\nTool is running as a background task with task id: {tid}"}}],
                    },
                ],
                "reply": "Finished historical bg check.",
            }],
        }), encoding="utf-8")
        sys_msg = (
            '<SYSTEM_MESSAGE>\n[Message] timestamp=2026-11-15T00:00:02Z '
            f'sender={tid} priority=MESSAGE_PRIORITY_HIGH content=Task id "{tid}" finished with result:\n'
            'Command exited with code 0.\nhistorical output\n</SYSTEM_MESSAGE>'
        )
        self._write_steps(sid, [
            {"step_index": 0, "type": "USER_INPUT", "status": "DONE", "content": "run long check"},
            {"step_index": 1, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                {"name": "run_command", "args": {"CommandLine": '"sleep 2"'}},
            ]},
            {"step_index": 2, "type": "GENERIC", "status": "RUNNING", "content": f"Created At: x\nTool is running as a background task with task id: {tid}"},
            {"step_index": 3, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": sys_msg},
            {"step_index": 4, "type": "PLANNER_RESPONSE", "status": "DONE", "content": "Finished historical bg check."},
        ])
        env = {
            "PATH": f"{self.bin_dir}:/usr/bin:/bin",
            "HOME": str(self.root),
            "GEMINI_HOME": str(self.gemini_home),
            "LUMA_FIXTURE_STORE": str(self.store),
            "LUMA_FIXTURE_OPTIONS": json.dumps(AGY_ACP_OPTIONS),
        }
        state_dir = self.root / "state-hist-bg"
        state_dir.mkdir()
        (state_dir / "histbg.json").write_text(json.dumps({
            "cli": "Antigravity", "cwd": str(self.root), "nativeId": sid, "nativeProtocol": AGY_ACP_PROTOCOL,
        }), encoding="utf-8")
        session = None
        try:
            with patch.dict(os.environ, env):
                manager = Manager(state_dir)
                session = manager.connect({"id": "histbg", "cli": "Antigravity", "cwd": str(self.root)})
                self.assertEqual(session.rows["tool-hist-bg-1"]["status"], "completed")
                self.assertIn("historical output", session.rows["tool-hist-bg-1"]["outcome"])
                self.assertFalse(session.background_tasks)
                self.assertTrue(session.snapshot()["releasable"])
        finally:
            if session is not None:
                session._close_current_peer()

    def test_autonomous_streamed_race_and_conditional_timer_early_wakeup_settle_cleanly(self):
        sid = "56565656-5656-4565-8565-565656565656"
        timer_id = f"{sid}/task-55"
        with patch.dict(os.environ, {"GEMINI_HOME": str(self.gemini_home)}):
            s = self._make_session(sid, current_submission=None)
            s.parent_turn_settled = True
            self._write_steps(sid, [
                {"step_index": 0, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": '<SYSTEM_MESSAGE>\n[Message] sender=sub-1 priority=MESSAGE_PRIORITY_HIGH content=ready\n</SYSTEM_MESSAGE>'},
                {"step_index": 1, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                    {"name": "read_file", "args": {"Path": '"/tmp/notes.md"'}},
                    {"name": "schedule", "args": {"DurationSeconds": "30", "Prompt": '"fallback"', "TimerCondition": '"any"'}},
                ]},
            ])
            s.sync_transcripts_once()
            self.assertEqual(s.rows["agy-auto:1:tool:0"]["status"], "running")
            self.assertEqual(s.rows["agy-auto:1:tool:1"]["status"], "running")

            s.event({"method": "session/update", "params": {"update": {
                "sessionUpdate": "tool_call", "toolCallId": "auto-race-1", "name": "read_file",
                "rawInput": {"Path": "/tmp/notes.md"}, "status": "in_progress", "kind": "read", "title": "/tmp/notes.md",
            }}})
            s.autonomous_streamed = True
            self.assertNotIn("tool-auto-race-1", s.rows)

            self._write_steps(sid, [
                {"step_index": 0, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": '<SYSTEM_MESSAGE>\n[Message] sender=sub-1 priority=MESSAGE_PRIORITY_HIGH content=ready\n</SYSTEM_MESSAGE>'},
                {"step_index": 1, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                    {"name": "read_file", "args": {"Path": '"/tmp/notes.md"'}},
                    {"name": "schedule", "args": {"DurationSeconds": "30", "Prompt": '"fallback"', "TimerCondition": '"any"'}},
                ]},
                {"step_index": 2, "type": "GENERIC", "status": "DONE", "content": "notes body"},
                {"step_index": 3, "type": "GENERIC", "status": "RUNNING", "content": f"Created At: x\nTool is running as a background task with task id: {timer_id}\nTask Description: Timer: 30s"},
                {"step_index": 4, "type": "PLANNER_RESPONSE", "status": "DONE", "content": "Waiting for worker."},
            ])
            s.sync_transcripts_once()
            self.assertEqual(s.rows["agy-auto:1:tool:0"]["status"], "completed")
            self.assertEqual(s.rows["agy-auto:1:tool:0"]["outcome"], "notes body")
            self.assertEqual(s.rows["agy-auto:1:tool:1"]["status"], "running")
            self.assertIn(timer_id, s.conditional_timers)

            self._write_steps(sid, [
                {"step_index": 0, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": '<SYSTEM_MESSAGE>\n[Message] sender=sub-1 priority=MESSAGE_PRIORITY_HIGH content=ready\n</SYSTEM_MESSAGE>'},
                {"step_index": 1, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                    {"name": "read_file", "args": {"Path": '"/tmp/notes.md"'}},
                    {"name": "schedule", "args": {"DurationSeconds": "30", "Prompt": '"fallback"', "TimerCondition": '"any"'}},
                ]},
                {"step_index": 2, "type": "GENERIC", "status": "DONE", "content": "notes body"},
                {"step_index": 3, "type": "GENERIC", "status": "RUNNING", "content": f"Created At: x\nTool is running as a background task with task id: {timer_id}\nTask Description: Timer: 30s"},
                {"step_index": 4, "type": "PLANNER_RESPONSE", "status": "DONE", "content": "Waiting for worker."},
                {"step_index": 5, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": '<SYSTEM_MESSAGE>\n[Message] sender=sub-2 priority=MESSAGE_PRIORITY_HIGH content=done early\n</SYSTEM_MESSAGE>'},
                {"step_index": 6, "type": "PLANNER_RESPONSE", "status": "DONE", "content": "All done."},
            ])
            s.sync_transcripts_once()
            self.assertEqual(s.rows["agy-auto:1:tool:1"]["status"], "completed")
            self.assertFalse(s.background_tasks)
            self.assertFalse(s.conditional_timers)
            self.assertFalse(s.autonomous_open_tools)
            self.assertFalse(s.autonomous_running)
            self.assertFalse(s.in_autonomous_turn)
            s.config["nativeProtocol"] = AGY_ACP_PROTOCOL
            s.native_caps = {"loadSession": True}
            s.data["connected"] = True
            s.data["historyReady"] = True
            s.peer = type("Peer", (), {"closed": False, "pending": {}})()
            try:
                self.assertIsNone(s.fast_release_blocker())
            finally:
                s.peer = None

    def test_session_resume_hydrates_ordered_transcript_and_skips_session_load(self):
        sid = "77777777-7777-4777-8777-777777777777"
        child_id = "88888888-8888-4888-8888-888888888888"
        log_dir = self.gemini_home / "antigravity-cli" / "brain" / sid / ".system_generated" / "logs"
        log_dir.mkdir(parents=True, exist_ok=True)
        child_log_dir = self.gemini_home / "antigravity-cli" / "brain" / child_id / ".system_generated" / "logs"
        child_log_dir.mkdir(parents=True, exist_ok=True)
        child_log_path_obj = child_log_dir / "transcript.jsonl"
        img_path = self.root / "uploaded_shot.png"
        png_bytes = (
            b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
            b"\x08\x06\x00\x00\x00\x1f\x15c4\x00\x00\x00\rIDATx\x9cc```\xf8\x0f\x00\x01\x04\x01\x00"
            b"\x80\x1d\x1e\x1f\x00\x00\x00\x00IEND\xaeB`\x82"
        )
        img_path.write_bytes(png_bytes)

        compact_steps = [
            {
                "step_index": 0,
                "type": "USER_INPUT",
                "status": "DONE",
                "created_at": "2026-11-18T10:00:00Z",
                "content": "<USER_REQUEST>\nfirst prompt\n</USER_REQUEST>\n<ADDITIONAL_METADATA>\nThe current local time is: 2026-11-18.\n</ADDITIONAL_METADATA>",
                "media": [{"mime_type": "image/png", "uri": str(img_path)}],
            },
            {
                "step_index": 1,
                "type": "PLANNER_RESPONSE",
                "status": "DONE",
                "created_at": "2026-11-18T10:00:03Z",
                "thinking": "truncated...",
                "truncated_fields": ["thinking"],
                "tool_calls": [
                    {"name": "read_file", "args": {"Path": '"/tmp/notes.md"'}},
                    {"name": "exit_plan_mode", "args": {"plan": '"## Plan\\nStep 1"'}},
                ],
            },
            {"step_index": 2, "type": "GENERIC", "status": "DONE", "created_at": "2026-11-18T10:00:05Z", "content": "notes body"},
            {"step_index": 3, "type": "GENERIC", "status": "DONE", "created_at": "2026-11-18T10:00:06Z", "content": "Plan mode is off."},
            {"step_index": 4, "type": "SYSTEM_MESSAGE", "status": "DONE", "created_at": "2026-11-18T10:00:06Z", "content": '<SYSTEM_MESSAGE>\nsender=system content=plan_mode_handoff\n</SYSTEM_MESSAGE>'},
            {"step_index": 5, "type": "PLANNER_RESPONSE", "status": "DONE", "created_at": "2026-11-18T10:00:10Z", "thinking": "ready reply", "content": "handover written"},
            {
                "step_index": 6,
                "type": "USER_INPUT",
                "status": "DONE",
                "content": "<USER_REQUEST>\nsecond prompt\n</USER_REQUEST>",
            },
            {
                "step_index": 7,
                "type": "PLANNER_RESPONSE",
                "status": "DONE",
                "tool_calls": [
                    {"name": "invoke_subagent", "args": {"TypeName": '"research"', "Role": '"Inspector"', "Prompt": '"Check logs"'}},
                ],
            },
            {
                "step_index": 8,
                "type": "GENERIC",
                "status": "DONE",
                "content": "Created the following subagent:\n" + json.dumps({"conversationId": child_id, "logAbsoluteUri": f"file://{child_log_path_obj}"}),
            },
            {"step_index": 9, "type": "PLANNER_RESPONSE", "status": "DONE", "content": "all done"},
        ]
        full_steps = copy.deepcopy(compact_steps)
        full_steps[1]["thinking"] = "full hydrated reasoning process"
        del full_steps[1]["truncated_fields"]

        (log_dir / "transcript.jsonl").write_text("".join(json.dumps(s) + "\n" for s in compact_steps), encoding="utf-8")
        (log_dir / "transcript_full.jsonl").write_text("".join(json.dumps(s) + "\n" for s in full_steps), encoding="utf-8")
        child_log_path_obj.write_text("".join(json.dumps(s) + "\n" for s in [
            {"step_index": 0, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": f"<SYSTEM_MESSAGE>\n[Message] sender={sid} priority=MESSAGE_PRIORITY_HIGH content=Check logs\n</SYSTEM_MESSAGE>"},
            {"step_index": 1, "type": "PLANNER_RESPONSE", "status": "DONE", "content": "child finished"},
        ]), encoding="utf-8")

        state_dir = self.root / "state-resume"
        state_dir.mkdir(parents=True, exist_ok=True)
        (state_dir / "resume1.json").write_text(json.dumps({
            "cli": "Antigravity",
            "cwd": str(self.root),
            "nativeId": sid,
            "nativeProtocol": AGY_ACP_PROTOCOL,
        }), encoding="utf-8")

        env = {
            "PATH": f"{self.bin_dir}:/usr/bin:/bin",
            "HOME": str(self.root),
            "GEMINI_HOME": str(self.gemini_home),
            "LUMA_FIXTURE_STORE": str(self.store),
            "LUMA_FIXTURE_OPTIONS": json.dumps(AGY_ACP_OPTIONS),
            "LUMA_FIXTURE_RESUME_CAP": "1",
        }
        session = None
        try:
            with patch.dict(os.environ, env):
                manager = Manager(state_dir)
                session = manager.connect({"id": "resume1", "cli": "Antigravity", "cwd": str(self.root)})
                snap = session.snapshot()
                rpc_methods = [json.loads(line)["method"] for line in (self.store / "rpc_methods.jsonl").read_text(encoding="utf-8").splitlines()]
                self.assertIn("session/resume", rpc_methods)
                self.assertNotIn("session/load", rpc_methods)

                msgs = snap["messages"]
                self.assertEqual([(m["role"], m.get("kind", ""), m["text"]) for m in msgs], [
                    ("user", "", "first prompt"),
                    ("reasoning", "reasoning", "full hydrated reasoning process"),
                    ("tool", "tool", "/tmp/notes.md"),
                    ("tool", "tool", tr("退出规划 · 交由执行模型", "Exit plan · Hand off to executor")),
                    ("reasoning", "reasoning", "ready reply"),
                    ("assistant", "", "handover written"),
                    ("user", "", "second prompt"),
                    ("tool", "tool", "invoke_subagent"),
                    ("assistant", "", "all done"),
                ])
                self.assertEqual(msgs[3]["outcome"], "## Plan\nStep 1")
                self.assertEqual(msgs[0]["timestampMs"], 1794996000000)
                self.assertEqual(msgs[1]["timestampMs"], 1794996003000)
                self.assertEqual(msgs[2]["completedAtMs"], 1794996005000)
                self.assertEqual(msgs[5]["timestampMs"], 1794996010000)
                self.assertEqual(len(msgs[0].get("media", [])), 1)
                cached_src = msgs[0]["media"][0]["source"]
                self.assertTrue(cached_src.startswith(str(state_dir / "media-cache")))
                self.assertTrue(pathlib.Path(cached_src).is_file())
                self.assertEqual(len(snap["subAgents"]), 1)
                self.assertEqual(snap["subAgents"][0]["itemId"], msgs[7]["id"])
                self.assertTrue(snap["historyReady"])
                self.assertTrue(snap["releasable"])
        finally:
            if session is not None:
                session._close_current_peer()

    def test_native_timestamp_extraction_across_agy_claude_pi_and_grok(self):
        self.assertEqual(parse_ts_ms(1), 0)
        self.assertEqual(parse_ts_ms("2026-11-18T10:00:00Z"), 1794996000000)

        child_msgs = project_child_transcript(
            "child-1",
            [
                {"step_index": 0, "type": "USER_INPUT", "status": "DONE", "created_at": "2026-11-18T10:00:00Z", "content": "check"},
                {
                    "step_index": 1,
                    "type": "PLANNER_RESPONSE",
                    "status": "DONE",
                    "created_at": "2026-11-18T10:00:02Z",
                    "thinking": "plan",
                    "tool_calls": [{"name": "run_command", "args": {"CommandLine": '"ls"'}}],
                },
                {
                    "step_index": 2,
                    "type": "GENERIC",
                    "status": "DONE",
                    "created_at": "2026-11-18T10:00:07Z",
                    "content": "Created At: 2026-11-18T10:00:03Z\nCompleted At: 2026-11-18T10:00:06Z\nok",
                },
                {"step_index": 3, "type": "PLANNER_RESPONSE", "status": "DONE", "created_at": "2026-11-18T10:00:09Z", "content": "done"},
            ],
        )
        self.assertEqual(child_msgs[0]["timestampMs"], 1794996000000)
        self.assertEqual(child_msgs[1]["timestampMs"], 1794996002000)
        self.assertEqual(child_msgs[2]["timestampMs"], 1794996002000)
        self.assertEqual(child_msgs[2]["completedAtMs"], 1794996006000)
        self.assertEqual(child_msgs[3]["timestampMs"], 1794996009000)

        cs = ClaudeSession({"cli": "Claude Code", "cwd": "/tmp", "nativeId": "c1", "receipts": {}}, lambda: None)
        cs.current_submission = None; cs.stream_id = None; cs.stream_blocks = {}; cs.finalized_blocks = set()
        cs.usage_message = {}; cs.usage_models = {}; cs.usage_rows = []
        cs.project({"type": "user", "uuid": "u1", "timestamp": "2026-11-18T10:00:00Z", "message": {"role": "user", "content": "hi"}})
        cs.project({"type": "assistant", "uuid": "a1", "timestamp": "2026-11-18T10:00:03Z", "message": {"role": "assistant", "content": [{"type": "tool_use", "id": "tu1", "name": "Bash", "input": {"command": "ls"}}]}})
        cs.project({"type": "user", "uuid": "tr1", "timestamp": "2026-11-18T10:00:08Z", "message": {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "tu1", "content": "ok"}]}})
        self.assertEqual(cs.rows["u1:0"]["timestampMs"], 1794996000000)
        self.assertEqual(cs.rows["tool-tu1"]["timestampMs"], 1794996003000)
        self.assertEqual(cs.rows["tool-tu1"]["completedAtMs"], 1794996008000)

        ps = PiSession({"cli": "pi", "cwd": "/tmp"}, lambda: None)
        ps.project({"id": "pu1", "role": "user", "timestamp": 1794996000000, "content": "go"}, "f0")
        ps.project({"id": "pa1", "role": "assistant", "timestamp": 1794996002000, "content": [{"type": "thinking", "thinking": "hmm", "_done": True}, {"type": "toolCall", "id": "pt1", "name": "read", "arguments": {"path": "x"}}]}, "f1", done=True)
        ps.project({"id": "pr1", "role": "toolResult", "toolCallId": "pt1", "toolName": "read", "timestamp": 1794996005000, "content": [{"type": "text", "text": "ok"}]}, "f2", done=True)
        self.assertEqual(ps.rows["pu1"]["timestampMs"], 1794996000000)
        self.assertEqual(ps.rows["pa1-0"]["timestampMs"], 1794996002000)
        self.assertEqual(ps.rows["tool-pt1"]["timestampMs"], 1794996002000)
        self.assertEqual(ps.rows["tool-pt1"]["completedAtMs"], 1794996005000)

        gs = GrokSession({"cli": "Grok Build", "cwd": "/tmp", "nativeId": "g1", "receipts": {}}, lambda: None)
        gs.loading = gs.processing_replay = False
        gs.seen_events = set()
        gs.chunk_prompt = gs.chunk_kind = gs.chunk_id = gs.current_submission = None
        gs.background_tools = {}
        gs.child_sessions = {}
        gs.spawn_tools = []
        gs.event({"method": "session/update", "params": {"sessionId": "g1", "update": {"sessionUpdate": "agent_thought_chunk", "content": {"type": "text", "text": "think"}}, "_meta": {"isReplay": True, "agentTimestampMs": 1794996001000}}})
        gs.event({"method": "session/update", "params": {"sessionId": "g1", "update": {"sessionUpdate": "tool_call", "toolCallId": "gt1", "title": "Bash", "status": "completed"}, "_meta": {"isReplay": True, "agentTimestampMs": 1794996004000}}})
        gs.event({"method": "session/update", "params": {"sessionId": "g1", "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "done"}}, "_meta": {"isReplay": True, "agentTimestampMs": 1794996007000}}})
        gs.event({"method": "session/update", "params": {"sessionId": "g1", "update": {"sessionUpdate": "turn_completed"}, "_meta": {"isReplay": True, "agentTimestampMs": 1794996008000}}})
        rows = list(gs.rows.values())
        self.assertEqual(rows[0]["timestampMs"], 1794996001000)
        self.assertEqual(rows[1]["timestampMs"], 1794996004000)
        self.assertEqual(rows[1]["completedAtMs"], 1794996004000)
        self.assertEqual(rows[2]["timestampMs"], 1794996007000)

    def test_background_task_live_log_tail_updates_and_settles_cleanly(self):
        with tempfile.TemporaryDirectory() as td:
            log_file = pathlib.Path(td) / "task-233.log"
            log_file.write_text("  0 48.8M  0 3.3M\r 45 48.8M 45 22.0M\r", encoding="utf-8")
            s = self._make_session("s-bg")
            s.tail_thread = type("Dummy", (), {"is_alive": lambda self: True, "join": lambda self, timeout=None: None})()
            bg_output = (
                "Tool is running as a background task with task id: s-bg/task-233\n"
                f"Task logs are available at: file://{log_file}\n"
                "Log output:\n"
                "  0 48.8M  0 3.3M\n"
            )
            s.event({"method": "session/update", "params": {"sessionId": "s-bg", "update": {
                "sessionUpdate": "tool_call", "toolCallId": "tc-bg", "name": "run_command", "title": "Download",
                "status": "in_progress", "rawInput": {"CommandLine": "curl -O ..."},
            }}})
            s.event({"method": "session/update", "params": {"sessionId": "s-bg", "update": {
                "sessionUpdate": "tool_call_update", "toolCallId": "tc-bg", "name": "run_command",
                "status": "completed", "rawOutput": {"output": bg_output},
            }}})
            self.assertEqual(s.rows["tool-tc-bg"]["status"], "running")
            s.refresh_background_task_logs()
            self.assertEqual(s.rows["tool-tc-bg"]["outcome"], " 45 48.8M 45 22.0M")
            log_file.write_text("header\n 45 48.8M 45 22.0M\r 92 48.8M 92 45.0M\r", encoding="utf-8")
            s.refresh_background_task_logs()
            self.assertEqual(s.rows["tool-tc-bg"]["outcome"], "header\n 92 48.8M 92 45.0M")
            s._settle_background_task("s-bg/task-233", "completed", outcome="download ok", settled_at_ms=1795000000000)
            self.assertEqual(s.rows["tool-tc-bg"]["status"], "completed")
            self.assertEqual(s.rows["tool-tc-bg"]["outcome"], "download ok")
            self.assertNotIn("s-bg/task-233", s.background_task_logs)

if __name__ == "__main__":
    unittest.main(verbosity=2)

#!/usr/bin/env python3
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "apps/gui/app/src/main/assets/cli_bridge"))

from luma_cli.base import Session
from luma_cli.claude import ClaudeSession
from luma_cli.claude_pty import (
    CLAUDE_PTY_PROTOCOL,
    CLAUDE_STREAM_PROTOCOL,
    ClaudePtySession,
    _build_claude_hooks_settings,
    _extract_tui_confirm_dialog,
    _parse_pane_permission_mode,
    _probe_claude_catalog,
    probe_claude_pty,
)
from luma_cli.metrics import claude_context, model_context_window
from luma_cli.native_owner import (
    NativeOwner,
    _verified_tmux_pane_pid,
    pty_owner_settled,
    reap_recorded_owner,
    stop_recorded_owner,
)
from luma_cli.pty_tui import PtyOwnerPeer
from luma_cli.server import Manager, default_new_protocol
from luma_cli.transport import Conflict, Rejected

NATIVE_UUID = "11111111-2222-4333-8444-555555555555"
OTHER_UUID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

FULL_HELP = """Options:
  --settings <file>
  --session-id <uuid>
  --resume [value]
  --allow-dangerously-skip-permissions
  --permission-mode <mode> (choices: "default", "acceptEdits", "auto", "plan", "dontAsk", "bypassPermissions")
"""

LEGACY_HELP = """Options:
  --permission-mode <mode> (choices: "default", "acceptEdits", "plan")
"""

def _post_json(url, payload, headers=None):
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", **(headers or {})},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        try:
            return exc.code, json.loads(exc.read().decode("utf-8"))
        finally:
            exc.close()

def _wait_for(predicate, timeout=6.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.05)
    return False

class PtyFoundationAndClaudeTests(unittest.TestCase):
    def test_native_owner_pty_tui_primitives_and_reaping(self):
        with tempfile.TemporaryDirectory(prefix="luma-pty-owner-") as tmp:
            config = {"id": "pty1", "cli": "Claude Code", "cwd": tmp, "ownerDirectory": tmp + "/owner", "nativeId": NATIVE_UUID}
            session = Session(config, lambda: None)
            session.update(nativeId=NATIVE_UUID)
            owner = NativeOwner(session)
            out_file = pathlib.Path(tmp) / "captured.txt"
            py_script = (
                "import sys, time; "
                "data = sys.stdin.readline(); "
                f"open({str(out_file)!r}, 'w').write(data); "
                "print('GOT:' + data.strip(), flush=True); "
                "time.sleep(30)"
            )
            try:
                owner.launch_tui([sys.executable, "-u", "-c", py_script], host_kind="pty-tui", binary_name=os.path.basename(sys.executable))
                self.assertEqual(owner.handle["hostKind"], "pty-tui")
                self.assertEqual(owner.handle["binaryName"], os.path.basename(sys.executable))
                self.assertNotIn("socket", owner.handle)
                self.assertTrue(owner.alive())
                pane_pid, start = _verified_tmux_pane_pid(owner.handle["tmuxCommand"], owner.handle["terminalSocket"], owner.handle)
                self.assertIsNotNone(pane_pid)
                self.assertIsNotNone(start)
                time.sleep(0.3)
                owner.inject_text("hello pty", submit=True, submit_delay=0.05)
                self.assertTrue(_wait_for(lambda: out_file.is_file() and "hello pty" in out_file.read_text()))
                pane_text = owner.capture_pane(20)
                self.assertIn("GOT:", pane_text)
                owner.write_runtime_state(busy=True, pendingRequests=0, waitingForStartup=False)
                self.assertFalse(pty_owner_settled(owner.directory, 0))
                self.assertFalse(reap_recorded_owner(config, 0, {}))
                owner.write_runtime_state(busy=False, pendingRequests=1, waitingForStartup=False)
                self.assertFalse(pty_owner_settled(owner.directory, 0))
                owner.write_runtime_state(busy=False, pendingRequests=0, waitingForStartup=False)
                self.assertTrue(pty_owner_settled(owner.directory, 0))
                term = owner.terminal()
                self.assertEqual(term["nativeId"], NATIVE_UUID)
                self.assertEqual(term["instanceId"], owner.handle["instanceId"])
                self.assertIn("attach-session", term["argv"])
                self.assertLess(len("/data/user/0/io.github.modao7067.luma/files/linux/debian") + len(owner.tmux_socket), 107)
                self.assertLess(len("/data/user/0/io.github.modao7067.luma/files/linux/debian") + len(owner.socket), 107)
            finally:
                stop_recorded_owner(config)
                self.assertFalse(owner.alive())

    def test_claude_protocol_default_and_fallback_preserve_legacy_sessions(self):
        with tempfile.TemporaryDirectory(prefix="luma-claude-proto-") as tmp:
            work = pathlib.Path(tmp) / "work"
            work.mkdir()
            state = pathlib.Path(tmp) / "state"
            with patch("luma_cli.server.probe_claude_pty", return_value=(True, True, "", "/usr/bin/claude")), \
                 patch.object(ClaudePtySession, "connect", lambda s: s.update(connected=True, canOpenTui=True, nativeProtocol=CLAUDE_PTY_PROTOCOL)):
                with Manager(state) as m:
                    s = m.connect({"id": "new-claude", "cli": "Claude Code", "cwd": str(work)})
                    self.assertIs(type(s), ClaudePtySession)
                    self.assertEqual(s.config["nativeProtocol"], CLAUDE_PTY_PROTOCOL)
            with patch("luma_cli.server.probe_claude_pty", return_value=(True, False, "old flags", "/usr/bin/claude")), \
                 patch.object(ClaudeSession, "connect", lambda s: s.update(connected=True)):
                with Manager(state) as m:
                    s = m.connect({"id": "fallback-claude", "cli": "Claude Code", "cwd": str(work)})
                    self.assertIs(type(s), ClaudeSession)
                    self.assertEqual(s.config["nativeProtocol"], CLAUDE_STREAM_PROTOCOL)
                    self.assertEqual(s.config["protocolFallbackNotice"], "old flags")
            (state / "legacy-claude.json").write_text(json.dumps({"id": "legacy-claude", "cli": "Claude Code", "cwd": str(work)}))
            with patch("luma_cli.server.probe_claude_pty", return_value=(True, True, "", "/usr/bin/claude")), \
                 patch.object(ClaudeSession, "connect", lambda s: s.update(connected=True)):
                with Manager(state) as m:
                    legacy = m.connect({"id": "legacy-claude", "cli": "Claude Code", "cwd": str(work)})
                    self.assertIs(type(legacy), ClaudeSession)
                    self.assertNotIn("nativeProtocol", legacy.config)
            with patch("luma_cli.claude_pty.probe_claude_pty", return_value=(True, False, "old flags", "/usr/bin/claude")):
                with Manager(state) as m:
                    with self.assertRaisesRegex(Rejected, "old flags"):
                        m.connect({"id": "explicit-pty", "cli": "Claude Code", "cwd": str(work), "nativeProtocol": CLAUDE_PTY_PROTOCOL})

    def test_probe_claude_pty_checks_required_flags(self):
        with tempfile.TemporaryDirectory(prefix="luma-claude-probe-flag-") as tmp:
            bin_dir = pathlib.Path(tmp) / "bin"
            bin_dir.mkdir()
            good = bin_dir / "claude-good"
            good.write_text("#!" + sys.executable + "\nprint(" + repr(FULL_HELP) + ")\n")
            good.chmod(0o700)
            bad = bin_dir / "claude-bad"
            bad.write_text("#!" + sys.executable + "\nprint(" + repr(LEGACY_HELP) + ")\n")
            bad.chmod(0o700)
            self.assertEqual(probe_claude_pty(str(good))[:2], (True, True))
            self.assertEqual(probe_claude_pty(str(bad))[:2], (True, False))
            with patch("luma_cli.claude_pty.shutil.which", return_value=None):
                self.assertEqual(probe_claude_pty()[:2], (False, False))

    def test_hook_router_auth_and_end_to_end_claude_pty_session(self):
        with tempfile.TemporaryDirectory(prefix="luma-claude-e2e-") as tmp:
            base = pathlib.Path(tmp)
            work = base / "work"
            work.mkdir()
            state = base / "state"
            bin_dir = base / "bin"
            bin_dir.mkdir()
            transcript_file = base / "transcript.jsonl"
            fake_log = base / "fake-claude.log"
            fake_claude = bin_dir / "claude"
            fake_claude.write_text(
                "#!" + sys.executable + "\n"
                + f"""import json, os, shlex, subprocess, sys, time, urllib.request
args = sys.argv[1:]
if args == ["--help"]:
    print({FULL_HELP!r})
    sys.exit(0)
if "--print" in args:
    for line in sys.stdin:
        f = json.loads(line)
        req = f["request"]
        if req["subtype"] == "initialize":
            res = {{"models": [{{"value": "opus", "displayName": "Opus", "supportsEffort": True, "supportedEffortLevels": ["low", "high"]}}],
                    "commands": [{{"name": "compact"}}], "current_permission_mode": "default"}}
        elif req["subtype"] == "mcp_status":
            res = {{"mcpServers": [{{"name": "demo-mcp", "status": "connected"}}]}}
        else:
            res = {{}}
        print(json.dumps({{"type": "control_response", "response": {{"subtype": "success", "request_id": f["request_id"], "response": res}}}}), flush=True)
    sys.exit(0)
settings_path = args[args.index("--settings") + 1]
sid = args[args.index("--session-id") + 1] if "--session-id" in args else args[args.index("--resume") + 1]
settings = json.loads(open(settings_path).read())
start_cmd = settings["hooks"]["SessionStart"][0]["hooks"][0]["command"]
start_payload = {{"session_id": sid, "transcript_path": {str(transcript_file)!r}, "cwd": os.getcwd(), "hook_event_name": "SessionStart", "source": "startup", "model": "opus"}}
subprocess.run(["/bin/sh", "-c", start_cmd], input=json.dumps(start_payload).encode(), check=False)
hook_url = os.environ["LUMA_HOOK_URL"]
hook_token = os.environ["LUMA_HOOK_TOKEN"]
def post_hook(payload):
    req = urllib.request.Request(hook_url, data=json.dumps(payload).encode(), headers={{"Content-Type": "application/json", "Authorization": "Bearer " + hook_token}}, method="POST")
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode())
with open({str(fake_log)!r}, "a") as lg:
    lg.write("STARTED:" + sid + "\\n")
while True:
    line = sys.stdin.readline()
    if not line:
        break
    cleaned = line.replace("\\x1b[200~", "").replace("\\x1b[201~", "").strip()
    if not cleaned:
        continue
    with open({str(fake_log)!r}, "a") as lg:
        lg.write("IN:" + cleaned + "\\n")
    if cleaned.startswith("/model ") or cleaned.startswith("/effort "):
        continue
    post_hook({{"session_id": sid, "transcript_path": {str(transcript_file)!r}, "permission_mode": "default", "hook_event_name": "UserPromptSubmit", "prompt": cleaned}})
    u_id = "native-u-1"
    a_id = "native-a-1"
    with open({str(transcript_file)!r}, "a") as tf:
        tf.write(json.dumps({{"type": "user", "uuid": u_id, "parentUuid": None, "message": {{"role": "user", "content": [{{"type": "text", "text": cleaned}}]}}}}) + "\\n")
    if cleaned == "need-perm":
        post_hook({{"session_id": sid, "transcript_path": {str(transcript_file)!r}, "permission_mode": "default", "hook_event_name": "PreToolUse", "tool_name": "Bash", "tool_use_id": "tu-1", "tool_input": {{"command": "echo hi"}}}})
        dec = post_hook({{"session_id": sid, "transcript_path": {str(transcript_file)!r}, "permission_mode": "default", "hook_event_name": "PermissionRequest", "tool_name": "Bash", "tool_use_id": "tu-1", "tool_input": {{"command": "echo hi"}}}})
        with open({str(fake_log)!r}, "a") as lg:
            lg.write("DEC:" + json.dumps(dec) + "\\n")
        post_hook({{"session_id": sid, "transcript_path": {str(transcript_file)!r}, "permission_mode": "default", "hook_event_name": "PostToolUse", "tool_name": "Bash", "tool_use_id": "tu-1"}})
    elif cleaned == "need-question":
        q_in = {{"questions": [{{"question": "Pick one?", "header": "Choice", "options": [{{"label": "A"}}, {{"label": "B"}}], "multiSelect": False}}]}}
        dec = post_hook({{"session_id": sid, "transcript_path": {str(transcript_file)!r}, "permission_mode": "default", "hook_event_name": "PreToolUse", "tool_name": "AskUserQuestion", "tool_use_id": "tu-q1", "tool_input": q_in}})
        with open({str(fake_log)!r}, "a") as lg:
            lg.write("QDEC:" + json.dumps(dec) + "\\n")
        dup = post_hook({{"session_id": sid, "transcript_path": {str(transcript_file)!r}, "permission_mode": "default", "hook_event_name": "PermissionRequest", "tool_name": "AskUserQuestion", "tool_use_id": "tu-q1", "tool_input": q_in}})
        with open({str(fake_log)!r}, "a") as lg:
            lg.write("QDUP:" + json.dumps(dup) + "\\n")
    with open({str(transcript_file)!r}, "a") as tf:
        tf.write(json.dumps({{"type": "assistant", "uuid": a_id, "parentUuid": u_id, "message": {{"id": "msg-1", "role": "assistant", "model": "claude-opus-4-6", "usage": {{"input_tokens": 100, "cache_read_input_tokens": 900, "cache_creation_input_tokens": 0, "output_tokens": 50}}, "content": [{{"type": "text", "text": "Reply to " + cleaned}}]}}}}) + "\\n")
    post_hook({{"session_id": sid, "transcript_path": {str(transcript_file)!r}, "permission_mode": "default", "hook_event_name": "Stop"}})
"""
            )
            fake_claude.chmod(0o700)
            env_path = str(bin_dir) + os.pathsep + os.environ.get("PATH", "")
            with patch.dict(os.environ, {"PATH": env_path}), \
                 patch("luma_cli.claude.os.geteuid", return_value=1000):
                with Manager(state) as m:
                    s = m.connect({"id": "c1", "cli": "Claude Code", "cwd": str(work)})
                    try:
                        self.assertIs(type(s), ClaudePtySession)
                        self.assertTrue(s.data["connected"])
                        self.assertTrue(s.data["canOpenTui"])
                        self.assertEqual(s.data["nativeProtocol"], CLAUDE_PTY_PROTOCOL)
                        self.assertEqual(s.data["model"], "opus")

                        status_401, _ = _post_json(s.hook_url, {}, {"Authorization": "Bearer wrong"})
                        self.assertEqual(status_401, 401)
                        status_403, _ = _post_json(s.hook_url, {}, {"Authorization": "Bearer " + s.hook_token, "Origin": "https://evil.example"})
                        self.assertEqual(status_403, 403)

                        self.assertTrue(_wait_for(lambda: any(m_item["id"] == "opus" for m_item in s.data["models"])))
                        self.assertTrue(_wait_for(lambda: any(x["name"] == "demo-mcp" for x in s.data["mcp"])))

                        s.admit({"submissionId": "sub-1", "text": "need-perm"})
                        self.assertTrue(_wait_for(lambda: len(s.data["requests"]) == 1))
                        req = s.data["requests"][0]
                        self.assertEqual(req["title"], "Bash")
                        s.answer({"id": req["id"], "choice": "allow"})
                        self.assertTrue(_wait_for(lambda: not s.data["running"] and any("Reply to need-perm" in r["text"] for r in s.rows.values())))
                        self.assertEqual(s.config["receipts"]["sub-1"], "accepted")
                        self.assertTrue(s.config["consumedSubmissions"]["sub-1"])
                        user_rows = [r for r in s.rows.values() if r["role"] == "user"]
                        self.assertEqual(len(user_rows), 1)
                        self.assertEqual(user_rows[0]["id"], "sub-1:0")
                        self.assertIn('DEC:{"hookSpecificOutput": {"hookEventName": "PermissionRequest", "decision": {"behavior": "allow"}}}', fake_log.read_text())
                        self.assertTrue(any(metric["label"] in ("上下文", "Context") for metric in s.data["metrics"]))

                        s.admit({"submissionId": "sub-2", "text": "need-question"})
                        self.assertTrue(_wait_for(lambda: len(s.data["requests"]) == 1))
                        qreq = s.data["requests"][0]
                        self.assertEqual(qreq["title"], "Answer")
                        s.answer({"id": qreq["id"], "choice": "allow", "answers": {"0": ["\u0000raw:CustomX"]}})
                        self.assertTrue(_wait_for(lambda: not s.data["running"] and "QDUP:{}" in fake_log.read_text()))
                        log_text = fake_log.read_text()
                        self.assertIn('"permissionDecision": "allow"', log_text)
                        self.assertIn('"Pick one?": "CustomX"', log_text)

                        s.configure({"model": "opus", "effort": "high"})
                        self.assertEqual(s.data["effort"], "high")
                        self.assertTrue(_wait_for(lambda: "IN:/effort high" in fake_log.read_text()))
                        with self.assertRaises(Rejected):
                            s.configure({"permission": "plan"})
                    finally:
                        m.destroy("c1", {"deleteState": True})

    def test_terminal_open_and_foreground_handoff_and_identity_fence(self):
        with tempfile.TemporaryDirectory(prefix="luma-claude-handoff-") as tmp:
            config = {"id": "c2", "cli": "Claude Code", "cwd": tmp, "ownerDirectory": tmp + "/owner", "nativeId": NATIVE_UUID}
            s = ClaudePtySession(config, lambda: None)
            s.owner = NativeOwner(s)
            s.owner.launch_tui(["/bin/sh", "-c", "sleep 30"], host_kind="pty-tui", binary_name="sh")
            s.update(connected=True, canOpenTui=True, nativeId=NATIVE_UUID, nativeProtocol=CLAUDE_PTY_PROTOCOL)
            try:
                holder = {}
                def run_perm():
                    holder["result"] = s.on_hook({
                        "session_id": NATIVE_UUID,
                        "hook_event_name": "PermissionRequest",
                        "tool_name": "Bash",
                        "tool_use_id": "tu-h1",
                        "tool_input": {"command": "ls"},
                    })
                t = threading.Thread(target=run_perm, daemon=True)
                t.start()
                self.assertTrue(_wait_for(lambda: len(s.data["requests"]) == 1))
                term = s.terminal()
                self.assertEqual(term["nativeId"], NATIVE_UUID)
                t.join(timeout=3)
                self.assertEqual(holder.get("result"), {})
                self.assertEqual(s.data["requests"], [])

                fg_file = pathlib.Path(tmp) / "foreground-terminal.json"
                fg_file.write_text(json.dumps({
                    "sessionId": "c2",
                    "updatedAt": int(time.time() * 1000),
                }))
                immediate = s.on_hook({
                    "session_id": NATIVE_UUID,
                    "hook_event_name": "PermissionRequest",
                    "tool_name": "Bash",
                    "tool_use_id": "tu-h2",
                    "tool_input": {"command": "pwd"},
                })
                self.assertEqual(immediate, {})
                self.assertEqual(s.data["requests"], [])

                s.on_hook({"session_id": OTHER_UUID, "hook_event_name": "SessionStart", "source": "clear"})
                self.assertFalse(s.data["connected"])
                self.assertFalse(s.data["canOpenTui"])
                self.assertEqual(s.config["nativeId"], NATIVE_UUID)
            finally:
                stop_recorded_owner(config)

    def test_startup_gate_keeps_owner_alive_and_connects_after_session_start(self):
        with tempfile.TemporaryDirectory(prefix="luma-claude-gate-") as tmp:
            base = pathlib.Path(tmp)
            work = base / "work"
            work.mkdir()
            state = base / "state"
            bin_dir = base / "bin"
            bin_dir.mkdir()
            fake_claude = bin_dir / "claude"
            fake_claude.write_text(
                "#!" + sys.executable + "\n"
                + f"""import sys, time
if sys.argv[1:] == ["--help"]:
    print({FULL_HELP!r})
    sys.exit(0)
print("Do you trust the files in this folder? Yes, proceed", flush=True)
time.sleep(30)
"""
            )
            fake_claude.chmod(0o700)
            env_path = str(bin_dir) + os.pathsep + os.environ.get("PATH", "")
            with patch.dict(os.environ, {"PATH": env_path}), \
                 patch("luma_cli.claude.os.geteuid", return_value=1000), \
                 patch.object(ClaudePtySession, "startup_wait_sec", 0.5):
                with Manager(state) as m:
                    s = m.connect({"id": "g1", "cli": "Claude Code", "cwd": str(work)})
                    try:
                        self.assertIs(type(s), ClaudePtySession)
                        self.assertFalse(s.data["connected"])
                        self.assertTrue(s.data["canOpenTui"])
                        self.assertTrue(s.owner.alive())
                        self.assertIn("Dashboard", s.data["error"])
                        self.assertFalse(pty_owner_settled(s.owner.directory, 0))
                        s.on_hook({
                            "session_id": s.data["nativeId"],
                            "transcript_path": str(base / "t.jsonl"),
                            "hook_event_name": "SessionStart",
                            "source": "startup",
                            "model": "opus",
                        })
                        self.assertTrue(s.data["connected"])
                        self.assertTrue(s.data["canOpenTui"])
                        self.assertIsNone(s.data["error"])
                    finally:
                        m.destroy("g1", {"deleteState": True})

    def test_stop_failure_and_escape_interrupt_settle_turn_and_unblock_send_and_release(self):
        hooks = _build_claude_hooks_settings("http://127.0.0.1:9999/hooks/s1", "relay")["hooks"]
        self.assertIn("StopFailure", hooks)
        self.assertEqual(hooks["StopFailure"][0]["hooks"][0]["type"], "http")

        with tempfile.TemporaryDirectory(prefix="luma-claude-settle-") as tmp:
            base = pathlib.Path(tmp)
            transcript = base / "session.jsonl"
            transcript.write_text("", encoding="utf-8")
            owner_dir = base / "owner"
            owner_dir.mkdir()

            class FakeOwner:
                def __init__(self):
                    self.directory = owner_dir
                    self.sent_keys = []
                    self.injected = []
                    self.pane_text = ""
                    self.stop_calls = []

                def alive(self):
                    return True

                def has_attached_client(self):
                    return False

                def pane_pid(self):
                    return None

                def capture_pane(self, lines=20):
                    return self.pane_text

                def send_keys(self, *keys, literal=False):
                    self.sent_keys.append(keys)

                def inject_text(self, body, submit=True, submit_delay=0.12):
                    self.injected.append(body)

                def write_runtime_state(self, **state):
                    (owner_dir / "runtime-state.json").write_text(json.dumps(state), encoding="utf-8")

                def stop(self, force=False):
                    self.stop_calls.append(force)
                    return True

            config = {
                "id": "s-settle",
                "cli": "Claude Code",
                "cwd": str(base),
                "nativeId": NATIVE_UUID,
                "nativeProtocol": CLAUDE_PTY_PROTOCOL,
            }
            s = ClaudePtySession(config, lambda: None)
            s.owner = FakeOwner()
            s.peer = PtyOwnerPeer(s.owner, lambda: None)
            s._transcript_path = str(transcript)
            s.interrupt_settle_grace_sec = 0.05
            s.interrupt_settle_wait_sec = 0.3
            s.prompt_confirm_wait_sec = 0.0
            s.update(connected=True, canOpenTui=True, historyReady=True, nativeProtocol=CLAUDE_PTY_PROTOCOL)

            s.prompt({"submissionId": "sub-api", "text": "hello api"})
            s.on_hook({"session_id": NATIVE_UUID, "hook_event_name": "UserPromptSubmit", "prompt": "hello api"})
            self.assertTrue(s.data["running"])
            self.assertEqual(s.current_submission, "sub-api")

            s.on_hook({
                "session_id": NATIVE_UUID,
                "hook_event_name": "StopFailure",
                "error": "rate_limit",
                "error_details": "Rate limit exceeded",
            })
            self.assertFalse(s.data["running"])
            self.assertIsNone(s.current_submission)
            self.assertEqual(s.data["error"], "Rate limit exceeded")
            self.assertIsNone(s.release_blocker())
            s.validate_prompt({"submissionId": "sub-next", "text": "after api error"})

            s.prompt({"submissionId": "sub-esc", "text": "hello esc"})
            s.on_hook({"session_id": NATIVE_UUID, "hook_event_name": "UserPromptSubmit", "prompt": "hello esc"})
            self.assertTrue(s.data["running"])
            s.stop()
            self.assertIn(("Escape",), s.owner.sent_keys)
            self.assertFalse(s.data["running"])
            self.assertIsNone(s.current_submission)
            self.assertIsNone(s.release_blocker())
            s.validate_prompt({"submissionId": "sub-after-esc", "text": "after esc"})

            s.prompt({"submissionId": "sub-tool", "text": "run bash"})
            s.on_hook({"session_id": NATIVE_UUID, "hook_event_name": "UserPromptSubmit", "prompt": "run bash"})
            s.on_hook({"session_id": NATIVE_UUID, "hook_event_name": "PreToolUse", "tool_name": "Bash", "tool_use_id": "tu-1", "tool_input": {"command": "sleep 10"}})
            s.on_hook({"session_id": NATIVE_UUID, "hook_event_name": "PostToolUseFailure", "tool_name": "Bash", "tool_use_id": "tu-1", "error": "interrupted", "is_interrupt": True})
            self.assertFalse(s.data["running"])
            self.assertIsNone(s.current_submission)
            self.assertEqual(s.rows["tool-tu-1"]["status"], "cancelled")
            self.assertIsNone(s.release_blocker())

            s.prompt({"submissionId": "sub-tui-esc", "text": "manual tui esc"})
            s.on_hook({"session_id": NATIVE_UUID, "hook_event_name": "UserPromptSubmit", "prompt": "manual tui esc"})
            self.assertTrue(s.data["running"])
            with transcript.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps({
                    "type": "user",
                    "uuid": "u-int-1",
                    "sessionId": NATIVE_UUID,
                    "message": {"role": "user", "content": [{"type": "text", "text": "[Request interrupted by user]"}]},
                }) + "\n")
            s.sync_transcript()
            self.assertFalse(s.data["running"])
            self.assertIsNone(s.current_submission)
            self.assertIsNone(s.release_blocker())

            s.prompt({"submissionId": "sub-after-hist", "text": "new turn"})
            s.on_hook({"session_id": NATIVE_UUID, "hook_event_name": "UserPromptSubmit", "prompt": "new turn"})
            s.sync_transcript()
            self.assertTrue(s.data["running"])
            self.assertEqual(s.current_submission, "sub-after-hist")

    def test_session_crons_and_background_tasks_block_release_until_cleared(self):
        with tempfile.TemporaryDirectory(prefix="luma-claude-crons-") as tmp:
            base = pathlib.Path(tmp)
            transcript = base / "session.jsonl"
            transcript.write_text("", encoding="utf-8")
            owner_dir = base / "owner"
            owner_dir.mkdir()

            class FakeOwner:
                def __init__(self):
                    self.directory = owner_dir
                    self.stop_calls = []

                def alive(self):
                    return True

                def has_attached_client(self):
                    return False

                def pane_pid(self):
                    return None

                def write_runtime_state(self, **state):
                    (owner_dir / "runtime-state.json").write_text(json.dumps(state), encoding="utf-8")

                def stop(self, force=False):
                    self.stop_calls.append(force)
                    return True

            config = {
                "id": "s-cron",
                "cli": "Claude Code",
                "cwd": str(base),
                "nativeId": NATIVE_UUID,
                "nativeProtocol": CLAUDE_PTY_PROTOCOL,
            }
            s = ClaudePtySession(config, lambda: None)
            s.owner = FakeOwner()
            s.peer = PtyOwnerPeer(s.owner, lambda: None)
            s._transcript_path = str(transcript)
            s.update(connected=True, canOpenTui=True, historyReady=True, nativeProtocol=CLAUDE_PTY_PROTOCOL)

            s.current_submission = "sub-1"
            s.update(running=True)
            s.on_hook({
                "session_id": NATIVE_UUID,
                "hook_event_name": "Stop",
                "session_crons": [{"id": "cron-1", "schedule": "*/5 * * * *", "recurring": True, "prompt": "ping"}],
                "background_tasks": [],
            })
            self.assertFalse(s.data["running"])
            self.assertIsNone(s.current_submission)
            s.validate_prompt({"submissionId": "sub-2", "text": "still interactive"})
            self.assertIsNotNone(s.release_blocker())
            self.assertFalse(pty_owner_settled(owner_dir, 0))
            with self.assertRaises(Conflict):
                s.release_resources(NATIVE_UUID, CLAUDE_PTY_PROTOCOL)
            self.assertEqual(s.owner.stop_calls, [])

            s.on_hook({
                "session_id": NATIVE_UUID,
                "hook_event_name": "Stop",
                "session_crons": [],
                "background_tasks": [{"id": "bg-1", "type": "local_bash", "status": "running"}],
            })
            self.assertIsNotNone(s.release_blocker())
            self.assertFalse(pty_owner_settled(owner_dir, 0))

            s.on_hook({
                "session_id": NATIVE_UUID,
                "hook_event_name": "Stop",
                "session_crons": [],
                "background_tasks": [{"id": "bg-1", "type": "local_bash", "status": "completed"}],
            })
            self.assertIsNone(s.release_blocker())
            self.assertTrue(pty_owner_settled(owner_dir, 0))

    def test_catalog_probe_timeout_breaks_silent_hang_and_kills_process_group_and_dedupes_refresh(self):
        with tempfile.TemporaryDirectory(prefix="luma-claude-cat-timeout-") as tmp:
            base = pathlib.Path(tmp)
            pid_file = base / "child.pid"
            silent_bin = base / "silent-claude"
            silent_bin.write_text(
                "#!" + sys.executable + "\n"
                + f"""import pathlib, subprocess, sys, time
child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
pathlib.Path({str(pid_file)!r}).write_text(str(child.pid), encoding="utf-8")
sys.stdin.readline()
sys.stdout.write("{{")
sys.stdout.flush()
time.sleep(30)
"""
            )
            silent_bin.chmod(0o700)

            t0 = time.monotonic()
            result = _probe_claude_catalog(str(silent_bin), str(base), force=True, timeout=0.3)
            elapsed = time.monotonic() - t0
            self.assertIsNone(result)
            self.assertLess(elapsed, 2.5)
            if pid_file.is_file():
                grandchild_pid = int(pid_file.read_text(encoding="utf-8").strip())
                deadline = time.monotonic() + 1.5
                alive = True
                while time.monotonic() < deadline:
                    try:
                        os.kill(grandchild_pid, 0)
                        time.sleep(0.05)
                    except ProcessLookupError:
                        alive = False
                        break
                self.assertFalse(alive)

            gate = threading.Event()
            started = threading.Event()
            calls = []

            def slow_probe(binary, cwd, force=False, timeout=None):
                calls.append(force)
                started.set()
                gate.wait(2.0)
                return None

            s = ClaudePtySession({"id": "s-cat", "cli": "Claude Code", "cwd": str(base)}, lambda: None)
            s._binary = str(silent_bin)
            with patch("luma_cli.claude_pty._probe_claude_catalog", side_effect=slow_probe):
                for _ in range(4):
                    s._refresh_catalog_async(force=False)
                self.assertTrue(started.wait(1.0))
                self.assertEqual(len(calls), 1)
                gate.set()
                deadline = time.monotonic() + 1.5
                while time.monotonic() < deadline and s._catalog_refresh_inflight:
                    time.sleep(0.02)
                self.assertFalse(s._catalog_refresh_inflight)
                self.assertEqual(len(calls), 1)

    def test_local_command_entries_empty_thinking_and_context_window_projection(self):
        self.assertEqual(model_context_window("deepseek-flash"), 1_000_000)
        self.assertEqual(model_context_window("deepseek-flash[1m]"), 1_000_000)
        self.assertEqual(model_context_window("deepseek-v4-pro"), 1_000_000)
        self.assertEqual(model_context_window("claude-sonnet-4-6[1m]"), 1_000_000)
        self.assertEqual(model_context_window("deepseek-chat"), 128_000)
        ctx_rows = claude_context(
            {"model": "deepseek-flash", "usage": {"input_tokens": 20000, "cache_read_input_tokens": 0, "cache_creation_input_tokens": 0}},
            {},
            fallback_model="deepseek-flash[1m]",
        )
        self.assertEqual(ctx_rows[0]["value"], "20000 / 1000000 · 2.0%")

        with tempfile.TemporaryDirectory(prefix="luma-claude-noise-") as tmp:
            base = pathlib.Path(tmp)
            transcript = base / "session.jsonl"
            entries = [
                {"type": "permission-mode", "permissionMode": "auto", "sessionId": NATIVE_UUID},
                {"type": "user", "uuid": "u1", "parentUuid": None, "sessionId": NATIVE_UUID,
                 "message": {"role": "user", "content": "你好"}},
                {"type": "assistant", "uuid": "a1", "parentUuid": "u1", "sessionId": NATIVE_UUID,
                 "message": {"role": "assistant", "id": "m1", "model": "deepseek-flash",
                             "usage": {"input_tokens": 20000, "cache_read_input_tokens": 0, "cache_creation_input_tokens": 0, "output_tokens": 10},
                             "content": [{"type": "thinking", "thinking": ""}, {"type": "text", "text": "你好！"}]}},
                {"type": "user", "uuid": "c1", "parentUuid": "a1", "promptId": "p-cmd", "isMeta": True, "sessionId": NATIVE_UUID,
                 "message": {"role": "user", "content": "<local-command-caveat>caveat</local-command-caveat>"}},
                {"type": "user", "uuid": "c2", "parentUuid": "c1", "promptId": "p-cmd", "sessionId": NATIVE_UUID,
                 "message": {"role": "user", "content": "<command-name>/model</command-name>\n<command-message>model</command-message>\n<command-args>sonnet</command-args>"}},
                {"type": "user", "uuid": "c3", "parentUuid": "c2", "promptId": "p-cmd", "sessionId": NATIVE_UUID,
                 "message": {"role": "user", "content": "<local-command-stdout>Set model to `deepseek-flash[1m]`</local-command-stdout>"}},
                {"type": "user", "uuid": "u2", "parentUuid": "c3", "sessionId": NATIVE_UUID,
                 "message": {"role": "user", "content": "继续"}},
                {"type": "assistant", "uuid": "a2", "parentUuid": "u2", "sessionId": NATIVE_UUID,
                 "message": {"role": "assistant", "id": "m2", "model": "deepseek-flash",
                             "usage": {"input_tokens": 200, "cache_read_input_tokens": 20000, "cache_creation_input_tokens": 0, "output_tokens": 15},
                             "content": [{"type": "thinking", "thinking": "brief thought"}, {"type": "text", "text": "好的"}]}},
            ]
            transcript.write_text("\n".join(json.dumps(e, ensure_ascii=False) for e in entries) + "\n", encoding="utf-8")
            s = ClaudePtySession({"id": "s-noise", "cli": "Claude Code", "cwd": str(base), "nativeId": NATIVE_UUID}, lambda: None)
            s._transcript_path = str(transcript)
            s._session_model = "deepseek-flash[1m]"
            s.update(permission="plan")
            s.load_history()
            texts = [(r["role"], r["text"]) for r in s.rows.values()]
            self.assertEqual(texts, [
                ("user", "你好"),
                ("assistant", "你好！"),
                ("user", "继续"),
                ("reasoning", "brief thought"),
                ("assistant", "好的"),
            ])
            self.assertEqual(s.data["permission"], "plan")
            self.assertTrue(any("1000000" in m["value"] for m in s.data["metrics"]))

    def test_fake_subagents_ignored_real_agent_anchored_and_permission_btab_sync(self):
        self.assertEqual(_parse_pane_permission_mode("hello\n──────────\n  ⏵⏵ accept edits on (shift+tab to cycle)"), "acceptEdits")
        self.assertEqual(_parse_pane_permission_mode("plan mode on in chat\n──────────\n  ⏸ manual mode on"), "default")
        self.assertEqual(_parse_pane_permission_mode("──────────\n  ⏸ plan mode on (shift+tab to cycle)"), "plan")
        self.assertEqual(_parse_pane_permission_mode("──────────\n  ⏵⏵ auto mode on (shift+tab to cycle)"), "auto")

        with tempfile.TemporaryDirectory(prefix="luma-claude-sub-perm-") as tmp:
            base = pathlib.Path(tmp)
            owner_dir = base / "owner"
            owner_dir.mkdir()
            cycle = ["default", "acceptEdits", "plan", "auto"]
            footers = {
                "default": "──────────────\n  ⏸ manual mode on",
                "acceptEdits": "──────────────\n  ⏵⏵ accept edits on (shift+tab to cycle)",
                "plan": "──────────────\n  ⏸ plan mode on (shift+tab to cycle)",
                "auto": "──────────────\n  ⏵⏵ auto mode on (shift+tab to cycle)",
            }

            class FakeOwner:
                def __init__(self):
                    self.directory = owner_dir
                    self.idx = 0

                def alive(self):
                    return True

                def has_attached_client(self):
                    return False

                def pane_pid(self):
                    return None

                def capture_pane(self, lines=12):
                    return footers[cycle[self.idx]]

                def send_keys(self, *keys, literal=False):
                    if keys == ("BTab",):
                        self.idx = (self.idx + 1) % len(cycle)

                def write_runtime_state(self, **state):
                    (owner_dir / "runtime-state.json").write_text(json.dumps(state), encoding="utf-8")

            s = ClaudePtySession({"id": "s-sp", "cli": "Claude Code", "cwd": str(base), "nativeId": NATIVE_UUID}, lambda: None)
            s.owner = FakeOwner()
            s.permission_modes = [
                {"id": "default", "label": "Default"},
                {"id": "acceptEdits", "label": "Accept edits"},
                {"id": "auto", "label": "Auto"},
                {"id": "plan", "label": "Plan"},
                {"id": "dontAsk", "label": "Don't ask"},
            ]
            s.permission_cycle_step_timeout_sec = 0.2
            s.update(connected=True, permission="default")

            s.on_hook({"session_id": NATIVE_UUID, "hook_event_name": "SubagentStop", "agent_id": "ghost-1"})
            s.on_hook({"session_id": NATIVE_UUID, "hook_event_name": "SubagentStart", "agent_id": "ghost-2", "agent_type": "memory"})
            self.assertEqual(s.subagents, {})

            s.on_hook({
                "session_id": NATIVE_UUID,
                "prompt_id": "p1",
                "hook_event_name": "PreToolUse",
                "tool_name": "Agent",
                "tool_use_id": "tu-agent-1",
                "tool_input": {"description": "List files", "subagent_type": "Explore"},
            })
            s.on_hook({
                "session_id": NATIVE_UUID,
                "prompt_id": "p1",
                "hook_event_name": "SubagentStart",
                "agent_id": "real-sub-1",
                "agent_type": "Explore",
            })
            s.on_hook({
                "session_id": NATIVE_UUID,
                "prompt_id": "p1",
                "hook_event_name": "PreToolUse",
                "agent_id": "real-sub-1",
                "tool_name": "Bash",
                "tool_use_id": "tu-sub-bash",
                "tool_input": {"command": "ls"},
            })
            self.assertNotIn("tool-tu-sub-bash", s.rows)
            self.assertIn("real-sub-1", s.subagents)
            self.assertEqual(s.subagents["real-sub-1"]["itemId"], "tool-tu-agent-1")
            self.assertEqual(s.subagents["real-sub-1"]["role"], "Explore")
            self.assertEqual(s.subagents["real-sub-1"]["task"], "List files")

            s.on_hook({
                "session_id": NATIVE_UUID,
                "prompt_id": "p1",
                "hook_event_name": "SubagentStop",
                "agent_id": "real-sub-1",
                "last_assistant_message": "Found 3 files",
            })
            self.assertEqual(s.subagents["real-sub-1"]["status"], "succeeded")
            self.assertEqual(s.subagents["real-sub-1"]["detail"], "Found 3 files")
            s.on_hook({"session_id": NATIVE_UUID, "hook_event_name": "Stop", "effort": {"level": "high"}})
            self.assertEqual(s.data["effort"], "high")

            s.configure({"permission": "plan"})
            self.assertEqual(s.data["permission"], "plan")
            self.assertEqual(cycle[s.owner.idx], "plan")

            s.owner.idx = 3
            s._sync_pane_permission(force=True)
            self.assertEqual(s.data["permission"], "auto")

            with self.assertRaises(Rejected):
                s.configure({"permission": "dontAsk"})
            self.assertEqual(cycle[s.owner.idx], "auto")

    def test_tui_confirm_notice_captured_as_gui_request_and_answered_via_enter_or_escape(self):
        pane_sample = "\n".join([
            "● Running 2 agents…",
            "──────────────────────────────────────────────",
            "  We're changing auto mode to no longer charge for classifier requests.",
            "",
            "  Auto mode works for now.",
            "",
            "  Enter to continue · Esc to cancel",
        ])
        body, has_cancel = _extract_tui_confirm_dialog(pane_sample)
        self.assertIn("We're changing auto mode", body)
        self.assertTrue(has_cancel)

        with tempfile.TemporaryDirectory(prefix="luma-claude-tui-prompt-") as tmp:
            base = pathlib.Path(tmp)
            owner_dir = base / "owner"
            owner_dir.mkdir()

            class FakeOwner:
                def __init__(self):
                    self.directory = owner_dir
                    self.pane_text = pane_sample
                    self.sent_keys = []

                def alive(self):
                    return True

                def has_attached_client(self):
                    return False

                def pane_pid(self):
                    return None

                def capture_pane(self, lines=30):
                    return self.pane_text

                def send_keys(self, *keys, literal=False):
                    self.sent_keys.append(keys)

                def write_runtime_state(self, **state):
                    (owner_dir / "runtime-state.json").write_text(json.dumps(state), encoding="utf-8")

            s = ClaudePtySession({"id": "s-tp", "cli": "Claude Code", "cwd": str(base), "nativeId": NATIVE_UUID}, lambda: None)
            s.owner = FakeOwner()
            s.update(connected=True, running=True)

            s.on_hook({
                "session_id": NATIVE_UUID,
                "hook_event_name": "Notification",
                "notification_type": "agent_needs_input",
                "message": "Auto mode has a classifier billing notice to acknowledge",
            })
            self.assertEqual(len(s.data["requests"]), 1)
            req = s.data["requests"][0]
            self.assertIn("We're changing auto mode", req["body"])
            self.assertEqual([c["id"] for c in req["choices"]], ["allow", "deny"])
            self.assertFalse(pty_owner_settled(owner_dir, 0))

            s.answer({"id": req["id"], "choice": "allow"})
            self.assertEqual(s.owner.sent_keys[-1], ("Enter",))
            self.assertEqual(s.data["requests"], [])

            s._last_tui_prompt_scan = 0.0
            s.on_tail_tick()
            self.assertEqual(len(s.data["requests"]), 1)
            req2 = s.data["requests"][0]
            s.answer({"id": req2["id"], "choice": "deny"})
            self.assertEqual(s.owner.sent_keys[-1], ("Escape",))
            self.assertEqual(s.data["requests"], [])

            s._last_tui_prompt_scan = 0.0
            s.on_tail_tick()
            self.assertEqual(len(s.data["requests"]), 1)
            s.handoff_requests_to_tui()
            self.assertEqual(s.data["requests"], [])

    def test_pty_permissions_match_native_tui_four_modes_stop_clears_unknown_and_subagent_stream(self):
        with tempfile.TemporaryDirectory(prefix="luma-claude-4perm-sub-") as tmp:
            base = pathlib.Path(tmp)
            owner_dir = base / "owner"
            owner_dir.mkdir()
            transcript = base / f"{NATIVE_UUID}.jsonl"
            sub_dir = base / NATIVE_UUID / "subagents"
            sub_dir.mkdir(parents=True)
            sub_file = sub_dir / "agent-sub42.jsonl"

            class FakeOwner:
                def __init__(self):
                    self.directory = owner_dir
                    self.pane_text = "────────────\n❯ 测试权限审批\n────────────\n  ⏸ manual mode on"
                    self.sent_keys = []

                def alive(self):
                    return True

                def has_attached_client(self):
                    return False

                def pane_pid(self):
                    return None

                def capture_pane(self, lines=20):
                    return self.pane_text

                def send_keys(self, *keys, literal=False):
                    self.sent_keys.append(keys)
                    if keys in (("Escape",), ("C-u",)):
                        self.pane_text = "────────────\n❯ \n────────────\n  ⏸ manual mode on"

                def inject_text(self, body, submit=True, submit_delay=0.12):
                    pass

                def write_runtime_state(self, **state):
                    (owner_dir / "runtime-state.json").write_text(json.dumps(state), encoding="utf-8")

            s = ClaudePtySession(
                {
                    "id": "s-all",
                    "cli": "Claude Code",
                    "cwd": str(base),
                    "nativeId": NATIVE_UUID,
                    "nativeProtocol": CLAUDE_PTY_PROTOCOL,
                },
                lambda: None,
            )
            fake_help = subprocess.CompletedProcess(["claude", "--help"], 0, FULL_HELP, "")
            with patch("luma_cli.claude.subprocess.run", return_value=fake_help), \
                 patch("luma_cli.claude.os.geteuid", return_value=0):
                s.detect_permissions()
            self.assertEqual([m["id"] for m in s.permission_modes], ["default", "acceptEdits", "plan", "auto"])
            with patch("luma_cli.claude.subprocess.run", return_value=fake_help), \
                 patch("luma_cli.claude.os.geteuid", return_value=1000):
                s.detect_permissions()
            self.assertEqual([m["id"] for m in s.permission_modes], ["default", "acceptEdits", "plan", "auto", "bypassPermissions"])

            s.owner = FakeOwner()
            s.peer = PtyOwnerPeer(s.owner, lambda: None)
            s._transcript_path = str(transcript)
            s.interrupt_settle_grace_sec = 0.05
            s.interrupt_settle_wait_sec = 0.25
            s.prompt_confirm_wait_sec = 0.0
            s.update(connected=True, canOpenTui=True, historyReady=True, nativeProtocol=CLAUDE_PTY_PROTOCOL)

            s.config["deliveries"] = {"sub-stuck": {"state": "unknown", "payload": {"submissionId": "sub-stuck", "text": "测试权限审批"}}}
            s.config["receipts"] = {"sub-stuck": "unknown"}
            s.current_submission = "sub-stuck"
            s._pending_submission = {"id": "sub-stuck", "text": "测试权限审批", "ts": time.monotonic()}
            s.update(running=True)
            s.stop()
            self.assertFalse(s.data["running"])
            self.assertEqual(s.config["deliveries"]["sub-stuck"]["state"], "rejected")
            self.assertEqual(s.config["receipts"]["sub-stuck"], "rejected")
            self.assertIsNone(s.release_blocker())

            s.on_hook({
                "session_id": NATIVE_UUID,
                "prompt_id": "p-sub",
                "hook_event_name": "PreToolUse",
                "tool_name": "Agent",
                "tool_use_id": "tu-ag1",
                "tool_input": {"description": "Check repo", "subagent_type": "Explore"},
            })
            s.on_hook({
                "session_id": NATIVE_UUID,
                "prompt_id": "p-sub",
                "hook_event_name": "SubagentStart",
                "agent_id": "sub42",
                "agent_type": "Explore",
            })
            s.on_hook({
                "session_id": NATIVE_UUID,
                "prompt_id": "p-sub",
                "hook_event_name": "PreToolUse",
                "agent_id": "sub42",
                "tool_name": "Bash",
                "tool_use_id": "tu-inner-1",
                "tool_input": {"command": "uname -a"},
            })
            s.on_hook({
                "session_id": NATIVE_UUID,
                "prompt_id": "p-sub",
                "hook_event_name": "PostToolUse",
                "agent_id": "sub42",
                "tool_name": "Bash",
                "tool_use_id": "tu-inner-1",
                "tool_response": {"stdout": "Linux arm64"},
            })
            self.assertNotIn("tool-tu-inner-1", s.rows)
            live_msgs = s.subagents["sub42"].get("messages", [])
            self.assertTrue(any(m["id"] == "tool-tu-inner-1" and m["status"] == "completed" and "Linux arm64" in m["outcome"] for m in live_msgs))

            sub_entries = [
                {"type": "user", "uuid": "su1", "isSidechain": True, "agentId": "sub42",
                 "message": {"role": "user", "content": "Run uname -a"}},
                {"type": "user", "uuid": "su2", "isSidechain": True, "isMeta": True, "agentId": "sub42",
                 "message": {"role": "user", "content": "<system-reminder>hidden</system-reminder>"}},
                {"type": "assistant", "uuid": "sa1", "isSidechain": True, "agentId": "sub42",
                 "message": {"role": "assistant", "id": "sm1", "model": "deepseek-flash",
                             "content": [{"type": "tool_use", "id": "tu-inner-1", "name": "Bash", "input": {"command": "uname -a"}}]}},
                {"type": "user", "uuid": "sr1", "isSidechain": True, "agentId": "sub42",
                 "message": {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "tu-inner-1", "content": "Linux arm64"}]}},
                {"type": "assistant", "uuid": "sa2", "isSidechain": True, "agentId": "sub42",
                 "message": {"role": "assistant", "id": "sm2", "model": "deepseek-flash",
                             "content": [{"type": "tool_use", "id": "tu-hb", "name": "SubagentHandback", "input": {"message": "Done: Linux arm64"}}]}},
                {"type": "user", "uuid": "sr2", "isSidechain": True, "agentId": "sub42",
                 "message": {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "tu-hb", "content": "{\"success\":true}"}]}},
            ]
            sub_file.write_text("\n".join(json.dumps(e, ensure_ascii=False) for e in sub_entries) + "\n", encoding="utf-8")
            s.on_hook({
                "session_id": NATIVE_UUID,
                "hook_event_name": "SubagentStop",
                "agent_id": "sub42",
                "agent_transcript_path": str(sub_file),
                "last_assistant_message": "Done: Linux arm64",
            })
            final_msgs = s.subagents["sub42"]["messages"]
            self.assertEqual(s.subagents["sub42"]["model"], "deepseek-flash")
            self.assertTrue(any(m["role"] == "tool" and m["toolName"] == "Bash" and m["outcome"] == "Linux arm64" for m in final_msgs))
            self.assertTrue(any(m["role"] == "assistant" and m["text"] == "Done: Linux arm64" for m in final_msgs))
            self.assertFalse(any(m.get("toolName") == "SubagentHandback" for m in final_msgs))

            main_entries = [
                {"type": "user", "uuid": "mu1", "parentUuid": None, "sessionId": NATIVE_UUID,
                 "message": {"role": "user", "content": "check"}},
                {"type": "assistant", "uuid": "ma1", "parentUuid": "mu1", "sessionId": NATIVE_UUID,
                 "message": {"role": "assistant", "id": "mm1", "model": "deepseek-flash",
                             "content": [{"type": "tool_use", "id": "tu-ag1", "name": "Agent", "input": {"description": "Check repo", "subagent_type": "Explore"}}]}},
                {"type": "user", "uuid": "mr1", "parentUuid": "ma1", "sessionId": NATIVE_UUID,
                 "message": {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "tu-ag1", "content": "Async agent launched. agentId: sub42"}]}},
            ]
            transcript.write_text("\n".join(json.dumps(e, ensure_ascii=False) for e in main_entries) + "\n", encoding="utf-8")
            s2 = ClaudePtySession({"id": "s-reload", "cli": "Claude Code", "cwd": str(base), "nativeId": NATIVE_UUID}, lambda: None)
            s2._transcript_path = str(transcript)
            s2.load_history()
            self.assertIn("sub42", s2.subagents)
            self.assertEqual(s2.subagents["sub42"]["itemId"], "tool-tu-ag1")
            self.assertTrue(any(m["role"] == "tool" and m["toolName"] == "Bash" for m in s2.subagents["sub42"]["messages"]))

if __name__ == "__main__":
    unittest.main(verbosity=2)

#!/usr/bin/env python3
import io
import json
import os
import pathlib
import queue
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "apps/gui/app/src/main/assets/cli_bridge"))

from luma_cli.acp import AcpSession
from luma_cli.antigravity_cli import AntigravityCliSession
from luma_cli.base import Session
from luma_cli.claude_pty import ClaudePtySession
from luma_cli.codebuddy import CodeBuddyProjection, CodeBuddySession
from luma_cli.codebuddy_http import CodeBuddyHttpPeer
from luma_cli.codebuddy_owner import CodeBuddyOwner
from luma_cli.external_browser import BrowserRelay, valid_google_auth_url
from luma_cli.grok import GrokSession
from luma_cli.kimi_web import KimiWebSession
from luma_cli.metrics import _kimi_wire_cost, cost, grok_usage
from luma_cli.native_owner import NativeOwner
from luma_cli.pi_native import PiNativeSession
from luma_cli.server import _write_json_atomic
from luma_cli.transport import Peer, Rejected


def wait_for(predicate, timeout=3.0):
    end = time.monotonic() + timeout
    while not predicate():
        if time.monotonic() >= end:
            raise AssertionError("fixture timed out")
        time.sleep(0.005)


class AuditFixesTest(unittest.TestCase):
    def test_acp_direct_and_late_rejected_settlement_unblocks_queue_configure_and_release(self):
        cfg = {"cli": "Kimi Code", "cwd": "/tmp", "nativeId": "n1", "receipts": {}, "deliveries": {}}
        s = AcpSession(cfg, lambda: None)
        s.chunk_kind = s.chunk_id = s.current_submission = None
        s.native_caps = {"loadSession": True}
        s.replay_release = True
        s.release_empty = True
        s.peer = Mock(closed=False, pending={})
        s.update(connected=True, historyReady=True, capabilities={})

        s.call = Mock(side_effect=Rejected("direct refuse"))
        s.admit({"submissionId": "sub-direct", "text": "hello"})
        wait_for(lambda: cfg["receipts"].get("sub-direct") == "rejected")
        self.assertEqual(cfg["deliveries"]["sub-direct"]["state"], "rejected")
        self.assertIsNone(s.fast_release_blocker())

        def slow_then_reject(method, params, timeout=45, on_uncertain=None):
            if on_uncertain:
                on_uncertain(TimeoutError("soft"))
            raise Rejected("late refuse")

        s.call = slow_then_reject
        s.admit({"submissionId": "sub-late", "text": "late"})
        wait_for(lambda: not s.data["running"] and s.current_submission is None and cfg.get("settledUnknown", {}).get("sub-late") is True)
        self.assertEqual(cfg["receipts"]["sub-late"], "unknown")
        self.assertTrue(cfg["deliveries"]["sub-late"].get("settled"))
        self.assertIsNone(s.fast_release_blocker())
        s.configure = Mock(return_value={"ok": True})
        self.assertEqual(s.action("configure", {"model": "x"}), {"ok": True})

        s.call = Mock(return_value={"stopReason": "end_turn"})
        s.admit({"submissionId": "sub-after", "text": "next"})
        wait_for(lambda: cfg["receipts"].get("sub-after") == "accepted")

    def test_grok_codebuddy_and_agy_rejected_or_terminal_error_settlement(self):
        g_cfg = {"cli": "Grok Build", "cwd": "/tmp", "nativeId": "g1", "receipts": {}}
        g = GrokSession(g_cfg, lambda: None)
        g.turns = {}
        g.current_submission = None
        g.chunk_kind = g.chunk_id = None
        g.update(connected=True, capabilities={})

        class FakePeer:
            def __init__(self):
                self.lock = threading.RLock()
                self.pending = {}
                self.closed = False

            def send(self, frame):
                with self.lock:
                    waiter = self.pending.get(frame["id"])
                if waiter is not None:
                    waiter.put_nowait({"jsonrpc": "2.0", "id": frame["id"], "error": {"code": -32000, "message": "denied"}})

        g.peer = FakePeer()
        g.submit({"submissionId": "g-sub", "text": "hi"})
        wait_for(lambda: g_cfg["receipts"].get("g-sub") == "rejected")
        self.assertFalse(g.data["running"])

        cb_cfg = {"cli": "CodeBuddy Code", "cwd": "/tmp", "nativeId": "cb1", "receipts": {}}
        cb = CodeBuddyProjection(cb_cfg, lambda: None)
        cb.current_submission = "main"
        cb.update(connected=True, running=True, capabilities={"steer": True})
        cb.call = Mock(side_effect=Rejected("steer denied"))
        cb.submit({"submissionId": "cb-steer", "text": "nudge", "steer": True})
        wait_for(lambda: cb_cfg["receipts"].get("cb-steer") == "rejected")
        self.assertEqual(cb.data["error"], "steer denied")

        agy_cfg = {"cli": "Antigravity", "cwd": "/tmp", "nativeId": "a1", "receipts": {"t1": "unknown"}, "deliveries": {"t1": {"state": "unknown", "payload": {"submissionId": "t1", "text": "x"}, "settings": {}}}}
        agy = AntigravityCliSession(agy_cfg, lambda: None)
        agy.current_submission = "t1"
        agy.turn_has_output = False
        agy.turn_steps = {}
        agy.checkpoint = Mock()
        agy.peer = Mock(closed=False, pending={})
        agy.release_mode = lambda: "process"
        agy.update(connected=True, running=True)
        agy.result({"status": "ERROR", "error": "boom"})
        self.assertEqual(agy_cfg["receipts"]["t1"], "unknown")
        self.assertTrue(agy_cfg["settledUnknown"]["t1"])
        self.assertIsNone(agy.fast_release_blocker())

    def test_orphan_owner_cleanup_only_for_freshly_launched_owners(self):
        with tempfile.TemporaryDirectory() as tmp:
            g_cfg = {"cli": "Grok Build", "cwd": tmp, "ownerDirectory": tmp + "/g", "nativeProtocol": "grok-private-leader-v1"}
            g = GrokSession(g_cfg, lambda: None)
            g.owner = Mock(handle={"kind": "grok-leader"})
            g._connect = Mock(side_effect=Rejected("init fail"))
            with self.assertRaises(Rejected):
                g.connect()
            g.owner.stop.assert_not_called()

            def launch_then_fail():
                g._launched_owner = True
                raise Rejected("init fail")

            g._connect = launch_then_fail
            with self.assertRaises(Rejected):
                g.connect()
            g.owner.stop.assert_called_once_with(force=True)

            g.owner.stop.reset_mock()
            g_cfg["creationUnknown"] = True
            with self.assertRaises(Rejected):
                g.connect()
            g.owner.stop.assert_not_called()

            c_cfg = {"cli": "Claude Code", "cwd": tmp, "ownerDirectory": tmp + "/c", "nativeId": "11111111-2222-4333-8444-555555555555"}
            c = ClaudePtySession(c_cfg, lambda: None)
            c.startup_wait_sec = 0.05
            fake_owner = Mock()
            fake_owner.directory = pathlib.Path(tmp) / "c"
            fake_owner.directory.mkdir(parents=True, exist_ok=True)
            fake_owner.handle = {"instanceId": "inst-1"}
            fake_owner.write_private_file = Mock(side_effect=lambda name, content, mode=0o600: fake_owner.directory / name)
            fake_owner.launch_tui = Mock(return_value=True)
            fake_owner.alive = Mock(return_value=False)
            c.ensure_owner = Mock(return_value=fake_owner)
            c.setup_hook_endpoint = Mock(return_value=("http://127.0.0.1:9/h", "tok"))
            c.detect_permissions = Mock()
            c.load_history = Mock()
            c.start_tailer = Mock()
            with patch("luma_cli.claude_pty.probe_claude_pty", return_value=(True, True, "", "/bin/claude")):
                with self.assertRaises(Rejected):
                    c.connect()
            fake_owner.stop.assert_called_once_with(force=True)

            fake_owner.stop.reset_mock()
            fake_owner.handle = {"kind": "tmux", "instanceId": "inst-1"}
            with patch("luma_cli.claude_pty.probe_claude_pty", return_value=(True, True, "", "/bin/claude")):
                with self.assertRaises(Rejected):
                    c.connect()
            fake_owner.stop.assert_not_called()

            p_cfg = {"cli": "pi", "cwd": tmp, "ownerDirectory": tmp + "/p"}
            p = PiNativeSession(p_cfg, lambda: None)
            p_owner = Mock()
            p_owner.socket = tmp + "/p/s.sock"
            p_owner.handle = {"instanceId": "pi-1"}
            p_owner.write_private_file = Mock(return_value=pathlib.Path(tmp) / "p/host.json")
            p_owner.stage_native = Mock(return_value=pathlib.Path(tmp) / "p/pi-host.mjs")
            p_owner.launch_tui = Mock(return_value=True)
            p_owner.connect = Mock(side_effect=Rejected("connect fail"))
            with patch("luma_cli.pi_native.probe_pi_native", return_value=(True, True, "", pathlib.Path(tmp))), \
                 patch("luma_cli.pi_native.NativeOwner", return_value=p_owner):
                with self.assertRaises(Rejected):
                    p.start(["pi"])
            p_owner.stop.assert_called_once_with(force=True)

            cb_cfg = {"cli": "CodeBuddy Code", "cwd": tmp, "ownerDirectory": tmp + "/cb"}
            cb = CodeBuddySession(cb_cfg, lambda: None)
            cb_owner = Mock()
            cb_owner.launch = Mock(return_value=True)
            cb_owner.connect_http = Mock(side_effect=Rejected("http fail"))
            with patch("luma_cli.codebuddy_owner.CodeBuddyOwner", return_value=cb_owner):
                with self.assertRaises(Rejected):
                    cb.start(["codebuddy"])
            cb_owner.stop.assert_called_once_with(force=True)

    def test_peer_close_closes_remaining_streams_and_call_breaks_on_dead_child(self):
        peer = Peer.__new__(Peer)
        peer.lock = threading.RLock()
        peer.write_lock = threading.Lock()
        peer.pending = {}
        peer.closed = True
        peer.proc = Mock(poll=Mock(return_value=None))
        with self.assertRaises(ConnectionError):
            peer.call("ping")

        peer.closed = False
        peer.waiting_for_user = lambda: False
        exit_flag = {"code": None}
        peer.proc = Mock()
        peer.proc.poll = lambda: exit_flag["code"]
        peer.send = Mock()
        uncertain = []

        def mark_dead():
            time.sleep(0.08)
            exit_flag["code"] = 1

        t = threading.Thread(target=mark_dead, daemon=True)
        t.start()
        with self.assertRaises(ConnectionError):
            peer.call("slow", timeout=0.02, on_uncertain=uncertain.append)
        t.join(timeout=2)
        self.assertEqual(len(uncertain), 1)
        self.assertFalse(peer.pending)

        close_peer = Peer.__new__(Peer)
        stdin_stream = io.BytesIO()
        stdout_stream = io.BytesIO()
        stderr_stream = io.BytesIO()
        close_peer.proc = Mock(poll=Mock(return_value=0), stdin=stdin_stream, stdout=stdout_stream, stderr=stderr_stream)
        alive_reader = Mock(is_alive=Mock(return_value=True))
        dead_stderr = Mock(is_alive=Mock(return_value=False))
        close_peer.reader_thread = alive_reader
        close_peer.stderr_thread = dead_stderr
        with self.assertRaises(OSError):
            close_peer.close()
        self.assertTrue(stdin_stream.closed)
        self.assertFalse(stdout_stream.closed)
        self.assertTrue(stderr_stream.closed)

    def test_host_server_byte_chunk_framing_utf8_split_and_32mib_limit(self):
        module = (ROOT / "apps/gui/app/src/main/assets/cli_bridge/native/host-server.mjs").as_uri()
        with tempfile.TemporaryDirectory(prefix="luma-host-framing-") as tmp:
            sock_path = os.path.join(tmp, "h.sock")
            script = (
                "import net from 'node:net';"
                f"import {{hostServer}} from {json.dumps(module)};"
                f"const sock={json.dumps(sock_path)};"
                "const srv=hostServer({socket:sock},()=>'nid',async p=>({echo:p.text}));"
                "await new Promise(r=>srv.server.once('listening',r));"
                "const c1=net.createConnection(sock);"
                "await new Promise(r=>c1.once('connect',r));"
                "const msg=Buffer.from(JSON.stringify({id:'1',text:'你好🚀世界'})+'\\n'+JSON.stringify({id:'2',text:'second'})+'\\n','utf8');"
                "c1.write(msg.subarray(0,23));"
                "await new Promise(r=>setTimeout(r,20));"
                "c1.write(msg.subarray(23));"
                "let out='';"
                "await new Promise((resolve,reject)=>{"
                " c1.on('data',d=>{out+=d.toString('utf8');const ls=out.trim().split('\\n').filter(Boolean);if(ls.length>=2){"
                "  const p1=JSON.parse(ls[0]),p2=JSON.parse(ls[1]);"
                "  if(p1.data?.echo!=='你好🚀世界'||p2.data?.echo!=='second')reject(Error('bad:'+out));else resolve();"
                " }});"
                "});"
                "c1.destroy();"
                "const c2=net.createConnection(sock);"
                "await new Promise(r=>c2.once('connect',r));"
                "let closed=false;c2.on('close',()=>{closed=true;});c2.on('error',()=>{});"
                "const chunk=Buffer.alloc(1024*1024,0x61);"
                "for(let i=0;i<34;i++){if(c2.destroyed)break;c2.write(chunk);}"
                "await new Promise(r=>setTimeout(r,150));"
                "if(!closed)throw Error('oversized line not destroyed');"
                "srv.detach();"
            )
            subprocess.run(["node", "--input-type=module", "-e", script], check=True, timeout=15)

    def test_codebuddy_owner_stop_cleans_dead_owner_and_http_post_failure_keeps_reader(self):
        with tempfile.TemporaryDirectory() as tmp:
            saves = []
            cfg = {"cli": "CodeBuddy Code", "cwd": tmp, "ownerDirectory": tmp + "/owner"}
            s = CodeBuddySession(cfg, lambda: saves.append(dict(cfg)))
            owner = CodeBuddyOwner(s)
            owner.write_private_file("codebuddy-gateway-password", "secret", mode=0o600)
            self.assertEqual(stat.S_IMODE((owner.directory / "codebuddy-gateway-password").stat().st_mode), 0o600)
            owner.handle.update(kind="codebuddy-server", pid=999999, processStart="dead-stamp")
            saves.clear()
            self.assertTrue(owner.stop(force=False))
            self.assertFalse(owner.directory.exists())
            self.assertNotIn("nativeOwner", cfg)
            self.assertEqual(len(saves), 1)

        peer = CodeBuddyHttpPeer.__new__(CodeBuddyHttpPeer)
        peer.lock = threading.RLock()
        peer.pending = {}
        peer.closed = False
        peer.reader = Mock()
        peer.finish = Mock()
        done = threading.Event()

        def failing_req(*a, **kw):
            done.set()
            raise ConnectionError("post failed")

        peer.request = failing_req
        peer.send({"jsonrpc": "2.0", "method": "session/cancel"})
        self.assertTrue(done.wait(2))
        time.sleep(0.05)
        peer.reader.close.assert_not_called()

    def test_codebuddy_malformed_question_and_tool_frames_do_not_raise(self):
        s = CodeBuddyProjection({"cli": "CodeBuddy Code", "cwd": "/tmp", "nativeId": "n1"}, lambda: None)
        s.current_submission = None
        s.chunk_kind = s.chunk_id = None
        s.replaying_history = False
        s.history_seen = set()
        s.external_request = None
        s.peer = Mock()
        s.event({"jsonrpc": "2.0", "id": 99, "method": "_codebuddy.ai/question", "params": {"sessionId": "n1", "inputType": "invalid"}})
        s.peer.send.assert_called_once()
        sent = s.peer.send.call_args.args[0]
        self.assertEqual(sent["id"], 99)
        self.assertEqual(sent["error"]["code"], -32602)

        s.event({
            "method": "session/update",
            "params": {
                "sessionId": "n1",
                "update": {
                    "sessionUpdate": "tool_call",
                    "toolCallId": "tc1",
                    "rawInput": "not-a-dict",
                    "_meta": {"codebuddy.ai/isSubagent": True, "codebuddy.ai/toolName": "Agent"},
                },
            },
        })
        self.assertIn("codebuddy-task:tc1", s.subagents)
        self.assertEqual(s.subagents["codebuddy-task:tc1"]["task"], "")

    def test_native_owner_inject_sanitization_launcher_script_and_claude_configure_guards(self):
        with tempfile.TemporaryDirectory() as tmp:
            cfg = {"cli": "Claude Code", "cwd": tmp, "ownerDirectory": tmp + "/owner", "nativeId": "11111111-2222-4333-8444-555555555555"}
            s = Session(cfg, lambda: None)
            owner = NativeOwner(s)
            tmux_calls = []

            def fake_tmux(*args, check=True, input_bytes=None):
                tmux_calls.append((args, input_bytes))
                return Mock(returncode=0, stdout=b"0")

            owner.tmux = fake_tmux
            owner.tmux_command = lambda: ["/usr/bin/tmux"]
            owner.alive = lambda: True
            with patch("luma_cli.native_owner._query_tmux_pane_pid", return_value=(1234, "start1")):
                owner.launch_tui(["/bin/true"], env={"LUMA_HOOK_TOKEN": "SUPER_SECRET_TOKEN"}, host_kind="pty-tui", binary_name="true")
            new_sess_args = tmux_calls[0][0]
            self.assertNotIn("SUPER_SECRET_TOKEN", " ".join(new_sess_args))
            launcher = owner.directory / "launch-tui.sh"
            self.assertTrue(launcher.is_file())
            self.assertEqual(stat.S_IMODE(launcher.stat().st_mode), 0o700)
            self.assertIn("SUPER_SECRET_TOKEN", launcher.read_text("utf-8"))

            tmux_calls.clear()
            owner.inject_text("hi\x00\x1b[201~evil\x1b[20\x001~\x1b[20\x1b[200~0~done", submit=False)
            self.assertEqual(tmux_calls[0][1], b"hievildone")

            c = ClaudePtySession(cfg, lambda: None)
            c.owner = Mock()
            c.update(model="sonnet", effort="low", models=[{"id": "sonnet", "efforts": ["low", "high"]}, {"id": "opus", "efforts": ["low", "high"]}])
            with self.assertRaises(Rejected):
                c.configure({"model": "opus\n/clear"})
            with self.assertRaises(Rejected):
                c.configure({"effort": "high --bad"})
            c.owner.inject_text.assert_not_called()

            panes = iter(["ready\n", "ready\n/model opus\nError: model not found\n"])
            c.owner.capture_pane = Mock(side_effect=lambda lines=20: next(panes, "ready\n/model opus\nError: model not found\n"))
            with self.assertRaises(Rejected):
                c.configure({"model": "opus"})
            self.assertEqual(c.data["model"], "sonnet")
            self.assertNotIn("selectedModel", cfg)

    def test_kimi_web_missing_attachments_and_wire_cost_cache_and_zero_cost_guards(self):
        k = KimiWebSession({"cli": "Kimi Code", "cwd": "/tmp", "nativeId": "k1"}, lambda: None)
        rows = k.project({
            "attachments": [{"attachmentId": "bad"}, "not-a-dict"],
            "prompts": [{"promptId": "p1", "content": []}],
            "items": [{
                "kind": "turn",
                "turnId": "t1",
                "origin": {"kind": "user"},
                "triggerPromptId": "p1",
                "input": "hello",
                "attachmentIds": ["missing-id"],
                "steps": [],
            }],
        })
        self.assertIn("p1", rows)

        self.assertEqual(cost(0, "USD"), [])
        self.assertEqual(cost(0.0, "USD", estimated=True), [])
        g_rows = grok_usage({"inputTokens": 1000, "outputTokens": 500, "modelCalls": 1, "costUsdTicks": 0}, model="grok-4")
        self.assertTrue(any("USD" in r["label"] and float(r["value"]) > 0 for r in g_rows))

        with tempfile.TemporaryDirectory() as tmp:
            home = pathlib.Path(tmp)
            wire = home / ".kimi-code" / "sessions" / "ws" / "session_kcache" / "agents" / "main" / "wire.jsonl"
            wire.parent.mkdir(parents=True)
            line1 = json.dumps({"type": "usage.record", "usageScope": "turn", "model": "kimi-k2.5", "usage": {"inputOther": 1000, "output": 500}}) + "\n"
            wire.write_text(line1, encoding="utf-8")
            first = _kimi_wire_cost("kcache", home=home)
            self.assertIsNotNone(first)
            with patch("pathlib.Path.open", side_effect=AssertionError("should use cache")):
                second = _kimi_wire_cost("kcache", home=home)
            self.assertEqual(first, second)
            wire.write_text(line1 + line1, encoding="utf-8")
            third = _kimi_wire_cost("kcache", home=home)
            self.assertGreater(float(third[0]["value"]), float(first[0]["value"]))

    def test_external_browser_hardening_and_server_atomic_write(self):
        base = "https://accounts.google.com/o/oauth2/v2/auth?redirect_uri=http%3A%2F%2F127.0.0.1%3A45678%2F"
        self.assertTrue(valid_google_auth_url(base))
        for bad in (
            base + "\r\nX-Test: 1",
            "https://accounts.google.com/o/oauth2/v2/auth\t?redirect_uri=http%3A%2F%2F127.0.0.1%3A45678%2F",
            base + " extra",
            base + "\x00",
            base + "\x7f",
        ):
            self.assertFalse(valid_google_auth_url(bad), repr(bad))

        relay = BrowserRelay(lambda u: None)
        try:
            env = relay.environment()
            self.assertNotIn("%s", env["BROWSER"])
            opener = pathlib.Path(env["BROWSER"])
            self.assertTrue(opener.is_file())
            self.assertEqual(stat.S_IMODE(opener.stat().st_mode), 0o700)
        finally:
            relay.close()

        with tempfile.TemporaryDirectory() as tmp:
            target = pathlib.Path(tmp) / "state.json"
            _write_json_atomic(target, {"ok": True})
            self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o600)
            self.assertEqual(json.loads(target.read_text("utf-8")), {"ok": True})


if __name__ == "__main__":
    unittest.main(verbosity=2)

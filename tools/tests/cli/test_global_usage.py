#!/usr/bin/env python3
from pathlib import Path
import datetime as dt
import io
import json
import os
import shutil
import sqlite3
import stat
import struct
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "apps/gui/app/src/main/assets/cli_bridge"))

from luma_cli import balances
from luma_cli import global_usage as gu


def _write_zstd_events(path, events):
    payload = ("\n".join(json.dumps(event) for event in events) + "\n").encode("utf-8")
    try:
        from compression import zstd
    except ImportError:
        compressed = subprocess.run(["zstd", "-qc"], input=payload, stdout=subprocess.PIPE, check=True).stdout
    else:
        compressed = zstd.compress(payload)
    path.write_bytes(compressed)


class GlobalUsageTest(unittest.TestCase):
    def setUp(self):
        self._cache_tmp = tempfile.TemporaryDirectory()
        self._cache_patch = patch.object(gu, "_cache_dir", return_value=Path(self._cache_tmp.name))
        self._remote_patch = patch.object(gu, "_fetch_remote_json", return_value=None)
        self._cache_patch.start()
        self._remote_patch.start()
        gu._PRICING_RUNTIME_STATE.update(cache_path="", mtime_ns=-1, checked_mono=0.0, catalog=None)

    def tearDown(self):
        self._remote_patch.stop()
        self._cache_patch.stop()
        self._cache_tmp.cleanup()
        gu._PRICING_RUNTIME_STATE.update(cache_path="", mtime_ns=-1, checked_mono=0.0, catalog=None)

    def test_claude_deduplication_and_pricing(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            proj = home / ".claude" / "projects" / "demo"
            proj.mkdir(parents=True)
            jsonl = proj / "session.jsonl"
            line1 = {
                "type": "assistant",
                "timestamp": "2026-11-06T12:00:00Z",
                "requestId": "req_1",
                "message": {
                    "id": "msg_1",
                    "model": "claude-sonnet-4-6",
                    "usage": {
                        "input_tokens": 1000,
                        "cache_read_input_tokens": 5000,
                        "cache_creation_input_tokens": 200,
                        "output_tokens": 300,
                    },
                },
            }
            jsonl.write_text(json.dumps(line1) + "\n" + json.dumps(line1) + "\n", "utf-8")
            cache_files = {}
            recs = gu._scan_claude(home, cache_files, cutoff_ms=0)
            self.assertEqual(len(recs), 1)
            self.assertEqual(recs[0]["uncachedInputTokens"], 1000)
            self.assertEqual(recs[0]["cachedInputTokens"], 5000)
            self.assertEqual(recs[0]["cacheCreationTokens"], 200)
            self.assertEqual(recs[0]["outputTokens"], 300)

            priced = gu._price_record(recs[0])
            self.assertEqual(priced["costSource"], "modelPriced")
            self.assertGreater(priced["costUsd"], 0.0)
            self.assertGreater(priced["cacheSavingsUsd"], 0.0)

    def test_claude_one_hour_cache_writes_price_at_double_input(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            proj = home / ".claude" / "projects" / "demo"
            proj.mkdir(parents=True)
            line = {
                "type": "assistant",
                "timestamp": "2026-11-06T12:00:00Z",
                "requestId": "req_1h",
                "message": {
                    "id": "msg_1h",
                    "model": "claude-sonnet-4-6",
                    "usage": {
                        "input_tokens": 10,
                        "cache_creation_input_tokens": 1000,
                        "cache_creation": {"ephemeral_5m_input_tokens": 400, "ephemeral_1h_input_tokens": 600},
                        "output_tokens": 0,
                    },
                },
            }
            (proj / "s.jsonl").write_text(json.dumps(line) + "\n", "utf-8")
            rec = gu._scan_claude(home, {}, cutoff_ms=0)[0]
            self.assertEqual(rec["cacheCreationTokens"], 1000)
            self.assertEqual(rec["cacheWrite1hTokens"], 600)

            in_m, _, _, write_m, tiered = gu._lookup_model_rate("claude-sonnet-4-6")
            priced = gu._price_record(rec)
            expected = (400 * write_m + 600 * in_m * 2.0) / 1_000_000.0
            self.assertAlmostEqual(priced["categoryCostUsd"]["cacheWrite"], expected)
            five_min_only = gu._price_record({**rec, "cacheWrite1hTokens": 0})
            self.assertAlmostEqual(five_min_only["categoryCostUsd"]["cacheWrite"], 1000 * write_m / 1_000_000.0)
            self.assertGreater(priced["costUsd"], five_min_only["costUsd"])

            self.assertIsNotNone(tiered)
            _, t_in_m, _, _, t_write_m = tiered
            tiered_rec = {
                **rec,
                "uncachedInputTokens": 210_000,
                "cacheCreationTokens": 1000,
                "cacheWrite1hTokens": 600,
            }
            tiered_priced = gu._price_record(tiered_rec)
            self.assertAlmostEqual(
                tiered_priced["categoryCostUsd"]["cacheWrite"],
                (400 * t_write_m + 600 * t_in_m * 2.0) / 1_000_000.0,
            )

            split_only = {
                "type": "assistant",
                "timestamp": "2026-11-06T12:01:00Z",
                "requestId": "req_1h_split",
                "message": {
                    "id": "msg_1h_split",
                    "model": "claude-sonnet-4-6",
                    "usage": {
                        "input_tokens": 0,
                        "cache_creation": {"ephemeral_5m_input_tokens": 100, "ephemeral_1h_input_tokens": 300},
                        "output_tokens": 0,
                    },
                },
            }
            (proj / "split.jsonl").write_text(json.dumps(split_only) + "\n", "utf-8")
            with (
                patch.object(gu.Path, "home", return_value=home),
                patch.object(gu, "_cache_dir", return_value=home / ".cache"),
            ):
                summary = gu.collect_summary(period="all", tz_name="UTC", refresh=True, sync_pricing=False)
                expected_cw = (500 * write_m + 900 * in_m * 2.0) / 1_000_000.0
                self.assertAlmostEqual(summary["totals"]["categoryCostUsd"]["cacheWrite"], expected_cw)
                archive = gu._load_daily_archive()
                entry = archive["byTz"]["UTC"]["2026-11-06"]["Claude Code::claude-sonnet-4-6"]
                self.assertEqual(entry["cacheWrite1hTokens"], 900)
                gu._reprice_archived_entry(entry, gu._get_pricing_catalog())
                self.assertAlmostEqual(entry["categoryCostUsd"]["cacheWrite"], expected_cw)

    def test_opencode_v2_session_message_sqlite(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            oc_dir = home / ".local" / "share" / "opencode"
            oc_dir.mkdir(parents=True)
            db_path = oc_dir / "opencode.db"
            con = sqlite3.connect(str(db_path))
            con.execute(
                "CREATE TABLE session_message (id TEXT PRIMARY KEY, session_id TEXT, type TEXT, time_created INTEGER, data TEXT)"
            )
            payload = {
                "time": {"created": 1793900000000, "completed": 1793900005000},
                "model": {"id": "gemini-4-argon", "providerID": "opencode"},
                "cost": 0.42,
                "tokens": {
                    "input": 2000,
                    "output": 500,
                    "reasoning": 100,
                    "cache": {"read": 8000, "write": 0},
                },
            }
            con.execute(
                "INSERT INTO session_message VALUES (?, ?, ?, ?, ?)",
                ("msg_a", "ses_1", "assistant", 1793900000000, json.dumps(payload)),
            )
            con.commit()
            con.close()

            cache_files = {}
            recs = gu._scan_opencode(home, cache_files, cutoff_ms=0)
            self.assertEqual(len(recs), 1)
            self.assertEqual(recs[0]["model"], "gemini-4-argon")
            self.assertAlmostEqual(recs[0]["reportedCostUsd"], 0.42)
            priced = gu._price_record(recs[0])
            self.assertEqual(priced["costSource"], "providerReported")
            self.assertAlmostEqual(priced["costUsd"], 0.42)

    def test_antigravity_cli_usage_parser_and_subprocess_probe(self):
        cli_payload = {
            "conversation_id": "",
            "status": "SUCCESS",
            "command": {
                "name": "usage",
                "data": {
                    "groups": [
                        {
                            "name": "Gemini Models",
                            "buckets": [
                                {
                                    "id": "gemini-weekly",
                                    "name": "Weekly Limit Remaining",
                                    "window": "weekly",
                                    "remaining_fraction": 0.8868,
                                    "reset_time": "2026-12-01T00:47:41Z",
                                },
                                {
                                    "id": "gemini-5h",
                                    "name": "Five Hour Limit Remaining",
                                    "window": "5h",
                                    "remaining_fraction": 0.9096,
                                    "reset_time": "2026-11-25T08:09:23Z",
                                },
                                {
                                    "id": "gemini-disabled",
                                    "name": "Disabled Limit",
                                    "window": "5h",
                                    "remaining_fraction": 0.5,
                                    "disabled": True,
                                },
                            ],
                        },
                        {
                            "name": "Gemini 4 Argon",
                            "buckets": [
                                {
                                    "id": "gemini-argon-wtu-weekly",
                                    "name": "Weekly Limit Remaining",
                                    "window": "weekly",
                                    "remaining_fraction": 0.65,
                                    "reset_time": "2026-12-02T10:00:00Z",
                                },
                            ],
                        },
                        {
                            "name": "Claude and GPT models",
                            "buckets": [
                                {
                                    "id": "3p-weekly",
                                    "name": "Weekly Limit Remaining",
                                    "window": "weekly",
                                    "remaining_fraction": 0.0127,
                                    "reset_time": "2026-12-01T10:25:17Z",
                                },
                                {
                                    "id": "3p-5h",
                                    "name": "Five Hour Limit Remaining",
                                    "window": "5h",
                                    "remaining_fraction": 1,
                                    "reset_time": "1970-01-01T00:00:00Z",
                                },
                            ],
                        },
                    ]
                },
            },
        }

        parsed = gu._parse_agy_cli_usage(cli_payload)
        by_id = {w["id"]: w for w in parsed}
        self.assertEqual(
            [w["id"] for w in parsed],
            ["gemini-5h", "gemini-weekly", "gemini-argon-wtu-weekly", "3p-5h", "3p-weekly"],
        )
        self.assertAlmostEqual(by_id["gemini-5h"]["usedPercent"], 9.0, places=1)
        self.assertEqual(by_id["gemini-5h"]["label"], f"Gemini · {gu._tr('短期', 'Session')}")
        self.assertAlmostEqual(by_id["gemini-weekly"]["usedPercent"], 11.3, places=1)
        self.assertEqual(by_id["gemini-weekly"]["label"], f"Gemini · {gu._tr('每周', 'Weekly')}")
        self.assertEqual(by_id["gemini-argon-wtu-weekly"]["label"], f"Gemini 4 Argon · {gu._tr('每周', 'Weekly')}")
        self.assertAlmostEqual(by_id["gemini-argon-wtu-weekly"]["usedPercent"], 35.0, places=1)
        self.assertEqual(by_id["3p-5h"]["usedPercent"], 0.0)
        self.assertIsNone(by_id["3p-5h"]["resetsAt"])
        self.assertAlmostEqual(by_id["3p-weekly"]["usedPercent"], 98.7, places=1)
        self.assertEqual(by_id["3p-weekly"]["resetsAt"], "2026-12-01T10:25:17Z")

        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            (home / ".gemini" / "antigravity-cli").mkdir(parents=True)
            fake_agy = home / "fake-agy"
            fake_agy.write_text(
                f"#!{sys.executable}\n"
                "import json, os, sys, time\n"
                "assert sys.argv[1:] == ['-p', '/usage', '--output-format', 'json']\n"
                "mode = os.environ.get('FAKE_AGY_MODE', 'ok')\n"
                "if mode == 'sleep':\n"
                "    time.sleep(5)\n"
                "elif mode == 'auth_err':\n"
                "    sys.stderr.write('Not logged in; please sign in\\n')\n"
                "    raise SystemExit(1)\n"
                f"print(json.dumps({cli_payload!r}))\n",
                "utf-8",
            )
            fake_agy.chmod(0o755)

            with patch.dict(os.environ, {"LUMA_AGY_BIN": str(fake_agy), "FAKE_AGY_MODE": "ok"}, clear=False):
                self.assertTrue(gu._has_antigravity_oauth(home))
                res = gu._probe_antigravity_limits(home, timeout_sec=5.0)
            self.assertEqual(res["cli"], "Antigravity")
            self.assertEqual(res["status"], "ok")
            self.assertEqual(res["source"], "live")
            self.assertEqual([w["id"] for w in res["windows"]], ["gemini-5h", "gemini-weekly", "gemini-argon-wtu-weekly", "3p-5h", "3p-weekly"])

            with patch.dict(os.environ, {"LUMA_AGY_BIN": str(fake_agy), "FAKE_AGY_MODE": "auth_err"}, clear=False):
                err_res = gu._probe_antigravity_limits(home, timeout_sec=5.0)
            self.assertEqual(err_res["status"], "error")
            self.assertIn(gu._tr("重新登录", "sign in"), err_res["message"])

            with patch.dict(os.environ, {"LUMA_AGY_BIN": str(fake_agy), "FAKE_AGY_MODE": "sleep"}, clear=False):
                t0 = time.monotonic()
                to_res = gu._probe_antigravity_limits(home, timeout_sec=0.15)
            self.assertLess(time.monotonic() - t0, 1.5)
            self.assertEqual(to_res["status"], "error")
            self.assertIn(gu._tr("超时", "timed out"), to_res["message"])

    def test_oauth_gate_skips_unconfigured_and_apikey_only_providers(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            cache_dir = home / ".cache-test"
            cache_dir.mkdir(parents=True)

            with (
                patch.object(gu.Path, "home", return_value=home),
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.dict(os.environ, {"CODEX_HOME": str(home / ".codex")}, clear=False),
            ):
                res = gu.collect_limits(fresh=True)
                self.assertEqual(res["providers"], [])

            (home / ".codex").mkdir(parents=True)
            (home / ".codex" / "auth.json").write_text(
                json.dumps({"auth_mode": "apikey", "OPENAI_API_KEY": "sk-proj-demo", "tokens": None}),
                "utf-8",
            )
            (home / ".claude").mkdir(parents=True)
            (home / ".claude" / ".credentials.json").write_text(
                json.dumps({"accessToken": "sk-ant-api03-demo"}),
                "utf-8",
            )
            (home / ".grok").mkdir(parents=True)
            (home / ".grok" / "auth.json").write_text(
                json.dumps({
                    "https://accounts.x.ai/sign-in": {
                        "auth_mode": "api_key",
                        "key": "xai-demo-key",
                    }
                }),
                "utf-8",
            )

            with (
                patch.object(gu.Path, "home", return_value=home),
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.dict(os.environ, {"CODEX_HOME": str(home / ".codex")}, clear=False),
                patch("subprocess.Popen") as mock_popen,
            ):
                self.assertFalse(gu._has_codex_oauth(home))
                self.assertFalse(gu._has_claude_oauth(home))
                self.assertFalse(gu._has_antigravity_oauth(home))
                self.assertFalse(gu._has_grok_oauth(home))
                res = gu.collect_limits(fresh=True)
                self.assertEqual(res["providers"], [])
                mock_popen.assert_not_called()

    def test_collect_limits_isolates_slow_probe_timeout_without_crashing(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            cache_dir = home / ".cache-test"
            cache_dir.mkdir(parents=True)

            (home / ".codex").mkdir(parents=True)
            (home / ".codex" / "auth.json").write_text(
                json.dumps({
                    "auth_mode": "chatgpt",
                    "tokens": {"access_token": "tok_codex", "refresh_token": "ref_codex"},
                }),
                "utf-8",
            )
            agy_dir = home / ".gemini" / "antigravity-cli"
            agy_dir.mkdir(parents=True)
            fake_agy = home / "fake-agy-slow"
            fake_agy.write_text("#!/bin/sh\nexit 0\n", "utf-8")
            fake_agy.chmod(0o755)

            def fast_codex(_h: Path):
                return {
                    "cli": "Codex",
                    "planType": "plus",
                    "updatedAt": "2026-11-07T10:00:00Z",
                    "source": "live",
                    "status": "ok",
                    "message": None,
                    "windows": [gu._make_window("primary", gu._tr("5h 窗口", "5h window"), 22.0)],
                    "credits": None,
                }

            def slow_agy(_h: Path):
                time.sleep(0.45)
                return {
                    "cli": "Antigravity",
                    "planType": "Antigravity CLI",
                    "updatedAt": "2026-11-07T10:00:00Z",
                    "source": "live",
                    "status": "ok",
                    "message": None,
                    "windows": [],
                    "credits": None,
                }

            t0 = time.monotonic()
            with (
                patch.object(gu.Path, "home", return_value=home),
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.dict(os.environ, {"CODEX_HOME": str(home / ".codex"), "LUMA_AGY_BIN": str(fake_agy)}, clear=False),
                patch.object(gu, "_probe_codex_limits", side_effect=fast_codex),
                patch.object(gu, "_probe_antigravity_limits", side_effect=slow_agy),
            ):
                res = gu.collect_limits(fresh=True, probe_timeout=0.12)
            elapsed = time.monotonic() - t0

            self.assertLess(elapsed, 0.35)
            providers = res["providers"]
            self.assertEqual([p["cli"] for p in providers], ["Codex", "Antigravity"])
            self.assertEqual(providers[0]["status"], "ok")
            self.assertEqual(providers[0]["windows"][0]["usedPercent"], 22.0)
            self.assertEqual(providers[1]["status"], "error")
            self.assertIn(gu._tr("超时", "timed out"), providers[1]["message"])
            self.assertFalse((cache_dir / "usage-limits-cache-v5.json").exists())

    def _probe_codex_rpc_server(self, mode, with_history=True):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            server = home / "codex"
            server.write_text(f'''#!{sys.executable}
import json
import os
import select
import sys
import time

mode = {mode!r}
initialize = json.loads(sys.stdin.readline())
assert initialize["method"] == "initialize"
assert initialize["params"]["clientInfo"]["name"] == "luma-gui"
assert not select.select([sys.stdin], [], [], 0.05)[0]
if mode == "initialize_error":
    print(json.dumps({{"id": initialize["id"], "error": {{"code": -32603}}}}), flush=True)
else:
    print(json.dumps({{"id": initialize["id"], "result": {{}}}}), flush=True)
    assert json.loads(sys.stdin.readline())["method"] == "initialized"
    request = json.loads(sys.stdin.readline())
    assert request["method"] == "account/rateLimits/read"
    assert not select.select([sys.stdin], [], [], 0.05)[0]
    if mode == "eof":
        sys.exit(0)
    if mode == "read_error":
        print(json.dumps({{"id": request["id"], "error": {{"code": -32603}}}}), flush=True)
    elif mode == "partial_timeout":
        os.write(sys.stdout.fileno(), b'{{"id":2,"result":')
    else:
        print("not json", flush=True)
        print(json.dumps({{"method": "account/rateLimits/updated", "params": {{}}}}), flush=True)
        print(json.dumps({{"id": 99, "result": {{}}}}), flush=True)
        response = json.dumps({{"id": request["id"], "result": {{"rateLimits": {{
            "planType": "测试",
            "primary": {{"usedPercent": 12.5, "windowDurationMins": 300, "resetsAt": 1800000000}},
            "secondary": {{"usedPercent": 42, "windowDurationMins": 10080, "resetsAt": 1800000001}}
        }}}}}}, ensure_ascii=False).encode() + b"\\n"
        split = response.index("测".encode()) + 1
        os.write(sys.stdout.fileno(), response[:split])
        time.sleep(0.02)
        os.write(sys.stdout.fileno(), response[split:])
sys.stdin.read()
''', "utf-8")
            server.chmod(0o755)
            if with_history:
                sessions = home / ".codex" / "sessions" / "2026" / "11" / "01"
                sessions.mkdir(parents=True)
                (sessions / "rollout-test.jsonl").write_text(json.dumps({
                    "timestamp": "2026-11-01T00:00:00Z",
                    "payload": {"rate_limits": {"primary": {"used_percent": 77, "window_minutes": 300}}},
                }) + "\n", "utf-8")
            processes = []
            popen = subprocess.Popen

            def spawn(*args, **kwargs):
                process = popen(*args, **kwargs)
                processes.append(process)
                return process

            started = time.monotonic()
            with (
                patch.dict(os.environ, {"CODEX_HOME": str(home / ".codex")}),
                patch.object(gu, "_has_codex_oauth", return_value=True),
                patch.object(gu, "_resolve_codex_binary", return_value=str(server)),
                patch.object(gu.subprocess, "Popen", side_effect=spawn),
            ):
                result = gu._probe_codex_limits(home)
            self.assertEqual(len(processes), 1)
            self.assertIsNotNone(processes[0].poll())
            self.assertTrue(processes[0].stdin.closed)
            self.assertTrue(processes[0].stdout.closed)
            self.assertLess(time.monotonic() - started, 4.5)
            return result

    def test_codex_rpc_waits_for_handshake_and_reply_without_closing_stdin(self):
        result = self._probe_codex_rpc_server("live")
        self.assertEqual(result["source"], "live")
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["planType"], "测试")
        self.assertEqual([w["usedPercent"] for w in result["windows"]], [12.5, 42.0])

    def test_codex_rpc_errors_and_eof_preserve_session_fallback(self):
        for mode in ("initialize_error", "read_error", "eof", "partial_timeout"):
            with self.subTest(mode=mode):
                result = self._probe_codex_rpc_server(mode)
                self.assertEqual(result["source"], "session_fallback")
                self.assertEqual(result["updatedAt"], "2026-11-01T00:00:00Z")
                self.assertEqual(result["windows"][0]["usedPercent"], 77.0)

    def test_codex_rpc_failure_without_history_remains_unavailable(self):
        result = self._probe_codex_rpc_server("eof", with_history=False)
        self.assertEqual(result["source"], "unavailable")
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["windows"], [])

    def test_recent_codex_session_files_newest_first(self):
        with tempfile.TemporaryDirectory() as tmp:
            sessions = Path(tmp) / "sessions"
            old_dir = sessions / "2026" / "09" / "01"
            new_dir = sessions / "2026" / "11" / "07"
            old_dir.mkdir(parents=True)
            new_dir.mkdir(parents=True)
            old_file = old_dir / "rollout-old.jsonl"
            new_file = new_dir / "rollout-new.jsonl"
            old_file.write_text("{}\n", "utf-8")
            new_file.write_text("{}\n", "utf-8")

            found = gu._recent_codex_session_files(sessions, limit=1)
            self.assertEqual(found, [new_file])

    def test_antigravity_backend_variant_endpoints_and_session_recovery(self):
        def pb_varint(val: int) -> bytes:
            out = bytearray()
            v = int(val)
            while v >= 0x80:
                out.append((v & 0x7F) | 0x80)
                v >>= 7
            out.append(v)
            return bytes(out)

        def pb_num(field: int, val: int) -> bytes:
            return pb_varint((field << 3) | 0) + pb_varint(val)

        def pb_bytes(field: int, raw: bytes) -> bytes:
            return pb_varint((field << 3) | 2) + pb_varint(len(raw)) + raw

        def pb_str(field: int, s: str) -> bytes:
            return pb_bytes(field, s.encode("utf-8"))

        def pb_ts(sec: int) -> bytes:
            return pb_num(1, sec)

        now_sec = int(time.time()) - 120

        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            cache_dir = home / ".cache-agy"
            cache_dir.mkdir(parents=True)
            conv_dir = home / ".gemini" / "antigravity-cli" / "conversations"
            conv_dir.mkdir(parents=True)
            db_path = conv_dir / "conv-gray.db"

            con = sqlite3.connect(str(db_path))
            con.execute("CREATE TABLE steps (idx INTEGER PRIMARY KEY, step_type INTEGER, metadata BLOB)")
            con.execute("CREATE TABLE gen_metadata (idx INTEGER PRIMARY KEY, data BLOB)")

            step0_meta = pb_bytes(1, pb_ts(now_sec))
            con.execute("INSERT INTO steps VALUES (0, 15, ?)", (step0_meta,))
            step1_meta = pb_bytes(1, pb_ts(now_sec + 30))
            con.execute("INSERT INTO steps VALUES (1, 15, ?)", (step1_meta,))
            step2_meta = pb_bytes(1, pb_ts(now_sec + 60))
            con.execute("INSERT INTO steps VALUES (2, 15, ?)", (step2_meta,))

            usage0 = (
                pb_num(1, 1318)
                + pb_num(2, 4000)
                + pb_num(5, 6000)
                + pb_num(9, 200)
                + pb_num(10, 800)
                + pb_str(11, "resp-0")
            )
            chat0 = pb_str(19, "gemini-3.8-flash-n") + pb_str(21, "Gemini 3.8 Flash") + pb_bytes(4, usage0)
            gen0 = pb_bytes(1, chat0) + pb_num(2, 0)
            con.execute("INSERT INTO gen_metadata VALUES (0, ?)", (gen0,))

            usage1 = pb_num(1, 1318) + pb_num(2, 2000) + pb_num(9, 100) + pb_num(10, 400) + pb_str(11, "resp-1")
            chat1 = pb_str(19, "gemini-default") + pb_str(21, "Gemini 3.8 Flash") + pb_bytes(4, usage1)
            gen1 = pb_bytes(1, chat1) + pb_num(2, 1)
            con.execute("INSERT INTO gen_metadata VALUES (1, ?)", (gen1,))

            usage2 = pb_num(1, 1417) + pb_num(2, 5000) + pb_num(9, 300) + pb_num(10, 700) + pb_str(11, "resp-2")
            chat2 = pb_str(19, "gemini-4-argon-trial") + pb_str(21, "Gemini 4 Argon (High)") + pb_bytes(4, usage2)
            gen2 = pb_bytes(1, chat2) + pb_num(2, 2)
            con.execute("INSERT INTO gen_metadata VALUES (2, ?)", (gen2,))
            con.commit()
            con.close()

            orch_db = conv_dir / "conv-orch.db"
            con_orch = sqlite3.connect(str(orch_db))
            con_orch.execute("CREATE TABLE steps (idx INTEGER PRIMARY KEY, step_type INTEGER, metadata BLOB)")
            con_orch.execute("CREATE TABLE gen_metadata (idx INTEGER PRIMARY KEY, data BLOB)")
            con_orch.execute("INSERT INTO steps VALUES (0, 15, ?)", (pb_bytes(1, pb_ts(now_sec + 70)),))
            con_orch.execute("INSERT INTO steps VALUES (1, 15, ?)", (pb_bytes(1, pb_ts(now_sec + 80)),))
            plan_usage = (
                pb_num(1, 1427)
                + pb_num(2, 100_000)
                + pb_num(5, 900_000)
                + pb_num(9, 10_000)
                + pb_num(10, 10_000)
                + pb_str(11, "orch-plan-0")
            )
            plan_chat = pb_num(3, 1667) + pb_str(21, "Orchestrator (Carbon)") + pb_bytes(4, plan_usage)
            con_orch.execute("INSERT INTO gen_metadata VALUES (0, ?)", (pb_bytes(1, plan_chat) + pb_num(2, 0),))
            exec_usage = (
                pb_num(1, 1528)
                + pb_num(2, 100_000)
                + pb_num(5, 400_000)
                + pb_num(9, 5_000)
                + pb_num(10, 15_000)
                + pb_str(11, "orch-exec-0")
            )
            exec_chat = pb_num(3, 1667) + pb_str(21, "Orchestrator (Carbon)") + pb_bytes(4, exec_usage)
            con_orch.execute("INSERT INTO gen_metadata VALUES (1, ?)", (pb_bytes(1, exec_chat) + pb_num(2, 1),))
            con_orch.commit()
            con_orch.close()

            with (
                patch.object(gu.Path, "home", return_value=home),
                patch.object(gu, "_cache_dir", return_value=cache_dir),
            ):
                summary = gu.collect_summary(period="today", tz_name="UTC", refresh=True)

            models = {m["model"]: m for m in summary["byModel"]}
            self.assertIn("gemini-3.8-flash-n", models)
            self.assertIn("gemini-4-argon-trial", models)
            self.assertIn("gemini-4-carbon", models)
            self.assertIn("gemini-4-hydrogen", models)
            self.assertNotIn("claude-opus-4-5", models)

            m_flash_n = models["gemini-3.8-flash-n"]
            self.assertEqual(m_flash_n["events"], 2)
            self.assertEqual(m_flash_n["totalTokens"], 13500)
            self.assertTrue(m_flash_n["isVariantEndpoint"])
            self.assertGreater(m_flash_n["costUsd"], 0.0)
            self.assertEqual(m_flash_n["uiLabels"], ["Gemini 3.8 Flash"])

            raw_gray, *_ = gu._extract_agy_db_session(db_path)
            self.assertEqual(raw_gray[2]["reasoningTokens"], 300)
            self.assertEqual(raw_gray[2]["outputTokens"], 700)

            raw_orch, *_ = gu._extract_agy_db_session(orch_db)
            self.assertEqual(len(raw_orch), 2)
            self.assertEqual(raw_orch[0]["model"], "gemini-4-carbon")
            self.assertEqual(raw_orch[0]["reasoningTokens"], 10_000)
            self.assertEqual(raw_orch[0]["outputTokens"], 10_000)
            self.assertEqual(raw_orch[1]["model"], "gemini-4-hydrogen")
            self.assertEqual(raw_orch[1]["reasoningTokens"], 5_000)
            self.assertEqual(raw_orch[1]["outputTokens"], 15_000)

            m_argon_trial = models["gemini-4-argon-trial"]
            self.assertEqual(m_argon_trial["totalTokens"], 6000)
            self.assertEqual(m_argon_trial["outputTokens"], 1000)
            self.assertTrue(m_argon_trial["isVariantEndpoint"])
            self.assertGreater(m_argon_trial["costUsd"], 0.0)
            self.assertEqual(m_argon_trial["uiLabels"], ["Gemini 4 Argon (High)"])

            m_carbon = models["gemini-4-carbon"]
            self.assertAlmostEqual(m_carbon["costUsd"], 0.49, places=6)
            self.assertEqual(m_carbon["inputTokens"], 1_000_000)
            self.assertEqual(m_carbon["outputTokens"], 20_000)

            m_hydrogen = models["gemini-4-hydrogen"]
            self.assertAlmostEqual(m_hydrogen["costUsd"], 0.33, places=6)
            self.assertEqual(m_hydrogen["inputTokens"], 500_000)
            self.assertEqual(m_hydrogen["outputTokens"], 20_000)

            from luma_cli.metrics import agy_session_metrics
            orch_rows = agy_session_metrics("conv-orch", model="gemini-pro-orchestrator", home=home)
            self.assertIn({"label": gu._tr("CLI 累计估算 · USD", "CLI estimated cost · USD"), "value": "0.82"}, orch_rows)

    def test_antigravity_orchestrator_neon_worker_compaction_and_archive_migration(self):
        def pb_varint(val: int) -> bytes:
            out = bytearray()
            v = int(val)
            while v >= 0x80:
                out.append((v & 0x7F) | 0x80)
                v >>= 7
            out.append(v)
            return bytes(out)

        def pb_num(field: int, val: int) -> bytes:
            return pb_varint((field << 3) | 0) + pb_varint(val)

        def pb_bytes(field: int, raw: bytes) -> bytes:
            return pb_varint((field << 3) | 2) + pb_varint(len(raw)) + raw

        def pb_str(field: int, s: str) -> bytes:
            return pb_bytes(field, s.encode("utf-8"))

        def pb_ts(sec: int) -> bytes:
            return pb_num(1, sec)

        class _FrozenDt(dt.datetime):
            fixed_utc = dt.datetime(2026, 11, 19, 12, 0, tzinfo=dt.timezone.utc)

            @classmethod
            def now(cls, tz=None):
                return cls.fixed_utc.astimezone(tz) if tz is not None else cls.fixed_utc.replace(tzinfo=None)

        now_sec = int(dt.datetime(2026, 11, 19, 11, 50, tzinfo=dt.timezone.utc).timestamp())

        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            cache_dir = home / ".cache-neon"
            cache_dir.mkdir(parents=True)
            conv_dir = home / ".gemini" / "antigravity-cli" / "conversations"
            conv_dir.mkdir(parents=True)
            db_path = conv_dir / "conv-neon-orch.db"

            con = sqlite3.connect(str(db_path))
            con.execute("CREATE TABLE steps (idx INTEGER PRIMARY KEY, step_type INTEGER, metadata BLOB)")
            con.execute("CREATE TABLE gen_metadata (idx INTEGER PRIMARY KEY, data BLOB)")

            for s_idx in range(4):
                con.execute("INSERT INTO steps VALUES (?, 15, ?)", (s_idx, pb_bytes(1, pb_ts(now_sec + s_idx * 10))))

            neon_plan_u = (
                pb_num(1, 1537)
                + pb_num(2, 100_000)
                + pb_num(5, 400_000)
                + pb_num(9, 5_000)
                + pb_num(10, 5_000)
                + pb_str(11, "neon-plan-0")
            )
            neon_plan_c = (
                pb_num(3, 1537)
                + pb_str(19, "gemini-pro-orchestrator-neon")
                + pb_str(21, "Orchestrator (Neon)")
                + pb_bytes(4, neon_plan_u)
            )
            con.execute("INSERT INTO gen_metadata VALUES (0, ?)", (pb_bytes(1, neon_plan_c) + pb_num(2, 0),))

            work0_u = (
                pb_num(1, 1669)
                + pb_num(2, 200_000)
                + pb_num(5, 800_000)
                + pb_num(9, 10_000)
                + pb_num(10, 10_000)
                + pb_str(11, "neon-work-0")
            )
            work0_c = (
                pb_num(3, 1669)
                + pb_str(19, "gemini-flash-worker-agy")
                + pb_str(21, "Gemini 4 Hydrogen (Orchestrator)")
                + pb_bytes(4, work0_u)
            )
            con.execute("INSERT INTO gen_metadata VALUES (1, ?)", (pb_bytes(1, work0_c) + pb_num(2, 1),))

            work1_u = (
                pb_num(1, 1669)
                + pb_num(2, 100_000)
                + pb_num(10, 10_000)
                + pb_str(11, "neon-work-1")
            )
            work1_c = (
                pb_num(3, 1669)
                + pb_str(19, "gemini-flash-worker-agy")
                + pb_str(21, "Gemini Flash")
                + pb_bytes(4, work1_u)
            )
            con.execute("INSERT INTO gen_metadata VALUES (2, ?)", (pb_bytes(1, work1_c) + pb_num(2, 2),))

            carb_u = (
                pb_num(1, 1667)
                + pb_num(2, 100_000)
                + pb_num(5, 500_000)
                + pb_num(10, 10_000)
                + pb_str(11, "carb-plan-0")
            )
            carb_c = (
                pb_num(3, 1667)
                + pb_str(19, "gemini-pro-orchestrator")
                + pb_str(21, "Orchestrator (Carbon)")
                + pb_bytes(4, carb_u)
            )
            con.execute("INSERT INTO gen_metadata VALUES (3, ?)", (pb_bytes(1, carb_c) + pb_num(2, 3),))

            comp1_u9 = pb_num(1, 1537) + pb_num(2, 100_000) + pb_num(10, 10_000) + pb_str(11, "compact-1")
            comp1_u28 = pb_num(1, 0) + pb_num(2, 100_000) + pb_num(10, 10_000) + pb_str(11, "compact-1")
            con.execute(
                "INSERT INTO steps VALUES (4, 23, ?)",
                (pb_bytes(1, pb_ts(now_sec + 40)) + pb_bytes(9, comp1_u9) + pb_bytes(28, pb_bytes(2, comp1_u28)),),
            )

            comp2_u28 = pb_num(1, 0) + pb_num(2, 100_000) + pb_num(10, 10_000) + pb_str(11, "compact-2")
            con.execute(
                "INSERT INTO steps VALUES (5, 23, ?)",
                (pb_bytes(1, pb_ts(now_sec + 50)) + pb_bytes(20, pb_num(3, 1)) + pb_bytes(28, pb_bytes(2, comp2_u28)),),
            )

            con.execute(
                "INSERT INTO steps VALUES (6, 15, ?)",
                (pb_bytes(1, pb_ts(now_sec + 60)) + pb_bytes(9, neon_plan_u),),
            )
            con.commit()
            con.close()

            with (
                patch.object(gu.Path, "home", return_value=home),
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.object(gu.dt, "datetime", _FrozenDt),
            ):
                today_summary = gu.collect_summary(period="today", tz_name="UTC", refresh=True, sync_pricing=False)

                today_models = {m["model"]: m for m in today_summary["byModel"]}
                self.assertNotIn("gemini-flash-worker-agy", today_models)
                self.assertNotIn("gemini-pro-orchestrator-neon", today_models)
                self.assertNotIn("gemini-pro-orchestrator", today_models)
                self.assertIn("gemini-4-neon", today_models)
                self.assertIn("gemini-4-hydrogen", today_models)
                self.assertIn("gemini-4-carbon", today_models)

                m_neon = today_models["gemini-4-neon"]
                self.assertEqual(m_neon["events"], 2)
                self.assertEqual(m_neon["totalTokens"], 620_000)
                self.assertAlmostEqual(m_neon["costUsd"], 0.96, places=6)
                self.assertEqual(m_neon["costSource"], "modelPriced")

                m_hyd = today_models["gemini-4-hydrogen"]
                self.assertEqual(m_hyd["events"], 3)
                self.assertEqual(m_hyd["totalTokens"], 1_240_000)
                self.assertAlmostEqual(m_hyd["costUsd"], 0.96, places=6)
                self.assertEqual(m_hyd["costSource"], "modelPriced")
                self.assertEqual(m_hyd["uiLabels"], ["Gemini 4 Hydrogen (Orchestrator)"])

                m_carb = today_models["gemini-4-carbon"]
                self.assertEqual(m_carb["events"], 1)
                self.assertEqual(m_carb["totalTokens"], 610_000)
                self.assertAlmostEqual(m_carb["costUsd"], 0.35, places=6)

                from luma_cli.metrics import agy_session_metrics
                session_rows = agy_session_metrics("conv-neon-orch", model="gemini-pro-orchestrator-neon", home=home)
                self.assertIn({"label": gu._tr("CLI 累计估算 · USD", "CLI estimated cost · USD"), "value": "2.27"}, session_rows)

                old_archive = {
                    "version": gu.ARCHIVE_VERSION,
                    "scannerRev": 4,
                    "seededTimezones": ["UTC"],
                    "byTz": {
                        "UTC": {
                            "2026-11-19": {
                                "Antigravity::gemini-flash-worker-agy": {
                                    "cli": "Antigravity",
                                    "model": "gemini-flash-worker-agy",
                                    "events": 2,
                                    "uncachedInputTokens": 300_000,
                                    "cachedInputTokens": 800_000,
                                    "cacheCreationTokens": 0,
                                    "outputTokens": 20_000,
                                    "reasoningTokens": 10_000,
                                    "totalTokens": 1_130_000,
                                    "costUsd": 0.0,
                                    "providerReportedCostUsd": 0.0,
                                    "modelPricedCostUsd": 0.0,
                                    "unpricedEvents": 2,
                                    "unpricedTokens": 1_130_000,
                                    "uiLabels": ["Gemini Flash"],
                                },
                                "Antigravity::gemini-pro-orchestrator-neon": {
                                    "cli": "Antigravity",
                                    "model": "gemini-pro-orchestrator-neon",
                                    "events": 1,
                                    "uncachedInputTokens": 100_000,
                                    "cachedInputTokens": 400_000,
                                    "cacheCreationTokens": 0,
                                    "outputTokens": 5_000,
                                    "reasoningTokens": 5_000,
                                    "totalTokens": 510_000,
                                    "costUsd": 0.34,
                                    "providerReportedCostUsd": 0.0,
                                    "modelPricedCostUsd": 0.34,
                                    "unpricedEvents": 0,
                                    "unpricedTokens": 0,
                                    "uiLabels": ["Orchestrator (Neon)"],
                                },
                            },
                            "2026-11-18": {
                                "Antigravity::gemini-flash-worker-agy": {
                                    "cli": "Antigravity",
                                    "model": "gemini-flash-worker-agy",
                                    "events": 1,
                                    "uncachedInputTokens": 100_000,
                                    "cachedInputTokens": 0,
                                    "cacheCreationTokens": 0,
                                    "outputTokens": 10_000,
                                    "reasoningTokens": 0,
                                    "totalTokens": 110_000,
                                    "costUsd": 0.0,
                                    "providerReportedCostUsd": 0.0,
                                    "modelPricedCostUsd": 0.0,
                                    "unpricedEvents": 1,
                                    "unpricedTokens": 110_000,
                                    "uiLabels": ["Gemini Flash"],
                                    "repriceBuckets": {
                                        "|0|0": {
                                            "speed": None,
                                            "events": 1,
                                            "uncachedInputTokens": 100_000,
                                            "cachedInputTokens": 0,
                                            "cacheCreationTokens": 0,
                                            "outputTokens": 10_000,
                                            "reasoningTokens": 0,
                                        }
                                    },
                                },
                                "Antigravity::gemini-4-hydrogen": {
                                    "cli": "Antigravity",
                                    "model": "gemini-4-hydrogen",
                                    "events": 1,
                                    "uncachedInputTokens": 200_000,
                                    "cachedInputTokens": 0,
                                    "cacheCreationTokens": 0,
                                    "outputTokens": 0,
                                    "reasoningTokens": 0,
                                    "totalTokens": 200_000,
                                    "costUsd": 0.30,
                                    "providerReportedCostUsd": 0.0,
                                    "modelPricedCostUsd": 0.30,
                                    "unpricedEvents": 0,
                                    "unpricedTokens": 0,
                                    "uiLabels": ["Gemini 4 Hydrogen (High)"],
                                    "repriceBuckets": {
                                        "|0|0": {
                                            "speed": None,
                                            "events": 1,
                                            "uncachedInputTokens": 200_000,
                                            "cachedInputTokens": 0,
                                            "cacheCreationTokens": 0,
                                            "outputTokens": 0,
                                            "reasoningTokens": 0,
                                        }
                                    },
                                },
                                "Antigravity::gemini-pro-orchestrator-neon": {
                                    "cli": "Antigravity",
                                    "model": "gemini-pro-orchestrator-neon",
                                    "events": 1,
                                    "uncachedInputTokens": 100_000,
                                    "cachedInputTokens": 0,
                                    "cacheCreationTokens": 0,
                                    "outputTokens": 0,
                                    "reasoningTokens": 0,
                                    "totalTokens": 100_000,
                                    "costUsd": 0.20,
                                    "providerReportedCostUsd": 0.0,
                                    "modelPricedCostUsd": 0.20,
                                    "unpricedEvents": 0,
                                    "unpricedTokens": 0,
                                    "uiLabels": ["Orchestrator (Neon)"],
                                    "repriceBuckets": {
                                        "|0|0": {
                                            "speed": None,
                                            "events": 1,
                                            "uncachedInputTokens": 100_000,
                                            "cachedInputTokens": 0,
                                            "cacheCreationTokens": 0,
                                            "outputTokens": 0,
                                            "reasoningTokens": 0,
                                        }
                                    },
                                },
                            },
                        }
                    },
                }
                gu._save_daily_archive(old_archive)

                week_summary = gu.collect_summary(period="week", tz_name="UTC", refresh=False, sync_pricing=False)
                week_models = {m["model"]: m for m in week_summary["byModel"]}
                self.assertNotIn("gemini-flash-worker-agy", week_models)
                self.assertNotIn("gemini-pro-orchestrator-neon", week_models)
                self.assertEqual(week_models["gemini-4-hydrogen"]["totalTokens"], 1_550_000)
                self.assertAlmostEqual(week_models["gemini-4-hydrogen"]["costUsd"], 1.485, places=6)
                self.assertEqual(week_models["gemini-4-neon"]["totalTokens"], 720_000)
                self.assertAlmostEqual(week_models["gemini-4-neon"]["costUsd"], 1.26, places=6)
                self.assertEqual(week_models["gemini-4-carbon"]["totalTokens"], 610_000)
                self.assertAlmostEqual(week_models["gemini-4-carbon"]["costUsd"], 0.35, places=6)

                migrated_arch = gu._load_daily_archive()
                self.assertEqual(migrated_arch.get("scannerRev"), gu.SCANNER_REV)
                utc_days = migrated_arch["byTz"]["UTC"]
                self.assertNotIn("Antigravity::gemini-flash-worker-agy", utc_days["2026-11-19"])
                self.assertNotIn("Antigravity::gemini-pro-orchestrator-neon", utc_days["2026-11-19"])
                self.assertNotIn("Antigravity::gemini-flash-worker-agy", utc_days["2026-11-18"])
                self.assertNotIn("Antigravity::gemini-pro-orchestrator-neon", utc_days["2026-11-18"])
                self.assertEqual(
                    utc_days["2026-11-18"]["Antigravity::gemini-4-hydrogen"]["uiLabels"],
                    ["Gemini 4 Hydrogen (High)", "Gemini 4 Hydrogen (Orchestrator)"],
                )

    def test_codex_rerouted_endpoint_and_daily_archive_retention(self):
        now_utc = dt.datetime.now(dt.timezone.utc)
        yesterday_utc = now_utc - dt.timedelta(days=1)

        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            cache_dir = home / ".cache-arch"
            cache_dir.mkdir(parents=True)
            sess_dir = home / ".codex" / "sessions" / "2026" / "11"
            sess_dir.mkdir(parents=True)
            rollout = sess_dir / "rollout-1.jsonl"
            lines = [
                json.dumps({"type": "session_meta", "timestamp": yesterday_utc.isoformat(), "payload": {"model": "gpt-5.4"}}),
                json.dumps({"type": "event_msg", "timestamp": yesterday_utc.isoformat(), "payload": {"type": "model_rerouted", "to_model": "gpt-5.4-codex-exp"}}),
                json.dumps({
                    "type": "event_msg",
                    "timestamp": yesterday_utc.isoformat(),
                    "payload": {
                        "type": "token_count",
                        "info": {
                            "last_token_usage": {
                                "input_tokens": 10000,
                                "cached_input_tokens": 4000,
                                "output_tokens": 2000,
                                "reasoning_output_tokens": 600,
                            }
                        },
                    },
                }),
            ]
            rollout.write_text("\n".join(lines) + "\n", "utf-8")

            with (
                patch.object(gu.Path, "home", return_value=home),
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.dict(os.environ, {"CODEX_HOME": str(home / ".codex")}, clear=False),
            ):
                s1 = gu.collect_summary(period="all", tz_name="UTC", refresh=True)
                self.assertEqual(s1["totals"]["totalTokens"], 12000)
                self.assertEqual(s1["totals"]["outputTokens"], 1400)
                self.assertEqual(s1["totals"]["reasoningTokens"], 600)
                self.assertEqual(s1["byModel"][0]["model"], "gpt-5.4-codex-exp")
                self.assertTrue(s1["byModel"][0]["isVariantEndpoint"])
                self.assertEqual(s1["activity"]["activeDays"], 1)
                self.assertEqual(s1["activity"]["currentStreak"], 1)
                self.assertGreater(s1["rolling30dCostByCli"].get("Codex", 0.0), 0.0)

                rollout.unlink()
                s2 = gu.collect_summary(period="all", tz_name="UTC", refresh=True)
                self.assertEqual(s2["totals"]["totalTokens"], 12000)
                self.assertEqual(s2["byModel"][0]["model"], "gpt-5.4-codex-exp")
                self.assertGreater(s2["rolling30dCostByCli"].get("Codex", 0.0), 0.0)

    def test_tiered_cliff_pricing_above_200k_prompt_tokens(self):
        below = gu._price_record({
            "model": "claude-sonnet-4-6",
            "uncachedInputTokens": 200_000,
            "cachedInputTokens": 0,
            "cacheCreationTokens": 0,
            "outputTokens": 2_000,
            "reasoningTokens": 0,
        })
        self.assertAlmostEqual(below["costUsd"], 200_000 * 3.0 / 1e6 + 2_000 * 15.0 / 1e6, places=6)

        above = gu._price_record({
            "model": "claude-sonnet-4-6",
            "uncachedInputTokens": 50_000,
            "cachedInputTokens": 160_000,
            "cacheCreationTokens": 0,
            "outputTokens": 2_000,
            "reasoningTokens": 0,
        })
        self.assertAlmostEqual(
            above["costUsd"],
            50_000 * 6.0 / 1e6 + 160_000 * 0.60 / 1e6 + 2_000 * 22.50 / 1e6,
            places=6,
        )

        cat = gu._build_runtime_catalog(
            {
                "claude-sonnet-4-6": (3.0, 15.0, 0.3, 3.75, None),
                "claude-sonnet-4": (3.3, 16.5, 3.3, 3.3, None),
            },
            mode="cached",
            updated_at="2026-11-14T00:00:00Z",
            active_sources=["models.dev"],
        )
        merged_above = gu._price_record(
            {
                "model": "claude-sonnet-4-6",
                "uncachedInputTokens": 50_000,
                "cachedInputTokens": 160_000,
                "cacheCreationTokens": 0,
                "outputTokens": 2_000,
                "reasoningTokens": 0,
            },
            catalog=cat,
        )
        self.assertAlmostEqual(
            merged_above["costUsd"],
            50_000 * 6.0 / 1e6 + 160_000 * 0.60 / 1e6 + 2_000 * 22.50 / 1e6,
            places=6,
        )
        self.assertIsNone(gu._lookup_model_rate_in_catalog("claude-sonnet-4", cat)[4])

    def test_codex_partial_jsonl_line_and_today_archive_consistency(self):
        now_utc = dt.datetime.now(dt.timezone.utc)
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            cache_dir = home / ".cache-partial"
            cache_dir.mkdir(parents=True)
            sess_dir = home / ".codex" / "sessions" / "2026" / "11"
            sess_dir.mkdir(parents=True)
            rollout = sess_dir / "rollout-partial.jsonl"
            meta = json.dumps({"type": "session_meta", "timestamp": now_utc.isoformat(), "payload": {"model": "gpt-5.4"}})
            tok = json.dumps({
                "type": "event_msg",
                "timestamp": now_utc.isoformat(),
                "payload": {"type": "token_count", "info": {"last_token_usage": {"input_tokens": 1000, "cached_input_tokens": 0, "output_tokens": 500}}},
            })
            rollout.write_text(meta + "\n" + tok, "utf-8")
            with (
                patch.object(gu.Path, "home", return_value=home),
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.dict(os.environ, {"CODEX_HOME": str(home / ".codex")}, clear=False),
            ):
                s_before_nl = gu.collect_summary(period="today", tz_name="UTC", refresh=True)
                self.assertEqual(s_before_nl["totals"]["totalTokens"], 0)

                rollout.write_text(meta + "\n" + tok + "\n", "utf-8")
                s_after_nl = gu.collect_summary(period="today", tz_name="UTC", refresh=True)
                self.assertEqual(s_after_nl["totals"]["totalTokens"], 1500)

                rollout.unlink()
                s_today = gu.collect_summary(period="today", tz_name="UTC", refresh=True)
                s_week = gu.collect_summary(period="week", tz_name="UTC", refresh=False)
                self.assertEqual(s_today["totals"]["totalTokens"], 1500)
                self.assertEqual(s_week["totals"]["totalTokens"], 1500)

    def test_online_pricing_catalog_sync_ttl_zero_plan_filter_and_archive_repricing(self):
        class _FrozenDt(dt.datetime):
            fixed_utc = dt.datetime(2026, 11, 18, 12, 0, tzinfo=dt.timezone.utc)
            @classmethod
            def now(cls, tz=None):
                return cls.fixed_utc.astimezone(tz) if tz is not None else cls.fixed_utc.replace(tzinfo=None)

        now_utc = _FrozenDt.fixed_utc
        yesterday_utc = now_utc - dt.timedelta(days=1)
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            cache_dir = home / ".cache-online"
            cache_dir.mkdir(parents=True)
            sess_dir = home / ".codex" / "sessions" / "2026" / "11"
            sess_dir.mkdir(parents=True)
            rollout = sess_dir / "rollout-reprice.jsonl"
            meta = json.dumps({"type": "session_meta", "timestamp": yesterday_utc.isoformat(), "payload": {"model": "kimi-k2.5"}})
            tok = json.dumps({
                "type": "event_msg",
                "timestamp": yesterday_utc.isoformat(),
                "payload": {"type": "token_count", "info": {"last_token_usage": {"input_tokens": 1_000_000, "cached_input_tokens": 0, "output_tokens": 1_000_000}}},
            })
            rollout.write_text(meta + "\n" + tok + "\n", "utf-8")

            with (
                patch.object(gu.Path, "home", return_value=home),
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.object(gu.dt, "datetime", _FrozenDt),
                patch.dict(os.environ, {"CODEX_HOME": str(home / ".codex")}, clear=False),
            ):
                offline_summary = gu.collect_summary(period="week", tz_name="UTC", refresh=True)
                self.assertEqual(offline_summary["pricingSource"]["mode"], "builtin")
                self.assertAlmostEqual(offline_summary["totals"]["costUsd"], 3.60, places=5)

                rollout.unlink()

                fetch_calls = []
                md_v1 = {
                    "alibaba-token-plan-cn": {
                        "models": {
                            "kimi-k2.5": {"cost": {"input": 0, "output": 0, "cache_read": 0, "cache_write": 0}},
                        },
                    },
                    "alibaba": {
                        "models": {
                            "qwen3-coder-plus": {"cost": {"input": 1.0, "output": 5.0}},
                            "deepseek-v4-pro": {"cost": {"input": 9.9, "output": 19.9}},
                        },
                    },
                    "deepseek": {
                        "models": {
                            "deepseek-v4-pro": {"cost": {"input": 0.66, "output": 1.98, "cache_read": 0.022}},
                        },
                    },
                    "openai": {
                        "models": {
                            "gpt-5.4": {
                                "cost": {
                                    "input": 2.5,
                                    "output": 15.0,
                                    "cache_read": 0.25,
                                    "tiers": [{"input": 5.0, "output": 22.5, "cache_read": 0.5, "tier": {"type": "context", "size": 272000}}],
                                },
                            },
                        },
                    },
                }
                ll_v1 = {
                    "moonshot/kimi-k2.5": {
                        "input_cost_per_token": 0.8e-6,
                        "output_cost_per_token": 4.0e-6,
                        "cache_read_input_token_cost": 0.1e-6,
                    },
                }

                def fake_fetch_v1(url, timeout=6.5):
                    fetch_calls.append(url)
                    if url == gu.MODELS_DEV_API_URL:
                        return md_v1
                    if url == gu.LITELLM_PRICES_URLS[0]:
                        return None
                    if url == gu.LITELLM_PRICES_URLS[1]:
                        return ll_v1
                    return None

                with patch.object(gu, "_fetch_remote_json", side_effect=fake_fetch_v1):
                    online_s1 = gu.collect_summary(period="week", tz_name="UTC", refresh=True)
                    self.assertEqual(online_s1["pricingSource"]["mode"], "online")
                    self.assertTrue(all(s["active"] for s in online_s1["pricingSource"]["sources"]))
                    self.assertAlmostEqual(online_s1["totals"]["costUsd"], 4.80, places=5)
                    self.assertEqual(gu._lookup_model_rate("deepseek-v4-pro"), (0.66, 1.98, 0.022, 0.66, None))
                    self.assertEqual(gu._lookup_model_rate("qwen3-coder-plus"), (1.0, 5.0, 0.1, 1.0, None))
                    self.assertEqual(gu._lookup_model_rate("gpt-5.4"), (2.5, 15.0, 0.25, 2.5, (272000, 5.0, 22.5, 0.5, 5.0)))

                    calls_after_first = len(fetch_calls)
                    gu._PRICING_RUNTIME_STATE["catalog"] = None
                    cached_s2 = gu.collect_summary(period="week", tz_name="UTC", refresh=False)
                    self.assertEqual(len(fetch_calls), calls_after_first)
                    self.assertEqual(cached_s2["pricingSource"]["mode"], "cached")
                    self.assertAlmostEqual(cached_s2["totals"]["costUsd"], 4.80, places=5)

                ll_v2 = {
                    "moonshot/kimi-k2.5": {
                        "input_cost_per_token": 1.2e-6,
                        "output_cost_per_token": 6.0e-6,
                        "cache_read_input_token_cost": 0.2e-6,
                    },
                }

                def fake_fetch_v2(url, timeout=6.5):
                    if url == gu.MODELS_DEV_API_URL:
                        return md_v1
                    return ll_v2

                with patch.object(gu, "_fetch_remote_json", side_effect=fake_fetch_v2):
                    repriced_s3 = gu.collect_summary(period="week", tz_name="UTC", refresh=True)
                    self.assertAlmostEqual(repriced_s3["totals"]["costUsd"], 7.20, places=5)
                    self.assertAlmostEqual(repriced_s3["rolling30dCostByCli"]["Codex"], 7.20, places=5)

    def test_grok_deepseek_kimi_and_qoder_scanners(self):
        now_utc = dt.datetime.now(dt.timezone.utc)
        now_ms = int(now_utc.timestamp() * 1000)
        nano_iso = now_utc.strftime("%Y-%m-%dT%H:%M:%S") + ".546962688+00:00"
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            cache_dir = home / ".cache-multi"
            cache_dir.mkdir(parents=True)

            grok_s1 = home / ".grok" / "sessions" / "%2Fworkspace" / "sess-uj"
            grok_s1.mkdir(parents=True)
            (grok_s1 / "usage.json").write_text(
                json.dumps({
                    "sessionId": "sess-uj",
                    "updatedAt": nano_iso,
                    "session": {"primaryModelId": "grok-4.7-build-fast"},
                    "turns": [
                        {
                            "turnNumber": 1,
                            "endedAt": nano_iso,
                            "modelUsage": {
                                "grok-4.7-build-fast": {
                                    "inputTokens": 80_000,
                                    "cachedReadTokens": 50_000,
                                    "cacheCreationTokens": 0,
                                    "outputTokens": 1_000,
                                    "reasoningTokens": 600,
                                    "costUsdTicks": 1_820_000_000,
                                }
                            },
                        }
                    ],
                }),
                "utf-8",
            )
            (grok_s1 / "updates.jsonl").write_text(
                json.dumps({
                    "timestamp": int(now_utc.timestamp()),
                    "method": "_x.ai/session/update",
                    "params": {
                        "sessionId": "sess-uj",
                        "update": {
                            "sessionUpdate": "turn_completed",
                            "prompt_id": "dup-prompt",
                            "usage": {
                                "modelUsage": {
                                    "grok-4.7-build-fast": {
                                        "inputTokens": 80_000,
                                        "cachedReadTokens": 50_000,
                                        "outputTokens": 1_000,
                                        "reasoningTokens": 600,
                                        "costUsdTicks": 1_820_000_000,
                                    }
                                }
                            },
                        },
                    },
                }) + "\n",
                "utf-8",
            )

            grok_s2 = home / ".grok" / "sessions" / "%2Fworkspace" / "sess-rpc"
            grok_s2.mkdir(parents=True)
            (grok_s2 / "updates.jsonl").write_text(
                json.dumps({
                    "timestamp": int(now_utc.timestamp()),
                    "method": "_x.ai/session/update",
                    "params": {
                        "sessionId": "sess-rpc",
                        "update": {
                            "sessionUpdate": "turn_completed",
                            "prompt_id": "p-rpc-1",
                            "usage": {
                                "modelUsage": {
                                    "grok-4.7-build": {
                                        "inputTokens": 20_000,
                                        "cachedReadTokens": 5_000,
                                        "outputTokens": 500,
                                        "reasoningTokens": 200,
                                        "costUsdTicks": 355_000_000,
                                    }
                                }
                            },
                        },
                        "_meta": {"agentTimestampMs": now_ms},
                    },
                }) + "\n",
                "utf-8",
            )

            dsh_dir = home / ".dsh" / "sessions" / "--workspace--" / "dsh-sess-1"
            dsh_dir.mkdir(parents=True)
            dsh_events = [
                {"type": "session", "seq": 0, "time": now_ms - 5000, "id": "dsh-sess-1", "seedLength": 2},
                {
                    "type": "assistant/message",
                    "seq": 1,
                    "time": now_ms - 4000,
                    "data": {
                        "turn": 0,
                        "step": 1,
                        "message": {"id": "seeded-msg", "source": {"provider": "deepseek-official", "model": "deepseek-flash"}},
                        "usage": {"inputTokens": 99_999, "outputTokens": 9_999, "cacheReadTokens": 0, "cacheWriteTokens": 0},
                    },
                },
                {
                    "type": "request/header",
                    "seq": 2,
                    "time": now_ms - 3000,
                    "data": {"header": {"config": {"provider": "deepseek-official", "model": "deepseek-flash"}}},
                },
                {"type": "step/start", "seq": 3, "time": now_ms - 2500, "data": {"turn": 1, "step": 1}},
                {
                    "type": "assistant/attempt",
                    "seq": 4,
                    "time": now_ms - 2200,
                    "data": {
                        "turn": 1,
                        "step": 1,
                        "attemptId": "att-1",
                        "stream": [{"chunk": {"type": "usage", "usage": {"inputTokens": 1_000, "outputTokens": 100, "cacheReadTokens": 500, "cacheWriteTokens": 0}}}],
                    },
                },
                {
                    "type": "assistant/message",
                    "seq": 5,
                    "time": now_ms - 2000,
                    "data": {
                        "turn": 1,
                        "step": 1,
                        "message": {"id": "msg-final-1", "source": {"provider": "deepseek-official", "model": "deepseek-flash"}},
                        "usage": {"inputTokens": 4_000, "outputTokens": 500, "reasoningTokens": 200, "cacheReadTokens": 2_000, "cacheWriteTokens": 0},
                    },
                },
                {"type": "llm/retry-started", "seq": 6, "time": now_ms - 1800, "data": {"turn": 1, "step": 1}},
                {
                    "type": "assistant/message",
                    "seq": 7,
                    "time": now_ms - 1500,
                    "data": {
                        "turn": 1,
                        "step": 1,
                        "message": {"id": "msg-retry-1", "source": {"provider": "deepseek-official", "model": "deepseek-v4-pro"}},
                        "usage": {"inputTokens": 3_000, "outputTokens": 400, "cacheReadTokens": 1_000, "cacheWriteTokens": 0},
                    },
                },
                {
                    "type": "compaction/summary",
                    "seq": 8,
                    "time": now_ms - 1000,
                    "data": {
                        "compactionId": "cmp-1",
                        "usage": {"inputTokens": 1_000, "outputTokens": 200, "cacheReadTokens": 0, "cacheWriteTokens": 0},
                    },
                },
                {
                    "type": "assistant/message",
                    "seq": 9,
                    "time": now_ms - 800,
                    "data": {
                        "turn": 2,
                        "step": 1,
                        "message": {"id": "migrated-1", "source": {"provider": "migrated:claude-code", "model": "deepseek-flash"}},
                        "usage": {"inputTokens": 50_000, "outputTokens": 5_000},
                    },
                },
            ]
            _write_zstd_events(dsh_dir / "session.v4.jsonl.zstd", dsh_events)

            kimi_cli_root = home / ".kimi"
            kimi_sess = kimi_cli_root / "sessions" / "grp1" / "uuid-1"
            kimi_sess.mkdir(parents=True)
            (kimi_cli_root / "config.toml").write_text(
                'default_model = "kimi-code/default"\n\n[models."kimi-code/default"]\nprovider = "managed:kimi-code"\nmodel = "kimi-for-coding"\nmax_context_size = 262144\n',
                "utf-8",
            )
            (kimi_sess / "wire.jsonl").write_text(
                "\n".join([
                    json.dumps({"type": "metadata", "protocol_version": "1.3"}),
                    json.dumps({
                        "timestamp": now_utc.timestamp() - 60,
                        "message": {
                            "type": "StatusUpdate",
                            "payload": {
                                "message_id": "chatcmpl-k1",
                                "token_usage": {"input_other": 2_000, "output": 500, "input_cache_read": 8_000, "input_cache_creation": 0},
                            },
                        },
                    }),
                ]) + "\n",
                "utf-8",
            )

            kimi_code_agent = home / ".kimi-code" / "sessions" / "ws-slug" / "kc-sess-1" / "agents" / "main"
            kimi_code_agent.mkdir(parents=True)
            (kimi_code_agent / "wire.jsonl").write_text(
                "\n".join([
                    json.dumps({"type": "llm.request", "model": "kimi-code/kimi-k2.6", "time": now_ms - 30_000}),
                    json.dumps({
                        "type": "usage.record",
                        "usageScope": "turn",
                        "model": "default",
                        "time": now_ms - 28_000,
                        "usage": {"inputOther": 3_000, "output": 1_000, "inputCacheRead": 4_000, "inputCacheCreation": 0},
                    }),
                    json.dumps({
                        "type": "usage.record",
                        "usageScope": "session",
                        "time": now_ms - 28_000,
                        "usage": {"inputOther": 99_000, "output": 99_000},
                    }),
                ]) + "\n",
                "utf-8",
            )

            qoder_proj = home / ".qoder" / "projects" / "-workspace"
            qoder_proj.mkdir(parents=True)
            (qoder_proj / "q1.jsonl").write_text(
                "\n".join([
                    json.dumps({"type": "assistant", "timestamp": now_utc.isoformat(), "message": {"id": "syn-1", "model": "<synthetic>", "usage": {"input_tokens": 500, "output_tokens": 500}}}),
                    json.dumps({"type": "assistant", "timestamp": now_utc.isoformat(), "message": {"id": "q-real-1", "model": "qwen3-coder-plus", "usage": {"input_tokens": 2_000, "cache_read_input_tokens": 1_000, "output_tokens": 400}}}),
                ]) + "\n",
                "utf-8",
            )

            env_overrides = {
                "GROK_HOME": str(home / ".grok"),
                "DSH_HOME": str(home / ".dsh"),
                "KIMI_SHARE_DIR": str(kimi_cli_root),
                "KIMI_CODE_HOME": str(home / ".kimi-code"),
                "QODER_HOME": str(home / ".qoder"),
            }
            with (
                patch.object(gu.Path, "home", return_value=home),
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.dict(os.environ, env_overrides, clear=False),
            ):
                s = gu.collect_summary(period="today", tz_name="UTC", refresh=True, sync_pricing=False)
                by_cli = {row["cli"]: row for row in s["byProvider"]}
                self.assertIn("Grok Build", by_cli)
                self.assertIn("DeepSeek Harness", by_cli)
                self.assertIn("Kimi Code", by_cli)
                self.assertIn("Qoder CLI", by_cli)

                self.assertEqual(by_cli["Grok Build"]["totalTokens"], 81_000 + 20_500)
                self.assertAlmostEqual(by_cli["Grok Build"]["costUsd"], 0.182 + 0.0355, places=6)

                self.assertEqual(by_cli["DeepSeek Harness"]["totalTokens"], 6_500 + 4_400 + 1_200)
                self.assertGreater(by_cli["DeepSeek Harness"]["costUsd"], 0.0)

                self.assertEqual(by_cli["Kimi Code"]["totalTokens"], 10_500 + 8_000)
                self.assertGreater(by_cli["Kimi Code"]["costUsd"], 0.0)

                self.assertEqual(by_cli["Qoder CLI"]["totalTokens"], 3_400)

    def test_kimi_limits_probe_parses_weekly_and_rolling_windows(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            cache_dir = home / ".cache-kimi-lim"
            cache_dir.mkdir(parents=True)
            cred_dir = home / ".kimi" / "credentials"
            cred_dir.mkdir(parents=True)
            (cred_dir / "kimi-code.json").write_text(
                json.dumps({
                    "access_token": "tok-kimi-live",
                    "refresh_token": "ref-kimi",
                    "expires_at": time.time() + 3600,
                }),
                "utf-8",
            )

            class FakeResp:
                def __init__(self, payload: dict):
                    self._raw = json.dumps(payload).encode("utf-8")
                def read(self):
                    return self._raw
                def __enter__(self):
                    return self
                def __exit__(self, *args):
                    return False

            usages_payload = {
                "user": {"membership": {"level": "LEVEL_ADVANCED"}},
                "usage": {"limit": "100", "remaining": "75", "resetTime": "2026-11-20T08:00:00.123456789Z"},
                "limits": [
                    {
                        "window": {"duration": 300, "timeUnit": "TIME_UNIT_MINUTE"},
                        "detail": {"limit": "50", "used": "10", "resetTime": "2026-11-14T14:00:00Z"},
                    }
                ],
            }

            with (
                patch.object(gu.Path, "home", return_value=home),
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.dict(os.environ, {"KIMI_SHARE_DIR": str(home / ".kimi")}, clear=False),
                patch.object(gu, "_urlopen_auth", return_value=FakeResp(usages_payload)),
            ):
                lim = gu._probe_kimi_limits(home)
                self.assertEqual(lim["cli"], "Kimi Code")
                self.assertEqual(lim["status"], "ok")
                self.assertEqual(lim["planType"], "Advanced")
                self.assertEqual(len(lim["windows"]), 2)
                self.assertAlmostEqual(lim["windows"][0]["usedPercent"], 25.0, places=3)
                self.assertAlmostEqual(lim["windows"][1]["usedPercent"], 20.0, places=3)
                self.assertEqual(lim["windows"][1]["windowDurationMs"], 5 * 3_600_000)

    def test_grok_grpc_fallback_and_cancelled_turn_recovery(self):
        now_utc = dt.datetime.now(dt.timezone.utc)
        now_ms = int(now_utc.timestamp() * 1000)
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            cache_dir = home / ".cache-grok-ext"
            cache_dir.mkdir(parents=True)
            grok_home = home / ".grok"
            covered_sess = grok_home / "sessions" / "%2Fworkspace" / "sess-covered"
            covered_sess.mkdir(parents=True)
            (covered_sess / "usage.json").write_text(
                json.dumps({
                    "sessionId": "sess-covered",
                    "updatedAt": now_utc.isoformat(),
                    "session": {"primaryModelId": "grok-4.7-build"},
                    "turns": [{
                        "turnNumber": 1,
                        "endedAt": now_utc.isoformat(),
                        "modelUsage": {"grok-4.7-build": {"inputTokens": 10_000, "cachedReadTokens": 2_000, "outputTokens": 500, "costUsdTicks": 200_000_000}},
                    }],
                }),
                "utf-8",
            )
            aborted_sess = grok_home / "sessions" / "%2Fworkspace" / "sess-aborted"
            aborted_sess.mkdir(parents=True)
            logs_dir = grok_home / "logs"
            logs_dir.mkdir(parents=True)
            (logs_dir / "unified.jsonl").write_text(
                "\n".join([
                    json.dumps({
                        "ts": now_utc.isoformat(),
                        "msg": "shell.turn.inference_done",
                        "sid": "sess-covered",
                        "ctx": {"model": "grok-4.7-build", "prompt_tokens": 99_000, "completion_tokens": 9_000},
                    }),
                    json.dumps({
                        "ts": now_utc.isoformat(),
                        "msg": "shell.turn.inference_done",
                        "sid": "sess-aborted",
                        "ctx": {"model": "grok-4.7-build", "prompt_tokens": 30_000, "cached_prompt_tokens": 10_000, "completion_tokens": 1_000},
                    }),
                ]) + "\n",
                "utf-8",
            )

            scope_key = "https://auth.x.ai::test-client-id"
            grok_auth_path = grok_home / "auth.json"
            grok_auth_raw = json.dumps({
                scope_key: {
                    "auth_mode": "oidc",
                    "email": "dev@example.com",
                    "key": "valid-oidc-tok",
                    "refresh_token": "ref-oidc-1",
                    "expires_at": int(now_utc.timestamp()) + 3600,
                }
            })
            grok_auth_path.write_text(grok_auth_raw, "utf-8")

            start_iso = (now_utc - dt.timedelta(days=1)).isoformat()
            end_iso = (now_utc + dt.timedelta(days=6)).isoformat()
            fake_grok = home / "fake-grok"
            fake_grok.write_text(
                f"#!{sys.executable}\n"
                "import json, os, sys, time\n"
                "assert sys.argv[1:] == ['--no-auto-update', 'agent', '--no-leader', 'stdio']\n"
                "mode = os.environ.get('FAKE_GROK_MODE', 'ok')\n"
                "init = json.loads(sys.stdin.readline())\n"
                "assert init['method'] == 'initialize'\n"
                "print(json.dumps({'jsonrpc': '2.0', 'id': init['id'], 'result': {'protocolVersion': 1}}), flush=True)\n"
                "req = json.loads(sys.stdin.readline())\n"
                "assert req['method'] == '_x.ai/billing'\n"
                "print(json.dumps({'jsonrpc': '2.0', 'method': '_x.ai/mcp/servers_updated', 'params': {'mcpServers': []}}), flush=True)\n"
                "if mode == 'sleep':\n"
                "    time.sleep(5)\n"
                "elif mode == 'auth_err':\n"
                "    print(json.dumps({'jsonrpc': '2.0', 'id': req['id'], 'error': {'code': -32001, 'message': 'Authentication required: please login'}}), flush=True)\n"
                "else:\n"
                "    print(json.dumps({'jsonrpc': '2.0', 'id': req['id'], 'result': {\n"
                "        'config': {\n"
                "            'creditUsagePercent': 88.0,\n"
                f"            'currentPeriod': {{'type': 'USAGE_PERIOD_TYPE_WEEKLY', 'start': {start_iso!r}, 'end': {end_iso!r}}},\n"
                "        },\n"
                "        'subscription_tier': 'SuperGrok',\n"
                "    }}), flush=True)\n",
                "utf-8",
            )
            fake_grok.chmod(0o755)

            with (
                patch.object(gu.Path, "home", return_value=home),
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.dict(os.environ, {"GROK_HOME": str(grok_home), "LUMA_GROK_BIN": str(fake_grok), "FAKE_GROK_MODE": "ok"}, clear=False),
                patch.object(gu, "_urlopen_auth") as mock_open,
            ):
                s = gu.collect_summary(period="today", tz_name="UTC", refresh=True, sync_pricing=False)
                by_cli = {row["cli"]: row for row in s["byProvider"]}
                self.assertEqual(by_cli["Grok Build"]["totalTokens"], 10_500 + 31_000)

                self.assertTrue(gu._has_grok_oauth(home))
                lim = gu._probe_grok_limits(home, timeout_sec=5.0)
                mock_open.assert_not_called()
                self.assertEqual(lim["status"], "ok")
                self.assertEqual(lim["source"], "live")
                self.assertEqual(lim["planType"], "SuperGrok")
                self.assertEqual(len(lim["windows"]), 1)
                self.assertAlmostEqual(lim["windows"][0]["usedPercent"], 88.0, places=2)
                self.assertEqual(lim["windows"][0]["windowDurationMs"], 7 * 86_400_000)
                self.assertEqual(grok_auth_path.read_text("utf-8"), grok_auth_raw)

            with patch.dict(os.environ, {"GROK_HOME": str(grok_home), "LUMA_GROK_BIN": str(fake_grok), "FAKE_GROK_MODE": "auth_err"}, clear=False):
                auth_res = gu._probe_grok_limits(home, timeout_sec=5.0)
            self.assertEqual(auth_res["status"], "error")
            self.assertIn(gu._tr("重新登录", "sign in"), auth_res["message"])

            with patch.dict(os.environ, {"GROK_HOME": str(grok_home), "LUMA_GROK_BIN": str(fake_grok), "FAKE_GROK_MODE": "sleep"}, clear=False):
                t0 = time.monotonic()
                to_res = gu._probe_grok_limits(home, timeout_sec=0.25)
            self.assertLess(time.monotonic() - t0, 2.0)
            self.assertEqual(to_res["status"], "error")
            self.assertIn(gu._tr("超时", "timed out"), to_res["message"])

    def test_deepseek_v3_seed_cut_limits_and_kimi_usages_pools(self):
        now_utc = dt.datetime.now(dt.timezone.utc)
        now_ms = int(now_utc.timestamp() * 1000)
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            cache_dir = home / ".cache-dsh-ext"
            cache_dir.mkdir(parents=True)

            dsh_home = home / ".dsh"
            dsh_sess = dsh_home / "sessions" / "--ws--" / "forked-v3"
            dsh_sess.mkdir(parents=True)
            (dsh_home / ".credentials.yaml").write_text(
                "DEEPSEEK_API_KEY: sk-test-ds\n",
                "utf-8",
            )
            v3_events = [
                {"type": "session", "seq": 0, "time": now_ms - 5000, "id": "forked-v3", "isSeeded": True},
                {
                    "type": "assistant/message",
                    "seq": 1,
                    "time": now_ms - 4500,
                    "data": {
                        "turn": 0,
                        "step": 1,
                        "message": {"id": "parent-m1", "source": {"provider": "deepseek-official", "model": "deepseek-v4-pro"}},
                        "usage": {"inputTokens": 80_000, "outputTokens": 8_000},
                    },
                },
                {"type": "session/end-seed", "seq": 2, "time": now_ms - 4000, "data": {"inherited": True}},
                {
                    "type": "request/header",
                    "seq": 3,
                    "time": now_ms - 3500,
                    "data": {"header": {"config": {"provider": "deepseek-official", "model": "deepseek-flash"}}},
                },
                {
                    "type": "assistant/message",
                    "seq": 4,
                    "time": now_ms - 3000,
                    "data": {
                        "turn": 1,
                        "step": 1,
                        "message": {"id": "child-m1", "source": {"provider": "deepseek-official", "replayState": {"response": {"responseModel": "keepalive"}}}},
                        "usage": {"inputTokens": 5_000, "outputTokens": 600, "cacheReadTokens": 1_000},
                    },
                },
            ]
            _write_zstd_events(dsh_sess / "session.v3.jsonl.zst", v3_events)

            kimi_code = home / ".kimi-code" / "sessions" / "ws" / "s1" / "agents" / "main"
            kimi_code.mkdir(parents=True)
            (kimi_code / "wire.jsonl").write_text(
                json.dumps({
                    "type": "usage.record",
                    "usageScope": "turn",
                    "model": "__kimi_env_model__",
                    "time": now_ms - 2000,
                    "usage": {"inputOther": 1_000, "output": 500},
                }) + "\n",
                "utf-8",
            )

            qoder_cfg = home / ".qoder-custom" / "logs" / "sessions" / "qs1" / "segments"
            qoder_cfg.mkdir(parents=True)
            (qoder_cfg / "seg-1.jsonl").write_text(
                "\n".join([
                    json.dumps({"type": "runtime-config", "timestamp": now_utc.isoformat(), "model": "claude-sonnet-4-5"}),
                    json.dumps({"type": "model.response.completed", "timestamp": now_utc.isoformat(), "usage": {"input_tokens": 1_200, "output_tokens": 300}}),
                ]) + "\n",
                "utf-8",
            )

            class FakeJsonResp:
                def __init__(self, obj: dict):
                    self._raw = json.dumps(obj).encode("utf-8")
                def read(self):
                    return self._raw
                def __enter__(self):
                    return self
                def __exit__(self, *args):
                    return False

            def fake_urlopen(req, timeout=None):
                url = req.full_url
                if "api.deepseek.com/user/balance" in url:
                    return FakeJsonResp({
                        "is_available": True,
                        "balance_infos": [{"currency": "CNY", "total_balance": "2.73", "granted_balance": "0.00", "topped_up_balance": "2.73"}],
                    })
                if "/usages" in url:
                    return FakeJsonResp({
                        "data": {
                            "usages": {
                                "limit_5h": {"used_ratio": 0.35, "reset_time": "2026-11-14T18:00:00Z"},
                                "limit_month_total": {"used_ratio": 0.62, "reset_time": "2026-11-30T00:00:00Z"},
                            }
                        }
                    })
                raise AssertionError(f"Unexpected URL: {url}")

            env = {
                "DSH_HOME": str(dsh_home),
                "KIMI_CODE_HOME": str(home / ".kimi-code"),
                "KIMI_CODE_API_KEY": "sk-kimi-env",
                "QODER_CONFIG_DIR": str(home / ".qoder-custom"),
            }
            with (
                patch.object(gu.Path, "home", return_value=home),
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.dict(os.environ, env, clear=False),
                patch.object(gu, "_urlopen_auth", side_effect=fake_urlopen),
            ):
                s = gu.collect_summary(period="today", tz_name="UTC", refresh=True, sync_pricing=False)
                by_cli = {row["cli"]: row for row in s["byProvider"]}
                by_model = {row["model"]: row for row in s["byModel"]}
                self.assertEqual(by_cli["DeepSeek Harness"]["totalTokens"], 6_600)
                self.assertIn("deepseek-flash", by_model)
                self.assertNotIn("keepalive", by_model)
                self.assertNotIn("__kimi_env_model__", by_model)
                self.assertEqual(by_cli["Qoder CLI"]["totalTokens"], 1_500)

                ds_lim = gu._probe_deepseek_limits(home)
                self.assertEqual(ds_lim["status"], "ok")
                self.assertEqual(ds_lim["credits"]["remaining"], 2.73)
                self.assertEqual(ds_lim["credits"]["unit"], "CNY")

                km_lim = gu._probe_kimi_limits(home)
                self.assertEqual(km_lim["status"], "ok")
                self.assertEqual(len(km_lim["windows"]), 2)
                self.assertAlmostEqual(km_lim["windows"][0]["usedPercent"], 35.0, places=2)
                self.assertAlmostEqual(km_lim["windows"][1]["usedPercent"], 62.0, places=2)

    def test_balances_regressions_http_error_json_nan_and_size_cap(self):
        with self.assertRaises(ValueError):
            balances._get("file:///etc/passwd", {})
        payload, err = balances._fetch_json("file:///etc/passwd", {})
        self.assertIsNone(payload)
        self.assertIn("Invalid URL scheme", err)

        http_exc = urllib.error.HTTPError("https://api.example.com", 401, "Unauthorized", {}, None)
        with (
            patch.object(http_exc, "close", wraps=http_exc.close) as close_mock,
            patch.object(balances.urllib.request, "urlopen", side_effect=http_exc),
        ):
            payload, err = balances._fetch_json("https://api.example.com/bal", {})
        self.assertIsNone(payload)
        self.assertEqual(err, "HTTP 401")
        close_mock.assert_called_once()

        class _BytesResp:
            status = 200
            def __init__(self, raw: bytes):
                self._raw = raw
            def read(self, n=-1):
                return self._raw if n is None or n < 0 else self._raw[:n]
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return False

        with patch.object(balances.urllib.request, "urlopen", return_value=_BytesResp(b"[]")):
            payload, err = balances._fetch_json("https://api.example.com/bal", {})
        self.assertIsNone(payload)
        self.assertIn("Invalid JSON", err)

        oversized = b"{" + (b" " * (balances.MAX_RESPONSE_BYTES + 16)) + b"}"
        with patch.object(balances.urllib.request, "urlopen", return_value=_BytesResp(oversized)):
            payload, err = balances._fetch_json("https://api.example.com/bal", {})
        self.assertIsNone(payload)
        self.assertIn("too large", err)

        self.assertEqual(balances._amount(True), "0.00")
        self.assertEqual(balances._amount(float("nan")), "0.00")
        self.assertEqual(balances._amount(float("inf")), "0.00")
        self.assertEqual(balances._amount(-0.0001), "0.00")
        self.assertEqual(balances._money("USD", "-0.0001"), "$0.00")

        with patch.object(balances.urllib.request, "urlopen", return_value=_BytesResp(b'{"balance_infos": "bad", "is_available": true}')):
            off = balances._official("DEEPSEEK_API_KEY: sk-bad\n")
        self.assertEqual(off, {"status": "ready", "available": True, "wallets": []})

        with patch.object(balances.urllib.request, "urlopen", return_value=_BytesResp(b'{"data": "bad"}')):
            acc = balances._account("deepseek-account-platform/default:\n  token: tok\n")
        self.assertEqual(acc, {"status": "ready", "wallets": [], "bonus": [], "spent": []})

    def test_quota_probes_are_strictly_readonly_and_respect_429_cooldown(self):
        class _JsonResp:
            def __init__(self, data: dict):
                self._raw = json.dumps(data).encode("utf-8")
            def read(self, n: int = -1):
                return self._raw if n < 0 else self._raw[:n]
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return False

        now_sec = time.time()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cache_dir = root / ".cache-quota"
            cache_dir.mkdir(parents=True)

            claude_dir = root / ".claude"
            claude_dir.mkdir(parents=True)
            claude_cred = claude_dir / ".credentials.json"
            expired_claude_raw = json.dumps({
                "claudeAiOauth": {
                    "accessToken": "claude-expired-secret",
                    "refreshToken": "claude-ref",
                    "subscriptionType": "max",
                    "expiresAt": int((now_sec - 120) * 1000),
                }
            })
            claude_cred.write_text(expired_claude_raw, "utf-8")

            kimi_dir = root / ".kimi" / "credentials"
            kimi_dir.mkdir(parents=True)
            kimi_cred = kimi_dir / "kimi-code.json"
            expired_kimi_raw = json.dumps({
                "access_token": "kimi-expired-secret",
                "refresh_token": "kimi-ref",
                "expires_at": now_sec - 120,
            })
            kimi_cred.write_text(expired_kimi_raw, "utf-8")

            with (
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.dict(os.environ, {"KIMI_SHARE_DIR": str(root / ".kimi")}, clear=False),
                patch.object(gu, "_urlopen_auth") as mock_open,
            ):
                c_exp = gu._probe_claude_limits(root)
                k_exp = gu._probe_kimi_limits(root)
                mock_open.assert_not_called()

            self.assertEqual(c_exp["status"], "error")
            self.assertEqual(k_exp["status"], "error")
            self.assertEqual(claude_cred.read_text("utf-8"), expired_claude_raw)
            self.assertEqual(kimi_cred.read_text("utf-8"), expired_kimi_raw)

            valid_claude_token = "claude-live-token-xyz"
            claude_cred.write_text(
                json.dumps({
                    "claudeAiOauth": {
                        "accessToken": valid_claude_token,
                        "subscriptionType": "pro",
                        "expiresAt": int((now_sec + 3600) * 1000),
                    }
                }),
                "utf-8",
            )
            valid_kimi_token = "kimi-live-token-xyz"
            kimi_cred.write_text(
                json.dumps({"access_token": valid_kimi_token, "expires_at": now_sec + 3600}),
                "utf-8",
            )

            seen_headers: list[dict[str, str]] = []

            def fake_ok_headers(req, timeout=None):
                seen_headers.append({k.lower(): v for k, v in req.header_items()})
                if "anthropic.com" in req.full_url:
                    return _JsonResp({
                        "five_hour": {"utilization": 25.0, "resets_at": "2026-11-25T15:00:00Z"},
                        "limits": [
                            {"type": "weekly_scoped", "model_family": "sonnet", "utilization": 42.0, "resets_at": "2026-12-01T00:00:00Z"},
                        ],
                    })
                return _JsonResp({"usage": {"limit": "100", "used": "10"}})

            with (
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.dict(os.environ, {"KIMI_SHARE_DIR": str(root / ".kimi")}, clear=False),
                patch.object(gu, "_urlopen_auth", side_effect=fake_ok_headers),
            ):
                c_ok = gu._probe_claude_limits(root)
                k_ok = gu._probe_kimi_limits(root)
            self.assertEqual(c_ok["status"], "ok")
            self.assertEqual([w["id"] for w in c_ok["windows"]], ["five_hour", "seven_day_sonnet"])
            self.assertEqual(k_ok["status"], "ok")
            for hdrs in seen_headers:
                self.assertEqual(hdrs.get("user-agent"), "luma-usage/1")
                self.assertNotIn("x-msh-platform", hdrs)

            call_count = 0

            def fake_429(req, timeout=None):
                nonlocal call_count
                call_count += 1
                raise urllib.error.HTTPError(req.full_url, 429, "Too Many Requests", {"Retry-After": "180"}, None)

            with (
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.object(gu, "_urlopen_auth", side_effect=fake_429),
            ):
                first_429 = gu._probe_claude_limits(root)
                second_cached = gu._probe_claude_limits(root)
            self.assertEqual(first_429["status"], "error")
            self.assertEqual(second_cached["status"], "error")
            self.assertEqual(call_count, 1)
            cd_file = cache_dir / "quota-cooldowns-v1.json"
            self.assertTrue(cd_file.is_file())
            self.assertEqual(stat.S_IMODE(cd_file.stat().st_mode), 0o600)
            self.assertNotIn(valid_claude_token, cd_file.read_text("utf-8"))

            http_date = "Wed, 25 Nov 2026 12:10:00 GMT"
            base_epoch = dt.datetime(2026, 11, 25, 12, 0, 0, tzinfo=dt.timezone.utc).timestamp()
            self.assertEqual(gu._parse_retry_after_seconds({"Retry-After": http_date}, base_epoch), 600)
            self.assertEqual(gu._parse_retry_after_seconds({"Retry-After": "5"}, base_epoch), 30)
            self.assertEqual(gu._parse_retry_after_seconds({"Retry-After": "99999"}, base_epoch), 3600)
            self.assertEqual(gu._parse_retry_after_seconds({}, base_epoch), 300)

    def test_scan_cache_prunes_dead_entries_and_preserves_history_above_per_source_cap(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp) / "home"
            cache_dir = Path(tmp) / "cache"
            oc_dir = home / ".local" / "share" / "opencode"
            oc_dir.mkdir(parents=True)
            db_path = oc_dir / "opencode.db"
            con = sqlite3.connect(str(db_path))
            con.execute("CREATE TABLE message (id TEXT PRIMARY KEY, time_created INTEGER, data TEXT)")
            now_dt = dt.datetime.now(dt.timezone.utc)
            for idx in range(12):
                day_dt = now_dt - dt.timedelta(days=idx)
                ts_ms = int(day_dt.timestamp() * 1000)
                payload = json.dumps({
                    "role": "assistant",
                    "modelID": "claude-sonnet-4-6",
                    "time": {"completed": ts_ms},
                    "tokens": {"input": 100, "output": 50, "cache": {"read": 0, "write": 0}},
                })
                con.execute("INSERT INTO message VALUES (?, ?, ?)", (f"m{idx}", ts_ms, payload))
            con.commit()
            con.close()

            live_kimi = home / ".kimi-code" / "sessions" / "w1" / "session_s1" / "agents" / "main" / "wire.jsonl"
            live_kimi.parent.mkdir(parents=True)
            live_kimi.write_text("", encoding="utf-8")

            with patch.dict(os.environ, {"HOME": str(home), "XDG_CACHE_HOME": str(cache_dir), "XDG_DATA_HOME": str(home / ".local" / "share")}):
                with patch.object(gu, "MAX_RECORDS_PER_SOURCE", 5):
                    report = gu.collect_summary(period="all", tz_name="UTC", sync_pricing=False)
                self.assertEqual(report["totals"]["events"], 12)
                saved = gu._load_scan_cache()["files"]
                self.assertIn(str(db_path), saved)
                self.assertTrue(saved[str(db_path)].get("truncated"))
                self.assertEqual(len(saved[str(db_path)]["records"]), 5)

                saved[str(home / "deleted.jsonl")] = {"size": 1, "mtime_ns": 1, "records": []}
                saved[f"kimi::{home / 'deleted-wire.jsonl'}"] = {"fp_sig": "1:1", "records": []}
                saved[f"kimi::{live_kimi}"] = {"fp_sig": "1:1", "records": []}
                gu._save_scan_cache({"version": gu.CACHE_VERSION, "files": saved})
                with patch.object(gu, "MAX_RECORDS_PER_SOURCE", 5):
                    gu.collect_summary(period="week", tz_name="UTC", refresh=False, sync_pricing=False)
                pruned = gu._load_scan_cache()["files"]
                self.assertNotIn(str(home / "deleted.jsonl"), pruned)
                self.assertNotIn(f"kimi::{home / 'deleted-wire.jsonl'}", pruned)
                self.assertIn(f"kimi::{live_kimi}", pruned)
                self.assertIn(str(db_path), pruned)

    def test_codex_fork_child_skips_replayed_parent_history(self):
        def tok(ts, total_in, total_out, last_in, last_out):
            return {"type": "event_msg", "timestamp": ts, "payload": {"type": "token_count", "info": {
                "total_token_usage": {"input_tokens": total_in, "output_tokens": total_out},
                "last_token_usage": {"input_tokens": last_in, "output_tokens": last_out},
            }}}

        child_id = "01a20000-1000-7000-8000-000000000002"
        lines = [
            {"type": "session_meta", "timestamp": "2026-11-20T10:00:00Z", "payload": {
                "id": child_id, "source": {"subagent": {"thread_spawn": {"parent_thread_id": "01a1ffff-0000-7000-8000-000000000001"}}}}},
            {"type": "turn_context", "timestamp": "2026-11-20T09:00:00Z", "payload": {"turn_id": "01a1ffff-1000-7000-8000-000000000003", "model": "gpt-5"}},
            tok("2026-11-20T09:00:05Z", 1000, 100, 1000, 100),
            tok("2026-11-20T09:01:05Z", 3000, 300, 2000, 200),
            {"type": "event_msg", "timestamp": "2026-11-20T10:00:01Z", "payload": {"type": "task_started", "turn_id": "01a20000-2000-7000-8000-000000000004"}},
            {"type": "turn_context", "timestamp": "2026-11-20T10:00:01Z", "payload": {"turn_id": "01a20000-2000-7000-8000-000000000004", "model": "gpt-5"}},
            tok("2026-11-20T10:00:02Z", 3000, 300, 2000, 200),
            tok("2026-11-20T10:00:09Z", 3500, 340, 500, 40),
        ]
        state = gu._codex_rollout_init_state()
        recs = [r for r in (gu._codex_rollout_parse_line(obj, state) for obj in lines) if r]
        self.assertEqual(len(recs), 1)
        self.assertEqual(recs[0]["uncachedInputTokens"], 500)
        self.assertEqual(recs[0]["outputTokens"], 40)

        plain = gu._codex_rollout_init_state()
        self.assertEqual(len([r for r in (gu._codex_rollout_parse_line(obj, plain) for obj in lines[1:]) if r]), 3)

    def test_codex_thread_settings_snapshot_sets_and_clears_fast_tier(self):
        state = gu._codex_rollout_init_state()

        def settings(extra):
            return {"type": "event_msg", "timestamp": "2026-11-20T10:00:00Z", "payload": {
                "type": "thread_settings_applied", "thread_settings": {"model": "gpt-6.1-sol", **extra}}}

        tok = {"type": "event_msg", "timestamp": "2026-11-20T10:00:05Z", "payload": {"type": "token_count", "info": {
            "last_token_usage": {"input_tokens": 10, "output_tokens": 5}}}}
        gu._codex_rollout_parse_line(settings({"service_tier": "priority"}), state)
        self.assertEqual(gu._codex_rollout_parse_line(tok, state)["speed"], "codex:fast")
        gu._codex_rollout_parse_line(settings({}), state)
        self.assertIsNone(gu._codex_rollout_parse_line(tok, state)["speed"])
        self.assertEqual(state["model"], gu._normalize_model_id("gpt-6.1-sol"))

    def test_incremental_scan_resumes_appends_and_rescans_rewrites(self):
        with tempfile.TemporaryDirectory() as td:
            log = Path(td) / "append.jsonl"
            log.write_text(
                json.dumps({"ts": 1, "usage": 10}) + "\n" + json.dumps({"ts": 2, "usage": 20}) + "\n",
                encoding="utf-8",
            )
            cache_files = {}
            seen = []

            def parser(obj, _state):
                seen.append(obj["ts"])
                return {"timestampMs": obj["ts"], "usage": obj["usage"]}

            first = gu._read_jsonl_incremental(str(log), cache_files, parser, cutoff_ms=0)
            self.assertEqual([r["timestampMs"] for r in first], [1, 2])
            entry = cache_files[str(log)]
            self.assertEqual(entry["offset"], log.stat().st_size)
            self.assertNotIn("guard_hash", entry)

            with log.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps({"ts": 3, "usage": 30}) + "\n" + '{"ts": 4, "usa')
            seen.clear()
            appended = gu._read_jsonl_incremental(str(log), cache_files, parser, cutoff_ms=0)
            self.assertEqual(seen, [3])
            self.assertEqual([r["timestampMs"] for r in appended], [1, 2, 3])

            ino = log.stat().st_ino
            with log.open("r+", encoding="utf-8") as handle:
                handle.write(json.dumps({"ts": 7, "usage": 70}) + "\n" + json.dumps({"ts": 8, "usage": 80}) + "\n")
                handle.write(json.dumps({"ts": 9, "usage": 90}) + "\n" * 40)
            self.assertEqual(log.stat().st_ino, ino)
            seen.clear()
            rewritten = gu._read_jsonl_incremental(str(log), cache_files, parser, cutoff_ms=0)
            self.assertEqual(seen, [7, 8, 9])
            self.assertEqual([r["timestampMs"] for r in rewritten], [7, 8, 9])

    def test_audit_regressions_archive_upgrade_fork_reset_same_size_rewrite_and_grok_empty(self):
        now_utc = dt.datetime.now(dt.timezone.utc).replace(minute=10, second=0, microsecond=0)
        today_key = now_utc.strftime("%Y-%m-%d")
        yesterday_key = (now_utc - dt.timedelta(days=1)).strftime("%Y-%m-%d")

        def tok(ts, total_in, total_out, last_in=None, last_out=None):
            info = {"total_token_usage": {"input_tokens": total_in, "output_tokens": total_out}}
            if last_in is not None and last_out is not None:
                info["last_token_usage"] = {"input_tokens": last_in, "output_tokens": last_out}
            return {"type": "event_msg", "timestamp": ts, "payload": {"type": "token_count", "info": info}}

        parent_id = "01a1ffff-0000-7000-8000-000000000001"
        child_id = "01a20000-1000-7000-8000-000000000002"
        parent_turn = "01a1ffff-1000-7000-8000-000000000003"
        child_turn = "01a20000-2000-7000-8000-000000000004"

        for with_last in (False, True):
            reset_lines = [
                {"type": "session_meta", "timestamp": now_utc.isoformat(), "payload": {"id": child_id, "forked_from_id": parent_id}},
                {"type": "turn_context", "timestamp": now_utc.isoformat(), "payload": {"turn_id": parent_turn, "model": "gpt-5"}},
                tok(now_utc.isoformat(), 3000, 300, 3000 if with_last else None, 300 if with_last else None),
                {"type": "event_msg", "timestamp": now_utc.isoformat(), "payload": {"type": "task_started", "turn_id": child_turn}},
                tok(now_utc.isoformat(), 10, 1, 10 if with_last else None, 1 if with_last else None),
                tok(now_utc.isoformat(), 20, 2, 10 if with_last else None, 1 if with_last else None),
            ]
            st = gu._codex_rollout_init_state()
            recs = [r for r in (gu._codex_rollout_parse_line(obj, st) for obj in reset_lines) if r]
            self.assertEqual([(r["uncachedInputTokens"], r["outputTokens"]) for r in recs], [(10, 1), (10, 1)])

        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            cache_dir = home / ".cache-audit"
            cache_dir.mkdir(parents=True)
            sess_dir = home / ".codex" / "sessions" / "2026" / "11"
            sess_dir.mkdir(parents=True)

            parent_lines = [
                {"type": "session_meta", "timestamp": now_utc.isoformat(), "payload": {"id": parent_id, "model": "gpt-5"}},
                {"type": "turn_context", "timestamp": now_utc.isoformat(), "payload": {"turn_id": parent_turn, "model": "gpt-5"}},
                tok(now_utc.isoformat(), 3000, 300, 3000, 300),
            ]
            child_lines = [
                {"type": "session_meta", "timestamp": now_utc.isoformat(), "payload": {"id": child_id, "forked_from_id": parent_id, "model": "gpt-5"}},
                {"type": "turn_context", "timestamp": now_utc.isoformat(), "payload": {"turn_id": parent_turn, "model": "gpt-5"}},
                tok(now_utc.isoformat(), 3000, 300, 3000, 300),
                {"type": "event_msg", "timestamp": now_utc.isoformat(), "payload": {"type": "task_started", "turn_id": child_turn}},
                tok(now_utc.isoformat(), 3500, 340, 500, 40),
            ]
            (sess_dir / "parent.jsonl").write_text("\n".join(json.dumps(x) for x in parent_lines) + "\n", "utf-8")
            (sess_dir / "child.jsonl").write_text("\n".join(json.dumps(x) for x in child_lines) + "\n", "utf-8")

            old_archive = {
                "version": gu.ARCHIVE_VERSION,
                "scannerRev": gu.SCANNER_REV - 1,
                "seededTimezones": ["UTC"],
                "byTz": {
                    "UTC": {
                        today_key: {
                            "Codex::gpt-5": {
                                "cli": "Codex",
                                "model": "gpt-5",
                                "events": 3,
                                "uncachedInputTokens": 6500,
                                "cachedInputTokens": 0,
                                "cacheCreationTokens": 0,
                                "outputTokens": 640,
                                "reasoningTokens": 0,
                                "totalTokens": 7140,
                                "costUsd": 0.05,
                                "providerReportedCostUsd": 0.0,
                                "modelPricedCostUsd": 0.05,
                                "unpricedEvents": 0,
                                "unpricedTokens": 0,
                                "cacheSavingsUsd": 0.0,
                                "categoryCostUsd": {"input": 0.03, "cacheRead": 0.0, "cacheWrite": 0.0, "output": 0.02},
                                "uiLabels": [],
                            }
                        },
                        yesterday_key: {
                            "Codex::gpt-5": {
                                "cli": "Codex",
                                "model": "gpt-5",
                                "events": 1,
                                "uncachedInputTokens": 3600,
                                "cachedInputTokens": 0,
                                "cacheCreationTokens": 0,
                                "outputTokens": 400,
                                "reasoningTokens": 0,
                                "totalTokens": 4000,
                                "costUsd": 0.02,
                                "providerReportedCostUsd": 0.0,
                                "modelPricedCostUsd": 0.02,
                                "unpricedEvents": 0,
                                "unpricedTokens": 0,
                                "cacheSavingsUsd": 0.0,
                                "categoryCostUsd": {"input": 0.01, "cacheRead": 0.0, "cacheWrite": 0.0, "output": 0.01},
                                "uiLabels": [],
                            }
                        },
                    }
                },
            }
            (cache_dir / "usage-daily-archive-v1.json").write_text(json.dumps(old_archive), "utf-8")

            with (
                patch.object(gu.Path, "home", return_value=home),
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.dict(os.environ, {"CODEX_HOME": str(home / ".codex")}, clear=False),
            ):
                today_summary = gu.collect_summary(period="today", tz_name="UTC", refresh=False, sync_pricing=False)
                self.assertEqual(today_summary["totals"]["totalTokens"], 3840)
                self.assertEqual(sum(b["totalTokens"] for b in today_summary["buckets"]), 3840)
                all_summary = gu.collect_summary(period="all", tz_name="UTC", refresh=False, sync_pricing=False)
                self.assertEqual(all_summary["totals"]["totalTokens"], 7840)

            pad = "x" * (gu.HEAD_FINGERPRINT_BYTES + 256)
            same_size_log = home / "same-size.jsonl"
            line1 = json.dumps({"ts": 1, "usage": 1, "pad": pad}) + "\n"
            line2_old = json.dumps({"ts": 2, "usage": 2}) + "\n"
            line2_new = json.dumps({"ts": 2, "usage": 7}) + "\n"
            self.assertEqual(len(line2_old), len(line2_new))
            same_size_log.write_text(line1 + line2_old, "utf-8")
            cf = {}
            seen_u = []

            def parse_u(obj, _state):
                seen_u.append(obj["usage"])
                return {"timestampMs": obj["ts"], "usage": obj["usage"]}

            first_u = gu._read_jsonl_incremental(str(same_size_log), cf, parse_u, cutoff_ms=0)
            self.assertEqual([r["usage"] for r in first_u], [1, 2])
            orig_st = same_size_log.stat()
            with same_size_log.open("r+b") as fh:
                fh.seek(len(line1.encode("utf-8")))
                fh.write(line2_new.encode("utf-8"))
            os.utime(same_size_log, ns=(orig_st.st_atime_ns, orig_st.st_mtime_ns + 1_000_000_000))
            self.assertEqual(same_size_log.stat().st_ino, orig_st.st_ino)
            self.assertEqual(same_size_log.stat().st_size, orig_st.st_size)
            seen_u.clear()
            rescanned_u = gu._read_jsonl_incremental(str(same_size_log), cf, parse_u, cutoff_ms=0)
            self.assertEqual([r["usage"] for r in rescanned_u], [1, 7])
            self.assertEqual(seen_u, [1, 7])

            with same_size_log.open("a", encoding="utf-8") as fh:
                fh.write(json.dumps({"ts": 3, "usage": 9}) + "\n")
            seen_u.clear()
            appended_u = gu._read_jsonl_incremental(str(same_size_log), cf, parse_u, cutoff_ms=0)
            self.assertEqual(seen_u, [9])
            self.assertEqual([r["usage"] for r in appended_u], [1, 7, 9])

            grok_home = home / ".grok"
            grok_home.mkdir(parents=True)
            (grok_home / "auth.json").write_text(
                json.dumps({"https://auth.x.ai::cid": {"auth_mode": "oidc", "key": "tok"}}),
                "utf-8",
            )
            fake_grok = home / "fake-grok-audit"
            fake_grok.write_text(
                f"#!{sys.executable}\n"
                "import json, os, sys\n"
                "init = json.loads(sys.stdin.readline())\n"
                "print(json.dumps({'jsonrpc': '2.0', 'id': init['id'], 'result': {'protocolVersion': 1}}), flush=True)\n"
                "req = json.loads(sys.stdin.readline())\n"
                "payload = json.loads(os.environ['FAKE_GROK_BILLING_JSON'])\n"
                "print(json.dumps({'jsonrpc': '2.0', 'id': req['id'], 'result': payload}), flush=True)\n",
                "utf-8",
            )
            fake_grok.chmod(0o755)
            with patch.dict(os.environ, {"GROK_HOME": str(grok_home), "LUMA_GROK_BIN": str(fake_grok), "FAKE_GROK_BILLING_JSON": "{}"}, clear=False):
                empty_res = gu._probe_grok_limits(home, timeout_sec=5.0)
            self.assertEqual(empty_res["status"], "error")
            self.assertEqual(empty_res["source"], "unavailable")
            self.assertIn(gu._tr("未返回额度", "no quota data"), empty_res["message"])

            for zero_payload in ({"effectiveRemaining": 0}, {"config": {"prepaidBalance": {"val": 0}}}):
                with patch.dict(
                    os.environ,
                    {"GROK_HOME": str(grok_home), "LUMA_GROK_BIN": str(fake_grok), "FAKE_GROK_BILLING_JSON": json.dumps(zero_payload)},
                    clear=False,
                ):
                    zero_res = gu._probe_grok_limits(home, timeout_sec=5.0)
                self.assertEqual(zero_res["status"], "ok")
                self.assertEqual(zero_res["source"], "live")
                self.assertIsNotNone(zero_res["credits"])
                self.assertEqual(zero_res["credits"]["remaining"], 0.0)

    def test_incremental_scan_preserves_truncated_coverage_bound(self):
        with tempfile.TemporaryDirectory() as td:
            log = Path(td) / "session.jsonl"
            log.write_text(
                "\n".join([
                    json.dumps({"ts": 1, "usage": 10}),
                    json.dumps({"ts": 2, "usage": 20}),
                    json.dumps({"ts": 3, "usage": 30}),
                ]) + "\n",
                encoding="utf-8",
            )
            cache_files = {}

            def parser(obj, _state):
                if "usage" not in obj:
                    return None
                return {"timestampMs": obj["ts"], "usage": obj["usage"]}

            with patch.object(gu, "MAX_RECORDS_PER_SOURCE", 2):
                first = gu._read_jsonl_incremental(str(log), cache_files, parser, cutoff_ms=0)
                self.assertEqual([r["timestampMs"] for r in first], [1, 2, 3])
                self.assertTrue(cache_files[str(log)].get("truncated"))
                self.assertEqual(cache_files[str(log)].get("min_ts_ms"), 2)

                with log.open("a", encoding="utf-8") as handle:
                    handle.write(json.dumps({"ts": 4, "note": "non-usage"}) + "\n")

                inc = gu._read_jsonl_incremental(str(log), cache_files, parser, cutoff_ms=3)
                self.assertEqual([r["timestampMs"] for r in inc], [2, 3])
                self.assertTrue(cache_files[str(log)].get("truncated"))
                self.assertEqual(cache_files[str(log)].get("min_ts_ms"), 2)

                all_time = gu._read_jsonl_incremental(str(log), cache_files, parser, cutoff_ms=0)
                self.assertEqual([r["timestampMs"] for r in all_time], [1, 2, 3])

                with log.open("a", encoding="utf-8") as handle:
                    handle.write(json.dumps({"ts": 5, "usage": 50}) + "\n")
                inc2 = gu._read_jsonl_incremental(str(log), cache_files, parser, cutoff_ms=3)
                self.assertEqual([r["timestampMs"] for r in inc2], [2, 3, 5])
                self.assertTrue(cache_files[str(log)].get("truncated"))
                self.assertEqual(cache_files[str(log)].get("min_ts_ms"), 3)
                all_time2 = gu._read_jsonl_incremental(str(log), cache_files, parser, cutoff_ms=0)
                self.assertEqual([r["timestampMs"] for r in all_time2], [1, 2, 3, 5])

    def test_upgrade_invalidates_legacy_v6_scan_cache_and_recovers_all_records(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp) / "home"
            cache_dir = Path(tmp) / "cache"
            oc_dir = home / ".local" / "share" / "opencode"
            oc_dir.mkdir(parents=True)
            db_path = oc_dir / "opencode.db"
            con = sqlite3.connect(str(db_path))
            con.execute("CREATE TABLE message (id TEXT PRIMARY KEY, time_created INTEGER, data TEXT)")
            now_dt = dt.datetime.now(dt.timezone.utc)
            for idx, inp in enumerate((100, 200, 300)):
                ts_ms = int((now_dt - dt.timedelta(days=2 - idx)).timestamp() * 1000)
                payload = json.dumps({
                    "role": "assistant",
                    "modelID": "claude-sonnet-4-6",
                    "time": {"completed": ts_ms},
                    "tokens": {"input": inp, "output": 50, "cache": {"read": 0, "write": 0}},
                })
                con.execute("INSERT INTO message VALUES (?, ?, ?)", (f"m{idx}", ts_ms, payload))
            con.commit()
            con.close()

            log = Path(tmp) / "legacy.jsonl"
            log.write_text(
                "\n".join([
                    json.dumps({"ts": 1, "usage": 10}),
                    json.dumps({"ts": 2, "usage": 20}),
                    json.dumps({"ts": 3, "usage": 30}),
                ]) + "\n",
                encoding="utf-8",
            )
            st = os.stat(log)

            def parser(obj, _state):
                return {"timestampMs": obj["ts"], "usage": obj["usage"]}

            with patch.dict(os.environ, {"HOME": str(home), "XDG_CACHE_HOME": str(cache_dir), "XDG_DATA_HOME": str(home / ".local" / "share")}):
                with patch.object(gu, "MAX_RECORDS_PER_SOURCE", 2):
                    seed_report = gu.collect_summary(period="all", tz_name="UTC", sync_pricing=False)
                self.assertEqual(seed_report["totals"]["events"], 3)
                seeded = gu._load_scan_cache()
                legacy_db_entry = dict(seeded["files"][str(db_path)])
                legacy_db_entry.pop("truncated", None)
                legacy_db_entry.pop("min_ts_ms", None)
                self.assertEqual(len(legacy_db_entry["records"]), 2)
                legacy_cache = {
                    "version": 6,
                    "files": {
                        str(db_path): legacy_db_entry,
                        str(log): {
                            "size": st.st_size,
                            "mtime_ns": st.st_mtime_ns,
                            "records": [{"timestampMs": 2, "usage": 20}, {"timestampMs": 3, "usage": 30}],
                        },
                    },
                }
                gu._save_scan_cache(legacy_cache)
                archive_file = gu._cache_dir() / f"usage-daily-archive-v{gu.ARCHIVE_VERSION}.json"
                if archive_file.exists():
                    archive_file.unlink()

                loaded = gu._load_scan_cache()
                self.assertEqual(loaded["version"], gu.CACHE_VERSION)
                self.assertGreater(gu.CACHE_VERSION, 6)
                self.assertEqual(loaded["files"], {})

                with patch.object(gu, "MAX_RECORDS_PER_SOURCE", 2):
                    all_rows = gu._read_jsonl_incremental(str(log), loaded["files"], parser, cutoff_ms=0)
                self.assertEqual([r["timestampMs"] for r in all_rows], [1, 2, 3])
                self.assertTrue(loaded["files"][str(log)].get("truncated"))

                gu._save_scan_cache(legacy_cache)
                with patch.object(gu, "MAX_RECORDS_PER_SOURCE", 2):
                    upgraded = gu.collect_summary(period="all", tz_name="UTC", refresh=False, sync_pricing=False)
                self.assertEqual(upgraded["totals"]["events"], 3)
                self.assertEqual(upgraded["totals"]["totalTokens"], 750)
                rebuilt = gu._load_scan_cache()
                self.assertEqual(rebuilt["version"], gu.CACHE_VERSION)
                self.assertTrue(rebuilt["files"][str(db_path)].get("truncated"))

    def test_truncated_cache_boundary_tie_falls_back_to_source(self):
        with tempfile.TemporaryDirectory() as td:
            log = Path(td) / "tie.jsonl"
            log.write_text(
                "\n".join([
                    json.dumps({"id": "A", "ts": 10, "usage": 100}),
                    json.dumps({"id": "B", "ts": 10, "usage": 200}),
                    json.dumps({"id": "C", "ts": 11, "usage": 300}),
                ]) + "\n",
                encoding="utf-8",
            )
            cache_files = {}

            def parser(obj, _state):
                return {"id": obj["id"], "timestampMs": obj["ts"], "usage": obj["usage"]}

            with patch.object(gu, "MAX_RECORDS_PER_SOURCE", 2):
                initial = gu._read_jsonl_incremental(str(log), cache_files, parser, cutoff_ms=0)
                self.assertEqual([(r["id"], r["timestampMs"], r["usage"]) for r in initial], [("A", 10, 100), ("B", 10, 200), ("C", 11, 300)])
                entry = cache_files[str(log)]
                self.assertTrue(entry.get("truncated"))
                self.assertEqual(entry.get("min_ts_ms"), 10)
                self.assertEqual([r["id"] for r in entry["records"]], ["B", "C"])
                self.assertFalse(gu._cache_entry_covers(entry, 10))
                self.assertTrue(gu._cache_entry_covers(entry, 11))

                at_boundary = gu._read_jsonl_incremental(str(log), cache_files, parser, cutoff_ms=10)
                self.assertEqual([(r["id"], r["timestampMs"], r["usage"]) for r in at_boundary], [("A", 10, 100), ("B", 10, 200), ("C", 11, 300)])

                above_boundary = gu._read_jsonl_incremental(str(log), cache_files, parser, cutoff_ms=11)
                self.assertEqual([(r["id"], r["timestampMs"], r["usage"]) for r in above_boundary], [("B", 10, 200), ("C", 11, 300)])

    def test_pricing_lowercase_claude_offline_backoff_and_reprice_tiers_mixed(self):
        self.assertIsNotNone(gu._lookup_model_rate("claude-4.5-sonnet"))
        self.assertIsNotNone(gu._lookup_model_rate("anthropic/claude-4.5-sonnet"))

        with patch.object(gu, "_fetch_remote_json", return_value=None) as m_fetch:
            gu._sync_pricing_catalog(force=True)
            self.assertGreater(m_fetch.call_count, 0)
            self.assertTrue(gu._pricing_backoff_path().is_file())
            after_fail = m_fetch.call_count
            gu._sync_pricing_catalog(force=False)
            self.assertEqual(m_fetch.call_count, after_fail)
            md_ok = {"openai": {"models": {"gpt-5": {"cost": {"input": 1.25, "output": 10.0, "cache_read": 0.125}}}}}
            m_fetch.side_effect = lambda url, timeout=6.5: md_ok if url == gu.MODELS_DEV_API_URL else None
            cat = gu._sync_pricing_catalog(force=True)
            self.assertEqual(cat["mode"], "online")
            self.assertFalse(gu._pricing_backoff_path().exists())

        now_iso = dt.datetime.now(dt.timezone.utc).isoformat()
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            cache_dir = home / ".cache"
            cache_dir.mkdir()
            qdir = home / ".qoder" / "projects" / "-w"
            qdir.mkdir(parents=True)
            ev_rep = {
                "type": "assistant",
                "timestamp": now_iso,
                "costUSD": 0.50,
                "message": {"id": "q-rep", "model": "custom-unpriced-mix", "usage": {"input_tokens": 1000, "output_tokens": 100}},
            }
            ev_low = {
                "type": "assistant",
                "timestamp": now_iso,
                "message": {"id": "q-low", "model": "custom-unpriced-mix", "usage": {"input_tokens": 10_000, "output_tokens": 0}},
            }
            ev_high = {
                "type": "assistant",
                "timestamp": now_iso,
                "message": {"id": "q-high", "model": "custom-unpriced-mix", "usage": {"input_tokens": 250_000, "output_tokens": 0}},
            }
            (qdir / "s.jsonl").write_text("\n".join(json.dumps(x) for x in (ev_rep, ev_low, ev_high)) + "\n", "utf-8")

            with (
                patch.object(gu.Path, "home", return_value=home),
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.dict(os.environ, {"QODER_HOME": str(home / ".qoder")}, clear=False),
            ):
                s1 = gu.collect_summary(period="today", tz_name="UTC", refresh=False, sync_pricing=False)
                self.assertAlmostEqual(s1["totals"]["costUsd"], 0.50, places=6)

                cat_v2 = gu._build_runtime_catalog(
                    online_rates={"custom-unpriced-mix": (1.0, 2.0, 0.1, 1.0, (200_000, 10.0, 20.0, 1.0, 10.0))},
                    mode="online",
                    updated_at=now_iso,
                    active_sources=["models.dev"],
                )
                arch = gu._load_daily_archive()
                today_key = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d")
                entry = arch["byTz"]["UTC"][today_key]["Qoder CLI::custom-unpriced-mix"]
                gu._reprice_archived_entry(entry, cat_v2)
                self.assertAlmostEqual(entry["providerReportedCostUsd"], 0.50, places=6)
                self.assertAlmostEqual(entry["modelPricedCostUsd"], 0.01 + 2.50, places=6)
                self.assertAlmostEqual(entry["costUsd"], 3.01, places=6)

    def test_codex_streaming_diff_in_zero_grok_partial_cost_and_kimi_safe_timestamps(self):
        now_ms = int(time.time() * 1000)
        now_sec = now_ms / 1000.0
        now_iso = dt.datetime.now(dt.timezone.utc).isoformat()
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            codex_dir = home / ".codex" / "sessions" / "2026" / "11"
            codex_dir.mkdir(parents=True)
            rollout = codex_dir / "rollout-stream.jsonl"
            lines = [
                json.dumps({
                    "type": "event_msg",
                    "timestamp": now_iso,
                    "payload": {
                        "type": "token_count",
                        "info": {
                            "model": "gpt-5-codex",
                            "total_token_usage": {"input_tokens": 100, "output_tokens": 20},
                            "last_token_usage": {"input_tokens": 100, "output_tokens": 20},
                        },
                    },
                }),
                json.dumps({
                    "type": "event_msg",
                    "timestamp": now_iso,
                    "payload": {
                        "type": "token_count",
                        "info": {
                            "model": "gpt-5-codex",
                            "total_token_usage": {"input_tokens": 100, "output_tokens": 35},
                            "last_token_usage": {"input_tokens": 100, "output_tokens": 35},
                        },
                    },
                }),
            ]
            rollout.write_text("\n".join(lines) + "\n", "utf-8")
            with patch.dict(os.environ, {"CODEX_HOME": str(home / ".codex")}, clear=False):
                crecs = gu._scan_codex(home, {}, 0)
            self.assertEqual(sum(r["uncachedInputTokens"] for r in crecs), 100)
            self.assertEqual(sum(r["outputTokens"] for r in crecs), 35)

            grok_sess = home / ".grok" / "sessions" / "%2Fw" / "gs1"
            grok_sess.mkdir(parents=True)
            (grok_sess / "updates.jsonl").write_text(
                "\n".join([
                    json.dumps({
                        "sessionId": "gs1",
                        "timestamp": now_ms,
                        "update": {
                            "sessionUpdate": "turn_completed",
                            "promptId": "t1",
                            "usage": {
                                "model": "grok-4",
                                "inputTokens": 1000,
                                "cachedReadTokens": 400,
                                "cacheCreationTokens": 300,
                                "outputTokens": 50,
                            },
                        },
                    }),
                    json.dumps({
                        "sessionId": "gs1",
                        "timestamp": now_ms,
                        "update": {
                            "sessionUpdate": "turn_completed",
                            "promptId": "t2",
                            "_meta": {
                                "model": "grok-4",
                                "costIsPartial": True,
                                "costUsdTicks": 9999999999,
                                "inputTokens": 500,
                                "outputTokens": 20,
                            },
                        },
                    }),
                    json.dumps({
                        "sessionId": "gs1",
                        "timestamp": now_ms,
                        "update": {
                            "sessionUpdate": "turn_completed",
                            "promptId": "t3",
                            "_meta": {
                                "model": "grok-4",
                                "usageIsIncomplete": True,
                                "inputTokens": 999,
                                "outputTokens": 99,
                            },
                        },
                    }),
                ]) + "\n",
                "utf-8",
            )
            with patch.dict(os.environ, {"GROK_HOME": str(home / ".grok")}, clear=False):
                grecs = gu._scan_grok(home, {}, 0)
            by_k = {r["dedupKey"]: r for r in grecs}
            t1_rec = next(v for k, v in by_k.items() if ":prompt:t1:" in k)
            self.assertEqual(t1_rec["uncachedInputTokens"], 300)
            t2_rec = next(v for k, v in by_k.items() if ":prompt:t2:" in k)
            self.assertIsNone(t2_rec["reportedCostUsd"])
            self.assertEqual(t2_rec["uncachedInputTokens"], 500)
            self.assertFalse(any(":prompt:t3:" in k for k in by_k))

            kimi_code_sess = home / ".kimi-code" / "sessions" / "ks1" / "agents" / "main"
            kimi_code_sess.mkdir(parents=True)
            (kimi_code_sess / "wire.jsonl").write_text(
                "\n".join([
                    json.dumps({"type": "llm.request", "time": 1e309, "model": "kimi-for-coding"}).replace("Infinity", "1e309"),
                    json.dumps({"type": "usage.record", "usageScope": "turn", "time": now_sec, "usage": {"inputOther": 100, "output": 20}}),
                ]) + "\n",
                "utf-8",
            )
            with patch.dict(
                os.environ,
                {"KIMI_SHARE_DIR": str(home / ".kimi"), "KIMI_CODE_HOME": str(home / ".kimi-code")},
                clear=False,
            ):
                krecs = gu._scan_kimi(home, {}, 0)
            self.assertEqual(len(krecs), 1)
            self.assertEqual(krecs[0]["timestampMs"], int(now_sec * 1000))

            cb_dir = home / ".codebuddy" / "projects" / "-w"
            cb_dir.mkdir(parents=True)
            (cb_dir / "cb1.jsonl").write_text(
                "\n".join([
                    json.dumps({"type": "message", "role": "assistant", "timestamp": now_iso, "providerData": {"model": "m1", "rawUsage": {"input_tokens": 10, "output_tokens": 5}}}),
                    json.dumps({"type": "message", "role": "assistant", "timestamp": now_iso, "providerData": {"model": "m1", "rawUsage": {"input_tokens": 20, "output_tokens": 5}}}),
                ]) + "\n",
                "utf-8",
            )
            cbrecs = gu._scan_codebuddy(home, {}, 0)
            self.assertEqual(len(cbrecs), 2)

            qd_dir = home / ".qoder" / "projects" / "-w"
            qd_dir.mkdir(parents=True)
            (qd_dir / "q1.jsonl").write_text(
                "\n".join([
                    json.dumps({"type": "assistant", "timestamp": now_ms, "message": {"model": "m1", "usage": {"input_tokens": 11, "output_tokens": 2}}}),
                    json.dumps({"type": "assistant", "timestamp": now_ms, "message": {"model": "m1", "usage": {"input_tokens": 12, "output_tokens": 3}}}),
                ]) + "\n",
                "utf-8",
            )
            with patch.dict(os.environ, {"QODER_HOME": str(home / ".qoder")}, clear=False):
                qrecs = gu._scan_qoder(home, {}, 0)
            self.assertEqual(len(qrecs), 2)

    def test_sqlite_locked_wal_uri_escape_and_zero_foreign_shm(self):
        now_ms = int(time.time() * 1000)
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            oc_dir = home / ".local" / "share" / "opencode"
            oc_dir.mkdir(parents=True)
            wal_db = oc_dir / "opencode#wal?1.db"
            c = sqlite3.connect(str(wal_db))
            c.execute("PRAGMA journal_mode=WAL")
            c.execute("CREATE TABLE session_message (id TEXT, type TEXT, time_created INT, data TEXT)")
            c.execute(
                "INSERT INTO session_message VALUES ('m1', 'assistant', ?, ?)",
                (now_ms, json.dumps({"model": {"id": "gpt-5"}, "tokens": {"input": 100, "output": 50}})),
            )
            c.commit()
            c.close()
            self.assertFalse(Path(str(wal_db) + "-shm").exists())

            recs = gu._scan_opencode(home, {}, 0)
            self.assertEqual(len(recs), 1)
            self.assertFalse(Path(str(wal_db) + "-shm").exists())

            locked_db = oc_dir / "opencode-locked.db"
            c_lock = sqlite3.connect(str(locked_db))
            c_lock.execute("CREATE TABLE session_message (id TEXT, type TEXT, time_created INT, data TEXT)")
            c_lock.commit()
            c_lock.execute("BEGIN EXCLUSIVE")
            cache_files: dict = {}
            try:
                with patch.object(gu.sqlite3, "connect", side_effect=sqlite3.OperationalError("database is locked")):
                    recs2 = gu._scan_opencode(home, cache_files, 0)
                self.assertEqual(recs2, [])
                self.assertNotIn(str(locked_db), cache_files)
            finally:
                c_lock.rollback()
                c_lock.close()

            agy_dir = home / ".gemini" / "antigravity-cli" / "conversations"
            agy_dir.mkdir(parents=True)
            agy_db = agy_dir / "conv#bad.db"
            agy_db.write_bytes(b"corrupt")
            agy_cache: dict = {}
            self.assertEqual(gu._scan_antigravity(home, agy_cache, 0), [])
            self.assertNotIn(str(agy_db), agy_cache)

    def test_collect_summary_timezone_dst_and_limits_daemon_exit(self):
        class _FrozenDt(dt.datetime):
            fixed_utc = dt.datetime(2026, 11, 1, 12, 0, tzinfo=dt.timezone.utc)
            @classmethod
            def now(cls, tz=None):
                return cls.fixed_utc.astimezone(tz) if tz is not None else cls.fixed_utc.replace(tzinfo=None)

        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            cache_dir = home / ".cache"
            cache_dir.mkdir()
            with (
                patch.object(gu.Path, "home", return_value=home),
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.object(gu.dt, "datetime", _FrozenDt),
            ):
                _FrozenDt.fixed_utc = dt.datetime(2026, 11, 1, 12, 0, tzinfo=dt.timezone.utc)
                fb = gu.collect_summary(period="today", tz_name="Invalid/Zone", sync_pricing=False)
                self.assertEqual(fb["period"], "today")
                arch = gu._load_daily_archive()
                self.assertIn("LOCAL", arch["byTz"])
                self.assertNotIn("Invalid/Zone", arch["byTz"])

                fall_back = gu.collect_summary(period="today", tz_name=" America/New_York ", sync_pricing=False)
                self.assertEqual(len(fall_back["buckets"]), 25)
                arch2 = gu._load_daily_archive()
                self.assertIn("America/New_York", arch2["byTz"])

                _FrozenDt.fixed_utc = dt.datetime(2026, 3, 8, 12, 0, tzinfo=dt.timezone.utc)
                spring_fwd = gu.collect_summary(period="today", tz_name="America/New_York", sync_pricing=False)
                self.assertEqual(len(spring_fwd["buckets"]), 23)
                self.assertNotIn("2026-03-08T02:00", [b["date"] for b in spring_fwd["buckets"]])

            codex_home = home / ".codex"
            codex_home.mkdir()
            (codex_home / "auth.json").write_text(json.dumps({"auth_mode": "chatgpt", "tokens": {"access_token": "tok"}}), "utf-8")
            script = "\n".join([
                "import os, pathlib, sys, time",
                "from unittest.mock import patch",
                f"sys.path.insert(0, {str(REPO / 'apps/gui/app/src/main/assets/cli_bridge')!r})",
                "from luma_cli import global_usage as gu",
                "with (",
                "    patch.object(gu.Path, 'home', return_value=pathlib.Path(sys.argv[1])),",
                "    patch.object(gu, '_cache_dir', return_value=pathlib.Path(sys.argv[2])),",
                "    patch.object(gu, '_probe_codex_limits', side_effect=lambda _h: time.sleep(10)),",
                "):",
                "    res = gu.collect_limits(fresh=True, probe_timeout=0.05)",
                "assert res['providers'][0]['status'] == 'error', res",
            ])
            proc = subprocess.run(
                [sys.executable, "-c", script, str(home), str(cache_dir)],
                env={
                    "PATH": os.environ.get("PATH", ""),
                    "HOME": str(home),
                    "CODEX_HOME": str(codex_home),
                    "LUMA_LANG": os.environ.get("LUMA_LANG", "zh"),
                },
                timeout=2.0,
                capture_output=True,
                text=True,
            )
            self.assertEqual(proc.returncode, 0, proc.stderr)

    def test_codex_session_pricing_cliff_and_speed_reset(self):
        self.assertEqual(
            gu.MODEL_RATES_PER_M["gpt-6.1-sol"],
            (2.0, 10.0, 0.1, 2.5, (272_000, 4.0, 15.0, 0.2, 5.0)),
        )
        now = dt.datetime.now(dt.timezone.utc)
        now_iso = now.isoformat().replace("+00:00", "Z")
        with tempfile.TemporaryDirectory() as td:
            home = Path(td)
            sess_dir = home / ".codex" / "sessions" / "2026" / "11" / "16"
            sess_dir.mkdir(parents=True)
            rollout = sess_dir / "rollout-pricing-test.jsonl"
            lines = [
                json.dumps({
                    "type": "session_meta",
                    "timestamp": now_iso,
                    "payload": {"model": "gpt-6.1-sol"},
                }),
                json.dumps({
                    "type": "event_msg",
                    "timestamp": now_iso,
                    "payload": {
                        "type": "token_count",
                        "info": {
                            "total_token_usage": {"input_tokens": 150_000, "output_tokens": 0},
                            "last_token_usage": {"input_tokens": 150_000, "output_tokens": 0},
                        },
                    },
                }),
                json.dumps({
                    "type": "event_msg",
                    "timestamp": now_iso,
                    "payload": {
                        "type": "token_count",
                        "info": {
                            "total_token_usage": {"input_tokens": 300_000, "output_tokens": 0},
                            "last_token_usage": {"input_tokens": 150_000, "output_tokens": 0},
                        },
                    },
                }),
                json.dumps({
                    "type": "event_msg",
                    "timestamp": now_iso,
                    "payload": {
                        "type": "token_count",
                        "info": {
                            "total_token_usage": {"input_tokens": 600_000, "output_tokens": 0},
                            "last_token_usage": {"input_tokens": 300_000, "output_tokens": 0},
                        },
                    },
                }),
                json.dumps({
                    "type": "turn_context",
                    "timestamp": now_iso,
                    "payload": {"model": "gpt-6-luna", "service_tier": "priority"},
                }),
                json.dumps({
                    "type": "event_msg",
                    "timestamp": now_iso,
                    "payload": {
                        "type": "token_count",
                        "info": {
                            "total_token_usage": {"input_tokens": 700_000, "output_tokens": 0},
                            "last_token_usage": {"input_tokens": 100_000, "output_tokens": 0},
                        },
                    },
                }),
                json.dumps({
                    "type": "turn_context",
                    "timestamp": now_iso,
                    "payload": {"model": "gpt-6-luna", "service_tier": None},
                }),
                json.dumps({
                    "type": "event_msg",
                    "timestamp": now_iso,
                    "payload": {
                        "type": "token_count",
                        "info": {
                            "total_token_usage": {"input_tokens": 800_000, "output_tokens": 0},
                            "last_token_usage": {"input_tokens": 100_000, "output_tokens": 0},
                        },
                    },
                }),
            ]
            rollout.write_text("\n".join(lines) + "\n", "utf-8")
            cat = gu._build_runtime_catalog({}, "builtin", "", [])
            summary = gu.summarize_codex_rollout(rollout, catalog=cat)
            self.assertEqual(summary["eventCount"], 5)
            self.assertAlmostEqual(summary["costUsd"], 1.83, places=9)
            self.assertIsNone(summary["speed"])
            with patch.dict(os.environ, {"CODEX_HOME": str(home / ".codex")}, clear=False):
                recs = gu._scan_codex(home, {}, 0)
            scan_cost = sum(gu._price_record(r, catalog=cat, event_count=1)["costUsd"] for r in recs)
            self.assertAlmostEqual(summary["costUsd"], scan_cost, places=9)
            session_payload = gu.collect_codex_session_pricing(str(rollout))
            self.assertIn("catalog", session_payload)
            self.assertTrue(session_payload["rollout"]["hasUsage"])

    def test_opencode_go_limits_probe_keys_fallback_and_windows(self):
        resets_at = "2026-11-24T12:00:00.000Z"

        class _FakeResp:
            def __init__(self, payload: dict):
                self.status = 200
                self._raw = json.dumps(payload).encode("utf-8")

            def read(self, n: int = -1) -> bytes:
                return self._raw if n < 0 else self._raw[:n]

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            oc_dir = home / ".local" / "share" / "opencode"
            oc_dir.mkdir(parents=True)
            (oc_dir / "auth.json").write_text(
                json.dumps({"opencode-go": {"type": "api", "key": "auth-go-key"}}),
                "utf-8",
            )
            db_path = oc_dir / "opencode.db"
            con = sqlite3.connect(str(db_path))
            con.execute(
                "CREATE TABLE credential (id TEXT PRIMARY KEY, integration_id TEXT, value TEXT, active INTEGER, time_created INTEGER, time_updated INTEGER)"
            )
            con.execute(
                "INSERT INTO credential VALUES (?, ?, ?, ?, ?, ?)",
                ("c1", "opencode", json.dumps({"type": "key", "key": "console-key", "metadata": {"server": "https://opencode.ai/console"}}), 1, 2, 2),
            )
            con.execute(
                "INSERT INTO credential VALUES (?, ?, ?, ?, ?, ?)",
                ("c2", "opencode-go", json.dumps({"type": "key", "key": "db-go-key"}), 1, 3, 3),
            )
            con.execute(
                "INSERT INTO credential VALUES (?, ?, ?, ?, ?, ?)",
                ("c3", "opencode", json.dumps({"type": "key", "key": "custom-host-key", "metadata": {"server": "https://selfhosted.example"}}), 1, 9, 9),
            )
            con.commit()
            con.close()

            clean_env = {k: v for k, v in os.environ.items() if k not in ("XDG_DATA_HOME", "OPENCODE_API_KEY", "OPENCODE_AUTH_CONTENT", "OPENCODE_DB")}
            with patch.dict(os.environ, clean_env, clear=True):
                keys = gu._read_opencode_go_tokens(home)
                self.assertEqual(keys, ["db-go-key", "auth-go-key"])

                seen_tokens: list[str] = []

                def fake_urlopen(req, timeout=None):
                    auth_header = req.get_header("Authorization") or ""
                    token = auth_header.replace("Bearer ", "")
                    seen_tokens.append(token)
                    if token == "db-go-key":
                        raise urllib.error.HTTPError(req.full_url, 401, "Unauthorized", {}, None)
                    return _FakeResp({
                        "usage": {
                            "rolling": {"percent": 12.5, "resetsAt": resets_at},
                            "weekly": {"percent": 40, "resetsAt": resets_at},
                            "monthly": {"percent": 125, "resetsAt": resets_at},
                        }
                    })

                with patch.object(gu, "_urlopen_auth", side_effect=fake_urlopen):
                    res = gu._probe_opencode_limits(home)
                self.assertEqual(seen_tokens, ["db-go-key", "auth-go-key"])
                self.assertEqual(res["cli"], "OpenCode")
                self.assertEqual(res["planType"], "Go")
                self.assertEqual(res["status"], "ok")
                self.assertEqual(res["source"], "live")
                self.assertEqual([w["id"] for w in res["windows"]], ["go_rolling", "go_weekly", "go_monthly"])
                self.assertEqual([w["label"] for w in res["windows"]], [gu._tr("短期", "Session"), gu._tr("每周", "Weekly"), gu._tr("每月", "Monthly")])
                self.assertEqual([w["usedPercent"] for w in res["windows"]], [12.5, 40.0, 100.0])
                self.assertEqual([w["remainingPercent"] for w in res["windows"]], [87.5, 60.0, 0.0])
                self.assertEqual([w["windowDurationMs"] for w in res["windows"]], [5 * 3_600_000, 7 * 86_400_000, None])

                def fake_403(req, timeout=None):
                    raise urllib.error.HTTPError(req.full_url, 403, "Forbidden", {}, None)

                with patch.object(gu, "_urlopen_auth", side_effect=fake_403):
                    non_go = gu._probe_opencode_limits(home)
                self.assertEqual(non_go["status"], "unconfigured")

                cache_dir = home / ".cache"
                cache_dir.mkdir()
                with (
                    patch.object(gu.Path, "home", return_value=home),
                    patch.object(gu, "_cache_dir", return_value=cache_dir),
                    patch.object(gu, "_has_codex_oauth", return_value=False),
                    patch.object(gu, "_has_claude_oauth", return_value=False),
                    patch.object(gu, "_has_antigravity_oauth", return_value=False),
                    patch.object(gu, "_has_grok_oauth", return_value=False),
                    patch.object(gu, "_has_deepseek_auth", return_value=False),
                    patch.object(gu, "_has_kimi_oauth", return_value=False),
                    patch.object(gu, "_urlopen_auth", side_effect=lambda req, timeout=None: _FakeResp({
                        "usage": {
                            "rolling": {"percent": 5, "resetsAt": resets_at},
                            "weekly": {"percent": 20, "resetsAt": resets_at},
                            "monthly": {"percent": 80, "resetsAt": resets_at},
                        }
                    })),
                ):
                    limits = gu.collect_limits(fresh=True)
                self.assertEqual([p["cli"] for p in limits["providers"]], ["OpenCode"])

            with patch.dict(
                os.environ,
                {**clean_env, "OPENCODE_AUTH_CONTENT": json.dumps({"opencode-go": {"type": "api", "key": "inline-key"}})},
                clear=True,
            ):
                self.assertEqual(gu._read_opencode_go_tokens(home), ["inline-key"])

    def test_auth_redirect_blocks_cross_origin_and_https_downgrade(self):
        import http.server
        import threading

        handler = gu._SameOriginRedirectHandler()
        base_req = gu.urllib.request.Request(
            "https://opencode.ai/zen/go/v1/usage",
            headers={"Authorization": "Bearer secret-token"},
        )
        for bad_url in (
            "https://evil.example/steal",
            "http://opencode.ai/zen/go/v1/usage",
            "https://opencode.ai:8443/zen/go/v1/usage",
        ):
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                handler.redirect_request(base_req, None, 302, "Found", {}, bad_url)
            ctx.exception.close()
        same_req = handler.redirect_request(base_req, None, 302, "Found", {}, "https://opencode.ai/zen/go/v2/usage")
        self.assertEqual(same_req.full_url, "https://opencode.ai/zen/go/v2/usage")

        leaked_headers: list[str] = []

        class _SinkHandler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                leaked_headers.append(self.headers.get("Authorization") or "")
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"usage":{"rolling":{"percent":1}}}')

            def log_message(self, format, *args):
                return

        sink_srv = http.server.HTTPServer(("127.0.0.1", 0), _SinkHandler)
        sink_port = sink_srv.server_address[1]

        class _RedirectorHandler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(302)
                self.send_header("Location", f"http://127.0.0.1:{sink_port}/steal")
                self.end_headers()

            def log_message(self, format, *args):
                return

        redir_srv = http.server.HTTPServer(("127.0.0.1", 0), _RedirectorHandler)
        redir_port = redir_srv.server_address[1]
        t_sink = threading.Thread(target=sink_srv.serve_forever, daemon=True)
        t_redir = threading.Thread(target=redir_srv.serve_forever, daemon=True)
        t_sink.start()
        t_redir.start()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                home = Path(tmp)
                clean_env = {k: v for k, v in os.environ.items() if k not in ("XDG_DATA_HOME", "OPENCODE_API_KEY", "OPENCODE_AUTH_CONTENT", "OPENCODE_DB")}
                with (
                    patch.dict(os.environ, {**clean_env, "OPENCODE_API_KEY": "top-secret-key"}, clear=True),
                    patch.object(gu, "OPENCODE_GO_USAGE_URL", f"http://127.0.0.1:{redir_port}/zen/go/v1/usage"),
                ):
                    res = gu._probe_opencode_limits(home)
                self.assertEqual(res["status"], "error")
                self.assertIn("302", res["message"] or "")
                self.assertEqual(leaked_headers, [])
        finally:
            redir_srv.shutdown()
            sink_srv.shutdown()
            redir_srv.server_close()
            sink_srv.server_close()

    def test_opencode_corrupt_auth_unicode_error_and_limits_fault_isolation(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            oc_dir = home / ".local" / "share" / "opencode"
            oc_dir.mkdir(parents=True)
            (oc_dir / "auth.json").write_bytes(b"\xff\xfe\xfd")
            cache_dir = home / ".cache"
            cache_dir.mkdir()
            clean_env = {k: v for k, v in os.environ.items() if k not in ("XDG_DATA_HOME", "OPENCODE_API_KEY", "OPENCODE_AUTH_CONTENT", "OPENCODE_DB")}
            with patch.dict(os.environ, clean_env, clear=True):
                self.assertEqual(gu._read_opencode_go_tokens(home), [])
                fake_claude = {
                    "cli": "Claude Code",
                    "planType": "Max",
                    "updatedAt": "2026-11-23T00:00:00Z",
                    "source": "live",
                    "status": "ok",
                    "message": None,
                    "windows": [gu._make_window("five_hour", "Session", 10.0, None, 5 * 3_600_000)],
                    "credits": None,
                }
                with (
                    patch.object(gu.Path, "home", return_value=home),
                    patch.object(gu, "_cache_dir", return_value=cache_dir),
                    patch.object(gu, "_has_codex_oauth", side_effect=RuntimeError("corrupt codex")),
                    patch.object(gu, "_has_claude_oauth", return_value=True),
                    patch.object(gu, "_probe_claude_limits", return_value=fake_claude),
                    patch.object(gu, "_has_antigravity_oauth", return_value=False),
                    patch.object(gu, "_has_grok_oauth", return_value=False),
                    patch.object(gu, "_has_deepseek_auth", return_value=False),
                    patch.object(gu, "_has_kimi_oauth", return_value=False),
                ):
                    limits = gu.collect_limits(fresh=True)
                self.assertEqual([p["cli"] for p in limits["providers"]], ["Claude Code"])

    def test_opencode_wal_db_opens_only_once_per_collect_limits_and_preserves_priority(self):
        class _FakeResp:
            status = 200

            def __init__(self, payload: dict):
                self._raw = json.dumps(payload).encode("utf-8")

            def read(self, n: int = -1) -> bytes:
                return self._raw if n < 0 else self._raw[:n]

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            oc_dir = home / ".local" / "share" / "opencode"
            oc_dir.mkdir(parents=True)
            (oc_dir / "auth.json").write_text(
                json.dumps({"opencode-go": {"type": "api", "key": "auth-go-key"}}),
                "utf-8",
            )
            db_path = oc_dir / "opencode.db"
            con = sqlite3.connect(str(db_path))
            con.execute("PRAGMA journal_mode=WAL")
            con.execute(
                "CREATE TABLE credential (id TEXT PRIMARY KEY, integration_id TEXT, value TEXT, active INTEGER, time_created INTEGER, time_updated INTEGER)"
            )
            con.execute(
                "INSERT INTO credential VALUES (?, ?, ?, ?, ?, ?)",
                ("c1", "opencode", json.dumps({"type": "key", "key": "console-key", "metadata": {"server": "https://opencode.ai/console"}}), 1, 2, 2),
            )
            con.execute(
                "INSERT INTO credential VALUES (?, ?, ?, ?, ?, ?)",
                ("c2", "opencode-go", json.dumps({"type": "key", "key": "db-go-key"}), 1, 3, 3),
            )
            con.commit()
            con.execute("INSERT INTO credential VALUES (?, ?, ?, ?, ?, ?)", ("c3", "other", "{}", 1, 4, 4))
            con.commit()

            cache_dir = home / ".cache"
            cache_dir.mkdir()
            clean_env = {k: v for k, v in os.environ.items() if k not in ("XDG_DATA_HOME", "OPENCODE_API_KEY", "OPENCODE_AUTH_CONTENT", "OPENCODE_DB")}
            seen_tokens: list[str] = []

            def fake_urlopen(req, timeout=None):
                seen_tokens.append((req.get_header("Authorization") or "").replace("Bearer ", ""))
                if len(seen_tokens) < 3:
                    raise urllib.error.HTTPError(req.full_url, 401, "Unauthorized", {}, None)
                return _FakeResp({"usage": {"rolling": {"percent": 15, "resetsAt": "2026-11-24T12:00:00Z"}}})

            try:
                with (
                    patch.dict(os.environ, clean_env, clear=True),
                    patch.object(gu.Path, "home", return_value=home),
                    patch.object(gu, "_cache_dir", return_value=cache_dir),
                    patch.object(gu, "_has_codex_oauth", return_value=False),
                    patch.object(gu, "_has_claude_oauth", return_value=False),
                    patch.object(gu, "_has_antigravity_oauth", return_value=False),
                    patch.object(gu, "_has_grok_oauth", return_value=False),
                    patch.object(gu, "_has_deepseek_auth", return_value=False),
                    patch.object(gu, "_has_kimi_oauth", return_value=False),
                    patch.object(gu, "_open_readonly_sqlite", wraps=gu._open_readonly_sqlite) as open_spy,
                    patch.object(gu, "_urlopen_auth", side_effect=fake_urlopen),
                ):
                    limits = gu.collect_limits(fresh=True)
                self.assertEqual(open_spy.call_count, 1)
                self.assertEqual(seen_tokens, ["console-key", "db-go-key", "auth-go-key"])
                self.assertEqual([p["cli"] for p in limits["providers"]], ["OpenCode"])
            finally:
                con.close()

    def test_kimi_multi_pool_same_duration_preserves_distinct_pool_names(self):
        class _FakeResp:
            status = 200

            def __init__(self, payload: dict):
                self._raw = json.dumps(payload).encode("utf-8")

            def read(self, n: int = -1) -> bytes:
                return self._raw if n < 0 else self._raw[:n]

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            kimi_dir = home / ".kimi" / "credentials"
            kimi_dir.mkdir(parents=True)
            (kimi_dir / "kimi-code.json").write_text(
                json.dumps({"access_token": "tok", "expires_at": time.time() + 3600}),
                "utf-8",
            )
            payload = {
                "user": {"membership": {"level": "LEVEL_PRO"}},
                "limits": [
                    {
                        "name": "Model A",
                        "window": {"duration": 5, "timeUnit": "TIME_UNIT_HOUR"},
                        "detail": {"limit": "100", "used": "10"},
                    },
                    {
                        "name": "Model B",
                        "window": {"duration": 5, "timeUnit": "TIME_UNIT_HOUR"},
                        "detail": {"limit": "100", "used": "40"},
                    },
                    {
                        "name": "5h Limit",
                        "window": {"duration": 5, "timeUnit": "TIME_UNIT_HOUR"},
                        "detail": {"limit": "100", "used": "5"},
                    },
                    {
                        "name": "limit_5h",
                        "window": {"duration": 300, "timeUnit": "TIME_UNIT_MINUTE"},
                        "detail": {"limit": "100", "used": "15"},
                    },
                ],
            }
            with (
                patch.dict(os.environ, {"KIMI_SHARE_DIR": str(home / ".kimi")}, clear=False),
                patch.object(gu, "_urlopen_auth", return_value=_FakeResp(payload)),
            ):
                lim = gu._probe_kimi_limits(home)
            labels = [w["label"] for w in lim["windows"]]
            short_lbl = gu._tr("短期", "Session")
            self.assertEqual(
                labels,
                [f"{short_lbl} · Model A", f"{short_lbl} · Model B", short_lbl, short_lbl],
            )

    def test_builtin_model_pricing_json_contract_and_staged_execution(self):
        pricing_path = REPO / "apps/gui/app/src/main/assets/cli_bridge/luma_cli/model_pricing.json"
        self.assertTrue(pricing_path.is_file())
        data = json.loads(pricing_path.read_text("utf-8"))
        self.assertIsInstance(data.get("aliases"), dict)
        self.assertIsInstance(data.get("rates"), dict)
        self.assertGreater(len(data["rates"]), 0)
        self.assertEqual(set(gu.MODEL_PRICING_ALIASES.keys()), {k.lower() for k in data["aliases"]})
        self.assertEqual(set(gu.MODEL_RATES_PER_M.keys()), {k.lower() for k in data["rates"]})
        self.assertEqual(set(gu.MODEL_PRICING_ALIASES) & set(gu.MODEL_RATES_PER_M), set())
        for alias, target in gu.MODEL_PRICING_ALIASES.items():
            base_target = target[:-5] if target.endswith(":fast") else target
            self.assertIn(base_target, gu.MODEL_RATES_PER_M, f"alias {alias} -> missing target {target}")
        builtin_cat = gu._build_runtime_catalog({}, mode="builtin", updated_at="", active_sources=[])
        self.assertEqual(
            gu._lookup_model_rate_in_catalog("grok-4.7-build", builtin_cat),
            gu.MODEL_RATES_PER_M["grok-4.7"],
        )
        self.assertEqual(
            gu._lookup_model_rate_in_catalog("grok-4.7-build-fast", builtin_cat),
            (4.0, 12.0, 1.0, 4.0, (200_000, 8.0, 24.0, 2.0, 8.0)),
        )
        online_cat = gu._build_runtime_catalog(
            {"grok-4.7": (3.0, 9.0, 0.75, 3.0, None)},
            mode="online",
            updated_at="2026-11-25T00:00:00Z",
            active_sources=["models.dev"],
        )
        self.assertEqual(
            gu._lookup_model_rate_in_catalog("grok-4.7-build-fast", online_cat),
            (6.0, 18.0, 1.5, 6.0, None),
        )
        self.assertEqual(
            gu._lookup_model_rate_in_catalog("codex-mini-latest", builtin_cat),
            gu.MODEL_RATES_PER_M["codex-mini"],
        )
        self.assertEqual(
            gu._lookup_model_rate_in_catalog("deepseek-v4", builtin_cat),
            gu.MODEL_RATES_PER_M["deepseek-v4-pro"],
        )
        self.assertEqual(
            gu._lookup_model_rate_in_catalog("gemini-pro-orchestrator-v2", builtin_cat),
            gu.MODEL_RATES_PER_M["gemini-4-carbon"],
        )
        with tempfile.TemporaryDirectory() as tmp:
            stage_dir = Path(tmp)
            shutil.copyfile(
                REPO / "apps/gui/app/src/main/assets/cli_bridge/luma_cli/global_usage.py",
                stage_dir / "global-usage.py",
            )
            shutil.copyfile(pricing_path, stage_dir / "model_pricing.json")
            proc = subprocess.run(
                [sys.executable, "-I", str(stage_dir / "global-usage.py"), "codex-session"],
                env={"PATH": os.environ.get("PATH", ""), "HOME": tmp, "XDG_CACHE_HOME": tmp},
                capture_output=True,
                text=True,
                timeout=10,
            )
            self.assertEqual(proc.returncode, 0, proc.stderr)
            payload = json.loads(proc.stdout)
            self.assertIn("gpt-6.1-sol", payload["catalog"]["rates"])
            self.assertEqual(payload["catalog"]["aliases"]["kimi-for-coding"], "kimi-k2.7-code")

    def test_online_pricing_parser_filters_reseller_pollution_and_regional_markups(self):
        md_raw = {
            "alibaba-cn": {
                "models": {
                    "deepseek-r1": {"cost": {"input": 0.574, "output": 2.294}},
                    "kimi-k2.5": {"cost": {"input": 0.574, "output": 3.011}},
                    "qwen3-max": {"cost": {"input": 1.5, "output": 7.5, "cache_read": 0.3}},
                },
            },
            "anthropic": {
                "models": {
                    "claude-opus-4-6": {"cost": {"input": 5.0, "output": 25.0, "cache_read": 0.5, "cache_write": 6.25}},
                },
            },
            "google": {
                "models": {
                    "gemini-3.1-pro-preview": {
                        "cost": {
                            "input": 2.0,
                            "output": 12.0,
                            "cache_read": 0.2,
                            "tiers": [{"input": 4.0, "output": 18.0, "cache_read": 0.4, "tier": {"type": "context", "size": 200000}}],
                        },
                    },
                },
            },
            "poe": {
                "models": {
                    "anthropic/claude-opus-4": {"cost": {"input": 13.0, "output": 64.0, "cache_read": 1.3, "cache_write": 16.0}},
                },
            },
            "helicone": {
                "models": {
                    "claude-opus-4": {"cost": {"input": 15.0, "output": 75.0, "cache_read": 1.5, "cache_write": 18.75}},
                },
            },
            "openrouter": {
                "models": {
                    "anthropic/claude-opus-4.6": {
                        "cost": {
                            "input": 5.0,
                            "output": 25.0,
                            "cache_read": 0.5,
                            "cache_write": 6.25,
                            "tiers": [{"input": 10.0, "output": 37.5, "cache_read": 1.0, "cache_write": 12.5, "tier": {"type": "context", "size": 200000}}],
                        },
                    },
                },
            },
        }
        ll_raw = {
            "deepseek/deepseek-r1": {"input_cost_per_token": 0.55e-6, "output_cost_per_token": 2.19e-6, "cache_read_input_token_cost": 0.14e-6},
            "moonshot/kimi-k2.5": {"input_cost_per_token": 0.6e-6, "output_cost_per_token": 3.0e-6, "cache_read_input_token_cost": 0.1e-6},
            "us.anthropic.claude-sonnet-4-6": {"input_cost_per_token": 3.3e-6, "output_cost_per_token": 16.5e-6},
            "deepinfra/google/gemini-3.1-pro": {"input_cost_per_token": 2.0e-6, "output_cost_per_token": 12.0e-6},
        }

        def fake_fetch(url, timeout=6.5):
            if url == gu.MODELS_DEV_API_URL:
                return md_raw
            if url == gu.LITELLM_PRICES_URLS[0]:
                return ll_raw
            return None

        with tempfile.TemporaryDirectory() as tmp:
            cache_dir = Path(tmp) / ".cache"
            cache_dir.mkdir()
            with (
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.object(gu, "_fetch_remote_json", side_effect=fake_fetch),
            ):
                cat = gu._sync_pricing_catalog(force=True)
                self.assertEqual(gu._lookup_model_rate_in_catalog("deepseek-r1", cat), (0.55, 2.19, 0.14, 0.55, None))
                self.assertEqual(gu._lookup_model_rate_in_catalog("kimi-k2.5", cat), (0.6, 3.0, 0.1, 0.6, None))
                self.assertEqual(gu._lookup_model_rate_in_catalog("qwen3-max", cat), (1.5, 7.5, 0.3, 1.5, None))
                self.assertEqual(gu._lookup_model_rate_in_catalog("claude-opus-4", cat), (15.0, 75.0, 1.5, 18.75, None))
                self.assertEqual(gu._lookup_model_rate_in_catalog("claude-opus-4-6", cat), (5.0, 25.0, 0.5, 6.25, None))
                self.assertEqual(
                    gu._lookup_model_rate_in_catalog("gemini-3.1-pro", cat),
                    (2.0, 12.0, 0.2, 2.0, (200_000, 4.0, 18.0, 0.4, 4.0)),
                )
                self.assertEqual(gu._lookup_model_rate_in_catalog("claude-sonnet-4-6", cat)[0], 3.0)

    def test_antigravity_custom_orchestrator_planner_executor_and_dynamic_enums(self):
        def pb_varint(val: int) -> bytes:
            out = bytearray()
            v = int(val)
            while v >= 0x80:
                out.append((v & 0x7F) | 0x80)
                v >>= 7
            out.append(v)
            return bytes(out)

        def pb_num(field: int, val: int) -> bytes:
            return pb_varint((field << 3) | 0) + pb_varint(val)

        def pb_bytes(field: int, raw: bytes) -> bytes:
            return pb_varint((field << 3) | 2) + pb_varint(len(raw)) + raw

        def pb_str(field: int, s: str) -> bytes:
            return pb_bytes(field, s.encode("utf-8"))

        def pb_ts(sec: int) -> bytes:
            return pb_num(1, sec)

        now_sec = int(dt.datetime.now(dt.timezone.utc).timestamp()) - 30
        with tempfile.TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "conv-custom-orch.db"
            con = sqlite3.connect(str(db_path))
            con.execute("CREATE TABLE steps (idx INTEGER PRIMARY KEY, step_type INTEGER, metadata BLOB)")
            con.execute("CREATE TABLE gen_metadata (idx INTEGER PRIMARY KEY, data BLOB)")
            for idx in range(7):
                con.execute("INSERT INTO steps VALUES (?, 15, ?)", (idx, pb_bytes(1, pb_ts(now_sec + idx))))

            u0 = pb_num(1, 1402) + pb_num(2, 100_000) + pb_num(10, 10_000) + pb_str(11, "custom-plan-enum")
            c0 = pb_num(3, 1667) + pb_str(19, "gemini-pro-orchestrator") + pb_str(21, "Orchestrator (Claude Opus 5.5)") + pb_bytes(4, u0)
            con.execute("INSERT INTO gen_metadata VALUES (0, ?)", (pb_bytes(1, c0) + pb_num(2, 0),))

            u1 = pb_num(1, 1667) + pb_num(2, 100_000) + pb_num(10, 10_000) + pb_str(11, "custom-plan-label")
            c1 = pb_num(3, 1667) + pb_str(19, "gemini-pro-orchestrator") + pb_str(21, "Orchestrator (Claude Opus 5.5 High)") + pb_bytes(4, u1)
            con.execute("INSERT INTO gen_metadata VALUES (1, ?)", (pb_bytes(1, c1) + pb_num(2, 1),))

            u2 = pb_num(1, 1405) + pb_num(2, 100_000) + pb_num(10, 10_000) + pb_str(11, "custom-exec-enum")
            c2 = pb_num(3, 1669) + pb_str(19, "gemini-flash-worker-agy") + pb_str(21, "Gemini Flash") + pb_bytes(4, u2)
            con.execute("INSERT INTO gen_metadata VALUES (2, ?)", (pb_bytes(1, c2) + pb_num(2, 2),))

            u3 = pb_num(1, 1669) + pb_num(2, 100_000) + pb_num(10, 10_000) + pb_str(11, "custom-exec-label")
            c3 = pb_num(3, 1669) + pb_str(19, "gemini-flash-worker-agy") + pb_str(21, "Claude Sonnet 5.5 (Orchestrator)") + pb_bytes(4, u3)
            con.execute("INSERT INTO gen_metadata VALUES (3, ?)", (pb_bytes(1, c3) + pb_num(2, 3),))

            u4 = pb_num(1, 1899) + pb_num(2, 50_000) + pb_num(10, 5_000) + pb_str(11, "future-gen")
            c4 = pb_num(3, 1899) + pb_str(19, "claude-opus-5-5-max") + pb_str(21, "Claude Opus 5.5 (Max)") + pb_bytes(4, u4)
            con.execute("INSERT INTO gen_metadata VALUES (4, ?)", (pb_bytes(1, c4) + pb_num(2, 4),))

            u5 = pb_num(1, 1532) + pb_num(2, 50_000) + pb_num(10, 5_000) + pb_str(11, "neon-high-step")
            con.execute("UPDATE steps SET metadata = ? WHERE idx = 5", (pb_bytes(1, pb_ts(now_sec + 5)) + pb_bytes(9, u5),))

            u6 = pb_num(1, 1899) + pb_num(2, 50_000) + pb_num(10, 5_000) + pb_str(11, "future-step")
            con.execute("UPDATE steps SET metadata = ? WHERE idx = 6", (pb_bytes(1, pb_ts(now_sec + 6)) + pb_bytes(9, u6),))

            con.commit()
            con.close()

            recs, *_ = gu._extract_agy_db_session(db_path)
            self.assertEqual(len(recs), 7)
            self.assertEqual(recs[0]["model"], "claude-opus-5-5")
            self.assertEqual(recs[1]["model"], "claude-opus-5-5")
            self.assertEqual(recs[2]["model"], "claude-sonnet-5-5")
            self.assertEqual(recs[2]["uiLabel"], "Gemini Flash")
            self.assertEqual(recs[3]["model"], "claude-sonnet-5-5")
            self.assertEqual(recs[4]["model"], "claude-opus-5-5-max")
            self.assertEqual(recs[5]["model"], "gemini-4-neon")
            self.assertEqual(recs[6]["model"], "claude-opus-5-5-max")


if __name__ == "__main__":
    unittest.main()

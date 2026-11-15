#!/usr/bin/env python3
from pathlib import Path
import datetime as dt
import io
import json
import os
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

    def test_antigravity_dual_endpoint_merge_and_pooled_policy(self):
        requested_at_ms = gu._parse_ts_ms("2026-11-07T01:36:14Z")
        assert requested_at_ms is not None

        daily_payload = {
            "groups": [
                {
                    "displayName": "Gemini Models",
                    "buckets": [
                        {
                            "bucketId": "gemini-weekly",
                            "displayName": "Weekly Limit",
                            "window": "weekly",
                            "remainingFraction": 0.9749,
                            "resetTime": "2026-11-11T17:58:23Z",
                            "description": "97% remaining",
                        },
                        {
                            "bucketId": "gemini-5h",
                            "displayName": "5-Hour Limit",
                            "window": "5h",
                            "remainingFraction": 0.9096,
                            "resetTime": "2026-11-07T02:56:04Z",
                            "description": "90% remaining",
                        },
                    ],
                },
                {
                    "displayName": "Gemini 4 Argon",
                    "buckets": [
                        {
                            "bucketId": "gemini-argon-wtu-weekly",
                            "displayName": "Weekly Limit",
                            "window": "weekly",
                            "remainingFraction": 0.65,
                            "resetTime": "2026-11-12T10:00:00Z",
                            "description": "65% remaining",
                        },
                    ],
                },
                {
                    "displayName": "Claude and GPT models",
                    "buckets": [
                        {
                            "bucketId": "3p-weekly",
                            "displayName": "Weekly Limit",
                            "window": "weekly",
                            "remainingFraction": 0.0127,
                            "resetTime": "2026-11-10T10:25:17Z",
                            "description": "1% remaining",
                        },
                        {
                            "bucketId": "3p-5h",
                            "displayName": "5-Hour Limit",
                            "window": "5h",
                            "remainingFraction": 1.0,
                            "resetTime": "2026-11-07T06:36:14Z",
                        },
                    ],
                },
            ]
        }

        prod_payload = {
            "groups": [
                {
                    "displayName": "Gemini Models",
                    "buckets": [
                        {
                            "bucketId": "gemini-weekly",
                            "displayName": "Weekly Limit",
                            "window": "weekly",
                            "remainingFraction": 0.8924,
                            "resetTime": "2026-11-11T17:58:23Z",
                            "description": "89% remaining",
                        },
                        {
                            "bucketId": "gemini-5h",
                            "displayName": "5-Hour Limit",
                            "window": "5h",
                            "remainingFraction": 1.0,
                            "resetTime": "2026-11-07T06:36:14Z",
                        },
                    ],
                },
                {
                    "displayName": "Claude and GPT models",
                    "buckets": [
                        {
                            "bucketId": "3p-weekly",
                            "displayName": "Weekly Limit",
                            "window": "weekly",
                            "remainingFraction": 0.0127,
                            "resetTime": "2026-11-10T10:25:17Z",
                            "description": "1% remaining",
                        },
                        {
                            "bucketId": "3p-5h",
                            "displayName": "5-Hour Limit",
                            "window": "5h",
                            "remainingFraction": 1.0,
                            "resetTime": "2026-11-07T06:36:14Z",
                        },
                    ],
                },
            ]
        }

        daily_wins = gu._parse_antigravity_summary_windows(daily_payload, requested_at_ms)
        prod_wins = gu._parse_antigravity_summary_windows(prod_payload, requested_at_ms)
        merged = gu._merge_antigravity_host_windows([daily_wins, prod_wins])

        by_id = {w["id"]: w for w in merged}
        self.assertEqual(
            [w["id"] for w in merged],
            ["gemini-5h", "gemini-weekly", "gemini-argon-wtu-weekly", "3p-5h", "3p-weekly"],
        )
        self.assertAlmostEqual(by_id["gemini-5h"]["usedPercent"], 9.04, places=1)
        self.assertEqual(by_id["gemini-5h"]["label"], f"Gemini · {gu._tr('5h 窗口', '5h window')}")
        self.assertAlmostEqual(by_id["gemini-weekly"]["usedPercent"], 10.76, places=1)
        self.assertEqual(by_id["gemini-weekly"]["label"], f"Gemini · {gu._tr('7d 窗口', '7d window')}")
        self.assertEqual(by_id["gemini-argon-wtu-weekly"]["label"], f"Gemini 4 Argon · {gu._tr('7d 窗口', '7d window')}")
        self.assertAlmostEqual(by_id["gemini-argon-wtu-weekly"]["usedPercent"], 35.0, places=1)
        self.assertEqual(by_id["3p-5h"]["usedPercent"], 0.0)
        self.assertIsNone(by_id["3p-5h"]["resetsAt"])
        self.assertAlmostEqual(by_id["3p-weekly"]["usedPercent"], 98.73, places=1)
        self.assertEqual(by_id["3p-weekly"]["resetsAt"], "2026-11-10T10:25:17Z")

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
            (agy_dir / "antigravity-oauth-token").write_text(
                json.dumps({"token": {"access_token": "tok_agy", "refresh_token": "ref_agy"}}),
                "utf-8",
            )

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
                    "planType": "Google OAuth",
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
                patch.dict(os.environ, {"CODEX_HOME": str(home / ".codex")}, clear=False),
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
            self.assertFalse((cache_dir / "usage-limits-cache-v4.json").exists())

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
        now_utc = dt.datetime.now(dt.timezone.utc)
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
                patch.dict(os.environ, {"CODEX_HOME": str(home / ".codex")}, clear=False),
            ):
                offline_summary = gu.collect_summary(period="week", tz_name="UTC", refresh=True)
                self.assertEqual(offline_summary["pricingSource"]["mode"], "builtin")
                self.assertAlmostEqual(offline_summary["totals"]["costUsd"], 3.10, places=5)

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
                    self.assertEqual(gu._lookup_model_rate("qwen3-coder-plus"), (1.0, 5.0, 1.0, 1.0, None))
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
            raw_dsh = "\n".join(json.dumps(e) for e in dsh_events) + "\n"
            from compression import zstd
            (dsh_dir / "session.v4.jsonl.zstd").write_bytes(zstd.compress(raw_dsh.encode("utf-8")))

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
                patch.object(gu.urllib.request, "urlopen", return_value=FakeResp(usages_payload)),
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

            scope_key = "https://auth.x.ai::b1a00492-073a-47ea-816f-4c329264a828"
            (grok_home / "auth.json").write_text(
                json.dumps({
                    scope_key: {
                        "auth_mode": "oidc",
                        "email": "dev@example.com",
                        "key": "expired-oidc-tok",
                        "refresh_token": "ref-oidc-1",
                        "expires_at": int(now_utc.timestamp()) - 120,
                        "oidc_issuer": "https://auth.x.ai",
                        "oidc_client_id": "b1a00492-073a-47ea-816f-4c329264a828",
                    }
                }),
                "utf-8",
            )

            def _varint(v: int) -> bytes:
                out = bytearray()
                while True:
                    b = v & 0x7F
                    v >>= 7
                    if v:
                        out.append(b | 0x80)
                    else:
                        out.append(b)
                        break
                return bytes(out)

            start_sec = int(now_utc.timestamp()) - 86_400
            end_sec = start_sec + 7 * 86_400
            ts_start = b"\x08" + _varint(start_sec)
            ts_end = b"\x08" + _varint(end_sec)
            cfg_msg = (
                b"\x0d" + struct.pack("<f", 88.0)
                + b"\x22" + _varint(len(ts_start)) + ts_start
                + b"\x2a" + _varint(len(ts_end)) + ts_end
            )
            top_msg = b"\x0a" + _varint(len(cfg_msg)) + cfg_msg
            trailer_bytes = b"grpc-status: 0\r\n"
            grpc_wire = (
                b"\x00" + len(top_msg).to_bytes(4, "big") + top_msg
                + b"\x80" + len(trailer_bytes).to_bytes(4, "big") + trailer_bytes
            )

            class FakeBytesResp:
                def __init__(self, raw: bytes):
                    self._raw = raw
                def read(self):
                    return self._raw
                def __enter__(self):
                    return self
                def __exit__(self, *args):
                    return False

            seen_auth_headers: list[str] = []

            def fake_urlopen(req, timeout=None):
                url = req.full_url
                if "/oauth2/token" in url:
                    return FakeBytesResp(json.dumps({"access_token": "fresh-oidc-tok", "refresh_token": "ref-oidc-2", "expires_in": 3600}).encode("utf-8"))
                seen_auth_headers.append(req.headers.get("Authorization") or req.headers.get("authorization") or "")
                if "cli-chat-proxy.grok.com" in url:
                    import io
                    raise urllib.error.HTTPError(url, 403, "Forbidden", {}, io.BytesIO(b"WKE=privacy-mode: blocked"))
                if "GetGrokCreditsConfig" in url:
                    return FakeBytesResp(grpc_wire)
                raise AssertionError(f"Unexpected URL: {url}")

            with (
                patch.object(gu.Path, "home", return_value=home),
                patch.object(gu, "_cache_dir", return_value=cache_dir),
                patch.dict(os.environ, {"GROK_HOME": str(grok_home)}, clear=False),
                patch.object(gu.urllib.request, "urlopen", side_effect=fake_urlopen),
            ):
                s = gu.collect_summary(period="today", tz_name="UTC", refresh=True, sync_pricing=False)
                by_cli = {row["cli"]: row for row in s["byProvider"]}
                self.assertEqual(by_cli["Grok Build"]["totalTokens"], 10_500 + 31_000)

                lim = gu._probe_grok_limits(home)
                self.assertEqual(lim["status"], "ok")
                self.assertEqual(lim["planType"], "dev@example.com")
                self.assertEqual(len(lim["windows"]), 1)
                self.assertAlmostEqual(lim["windows"][0]["usedPercent"], 88.0, places=2)
                self.assertEqual(lim["windows"][0]["windowDurationMs"], 7 * 86_400_000)
                self.assertIn("Bearer fresh-oidc-tok", seen_auth_headers)

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
            from compression import zstd
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
            (dsh_sess / "session.v3.jsonl.zst").write_bytes(zstd.compress(("\n".join(json.dumps(e) for e in v3_events) + "\n").encode("utf-8")))

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
                patch.object(gu.urllib.request, "urlopen", side_effect=fake_urlopen),
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

    def test_oauth_atomic_refresh_preserves_keys_sets_0600_and_aborts_on_write_failure(self):
        class _JsonResp:
            def __init__(self, data: dict):
                self._raw = json.dumps(data).encode("utf-8")
            def read(self):
                return self._raw
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return False

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            grok_file = root / "auth.json"
            grok_orig = {
                "other_scope": {"keep": 1},
                "https://accounts.x.ai/sign-in": {
                    "auth_mode": "oidc",
                    "access_token": "old_g_acc",
                    "refresh_token": "old_g_ref",
                    "oidc_issuer": "https://auth.x.ai",
                    "oidc_client_id": "cid-1",
                    "expires_at": 1000,
                    "email": "u@example.com",
                },
            }
            grok_file.write_text(json.dumps(grok_orig), "utf-8")
            grok_auth = {
                "token": "old_g_acc",
                "refreshToken": "old_g_ref",
                "oidcIssuer": "file:///etc/passwd",
                "oidcClientId": "cid-1",
                "authFile": grok_file,
                "scopeKey": "https://accounts.x.ai/sign-in",
            }
            self.assertIsNone(gu._refresh_grok_access_token(grok_auth))

            grok_auth["oidcIssuer"] = "https://auth.x.ai"
            with patch.object(
                gu.urllib.request,
                "urlopen",
                return_value=_JsonResp({"access_token": "new_g_acc", "refresh_token": "new_g_ref", "expires_in": 1800}),
            ):
                tok = gu._refresh_grok_access_token(grok_auth)
            self.assertEqual(tok, "new_g_acc")
            self.assertEqual(stat.S_IMODE(grok_file.stat().st_mode), 0o600)
            saved_grok = json.loads(grok_file.read_text("utf-8"))
            self.assertEqual(saved_grok["other_scope"], {"keep": 1})
            self.assertEqual(saved_grok["https://accounts.x.ai/sign-in"]["email"], "u@example.com")
            self.assertEqual(saved_grok["https://accounts.x.ai/sign-in"]["refresh_token"], "new_g_ref")

            with (
                patch.object(gu.urllib.request, "urlopen", return_value=_JsonResp({"access_token": "bad_g", "refresh_token": "bad_ref"})),
                patch.object(gu, "_atomic_write_text", side_effect=OSError("disk full")),
            ):
                self.assertIsNone(gu._refresh_grok_access_token(grok_auth))
            self.assertEqual(grok_auth["token"], "new_g_acc")

            kimi_file = root / "kimi-code.json"
            kimi_file.write_text(
                json.dumps({"access_token": "old_k", "refresh_token": "old_k_ref", "email": "k@example.com", "device_id": "d1"}),
                "utf-8",
            )
            kimi_auth = {
                "accessToken": "old_k",
                "refreshToken": "old_k_ref",
                "oauthHost": "ftp://bad.host",
                "credFile": kimi_file,
            }
            self.assertIsNone(gu._refresh_kimi_access_token(kimi_auth))

            kimi_auth["oauthHost"] = "https://auth.kimi.com"
            with patch.object(
                gu.urllib.request,
                "urlopen",
                return_value=_JsonResp({"access_token": "new_k", "refresh_token": "new_k_ref", "expires_in": 3600}),
            ):
                ktok = gu._refresh_kimi_access_token(kimi_auth)
            self.assertEqual(ktok, "new_k")
            self.assertEqual(stat.S_IMODE(kimi_file.stat().st_mode), 0o600)
            saved_kimi = json.loads(kimi_file.read_text("utf-8"))
            self.assertEqual(saved_kimi["email"], "k@example.com")
            self.assertEqual(saved_kimi["device_id"], "d1")
            self.assertEqual(saved_kimi["refresh_token"], "new_k_ref")

            with (
                patch.object(gu.urllib.request, "urlopen", return_value=_JsonResp({"access_token": "fail_k", "refresh_token": "fail_ref"})),
                patch.object(gu, "_atomic_write_text", side_effect=OSError("ro")),
            ):
                self.assertIsNone(gu._refresh_kimi_access_token(kimi_auth))
            self.assertEqual(kimi_auth["accessToken"], "new_k")

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


if __name__ == "__main__":
    unittest.main()

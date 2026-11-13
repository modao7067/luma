#!/usr/bin/env python3
from pathlib import Path
import datetime as dt
import json
import os
import sqlite3
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "apps/gui/app/src/main/assets/cli_bridge"))

from luma_cli import global_usage as gu

class GlobalUsageTest(unittest.TestCase):
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
        self.assertEqual(by_id["gemini-5h"]["label"], "Gemini · 5h 窗口")
        self.assertAlmostEqual(by_id["gemini-weekly"]["usedPercent"], 10.76, places=1)
        self.assertEqual(by_id["gemini-weekly"]["label"], "Gemini · 7d 窗口")
        self.assertEqual(by_id["gemini-argon-wtu-weekly"]["label"], "Gemini 4 Argon · 7d 窗口")
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
                    "windows": [gu._make_window("primary", "5h 窗口", 22.0)],
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
            self.assertIn("超时", providers[1]["message"])
            self.assertFalse((cache_dir / "usage-limits-cache-v3.json").exists())

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
                pb_num(1, 1026)
                + pb_num(2, 2974)
                + pb_num(5, 6000)
                + pb_num(9, 800)
                + pb_num(10, 200)
                + pb_str(11, "resp-0")
            )
            chat0 = pb_str(19, "gemini-3.8-flash-n") + pb_str(21, "Gemini 3.8 Flash") + pb_bytes(4, usage0)
            gen0 = pb_bytes(1, chat0) + pb_num(2, 0)
            con.execute("INSERT INTO gen_metadata VALUES (0, ?)", (gen0,))

            usage1 = pb_num(1, 1000) + pb_num(2, 1000) + pb_num(9, 500) + pb_str(11, "resp-1")
            chat1 = pb_str(19, "gemini-default") + pb_str(21, "Gemini 3.8 Flash") + pb_bytes(4, usage1)
            gen1 = pb_bytes(1, chat1) + pb_num(2, 1)
            con.execute("INSERT INTO gen_metadata VALUES (1, ?)", (gen1,))

            usage2 = pb_num(1, 1132) + pb_num(2, 3868) + pb_num(9, 1000) + pb_str(11, "resp-2")
            chat2 = pb_str(19, "gemini-4-argon-trial") + pb_str(21, "Gemini 4 Argon (High)") + pb_bytes(4, usage2)
            gen2 = pb_bytes(1, chat2) + pb_num(2, 2)
            con.execute("INSERT INTO gen_metadata VALUES (2, ?)", (gen2,))
            con.commit()
            con.close()

            with (
                patch.object(gu.Path, "home", return_value=home),
                patch.object(gu, "_cache_dir", return_value=cache_dir),
            ):
                summary = gu.collect_summary(period="today", tz_name="UTC", refresh=True)

            models = {m["model"]: m for m in summary["byModel"]}
            self.assertIn("gemini-3.8-flash-n", models)
            self.assertIn("gemini-4-argon-trial", models)
            self.assertNotIn("claude-opus-4-5", models)

            m_flash_n = models["gemini-3.8-flash-n"]
            self.assertEqual(m_flash_n["events"], 2)
            self.assertEqual(m_flash_n["totalTokens"], 13500)
            self.assertTrue(m_flash_n["isVariantEndpoint"])
            self.assertGreater(m_flash_n["costUsd"], 0.0)
            self.assertEqual(m_flash_n["uiLabels"], ["Gemini 3.8 Flash"])

            m_argon_trial = models["gemini-4-argon-trial"]
            self.assertEqual(m_argon_trial["totalTokens"], 6000)
            self.assertTrue(m_argon_trial["isVariantEndpoint"])
            self.assertGreater(m_argon_trial["costUsd"], 0.0)
            self.assertEqual(m_argon_trial["uiLabels"], ["Gemini 4 Argon (High)"])

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

if __name__ == "__main__":
    unittest.main()

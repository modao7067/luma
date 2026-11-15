#!/usr/bin/env python3
import sys
sys.dont_write_bytecode = True
import copy
import json
import os
import pathlib
import tempfile
import threading
import time
import types
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "apps/gui/app/src/main/assets/cli_bridge"))
from luma_cli.antigravity_cli import (
    AntigravityCliSession,
    PROTOCOL,
    child_transcript_state,
    project_child_transcript,
    swarm_added_workers,
    swarm_kill_target,
    swarm_mentions,
    swarm_model_update,
    swarm_status_updates,
)
from luma_cli.i18n import lang_code, set_lang


class AntigravitySwarmHelperTests(unittest.TestCase):
    def test_swarm_added_workers_single_plural_and_error(self):
        single = swarm_added_workers(
            {"action": "add", "name": "reviewer", "brief": "Audit foo.py", "workspace": "share"},
            json.dumps({"status": "added", "name": "reviewer", "model": "gemini-3.1-pro-low", "conversation_id": "w-rev-1"}),
        )
        self.assertEqual(single, [{
            "id": "w-rev-1",
            "name": "reviewer",
            "role": "swarm · share",
            "task": "Audit foo.py",
            "model": "gemini-3.1-pro-low",
            "group": "",
        }])

        batch = swarm_added_workers(
            {"action": "add", "name": "batch", "brief": "Shared brief", "tasks": ["slice-1", "slice-2"], "workspace": "branch"},
            json.dumps({"status": "added", "names": ["batch-1", "batch-2"], "model": "gemini-3.8-flash-low", "conversation_ids": ["w-b-1", "w-b-2"]}),
        )
        self.assertEqual(batch, [
            {"id": "w-b-1", "name": "batch-1", "role": "swarm · branch", "task": "slice-1\n\nShared brief", "model": "gemini-3.8-flash-low", "group": "batch"},
            {"id": "w-b-2", "name": "batch-2", "role": "swarm · branch", "task": "slice-2\n\nShared brief", "model": "gemini-3.8-flash-low", "group": "batch"},
        ])

        self.assertEqual(swarm_added_workers({"action": "add", "name": "x"}, json.dumps({"error": "duplicate"})), [])

    def test_swarm_status_model_kill_and_mentions_parsers(self):
        updates = swarm_status_updates(json.dumps({
            "title": "Main",
            "members": [
                {"name": "coordinator", "role": "coordinator", "model": "m0", "state": "working", "killed": False},
                {"name": "w1", "role": "worker", "model": "m1", "state": "working", "killed": False},
                {"name": "w2", "role": "worker", "model": "m2", "state": "waiting", "killed": False},
                {"name": "w3", "role": "worker", "model": "m3", "state": "stopped", "killed": False},
                {"name": "w4", "role": "worker", "model": "m4", "state": "stopped", "killed": True},
            ],
        }))
        self.assertEqual(updates, [
            {"id": "", "name": "w1", "model": "m1", "status": "running", "nativeStatus": "swarm:working"},
            {"id": "", "name": "w2", "model": "m2", "status": "succeeded", "nativeStatus": "swarm:waiting"},
            {"id": "", "name": "w3", "model": "m3", "status": "succeeded", "nativeStatus": "swarm:stopped"},
            {"id": "", "name": "w4", "model": "m4", "status": "closed", "nativeStatus": "swarm:killed"},
        ])
        self.assertEqual(swarm_model_update({"action": "model", "name": "w1", "model": "m-old"}, '{"status":"switched","name":"w1","model":"m-new"}'), ("w1", "m-new"))
        self.assertEqual(swarm_model_update({"action": "model", "name": "w1", "model": "m-new"}, '{"error":"worker is working"}'), ("", ""))
        self.assertEqual(swarm_kill_target({"action": "kill", "name": "w1"}, '{"status":"killed","name":"w1"}'), "w1")
        self.assertEqual(swarm_kill_target({"action": "kill", "name": "w1"}, '{"error":"unknown member"}'), "")
        self.assertEqual(swarm_mentions("email test@example.com then @w1 and @batch"), (["w1", "batch"], False))
        self.assertEqual(swarm_mentions("@all finish up"), (["all"], True))

    def test_child_transcript_state_and_system_ping_filtering(self):
        parked_steps = [
            {"step_index": 0, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": "<SYSTEM_MESSAGE>[swarm] new board message #1</SYSTEM_MESSAGE>"},
            {"step_index": 1, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": "<SYSTEM_MESSAGE>[Message] sender=coordinator content=Audit A</SYSTEM_MESSAGE>"},
            {"step_index": 2, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                {"name": "post_message", "args": {"text": "@coordinator done A"}},
                {"name": "swarm_wait", "args": {"since": "1"}},
            ]},
            {"step_index": 3, "type": "GENERIC", "status": "DONE", "content": '{"status":"posted","seq":2}'},
            {"step_index": 4, "type": "GENERIC", "status": "DONE", "content": "Waiting for message"},
        ]
        self.assertEqual(child_transcript_state(parked_steps), "idle")
        self.assertEqual(child_transcript_state(parked_steps[:-1]), "running")
        self.assertEqual(child_transcript_state(parked_steps + [{"step_index": 5, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": "<SYSTEM_MESSAGE>[swarm] new board message #3</SYSTEM_MESSAGE>"}]), "running")
        failed_wait = copy.deepcopy(parked_steps)
        failed_wait[-1]["status"] = "ERROR"
        self.assertEqual(child_transcript_state(failed_wait), "running")

        msgs = project_child_transcript("c-1", parked_steps)
        self.assertFalse(any("[swarm] new board message #1" in (m.get("text") or "") for m in msgs))
        self.assertTrue(any("Audit A" in (m.get("text") or "") for m in msgs))


class AntigravityCliSwarmSessionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="luma-cli-swarm-")
        self.root = pathlib.Path(self.tmp.name)
        self.gemini_home = self.root / ".gemini"
        self.prev_lang = lang_code()

    def tearDown(self):
        set_lang(self.prev_lang)
        self.tmp.cleanup()

    def _transcript_path(self, conv_id, full=False):
        name = "transcript_full.jsonl" if full else "transcript.jsonl"
        path = self.gemini_home / "antigravity-cli" / "brain" / conv_id / ".system_generated" / "logs" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        return path

    def _write_steps(self, conv_id, steps, full_steps=None):
        path = self._transcript_path(conv_id)
        with path.open("w", encoding="utf-8") as handle:
            for step in steps:
                handle.write(json.dumps(step, ensure_ascii=False) + "\n")
        if full_steps is not None:
            full_path = self._transcript_path(conv_id, full=True)
            with full_path.open("w", encoding="utf-8") as handle:
                for step in full_steps:
                    handle.write(json.dumps(step, ensure_ascii=False) + "\n")
        return path

    def _make_session(self, native_id, saved=None):
        config = {"cli": "Antigravity", "cwd": str(self.root), "nativeId": native_id}
        if saved is not None:
            config["localProjection"] = saved
        s = AntigravityCliSession(config, lambda: None)
        s.tail_stop.set()
        s.current_submission = "turn-1"
        return s

    def test_step_stream_handles_swarm_add_status_model_kill_post_and_bilingual_release(self):
        parent_id = "p-cli-1"
        w1 = "w-cli-1"
        w2 = "w-cli-2"
        w3 = "w-cli-3"
        for lang in ("zh", "en"):
            set_lang(lang)
            with patch.dict(os.environ, {"GEMINI_HOME": str(self.gemini_home)}):
                s = self._make_session(parent_id + "-" + lang)
                self._write_steps(w1, [
                    {"step_index": 0, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                        {"name": "post_message", "args": {"text": "@coordinator audit complete"}},
                        {"name": "swarm_wait", "args": {"since": "1"}},
                    ]},
                    {"step_index": 1, "type": "GENERIC", "status": "DONE", "content": '{"status":"posted","seq":2}'},
                    {"step_index": 2, "type": "GENERIC", "status": "DONE", "content": "Waiting"},
                ])
                self._write_steps(w2, [{"step_index": 0, "type": "PLANNER_RESPONSE", "status": "RUNNING", "thinking": "x"}])
                self._write_steps(w3, [{"step_index": 0, "type": "PLANNER_RESPONSE", "status": "RUNNING", "thinking": "y"}])

                s.step({
                    "step_index": 2, "state": "DONE", "step_type": "tool",
                    "tool_info": {
                        "name": "swarm",
                        "parameters": {"action": "add", "name": "solo", "brief": "Audit auth"},
                        "output": json.dumps({"status": "added", "name": "solo", "model": "gemini-3.1-pro-low", "conversation_id": w1}),
                    },
                })
                s.step({
                    "step_index": 3, "state": "DONE", "step_type": "tool",
                    "tool_info": {
                        "name": "swarm",
                        "parameters": {"action": "add", "name": "grp", "brief": "Scan", "n": 2},
                        "output": json.dumps({"status": "added", "names": ["grp-1", "grp-2"], "model": "gemini-3.1-pro-low", "conversation_ids": [w2, w3]}),
                    },
                })
                s.tail_stop.set()

                self.assertEqual(s.subagents[w1]["itemId"], "agy:turn-1:2:tool")
                self.assertEqual(s.subagents[w2]["itemId"], "agy:turn-1:3:tool")
                self.assertEqual(s.subagents[w3]["itemId"], "agy:turn-1:3:tool")
                self.assertEqual(s.subagents[w1]["status"], "running")

                s.refresh_child_log(w1, s.subagents[w1]["logPath"])
                self.assertEqual(s.subagents[w1]["status"], "succeeded")
                self.assertEqual(s.subagents[w1]["detail"], "@coordinator audit complete")

                s.peer = types.SimpleNamespace(closed=False, pending={})
                s.update(connected=True, running=False, historyReady=True)
                s.current_submission = None
                s.checkpoint(force=True)
                blocker = s.release_blocker()
                self.assertIsNotNone(blocker)
                if lang == "zh":
                    self.assertIn("子代理", blocker)
                else:
                    self.assertIn("Subagent", blocker)
                s.current_submission = "turn-1"

                s.step({
                    "step_index": 4, "state": "DONE", "step_type": "tool",
                    "tool_info": {
                        "name": "post_message",
                        "parameters": {"text": "@solo second pass"},
                        "output": '{"status":"posted","seq":3}',
                    },
                })
                self.assertEqual(s.subagents[w1]["status"], "running")
                self.assertEqual(s.subagents[w1]["nativeStatus"], "revived")

                s.refresh_child_log(w1, s.subagents[w1]["logPath"])
                self.assertEqual(s.subagents[w1]["status"], "running")
                self.assertEqual(s.subagents[w1]["nativeStatus"], "revived")

                s.step({
                    "step_index": 5, "state": "DONE", "step_type": "tool",
                    "tool_info": {
                        "name": "swarm",
                        "parameters": {"action": "status"},
                        "output": json.dumps({"title": "S", "members": [
                            {"name": "coordinator", "role": "coordinator", "model": "m0", "state": "working", "killed": False},
                            {"name": "solo", "role": "worker", "model": "m1", "state": "waiting", "killed": False},
                            {"name": "grp-1", "role": "worker", "model": "m1", "state": "stopped", "killed": False},
                            {"name": "grp-2", "role": "worker", "model": "m1", "state": "killed", "killed": True},
                        ]}),
                    },
                })
                self.assertEqual(s.subagents[w1]["status"], "succeeded")
                self.assertEqual(s.subagents[w2]["status"], "succeeded")
                self.assertEqual(s.subagents[w2]["nativeStatus"], "swarm:stopped")
                self.assertEqual(s.subagents[w3]["status"], "closed")
                self.assertEqual(s.subagents[w3]["nativeStatus"], "swarm:killed")

                s.refresh_child_log(w2, s.subagents[w2]["logPath"])
                self.assertEqual(s.subagents[w2]["status"], "succeeded")
                self.assertEqual(s.subagents[w2]["nativeStatus"], "swarm:stopped")

                s.step({
                    "step_index": 6, "state": "DONE", "step_type": "tool",
                    "tool_info": {
                        "name": "swarm",
                        "parameters": {"action": "model", "name": "grp-1", "model": "gemini-3.8-flash-low"},
                        "output": '{"status":"switched","name":"grp-1","model":"gemini-3.8-flash-low"}',
                    },
                })
                self.assertEqual(s.subagents[w2]["model"], "gemini-3.8-flash-low")
                s.current_submission = None
                s.checkpoint(force=True)
                self.assertIsNone(s.release_blocker())

    def test_parent_log_backfill_anchoring_and_restore_projection_no_false_revive(self):
        parent_id = "p-cli-restore"
        w1 = "w-cli-r1"
        w2 = "w-cli-r2"
        with patch.dict(os.environ, {"GEMINI_HOME": str(self.gemini_home)}):
            s = self._make_session(parent_id)
            s.stream_high = 0
            s.stream_turn = "turn-1"
            s.last_frame = time.monotonic() - 10.0

            compact_t1 = [
                {"step_index": 0, "type": "USER_INPUT", "status": "DONE", "content": "start"},
                {"step_index": 1, "type": "PLANNER_RESPONSE", "status": "DONE", "truncated_fields": ["tool_calls"], "tool_calls": [
                    {"name": "swarm", "args": {"action": "add", "name": "duo", "brief": "TRUNCATED"}},
                ]},
                {"step_index": 2, "type": "GENERIC", "status": "DONE", "content": json.dumps({"status": "added", "names": ["duo-1", "duo-2"], "conversation_ids": [w1, w2]})},
            ]
            full_t1 = copy.deepcopy(compact_t1)
            full_t1[1] = {
                "step_index": 1, "type": "PLANNER_RESPONSE", "status": "DONE",
                "tool_calls": [
                    {"name": "swarm", "args": {"action": "add", "name": "duo", "brief": "Inspect CLI", "tasks": json.dumps(["a.py", "b.py"])}},
                ],
            }
            self._write_steps(parent_id, compact_t1, full_steps=full_t1)
            w1_t1 = [
                {"step_index": 0, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                    {"name": "post_message", "args": {"text": "@coordinator a.py clean"}},
                    {"name": "swarm_wait", "args": {"since": "2"}},
                ]},
                {"step_index": 1, "type": "GENERIC", "status": "DONE", "content": '{"status":"posted","seq":3}'},
                {"step_index": 2, "type": "GENERIC", "status": "DONE", "content": "Waiting"},
            ]
            w2_t1 = [
                {"step_index": 0, "type": "PLANNER_RESPONSE", "status": "DONE", "content": "Finished b.py"},
            ]
            self._write_steps(w1, w1_t1)
            self._write_steps(w2, w2_t1)

            s.refresh_parent_log()
            self.assertEqual(s.subagents[w1]["task"], "a.py\n\nInspect CLI")
            self.assertEqual(s.subagents[w2]["task"], "b.py\n\nInspect CLI")
            self.assertEqual(s.subagents[w1]["itemId"], "agy:turn-1:2:tool")
            self.assertEqual(s.subagents[w2]["itemId"], "agy:turn-1:2:tool")

            s.refresh_child_log(w1, s.subagents[w1]["logPath"])
            s.refresh_child_log(w2, s.subagents[w2]["logPath"])
            self.assertEqual(s.subagents[w1]["status"], "succeeded")
            self.assertEqual(s.subagents[w2]["status"], "succeeded")

            compact_t2 = compact_t1 + [
                {"step_index": 3, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                    {"name": "post_message", "args": {"text": "@duo go"}},
                ]},
                {"step_index": 4, "type": "GENERIC", "status": "DONE", "content": '{"status":"posted","seq":2}'},
            ]
            full_t2 = full_t1 + compact_t2[3:]
            self._write_steps(parent_id, compact_t2, full_steps=full_t2)
            s.refresh_parent_log()
            self.assertEqual(s.subagents[w1]["status"], "running")
            self.assertEqual(s.subagents[w1]["nativeStatus"], "revived")

            self._write_steps(w1, w1_t1 + [
                {"step_index": 3, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": "<SYSTEM_MESSAGE>[Message] sender=coordinator content=@duo go</SYSTEM_MESSAGE>"},
                {"step_index": 4, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [{"name": "swarm_wait", "args": {"since": "3"}}]},
                {"step_index": 5, "type": "GENERIC", "status": "DONE", "content": "Waiting"},
            ])
            self._write_steps(w2, w2_t1 + [
                {"step_index": 1, "type": "PLANNER_RESPONSE", "status": "DONE", "content": "Also green"},
            ])
            s.refresh_child_log(w1, s.subagents[w1]["logPath"])
            s.refresh_child_log(w2, s.subagents[w2]["logPath"])
            self.assertEqual(s.subagents[w1]["status"], "succeeded")
            self.assertEqual(s.subagents[w2]["status"], "succeeded")

            s.current_submission = None
            s.checkpoint(force=True)
            saved = copy.deepcopy(s.config["localProjection"])
            self.assertEqual(saved["nativeProtocol"], PROTOCOL)

            restored = self._make_session(parent_id, saved=saved)
            restored.restore_projection()
            restored.refresh_parent_log()
            self.assertEqual(restored.subagents[w1]["name"], "duo-1")
            self.assertEqual(restored.subagents[w1]["status"], "succeeded")
            self.assertEqual(restored.subagents[w2]["status"], "succeeded")

            self._write_steps(parent_id, compact_t2 + [
                {"step_index": 5, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                    {"name": "post_message", "args": {"text": "@duo followup check"}},
                ]},
                {"step_index": 6, "type": "GENERIC", "status": "DONE", "content": '{"status":"posted","seq":4}'},
            ], full_steps=full_t2 + [
                {"step_index": 5, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                    {"name": "post_message", "args": {"text": "@duo followup check"}},
                ]},
                {"step_index": 6, "type": "GENERIC", "status": "DONE", "content": '{"status":"posted","seq":4}'},
            ])
            restored.refresh_parent_log()
            self.assertEqual(restored.subagents[w1]["status"], "running")
            self.assertEqual(restored.subagents[w1]["nativeStatus"], "revived")
            self.assertEqual(restored.subagents[w2]["status"], "running")
            self.assertEqual(restored.subagents[w2]["nativeStatus"], "revived")

    def test_finish_observer_close_preserves_projection_and_joins_tail(self):
        parent_id = "p-close-guard"
        w1 = "w-close-guard"
        with patch.dict(os.environ, {"GEMINI_HOME": str(self.gemini_home)}):
            s = self._make_session(parent_id)
            s.row({"id": "turn-1", "role": "user", "text": "keep me", "turnId": "turn-1", "status": "completed"})
            self._write_steps(w1, [{"step_index": 0, "type": "PLANNER_RESPONSE", "status": "DONE", "content": "done"}])
            s.step({
                "step_index": 2, "state": "DONE", "step_type": "tool",
                "tool_info": {
                    "name": "swarm",
                    "parameters": {"action": "add", "name": "worker", "brief": "Scan"},
                    "output": json.dumps({"status": "added", "name": "worker", "conversation_id": w1}),
                },
            })
            s.checkpoint(force=True)
            expected_msgs = copy.deepcopy(s.config["localProjection"]["messages"])
            self.assertTrue(expected_msgs)

            s.last_checkpoint = time.monotonic()
            s.checkpoint()
            pending_gen = s.checkpoint_generation
            self.assertIsNotNone(s.checkpoint_timer)

            entered = threading.Event()
            release = threading.Event()
            orig_refresh = s.refresh_parent_log

            def slow_refresh():
                entered.set()
                release.wait(2.0)
                orig_refresh()

            with patch.object(s, "refresh_parent_log", side_effect=slow_refresh):
                s.restart_child_tail()
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
            s.flush_projection(pending_gen)
            s.refresh_child_log(w1, str(self._transcript_path(w1)))
            s.refresh_parent_log()
            self.assertEqual(s.config["localProjection"]["messages"], expected_msgs)
            self.assertEqual(s.rows, {})
            self.assertEqual(s.subagents, {})

    def test_swarm_mentions_matches_literal_names_groups_and_ignores_emails(self):
        names, mentions_all = swarm_mentions("ping @worker. mail user@worker.com @grp-2.")
        self.assertEqual(names, ["worker", "grp-2"])
        self.assertFalse(mentions_all)
        names_all, is_all = swarm_mentions("broadcast (@all).")
        self.assertEqual(names_all, ["all"])
        self.assertTrue(is_all)

    def test_child_transcript_background_tasks_conditional_timers_and_interrupted_tools(self):
        parent_id = "p-child-bg"
        w1 = "w-bg-1"
        steps = [
            {"step_index": 0, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                {"name": "run_command", "args": {"CommandLine": '"pytest -q"'}},
                {"name": "schedule", "args": {"DurationSeconds": "15", "Prompt": '"check"', "TimerCondition": '"any"'}},
            ]},
            {"step_index": 1, "type": "GENERIC", "status": "RUNNING", "content": f"Created At: 2026-11-15T00:00:00Z\nTool is running as a background task with task id: {w1}/task-1\nTask logs are available at: file:///x"},
            {"step_index": 2, "type": "GENERIC", "status": "RUNNING", "content": f"Created At: 2026-11-15T00:00:01Z\nTool is running as a background task with task id: {w1}/task-2\nTask Description: Timer: 15s"},
            {"step_index": 3, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": f'<SYSTEM_MESSAGE>\n[Message] timestamp=2026-11-15T00:00:02Z sender={w1}/task-1 priority=MESSAGE_PRIORITY_HIGH content=Task id "{w1}/task-1" finished with result:\nCommand exited with code 0.\nok\n</SYSTEM_MESSAGE>'},
            {"step_index": 4, "type": "SYSTEM_MESSAGE", "status": "DONE", "content": "<SYSTEM_MESSAGE>\n[Message] timestamp=2026-11-15T00:00:03Z sender=coordinator priority=MESSAGE_PRIORITY_HIGH content=@w-bg wake up early\n</SYSTEM_MESSAGE>"},
            {"step_index": 5, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                {"name": "read_file", "args": {"Path": '"a.py"'}},
                {"name": "read_file", "args": {"Path": '"b.py"'}},
                {"name": "read_file", "args": {"Path": '"c.py"'}},
            ]},
            {"step_index": 6, "type": "GENERIC", "status": "DONE", "content": "body a"},
            {"step_index": 7, "type": "ERROR_MESSAGE", "status": "DONE", "content": "stream interrupted"},
            {"step_index": 8, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                {"name": "read_board", "args": {"since": "37"}},
                {"name": "post_message", "args": {"text": '"@ver-backend done"'}},
                {"name": "swarm_wait", "args": {"since": "37"}},
            ]},
            {"step_index": 9, "type": "GENERIC", "status": "DONE", "content": "board msgs"},
            {"step_index": 10, "type": "GENERIC", "status": "DONE", "content": '{"status":"posted","seq":38}'},
            {"step_index": 11, "type": "GENERIC", "status": "DONE", "content": "Waiting for message"},
        ]
        projected = project_child_transcript(w1, steps)
        tools = [m for m in projected if m.get("role") == "tool" and m.get("kind") == "tool"]
        self.assertEqual([t["toolName"] for t in tools], [
            "run_command", "schedule", "read_file", "read_file", "read_file", "read_board", "post_message", "swarm_wait",
        ])
        self.assertEqual(tools[0]["status"], "completed")
        self.assertIn("ok", tools[0]["outcome"])
        self.assertEqual(tools[1]["status"], "completed")
        self.assertEqual(tools[2]["status"], "completed")
        self.assertEqual(tools[2]["outcome"], "body a")
        self.assertEqual(tools[3]["status"], "failed")
        self.assertEqual(tools[4]["status"], "failed")
        self.assertEqual([(t["toolName"], t["status"], t["outcome"]) for t in tools[5:]], [
            ("read_board", "completed", "board msgs"),
            ("post_message", "completed", '{"status":"posted","seq":38}'),
            ("swarm_wait", "completed", "Waiting for message"),
        ])

        steps_with_live_cron = steps + [
            {"step_index": 12, "type": "PLANNER_RESPONSE", "status": "DONE", "tool_calls": [
                {"name": "schedule", "args": {"CronExpression": '"*/5 * * * *"', "Prompt": '"poll"'}},
            ]},
            {"step_index": 13, "type": "GENERIC", "status": "RUNNING", "content": f"Created At: 2026-11-15T00:00:04Z\nTool is running as a background task with task id: {w1}/cron-9\nTask logs are available at: file:///y"},
        ]
        with patch.dict(os.environ, {"GEMINI_HOME": str(self.gemini_home)}):
            s = self._make_session(parent_id)
            self._write_steps(w1, steps_with_live_cron)
            s.note_swarm_tool("x", "turn-1", {"action": "add", "name": "rev-cli", "brief": "Scan"}, json.dumps({"status": "added", "name": "rev-cli", "conversation_id": w1}))
            s.refresh_child_log(w1, str(self._transcript_path(w1)))
            self.assertEqual(s.subagents[w1]["messages"][-1]["status"], "running")
            s.note_swarm_tool("x2", "turn-1", {"action": "kill", "name": "rev-cli"}, json.dumps({"status": "killed", "name": "rev-cli"}))
            self.assertEqual(s.subagents[w1]["status"], "closed")
            self.assertFalse(any(m.get("status") == "running" for m in s.subagents[w1]["messages"]))
            self.assertEqual(s.subagents[w1]["messages"][-1]["status"], "cancelled")


if __name__ == "__main__":
    unittest.main(verbosity=2)

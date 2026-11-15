#!/usr/bin/env python3
import copy
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.claude import ClaudeSession
from luma_cli.claude_history import _build_conversation_chain
from luma_cli.transport import Peer

NATIVE = '11111111-1111-4111-8111-111111111111'
NOTIFICATION = ('<task-notification>\n<task-id>bmt6sor2r</task-id>\n'
                '<summary>Monitor event: "8个审计子代理每4分钟心跳"</summary>\n'
                '<event>audit-heartbeat elapsed=4m</event>\n</task-notification>')

def session():
    s = ClaudeSession({'cli': 'Claude Code', 'cwd': '/tmp', 'nativeId': NATIVE,
                       'receipts': {}}, lambda: None)
    s.current_submission = None
    s.stream_id = None
    s.stream_blocks = {}
    s.finalized_blocks = set()
    s.usage_message = {}
    s.usage_models = {}
    s.usage_rows = []
    return s

def entry(uid, role, content, parent=None, **extra):
    return {'type': role, 'uuid': uid, 'parentUuid': parent, 'sessionId': NATIVE,
            'session_id': NATIVE, 'message': {'role': role, 'content': content}, **extra}

def history(s, entries):
    raw = '\n'.join(json.dumps(e, ensure_ascii=False) for e in entries) + '\n'
    with tempfile.TemporaryDirectory(prefix='luma-claude-provenance-') as tmp:
        path = Path(tmp) / (NATIVE + '.jsonl')
        path.write_text(raw, encoding='utf-8')
        s.history_path = lambda: path
        s.load_history()
        assert path.read_text(encoding='utf-8') == raw

class ClaudeUserProjection(unittest.TestCase):
    def test_exact_monitor_shape_never_becomes_any_conversation_bubble(self):
        notification = entry('monitor', 'user', NOTIFICATION,
                             origin={'kind': 'task-notification'}, isSidechain=False)
        for path in ('stream', 'history'):
            with self.subTest(path=path):
                s = session()
                if path == 'stream': s.event(notification)
                else: history(s, [notification])
                self.assertEqual(s.snapshot()['messages'], [])

    def test_human_xml_and_transport_looking_text_are_preserved_byte_for_byte(self):
        for body in (NOTIFICATION, 'This session is being continued from a previous conversation.',
                     '{"origin":{"kind":"peer"},"isMeta":true}', '/compact\n' + NOTIFICATION):
            for path in ('stream', 'history'):
                with self.subTest(body=body, path=path):
                    s = session(); e = entry('human', 'user', body)
                    if path == 'stream': s.event(e)
                    else: history(s, [e])
                    self.assertEqual([(r['role'], r['text']) for r in s.rows.values()], [('user', body)])

    def test_absent_legacy_and_explicit_human_origin_keep_existing_contract(self):
        for metadata in ({}, {'origin': None}, {'origin': {'kind': 'human'}},
                         {'origin': 'human'}, {'origin': {}}):
            for path in ('stream', 'history'):
                with self.subTest(metadata=metadata, path=path):
                    s = session(); e = entry('input', 'user', 'original', **metadata)
                    if path == 'stream': s.event(e)
                    else: history(s, [e])
                    self.assertEqual(s.rows['input:0']['text'], 'original')
                    self.assertEqual(s.rows['input:0']['role'], 'user')

    def test_explicit_nonhuman_and_unknown_origins_cannot_claim_human_identity(self):
        for kind in ('task-notification', 'channel', 'peer', 'coordinator', 'observer',
                     'auto-continuation', 'observer-activity', 'unclassified', 'future-kind'):
            for path in ('stream', 'history'):
                with self.subTest(kind=kind, path=path):
                    s = session(); e = entry('source', 'user', 'source body', origin={'kind': kind})
                    if path == 'stream': s.event(e)
                    else: history(s, [e])
                    self.assertEqual(s.rows, {})

    def test_stream_and_history_apply_the_same_internal_frame_filter(self):
        for flags in ({'isMeta': True}, {'isSidechain': True}, {'teamName': 'native-team'},
                      {'parent_tool_use_id': 'launch'}):
            for role in ('user', 'assistant'):
                for path in ('stream', 'history'):
                    with self.subTest(flags=flags, role=role, path=path):
                        s = session(); e = entry('internal', role, 'internal body', **flags)
                        if path == 'stream': s.event(e)
                        else: history(s, [e])
                        self.assertEqual(s.rows, {})

    def test_compact_summary_remains_in_parent_chain_without_impersonating_user(self):
        entries = [entry('old', 'user', 'before compact'),
                   {'uuid': 'boundary', 'type': 'system', 'subtype': 'compact_boundary',
                    'parentUuid': None, 'logicalParentUuid': 'old'},
                   entry('summary', 'user', 'native generated compact context', 'boundary', isCompactSummary=True),
                   entry('answer', 'assistant', [{'type': 'text', 'text': 'continued'}], 'summary')]
        self.assertEqual([e['uuid'] for e in _build_conversation_chain(entries)], ['boundary', 'summary', 'answer'])
        s = session(); history(s, entries)
        self.assertEqual([(r['role'], r['text']) for r in s.rows.values()], [('assistant', 'continued')])
        streamed = session(); streamed.event(entries[2])
        self.assertEqual(streamed.rows, {})

    def test_notification_is_traversed_without_dropping_later_answer_or_real_user(self):
        entries = [entry('human', 'user', 'audit'),
                   entry('notice', 'user', NOTIFICATION, 'human', origin={'kind': 'task-notification'}),
                   entry('answer', 'assistant', [{'type': 'text', 'text': 'still working'}], 'notice'),
                   entry('literal', 'user', NOTIFICATION, 'answer', origin={'kind': 'human'})]
        s = session(); history(s, entries)
        self.assertEqual([r['id'] for r in s.rows.values()], ['human:0', 'answer:0', 'literal:0'])
        self.assertEqual(s.rows['literal:0']['text'], NOTIFICATION)
        history(s, entries)
        self.assertEqual(len(s.rows), 3)

    def test_history_reload_removes_an_existing_wrong_same_uuid_user_bubble(self):
        entries = [entry('human', 'user', 'audit'),
                   entry('monitor', 'user', NOTIFICATION, 'human', origin={'kind': 'task-notification'}),
                   entry('answer', 'assistant', [{'type': 'text', 'text': 'still working'}], 'monitor')]
        s = session()
        s.row({'id': 'monitor:0', 'role': 'user', 'text': NOTIFICATION, 'status': 'completed'})
        history(s, entries)
        self.assertNotIn('monitor:0', s.rows)
        self.assertEqual(list(s.rows), ['human:0', 'answer:0'])

    def test_tool_result_keeps_call_identity_input_and_outcome(self):
        entries = [entry('human', 'user', 'read'),
                   entry('call', 'assistant', [{'type': 'tool_use', 'id': 'read-tool', 'name': 'Read',
                                                'input': {'file_path': '/tmp/fixture'}}], 'human'),
                   entry('result', 'user', [{'type': 'tool_result', 'tool_use_id': 'read-tool',
                                             'content': 'native result'}], 'call')]
        for path in ('stream', 'history'):
            with self.subTest(path=path):
                s = session()
                if path == 'stream':
                    for e in entries: s.event(e)
                else: history(s, entries)
                tool = s.rows['tool-read-tool']
                self.assertEqual(tool['toolName'], 'Read')
                self.assertEqual(json.loads(tool['input']), {'file_path': '/tmp/fixture'})
                self.assertEqual((tool['status'], tool['outcome']), ('completed', 'native result'))
                self.assertEqual([r['id'] for r in s.rows.values() if r['role'] == 'user'], ['human:0'])

    def test_nonhuman_replay_cannot_ack_a_caller_receipt_even_with_matching_uuid(self):
        for flags in ({'origin': {'kind': 'task-notification'}}, {'isMeta': True}, {'isCompactSummary': True}):
            with self.subTest(flags=flags):
                s = session(); s.config['receipts']['caller'] = 'unknown'
                s.event(entry('caller', 'user', NOTIFICATION, **flags))
                self.assertEqual(s.config['receipts']['caller'], 'unknown')
                self.assertEqual(s.config.get('consumedSubmissions', {}), {})

    def test_background_result_cannot_ack_idle_fail_or_settle_the_foreground(self):
        for kind in ('task-notification', 'peer', 'future-kind'):
            for failed in (False, True):
                with self.subTest(kind=kind, failed=failed):
                    s = session(); s.current_submission = 'caller'
                    s.config['receipts']['caller'] = 'unknown'
                    s.update(running=True, error='foreground diagnostic')
                    s.row({'id': 'foreground-tool', 'role': 'tool', 'text': 'Bash', 'status': 'running'})
                    before = copy.deepcopy(s.rows)
                    loads = []; s.load_history = lambda: loads.append(True)
                    s.event({'type': 'result', 'session_id': NATIVE, 'origin': {'kind': kind},
                             'is_error': failed, 'errors': ['background failure'] if failed else [],
                             'usage': {}, 'modelUsage': {}})
                    self.assertTrue(s.data['running'])
                    self.assertEqual(s.current_submission, 'caller')
                    self.assertEqual(s.config['receipts']['caller'], 'unknown')
                    self.assertEqual(s.config.get('consumedSubmissions', {}), {})
                    self.assertEqual(s.data['error'], 'foreground diagnostic')
                    self.assertEqual(s.rows, before)
                    self.assertEqual(loads, [])

    def test_foreground_result_still_settles_legacy_and_explicit_human_turns(self):
        for extra in ({}, {'origin': {'kind': 'human'}}):
            with self.subTest(extra=extra):
                s = session(); s.current_submission = 'caller'; s.config['receipts']['caller'] = 'unknown'
                s.update(running=True); s.load_history = lambda: None
                s.event({'type': 'result', 'session_id': NATIVE, 'is_error': False, **extra})
                self.assertFalse(s.data['running'])
                self.assertIsNone(s.current_submission)
                self.assertEqual(s.config['receipts']['caller'], 'accepted')
                self.assertTrue(s.config['consumedSubmissions']['caller'])

    def test_structured_child_lifecycle_and_monitor_identity_stay_native(self):
        s = session()
        for task, kind in (('child', 'local_agent'), ('monitor', 'monitor_mcp')):
            s.event({'type': 'system', 'subtype': 'task_started', 'task_id': task,
                     'task_type': kind, 'description': 'native task'})
        s.event(entry('monitor-text', 'user', NOTIFICATION, origin={'kind': 'task-notification'}))
        s.event({'type': 'system', 'subtype': 'task_notification', 'task_id': 'child',
                 'status': 'completed', 'summary': 'native completion'})
        self.assertEqual(set(s.subagents), {'child'})
        self.assertEqual(s.subagents['child']['status'], 'succeeded')
        self.assertEqual(s.rows, {})

    def test_background_results_do_not_replace_structured_child_lifecycle(self):
        s = session(); s.current_submission = 'caller'; s.config['receipts']['caller'] = 'unknown'
        s.update(running=True)
        s.event({'type': 'system', 'subtype': 'task_started', 'task_id': 'child',
                 'task_type': 'local_agent', 'description': 'native child'})
        s.event({'type': 'result', 'session_id': NATIVE, 'origin': {'kind': 'task-notification'},
                 'is_error': True, 'errors': ['injected turn failed']})
        self.assertEqual(s.subagents['child']['status'], 'running')
        s.event({'type': 'system', 'subtype': 'task_updated', 'task_id': 'child',
                 'patch': {'status': 'completed'}})
        self.assertEqual(s.subagents['child']['status'], 'succeeded')
        self.assertTrue(s.data['running'])
        self.assertEqual(s.config['receipts']['caller'], 'unknown')

    def test_transport_jsonl_replay_uses_the_same_production_projection(self):
        s = session(); s.current_submission = 'caller'; s.config['receipts']['caller'] = 'unknown'
        s.update(running=True)
        complete = threading.Event()
        def accept(e):
            s.event(e)
            if e.get('type') == 'result': complete.set()
        frames = [entry('monitor', 'user', NOTIFICATION, origin={'kind': 'task-notification'}),
                  entry('human-xml', 'user', NOTIFICATION, origin={'kind': 'human'}),
                  {'type': 'result', 'session_id': NATIVE, 'origin': {'kind': 'task-notification'}, 'is_error': False}]
        script = ('import json,sys\n'
                  'for frame in json.loads(sys.stdin.readline())["frames"]:\n'
                  ' print(json.dumps(frame),flush=True)\n'
                  'sys.stdin.readline()\n')
        with tempfile.TemporaryDirectory(prefix='luma-claude-wire-') as tmp:
            peer = Peer([sys.executable, '-B', '-c', script], tmp, accept, s.exited,
                        env={'HOME': tmp, 'PATH': '/usr/bin:/bin'})
            peer.start_read()
            try:
                peer.send({'frames': frames})
                self.assertTrue(complete.wait(3))
                self.assertEqual(list(s.rows), ['human-xml:0'])
                self.assertEqual(s.rows['human-xml:0']['text'], NOTIFICATION)
                self.assertTrue(s.data['running'])
                self.assertEqual(s.config['receipts']['caller'], 'unknown')
            finally:
                peer.close()

    def test_result_history_reload_keeps_unflushed_accepted_user_without_resurrecting_compacted(self):
        import os
        from unittest.mock import patch
        with tempfile.TemporaryDirectory(prefix='luma-claude-flush-') as tmp:
            s = session()
            s.config['cwd'] = tmp
            with patch.dict(os.environ, {'CLAUDE_CONFIG_DIR': tmp}):
                hp = s.history_path()
                hp.parent.mkdir(parents=True, exist_ok=True)
                entries = [
                    entry('old-compacted', 'user', 'very old'),
                    {'uuid': 'b1', 'type': 'system', 'subtype': 'compact_boundary', 'parentUuid': None, 'logicalParentUuid': 'old-compacted'},
                    entry('u1', 'user', 'first turn', 'b1'),
                    entry('a1', 'assistant', [{'type': 'text', 'text': 'first answer'}], 'u1'),
                ]
                hp.write_text(''.join(json.dumps(x) + '\n' for x in entries), encoding='utf-8')
                s.config['receipts'] = {'old-compacted': 'accepted', 'u1': 'accepted', 'u2': 'unknown'}
                s.peer = type('P', (), {'send': lambda *_: None})()
                s.prompt({'submissionId': 'u2', 'text': 'second turn'})
                s.event({'type': 'result', 'session_id': NATIVE, 'is_error': False})
                self.assertEqual(s.config['receipts']['u2'], 'accepted')
                self.assertIn('u2:0', s.rows)
                self.assertEqual(s.rows['u2:0']['text'], 'second turn')
                self.assertNotIn('old-compacted:0', s.rows)

    def test_background_task_and_autonomous_wakeup_block_idle_release(self):
        from luma_cli.i18n import tr
        with tempfile.TemporaryDirectory() as tmp:
            hp = Path(tmp) / f'{NATIVE}.jsonl'
            hp.write_text(json.dumps(entry('u1', 'user', 'run long tests')) + '\n', encoding='utf-8')
            s = session()
            s.history_path = lambda: hp
            s.peer = type('P', (), {'closed': False, 'pending': {}, 'send': lambda *_: None})()
            s.load_history()
            s.update(connected=True, connecting=False)
            self.assertIsNone(s.release_blocker())
            a1 = entry('a1', 'assistant', [{'type': 'tool_use', 'id': 'tu-bash', 'name': 'Bash', 'input': {'command': 'sleep 60', 'run_in_background': True}}], 'u1')
            tr1 = entry('tr1', 'user', [{'type': 'tool_result', 'tool_use_id': 'tu-bash', 'is_error': False, 'content': 'Running in background'}], 'a1')
            s.event(a1)
            s.event({'type': 'system', 'subtype': 'task_started', 'task_id': 'b1', 'tool_use_id': 'tu-bash', 'task_type': 'local_bash', 'description': 'long test'})
            s.event(tr1)
            self.assertEqual(s.rows['tool-tu-bash']['status'], 'running')
            hp.write_text(''.join(json.dumps(x) + '\n' for x in [entry('u1', 'user', 'run long tests'), a1, tr1]), encoding='utf-8')
            s.load_history()
            self.assertEqual(s.rows['tool-tu-bash']['status'], 'running')
            self.assertEqual(s.release_blocker(), tr('后台任务尚未确认结束', 'Background task has not been confirmed finished'))
            s.event({'type': 'system', 'subtype': 'task_notification', 'task_id': 'b1', 'tool_use_id': 'tu-bash', 'status': 'completed', 'summary': 'all passed'})
            self.assertEqual(s.rows['tool-tu-bash']['status'], 'completed')
            self.assertEqual(s.release_blocker(), tr('后台唤醒回合尚未结算', 'Background wake-up turn has not settled'))
            s.event({'type': 'stream_event', 'event': {'type': 'message_start', 'message': {'id': 'm-bg'}}})
            self.assertTrue(s.data['running'])
            self.assertEqual(s.release_blocker(), tr('原生回合尚未结算', 'Native turn has not settled'))
            s.event({'type': 'result', 'session_id': NATIVE, 'is_error': False})
            self.assertFalse(s.data['running'])
            self.assertIsNone(s.release_blocker())

    def test_fresh_reconnect_history_restores_active_background_task_and_low_monotonic_unblocked(self):
        from unittest.mock import patch
        from luma_cli.i18n import tr
        with tempfile.TemporaryDirectory() as tmp:
            hp = Path(tmp) / f'{NATIVE}.jsonl'
            u1 = entry('u1', 'user', 'run bg')
            a1 = entry('a1', 'assistant', [{'type': 'tool_use', 'id': 'tu-bg', 'name': 'Bash', 'input': {'command': 'sleep 60', 'run_in_background': True}}], 'u1')
            ts1 = {'type': 'system', 'subtype': 'task_started', 'task_id': 'bg1', 'tool_use_id': 'tu-bg', 'task_type': 'local_bash', 'description': 'bg job'}
            tr1 = entry('tr1', 'user', [{'type': 'tool_result', 'tool_use_id': 'tu-bg', 'is_error': False, 'content': 'Running in background'}], 'a1')
            hp.write_text(''.join(json.dumps(x) + '\n' for x in [u1, a1, ts1, tr1]), encoding='utf-8')
            s = session()
            s.history_path = lambda: hp
            s.peer = type('P', (), {'closed': False, 'pending': {}, 'send': lambda *_: None})()
            s.load_history()
            s.update(connected=True, connecting=False)
            self.assertEqual(s.background_tasks, {'bg1': 'tool-tu-bg'})
            self.assertEqual(s.rows['tool-tu-bg']['status'], 'running')
            self.assertEqual(s.release_blocker(), tr('后台任务尚未确认结束', 'Background task has not been confirmed finished'))
            tn1 = {'type': 'system', 'subtype': 'task_notification', 'task_id': 'bg1', 'tool_use_id': 'tu-bg', 'status': 'completed', 'summary': 'finished'}
            hp.write_text(''.join(json.dumps(x) + '\n' for x in [u1, a1, ts1, tr1, tn1]), encoding='utf-8')
            with patch('luma_cli.claude.time.monotonic', return_value=1.0):
                s2 = session()
                s2.history_path = lambda: hp
                s2.peer = type('P', (), {'closed': False, 'pending': {}, 'send': lambda *_: None})()
                s2.load_history()
                s2.update(connected=True, connecting=False)
                self.assertEqual(s2.background_tasks, {})
                self.assertEqual(s2.rows['tool-tu-bg']['status'], 'completed')
                self.assertIsNone(s2.release_blocker())

    def test_restore_unflushed_accepted_rows_preserves_images(self):
        with tempfile.TemporaryDirectory() as tmp:
            hp = Path(tmp) / f'{NATIVE}.jsonl'
            s = session()
            s.history_path = lambda: hp
            s.config.setdefault('receipts', {})['u-img'] = 'accepted'
            s.config.setdefault('deliveries', {})['u-img'] = {'state': 'accepted', 'payload': {'text': 'check image', 'images': ['/tmp/sample.png']}}
            s.load_history()
            row = s.rows['u-img:0']
            self.assertEqual(row['text'], 'check image')
            self.assertEqual(row['media'], [{'source': '/tmp/sample.png', 'mimeType': 'image/png'}])

if __name__ == '__main__':
    unittest.main(verbosity=2)

#!/usr/bin/env python3
import copy
import fcntl
import io
import json
import os
import pathlib
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

sys.dont_write_bytecode = True
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[3] / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.kimi import KimiSession
from luma_cli.kimi_web import KimiWebSession
from luma_cli.kimi_web_transport import (
    KimiWebGateway,
    KimiWebIncompatible,
    KimiWebPeer,
    KimiWebRejected,
    KimiWebSocket,
    PROTOCOL,
    has_live_kimi_gateway,
    reap_idle_kimi_gateway,
    resolve_kimi_binary,
)
from luma_cli.metrics import kimi_web_metrics
from luma_cli.server import Manager
from luma_cli.transport import Rejected

def turn(ident='t0', prompt='Original <system-reminder>literal</system-reminder>', prompt_id='sent'):
    return {'kind': 'turn', 'turnId': ident, 'ordinal': 0, 'state': 'completed',
        'triggerPromptId': prompt_id, 'origin': {'kind': 'user'}, 'prompt': prompt,
        'steps': [{'state': 'completed', 'frames': [
            {'kind': 'text', 'frameId': ident + '.reply', 'role': 'assistant', 'text': 'Reply'}]}]}

class Api:
    def __init__(self, cwd):
        self.calls = []
        self.record = {'id': 'native', 'metadata': {'cwd': cwd}, 'title': 'Original title',
            'agent_config': {'model': 'fixture/model'}, 'main_turn_active': False, 'usage': {}}
        self.page = {'items': [turn()], 'has_more': False, 'interactions': [], 'attachments': [],
            'prompts': [{'promptId': 'sent', 'status': 'completed'}], 'agents': [{'agentId': 'main'}]}
        self.queue = {'active': None, 'queued': []}
        self.fail_send = False
        self.files = {}

    def upload_file(self, filename, mime_type, content):
        fid = f'f_{len(self.files) + 1}'
        self.files[fid] = (filename, mime_type, content)
        self.calls.append(('UPLOAD', '/files', {'name': filename, 'media_type': mime_type, 'size': len(content)}))
        return {'id': fid, 'name': filename, 'media_type': mime_type, 'size': len(content)}

    def request(self, method, path, body=None, binary=False):
        self.calls.append((method, path, copy.deepcopy(body)))
        if method == 'GET' and path.startswith('/files/') and binary:
            fid = path.removeprefix('/files/')
            _, mime_type, content = self.files[fid]
            return content, mime_type
        if method == 'DELETE' and path.startswith('/files/'):
            self.files.pop(path.removeprefix('/files/'), None)
            return {}
        if path == '/models':
            return {'items': [{'model': 'fixture/model', 'display_name': 'Fixture'}]}
        if path == '/config':
            return {'default_model': 'fixture/model'}
        if path == '/sessions':
            self.record['agent_config']['model'] = ''
            return copy.deepcopy(self.record)
        if path == '/sessions/native':
            return copy.deepcopy(self.record)
        if '/transcript?' in path:
            return copy.deepcopy(self.page)
        if method == 'GET' and path.endswith('/prompts'):
            return copy.deepcopy(self.queue)
        if method == 'POST' and path.endswith(('/prompts', '/steer')):
            if self.fail_send:
                raise TimeoutError('Response lost')
            return {'prompt_id': body['prompt_id'], 'user_message_id': body['prompt_id']}
        if '/interactions/' in path:
            return {'outcome': 'answered'}
        if path.endswith('/profile'):
            self.record['agent_config'].update(body.get('agent_config', {}))
            return copy.deepcopy(self.record)
        return {}

class Peer:
    reconnectable = True
    pending = {}
    def __init__(self, gateway, session, changed, exited):
        self.closed = False
        self.ready = threading.Event()
        self.ready.set()
        self.gateway, self.session = gateway, session
        self.proc = mock.Mock()
        self.proc.poll.return_value = None
    def start_read(self):
        pass
    def close(self):
        self.closed = True
        self.gateway.detach(self.session.config['id'])

class KimiWebTest(unittest.TestCase):
    def session(self, directory, native='native'):
        config = {'id': 'local', 'cli': 'Kimi Code', 'cwd': directory,
            'ownerDirectory': str(pathlib.Path(directory) / 'local.native'), 'nativeProtocol': PROTOCOL}
        if native:
            config['nativeId'] = native
        s = KimiWebSession(config, lambda: None)
        s.api = Api(directory)
        s.path = '/sessions/native'
        s.models = s.api.request('GET', '/models')['items']
        return s

    def test_official_transcript_keeps_literal_tags_and_ignores_injection_markers(self):
        with tempfile.TemporaryDirectory() as root:
            s = self.session(root)
            s.api.page['items'][0]['steps'][0]['frames'].insert(0,
                {'kind': 'thinking', 'frameId': 't0.think', 'text': 'Actual reasoning text'})
            s.api.page['items'].append({'kind': 'marker', 'marker': 'undo', 'payload': {
                'messages': [{'role': 'user', 'origin': {'kind': 'injection'},
                    'content': [{'type': 'text', 'text': '<system-reminder>hidden</system-reminder>'}]}]}})
            s.refresh()
            self.assertEqual([r['text'] for r in s.rows.values()],
                ['Original <system-reminder>literal</system-reminder>', 'Actual reasoning text', 'Reply'])
            self.assertEqual(s.rows['t0.think']['kind'], 'reasoning')
            self.assertEqual([p['label'] for p in s.data['permissions']],
                ['Always Ask', 'Ask When Needed', 'Never Ask'])
            before = copy.deepcopy(s.rows)
            s.refresh()
            self.assertEqual(s.rows, before)

    def test_authoritative_reset_removes_undone_history(self):
        with tempfile.TemporaryDirectory() as root:
            s = self.session(root)
            s.api.record['usage'] = {'input_tokens': 0, 'output_tokens': 0, 'cache_read_tokens': 0,
                'cache_creation_tokens': 0, 'total_cost_usd': 0, 'context_tokens': 0, 'context_limit': 0}
            s.refresh()
            self.assertEqual(s.data['metrics'], [])
            s.api.record['agent_config']['model'] = 'volcengine/kimi-k3.1-pro'
            s.models = [{'model': 'volcengine/kimi-k3.1-pro', 'display_name': 'Kimi K3.1 Pro', 'max_context_size': 1024000}]
            s.api.page['items'][0]['steps'][0]['usage'] = {
                'inputOther': 2000, 'output': 500, 'inputCacheRead': 8000, 'inputCacheCreation': 0}
            s.refresh()
            labels = {m['label']: m['value'] for m in s.data['metrics']}
            self.assertIn('10500 / 1024000', labels['上下文'])
            self.assertIn('8000 / 10000 · 80.0%', labels['缓存命中'])
            self.assertIn('CLI 累计估算 · USD', labels)
            s.api.page['items'] = []
            s.api.page['prompts'] = []
            s.refresh()
            self.assertEqual(s.rows, {})
            self.assertEqual(s.data['metrics'], [])

    def test_failed_turn_and_standalone_notices_are_visible_without_injection_markers(self):
        with tempfile.TemporaryDirectory() as root:
            s = self.session(root)
            failed = turn()
            failed.update(state='failed', error='Model not set', steps=[])
            s.api.page['items'] = [failed,
                {'kind': 'marker', 'markerId': 'same', 'marker': 'notice', 'payload': {'level': 'error', 'message': 'Model not set'}},
                {'kind': 'marker', 'markerId': 'warning', 'marker': 'notice', 'payload': {'level': 'warning', 'message': 'Fixture warning'}},
                {'kind': 'marker', 'markerId': 'other', 'marker': 'notice', 'payload': {'level': 'error', 'message': 'Other failure'}},
                {'kind': 'marker', 'markerId': 'internal', 'marker': 'undo', 'payload': {'messages': [{'content': 'Hidden injection'}]}}]
            s.refresh()
            self.assertEqual([r['text'] for r in s.rows.values()], [failed['prompt'], 'Model not set', 'Fixture warning', 'Other failure'])
            self.assertEqual(s.rows['t0:error']['status'], 'failed')
            self.assertEqual(s.rows['other']['status'], 'failed')

    def test_recover_existing_native_id_never_creates_and_preserves_ledger(self):
        with tempfile.TemporaryDirectory() as root:
            s = self.session(root)
            s.config['receipts'] = {'sent': 'unknown'}
            gateway = mock.Mock(api=s.api)
            with mock.patch('luma_cli.kimi_web.gateway_for', return_value=gateway), mock.patch('luma_cli.kimi_web.KimiWebPeer', Peer):
                s.connect()
                self.assertEqual(s.config['nativeId'], 'native')
                self.assertEqual(s.config['receipts']['sent'], 'accepted')
                self.assertTrue(s.config['consumedSubmissions']['sent'])
                self.assertFalse(any(m == 'POST' and p == '/sessions' for m, p, b in s.api.calls))
                s.stop_observation()
                self.assertFalse(s.refresh_thread.is_alive())

    def test_new_native_identity_is_published_in_bridge_snapshot(self):
        with tempfile.TemporaryDirectory() as root:
            s = self.session(root, native=None)
            gateway = mock.Mock(api=s.api)
            with mock.patch('luma_cli.kimi_web.gateway_for', return_value=gateway), mock.patch('luma_cli.kimi_web.KimiWebPeer', Peer):
                s.connect()
                try:
                    self.assertEqual(s.snapshot()['nativeId'], 'native')
                    self.assertEqual(s.config['nativeId'], 'native')
                    self.assertEqual(sum(m == 'POST' and p == '/sessions' for m, p, b in s.api.calls), 1)
                finally:
                    s.stop_observation()

    def test_new_session_applies_explicit_model_after_native_create_ignores_it(self):
        with tempfile.TemporaryDirectory() as root:
            s = self.session(root, native=None)
            s.config['model'] = 'fixture/chosen'
            gateway = mock.Mock(api=s.api)
            with mock.patch('luma_cli.kimi_web.gateway_for', return_value=gateway), mock.patch('luma_cli.kimi_web.KimiWebPeer', Peer):
                s.connect()
                try:
                    self.assertEqual(s.snapshot()['model'], 'fixture/chosen')
                    self.assertIn(('POST', '/sessions/native/profile', {'agent_config': {'model': 'fixture/chosen'}}), s.api.calls)
                finally:
                    s.stop_observation()

    def test_existing_native_model_is_not_overwritten_by_saved_selection(self):
        with tempfile.TemporaryDirectory() as root:
            s = self.session(root)
            s.config['model'] = 'fixture/stale'
            gateway = mock.Mock(api=s.api)
            with mock.patch('luma_cli.kimi_web.gateway_for', return_value=gateway), mock.patch('luma_cli.kimi_web.KimiWebPeer', Peer):
                s.connect()
                try:
                    self.assertEqual(s.snapshot()['model'], 'fixture/model')
                    self.assertFalse(any(m == 'POST' and p.endswith('/profile') for m, p, b in s.api.calls))
                finally:
                    s.stop_observation()

    def test_uncertain_send_is_not_retried_and_native_history_resolves_it(self):
        with tempfile.TemporaryDirectory() as root:
            s = self.session(root)
            s.update(connected=True, capabilities={'queue': True})
            s.api.fail_send = True
            payload = {'submissionId': 'sent', 'text': 'Input'}
            with self.assertRaises(TimeoutError):
                s.submit(payload)
            self.assertEqual(s.config['receipts']['sent'], 'unknown')
            self.assertEqual(s.submit(payload), {'receipt': 'unknown'})
            self.assertEqual(sum(p.endswith('/prompts') and m == 'POST' for m, p, b in s.api.calls), 1)
            s.refresh()
            self.assertEqual(s.submit(payload), {'receipt': 'accepted'})
            self.assertEqual(sum(p.endswith('/prompts') and m == 'POST' for m, p, b in s.api.calls), 1)

    def test_unknown_creation_is_never_repeated(self):
        with tempfile.TemporaryDirectory() as root:
            s = self.session(root, None)
            s.config['creationUnknown'] = True
            gateway = mock.Mock(api=s.api)
            with mock.patch('luma_cli.kimi_web.gateway_for', return_value=gateway):
                with self.assertRaises(Rejected):
                    s.connect()
            self.assertFalse(any(m == 'POST' for m, p, b in s.api.calls))

    def test_wrong_cwd_cannot_retarget_or_recreate_session(self):
        with tempfile.TemporaryDirectory() as root:
            s = self.session(root)
            s.api.record['metadata']['cwd'] = str(pathlib.Path(root) / 'other')
            with mock.patch('luma_cli.kimi_web.gateway_for', return_value=mock.Mock(api=s.api)):
                with self.assertRaises(Rejected):
                    s.connect()
            self.assertFalse(any(m == 'POST' for m, p, b in s.api.calls))

    def test_question_and_approval_use_native_choices_and_restore_pending_requests(self):
        with tempfile.TemporaryDirectory() as root:
            s = self.session(root)
            s.api.page['items'] = [{'kind': 'turn', 'turnId': 't0', 'ordinal': 0, 'state': 'running',
                'origin': {'kind': 'user'}, 'prompt': 'Run command',
                'steps': [{'state': 'running', 'frames': [{'kind': 'tool', 'frameId': 'f-bash',
                    'toolCallId': 'tc-bash', 'name': 'Bash', 'state': 'running',
                    'input': {'command': 'printf full-command-not-truncated', 'description': 'Print fixture'}}]}]}]
            s.refresh()
            approval = {'interactionId': 'allow', 'interactionKind': 'approval', 'state': 'pending',
                'toolCallId': 'tc-bash', 'request': {'toolName': 'Bash', 'action': 'Running: printf full…'}}
            question = {'interactionId': 'ask', 'interactionKind': 'question', 'state': 'pending',
                'request': {'questions': [{'id': 'q', 'question': 'Pick', 'multi_select': True,
                    'allow_other': True, 'options': [{'id': 'opt_0_0', 'label': 'First', 'description': 'Desc 1'}, {'id': 'opt_0_1', 'label': 'Second'}]}]}}
            s.project_requests({'interactions': [approval, question]})
            by_id = {r['id']: r for r in s.data['requests']}
            self.assertEqual(set(by_id), {'allow', 'ask'})
            self.assertEqual(json.loads(by_id['allow']['body'])['command'], 'printf full-command-not-truncated')
            self.assertEqual(s.rows['f-bash']['text'], 'Print fixture')
            self.assertEqual(by_id['ask']['title'], 'Answer')
            self.assertEqual(by_id['ask']['questions'][0]['options'], ['First', 'Second'])
            self.assertEqual(by_id['ask']['questions'][0]['optionValues'], ['opt_0_0', 'opt_0_1'])
            s.answer({'id': 'allow', 'choice': 'session'})
            self.assertEqual(s.api.calls[-1][2], {'kind': 'approval', 'decision': 'approved', 'scope': 'session'})
            s.answer({'id': 'ask', 'choice': 'submit', 'answers': {'q': ['opt_0_0', '\u0000raw:opt_0_1']}})
            self.assertEqual(s.api.calls[-1][2]['answers']['q'],
                {'kind': 'multi_with_other', 'option_ids': ['opt_0_0'], 'other_text': 'opt_0_1'})
            self.assertEqual(s.data['requests'], [])
            s.project_requests({'interactions': [question]})
            s.answer({'id': 'ask', 'choice': 'submit', 'answers': {'q': ['Second']}})
            self.assertEqual(s.api.calls[-1][2]['answers']['q'], {'kind': 'multi', 'option_ids': ['opt_0_1']})

    def test_queued_input_is_accepted_but_not_consumed_until_native_turn(self):
        with tempfile.TemporaryDirectory() as root:
            s = self.session(root)
            s.config['receipts'] = {'queued': 'unknown'}
            s.api.queue['queued'] = [{'prompt_id': 'queued', 'content': [{'type': 'text', 'text': 'Pending'}]}]
            s.refresh()
            self.assertEqual(s.config['receipts']['queued'], 'accepted')
            self.assertFalse(s.config.get('consumedSubmissions', {}).get('queued'))
            self.assertEqual(s.data['queue'][0]['id'], 'queued')
            s.queue_change({'id': 'queued', 'steer': False})
            self.assertIn(('POST', '/sessions/native/prompts/queued:abort', {}), s.api.calls)

    def test_merged_native_steers_confirm_each_original_submission(self):
        with tempfile.TemporaryDirectory() as root:
            s = self.session(root)
            s.config['receipts'] = {'first': 'unknown', 'second': 'unknown'}
            s.api.page['items'][0]['steps'][0]['frames'].append({'kind': 'text', 'role': 'user',
                'frameId': 'steered', 'text': 'First and second', 'promptIds': ['first', 'second'], 'origin': {'kind': 'user'}})
            s.refresh()
            self.assertEqual(s.config['consumedSubmissions'], {'first': True, 'second': True})
            self.assertEqual(s.config['receipts'], {'first': 'accepted', 'second': 'accepted'})

    def test_subagent_requests_anchoring_and_settled_cache(self):
        with tempfile.TemporaryDirectory() as root:
            s = self.session(root)
            s.api.page['items'] = [{'kind': 'turn', 'turnId': 't0', 'ordinal': 0, 'state': 'completed',
                'origin': {'kind': 'user'}, 'prompt': 'Spawn child',
                'steps': [{'state': 'completed', 'frames': [{'kind': 'tool', 'frameId': 'f-agent',
                    'toolCallId': 'tc-agent', 'name': 'Agent', 'state': 'done',
                    'input': {'subagent_type': 'explore', 'description': 'Inspect repo'},
                    'agentRefs': [{'agentId': 'child', 'role': 'child'}],
                    'output': 'agent_id: child\nactual_subagent_type: explore\nstatus: completed\n\n[summary]\nDone summary'}]}]}]
            s.api.page['agents'].append({'agentId': 'child', 'type': 'sub'})
            child = {'items': [turn('ct0', 'Inspect repo')], 'has_more': False,
                'interactions': [{'interactionId': 'child-request', 'interactionKind': 'approval',
                    'state': 'pending', 'request': {'toolName': 'ReadFile'}}],
                'attachments': [], 'prompts': []}
            child_fetches = 0
            original = s.api.request
            def request(method, path, body=None, **kwargs):
                nonlocal child_fetches
                if 'agent_id=child' in path:
                    child_fetches += 1
                    return copy.deepcopy(child)
                return original(method, path, body, **kwargs)
            s.api.request = request
            s.refresh()
            self.assertEqual(s.data['requests'][0]['id'], 'child-request')
            self.assertEqual(s.subagents['child']['itemId'], 'f-agent')
            self.assertEqual(s.subagents['child']['turnId'], 't0')
            self.assertEqual(s.subagents['child']['role'], 'explore')
            self.assertEqual(s.subagents['child']['detail'], 'Done summary')
            child['interactions'] = []
            s.refresh()
            self.assertEqual(child_fetches, 2)
            s.refresh()
            self.assertEqual(child_fetches, 2)
            self.assertEqual(s.rows['f-agent']['text'], 'Inspect repo')

    def test_web_is_default_acp_is_fallback_and_explicit_web_does_not_fall_back(self):
        with tempfile.TemporaryDirectory() as root:
            work = pathlib.Path(root) / 'work'
            work.mkdir()
            def fake_web_connect(self_sess):
                self_sess.config['nativeProtocol'] = PROTOCOL
                self_sess.save()
                self_sess.update(connected=True, nativeProtocol=PROTOCOL)
            with mock.patch.object(KimiSession, 'connect'), mock.patch.object(KimiWebSession, 'connect', fake_web_connect):
                with Manager(pathlib.Path(root) / 'state') as manager:
                    default_sess = manager.connect({'id': 'default', 'cli': 'Kimi Code', 'cwd': str(work)})
                    acp_sess = manager.connect({'id': 'acp', 'cli': 'Kimi Code', 'cwd': str(work), 'nativeProtocol': 'kimi-acp'})
                    self.assertIs(type(default_sess), KimiWebSession)
                    self.assertEqual(default_sess.config['nativeProtocol'], PROTOCOL)
                    self.assertIs(type(acp_sess), KimiSession)
                    self.assertEqual(acp_sess.config['nativeProtocol'], 'kimi-acp')
            gate = threading.Event()
            def slow_incompatible(self_sess):
                gate.wait(2)
                raise KimiWebIncompatible('Web unavailable')
            with mock.patch.object(KimiWebSession, 'connect', slow_incompatible), mock.patch.object(KimiSession, 'connect', lambda s: s.update(connected=True, nativeProtocol='kimi-acp')):
                with Manager(pathlib.Path(root) / 'fallback-state') as manager:
                    pending = manager.connect({'id': 'auto-fallback', 'cli': 'Kimi Code', 'cwd': str(work)}, background=True)
                    self.assertNotIn('nativeProtocol', pending.snapshot())
                    gate.set()
                    deadline = time.monotonic() + 2
                    while time.monotonic() < deadline and manager.sessions.get('auto-fallback') is pending:
                        time.sleep(.01)
                    fell_back = manager.sessions['auto-fallback']
                    deadline = time.monotonic() + 2
                    while time.monotonic() < deadline and fell_back.data.get('connecting'):
                        time.sleep(.01)
                    self.assertIs(type(fell_back), KimiSession)
                    self.assertEqual(fell_back.snapshot()['nativeProtocol'], 'kimi-acp')
                    self.assertTrue(fell_back.data['permissionNotice'])
                    with self.assertRaises(KimiWebIncompatible):
                        manager.connect({'id': 'pinned-web', 'cli': 'Kimi Code', 'cwd': str(work), 'nativeProtocol': PROTOCOL})

    def test_web_accepts_future_versions_with_v2_capabilities_and_rejects_auth_bypass(self):
        with tempfile.TemporaryDirectory() as root:
            gateway = KimiWebGateway(root)
            gateway.api = mock.Mock()
            gateway.api.request.return_value = {'server_version': '2.6.2', 'backend': 'v2',
                'capabilities': {'websocket': True}, 'dangerous_bypass_auth': False}
            gateway.validate()
            gateway.api.request.return_value = {'server_version': '2.6.2', 'backend': 'v3',
                'capabilities': {'websocket': False}, 'dangerous_bypass_auth': False}
            with self.assertRaises(KimiWebIncompatible):
                gateway.validate()
            gateway.api.request.return_value = {'server_version': '2.5.3', 'backend': 'v2',
                'capabilities': {'websocket': True}, 'dangerous_bypass_auth': True}
            with self.assertRaises(Rejected):
                gateway.validate()

    def test_gateway_idle_retry_releases_file_lock_before_detaching(self):
        for reason in ('busy', 'connected', 'error'):
            with self.subTest(reason=reason), tempfile.TemporaryDirectory() as root:
                gateway = KimiWebGateway(root)
                gateway.handle = {'pid': 99999, 'port': 12345, 'token': 'fixture'}
                gateway.api = mock.Mock()
                if reason == 'busy':
                    gateway.api.request.side_effect = [{'items': [{'busy': True}]}]
                elif reason == 'connected':
                    gateway.api.request.side_effect = [{'items': []}, {'connections': [{'id': 'browser'}]}]
                else:
                    gateway.api.request.side_effect = OSError('fixture')
                detach = gateway.detach
                def detach_unlocked(ident):
                    with (gateway.directory / 'gateway.lock').open('a+b') as locked:
                        try:
                            fcntl.flock(locked, fcntl.LOCK_EX | fcntl.LOCK_NB)
                        except BlockingIOError:
                            self.fail('Idle retry reacquired a file lock already held by this process')
                    detach(ident)
                with mock.patch.object(gateway, 'alive', return_value=True), \
                        mock.patch.object(gateway, 'detach', side_effect=detach_unlocked) as retried, \
                        mock.patch('luma_cli.kimi_web_transport.threading.Timer') as timer:
                    gateway.stop_if_idle()
                    retried.assert_called_once_with('')
                    timer.assert_called_once_with(300, gateway.stop_if_idle)
                    timer.return_value.start.assert_called_once_with()
                    gateway.api.request.assert_any_call('GET', '/sessions?busy=true&page_size=1')
                    self.assertNotIn(mock.call('POST', '/shutdown', {}), gateway.api.request.call_args_list)

    def test_slow_gateway_shutdown_retries_until_exit_and_reaper_removes_handle(self):
        with tempfile.TemporaryDirectory() as root:
            gateway = KimiWebGateway(pathlib.Path(root) / '.kimi-web')
            gateway.handle = {'pid': 99999, 'processStart': 'fixture', 'port': 12345, 'token': 'fixture'}
            gateway._save_handle_locked()
            gateway.api = mock.Mock()
            gateway.api.request.side_effect = [{'items': []}, {'connections': []}, {}]
            with mock.patch.object(gateway, 'alive', return_value=True), \
                    mock.patch('luma_cli.kimi_web_transport.time.monotonic', side_effect=[0, 4]), \
                    mock.patch('luma_cli.kimi_web_transport.threading.Timer') as timer:
                gateway.stop_if_idle()
                gateway.api.request.assert_called_with('POST', '/shutdown', {})
                self.assertTrue(gateway.handle)
                self.assertTrue((gateway.directory / 'gateway.json').is_file())
                timer.assert_called_once_with(300, gateway.stop_if_idle)
                timer.return_value.start.assert_called_once_with()
            with mock.patch('luma_cli.kimi_web_transport._gateway_instance', return_value=gateway), \
                    mock.patch.object(gateway, 'alive', return_value=False):
                reap_idle_kimi_gateway(root, 0)
            self.assertEqual(gateway.handle, {})
            self.assertFalse((gateway.directory / 'gateway.json').exists())

    def test_gateway_idle_shutdown_and_bridge_reaper_protect_native_work(self):
        with tempfile.TemporaryDirectory() as root:
            gateway = KimiWebGateway(pathlib.Path(root) / '.kimi-web')
            gateway.api = mock.Mock()
            alive = [True]
            gateway.handle = {'pid': 99999, 'port': 12345, 'token': 'secret', 'detachedAt': 0}
            gateway._save_handle_locked()
            with mock.patch.object(KimiWebGateway, 'alive', side_effect=lambda: alive[0]), mock.patch('luma_cli.kimi_web_transport.KimiWebHttp', return_value=gateway.api):
                self.assertTrue(has_live_kimi_gateway(root))
                gateway.api.request.side_effect = [{'items': [{'busy': True}]}]
                reap_idle_kimi_gateway(root, 300)
                self.assertTrue(has_live_kimi_gateway(root))
                gateway.api.request.reset_mock()
                gateway.api.request.side_effect = [{'items': []}, {'connections': [{'id': 'browser'}]}]
                reap_idle_kimi_gateway(root, 300)
                self.assertTrue(has_live_kimi_gateway(root))
                gateway.api.request.reset_mock()
                def shutdown_side_effect(method, path, body=None, **kw):
                    if path == '/shutdown':
                        alive[0] = False
                        return {'ok': True}
                    return {'items': [], 'connections': []}
                gateway.api.request.side_effect = shutdown_side_effect
                reap_idle_kimi_gateway(root, 300)
                gateway.api.request.assert_called_with('POST', '/shutdown', {})
                self.assertFalse(has_live_kimi_gateway(root))

    def test_websocket_fragmented_json_handles_control_ping_without_corrupting_text(self):
        wire = KimiWebSocket.__new__(KimiWebSocket)
        wire.closed = False
        first, second = b'{"typ', b'e":"fixture"}'
        wire.reader = io.BytesIO(bytes([1, len(first)]) + first + b'\x89\x01x' + bytes([128, len(second)]) + second)
        wire.send = mock.Mock()
        self.assertEqual(wire.receive(), {'type': 'fixture'})
        wire.send.assert_called_once_with(b'x', 10)

    def test_live_acp_owner_blocks_web_replacement_of_the_same_native_session(self):
        with tempfile.TemporaryDirectory() as root:
            work = pathlib.Path(root) / 'work'
            work.mkdir()
            with mock.patch.object(KimiSession, 'connect'):
                with Manager(pathlib.Path(root) / 'state') as manager:
                    legacy = manager.connect({'id': 'legacy', 'cli': 'Kimi Code', 'cwd': str(work), 'nativeId': 'native', 'nativeProtocol': 'kimi-acp'})
                    legacy.data['connected'] = True
                    legacy.peer = mock.Mock(closed=False)
                    legacy.peer.proc.poll.return_value = None
                    with self.assertRaises(Rejected):
                        manager.connect({'id': 'web', 'cli': 'Kimi Code', 'cwd': str(work), 'nativeId': 'native', 'nativeProtocol': PROTOCOL})
                    self.assertNotIn('web', manager.sessions)

    def test_large_image_prompt_uploads_via_files_api_instead_of_inline_base64_and_cleans_up_on_rejection(self):
        with tempfile.TemporaryDirectory() as root:
            s = self.session(root)
            img = pathlib.Path(root) / 'shot.png'
            payload_bytes = b'\x89PNG\r\n\x1a\n' + (b'\x00' * (2 * 1024 * 1024))
            img.write_bytes(payload_bytes)
            s.prompt({'submissionId': 'img-1', 'text': 'Check screenshot', 'images': [str(img)]})
            post_calls = [c for c in s.api.calls if c[0] == 'POST' and c[1].endswith('/prompts')]
            self.assertEqual(len(post_calls), 1)
            sent_body = post_calls[0][2]
            self.assertLess(len(json.dumps(sent_body)), 512)
            self.assertEqual(sent_body['content'], [
                {'type': 'text', 'text': 'Check screenshot'},
                {'type': 'image', 'source': {'kind': 'file', 'file_id': 'f_1'}, 'name': 'shot.png'},
            ])
            s.api.page['items'] = [turn('t-img', 'Check screenshot', 'img-1')]
            s.api.page['prompts'] = [{'promptId': 'img-1', 'status': 'completed', 'content': sent_body['content']}]
            s.refresh()
            self.assertEqual(len(s.rows['img-1']['media']), 1)

            orig_req = s.api.request
            def reject_prompt(method, path, body=None, binary=False):
                if method == 'POST' and path.endswith('/prompts'):
                    raise Rejected('Model rejected')
                return orig_req(method, path, body, binary)
            s.api.request = reject_prompt
            with self.assertRaises(Rejected):
                s.prompt({'submissionId': 'img-2', 'text': 'Retry', 'images': [str(img)]})
            self.assertNotIn('f_2', s.api.files)

    def test_kimi_binary_resolves_from_kimi_code_home_when_noninteractive_path_omits_bashrc(self):
        with tempfile.TemporaryDirectory() as root:
            home = pathlib.Path(root)
            kimi_bin_dir = home / '.kimi-code' / 'bin'
            kimi_bin_dir.mkdir(parents=True)
            kimi_exe = kimi_bin_dir / 'kimi'
            kimi_exe.write_text('#!/bin/sh\nexit 0\n', encoding='utf-8')
            kimi_exe.chmod(0o755)
            with mock.patch.dict(os.environ, {'HOME': str(home), 'PATH': '/usr/bin:/bin'}, clear=False):
                self.assertEqual(resolve_kimi_binary(), str(kimi_exe))
                sess = KimiSession({'id': 's', 'cli': 'Kimi Code', 'cwd': root}, lambda: None)
                with mock.patch('luma_cli.acp.AcpSession.start') as acp_start:
                    sess.start(['kimi', 'acp'])
                acp_start.assert_called_once_with([str(kimi_exe), 'acp'], None)
                kimi_exe.unlink()
                self.assertIsNone(resolve_kimi_binary())
                with self.assertRaises(Rejected):
                    KimiWebGateway(home / '.kimi-web').launch(mock.Mock())
                with self.assertRaises(Rejected):
                    KimiSession({'id': 's', 'cli': 'Kimi Code', 'cwd': root}, lambda: None).start(['kimi', 'acp'])

    def test_kimi_26_turns_pagination_ws_subscribe_payloadless_ping_interactions_and_metrics(self):
        with tempfile.TemporaryDirectory() as root:
            gw = KimiWebGateway(pathlib.Path(root) / '.kimi-web')
            gw.handle = {'pid': 99999}
            gw.api = mock.Mock()
            gw.api.port = 12345
            gw.api.token = 'tok'
            gw.api.request.return_value = {
                'server_version': '2.6.3',
                'dangerous_bypass_auth': False,
                'backend': 'v2',
                'capabilities': {'websocket': True},
            }
            gw.validate()
            self.assertTrue(gw.uses_turns_api)

            fake_sock = mock.Mock()
            incoming = [
                {'type': 'ping'},
                {'type': 'response', 'request_id': 'subscribe', 'code': 0},
                {'type': 'ping', 'payload': {'ts': 1}},
                {
                    'type': 'interaction',
                    'session_id': 'native',
                    'agent_id': 'main',
                    'interaction_id': 'int-1',
                    'kind': 'approval',
                    'status': 'pending',
                    'tool_call_id': 'tc-shell',
                    'request': {'tool_name': 'Shell', 'action': 'ls'},
                },
                ConnectionError('eof'),
            ]
            gate = threading.Event()
            interaction_seen = threading.Event()
            def next_frame():
                nxt = incoming.pop(0)
                if isinstance(nxt, BaseException):
                    gate.wait(2)
                    raise nxt
                return nxt
            fake_sock.receive.side_effect = next_frame
            events = []
            def on_event(frame):
                events.append(frame)
                if frame.get('type') == 'interaction':
                    interaction_seen.set()
            sess = mock.Mock(config={'id': 'local', 'nativeId': 'native'})
            exits = []
            with mock.patch('luma_cli.kimi_web_transport.KimiWebSocket', return_value=fake_sock):
                peer = KimiWebPeer(gw, sess, on_event, exits.append)
                peer.start_read()
                self.assertTrue(peer.ready.wait(2))
                self.assertTrue(interaction_seen.wait(2))
                peer.ensure_agent_subscriptions(['agent-sub-1', 'main', 'agent-sub-1'])
                gate.set()
                peer.thread.join(2)
            sent_frames = [c.args[0] for c in fake_sock.send.call_args_list]
            self.assertEqual(sent_frames[0], {
                'type': 'subscribe',
                'request_id': 'subscribe',
                'session_id': 'native',
                'agent_ids': ['main'],
            })
            self.assertIn({'type': 'pong'}, sent_frames)
            self.assertIn({'type': 'pong', 'payload': {'ts': 1}}, sent_frames)
            self.assertIn({
                'type': 'subscribe',
                'request_id': 'sub:agent-sub-1',
                'session_id': 'native',
                'agent_ids': ['agent-sub-1'],
            }, sent_frames)
            self.assertEqual(len(events), 1)
            self.assertEqual(events[0]['interaction_id'], 'int-1')

            s = KimiWebSession({
                'id': 'local',
                'cli': 'Kimi Code',
                'cwd': root,
                'nativeId': 'native',
                'nativeProtocol': PROTOCOL,
                'receipts': {'p0': 'unknown', 'p1': 'unknown'},
                'deliveries': {},
                'consumedSubmissions': {},
            }, lambda: None)
            s.gateway = gw
            s.path = '/sessions/native'
            s.models = [{'alias': 'kimi-k2.5', 'model': 'kimi-k2.5', 'max_context_size': 262144}]
            s.peer = mock.Mock(closed=False)
            turn0 = {
                'turn_id': 'turn-0',
                'ordinal': 0,
                'kind': 'agent',
                'status': 'ended',
                'outcome': 'completed',
                'usage': {'input_other': 1000, 'input_cache_read': 500, 'input_cache_creation': 0, 'output': 200},
                'steps': [{'index': 1, 'outcome': 'completed', 'usage': {'input_other': 1000, 'input_cache_read': 500, 'input_cache_creation': 0, 'output': 200}}],
                'items': [
                    {'type': 'user_message', 'item_id': 'u0', 'prompt_ids': ['p0'], 'placement': 'opener', 'origin': {'kind': 'user'}, 'text': 'hi', 'attachments': [], 'skills': []},
                    {'type': 'reasoning', 'item_id': 'r0', 'status': 'ended', 'outcome': 'completed', 'text': 'think'},
                    {'type': 'assistant_message', 'item_id': 'a0', 'status': 'ended', 'outcome': 'completed', 'text': 'hello'},
                ],
            }
            turn1 = {
                'turn_id': 'turn-1',
                'ordinal': 1,
                'kind': 'agent',
                'status': 'running',
                'usage': {'input_other': 2000, 'input_cache_read': 2000, 'input_cache_creation': 0, 'output': 300},
                'steps': [{'index': 1, 'outcome': 'completed', 'usage': {'input_other': 2000, 'input_cache_read': 2000, 'input_cache_creation': 0, 'output': 300}}],
                'items': [
                    {'type': 'user_message', 'item_id': 'u1', 'prompt_ids': ['p1'], 'placement': 'opener', 'origin': {'kind': 'user'}, 'text': 'run sub', 'attachments': [], 'skills': []},
                    {
                        'type': 'tool_call',
                        'item_id': 'tc1',
                        'name': 'Agent',
                        'status': 'running',
                        'input': {'subagent_type': 'coder', 'description': 'helper task'},
                        'output': '',
                        'agent_refs': [{'agent_id': 'agent-sub-1', 'role': 'child'}],
                    },
                ],
            }
            sub_turn = {
                'turn_id': 'st-0',
                'ordinal': 0,
                'kind': 'agent',
                'status': 'ended',
                'outcome': 'completed',
                'steps': [{'index': 1, 'outcome': 'completed', 'usage': {'input_other': 500, 'input_cache_read': 500, 'input_cache_creation': 0, 'output': 50}}],
                'items': [
                    {'type': 'user_message', 'item_id': 'su0', 'prompt_ids': ['sp0'], 'placement': 'opener', 'origin': {'kind': 'user'}, 'text': 'sub prompt', 'attachments': [], 'skills': []},
                    {'type': 'assistant_message', 'item_id': 'sa0', 'status': 'ended', 'outcome': 'completed', 'text': 'sub done'},
                ],
            }
            def req_side_effect(method, path, body=None, binary=False):
                if path == '/sessions/native':
                    return {'id': 'native', 'metadata': {'cwd': root}, 'title': 't', 'agent_config': {'model': 'kimi-k2.5'}, 'main_turn_active': False}
                if path == '/sessions/native/prompts':
                    return {'active': None, 'queued': []}
                if path == '/sessions/native/agents/main/turns?page_size=200':
                    return {'turns': [turn1], 'has_more': True}
                if path == '/sessions/native/agents/main/turns?page_size=200&before_ordinal=1':
                    return {'turns': [turn0], 'has_more': False}
                if path == '/sessions/native/agents/agent-sub-1/turns?page_size=200':
                    return {'turns': [sub_turn], 'has_more': False}
                if path == '/sessions/native/interactions/int-1':
                    return {'outcome': 'approved'}
                raise AssertionError(f'Unexpected request: {method} {path}')
            s.api = mock.Mock()
            s.api.request.side_effect = req_side_effect

            s.event({'session_id': 'native', 'agent_id': 'main', 'type': 'usage', 'context_tokens': 12000, 'max_context_tokens': 262144})
            s.event({'session_id': 'native', 'agent_id': 'main', 'type': 'agent', 'status': 'running', 'compacting': False, 'model': 'kimi-k2.5', 'plan_mode': True})
            s.event({
                'type': 'interaction',
                'session_id': 'native',
                'agent_id': 'main',
                'interaction_id': 'int-1',
                'kind': 'approval',
                'status': 'pending',
                'tool_call_id': 'tc1',
                'request': {'tool_name': 'Shell', 'action': 'ls'},
            })
            s.refresh()

            self.assertEqual(s.config['receipts']['p0'], 'accepted')
            self.assertEqual(s.config['receipts']['p1'], 'accepted')
            self.assertTrue(s.config['consumedSubmissions']['p0'])
            self.assertTrue(s.config['consumedSubmissions']['p1'])
            self.assertIn('p0', s.rows)
            self.assertEqual(s.rows['r0']['kind'], 'reasoning')
            self.assertEqual(s.rows['a0']['text'], 'hello')
            self.assertIn('tc1', s.rows)
            self.assertTrue(s.data['running'])
            self.assertTrue(s.data['planMode'])
            self.assertEqual(len(s.data['requests']), 1)
            self.assertEqual(s.data['requests'][0]['id'], 'int-1')
            self.assertIn('agent-sub-1', s.subagents)
            self.assertEqual(s.subagents['agent-sub-1']['status'], 'succeeded')
            self.assertEqual(s.subagents['agent-sub-1']['task'], 'sub prompt')
            s.peer.ensure_agent_subscriptions.assert_called_once_with(['agent-sub-1'])
            labels = {m['label']: m['value'] for m in s.data['metrics']}
            self.assertIn('上下文', labels)
            self.assertIn('缓存命中', labels)
            self.assertTrue(any('估算' in k or '费用' in k or 'USD' in k for k in labels), labels)

            s.answer({'id': 'int-1', 'choice': 'approved'})
            self.assertEqual(s.pending_requests, {})
            self.assertEqual(s.ws_interactions, {})

            metrics_res = kimi_web_metrics(
                {'id': 'native'},
                {'turns': [turn0, turn1]},
                [{'turns': [sub_turn]}],
                s.models,
                {'context_tokens': 12000, 'max_context_tokens': 262144},
                'kimi-k2.5',
            )
            self.assertTrue(any('3000 / 6500' in m['value'] for m in metrics_res), metrics_res)

    def test_turns_api_404_fallback_updates_existing_peers_and_subscribes(self):
        with tempfile.TemporaryDirectory() as root:
            gw = KimiWebGateway(pathlib.Path(root) / '.kimi-web')
            gw.handle = {'pid': 99999}
            gw.api = mock.Mock()
            gw.api.port = 12345
            gw.api.token = 'tok'
            gw.uses_turns_api = False

            fake_sock = mock.Mock()
            gate = threading.Event()
            def wait_eof():
                gate.wait(2)
                raise ConnectionError('eof')
            fake_sock.receive.side_effect = wait_eof

            fake_sock2 = mock.Mock()
            gate2 = threading.Event()
            def wait_eof2():
                gate2.wait(2)
                raise ConnectionError('eof')
            fake_sock2.receive.side_effect = wait_eof2

            sess = KimiWebSession({
                'id': 'local',
                'cli': 'Kimi Code',
                'cwd': root,
                'nativeId': 'native',
                'nativeProtocol': PROTOCOL,
                'receipts': {},
                'deliveries': {},
                'consumedSubmissions': {},
            }, lambda: None)
            sess.gateway = gw
            sess.path = '/sessions/native'
            sess.models = [{'alias': 'kimi-k2.5', 'model': 'kimi-k2.5', 'max_context_size': 262144}]

            sub_turn = {
                'turn_id': 'st-0',
                'ordinal': 0,
                'kind': 'agent',
                'status': 'ended',
                'outcome': 'completed',
                'items': [
                    {'type': 'user_message', 'item_id': 'su0', 'prompt_ids': [], 'placement': 'opener', 'origin': {'kind': 'user'}, 'text': 'sub prompt', 'attachments': [], 'skills': []},
                    {'type': 'assistant_message', 'item_id': 'sa0', 'status': 'ended', 'outcome': 'completed', 'text': 'sub done'},
                ],
            }
            main_turn = {
                'turn_id': 'turn-0',
                'ordinal': 0,
                'kind': 'agent',
                'status': 'running',
                'items': [
                    {'type': 'user_message', 'item_id': 'u0', 'prompt_ids': [], 'placement': 'opener', 'origin': {'kind': 'user'}, 'text': 'go', 'attachments': [], 'skills': []},
                    {
                        'type': 'tool_call',
                        'item_id': 'tc1',
                        'name': 'Agent',
                        'status': 'running',
                        'input': {'subagent_type': 'coder', 'description': 'helper'},
                        'output': '',
                        'agent_refs': [{'agent_id': 'agent-sub-1', 'role': 'child'}],
                    },
                ],
            }

            def req_side_effect(method, path, body=None, binary=False):
                if path == '/sessions/native':
                    return {'id': 'native', 'metadata': {'cwd': root}, 'title': 't', 'agent_config': {'model': 'kimi-k2.5'}, 'main_turn_active': True}
                if path == '/sessions/native/prompts':
                    return {'active': None, 'queued': []}
                if path.startswith('/sessions/native/transcript?'):
                    raise KimiWebRejected(404, 'Not Found')
                if path == '/sessions/native/agents/main/turns?page_size=200':
                    return {'turns': [main_turn], 'has_more': False}
                if path == '/sessions/native/agents/agent-sub-1/turns?page_size=200':
                    return {'turns': [sub_turn], 'has_more': False}
                raise AssertionError(f'Unexpected request: {method} {path}')

            sess.api = mock.Mock()
            sess.api.request.side_effect = req_side_effect

            with mock.patch('luma_cli.kimi_web_transport.KimiWebSocket', side_effect=[fake_sock, fake_sock2]):
                peer = KimiWebPeer(gw, sess, lambda f: None, lambda e: None)
                self.assertFalse(peer.uses_turns_api)
                sess.peer = peer
                peer.start_read()

                peer2 = KimiWebPeer(gw, mock.Mock(config={'id': 'local2', 'nativeId': 'native'}), lambda f: None, lambda e: None)
                self.assertFalse(peer2.uses_turns_api)
                peer2.start_read()
                try:
                    sess.refresh()
                    self.assertTrue(gw.uses_turns_api)
                    self.assertTrue(peer.uses_turns_api)
                    sent = [c.args[0] for c in fake_sock.send.call_args_list]
                    self.assertIn({
                        'type': 'subscribe',
                        'request_id': 'subscribe',
                        'session_id': 'native',
                        'agent_ids': ['main'],
                    }, sent)
                    self.assertIn({
                        'type': 'subscribe',
                        'request_id': 'sub:agent-sub-1',
                        'session_id': 'native',
                        'agent_ids': ['agent-sub-1'],
                    }, sent)

                    peer2.ensure_agent_subscriptions(['agent-sub-2'])
                    self.assertTrue(peer2.uses_turns_api)
                    sent2 = [c.args[0] for c in fake_sock2.send.call_args_list]
                    self.assertEqual(sent2, [
                        {
                            'type': 'subscribe',
                            'request_id': 'subscribe',
                            'session_id': 'native',
                            'agent_ids': ['main'],
                        },
                        {
                            'type': 'subscribe',
                            'request_id': 'sub:agent-sub-2',
                            'session_id': 'native',
                            'agent_ids': ['agent-sub-2'],
                        },
                    ])
                finally:
                    gate.set()
                    gate2.set()
                    peer.thread.join(2)
                    peer2.thread.join(2)

    def test_ws_notices_keep_unique_monotonic_ids_past_retention_cap(self):
        with tempfile.TemporaryDirectory() as root:
            sess = KimiWebSession({
                'id': 'local',
                'cli': 'Kimi Code',
                'cwd': root,
                'nativeId': 'native',
                'nativeProtocol': PROTOCOL,
                'receipts': {},
                'deliveries': {},
                'consumedSubmissions': {},
            }, lambda: None)
            for idx in range(40):
                sess.event({
                    'session_id': 'native',
                    'agent_id': 'main',
                    'type': 'notice',
                    'notice': {'title': f'notice-{idx}', 'level': 'info'},
                })
            ids = [nid for nid, _, _ in sess.ws_notices]
            self.assertEqual(len(ids), 32)
            self.assertEqual(len(set(ids)), 32)
            self.assertEqual(ids[0], 'ws-notice:8')
            self.assertEqual(ids[-1], 'ws-notice:39')

    def test_kap_1_0_meta_profile_reconnect_interactions_actions_context_and_idle_shutdown(self):
        with tempfile.TemporaryDirectory() as root:
            gw = KimiWebGateway(pathlib.Path(root) / '.kimi-web')
            gw.handle = {'pid': 99999, 'processStart': 'fixture', 'port': 12345, 'token': 'tok'}
            gw.api = mock.Mock()
            gw.api.request.return_value = {
                'kimi_agent_protocol_version': '1.0.0',
                'server_version': '2.8.1',
                'product': 'kimi-code-cli',
                'capabilities': {'websocket': True, 'interactions_list': True, 'file_upload': True},
                'dangerous_bypass_auth': False,
            }
            gw.validate()
            self.assertTrue(gw.is_kap)
            self.assertTrue(gw.uses_turns_api)
            self.assertTrue(gw.capabilities['interactions_list'])

            gw.api.request.return_value = {
                'kimi_agent_protocol_version': '2.0.0',
                'server_version': '3.0.0',
                'capabilities': {'websocket': True},
                'dangerous_bypass_auth': False,
            }
            with self.assertRaises(KimiWebIncompatible):
                gw.validate()

            gw.api.request.return_value = {
                'kimi_agent_protocol_version': '1.0.0',
                'server_version': '2.8.1',
                'capabilities': {'websocket': False},
                'dangerous_bypass_auth': False,
            }
            with self.assertRaises(KimiWebIncompatible):
                gw.validate()

            gw.api.request.return_value = {
                'kimi_agent_protocol_version': '1.0.0',
                'server_version': '2.8.1',
                'capabilities': {'websocket': True, 'interactions_list': True},
                'dangerous_bypass_auth': False,
            }
            gw.validate()

            calls = []
            session_record = {
                'id': 'native',
                'cwd': root,
                'workspace_root': root,
                'title': 'KAP session',
                'activity': 'idle',
                'pending_interaction': 'approval',
            }
            agent_state = {
                'status': 'idle',
                'compacting': False,
                'model': 'fixture/existing',
                'thinking': 'off',
                'permission_mode': 'yolo',
                'plan_mode': False,
            }
            interactions_items = [{
                'interaction_id': 'int-kap-1',
                'session_id': 'native',
                'agent_id': 'main',
                'tool_call_id': 'tc-1',
                'kind': 'approval',
                'request': {'tool_name': 'Bash', 'action': 'ls', 'tool_input_display': {'command': 'ls'}},
            }]

            def kap_request(method, path, body=None, binary=False):
                calls.append((method, path, copy.deepcopy(body)))
                if method == 'GET' and path == '/sessions/native':
                    return copy.deepcopy(session_record)
                if method == 'GET' and path == '/models':
                    return {'items': [{'provider': 'fixture', 'model': 'fixture/existing', 'display_name': 'Existing', 'max_context_size': 64000}]}
                if method == 'POST' and path == '/sessions/native/profile':
                    if body and 'agent_config' in body:
                        agent_state.update(body['agent_config'])
                    return {'session': copy.deepcopy(session_record), 'agent': copy.deepcopy(agent_state)}
                if method == 'GET' and path == '/sessions/native/prompts':
                    return {'active': None, 'queued': []}
                if method == 'GET' and path == '/sessions/native/agents/main/turns?page_size=200':
                    return {'turns': [], 'has_more': False}
                if method == 'GET' and path == '/sessions/native/interactions':
                    return {'items': copy.deepcopy(interactions_items)}
                if method == 'POST' and path in ('/sessions/native:cancel', '/sessions/native/prompts:steer', '/sessions/native/prompts/q1:abort'):
                    return {'ok': True}
                raise AssertionError(f'Unexpected KAP request: {method} {path} {body}')

            gw.api = mock.Mock()
            gw.api.request.side_effect = kap_request

            s = KimiWebSession({
                'id': 'local',
                'cli': 'Kimi Code',
                'cwd': root,
                'nativeId': 'native',
                'model': 'fixture/stale',
                'ownerDirectory': str(pathlib.Path(root) / 'local.native'),
                'nativeProtocol': PROTOCOL,
            }, lambda: None)
            with mock.patch('luma_cli.kimi_web.gateway_for', return_value=gw), mock.patch('luma_cli.kimi_web.KimiWebPeer', Peer):
                s.connect()
                try:
                    self.assertEqual(s.snapshot()['model'], 'fixture/existing')
                    self.assertEqual(s.snapshot()['permission'], 'yolo')
                    self.assertEqual([r['id'] for r in s.data['requests']], ['int-kap-1'])
                    self.assertNotIn(('POST', '/sessions/native/profile', {'agent_config': {'model': 'fixture/stale'}}), calls)

                    s.event({'session_id': 'native', 'agent_id': 'main', 'type': 'context', 'context_tokens': 4096, 'max_context_tokens': 64000})
                    session_record['activity'] = 'turn'
                    session_record['pending_interaction'] = 'none'
                    interactions_items.clear()
                    s.refresh()
                    self.assertTrue(s.data['running'])
                    self.assertEqual(s.data['requests'], [])
                    labels = {m['label']: m['value'] for m in s.data['metrics']}
                    self.assertIn('4096 / 64000', labels['上下文'])

                    session_record['activity'] = 'idle'
                    s.event({'session_id': 'native', 'agent_id': 'main', 'type': 'agent', 'status': 'running', 'compacting': False, 'model': 'fixture/existing'})
                    s.refresh()
                    self.assertFalse(s.data['running'])

                    s.stop()
                    self.assertIn(('POST', '/sessions/native:cancel', {}), calls)
                    s.queue_change({'id': 'q1', 'steer': True})
                    self.assertIn(('POST', '/sessions/native/prompts:steer', {'prompt_ids': ['q1']}), calls)
                    s.queue_change({'id': 'q1', 'steer': False})
                    self.assertIn(('POST', '/sessions/native/prompts/q1:abort', {}), calls)
                finally:
                    s.stop_observation()

            gw2 = KimiWebGateway(pathlib.Path(root) / 'reap-gw' / '.kimi-web')
            gw2.handle = {'pid': 99999, 'processStart': 'fixture', 'port': 12345, 'token': 'tok', 'detachedAt': 0}
            gw2._save_handle_locked()
            gw2.api = mock.Mock()
            alive = [True]

            def reaper_req(method, path, body=None, binary=False):
                if path == '/sessions?busy=true&page_size=1':
                    return {'items': []}
                if path == '/connections':
                    raise KimiWebRejected(404, 'Not Found')
                if path == '/shutdown?if_idle=true':
                    if alive[0] == 'busy_ws':
                        return {'ok': False}
                    alive[0] = False
                    return {'ok': True}
                raise AssertionError(f'Unexpected reaper request: {method} {path}')

            gw2.api.request.side_effect = reaper_req
            with mock.patch.object(KimiWebGateway, 'alive', side_effect=lambda: bool(alive[0])), \
                    mock.patch('luma_cli.kimi_web_transport.KimiWebHttp', return_value=gw2.api):
                alive[0] = 'busy_ws'
                reap_idle_kimi_gateway(pathlib.Path(root) / 'reap-gw', 300)
                self.assertTrue((gw2.directory / 'gateway.json').exists())
                alive[0] = True
                reap_idle_kimi_gateway(pathlib.Path(root) / 'reap-gw', 300)
                self.assertFalse((gw2.directory / 'gateway.json').exists())
                gw2.api.request.assert_any_call('POST', '/shutdown?if_idle=true', {})

    def test_kimi_web_http_omits_content_type_when_body_is_none(self):
        from luma_cli.kimi_web_transport import KimiWebHttp
        fake_conn = mock.Mock()
        fake_resp = mock.Mock(status=200)
        fake_resp.read.return_value = json.dumps({'code': 0, 'data': {'ok': True}}).encode()
        fake_conn.getresponse.return_value = fake_resp
        with mock.patch('luma_cli.kimi_web_transport.http.client.HTTPConnection', return_value=fake_conn):
            api = KimiWebHttp(12345, 'tok')
            api.request('DELETE', '/files/f1')
            self.assertEqual(fake_conn.request.call_args.args[3], {'Authorization': 'Bearer tok'})
            api.request('POST', '/shutdown', {})
            self.assertEqual(fake_conn.request.call_args.args[3], {'Authorization': 'Bearer tok', 'Content-Type': 'application/json'})

if __name__ == '__main__':
    unittest.main()

#!/usr/bin/env python3
import base64
import json
import os
import pathlib
import secrets
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

sys.dont_write_bytecode = True
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[3] / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.kimi_web import KimiWebSession

class ModelFixture(BaseHTTPRequestHandler):
    mode = 'text'
    calls = []
    held = threading.Event()
    def log_message(self, *args):
        pass
    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        type(self).calls.append(data)
        mode = type(self).mode
        if mode == 'error':
            raw = json.dumps({'error': {'message': 'Fixture rejected', 'type': 'invalid_request_error'}}).encode()
            self.send_response(400)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(raw)
            return
        after_tool = data['messages'][-1]['role'] == 'tool'
        if mode == 'hold':
            type(self).held.wait(15)
        if mode in ('approval', 'question') and not after_tool:
            name = 'Bash' if mode == 'approval' else 'AskUserQuestion'
            arguments = {'command': 'printf luma-kimi-fixture', 'description': 'Print a fixture'} if mode == 'approval' else {
                'questions': [{'question': 'Choose a fixture?', 'header': 'Fixture', 'multi_select': False,
                    'options': [{'label': 'First'}, {'label': 'Second'}]}]}
            delta = {'tool_calls': [{'index': 0, 'id': 'call-' + secrets.token_hex(6), 'type': 'function',
                'function': {'name': name, 'arguments': json.dumps(arguments)}}]}
            finish = 'tool_calls'
        else:
            delta, finish = {'content': 'Fixture complete'}, 'stop'
        frames = [
            {'id': 'fixture', 'object': 'chat.completion.chunk', 'choices': [{'index': 0, 'delta': delta, 'finish_reason': None}]},
            {'id': 'fixture', 'object': 'chat.completion.chunk', 'choices': [{'index': 0, 'delta': {}, 'finish_reason': finish}],
                'usage': {'prompt_tokens': 30, 'completion_tokens': 8, 'total_tokens': 38}}]
        try:
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.end_headers()
            for frame in frames:
                self.wfile.write(('data: ' + json.dumps(frame) + '\n\n').encode())
            self.wfile.write(b'data: [DONE]\n\n')
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass

def wait_for(predicate, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(.05)
    raise AssertionError('Native Kimi fixture did not reach expected state')

class KimiWebNativeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        binary = shutil.which('kimi')
        if not binary:
            raise unittest.SkipTest('Requires Kimi Code')
        cls.temp = tempfile.TemporaryDirectory(prefix='luma-kimi-native-')
        cls.sessions = []
        cls.gateway = None
        cls.model = None
        cls.addClassCleanup(cls.cleanup)
        cls.root = pathlib.Path(cls.temp.name)
        cls.work = cls.root / 'work'
        cls.work.mkdir()
        cls.environment = mock.patch.dict(os.environ, {'HOME': str(cls.root),
            'KIMI_CODE_HOME': str(cls.root / 'home'), 'KIMI_SHARE_DIR': str(cls.root / 'home')})
        cls.environment.start()
        cls.model = ThreadingHTTPServer(('127.0.0.1', 0), ModelFixture)
        threading.Thread(target=cls.model.serve_forever, daemon=True).start()
        seed = cls.create_session('seed')
        cls.gateway = seed.gateway
        seed.api.request('POST', '/providers', {'id': 'fixture', 'type': 'openai',
            'api_key': secrets.token_urlsafe(24), 'base_url': 'http://127.0.0.1:' + str(cls.model.server_port) + '/v1',
            'default_model': 'fixture', 'models': [{'model': 'fixture', 'max_context_size': 32000, 'capabilities': ['image_in']}]})
        seed.stop_observation()

    @classmethod
    def create_session(cls, name, config=None):
        config = config or {'id': name, 'cli': 'Kimi Code', 'cwd': str(cls.work),
            'ownerDirectory': str(cls.root / 'bridge' / (name + '.native')), 'nativeProtocol': 'kimi-web-v1'}
        session = KimiWebSession(config, lambda: None)
        cls.sessions.append(session)
        session.connect()
        if session.snapshot()['nativeId'] != session.config['nativeId']:
            raise AssertionError('Bridge snapshot did not publish the native session identity')
        return session

    @classmethod
    def cleanup(cls):
        ModelFixture.held.set()
        try:
            for session in cls.sessions:
                session.stop_observation()
            gateway = cls.gateway or next((getattr(s, 'gateway', None) for s in cls.sessions if getattr(s, 'gateway', None)), None)
            if gateway:
                if gateway.idle_timer:
                    gateway.idle_timer.cancel()
                gateway.api.request('POST', '/shutdown', {})
                wait_for(lambda: not gateway.alive(), 8)
        finally:
            if cls.model:
                cls.model.shutdown()
                cls.model.server_close()
            if hasattr(cls, 'environment'):
                cls.environment.stop()
            cls.temp.cleanup()

    def setUp(self):
        ModelFixture.calls = []
        ModelFixture.mode = 'text'
        ModelFixture.held.clear()

    def tearDown(self):
        ModelFixture.held.set()
        for session in self.sessions:
            if session.peer and not session.peer.closed:
                if session.data['running']:
                    session.stop()
                session.stop_observation()

    def test_approval_clean_history_same_id_reconnect_and_no_duplicate_send(self):
        s = self.create_session('approval')
        s.configure({'model': 'fixture/fixture', 'effort': '', 'permission': 'manual'})
        ModelFixture.mode = 'approval'
        payload = {'submissionId': 'approval-input', 'text': 'Run fixture; keep <system-reminder>literal</system-reminder>'}
        self.assertEqual(s.submit(payload)['receipt'], 'accepted')
        wait_for(lambda: bool(s.data['requests']))
        request = s.data['requests'][0]
        self.assertEqual(request['title'], 'Bash')
        s.answer({'id': request['id'], 'choice': 'approved'})
        wait_for(lambda: not s.data['running'] and any(r['text'] == 'Fixture complete' for r in s.rows.values()))
        self.assertTrue(s.config['consumedSubmissions']['approval-input'])
        self.assertEqual([r['text'] for r in s.rows.values() if r['role'] == 'user'], [payload['text']])
        self.assertTrue(any(r.get('detail') == 'luma-kimi-fixture' for r in s.rows.values()))
        snapshot = s.fetch_snapshot()
        if 'turns' in snapshot and 'items' not in snapshot:
            self.assertTrue(snapshot['turns'])
            self.assertEqual([i['text'] for i in snapshot['turns'][0]['items'] if i.get('type') == 'user_message'], [payload['text']])
        else:
            self.assertIn('date_change', json.dumps(snapshot))
        native = s.config['nativeId']
        count = len(ModelFixture.calls)
        s.stop_observation()
        restored = self.create_session('restored', s.config)
        self.assertEqual(restored.config['nativeId'], native)
        self.assertEqual(restored.submit(payload)['receipt'], 'accepted')
        self.assertEqual(len(ModelFixture.calls), count)
        self.assertEqual([r['text'] for r in restored.rows.values() if r['role'] == 'user'], [payload['text']])

    def test_question_round_trip(self):
        s = self.create_session('question')
        s.configure({'model': 'fixture/fixture', 'permission': 'yolo'})
        wait_for(lambda: s.data['permission'] == 'yolo')
        ModelFixture.mode = 'question'
        s.submit({'submissionId': 'question-input', 'text': 'Ask fixture question'})
        wait_for(lambda: any(r['questions'] for r in s.data['requests']))
        request = next(r for r in s.data['requests'] if r['questions'])
        self.assertEqual(request['title'], 'Answer')
        question = request['questions'][0]
        self.assertEqual(question['options'], ['First', 'Second'])
        self.assertEqual(len(question['optionValues']), 2)
        s.answer({'id': request['id'], 'choice': 'submit',
            'answers': {question['id']: [question['optionValues'][0]]}})
        wait_for(lambda: not s.data['running'] and any(r['text'] == 'Fixture complete' for r in s.rows.values()))
        self.assertFalse(s.data['requests'])

    def test_selected_model_is_applied_before_first_prompt_without_configure(self):
        config = {'id': 'initial-model', 'cli': 'Kimi Code', 'cwd': str(self.work),
            'ownerDirectory': str(self.root / 'bridge' / 'initial-model.native'),
            'nativeProtocol': 'kimi-web-v1', 'model': 'fixture/fixture'}
        s = self.create_session('initial-model', config)
        self.assertEqual(s.snapshot()['model'], 'fixture/fixture')
        self.assertEqual(s.api.request('GET', s.path)['agent_config']['model'], 'fixture/fixture')
        s.submit({'submissionId': 'initial-model-input', 'text': 'Hello fixture'})
        wait_for(lambda: any(r['role'] == 'assistant' and r['text'] == 'Fixture complete' for r in s.rows.values()))

    def test_provider_failure_is_visible_in_bridge_history(self):
        s = self.create_session('failed-provider')
        ModelFixture.mode = 'error'
        s.submit({'submissionId': 'failed-input', 'text': 'Fail fixture'})
        wait_for(lambda: any(r.get('status') == 'failed' and 'Fixture rejected' in r['text'] for r in s.rows.values()))
        self.assertFalse(any(r['role'] == 'assistant' for r in s.rows.values()))

    def test_image_round_trip_keeps_native_attachment_without_injection_bubbles(self):
        s = self.create_session('image')
        s.configure({'model': 'fixture/fixture'})
        image = self.work / 'fixture.png'
        image.write_bytes(base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII='))
        s.submit({'submissionId': 'image-input', 'text': '', 'images': [str(image)]})
        wait_for(lambda: not s.data['running'] and any(r['text'] == 'Fixture complete' for r in s.rows.values()))
        user = [r for r in s.rows.values() if r['role'] == 'user']
        self.assertEqual(len(user), 1)
        self.assertEqual(user[0]['text'], '')
        self.assertTrue(user[0]['media'], json.dumps(s.fetch_snapshot()))
        self.assertTrue(pathlib.Path(user[0]['media'][0]['source']).is_file())
        native = s.config['nativeId']
        s.stop_observation()
        s.api.request('POST', '/shutdown', {})
        wait_for(lambda: not s.gateway.alive(), 8)
        restored = self.create_session('image-restored', s.config)
        self.assertEqual(restored.config['nativeId'], native)
        restored_user = [r for r in restored.rows.values() if r['role'] == 'user']
        self.assertEqual(len(restored_user), 1)
        self.assertTrue(restored_user[0]['media'], json.dumps(restored.fetch_snapshot()))

    def test_queue_cancel_stop_and_shared_gateway_observer_release(self):
        s = self.create_session('queue')
        s.configure({'model': 'fixture/fixture'})
        other = self.create_session('other')
        self.assertEqual(s.gateway.handle['pid'], other.gateway.handle['pid'])
        ModelFixture.mode = 'hold'
        s.submit({'submissionId': 'hold-input', 'text': 'Hold fixture'})
        wait_for(lambda: s.data['running'])
        s.submit({'submissionId': 'queued-input', 'text': 'Queued fixture'})
        wait_for(lambda: any(p['id'] == 'queued-input' for p in s.data['queue']))
        self.assertFalse(s.config.get('consumedSubmissions', {}).get('queued-input'))
        s.queue_change({'id': 'queued-input', 'steer': False})
        wait_for(lambda: not s.data['queue'])
        s.stop()
        ModelFixture.held.set()
        wait_for(lambda: not s.data['running'])
        released = s.release_resources(s.config['nativeId'], 'kimi-web-v1')
        self.assertTrue(released['released'])
        self.assertEqual(released['nativeId'], s.config['nativeId'])
        self.assertTrue(other.gateway.alive())
        self.assertFalse(other.peer.closed)

if __name__ == '__main__':
    unittest.main()

#!/usr/bin/env python3
import sys
sys.dont_write_bytecode = True
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
sys.path.insert(0, str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.antigravity import AntigravitySession
from luma_cli.base import Session
from luma_cli.external_browser import BrowserRelay, valid_google_auth_url
from luma_cli.server import ADAPTERS, Manager
from luma_cli.transport import Rejected

OPTIONS = [
    {'id': 'native:model', 'category': 'model', 'type': 'select', 'currentValue': 'opaque/model',
     'options': [{'value': 'opaque/model', 'name': 'Native model'}]},
    {'id': 'thinking_level', 'category': 'thought_level', 'type': 'select', 'currentValue': 'opaque/high',
     'options': [{'value': 'opaque/high', 'name': 'High'}, {'value': 'opaque/low', 'name': 'Low'}]},
    {'id': 'mode', 'category': 'mode', 'type': 'select', 'currentValue': 'default',
     'options': [{'value': 'default', 'name': 'Default', 'description': 'Native review'},
                 {'value': 'yolo', 'name': 'YOLO', 'description': 'Native automatic approval warning'}]},
]

def session(native=None):
    config = {'cli': 'Antigravity', 'cwd': '/tmp', 'receipts': {}}
    if native: config['nativeId'] = native
    value = AntigravitySession(config, lambda: None)
    value.auth_url_rejected = False
    return value

def connected(s):
    calls = []
    s.start = lambda argv: calls.append(('start', argv))
    def call(method, params=None, **kwargs):
        calls.append((method, params))
        if method == 'initialize':
            return {'protocolVersion': 1, 'agentCapabilities': {'loadSession': True, 'promptCapabilities': {'image': True}}, 'authMethods': [{'id': 'oauth-personal'}]}
        if method in ('session/new', 'session/load'):
            return {'sessionId': 'native', 'configOptions': OPTIONS}
        if method == 'session/set_config_option':
            return {'configOptions': [dict(o, currentValue=params['value']) if o['id'] == params['configId'] else o for o in OPTIONS]}
        return {'configOptions': OPTIONS}
    s.call = call
    with patch('luma_cli.antigravity.shutil.which', return_value='/native/agy_acp_server.par'): s.connect()
    return calls

class AntigravityTests(unittest.TestCase):
    def test_official_executable_and_no_consumer_headless_fallback(self):
        s = session(); calls = connected(s)
        self.assertEqual(calls[0], ('start', ['agy_acp_server.par', '--uid=']))
        self.assertNotIn('--input-format', str(calls))
        self.assertEqual(s.data['nativeId'], 'native')
        self.assertFalse(s.data['capabilities']['queue']); self.assertFalse(s.data['capabilities']['skills'])
        self.assertEqual(s.data['mcp'], []); self.assertEqual(s.subagents, {})

    def test_missing_acp_is_actionable_without_creating_identity_or_fake_catalog(self):
        s = session()
        with patch('luma_cli.antigravity.shutil.which', return_value=None):
            with self.assertRaisesRegex(Rejected, '安装 agy CLI 不等于安装 ACP'): s.connect()
        self.assertFalse(s.config.get('creationUnknown')); self.assertEqual(s.data['models'], [])
        self.assertEqual(s.data['permissions'], []); self.assertEqual(s.data['nativeId'], '')

    def test_known_native_tcmalloc_failure_has_short_safe_diagnostic(self):
        s = session()
        def start(argv):
            s.peer = type('P', (), {'diagnostic': b'third_party/tcmalloc/arena.cc FATAL ERROR token=not-for-ui', 'close': lambda _: None})()
            raise ConnectionError('native EOF')
        s.start = start
        with patch('luma_cli.antigravity.shutil.which', return_value='/native/agy_acp_server.par'):
            with self.assertRaisesRegex(Rejected, 'TCMalloc 地址空间不兼容') as error: s.connect()
        self.assertNotIn('not-for-ui', str(error.exception)); self.assertEqual(s.data['models'], [])

    def test_other_startup_failures_are_not_misclassified_as_arm64(self):
        s = session()
        def start(argv):
            s.peer = type('P', (), {'diagnostic': b'ordinary installation failure', 'close': lambda _: None})()
            raise ConnectionError('native EOF')
        s.start = start
        with patch('luma_cli.antigravity.shutil.which', return_value='/native/agy_acp_server.par'):
            with self.assertRaisesRegex(ConnectionError, 'native EOF'): s.connect()

    def test_resume_is_same_acp_identity_and_load_only(self):
        s = session('native'); calls = connected(s)
        self.assertEqual([c[0] for c in calls], ['start', 'initialize', 'authenticate', 'session/load'])
        self.assertEqual(calls[-1][1], {'cwd': '/tmp', 'mcpServers': [], 'sessionId': 'native'})
        self.assertEqual(s.data['nativeId'], 'native')
        self.assertEqual(s.config['nativeProtocol'], 'antigravity-acp')

    def test_protocol_identity_cannot_silently_change(self):
        s = session('native'); s.config['nativeProtocol'] = 'antigravity-cli-api'
        with self.assertRaisesRegex(Rejected, '不能切换协议'): s.connect()

    def test_config_options_are_native_and_keep_permission_warning(self):
        s = session(); calls = connected(s)
        self.assertEqual(s.data['models'][0]['id'], 'opaque/model')
        self.assertEqual(s.data['models'][0]['efforts'], ['opaque/high', 'opaque/low'])
        self.assertEqual(s.data['permissions'][-1]['description'], 'Native automatic approval warning')
        s.configure({'effort': 'opaque/low'})
        self.assertEqual(calls[-1], ('session/set_config_option', {'sessionId': 'native', 'configId': 'thinking_level', 'value': 'opaque/low'}))
        self.assertRaises(Rejected, s.configure, {'effort': 'invented'})
        self.assertRaises(Rejected, s.configure, {'speed': 'fast'})

    def test_real_events_confirm_receipt_local_row_and_config_do_not(self):
        s = session(); connected(s); waiting = threading.Event()
        def call(*args, **kwargs): waiting.wait(2); return {'stopReason': 'end_turn'}
        s.call = call
        self.assertEqual(s.submit({'submissionId': 'send', 'text': 'hello'}), {'receipt': 'unknown'})
        self.assertIn('send', s.rows); self.assertEqual(s.config['receipts']['send'], 'unknown')
        s.event({'method': 'session/update', 'params': {'sessionId': 'native', 'update': {'sessionUpdate': 'config_option_update', 'configOptions': OPTIONS}}})
        self.assertEqual(s.config['receipts']['send'], 'unknown')
        s.event({'method': 'session/update', 'params': {'sessionId': 'native', 'update': {'sessionUpdate': 'agent_thought_chunk', 'content': {'type': 'text', 'text': 'native thought'}}}})
        self.assertEqual(s.config['receipts']['send'], 'accepted')
        self.assertEqual(list(s.rows.values())[-1]['text'], 'native thought')
        waiting.set()

    def test_usage_and_approval_ids_remain_native(self):
        s = session(); connected(s); sent = []
        s.peer = type('P', (), {'send': lambda _, frame: sent.append(frame)})()
        s.event({'method': 'session/update', 'params': {'sessionId': 'native', 'update': {'sessionUpdate': 'usage_update', 'used': 13, 'size': 4096}}})
        self.assertEqual(s.data['metrics'][0]['label'], '上下文')
        s.event({'id': 0, 'method': 'session/request_permission', 'params': {'sessionId': 'native', 'options': [{'optionId': 'allow-once/opaque', 'name': 'Native choice'}]}})
        s.answer({'id': '0', 'choice': 'allow-once/opaque'})
        self.assertEqual(sent[-1], {'jsonrpc': '2.0', 'id': 0, 'result': {'outcome': {'outcome': 'selected', 'optionId': 'allow-once/opaque'}}})
        s.stop(); self.assertEqual(sent[-1]['method'], 'session/cancel')
        self.assertFalse(s.data['running'])

    def test_unknown_create_is_never_automatically_repeated(self):
        s = session(); s.config['creationUnknown'] = True
        s.start = lambda argv: None
        s.call = lambda *args, **kwargs: {'protocolVersion': 1, 'agentCapabilities': {}, 'authMethods': [{'id': 'oauth-personal'}]}
        with patch('luma_cli.antigravity.shutil.which', return_value='/native/agy_acp_server.par'):
            with self.assertRaisesRegex(Rejected, '创建结果未知'): s.connect()

    def test_personal_auth_uses_only_official_method_before_new_and_load(self):
        for native in (None, 'native'):
            s = session(native); calls = connected(s)
            self.assertEqual(calls[2], ('authenticate', {'methodId': 'oauth-personal'}))
            self.assertFalse(s.pending_requests); self.assertEqual(s.data['requests'], [])

    def test_auth_uses_native_absolute_window_without_changing_message_delivery_timeout(self):
        s = session(); calls = []
        s.call = lambda method, params, **kwargs: calls.append((method, params, kwargs)) or {}
        s.authenticate({'authMethods': [{'id': 'oauth-personal'}]})
        self.assertEqual(calls, [('authenticate', {'methodId': 'oauth-personal'}, {'timeout': 300, 'pause_for_user': False})])

    def test_existing_clis_keep_their_environment_without_auth_opener_injection(self):
        s = Session({'cli': 'pi', 'cwd': '/tmp', 'browserConfig': '/session/browser'}, lambda: None)
        with patch.dict(os.environ, {'BROWSER': 'user-original-browser'}), patch('luma_cli.base.Peer') as peer:
            s.start(['native'])
        environment = peer.call_args.args[4]
        self.assertEqual(environment['BROWSER'], 'user-original-browser')
        self.assertEqual(environment['LUMA_BROWSER_CONFIG'], '/session/browser')
        self.assertNotIn('LUMA_AUTH_BROWSER_SOCKET', environment)

    def test_missing_personal_auth_never_selects_api_or_infers_other_credentials(self):
        s = session(); s.start = lambda argv: None
        s.call = lambda *args, **kwargs: {'protocolVersion': 1, 'agentCapabilities': {}, 'authMethods': [{'id': 'gemini-api-key'}]}
        with patch('luma_cli.antigravity.shutil.which', return_value='/native/agy_acp_server.par'):
            with self.assertRaisesRegex(Rejected, '未提供 oauth-personal'): s.connect()
        self.assertFalse(s.config.get('creationUnknown')); self.assertFalse(s.data['connected'])

    def test_refused_opener_cannot_be_overridden_by_a_racing_auth_success(self):
        s = session(); s.auth_url_rejected = False
        def call(*args, **kwargs): s.auth_url_rejected = True; return {}
        s.call = call
        with self.assertRaisesRegex(Rejected, '已拒绝打开'): s.authenticate({'authMethods': [{'id': 'oauth-personal'}]})
        self.assertFalse(s.data['requests']); self.assertFalse(s.config.get('creationUnknown'))

    def test_google_opener_is_ephemeral_and_cancel_never_creates_session(self):
        s = session(); finished = threading.Event(); errors = []; calls = []
        url = 'https://accounts.google.com/o/oauth2/auth?state=fixture&redirect_uri=http%3A%2F%2F127.0.0.1%3A45678%2F'
        s.auth_url_rejected = False
        def call(method, params, **kwargs):
            calls.append((method, params)); finished.wait(2); raise ConnectionError('auth transport closed')
        s.call = call
        s.peer = type('P', (), {'close': lambda _: finished.set()})()
        def auth():
            try: s.authenticate({'authMethods': [{'id': 'oauth-personal'}]})
            except Exception as e: errors.append(e)
        thread = threading.Thread(target=auth); thread.start()
        deadline = time.monotonic() + 1
        while not getattr(s, 'authenticating', False) and time.monotonic() < deadline: time.sleep(.01)
        s.open_auth_browser(url)
        self.assertEqual(s.snapshot()['requests'][0]['externalUrl'], url)
        self.assertNotIn(url, json.dumps(s.config)); self.assertNotIn(url, json.dumps(s.pending_requests))
        self.assertFalse(s.data['connected']); self.assertFalse(s.config.get('creationUnknown'))
        self.assertRaises(Rejected, s.answer, {'id': 'antigravity-auth-browser', 'choice': 'open'})
        s.answer({'id': 'antigravity-auth-browser', 'choice': 'cancel'}); thread.join(2)
        self.assertEqual([c[0] for c in calls], ['authenticate'])
        self.assertEqual(s.data['requests'], []); self.assertEqual(str(errors[0]), '已取消 Antigravity 登录')

    def test_browser_helper_uses_real_python_webbrowser_without_launching_a_browser(self):
        opened = []; rejected = []
        relay = BrowserRelay(opened.append, lambda: rejected.append(True))
        url = 'https://accounts.google.com/o/oauth2/v2/auth?redirect_uri=http%3A%2F%2F127.0.0.1%3A45678%2F&state=fixture'
        env = {'PATH': '/usr/bin:/bin', **relay.environment()}
        try:
            command = 'import sys,webbrowser; assert webbrowser.open(sys.argv[1],new=1,autoraise=True)'
            for value in (url, 'https://evil.example/auth'):
                result = subprocess.run([sys.executable, '-I', '-B', '-c', command, value], env=env, capture_output=True, timeout=3)
                self.assertEqual(result.returncode, 0); self.assertEqual(result.stdout, b'')
            deadline = time.monotonic() + 1
            while (not opened or not rejected) and time.monotonic() < deadline: time.sleep(.01)
            self.assertEqual(opened, [url]); self.assertEqual(rejected, [True])
        finally: relay.close()
        self.assertFalse(relay.directory.exists())

    def test_browser_url_only_accepts_google_authorization_and_native_loopback(self):
        valid = 'https://accounts.google.com/o/oauth2/auth?redirect_uri=http%3A%2F%2F127.0.0.1%3A45678%2F'
        self.assertTrue(valid_google_auth_url(valid))
        for value in (valid.replace('https:', 'http:'), valid.replace('accounts.google.com', 'accounts.google.com.evil'),
                      valid.replace('/o/oauth2/auth', '/logout'), valid + '#fragment', valid + '&access_token=secret',
                      valid.replace('127.0.0.1', 'localhost'), valid.replace('45678', '65536'),
                      valid + '&redirect_uri=http%3A%2F%2Fevil.example%2F'):
            self.assertFalse(valid_google_auth_url(value), value)

    def test_real_stdio_auth_waits_for_native_success_and_cancel_is_session_local(self):
        fixture = '''#!/usr/bin/python3
import json,os,pathlib,sys,time,uuid,webbrowser
ready=pathlib.Path(os.environ['LUMA_FIXTURE_AUTH_READY'])
native=str(uuid.uuid4())
for line in sys.stdin:
 f=json.loads(line); method=f['method']; result={}
 if method=='initialize':result={'protocolVersion':1,'authMethods':[{'id':'oauth-personal'}],'agentCapabilities':{'loadSession':True}}
 elif method=='authenticate':
  assert f['params']=={'methodId':'oauth-personal'}
  if not ready.exists():
   assert webbrowser.open('https://accounts.google.com/o/oauth2/auth?state=fixture&redirect_uri=http%3A%2F%2F127.0.0.1%3A45678%2F')
   while not ready.exists():time.sleep(.01)
 elif method=='session/new':result={'sessionId':native,'configOptions':[]}
 else:raise AssertionError(method)
 print(json.dumps({'jsonrpc':'2.0','id':f['id'],'result':result}),flush=True)
'''
        with tempfile.TemporaryDirectory(prefix='luma-acp-auth-') as directory:
            root = pathlib.Path(directory); binary = root/'agy_acp_server.par'
            binary.write_text(fixture); binary.chmod(0o700)
            ready = root/'native-auth-finished'
            manager = Manager(root/'state')
            def wait(predicate):
                end = time.monotonic() + 3
                while not predicate() and time.monotonic() < end: time.sleep(.01)
                self.assertTrue(predicate())
            try:
                with patch.dict(os.environ, {'PATH': directory + ':/usr/bin:/bin', 'LUMA_FIXTURE_AUTH_READY': str(ready)}):
                    first = manager.connect({'id': 'first', 'cli': 'Antigravity', 'cwd': directory, 'nativeProtocol': 'antigravity-acp'}, background=True)
                    wait(lambda: bool(first.data['requests']))
                    self.assertFalse(first.data['connected']); self.assertEqual(first.data['nativeId'], '')
                    self.assertFalse(first.config.get('creationUnknown'))
                    index = json.loads((root/'state/first.json').read_text())
                    self.assertNotIn('externalUrl', json.dumps(index)); self.assertNotIn('accounts.google.com', json.dumps(index))
                    ready.touch()
                    wait(lambda: first.data['connected'])
                    self.assertFalse(first.data['requests']); first_id = first.data['nativeId']
                    second_ready = root/'second-auth-finished'
                    os.environ['LUMA_FIXTURE_AUTH_READY'] = str(second_ready)
                    second = manager.connect({'id': 'second', 'cli': 'Antigravity', 'cwd': directory, 'nativeProtocol': 'antigravity-acp'}, background=True)
                    wait(lambda: bool(second.data['requests']))
                    second.answer({'id': 'antigravity-auth-browser', 'choice': 'cancel'})
                    wait(lambda: not second.data.get('connecting'))
                    self.assertIn('已取消 Antigravity 登录', second.data['error'])
                    self.assertEqual(second.data['nativeId'], ''); self.assertFalse(second.config.get('creationUnknown'))
                    self.assertTrue(first.data['connected']); self.assertEqual(first.data['nativeId'], first_id)
                    self.assertIsNone(first.peer.proc.poll())
            finally:
                for s in manager.sessions.values():
                    if s.peer: s.peer.close()

    def test_native_auth_timeout_or_rejection_closes_unready_peer_and_clears_url(self):
        fixture = '''#!/usr/bin/python3
import json,os,sys,time,webbrowser
for line in sys.stdin:
 f=json.loads(line)
 if f['method']=='initialize':result={'protocolVersion':1,'authMethods':[{'id':'oauth-personal'}],'agentCapabilities':{}}
 elif f['method']=='authenticate':
  assert webbrowser.open('https://accounts.google.com/o/oauth2/auth?redirect_uri=http%3A%2F%2F127.0.0.1%3A45678%2F')
  if os.environ['LUMA_FIXTURE_AUTH_OUTCOME']=='timeout':time.sleep(20)
  print(json.dumps({'jsonrpc':'2.0','id':f['id'],'error':{'code':-32000,'message':'native auth denied'}}),flush=True);continue
 else:raise AssertionError('session creation must not be dispatched')
 print(json.dumps({'jsonrpc':'2.0','id':f['id'],'result':result}),flush=True)
'''
        with tempfile.TemporaryDirectory(prefix='luma-acp-auth-deadline-') as directory:
            binary = pathlib.Path(directory)/'agy_acp_server.par'; binary.write_text(fixture); binary.chmod(0o700)
            for outcome, expected in [('timeout', '5 分钟窗口内确认'), ('rejected', 'native auth denied')]:
                s = session(); s.config['cwd'] = directory; s.argv = [str(binary), '--uid=']; s.auth_timeout = .15
                with patch.dict(os.environ, {'PATH': '/usr/bin:/bin', 'LUMA_FIXTURE_AUTH_OUTCOME': outcome}):
                    with self.assertRaisesRegex(Rejected, expected): s.connect()
                self.assertIsNotNone(s.peer.proc.poll()); self.assertFalse(s.auth_browser.directory.exists())
                self.assertFalse(s.data['requests']); self.assertFalse(s.pending_requests)
                self.assertFalse(s.config.get('creationUnknown')); self.assertEqual(s.data['nativeId'], '')

if __name__ == '__main__': unittest.main()

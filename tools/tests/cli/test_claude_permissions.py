#!/usr/bin/env python3
import copy
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.claude import ClaudeSession, permission_options
from luma_cli.server import Manager
from luma_cli.transport import Rejected

HELP = '''Options:
  --allow-dangerously-skip-permissions  Allow bypass as an option, not the default
  --permission-mode <mode>              Mode
                                        (choices: "acceptEdits", "auto",
                                        "bypassPermissions", "manual",
                                        "dontAsk", "plan")
  --permission-prompts <target>         (choices: "host", "none")
'''
NATIVE = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'

def session(selected='default'):
    saves = []
    config = {'cli': 'Claude Code', 'cwd': '/tmp', 'nativeId': NATIVE}
    if selected is not None: config['selectedPermission'] = selected
    s = ClaudeSession(config, lambda: saves.append(copy.deepcopy(config)))
    s.permission_modes, s.allow_bypass = permission_options(HELP, False)
    s.update(connected=True, permission=selected or 'default')
    return s, saves

class ClaudePermissions(unittest.TestCase):
    def test_help_gates_new_modes_and_root_never_arms_bypass(self):
        modes, bypass = permission_options(HELP, False)
        self.assertEqual([m['id'] for m in modes], ['default', 'acceptEdits', 'auto', 'plan', 'dontAsk', 'bypassPermissions'])
        self.assertTrue(bypass)
        modes, bypass = permission_options(HELP, True)
        self.assertNotIn('bypassPermissions', [m['id'] for m in modes]); self.assertFalse(bypass)
        for output in ('', '--permission-mode <mode> (choices: "default", "acceptEdits", "plan")',
                       '--permission-mode <mode>\n  --other (choices: "auto", "bypassPermissions")'):
            modes, bypass = permission_options(output, False)
            self.assertEqual([m['id'] for m in modes], ['default', 'acceptEdits', 'plan']); self.assertFalse(bypass)

    def test_auto_and_dontask_require_native_ack_before_local_selection(self):
        for mode in ('auto', 'dontAsk'):
            s, saves = session()
            def call(method, params):
                self.assertEqual((method, params), ('set_permission_mode', {'mode': mode}))
                self.assertEqual(s.data['permission'], 'default'); self.assertEqual(s.config['selectedPermission'], 'default')
                self.assertEqual(saves, [])
                return {'mode': mode}
            s.call = call; s.configure({'permission': mode})
            self.assertEqual(s.data['permission'], mode); self.assertEqual(saves[-1]['selectedPermission'], mode)

    def test_legacy_empty_control_ack_still_confirms_supported_setter(self):
        s, saves = session(); s.call = lambda *a: {}
        s.configure({'permission': 'auto'}); self.assertEqual(saves[-1]['selectedPermission'], 'auto')

    def test_native_policy_rejection_keeps_confirmed_mode_and_saved_selection(self):
        s, saves = session()
        def refused(*args): raise Rejected('Auto mode is unavailable for this model or disabled by policy')
        s.call = refused
        self.assertRaisesRegex(Rejected, 'disabled by policy', s.configure, {'permission': 'auto'})
        self.assertEqual(s.data['permission'], 'default'); self.assertEqual(s.config['selectedPermission'], 'default')
        self.assertEqual(saves, [])

    def test_root_refusal_does_not_dispatch_or_change_selection(self):
        s, saves = session(); s.call = lambda *a: self.fail('Must not dispatch a root bypass')
        with patch('luma_cli.claude.os.geteuid', return_value=0):
            self.assertRaisesRegex(Rejected, 'Root/sudo', s.configure, {'permission': 'bypassPermissions'})
        self.assertEqual(s.data['permission'], 'default'); self.assertEqual(saves, [])

    def test_busy_or_unsupported_mode_does_not_dispatch(self):
        s, saves = session(); s.call = lambda *a: self.fail('Must not dispatch')
        s.permission_modes, _ = permission_options('', False)
        self.assertRaises(Rejected, s.configure, {'permission': 'auto'})
        s.update(running=True); self.assertRaises(Rejected, s.configure, {'permission': 'plan'})
        self.assertEqual(saves, [])

    def test_explicit_effective_mode_mismatch_is_not_saved_as_success(self):
        s, saves = session('plan'); s.call = lambda *a: {'mode': 'manual'}
        self.assertRaises(Rejected, s.configure, {'permission': 'auto'})
        self.assertEqual(s.data['permission'], 'default'); self.assertEqual(s.config['selectedPermission'], 'plan')
        self.assertEqual(saves, [])

    def test_lost_reply_is_unknown_unless_native_status_already_confirmed_it(self):
        for notification in (False, True):
            s, saves = session(); calls = []
            def lost(*args):
                calls.append(args)
                if notification: s.event({'type': 'system', 'subtype': 'status', 'session_id': NATIVE, 'permissionMode': 'auto'})
                raise TimeoutError('reply lost')
            s.call = lost
            self.assertRaises(TimeoutError, s.configure, {'permission': 'auto'})
            self.assertEqual(s.data['permission'], 'auto' if notification else '')
            self.assertEqual(s.config['selectedPermission'], 'default'); self.assertEqual(saves, []); self.assertEqual(len(calls), 1)

    def test_save_failure_restores_local_config_but_reports_effective_native_mode(self):
        for previous in ('plan', None):
            s, _ = session(previous); s.call = lambda *a: {'mode': 'auto'}
            def failed_save(): raise OSError('fixture disk failure')
            s.save = failed_save
            self.assertRaisesRegex(RuntimeError, '已在当前 Claude 进程生效', s.configure, {'permission': 'auto'})
            self.assertEqual(s.data['permission'], 'auto'); self.assertEqual(s.config.get('selectedPermission'), previous)

    def test_auto_never_answers_native_requests_on_users_behalf(self):
        s, _ = session('auto'); s.peer = type('Peer', (), {'send': lambda *a: self.fail('No automatic approvals')})()
        s.permission_request({'request_id': 'native', 'request': {'tool_name': 'Bash', 'input': {'command': 'true'}}})
        self.assertEqual(s.data['requests'][0]['id'], 'native')

    def connect_fixture(self, tmp, selected=None, actual='default', is_root=True, help_error=False, set_result=None):
        s, saves = session(selected); s.update(connected=False); calls = []; launched = []
        s.history_path = lambda: pathlib.Path(tmp) / 'native.jsonl'
        s.load_history = lambda: None
        s.start = lambda argv: launched.extend(argv)
        def call(method, params=None):
            calls.append((method, params))
            if method == 'initialize': return {'current_permission_mode': actual, 'models': []}
            if method == 'set_permission_mode':
                if isinstance(set_result, Exception): raise set_result
                return set_result if set_result is not None else {'mode': params['mode']}
            return {}
        s.call = call
        result = subprocess.CompletedProcess(['claude', '--help'], 0, HELP, '')
        with patch('luma_cli.claude.os.geteuid', return_value=0 if is_root else 1000), \
             patch('luma_cli.claude.subprocess.run', side_effect=OSError('help unavailable') if help_error else None, return_value=result):
            s.connect()
        return s, launched, calls, saves

    def test_new_default_session_does_not_select_auto_or_bypass(self):
        with tempfile.TemporaryDirectory() as tmp:
            for root in (False, True):
                s, argv, calls, saves = self.connect_fixture(tmp, is_root=root)
                self.assertNotIn('--permission-mode', argv)
                self.assertEqual('--allow-dangerously-skip-permissions' in argv, not root)
                self.assertNotIn('selectedPermission', s.config); self.assertEqual(s.data['permission'], 'default')
                self.assertNotIn('set_permission_mode', [c[0] for c in calls])

    def test_help_failure_preserves_existing_connection_and_modes(self):
        with tempfile.TemporaryDirectory() as tmp:
            s, argv, _, _ = self.connect_fixture(tmp, help_error=True)
            self.assertTrue(s.data['connected']); self.assertNotIn('--allow-dangerously-skip-permissions', argv)
            self.assertEqual([m['id'] for m in s.data['permissions']], ['default', 'acceptEdits', 'plan'])

    def test_startup_restores_explicit_auto_and_checks_native_actual_mode(self):
        with tempfile.TemporaryDirectory() as tmp:
            for actual in ('auto', 'default', ''):
                s, argv, calls, _ = self.connect_fixture(tmp, selected='auto', actual=actual)
                self.assertEqual(argv[argv.index('--permission-mode') + 1], 'auto')
                self.assertEqual(s.data['permission'], 'auto'); self.assertEqual(s.data['nativeId'], NATIVE)
                self.assertEqual(sum(method == 'set_permission_mode' for method, _ in calls), 0 if actual == 'auto' else 1)

    def test_startup_fallback_is_not_mistaken_for_restored_auto(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertRaisesRegex(Rejected, 'native policy', self.connect_fixture, tmp,
                selected='auto', actual='default', set_result=Rejected('native policy refused auto'))

    def test_nonroot_bypass_is_armed_and_restored_only_as_selected_mode(self):
        with tempfile.TemporaryDirectory() as tmp:
            s, argv, calls, _ = self.connect_fixture(tmp, selected='bypassPermissions', actual='bypassPermissions', is_root=False)
            self.assertIn('--allow-dangerously-skip-permissions', argv)
            self.assertEqual(argv[argv.index('--permission-mode') + 1], 'bypassPermissions')
            self.assertEqual(s.data['permission'], 'bypassPermissions')
            self.assertNotIn('set_permission_mode', [c[0] for c in calls])

    def test_root_cannot_relaunch_cached_bypass_or_silently_downgrade(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertRaisesRegex(Rejected, 'Root/sudo', self.connect_fixture, tmp, selected='bypassPermissions')

    def test_manager_roundtrip_uses_exact_control_ids_and_session_local_persistence(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = pathlib.Path(tmp); binary = base / 'bin'; binary.mkdir()
            fixture = binary / 'claude'
            fixture.write_text('#!' + sys.executable + '\n' + '''import json,sys
HELP = ''' + repr(HELP) + '''
args=sys.argv[1:]
if args==['--help']: print(HELP); sys.exit(0)
mode=args[args.index('--permission-mode')+1] if '--permission-mode' in args else 'default'
for line in sys.stdin:
 f=json.loads(line); req=f['request']; result={}; error=None
 if req['subtype']=='initialize': result={'models':[],'current_permission_mode':mode}
 elif req['subtype']=='set_permission_mode':
  value=req['mode']
  if value=='bypassPermissions' and '--allow-dangerously-skip-permissions' not in args: error='bypass_not_launched'
  else: mode=value; result={'mode':mode}
 response={'subtype':'error','error':error} if error else {'subtype':'success','response':result}
 print(json.dumps({'type':'control_response','response':dict(response,request_id=f['request_id'])}),flush=True)
''')
            fixture.chmod(0o700)
            config = {'id': 'local', 'cli': 'Claude Code', 'cwd': tmp}
            with patch.dict(os.environ, {'PATH': str(binary), 'HOME': tmp, 'CLAUDE_CONFIG_DIR': str(base / '.claude')}, clear=True), \
                 patch('luma_cli.claude.os.geteuid', return_value=1000):
                s = Manager(base / 'sessions').connect(config)
                try:
                    self.assertEqual(s.data['permission'], 'default'); self.assertNotIn('selectedPermission', s.config)
                    native = s.data['nativeId']
                    for mode in ('auto', 'bypassPermissions'):
                        s.configure({'permission': mode}); self.assertEqual(s.data['permission'], mode)
                    saved = json.loads((base / 'sessions/local.json').read_text())
                    self.assertEqual(saved['selectedPermission'], 'bypassPermissions')
                    self.assertFalse((base / '.claude/settings.json').exists())
                finally: s.peer.close()
                restored = Manager(base / 'sessions').connect(config)
                try:
                    self.assertEqual(restored.data['nativeId'], native)
                    self.assertEqual(restored.data['permission'], 'bypassPermissions')
                    self.assertIn('--allow-dangerously-skip-permissions', restored.peer.proc.args)
                finally: restored.peer.close()

if __name__ == '__main__': unittest.main(verbosity=2)

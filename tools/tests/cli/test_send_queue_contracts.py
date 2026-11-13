#!/usr/bin/env python3
import pathlib
import sys
import threading
import time
import unittest

sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.acp import AcpSession
from luma_cli.kimi import KimiSession
from luma_cli.antigravity import AntigravitySession
from luma_cli.transport import Rejected

def option(value='default'):
    return {'id': 'opaque-mode', 'category': 'mode', 'type': 'select', 'currentValue': value,
            'options': [{'value': 'default', 'name': 'Native default'}, {'value': 'plan', 'name': 'Native plan'}]}

class SendQueueContracts(unittest.TestCase):
    def session(self, cls):
        s = cls({'cli': 'fixture', 'cwd': '/tmp', 'nativeId': 'native', 'receipts': {}}, lambda: None)
        s.chunk_kind = None; s.chunk_id = None; s.current_submission = None; s.native_caps = {}
        s.update(connected=True, capabilities={'queue': False, 'stop': True})
        s.apply_options([option()])
        return s

    def test_acp_setting_ack_must_confirm_requested_opaque_value(self):
        for cls in (AcpSession, KimiSession, AntigravitySession):
            for returned in ([], [option('default')]):
                with self.subTest(adapter=cls.__name__, returned=returned):
                    s = self.session(cls); calls = []
                    s.call = lambda *a, **kw: calls.append(a) or {'configOptions': returned}
                    with self.assertRaisesRegex(Rejected, '尚未确认所选设置'):
                        s.configure({'permission': 'plan'})
                    self.assertNotEqual(s.data['permission'], 'plan'); self.assertEqual(len(calls), 1)

    def test_acp_setting_native_rejection_and_timeout_are_not_swallowed_or_retried(self):
        for error in (Rejected('native denied'), TimeoutError('native ACK timed out')):
            s = self.session(KimiSession); calls = []
            def fail(*a, **kw): calls.append(a); raise error
            s.call = fail
            with self.assertRaisesRegex(type(error), str(error)): s.configure({'permission': 'plan'})
            self.assertEqual(s.data['permission'], 'default'); self.assertEqual(len(calls), 1)

    def test_acp_stop_settle_then_native_permission_and_next_send_once(self):
        for cls in (AcpSession, KimiSession, AntigravitySession):
            with self.subTest(adapter=cls.__name__):
                s = self.session(cls); cancel = threading.Event(); calls = []
                s.peer = type('Peer', (), {'send': lambda _, f: cancel.set() if f.get('method') == 'session/cancel' else None})()
                def call(method, params, **kw):
                    calls.append((method, params))
                    if method == 'session/set_config_option': return {'configOptions': [option(params['value'])]}
                    if params['prompt'][0]['text'] == 'first':
                        self.assertTrue(cancel.wait(2)); return {'stopReason': 'cancelled'}
                    s.event({'method': 'session/update', 'params': {'sessionId': 'native', 'update':
                        {'sessionUpdate': 'user_message_chunk', 'content': {'type': 'text', 'text': 'next'}}}})
                    return {'stopReason': 'end_turn'}
                s.call = call; s.submit({'submissionId': 'first', 'text': 'first'})
                with self.assertRaises(Rejected): s.submit({'submissionId': 'busy', 'text': 'must not queue'})
                self.assertNotIn('busy', s.config['receipts'])
                s.stop()
                end = time.monotonic() + 2
                while s.data['running'] and time.monotonic() < end: time.sleep(.01)
                self.assertFalse(s.data['running'])
                s.configure({'permission': 'plan'}); self.assertEqual(s.data['permission'], 'plan')
                s.submit({'submissionId': 'next', 'text': 'next'})
                while s.data['running'] and time.monotonic() < end: time.sleep(.01)
                self.assertFalse(s.data['running']); self.assertEqual(s.config['receipts']['next'], 'accepted')
                self.assertEqual([r['text'] for r in s.rows.values() if r.get('role') == 'user'], ['first', 'next'])
                self.assertEqual(s.data['queue'], [])
                self.assertEqual(len([c for c in calls if c[0] == 'session/prompt']), 2)

if __name__ == '__main__': unittest.main(verbosity=2)

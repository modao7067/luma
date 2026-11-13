#!/usr/bin/env python3
import copy
import pathlib
import sys
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[3] / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.acp import AcpSession
from luma_cli.transport import Rejected

def option(ident, category, current, values):
    return {'id': ident, 'category': category, 'type': 'select', 'currentValue': current,
            'options': [{'value': v, 'name': v, 'description': 'native ' + v} for v in values]}

class SharedAcpTest(unittest.TestCase):
    def test_a_later_chunk_cannot_rewrite_an_old_cancelled_or_failed_terminal_row(self):
        for status in ('cancelled', 'failed', 'unknown', 'completed'):
            s = AcpSession({'cli': 'fixture', 'cwd': '/tmp'}, lambda: None)
            s.chunk_id = 'old'; s.chunk_kind = None; s.current_submission = None
            s.row({'id': 'old', 'role': 'reasoning', 'text': 'old thought', 'status': status})
            s.event({'method': 'session/update', 'params': {'update': {
                'sessionUpdate': 'agent_message_chunk', 'content': {'type': 'text', 'text': 'next response'}}}})
            self.assertEqual(s.rows['old']['status'], status)
            self.assertEqual(s.rows[s.chunk_id]['status'], 'running')

    def test_permission_category_is_adapter_scoped_not_a_brand_default(self):
        for categories, expected, value in [(('mode',), 'mode', 'agent'), (('permissions',), 'allow_all', 'off')]:
            s = AcpSession({'cli': 'fixture', 'cwd': '/tmp'}, lambda: None)
            s.permission_categories = categories; s.native_caps = {}
            options = [option('mode', 'mode', 'agent', ['agent', 'plan']), option('allow_all', 'permissions', 'off', ['off', 'on'])]
            s.apply_options(options)
            self.assertEqual(s.data['permission'], value)
            self.assertTrue(all(p['description'].startswith('native ') for p in s.data['permissions']))
            calls = []
            def call(method, params):
                calls.append((method, params))
                updated = copy.deepcopy(options)
                next(o for o in updated if o['id'] == params['configId'])['currentValue'] = params['value']
                return {'configOptions': updated}
            s.call = call
            selected = 'plan' if expected == 'mode' else 'on'
            s.configure({'permission': selected})
            self.assertEqual(calls[0][1]['configId'], expected)
            self.assertEqual(s.data['permission'], selected)
        empty = AcpSession({'cli': 'fixture', 'cwd': '/tmp'}, lambda: None)
        empty.native_caps = {}; empty.apply_options([])
        self.assertEqual(empty.data['permissions'], []); self.assertEqual(empty.data['permission'], '')

    def test_initial_model_is_explicit_new_only_and_native_acknowledged(self):
        class OptIn(AcpSession): desired_model_on_create = True
        for cls, native, expected in [(OptIn, None, True), (OptIn, 'native', False), (AcpSession, None, False)]:
            config = {'cli': 'fixture', 'cwd': '/tmp', 'model': 'requested'}
            if native: config['nativeId'] = native
            s = cls(config, lambda: None); calls = []; s.start = lambda argv: None
            def call(method, params):
                calls.append((method, params))
                if method == 'initialize': return {'protocolVersion': 1, 'agentCapabilities': {'loadSession': True}}
                if method in ('session/new', 'session/load'):
                    return {'sessionId': 'native', 'configOptions': [option('model', 'model', 'default', ['default', 'requested'])]}
                if method == 'session/set_config_option':
                    return {'configOptions': [option('model', 'model', params['value'], ['default', 'requested'])]}
                raise AssertionError(method)
            s.call = call; s.connect()
            changes = [p for m, p in calls if m == 'session/set_config_option']
            self.assertEqual(bool(changes), expected)
            self.assertEqual(s.data['model'], 'requested' if expected else 'default')
            self.assertNotIn('mode', {p.get('configId') for p in changes})

    def test_failed_model_ack_preserves_the_created_identity_without_a_second_create(self):
        class OptIn(AcpSession): desired_model_on_create = True
        s = OptIn({'cli': 'fixture', 'cwd': '/tmp', 'model': 'requested'}, lambda: None)
        s.start = lambda argv: None; calls = []
        def call(method, params):
            calls.append(method)
            if method == 'initialize': return {'protocolVersion': 1, 'agentCapabilities': {'loadSession': True}}
            return {'sessionId': 'native', 'configOptions': [option('model', 'model', 'default', ['default', 'requested'])]}
        s.call = call
        with self.assertRaisesRegex(Rejected, '尚未确认'): s.connect()
        self.assertEqual(s.config['nativeId'], 'native'); self.assertFalse(s.config['creationUnknown'])
        s.connect()
        self.assertEqual(calls.count('session/new'), 1); self.assertEqual(calls.count('session/load'), 1)

if __name__ == '__main__': unittest.main()

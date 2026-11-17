#!/usr/bin/env python3
import copy
import hashlib
import json
import os
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

sys.dont_write_bytecode = True
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[3] / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.acp import AcpSession
from luma_cli.antigravity import AntigravitySession
from luma_cli.kimi import KimiSession
from luma_cli.transport import Rejected

def option(ident, category, current, values):
    return {'id': ident, 'category': category, 'type': 'select', 'currentValue': current,
            'options': [{'value': v, 'name': v, 'description': 'native ' + v} for v in values]}

class SharedAcpTest(unittest.TestCase):
    def test_antigravity_parent_does_not_discard_additional_mode_groups(self):
        s = AntigravitySession({'cli': 'Antigravity', 'cwd': '/tmp'}, lambda: None)
        s.native_caps = {}
        s.apply_options([option('mode', 'mode', 'default', ['default', 'plan']), option('review', 'mode', 'ask', ['ask', 'allow'])])
        self.assertEqual([p['id'] for p in s.data['permissions']], ['default', 'plan', 'ask', 'allow'])

    def test_rejected_group_change_preserves_both_native_selections(self):
        s = AcpSession({'cli': 'fixture', 'cwd': '/tmp', 'nativeId': 'native'}, lambda: None)
        s.native_caps = {}
        options = [option('permissions', 'mode', 'ask', ['ask', 'allow']), option('workflow', 'mode', 'build', ['build', 'plan'])]
        s.apply_options(options)
        s.call = lambda method, params: {'configOptions': copy.deepcopy(options)}
        with self.assertRaises(Rejected): s.configure({'permission': 'plan', 'permissionConfigId': 'workflow'})
        self.assertEqual([p['id'] for p in s.data['permissions'] if p['selected']], ['ask', 'build'])

    def test_multiple_mode_groups_preserve_choices_selection_and_exact_dispatch(self):
        s = AcpSession({'cli': 'fixture', 'cwd': '/tmp', 'nativeId': 'native'}, lambda: None)
        s.native_caps = {}
        options = [option('permissions', 'mode', 'ask', ['ask', 'allow']), option('workflow', 'mode', 'build', ['build', 'plan'])]
        s.apply_options(options)
        self.assertEqual([p['id'] for p in s.data['permissions']], ['ask', 'allow', 'build', 'plan'])
        self.assertEqual([(p['configId'], p['id']) for p in s.data['permissions'] if p['selected']], [('permissions', 'ask'), ('workflow', 'build')])
        calls = []
        def call(method, params):
            calls.append((method, params))
            next(o for o in options if o['id'] == params['configId'])['currentValue'] = params['value']
            return {'configOptions': copy.deepcopy(options)}
        s.call = call
        s.configure({'permission': 'plan', 'permissionConfigId': 'workflow'})
        self.assertEqual(calls[-1][1], {'sessionId': 'native', 'configId': 'workflow', 'value': 'plan'})
        self.assertEqual([(p['configId'], p['id']) for p in s.data['permissions'] if p['selected']], [('permissions', 'ask'), ('workflow', 'plan')])
        s.configure({'permission': 'allow'})
        self.assertEqual(calls[-1][1]['configId'], 'permissions')
        self.assertEqual([(p['configId'], p['id']) for p in s.data['permissions'] if p['selected']], [('permissions', 'allow'), ('workflow', 'plan')])
        s.current_submission = None
        s.event({'method': 'session/update', 'params': {'sessionId': 'native', 'update': {'sessionUpdate': 'current_mode_update', 'currentModeId': 'build'}}})
        self.assertEqual([(p['configId'], p['id']) for p in s.data['permissions'] if p['selected']], [('permissions', 'allow'), ('workflow', 'build')])

    def test_duplicate_native_values_need_group_identity_without_changing_native_ids(self):
        s = AcpSession({'cli': 'fixture', 'cwd': '/tmp', 'nativeId': 'native'}, lambda: None)
        s.native_caps = {}
        options = [option('first', 'mode', 'off', ['off', 'on']), option('second', 'mode', 'off', ['off', 'on'])]
        s.apply_options(options)
        calls = []
        def call(method, params):
            calls.append(params)
            updated = copy.deepcopy(options)
            next(o for o in updated if o['id'] == params['configId'])['currentValue'] = params['value']
            return {'configOptions': updated}
        s.call = call
        with self.assertRaises(Rejected): s.configure({'permission': 'on'})
        with self.assertRaises(Rejected): s.configure({'permission': 'on', 'permissionConfigId': 'missing'})
        self.assertEqual(calls, [])
        s.configure({'permission': 'on', 'permissionConfigId': 'second'})
        self.assertEqual(calls[-1]['configId'], 'second')
        self.assertEqual([p['id'] for p in s.data['permissions'] if p['selected']], ['off', 'on'])
        s.current_submission = None
        s.event({'method': 'session/update', 'params': {'sessionId': 'native', 'update': {'sessionUpdate': 'current_mode_update', 'currentModeId': 'on'}}})
        self.assertEqual(s.data['permission'], 'off')

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

    def test_permission_request_recovers_streamed_outcome_json_and_strips_truncated_summary(self):
        s = AcpSession({'cli': 'Kimi Code', 'cwd': '/tmp', 'nativeId': 'native'}, lambda: None)
        s.chunk_id = None; s.chunk_kind = None; s.current_submission = None
        s.event({'method': 'session/update', 'params': {'sessionId': 'native', 'update': {
            'sessionUpdate': 'tool_call', 'toolCallId': 'call-1', 'title': 'Bash', 'kind': 'execute'}}})
        s.event({'method': 'session/update', 'params': {'sessionId': 'native', 'update': {
            'sessionUpdate': 'tool_call_update', 'toolCallId': 'call-1',
            'title': 'Running: test ! -e /tmp/x && echo ok',
            'content': [{'type': 'content', 'content': {'type': 'text', 'text': json.dumps({
                'command': 'test ! -e /tmp/x && echo ok', 'description': 'Check x'})}}]}}})
        s.event({'id': 'perm-1', 'method': 'session/request_permission', 'params': {
            'sessionId': 'native',
            'toolCall': {'toolCallId': 'call-1', 'title': 'Bash',
                         'content': [{'type': 'content', 'content': {'type': 'text', 'text': 'Requesting approval to Running: test ! -e /tmp/x &…'}}]},
            'options': [{'optionId': 'approve', 'name': 'Approve once'},
                        {'optionId': 'approve_always', 'name': 'Approve for this session'},
                        {'optionId': 'reject', 'name': 'Reject'}]}})
        self.assertEqual(s.rows['tool-call-1']['toolName'], 'Bash')
        self.assertEqual(len(s.data['requests']), 1)
        body = s.data['requests'][0]['body']
        self.assertNotIn('Requesting approval to ', body)
        self.assertEqual(json.loads(body), {'command': 'test ! -e /tmp/x && echo ok', 'description': 'Check x'})

    def test_permission_request_formats_diff_block_and_synthesizes_path_header(self):
        s = AcpSession({'cli': 'Kimi Code', 'cwd': '/tmp', 'nativeId': 'native'}, lambda: None)
        s.event({'id': 'perm-2', 'method': 'session/request_permission', 'params': {
            'sessionId': 'native',
            'toolCall': {'toolCallId': 'call-2', 'title': 'Edit /tmp/demo.py',
                         'content': [{'type': 'diff', 'path': '/tmp/demo.py', 'oldText': 'x = 1\n', 'newText': 'x = 2\n'}]},
            'options': [{'optionId': 'approve', 'name': 'Approve once'}, {'optionId': 'reject', 'name': 'Reject'}]}})
        first_line, tail = s.data['requests'][0]['body'].split('\n', 1)
        self.assertEqual(json.loads(first_line), {'path': '/tmp/demo.py'})
        self.assertIn('-x = 1', tail)
        self.assertIn('+x = 2', tail)
        self.assertNotIn('"type": "diff"', tail)

    def test_kimi_subagents_capture_live_and_disk_wire_without_ghost_entries(self):
        with tempfile.TemporaryDirectory() as td:
            cwd = os.path.join(td, 'proj')
            os.makedirs(cwd)
            digest = hashlib.sha256(cwd.encode('utf-8')).hexdigest()[:12]
            sdir = pathlib.Path(td) / 'sessions' / f'wd_proj_{digest}' / 'session_native-1'
            main_dir = sdir / 'agents' / 'main'
            child_dir = sdir / 'agents' / 'agent-0'
            main_dir.mkdir(parents=True)
            child_dir.mkdir(parents=True)
            (sdir / 'state.json').write_text(json.dumps({
                'id': 'session_native-1',
                'agents': {
                    'main': {'type': 'main'},
                    'agent-0': {'type': 'sub', 'parentAgentId': 'main', 'labels': {'parentAgentId': 'main', 'profileName': 'explore'}},
                },
            }), encoding='utf-8')
            with mock.patch.dict(os.environ, {'KIMI_CODE_HOME': td}):
                s = KimiSession({'cli': 'Kimi Code', 'cwd': cwd, 'nativeId': 'native-1'}, lambda: None)
                s.chunk_id = None; s.chunk_kind = None; s.current_submission = None
                s.data['connected'] = True
                s.event({'method': 'session/update', 'params': {'sessionId': 'native-1', 'update': {
                    'sessionUpdate': 'tool_call', 'toolCallId': 'Agent_0_abc', 'title': 'Agent', 'kind': 'other'}}})
                s.event({'method': 'session/update', 'params': {'sessionId': 'native-1', 'update': {
                    'sessionUpdate': 'tool_call_update', 'toolCallId': 'Agent_0_abc',
                    'title': 'Launching explore agent: First task',
                    'content': [{'type': 'content', 'content': {'type': 'text', 'text': json.dumps({
                        'description': 'First task', 'prompt': 'Compute 17*23', 'subagent_type': 'explore', 'run_in_background': True})}}]}}})
                self.assertEqual(s.rows['tool-Agent_0_abc']['toolName'], 'Agent')
                self.assertIn('pending:Agent_0_abc', s.subagents)
                (main_dir / 'wire.jsonl').write_text(json.dumps({
                    'type': 'subagent.spawned', 'subagentId': 'agent-0', 'subagentName': 'explore',
                    'parentToolCallId': 'Agent_0_abc', 'description': 'First task', 'model': 'kimi-k3-test',
                }) + '\n', encoding='utf-8')
                (child_dir / 'wire.jsonl').write_text('\n'.join([
                    json.dumps({'type': 'profile.bind', 'agentId': 'agent-0', 'modelAlias': 'kimi-k3-test'}),
                    json.dumps({'type': 'turn.prompt', 'agentId': 'agent-0', 'input': [{'type': 'text', 'text': '<git-context>\ndirty\n</git-context>\n\nCompute 17*23'}]}),
                    json.dumps({'type': 'agent.turn.started', 'turnId': 0}),
                    json.dumps({'type': 'llm.request', 'agentId': 'agent-0', 'modelAlias': 'kimi-k3-test'}),
                    json.dumps({'type': 'context.append_loop_event', 'event': {'type': 'content.part', 'uuid': 'u1', 'stepUuid': 's1', 'part': {'type': 'think', 'think': '17*23=391'}}}),
                    json.dumps({'type': 'context.append_loop_event', 'event': {'type': 'tool.call', 'toolCallId': 'Bash_0', 'name': 'Bash', 'args': {'command': 'echo 391'}, 'display': {'kind': 'command', 'command': 'echo 391'}}}),
                ]) + '\n', encoding='utf-8')
                s.event({'method': 'session/update', 'params': {'sessionId': 'native-1', 'update': {
                    'sessionUpdate': 'tool_call_update', 'toolCallId': 'Agent_0_abc', 'status': 'completed',
                    'rawOutput': 'task_id: t1\nstatus: running\nagent_id: agent-0\nactual_subagent_type: explore\n'}}})
                self.assertNotIn('pending:Agent_0_abc', s.subagents)
                agent = s.subagents['agent-0']
                self.assertEqual(agent['itemId'], 'tool-Agent_0_abc')
                self.assertEqual(agent['status'], 'running')
                self.assertEqual(agent['role'], 'explore')
                self.assertEqual(agent['name'], 'First task')
                self.assertEqual(agent['task'], 'Compute 17*23')
                self.assertEqual(agent['model'], 'kimi-k3-test')
                self.assertEqual([m['role'] for m in agent['messages']], ['user', 'tool', 'tool'])
                self.assertEqual(agent['messages'][2]['toolKind'], 'execute')
                with open(child_dir / 'wire.jsonl', 'a', encoding='utf-8') as f:
                    f.write('\n'.join([
                        json.dumps({'type': 'context.append_loop_event', 'event': {'type': 'tool.result', 'toolCallId': 'Bash_0', 'result': {'output': '391', 'isError': False}}}),
                        json.dumps({'type': 'context.append_loop_event', 'event': {'type': 'content.part', 'uuid': 'u2', 'stepUuid': 's2', 'part': {'type': 'text', 'text': '391'}}}),
                        json.dumps({'type': 'turn.ended', 'agentId': 'agent-0', 'turnId': 0, 'reason': 'completed'}),
                    ]) + '\n')
                with open(main_dir / 'wire.jsonl', 'a', encoding='utf-8') as f:
                    f.write(json.dumps({'type': 'subagent.completed', 'subagentId': 'agent-0', 'resultSummary': '391'}) + '\n')
                s._sync_subagents(force=True)
                agent = s.subagents['agent-0']
                self.assertEqual(agent['status'], 'succeeded')
                self.assertEqual(agent['detail'], '391')
                self.assertEqual(agent['messages'][2]['status'], 'completed')
                self.assertEqual(agent['messages'][3]['text'], '391')

if __name__ == '__main__': unittest.main()

#!/usr/bin/env python3
import json, pathlib, sys, unittest
sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.metrics import *
from luma_cli.acp import AcpSession
from luma_cli.claude import ClaudeSession
from luma_cli.kimi import KimiSession
from luma_cli.dsh import DshSession
from luma_cli.qoder import QoderSession

def session(cls):
    s = cls({'cli': 'fixture', 'cwd': '/tmp', 'receipts': {}, 'nativeId': 'parent'}, lambda: None)
    s.current_submission = None; s.chunk_kind = None; s.chunk_id = None
    s.stream_id = None; s.stream_blocks = {}; s.finalized_blocks = set()
    return s

class MetricsTest(unittest.TestCase):
    def test_invalid_and_missing_are_not_zero(self):
        for value in (None, True, False, '', '4', -1, float('inf'), float('nan')):
            self.assertIsNone(number(value))
        self.assertEqual(acp_usage({}), [])
        self.assertEqual(acp_usage({'used': None, 'size': 200000, 'cost': {'amount': 1}}), [])
        self.assertEqual(acp_usage({'used': 12, 'size': 0}), [row('上下文', '12')])

    def test_real_zero_and_overfull_are_not_hidden_or_clamped(self):
        self.assertEqual(context(0, 100), [row('上下文', '0 / 100 · 0.0%')])
        self.assertEqual(context(120, 100), [row('上下文', '120 / 100 · 120.0%')])
        self.assertEqual(cache_ratio(0, 100, '缓存'), [row('缓存', '0 / 100 · 0.0%')])
        self.assertEqual(cache_ratio(101, 100, '缓存'), [])
        self.assertEqual(cache_ratio(0, 0, '缓存'), [])

    def test_acp_providers_use_atomic_native_context_not_cumulative_usage(self):
        for cls in (AcpSession, DshSession, KimiSession, QoderSession):
            s = session(cls)
            frame = {'method': 'session/update', 'params': {'sessionId': 'parent', 'update':
                     {'sessionUpdate': 'usage_update', 'used': 1234, 'size': 200000, 'cost': {'amount': 0.125, 'currency': 'EUR'}}}}
            s.event(frame); expected = s.data['metrics']
            self.assertEqual(expected, [row('上下文', '1234 / 200000 · 0.6%'), row('CLI 记录费用 · EUR', '0.125')])
            s.event(frame); self.assertEqual(s.data['metrics'], expected)
            s.event({'method': 'session/update', 'params': {'sessionId': 'other', 'update': {'sessionUpdate': 'usage_update', 'used': 1, 'size': 1}}})
            self.assertEqual(s.data['metrics'], expected)
            s.apply_usage({'used': None, 'size': 200000}); self.assertEqual(s.data['metrics'], [])

    def test_acp_cache_rate_uses_disjoint_native_counters(self):
        self.assertEqual(acp_usage({'used': 1000, 'size': 200000, 'input': 10, 'cacheRead': 90, 'cacheWrite': 0}),
                         [row('上下文', '1000 / 200000 · 0.5%'), row('缓存命中', '90 / 100 · 90.0%')])
        self.assertEqual(acp_usage({'used': 1, 'size': 100, 'cacheRead': 5}),
                         [row('上下文', '1 / 100 · 1.0%')])

    def test_dsh_private_cache_notification_updates_the_current_usage_row(self):
        s = session(DshSession)
        s.apply_usage({'used': 1000, 'size': 200000})
        self.assertEqual(s.data['metrics'], [row('上下文', '1000 / 200000 · 0.5%')])
        s.event({'method': 'luma/usage/cache', 'params': {'sessionId': 'parent', 'input': 10, 'cacheRead': 90, 'cacheWrite': 0}})
        self.assertEqual(s.data['metrics'], [row('上下文', '1000 / 200000 · 0.5%'), row('缓存命中', '90 / 100 · 90.0%')])
        s.apply_usage({'used': 1010, 'size': 200000})
        self.assertEqual(s.data['metrics'], [row('上下文', '1010 / 200000 · 0.5%'), row('缓存命中', '90 / 100 · 90.0%')])

    def test_acp_model_change_invalidates_old_context_without_repricing_cost(self):
        s = session(AcpSession); s.native_caps = {}; s.data['model'] = 'old'
        s.apply_usage({'used': 90000, 'size': 100000, 'cost': {'amount': .2, 'currency': 'USD'}})
        s.apply_options([{'id':'model','category':'model','type':'select','currentValue':'new','options':[{'value':'new','name':'New'}]}])
        self.assertEqual(s.data['metrics'], [row('CLI 记录费用 · USD', '0.2')])
        s.apply_usage({'used': 5000, 'size': 200000})
        self.assertEqual(s.data['metrics'], [row('上下文', '5000 / 200000 · 2.5%')])
        self.assertEqual(session(AcpSession).data['metrics'], [])

    def test_pi_disjoint_cache_scope(self):
        stats = {'tokens': {'input': 1000, 'cacheRead': 6000, 'cacheWrite': 3000, 'output': 99999, 'reasoning': 90},
                 'contextUsage': {'tokens': 5000, 'contextWindow': 100000, 'percent': 5}, 'cost': .25}
        rows = pi_usage(stats, {})
        self.assertIn(row('累计 输入缓存命中', '6000 / 10000 · 60.0%'), rows)
        self.assertEqual(rows[0], row('上下文', '5000 / 100000 · 5.0%'))
        self.assertEqual(rows, pi_usage(stats, {}))

    def test_pi_compaction_null_does_not_fall_back_to_stale_state(self):
        stats = {'contextUsage': {'tokens': None, 'contextWindow': 200000, 'percent': None}}
        state = {'contextUsage': {'tokens': 190000, 'contextWindow': 200000, 'percent': 95}}
        self.assertEqual(pi_usage(stats, state), [])
        self.assertEqual(pi_usage({'contextUsage': None}, state), [])
        self.assertEqual(pi_usage({}, state)[0], row('上下文', '190000 / 200000 · 95.0%'))

    def test_pi_partial_counters_cannot_invent_cache_hit_rate(self):
        self.assertEqual(pi_usage({'tokens': {'input': 3, 'cacheRead': 2}}, {}), [])

    def test_claude_input_only_context_exact_model_and_result_scope(self):
        message = {'model': 'm', 'usage': {'input_tokens': 100, 'cache_read_input_tokens': 600, 'cache_creation_input_tokens': 300, 'output_tokens': 99999}}
        self.assertEqual(claude_context(message, {'m': {'contextWindow': 20000}}), [row('上下文', '1000 / 20000 · 5.0%')])
        self.assertEqual(claude_context(message, {'other': {'contextWindow': 20000}}), [])
        result = {'usage': message['usage'], 'total_cost_usd': .25}
        self.assertEqual(claude_result(result)[0], row('本回合输入缓存命中', '600 / 1000 · 60.0%'))
        self.assertIn(row('CLI 累计估算 · USD', '0.25'), claude_result(result))

    def test_claude_placeholder_and_compaction_do_not_fake_zero_bill_or_stale_context(self):
        s = session(ClaudeSession)
        s.project({'type': 'assistant', 'message': {'role': 'assistant', 'model': 'm', 'id': 'a', 'content': [],
                  'usage': {'input_tokens': 100, 'cache_read_input_tokens': 600, 'cache_creation_input_tokens': 300}}})
        result = {'usage': {'input_tokens': 100, 'output_tokens': 20, 'cache_read_input_tokens': 600, 'cache_creation_input_tokens': 300},
                  'modelUsage': {'m': {'contextWindow': 20000}}, 'total_cost_usd': .5}
        s.result_usage(result); expected = s.data['metrics']
        s.result_usage(result); self.assertEqual(s.data['metrics'], expected)
        s.result_usage({'subtype': 'error_during_execution', 'usage': {'input_tokens': 0}, 'modelUsage': {}, 'total_cost_usd': 0})
        self.assertEqual(s.data['metrics'], expected)
        s.event({'type': 'system', 'subtype': 'compact_boundary'})
        self.assertFalse(any(r['label']=='上下文' for r in s.data['metrics']))
        self.assertIn(row('CLI 累计估算 · USD', '0.5'), s.data['metrics'])

    def test_claude_subagents_cannot_replace_main_context(self):
        s = session(ClaudeSession); s.usage_message = {'model': 'main'}
        s.project({'parent_tool_use_id': 'tool', 'type': 'assistant', 'message': {'model':'child','usage': {'input_tokens': 5}}})
        self.assertEqual(s.usage_message, {'model': 'main'})

    def test_agy_missing_fields_are_not_zero_and_snapshots_replace(self):
        steps = {1: {'input_tokens': 10, 'output_tokens': 2}, 2: {'input_tokens': 20}}
        self.assertEqual(agy_steps(list(steps.values())), {'input_tokens': 30})
        steps[1] = {'input_tokens': 11, 'output_tokens': 3}
        self.assertEqual(agy_steps(list(steps.values())), {'input_tokens': 31})
        rows = agy_usage({'input_tokens': 20, 'cache_read_tokens': 80}, '本回合')
        self.assertEqual(rows, [row('本回合 · 输入缓存命中', '80 / 100 · 80.0%')])

    def test_self_computation_for_context_window_cumulative_cache_and_cost(self):
        res = {
            'usage': {'input_tokens': 10, 'cache_read_input_tokens': 10, 'cache_creation_input_tokens': 0},
            'modelUsage': {'claude-sonnet-4-6': {'inputTokens': 1000, 'cacheReadInputTokens': 9000, 'cacheCreationInputTokens': 0}},
            'total_cost_usd': 0.12,
        }
        self.assertEqual(claude_result(res), [
            row('累计输入缓存命中', '9000 / 10000 · 90.0%'),
            row('CLI 累计估算 · USD', '0.12'),
        ])
        self.assertEqual(
            claude_context({'model': 'claude-sonnet-4-6', 'usage': {'input_tokens': 1000, 'cache_read_input_tokens': 9000, 'cache_creation_input_tokens': 0}}, {}),
            [row('上下文', '10000 / 200000 · 5.0%')],
        )
        acp_rows = acp_usage({'input': 10000, 'cacheRead': 90000, 'cacheWrite': 0, 'output': 5000}, model='deepseek-chat')
        self.assertEqual(acp_rows[0], row('上下文', '100000 / 128000 · 78.1%'))
        self.assertEqual(acp_rows[1], row('缓存命中', '90000 / 100000 · 90.0%'))
        self.assertTrue(acp_rows[2]['label'].startswith('CLI 累计估算 · USD'))

    def test_grok_inclusive_cache_exact_ticks_and_incomplete_flags(self):
        usage = {'inputTokens':10000, 'outputTokens':20, 'cachedReadTokens':6000, 'cacheCreationTokens':3000,
                 'modelCalls':1, 'costUsdTicks':123456789}
        rows = grok_usage(usage)
        self.assertEqual(rows[0], row('进程累计 · 输入缓存命中', '6000 / 10000 · 60.0%'))
        self.assertIn(row('CLI 记录费用 · USD', '0.0123456789'), rows)
        self.assertEqual(grok_usage({**usage, 'usageIsIncomplete':True}), [])
        self.assertFalse(any('费用' in r['label'] for r in grok_usage({**usage, 'costIsPartial':True})))
        self.assertEqual(grok_usage({'inputTokens':0,'outputTokens':0,'modelCalls':0,'costUsdTicks':0}), [])
        self.assertEqual(grok_context(1329,500000), [row('上下文','1329 / 500000 · 0.3%')])

    def test_pi_inclusive_output_does_not_double_count_reasoning_or_zero_cost(self):
        stats_with_reason = {'tokens': {'input': 1000, 'cacheRead': 0, 'cacheWrite': 0, 'output': 1000, 'reasoning': 600}, 'cost': 0}
        stats_without_reason_split = {'tokens': {'input': 1000, 'cacheRead': 0, 'cacheWrite': 0, 'output': 1000, 'reasoning': 0}}
        state = {'model': {'id': 'claude-sonnet-4-6'}, 'contextUsage': {'tokens': 2000, 'contextWindow': 200000, 'percent': 1}}
        self.assertEqual(pi_usage(stats_with_reason, state), pi_usage(stats_without_reason_split, state))
        self.assertIn(row('CLI 累计估算 · USD', '0.018'), pi_usage(stats_with_reason, state))
        self.assertFalse(any(r['value'] == '0' for r in pi_usage({'tokens': {'input': 10, 'cacheRead': 0, 'cacheWrite': 0, 'output': 5}, 'cost': 0}, {'model': {'id': 'unknown-local'}})))

    def test_server_imports_and_agy_session_metrics_includes_field_1(self):
        import sqlite3, tempfile
        import luma_cli.server as srv
        self.assertTrue(hasattr(srv, 'Manager'))

        def pb_varint(val):
            out = bytearray()
            while True:
                b = val & 0x7F
                val >>= 7
                if val:
                    out.append(b | 0x80)
                else:
                    out.append(b)
                    break
            return bytes(out)

        def pb_num(f, v): return pb_varint((f << 3) | 0) + pb_varint(v)
        def pb_bytes(f, b): return pb_varint((f << 3) | 2) + pb_varint(len(b)) + b
        def pb_str(f, s): return pb_bytes(f, s.encode('utf-8'))

        with tempfile.TemporaryDirectory() as tmp:
            home = pathlib.Path(tmp)
            conv_dir = home / '.gemini' / 'antigravity-cli' / 'conversations'
            conv_dir.mkdir(parents=True)
            db = conv_dir / 'conv-1.db'
            con = sqlite3.connect(str(db))
            con.execute('CREATE TABLE gen_metadata (idx INTEGER PRIMARY KEY, data BLOB)')
            usage = pb_num(1, 1026) + pb_num(2, 2974) + pb_num(5, 6000) + pb_num(9, 800) + pb_num(10, 200) + pb_str(11, 'r0')
            chat = pb_str(19, 'gemini-3.8-flash') + pb_bytes(4, usage)
            con.execute('INSERT INTO gen_metadata VALUES (0, ?)', (pb_bytes(1, chat),))
            con.commit()
            con.close()
            rows = agy_session_metrics('conv-1', model='gemini-3.8-flash', home=home)
            self.assertIn(row('累计 · 输入缓存命中', '6000 / 10000 · 60.0%'), rows)
            self.assertTrue(any('费用' in r['label'] or 'USD' in r['label'] for r in rows))

if __name__ == '__main__': unittest.main()

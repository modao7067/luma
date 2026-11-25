#!/usr/bin/env python3
import json, os, pathlib, sqlite3, sys, tempfile, unittest
sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.i18n import tr
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
        self.assertEqual(acp_usage({'used': 12, 'size': 0}), [row(tr('上下文', 'Context'), '12')])

    def test_real_zero_and_overfull_are_not_hidden_or_clamped(self):
        ctx = tr('上下文', 'Context')
        self.assertEqual(context(0, 100), [row(ctx, '0 / 100 · 0.0%')])
        self.assertEqual(context(120, 100), [row(ctx, '120 / 100 · 120.0%')])
        self.assertEqual(cache_ratio(0, 100, '缓存'), [row('缓存', '0 / 100 · 0.0%')])
        self.assertEqual(cache_ratio(101, 100, '缓存'), [])
        self.assertEqual(cache_ratio(0, 0, '缓存'), [])

    def test_acp_providers_use_atomic_native_context_not_cumulative_usage(self):
        ctx = tr('上下文', 'Context')
        rec_eur = tr('CLI 记录费用', 'CLI recorded cost') + ' · EUR'
        for cls in (AcpSession, DshSession, KimiSession, QoderSession):
            s = session(cls)
            frame = {'method': 'session/update', 'params': {'sessionId': 'parent', 'update':
                     {'sessionUpdate': 'usage_update', 'used': 1234, 'size': 200000, 'cost': {'amount': 0.125, 'currency': 'EUR'}}}}
            s.event(frame); expected = s.data['metrics']
            self.assertEqual(expected, [row(ctx, '1234 / 200000 · 0.6%'), row(rec_eur, '0.125')])
            s.event(frame); self.assertEqual(s.data['metrics'], expected)
            s.event({'method': 'session/update', 'params': {'sessionId': 'other', 'update': {'sessionUpdate': 'usage_update', 'used': 1, 'size': 1}}})
            self.assertEqual(s.data['metrics'], expected)
            s.apply_usage({'used': None, 'size': 200000}); self.assertEqual(s.data['metrics'], [])

    def test_acp_cache_rate_uses_disjoint_native_counters(self):
        ctx = tr('上下文', 'Context')
        hit = tr('缓存命中', 'Cache hit')
        self.assertEqual(acp_usage({'used': 1000, 'size': 200000, 'input': 10, 'cacheRead': 90, 'cacheWrite': 0}),
                         [row(ctx, '1000 / 200000 · 0.5%'), row(hit, '90 / 100 · 90.0%')])
        self.assertEqual(acp_usage({'used': 1, 'size': 100, 'cacheRead': 5}),
                         [row(ctx, '1 / 100 · 1.0%')])

    def test_dsh_private_cache_notification_updates_the_current_usage_row(self):
        ctx = tr('上下文', 'Context')
        hit = tr('缓存命中', 'Cache hit')
        s = session(DshSession)
        s.apply_usage({'used': 1000, 'size': 200000})
        self.assertEqual(s.data['metrics'], [row(ctx, '1000 / 200000 · 0.5%')])
        s.event({'method': 'luma/usage/cache', 'params': {'sessionId': 'parent', 'input': 10, 'cacheRead': 90, 'cacheWrite': 0}})
        self.assertEqual(s.data['metrics'], [row(ctx, '1000 / 200000 · 0.5%'), row(hit, '90 / 100 · 90.0%')])
        s.apply_usage({'used': 1010, 'size': 200000})
        self.assertEqual(s.data['metrics'], [row(ctx, '1010 / 200000 · 0.5%'), row(hit, '90 / 100 · 90.0%')])

    def test_acp_model_change_invalidates_old_context_without_repricing_cost(self):
        ctx = tr('上下文', 'Context')
        rec_usd = tr('CLI 记录费用', 'CLI recorded cost') + ' · USD'
        s = session(AcpSession); s.native_caps = {}; s.data['model'] = 'old'
        s.apply_usage({'used': 90000, 'size': 100000, 'cost': {'amount': .2, 'currency': 'USD'}})
        s.apply_options([{'id':'model','category':'model','type':'select','currentValue':'new','options':[{'value':'new','name':'New'}]}])
        self.assertEqual(s.data['metrics'], [row(rec_usd, '0.2')])
        s.apply_usage({'used': 5000, 'size': 200000})
        self.assertEqual(s.data['metrics'], [row(ctx, '5000 / 200000 · 2.5%')])
        self.assertEqual(session(AcpSession).data['metrics'], [])

    def test_pi_disjoint_cache_scope(self):
        ctx = tr('上下文', 'Context')
        hit = tr('累计 输入缓存命中', 'Total input cache hit')
        stats = {'tokens': {'input': 1000, 'cacheRead': 6000, 'cacheWrite': 3000, 'output': 99999, 'reasoning': 90},
                 'contextUsage': {'tokens': 5000, 'contextWindow': 100000, 'percent': 5}, 'cost': .25}
        rows = pi_usage(stats, {})
        self.assertIn(row(hit, '6000 / 10000 · 60.0%'), rows)
        self.assertEqual(rows[0], row(ctx, '5000 / 100000 · 5.0%'))
        self.assertEqual(rows, pi_usage(stats, {}))

    def test_pi_compaction_null_does_not_fall_back_to_stale_state(self):
        ctx = tr('上下文', 'Context')
        stats = {'contextUsage': {'tokens': None, 'contextWindow': 200000, 'percent': None}}
        state = {'contextUsage': {'tokens': 190000, 'contextWindow': 200000, 'percent': 95}}
        self.assertEqual(pi_usage(stats, state), [])
        self.assertEqual(pi_usage({'contextUsage': None}, state), [])
        self.assertEqual(pi_usage({}, state)[0], row(ctx, '190000 / 200000 · 95.0%'))

    def test_pi_partial_counters_cannot_invent_cache_hit_rate(self):
        self.assertEqual(pi_usage({'tokens': {'input': 3, 'cacheRead': 2}}, {}), [])

    def test_claude_input_only_context_exact_model_and_result_scope(self):
        ctx = tr('上下文', 'Context')
        turn_hit = tr('本回合输入缓存命中', 'Turn input cache hit')
        est_usd = tr('CLI 累计估算', 'CLI estimated cost') + ' · USD'
        message = {'model': 'm', 'usage': {'input_tokens': 100, 'cache_read_input_tokens': 600, 'cache_creation_input_tokens': 300, 'output_tokens': 99999}}
        self.assertEqual(claude_context(message, {'m': {'contextWindow': 20000}}), [row(ctx, '1000 / 20000 · 5.0%')])
        self.assertEqual(claude_context(message, {'other': {'contextWindow': 20000}}), [])
        result = {'usage': message['usage'], 'total_cost_usd': .25}
        self.assertEqual(claude_result(result)[0], row(turn_hit, '600 / 1000 · 60.0%'))
        self.assertIn(row(est_usd, '0.25'), claude_result(result))

    def test_claude_placeholder_and_compaction_do_not_fake_zero_bill_or_stale_context(self):
        ctx = tr('上下文', 'Context')
        est_usd = tr('CLI 累计估算', 'CLI estimated cost') + ' · USD'
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
        self.assertFalse(any(r['label'] == ctx for r in s.data['metrics']))
        self.assertIn(row(est_usd, '0.5'), s.data['metrics'])

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
        self.assertEqual(rows, [row('本回合' + tr(' · 输入缓存命中', ' · input cache hit'), '80 / 100 · 80.0%')])

    def test_self_computation_for_context_window_cumulative_cache_and_cost(self):
        ctx = tr('上下文', 'Context')
        hit = tr('缓存命中', 'Cache hit')
        est_usd = tr('CLI 累计估算', 'CLI estimated cost') + ' · USD'
        res = {
            'usage': {'input_tokens': 10, 'cache_read_input_tokens': 10, 'cache_creation_input_tokens': 0},
            'modelUsage': {'claude-sonnet-4-6': {'inputTokens': 1000, 'cacheReadInputTokens': 9000, 'cacheCreationInputTokens': 0}},
            'total_cost_usd': 0.12,
        }
        self.assertEqual(claude_result(res), [
            row(tr('累计输入缓存命中', 'Total input cache hit'), '9000 / 10000 · 90.0%'),
            row(est_usd, '0.12'),
        ])
        self.assertEqual(
            claude_context({'model': 'claude-sonnet-4-6', 'usage': {'input_tokens': 1000, 'cache_read_input_tokens': 9000, 'cache_creation_input_tokens': 0}}, {}),
            [row(ctx, '10000 / 200000 · 5.0%')],
        )
        acp_rows = acp_usage({'input': 10000, 'cacheRead': 90000, 'cacheWrite': 0, 'output': 5000}, model='deepseek-chat')
        self.assertEqual(acp_rows[0], row(ctx, '100000 / 128000 · 78.1%'))
        self.assertEqual(acp_rows[1], row(hit, '90000 / 100000 · 90.0%'))
        self.assertTrue(acp_rows[2]['label'].startswith(est_usd))

    def test_grok_inclusive_cache_exact_ticks_and_incomplete_flags(self):
        ctx = tr('上下文', 'Context')
        proc_hit = tr('进程累计', 'Process total') + tr(' · 输入缓存命中', ' · input cache hit')
        rec_usd = tr('CLI 记录费用', 'CLI recorded cost') + ' · USD'
        usage = {'inputTokens':10000, 'outputTokens':20, 'cachedReadTokens':6000, 'cacheCreationTokens':3000,
                 'modelCalls':1, 'costUsdTicks':123456789}
        rows = grok_usage(usage)
        self.assertEqual(rows[0], row(proc_hit, '6000 / 10000 · 60.0%'))
        self.assertIn(row(rec_usd, '0.0123456789'), rows)
        self.assertEqual(grok_usage({**usage, 'usageIsIncomplete':True}), [])
        self.assertFalse(any('费用' in r['label'] or 'cost' in r['label'].lower() for r in grok_usage({**usage, 'costIsPartial':True})))
        self.assertEqual(grok_usage({'inputTokens':0,'outputTokens':0,'modelCalls':0,'costUsdTicks':0}), [])
        self.assertEqual(grok_context(1329,500000), [row(ctx,'1329 / 500000 · 0.3%')])

    def test_pi_inclusive_output_does_not_double_count_reasoning_or_zero_cost(self):
        est_usd = tr('CLI 累计估算', 'CLI estimated cost') + ' · USD'
        stats_with_reason = {'tokens': {'input': 1000, 'cacheRead': 0, 'cacheWrite': 0, 'output': 1000, 'reasoning': 600}, 'cost': 0}
        stats_without_reason_split = {'tokens': {'input': 1000, 'cacheRead': 0, 'cacheWrite': 0, 'output': 1000, 'reasoning': 0}}
        state = {'model': {'id': 'claude-sonnet-4-6'}, 'contextUsage': {'tokens': 2000, 'contextWindow': 200000, 'percent': 1}}
        self.assertEqual(pi_usage(stats_with_reason, state), pi_usage(stats_without_reason_split, state))
        self.assertIn(row(est_usd, '0.018'), pi_usage(stats_with_reason, state))
        self.assertFalse(any(r['value'] == '0' for r in pi_usage({'tokens': {'input': 10, 'cacheRead': 0, 'cacheWrite': 0, 'output': 5}, 'cost': 0}, {'model': {'id': 'unknown-local'}})))

    def test_server_imports_and_agy_session_metrics_excludes_enum_field_1(self):
        import luma_cli.server as srv
        from luma_cli.antigravity_agy_acp import AGY_ACP_PROTOCOL, AntigravityAgyAcpSession
        self.assertTrue(hasattr(srv, 'Manager'))
        ctx = tr('上下文', 'Context')
        db_hit = tr('累计 · 输入缓存命中', 'Total · input cache hit')
        turn_hit = tr('本回合', 'This turn') + tr(' · 输入缓存命中', ' · input cache hit')

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
            ctx_meta = pb_num(1, 10000) + pb_num(4, 256000)
            meta_wrap = pb_bytes(10, ctx_meta)
            usage = pb_num(1, 1318) + pb_num(2, 4000) + pb_num(5, 6000) + pb_num(9, 200) + pb_num(10, 800) + pb_str(11, 'r0')
            chat = pb_str(19, 'gemini-3.8-flash') + pb_bytes(4, usage) + pb_bytes(9, meta_wrap)
            con.execute('INSERT INTO gen_metadata VALUES (0, ?)', (pb_bytes(1, chat),))
            con.commit()
            con.close()
            rows = agy_session_metrics('conv-1', model='gemini-3.8-flash', home=home)
            self.assertEqual([r for r in rows if r['label'] == ctx], [row(ctx, '10000 / 256000 · 3.9%')])
            self.assertIn(row(db_hit, '6000 / 10000 · 60.0%'), rows)
            self.assertTrue(any('USD' in r['label'] for r in rows))

            old_home = os.environ.get('HOME')
            os.environ['HOME'] = str(home)
            try:
                sess = AntigravityAgyAcpSession({'cli': 'Antigravity', 'cwd': tmp, 'nativeProtocol': AGY_ACP_PROTOCOL, 'nativeId': 'conv-1'}, lambda: None)
                sess.update(nativeId='conv-1', model='gemini-pro-orchestrator')
                sess.apply_usage({'input': 2000, 'cacheRead': 8000, 'output': 500, 'used': 12000, 'size': 1048576})
                ctx_rows = [r for r in sess.data['metrics'] if r['label'] == ctx]
                self.assertEqual(ctx_rows, [row(ctx, '10000 / 256000 · 3.9%')])
                self.assertEqual(sess.acp_metrics, [])
                sess.invalidate_context()
                self.assertEqual([r for r in sess.data['metrics'] if r['label'] == ctx], [])
                self.assertTrue(any('USD' in r['label'] for r in sess.data['metrics']))

                sess_no_db = AntigravityAgyAcpSession({'cli': 'Antigravity', 'cwd': tmp, 'nativeProtocol': AGY_ACP_PROTOCOL, 'nativeId': 'conv-missing'}, lambda: None)
                sess_no_db.update(nativeId='conv-missing', model='gemini-pro-orchestrator')
                sess_no_db.apply_usage({'input': 2000, 'cacheRead': 8000, 'output': 500, 'size': 1048576})
                self.assertEqual(
                    [r for r in sess_no_db.data['metrics'] if r['label'] == ctx],
                    [row(ctx, '10000 / 256000 · 3.9%')],
                )
                self.assertIn(row(turn_hit, '8000 / 10000 · 80.0%'), sess_no_db.data['metrics'])
                self.assertTrue(any('USD' in r['label'] for r in sess_no_db.data['metrics']))
            finally:
                if old_home is None:
                    os.environ.pop('HOME', None)
                else:
                    os.environ['HOME'] = old_home

    def test_metrics_regressions_count_model_cost_and_agy_path_safety(self):
        from decimal import Decimal
        import luma_cli.metrics as mmod
        self.assertEqual(count(-0.0), '0')
        self.assertEqual(count(Decimal('-0')), '0')
        self.assertEqual(mmod._clean_model_name('openrouter/qwen/qwen3-coder:free'), 'qwen3-coder')
        tiny_rows = estimate_cost('claude-4.5-sonnet', uncached_input=10)
        self.assertEqual(len(tiny_rows), 1)
        self.assertNotEqual(tiny_rows[0]['value'], '0')
        self.assertAlmostEqual(float(tiny_rows[0]['value']), 3e-05, places=7)
        sub_micro = mmod._estimated_cost_rows(1.2e-7)
        self.assertEqual(len(sub_micro), 1)
        self.assertEqual(sub_micro[0]['value'], '0.00000012')
        self.assertEqual(mmod._estimated_cost_rows(0.0), [])

        with tempfile.TemporaryDirectory() as tmp:
            home = pathlib.Path(tmp)
            conv_dir = home / '.gemini' / 'antigravity-cli' / 'conversations'
            conv_dir.mkdir(parents=True)
            outside = home / 'secret.db'
            con = sqlite3.connect(str(outside))
            con.execute('CREATE TABLE gen_metadata (idx INTEGER PRIMARY KEY, data BLOB)')
            con.commit()
            con.close()
            self.assertEqual(agy_session_metrics('../../secret', home=home), [])
            self.assertEqual(agy_session_metrics('bad/id', home=home), [])
            sym_db = conv_dir / 'sym-conv.db'
            sym_db.symlink_to(outside)
            self.assertEqual(agy_session_metrics('sym-conv', home=home), [])

            rows = agy_session_metrics(
                'missing-conv',
                model='claude-sonnet-4-6',
                result_usage={
                    'input_tokens': 100,
                    'cache_read_tokens': 200,
                    'cache_creation_tokens': 300,
                    'output_tokens': 50,
                },
                home=home,
            )
            ctx = tr('上下文', 'Context')
            self.assertEqual([r for r in rows if r['label'] == ctx], [row(ctx, '600 / 200000 · 0.3%')])

        res_1h = {
            'model': 'claude-sonnet-4-6',
            'usage': {
                'input_tokens': 0,
                'cache_read_input_tokens': 0,
                'cache_creation_input_tokens': 1000,
                'cache_creation': {'ephemeral_5m_input_tokens': 400, 'ephemeral_1h_input_tokens': 600},
                'output_tokens': 0,
            },
        }
        est_usd = tr('CLI 累计估算', 'CLI estimated cost') + ' · USD'
        cost_1h = float([r for r in claude_result(res_1h) if r['label'] == est_usd][0]['value'])
        res_5m = {
            'model': 'claude-sonnet-4-6',
            'usage': {
                'input_tokens': 0,
                'cache_read_input_tokens': 0,
                'cache_creation_input_tokens': 1000,
                'output_tokens': 0,
            },
        }
        cost_5m = float([r for r in claude_result(res_5m) if r['label'] == est_usd][0]['value'])
        self.assertGreater(cost_1h, cost_5m)
        self.assertAlmostEqual(cost_1h, (400 * 3.75 + 600 * 6.0) / 1_000_000.0, places=8)

if __name__ == '__main__': unittest.main()

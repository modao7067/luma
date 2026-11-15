from __future__ import annotations

import math
import os
import re
import sqlite3
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import Any

from .global_usage import (
    _extract_agy_db_session,
    _lookup_model_rate,
    _price_record,
)
from .i18n import tr

_SAFE_NATIVE_ID_RE = re.compile(r'^[A-Za-z0-9_-]{1,128}$')

MODEL_CONTEXT_WINDOWS: tuple[tuple[str, int], ...] = (
    ("gpt-4.1", 1_047_576),
    ("gpt-4o", 128_000),
    ("gpt-6", 258_400),
    ("gpt-5", 258_400),
    ("codex-", 258_400),
    ("o1", 200_000),
    ("o3", 200_000),
    ("o4-", 200_000),
    ("claude-", 200_000),
    ("gemini-4-", 256_000),
    ("gemini-pro-orchestrator", 256_000),
    ("gemini-", 1_048_576),
    ("deepseek-", 128_000),
    ("grok-4", 256_000),
    ("grok-code", 256_000),
    ("grok-3", 131_072),
    ("grok-2", 131_072),
    ("kimi-k2", 256_000),
    ("kimi-", 128_000),
    ("moonshot-", 128_000),
    ("qwen3-coder", 256_000),
    ("qwen3-max", 256_000),
    ("qwen-max", 256_000),
    ("qwen-plus", 256_000),
    ("qwen", 131_072),
    ("glm-", 128_000),
    ("minimax-", 200_000),
    ("mimo-", 128_000),
)

def number(value):
    return value if type(value) in (int, float) and math.isfinite(value) and value >= 0 else None

def count(value):
    d = Decimal(str(value))
    if d.is_finite() and d == 0:
        return '0'
    return format(d.normalize(), 'f')

def row(label, value):
    return {'label': label, 'value': str(value)}

def percentage(part, whole):
    value = (Decimal(str(part)) * 100 / Decimal(str(whole))).quantize(Decimal('.1'), rounding=ROUND_HALF_UP)
    return format(value, 'f')

def _clean_model_name(model: Any) -> str:
    if isinstance(model, (list, tuple)) and model:
        model = model[-1]
    if not isinstance(model, str):
        return ''
    m = model.strip()
    if m.startswith('[') and m.endswith(']'):
        try:
            import json as _json
            parsed = _json.loads(m)
            if isinstance(parsed, list) and parsed and isinstance(parsed[-1], str):
                m = parsed[-1].strip()
        except Exception:
            pass
    if '/' in m:
        m = m.split('/')[-1].strip()
    if m.lower().endswith(':free'):
        m = m[:-5].strip()
    if ':' in m and not m.lower().endswith(':fast'):
        m = m.split(':')[-1].strip()
    return m

def model_context_window(model: Any) -> int | None:
    m = _clean_model_name(model).lower()
    if not m or m in ('unknown', '<synthetic>', 'synthetic'):
        return None
    if m.endswith(':fast'):
        m = m[:-5]
    if '1m' in m.split('-') or m.endswith('-1m'):
        return 1_000_000
    for prefix, win in MODEL_CONTEXT_WINDOWS:
        if m == prefix or m.startswith(prefix):
            return win
    return None

def context(used, size=None, percent=None, model=None):
    used, size = number(used), number(size)
    if size in (None, 0) and model is not None:
        size = number(model_context_window(model))
    if used is None:
        return []
    value = count(used)
    if size is not None and size > 0:
        pct = number(percent)
        value += f' / {count(size)} · {percentage(used, size) if pct is None else percentage(pct, 100)}%'
    return [row(tr('上下文', 'Context'), value)]

def cost(amount, currency, estimated=False):
    amount = number(amount)
    if amount is None or not isinstance(currency, str) or len(currency) != 3 or not currency.isalpha():
        return []
    prefix = tr('CLI 累计估算', 'CLI estimated cost') if estimated else tr('CLI 记录费用', 'CLI recorded cost')
    return [row(prefix + ' · ' + currency.upper(), count(amount))]

def _estimated_cost_rows(amount: float) -> list[dict[str, str]]:
    val = number(amount)
    if val is None or val <= 0.0:
        return []
    rounded = round(val, 6)
    if rounded == 0.0:
        rounded = float(f'{val:.6g}')
    return cost(rounded, 'USD', estimated=True)

def estimate_cost(
    model: Any,
    uncached_input: int | float = 0,
    cache_read: int | float = 0,
    cache_write: int | float = 0,
    output: int | float = 0,
    reasoning: int | float = 0,
    speed: str | None = None,
) -> list[dict[str, str]]:
    clean_model = _clean_model_name(model)
    if not clean_model:
        return []
    if _lookup_model_rate(clean_model) is None:
        return []
    u_in = int(number(uncached_input) or 0)
    c_read = int(number(cache_read) or 0)
    c_write = int(number(cache_write) or 0)
    out = int(number(output) or 0)
    reason = int(number(reasoning) or 0)
    if u_in + c_read + c_write + out + reason <= 0:
        return []
    priced = _price_record({
        'model': clean_model,
        'uncachedInputTokens': u_in,
        'cachedInputTokens': c_read,
        'cacheCreationTokens': c_write,
        'outputTokens': out,
        'reasoningTokens': reason,
        'speed': speed,
    })
    cost_usd = number(priced.get('costUsd'))
    if cost_usd is None:
        return []
    return _estimated_cost_rows(cost_usd)

def cache_ratio(read, total, label=None):
    label = label or tr('缓存命中', 'Cache hit')
    read, total = number(read), number(total)
    if read is None or total is None or total <= 0 or read > total:
        return []
    return [row(label, f'{count(read)} / {count(total)} · {percentage(read, total)}%')]

def disjoint_cache(usage, keys, label=None):
    label = label or tr('缓存命中', 'Cache hit')
    values = [number(usage.get(k)) for k in keys]
    return [] if any(v is None for v in values) else cache_ratio(values[1], sum(values), label)

def fields(usage, names):
    return [row(label, count(usage[key])) for key, label in names if number(usage.get(key)) is not None]

def acp_usage(update, model=None):
    resolved_model = model or update.get('model')
    money = update.get('cost') or {}
    read = number(update.get('cacheRead'))
    written = number(update.get('cacheWrite')) or 0
    fresh = number(update.get('input'))
    out = number(update.get('output')) or 0
    reason = number(update.get('reasoning')) or 0
    used = number(update.get('used'))
    size = number(update.get('size')) or model_context_window(resolved_model)
    if used is None and fresh is not None and size is not None:
        prompt_total = fresh + (read or 0) + written
        if prompt_total > 0:
            used = prompt_total
    rows = context(used, size)
    if read is not None and fresh is not None:
        rows += cache_ratio(read, fresh + read + written, tr('缓存命中', 'Cache hit'))
    cost_rows = cost(money.get('amount'), money.get('currency'))
    if not cost_rows:
        cost_rows = estimate_cost(
            resolved_model,
            uncached_input=fresh or 0,
            cache_read=read or 0,
            cache_write=written,
            output=out,
            reasoning=reason,
        )
    return rows + cost_rows

def pi_usage(stats, state):
    usage = stats.get('tokens') or {}
    current = stats.get('contextUsage') if 'contextUsage' in stats else state.get('contextUsage')
    current = current or {}
    raw_model = (state or {}).get('model')
    model = (raw_model.get('id') or raw_model.get('modelId') or raw_model.get('name')) if isinstance(raw_model, dict) else raw_model
    rows = context(current.get('tokens'), current.get('contextWindow'), current.get('percent'), model=model)
    rows += disjoint_cache(usage, ('input', 'cacheRead', 'cacheWrite'), tr('累计 输入缓存命中', 'Total input cache hit'))
    out_tok = number(usage.get('output')) or 0
    reason_tok = number(usage.get('reasoning')) or 0
    raw_cost = number(stats.get('cost'))
    cost_rows = cost(raw_cost, 'USD') if raw_cost and raw_cost > 0 else []
    if not cost_rows:
        cost_rows = estimate_cost(
            model,
            uncached_input=number(usage.get('input')) or 0,
            cache_read=number(usage.get('cacheRead')) or 0,
            cache_write=number(usage.get('cacheWrite')) or 0,
            output=max(0, out_tok - reason_tok),
            reasoning=reason_tok,
        )
    return rows + cost_rows

def claude_result(result, model=None):
    model_usage = result.get('modelUsage')
    rows = []
    if isinstance(model_usage, dict) and model_usage:
        entries = [v for v in model_usage.values() if isinstance(v, dict)]
        if entries and any(
            number(v.get('inputTokens')) is not None and number(v.get('cacheReadInputTokens')) is not None
            for v in entries
        ):
            tot_in = sum(number(v.get('inputTokens')) or 0 for v in entries)
            tot_read = sum(number(v.get('cacheReadInputTokens')) or 0 for v in entries)
            tot_write = sum(number(v.get('cacheCreationInputTokens')) or 0 for v in entries)
            rows += cache_ratio(tot_read, tot_in + tot_read + tot_write, tr('累计输入缓存命中', 'Total input cache hit'))
    if not rows:
        usage = result.get('usage') or {}
        rows += disjoint_cache(
            usage,
            ('input_tokens', 'cache_read_input_tokens', 'cache_creation_input_tokens'),
            tr('本回合输入缓存命中', 'Turn input cache hit'),
        )
    cost_rows = cost(result.get('total_cost_usd'), 'USD', estimated=True)
    if not cost_rows:
        usage = result.get('usage') or {}
        resolved_model = model or result.get('model')
        if not resolved_model and isinstance(model_usage, dict) and model_usage:
            resolved_model = next(iter(model_usage.keys()), None)
        cost_rows = estimate_cost(
            resolved_model,
            uncached_input=number(usage.get('input_tokens')) or 0,
            cache_read=number(usage.get('cache_read_input_tokens')) or 0,
            cache_write=number(usage.get('cache_creation_input_tokens')) or 0,
            output=number(usage.get('output_tokens')) or 0,
        )
    return rows + cost_rows

def claude_context(message, model_usage=None):
    model = message.get('model')
    model_usage = model_usage or {}
    window = (model_usage.get(model) or {}).get('contextWindow') if model else None
    if number(window) in (None, 0):
        window = model_context_window(model)
    usage = message.get('usage') or {}
    parts = [number(usage.get(k)) for k in ('input_tokens', 'cache_read_input_tokens', 'cache_creation_input_tokens')]
    if any(v is None for v in parts) or not any(parts) or number(window) in (None, 0):
        return []
    return context(sum(parts), window)

def agy_usage(usage, scope=None, model=None, context_used=None, context_window=None):
    scope = scope or tr('CLI 累计', 'CLI total')
    if not isinstance(usage, dict):
        return []
    resolved_model = model or usage.get('model')
    rows = []
    win = number(context_window) or model_context_window(resolved_model)
    if number(context_used) is not None and win:
        rows += context(context_used, win)
    inp = number(usage.get('input_tokens'))
    c_read = number(usage.get('cache_read_tokens'))
    c_write = number(usage.get('cache_creation_tokens') or usage.get('cache_write_tokens')) or 0
    out = number(usage.get('output_tokens')) or 0
    think = number(usage.get('thinking_tokens')) or 0
    if inp is not None and c_read is not None and (inp + c_read + c_write) > 0:
        rows += cache_ratio(c_read, inp + c_read + c_write, scope + tr(' · 输入缓存命中', ' · input cache hit'))
    rows += estimate_cost(
        resolved_model,
        uncached_input=inp or 0,
        cache_read=c_read or 0,
        cache_write=c_write,
        output=out,
        reasoning=think,
    )
    return rows

def agy_acp_usage(usage, scope=None, model=None, context_used=None, context_window=None):
    scope = scope or tr('本回合', 'This turn')
    token_keys = (
        'inputTokens', 'outputTokens', 'totalTokens', 'cachedReadTokens', 'cachedWriteTokens', 'thoughtTokens',
        'input', 'output', 'cacheRead', 'cacheWrite', 'reasoning', 'used',
    )
    if not isinstance(usage, dict) or not any((number(usage.get(k)) or 0) > 0 for k in token_keys):
        win = number(context_window) or model_context_window(model)
        return context(context_used, win) if number(context_used) is not None and win else []
    resolved_model = model or usage.get('model')
    inp = number(usage.get('inputTokens'))
    if inp is None:
        inp = number(usage.get('input'))
    c_read = number(usage.get('cachedReadTokens'))
    if c_read is None:
        c_read = number(usage.get('cacheRead'))
    c_write = number(usage.get('cachedWriteTokens'))
    if c_write is None:
        c_write = number(usage.get('cacheWrite')) or 0
    out = number(usage.get('outputTokens'))
    if out is None:
        out = number(usage.get('output')) or 0
    think = number(usage.get('thoughtTokens'))
    if think is None:
        think = number(usage.get('reasoning')) or 0
    win = number(context_window) or model_context_window(resolved_model)
    ctx = number(context_used)
    if ctx is None:
        used_val = number(usage.get('used'))
        if used_val is not None and used_val > 0:
            ctx = used_val
        elif win and (inp is not None or c_read is not None):
            prompt_tokens = (inp or 0) + (c_read or 0) + c_write
            if prompt_tokens > 0:
                ctx = prompt_tokens
    rows = context(ctx, win) if ctx is not None and win else []
    if inp is not None and c_read is not None and (inp + c_read + c_write) > 0:
        rows += cache_ratio(c_read, inp + c_read + c_write, scope + tr(' · 输入缓存命中', ' · input cache hit'))
    money = usage.get('cost') if isinstance(usage.get('cost'), dict) else {}
    cost_rows = cost(money.get('amount'), money.get('currency')) if money else []
    if not cost_rows:
        cost_rows = estimate_cost(
            resolved_model,
            uncached_input=inp or 0,
            cache_read=c_read or 0,
            cache_write=c_write,
            output=out,
            reasoning=think,
        )
    rows += cost_rows
    return rows

def agy_steps(steps):
    names = ('input_tokens', 'output_tokens', 'thinking_tokens', 'cache_read_tokens', 'total_tokens')
    return {
        key: sum(step[key] for step in steps)
        for key in names
        if steps and all(number(step.get(key)) is not None for step in steps)
    }

def agy_session_metrics(
    native_id: str | None,
    model: str | None = None,
    step_usages: list[dict[str, Any]] | None = None,
    result_usage: dict[str, Any] | None = None,
    acp_usage_dict: dict[str, Any] | None = None,
    home: Path | None = None,
) -> list[dict[str, str]]:
    base_home = home or Path(os.environ.get('HOME') or str(Path.home()))
    db_path: Path | None = None
    if isinstance(native_id, str) and _SAFE_NATIVE_ID_RE.fullmatch(native_id):
        for sub in ('antigravity-cli', 'antigravity'):
            conv_dir = base_home / '.gemini' / sub / 'conversations'
            candidate = conv_dir / f'{native_id}.db'
            try:
                if candidate.is_file() and not candidate.is_symlink():
                    if candidate.resolve(strict=True).parent == conv_dir.resolve(strict=True):
                        db_path = candidate
                        break
            except OSError:
                continue

    est_used = 0
    max_ctx = 0
    last_prompt = 0
    tot_uncached = 0
    tot_read = 0
    tot_write = 0
    tot_cost = 0.0
    has_cost = False
    latest_model = model or ''

    if db_path is not None:
        db_records, db_est_used, db_max_ctx, db_latest_model = _extract_agy_db_session(db_path)
        if db_est_used > 0:
            est_used = db_est_used
        if db_max_ctx > 0:
            max_ctx = db_max_ctx
        if db_latest_model:
            latest_model = db_latest_model
        for rec in db_records:
            uncached = int(rec.get('uncachedInputTokens') or 0)
            c_read = int(rec.get('cachedInputTokens') or 0)
            c_write = int(rec.get('cacheCreationTokens') or 0)
            text_out = int(rec.get('outputTokens') or 0)
            reason = int(rec.get('reasoningTokens') or 0)
            if uncached + c_read + c_write + text_out + reason <= 0:
                continue
            tot_uncached += uncached
            tot_read += c_read
            tot_write += c_write
            if uncached + c_read + c_write > 0:
                last_prompt = uncached + c_read + c_write
            rec_model = str(rec.get('model') or '')
            eff_model = (rec_model if rec_model and rec_model != 'antigravity-unknown' else '') or latest_model or model or ''
            if eff_model and _lookup_model_rate(eff_model) is not None:
                priced = _price_record({
                    'model': eff_model,
                    'uncachedInputTokens': uncached,
                    'cachedInputTokens': c_read,
                    'cacheCreationTokens': c_write,
                    'outputTokens': text_out,
                    'reasoningTokens': reason,
                })
                c_val = number(priced.get('costUsd'))
                if c_val is not None:
                    tot_cost += c_val
                    has_cost = True

    if tot_uncached + tot_read + tot_write > 0 or est_used > 0:
        ctx_used = est_used if est_used > 0 else (last_prompt if last_prompt > 0 else None)
        ctx_win = max_ctx if max_ctx > 0 else (model_context_window(latest_model or model) or 256_000)
        rows = context(ctx_used, ctx_win) if ctx_used is not None else []
        if tot_uncached + tot_read + tot_write > 0:
            rows += cache_ratio(tot_read, tot_uncached + tot_read + tot_write, tr('累计 · 输入缓存命中', 'Total · input cache hit'))
        if has_cost:
            rows += _estimated_cost_rows(tot_cost)
        return rows

    if isinstance(result_usage, dict) and result_usage:
        eff_model = latest_model or model or result_usage.get('model')
        c_write = number(result_usage.get('cache_creation_tokens'))
        if c_write is None:
            c_write = number(result_usage.get('cache_write_tokens')) or 0
        prompt_in = (number(result_usage.get('input_tokens')) or 0) + (number(result_usage.get('cache_read_tokens')) or 0) + c_write
        return agy_usage(
            result_usage,
            scope=tr('CLI 累计', 'CLI total'),
            model=eff_model,
            context_used=prompt_in if prompt_in > 0 else None,
            context_window=max_ctx if max_ctx > 0 else model_context_window(eff_model),
        )
    if step_usages:
        last_step = step_usages[-1]
        last_in = (number(last_step.get('input_tokens')) or 0) + (number(last_step.get('cache_read_tokens')) or 0) + (number(last_step.get('cache_creation_tokens')) or 0)
        summed = agy_steps(step_usages)
        eff_model = latest_model or model or last_step.get('model')
        return agy_usage(
            summed,
            scope=tr('步骤累计', 'Steps total'),
            model=eff_model,
            context_used=last_in if last_in > 0 else None,
            context_window=max_ctx if max_ctx > 0 else model_context_window(eff_model),
        )
    if isinstance(acp_usage_dict, dict) and acp_usage_dict:
        eff_model = latest_model or model or acp_usage_dict.get('model')
        return agy_acp_usage(
            acp_usage_dict,
            scope=tr('本回合', 'This turn'),
            model=eff_model,
            context_window=max_ctx if max_ctx > 0 else (model_context_window(eff_model) or 256_000),
        )
    return []

def grok_context(used, window=None, model=None):
    return context(used, window, model=model)

def grok_usage(usage, scope=None, model=None):
    scope = scope or tr('进程累计', 'Process total')
    if not isinstance(usage, dict) or usage.get('usageIsIncomplete') is True:
        return []
    if not any((number(usage.get(k)) or 0) > 0 for k in ('inputTokens', 'outputTokens', 'modelCalls')):
        return []
    rows = cache_ratio(usage.get('cachedReadTokens'), usage.get('inputTokens'), scope + tr(' · 输入缓存命中', ' · input cache hit'))
    ticks = usage.get('costUsdTicks')
    cost_rows = []
    if usage.get('costIsPartial') is not True and number(ticks) is not None:
        cost_rows = [row(tr('CLI 记录费用 · USD', 'CLI recorded cost · USD'), count(Decimal(str(ticks)) / Decimal(10_000_000_000)))]
    if not cost_rows:
        resolved_model = model or usage.get('model')
        total_in = number(usage.get('inputTokens')) or 0
        c_read = number(usage.get('cachedReadTokens')) or 0
        c_write = number(usage.get('cacheCreationTokens')) or 0
        uncached = max(0, total_in - c_read - c_write)
        out_tok = number(usage.get('outputTokens')) or 0
        reason = number(usage.get('reasoningTokens')) or 0
        cost_rows = estimate_cost(
            resolved_model,
            uncached_input=uncached,
            cache_read=c_read,
            cache_write=c_write,
            output=max(0, out_tok - reason),
            reasoning=reason,
        )
    return rows + cost_rows

# Ported and adapted from t3code UsageService and UsageLimitSources.
from __future__ import annotations

import calendar
import concurrent.futures
import datetime as dt
import glob
import hashlib
import json
import math
import os
import re
import shutil
import signal
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

CACHE_VERSION = 4
ARCHIVE_VERSION = 2
GUARD_BYTES = 64
MAX_RECORDS_PER_SOURCE = 25000
LIMITS_CACHE_TTL_SECONDS = 60

MODEL_RATES_PER_M: dict[str, tuple[float, float, float, float, tuple[int, float, float, float, float] | None]] = {
    "gpt-6.1": (2.50, 15.00, 0.25, 2.50, None),
    "gpt-6": (2.50, 15.00, 0.25, 2.50, None),
    "codex-auto-review": (0.40, 1.60, 0.04, 0.40, None),
    "gpt-5.4": (2.50, 15.00, 0.25, 2.50, None),
    "gpt-5.4-mini": (0.40, 1.60, 0.04, 0.40, None),
    "gpt-5.3-codex": (1.75, 14.00, 0.175, 1.75, None),
    "gpt-5.3-codex-spark": (0.40, 1.60, 0.04, 0.40, None),
    "gpt-5.2-codex": (1.75, 14.00, 0.175, 1.75, None),
    "gpt-5.2": (1.75, 14.00, 0.175, 1.75, None),
    "gpt-5.1-codex-max": (2.50, 15.00, 0.25, 2.50, None),
    "gpt-5.1-codex": (1.50, 12.00, 0.15, 1.50, None),
    "gpt-5.1-codex-mini": (0.30, 1.20, 0.03, 0.30, None),
    "gpt-5-codex": (1.25, 10.00, 0.125, 1.25, None),
    "gpt-5": (1.25, 10.00, 0.125, 1.25, None),
    "gpt-5-mini": (0.25, 2.00, 0.025, 0.25, None),
    "gpt-5-nano": (0.05, 0.40, 0.005, 0.05, None),
    "gpt-4.1": (2.00, 8.00, 0.50, 2.00, None),
    "gpt-4.1-mini": (0.40, 1.60, 0.10, 0.40, None),
    "gpt-4o": (2.50, 10.00, 1.25, 2.50, None),
    "gpt-4o-mini": (0.15, 0.60, 0.075, 0.15, None),
    "o3": (2.00, 8.00, 0.50, 2.00, None),
    "o4-mini": (1.10, 4.40, 0.275, 1.10, None),
    "codex-mini-latest": (1.50, 6.00, 0.375, 1.50, None),
    "claude-opus-5-5": (5.00, 25.00, 0.50, 6.25, None),
    "claude-sonnet-5-5": (3.00, 15.00, 0.30, 3.75, (200_000, 6.00, 22.50, 0.60, 7.50)),
    "claude-opus-4-7": (5.00, 25.00, 0.50, 6.25, None),
    "claude-opus-4-6": (5.00, 25.00, 0.50, 6.25, None),
    "claude-opus-4-5": (5.00, 25.00, 0.50, 6.25, None),
    "claude-opus-4-1": (15.00, 75.00, 1.50, 18.75, None),
    "claude-opus-4": (15.00, 75.00, 1.50, 18.75, None),
    "claude-sonnet-4-6": (3.00, 15.00, 0.30, 3.75, (200_000, 6.00, 22.50, 0.60, 7.50)),
    "claude-sonnet-4-5": (3.00, 15.00, 0.30, 3.75, (200_000, 6.00, 22.50, 0.60, 7.50)),
    "claude-sonnet-4": (3.00, 15.00, 0.30, 3.75, (200_000, 6.00, 22.50, 0.60, 7.50)),
    "claude-3-7-sonnet": (3.00, 15.00, 0.30, 3.75, None),
    "claude-3-5-sonnet": (3.00, 15.00, 0.30, 3.75, None),
    "claude-haiku-4-5": (1.00, 5.00, 0.10, 1.25, None),
    "claude-3-5-haiku": (0.80, 4.00, 0.08, 1.00, None),
    "gemini-4-carbon": (2.50, 15.00, 0.50, 2.50, None),
    "gemini-4-argon": (2.00, 12.00, 0.50, 2.00, None),
    "gemini-4": (2.00, 12.00, 0.50, 2.00, None),
    "gemini-3.8-flash": (0.15, 0.60, 0.0375, 0.15, None),
    "gemini-3.6-flash": (0.15, 0.60, 0.0375, 0.15, None),
    "gemini-3.5-flash-lite": (0.10, 0.40, 0.025, 0.10, None),
    "gemini-3.5-flash": (0.15, 0.60, 0.0375, 0.15, None),
    "gemini-3.1-pro": (1.25, 10.00, 0.3125, 1.25, (200_000, 2.50, 15.00, 0.625, 2.50)),
    "gemini-3-pro": (1.25, 10.00, 0.3125, 1.25, (200_000, 2.50, 15.00, 0.625, 2.50)),
    "gemini-3-flash": (0.15, 0.60, 0.0375, 0.15, None),
    "gemini-2.5-pro": (1.25, 10.00, 0.3125, 1.25, (200_000, 2.50, 15.00, 0.625, 2.50)),
    "gemini-2.5-flash": (0.15, 0.60, 0.0375, 0.15, None),
    "grok-4.20": (3.00, 15.00, 0.75, 3.00, None),
    "grok-4": (3.00, 15.00, 0.75, 3.00, None),
    "grok-code-fast-1": (0.20, 1.50, 0.02, 0.20, None),
    "grok-3": (3.00, 15.00, 0.75, 3.00, None),
    "grok-3-mini": (0.30, 0.50, 0.075, 0.30, None),
    "deepseek-v4.1-flash": (0.10, 0.40, 0.025, 0.10, None),
    "deepseek-flash": (0.10, 0.40, 0.025, 0.10, None),
    "deepseek-v4": (0.27, 1.10, 0.07, 0.27, None),
    "deepseek-chat": (0.27, 1.10, 0.07, 0.27, None),
    "deepseek-reasoner": (0.55, 2.19, 0.14, 0.55, None),
    "deepseek-v3": (0.27, 1.10, 0.07, 0.27, None),
    "deepseek-r1": (0.55, 2.19, 0.14, 0.55, None),
    "kimi-k2.5": (0.60, 2.50, 0.15, 0.60, None),
    "kimi-k2": (0.60, 2.50, 0.15, 0.60, None),
    "qwen3-coder-plus": (0.35, 1.40, 0.08, 0.35, None),
    "qwen3-max": (1.20, 4.80, 0.30, 1.20, None),
    "glm-5": (0.50, 2.00, 0.125, 0.50, None),
    "glm-4.7": (0.40, 1.60, 0.10, 0.40, None),
    "minimax-m2.5": (0.30, 1.20, 0.03, 0.30, None),
}

AGY_MODEL_ENUM: dict[int, str] = {
    246: "gemini-2.5-pro",
    312: "gemini-2.5-flash",
    313: "gemini-2.5-flash-thinking",
    329: "gemini-2.5-flash-thinking",
    330: "gemini-2.5-flash-lite",
    281: "claude-sonnet-4",
    282: "claude-sonnet-4",
    290: "claude-opus-4",
    291: "claude-opus-4",
    333: "claude-sonnet-4-5",
    334: "claude-sonnet-4-5",
    340: "claude-haiku-4-5",
    341: "claude-haiku-4-5",
    1016: "gemini-3.1-pro",
    1018: "gemini-3-flash-preview",
    1020: "claude-haiku-4-5",
    1026: "claude-opus-4-6",
    1027: "claude-opus-4-6",
    1028: "claude-sonnet-4-6",
    1030: "claude-opus-4-7",
    1035: "claude-sonnet-4-6",
    1036: "gemini-3.1-pro",
    1037: "gemini-3.1-pro",
    1047: "gemini-3-flash-preview",
    1084: "gemini-3-flash-preview",
    1318: "gemini-3.8-flash",
    1319: "gemini-3.8-flash",
    1322: "gemini-3.8-flash-tiered",
    1401: "claude-opus-5-5",
    1402: "claude-opus-5-5",
    1404: "claude-sonnet-5-5",
    1405: "claude-sonnet-5-5",
    1417: "gemini-4-argon",
    1418: "gemini-4-argon",
    1419: "gemini-4-argon",
    2006: "gemini-2.5-pro",
    2007: "gemini-2.5-flash",
    2018: "gemini-3-pro",
    2019: "gemini-3-flash",
    2020: "gemini-3.1-pro",
}

def _cache_dir() -> Path:
    base = Path(os.environ.get("XDG_CACHE_HOME") or (Path.home() / ".cache")) / "luma-gui"
    try:
        base.mkdir(parents=True, exist_ok=True)
    except Exception:
        pass
    return base

def _fnv1a64(data: bytes) -> str:
    h = 0xCBF29CE484222325
    for b in data:
        h ^= b
        h = (h * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return f"{h:016x}"

def _safe_int(val: Any) -> int:
    if isinstance(val, bool):
        return 0
    if isinstance(val, (int, float)) and math.isfinite(val):
        return max(0, int(val))
    if isinstance(val, str):
        try:
            f = float(val)
            if math.isfinite(f):
                return max(0, int(f))
        except Exception:
            pass
    return 0

def _safe_float(val: Any) -> float | None:
    if isinstance(val, bool):
        return None
    if isinstance(val, (int, float)) and math.isfinite(val) and val >= 0:
        return float(val)
    if isinstance(val, str):
        try:
            f = float(val)
            if math.isfinite(f) and f >= 0:
                return f
        except Exception:
            pass
    return None

def _parse_ts_ms(val: Any) -> int | None:
    if isinstance(val, (int, float)) and not isinstance(val, bool) and math.isfinite(val):
        n = int(val)
        if n <= 0:
            return None
        if n < 10_000_000_000:
            return n * 1000
        if n > 10_000_000_000_000:
            return n // 1000
        return n
    if isinstance(val, str):
        s = val.strip()
        if not s:
            return None
        if s.isdigit():
            return _parse_ts_ms(int(s))
        try:
            if s.endswith("Z"):
                s = s[:-1] + "+00:00"
            parsed = dt.datetime.fromisoformat(s)
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=dt.timezone.utc)
            return int(parsed.timestamp() * 1000)
        except Exception:
            return None
    return None

def _normalize_model_id(raw: Any) -> str:
    if not isinstance(raw, str):
        return "unknown"
    m = raw.strip()
    if not m:
        return "unknown"
    if "/" in m:
        m = m.split("/")[-1].strip()
    if ":" in m and not m.lower().endswith((":fast", ":free")):
        m = m.split(":")[-1].strip()
    m = m.strip()
    if not m:
        return "unknown"
    low = m.lower()
    if low.startswith("claude-"):
        import re
        low = re.sub(r"^claude-(\d+(?:\.\d+)?)-(sonnet|opus|haiku)(.*)$", r"claude-\2-\1\3", low)
        low = re.sub(r"^(claude-(?:sonnet|opus|haiku)-\d+)\.(\d+)(.*)$", r"\1-\2\3", low)
        return low
    return low

def _is_variant_endpoint(model_id: str) -> bool:
    import re
    m = model_id.lower().strip()
    if not m or m in ("unknown", "antigravity-unknown", "gemini-default"):
        return False
    if any(tag in m for tag in ("-trial", "-exp", "-canary", "-nightly", "-alpha", "-beta", "-internal")):
        return True
    if re.search(r"-(?:[a-z]|[0-9][a-z])$", m) and not m.endswith(("-v1", "-v2", "-v3", "-v4")):
        return True
    return False

def _lookup_model_rate(model_id: str) -> tuple[float, float, float, float, tuple[int, float, float, float, float] | None] | None:
    m = model_id.lower().strip()
    if not m or m in ("unknown", "<synthetic>", "synthetic", "gemini-default", "antigravity-unknown"):
        return None
    if m.endswith(":fast"):
        m = m[:-5]
    if m in MODEL_RATES_PER_M:
        return MODEL_RATES_PER_M[m]
    cleaned = m
    for suffix in (
        "-thinking",
        "-extra-low",
        "-medium",
        "-high",
        "-low",
        "-preview",
        "-latest",
        "-trial",
        "-exp",
        "-canary",
        "-tiered",
    ):
        if cleaned.endswith(suffix):
            cleaned = cleaned[: -len(suffix)]
    if cleaned in MODEL_RATES_PER_M:
        return MODEL_RATES_PER_M[cleaned]
    alt_hyphen = cleaned.replace(".", "-")
    if alt_hyphen in MODEL_RATES_PER_M:
        return MODEL_RATES_PER_M[alt_hyphen]
    for key in sorted(MODEL_RATES_PER_M.keys(), key=len, reverse=True):
        if cleaned == key or cleaned.startswith(key + "-") or cleaned.startswith(key + "."):
            return MODEL_RATES_PER_M[key]
        if alt_hyphen == key or alt_hyphen.startswith(key + "-"):
            return MODEL_RATES_PER_M[key]
    return None

def _tiered_cost(tokens: int, base_per_m: float, threshold: int | None, above_per_m: float | None) -> float:
    if tokens <= 0:
        return 0.0
    if threshold is None or above_per_m is None or tokens <= threshold:
        return (tokens * base_per_m) / 1_000_000.0
    below = threshold
    above = tokens - threshold
    return ((below * base_per_m) + (above * above_per_m)) / 1_000_000.0

def _price_record(rec: dict[str, Any]) -> dict[str, Any]:
    uncached = _safe_int(rec.get("uncachedInputTokens"))
    cached = _safe_int(rec.get("cachedInputTokens"))
    cache_write = _safe_int(rec.get("cacheCreationTokens"))
    output = _safe_int(rec.get("outputTokens"))
    reasoning = _safe_int(rec.get("reasoningTokens"))
    total = uncached + cached + cache_write + output + reasoning

    model = str(rec.get("model") or "unknown")
    is_fast = str(rec.get("speed") or "") == "codex:fast" or model.lower().endswith(":fast")
    multiplier = 2.0 if is_fast else 1.0

    reported = _safe_float(rec.get("reportedCostUsd"))
    rate = _lookup_model_rate(model)
    is_explicit_free = model.lower().endswith("-free") or model.lower().endswith(":free")

    cat_in = 0.0
    cat_read = 0.0
    cat_write = 0.0
    cat_out = 0.0
    cache_savings = 0.0

    if rate is not None:
        in_m, out_m, read_m, write_m, tiered = rate
        prompt_tokens = uncached + cached + cache_write
        if tiered is not None and prompt_tokens > tiered[0]:
            _, in_m, out_m, read_m, write_m = tiered

        cat_in = (uncached * in_m / 1_000_000.0) * multiplier
        cat_read = (cached * read_m / 1_000_000.0) * multiplier
        cat_write = (cache_write * write_m / 1_000_000.0) * multiplier
        cat_out = ((output + reasoning) * out_m / 1_000_000.0) * multiplier
        full_cached_as_input = (cached * in_m / 1_000_000.0) * multiplier
        cache_savings = max(0.0, full_cached_as_input - cat_read)

    rate_total = cat_in + cat_read + cat_write + cat_out

    if reported is not None and reported > 0:
        if rate_total > 0:
            scale = reported / rate_total
            cat_in *= scale
            cat_read *= scale
            cat_write *= scale
            cat_out *= scale
        else:
            cat_in = reported
        return {
            "totalTokens": total,
            "costUsd": reported,
            "costSource": "providerReported",
            "cacheSavingsUsd": cache_savings,
            "categoryCostUsd": {
                "input": cat_in,
                "cacheRead": cat_read,
                "cacheWrite": cat_write,
                "output": cat_out,
            },
        }

    if is_explicit_free and (reported == 0.0 or rate is None):
        return {
            "totalTokens": total,
            "costUsd": 0.0,
            "costSource": "providerReported",
            "cacheSavingsUsd": cache_savings,
            "categoryCostUsd": {"input": 0.0, "cacheRead": 0.0, "cacheWrite": 0.0, "output": 0.0},
        }

    if rate is not None:
        return {
            "totalTokens": total,
            "costUsd": rate_total,
            "costSource": "modelPriced",
            "cacheSavingsUsd": cache_savings,
            "categoryCostUsd": {
                "input": cat_in,
                "cacheRead": cat_read,
                "cacheWrite": cat_write,
                "output": cat_out,
            },
        }

    return {
        "totalTokens": total,
        "costUsd": 0.0,
        "costSource": "unpriced",
        "cacheSavingsUsd": 0.0,
        "categoryCostUsd": {"input": 0.0, "cacheRead": 0.0, "cacheWrite": 0.0, "output": 0.0},
    }

def _load_scan_cache() -> dict[str, Any]:
    path = _cache_dir() / "usage-scan-cache-v1.json"
    if not path.is_file():
        return {"version": CACHE_VERSION, "files": {}}
    try:
        data = json.loads(path.read_text("utf-8"))
        if isinstance(data, dict) and data.get("version") == CACHE_VERSION and isinstance(data.get("files"), dict):
            return data
    except Exception:
        pass
    return {"version": CACHE_VERSION, "files": {}}

def _save_scan_cache(cache: dict[str, Any]) -> None:
    path = _cache_dir() / "usage-scan-cache-v1.json"
    tmp = path.with_suffix(f".tmp.{os.getpid()}")
    try:
        tmp.write_text(json.dumps(cache, ensure_ascii=False, separators=(",", ":")), "utf-8")
        tmp.replace(path)
    except Exception:
        try:
            tmp.unlink(missing_ok=True)
        except Exception:
            pass

def _read_jsonl_incremental(
    file_path: str,
    cache_files: dict[str, Any],
    line_parser,
    initial_state_fn=None,
) -> list[dict[str, Any]]:
    try:
        st = os.stat(file_path)
    except OSError:
        return []
    size = st.st_size
    mtime_ns = st.st_mtime_ns
    if size <= 0:
        return []

    prev = cache_files.get(file_path)
    if (
        isinstance(prev, dict)
        and prev.get("size") == size
        and prev.get("mtime_ns") == mtime_ns
        and isinstance(prev.get("records"), list)
    ):
        return prev["records"]

    resume_offset = 0
    guard_hash = ""
    records: list[dict[str, Any]] = []
    state = initial_state_fn() if initial_state_fn else {}

    if (
        isinstance(prev, dict)
        and isinstance(prev.get("resume_offset"), int)
        and 0 < prev["resume_offset"] <= size
        and isinstance(prev.get("records"), list)
    ):
        cand_offset = prev["resume_offset"]
        cand_guard = prev.get("guard_hash") or ""
        try:
            with open(file_path, "rb") as f:
                g_start = max(0, cand_offset - GUARD_BYTES)
                f.seek(g_start)
                g_bytes = f.read(cand_offset - g_start)
                if _fnv1a64(g_bytes) == cand_guard:
                    resume_offset = cand_offset
                    records = list(prev["records"])
                    if isinstance(prev.get("state"), dict):
                        state = dict(prev["state"])
        except Exception:
            resume_offset = 0

    try:
        with open(file_path, "rb") as f:
            f.seek(resume_offset)
            raw = f.read()
            last_nl = raw.rfind(b"\n")
            if last_nl >= 0:
                complete_chunk = raw[: last_nl + 1]
                new_resume = resume_offset + last_nl + 1
            else:
                complete_chunk = b""
                new_resume = resume_offset

            cur_offset = resume_offset
            for raw_line_with_end in complete_chunk.splitlines(keepends=True):
                line_offset = cur_offset
                cur_offset += len(raw_line_with_end)
                raw_line = raw_line_with_end.rstrip(b"\r\n")
                if not raw_line:
                    continue
                try:
                    obj = json.loads(raw_line.decode("utf-8", errors="replace"))
                except Exception:
                    continue
                if not isinstance(obj, dict):
                    continue
                state["_line_offset"] = line_offset
                parsed = line_parser(obj, state)
                if parsed:
                    if isinstance(parsed, list):
                        records.extend(parsed)
                    else:
                        records.append(parsed)
            state.pop("_line_offset", None)

            if new_resume > 0:
                g_start = max(0, new_resume - GUARD_BYTES)
                f.seek(g_start)
                guard_hash = _fnv1a64(f.read(new_resume - g_start))
            else:
                guard_hash = ""
            resume_offset = new_resume
    except Exception:
        return records

    if len(records) > MAX_RECORDS_PER_SOURCE:
        records = records[-MAX_RECORDS_PER_SOURCE:]

    cache_files[file_path] = {
        "size": size,
        "mtime_ns": mtime_ns,
        "resume_offset": resume_offset,
        "guard_hash": guard_hash,
        "state": state,
        "records": records,
    }
    return records

def _scan_claude(home: Path, cache_files: dict[str, Any], cutoff_ms: int) -> list[dict[str, Any]]:
    roots = [home / ".claude" / "projects", home / ".config" / "claude" / "projects"]
    env_dir = os.environ.get("CLAUDE_CONFIG_DIR")
    if env_dir:
        for p in env_dir.split(","):
            if p.strip():
                roots.append(Path(p.strip()) / "projects")

    files: set[str] = set()
    for r in roots:
        if r.is_dir():
            for path in r.rglob("*.jsonl"):
                try:
                    if path.stat().st_mtime_ns // 1_000_000 >= cutoff_ms - 86_400_000:
                        files.add(str(path))
                except OSError:
                    pass

    def parse_line(obj: dict[str, Any], _state: dict[str, Any]) -> dict[str, Any] | None:
        if obj.get("type") != "assistant":
            return None
        msg = obj.get("message")
        if not isinstance(msg, dict):
            return None
        usage = msg.get("usage")
        if not isinstance(usage, dict):
            return None
        model = _normalize_model_id(msg.get("model"))
        if model in ("unknown", "<synthetic>", "synthetic"):
            return None
        ts = _parse_ts_ms(obj.get("timestamp"))
        if ts is None:
            return None
        uncached = _safe_int(usage.get("input_tokens"))
        cache_read = _safe_int(usage.get("cache_read_input_tokens"))
        cache_write = _safe_int(usage.get("cache_creation_input_tokens"))
        output = _safe_int(usage.get("output_tokens"))
        if uncached + cache_read + cache_write + output <= 0:
            return None
        msg_id = str(msg.get("id") or "").strip()
        req_id = str(obj.get("requestId") or "").strip()
        dedup_key = f"claude:{msg_id}:{req_id}" if (msg_id or req_id) else None
        return {
            "cli": "Claude Code",
            "model": model,
            "timestampMs": ts,
            "uncachedInputTokens": uncached,
            "cachedInputTokens": cache_read,
            "cacheCreationTokens": cache_write,
            "outputTokens": output,
            "reasoningTokens": 0,
            "reportedCostUsd": _safe_float(obj.get("costUSD") or obj.get("total_cost_usd")),
            "dedupKey": dedup_key,
        }

    deduped: dict[str, dict[str, Any]] = {}
    anonymous: list[dict[str, Any]] = []
    for fp in sorted(files):
        for rec in _read_jsonl_incremental(fp, cache_files, parse_line):
            if rec["timestampMs"] < cutoff_ms:
                continue
            dk = rec.get("dedupKey")
            if dk:
                deduped[dk] = rec
            else:
                anonymous.append(rec)
    return list(deduped.values()) + anonymous

def _scan_codex(home: Path, cache_files: dict[str, Any], cutoff_ms: int) -> list[dict[str, Any]]:
    codex_home = Path(os.environ.get("CODEX_HOME") or (home / ".codex"))
    roots = [codex_home / "sessions", codex_home / "archived_sessions"]
    files: set[str] = set()
    for r in roots:
        if r.is_dir():
            for path in r.rglob("*.jsonl"):
                try:
                    if path.stat().st_mtime_ns // 1_000_000 >= cutoff_ms - 86_400_000:
                        files.add(str(path))
                except OSError:
                    pass

    def init_state() -> dict[str, Any]:
        return {
            "model": "gpt-5-codex",
            "fast": False,
            "prev_totals": None,
            "fork_suppress_until_ms": 0,
            "session_meta_count": 0,
        }

    def parse_line(obj: dict[str, Any], state: dict[str, Any]) -> dict[str, Any] | None:
        t = obj.get("type")
        ts = _parse_ts_ms(obj.get("timestamp"))
        payload = obj.get("payload")
        if not isinstance(payload, dict):
            payload = {}

        if t == "session_meta":
            state["session_meta_count"] = _safe_int(state.get("session_meta_count")) + 1
            if state["session_meta_count"] > 1 and ts is not None:
                state["fork_suppress_until_ms"] = ts + 1000
            m_info = payload.get("model_info") if isinstance(payload.get("model_info"), dict) else {}
            m = payload.get("model") or payload.get("model_id") or m_info.get("slug") or m_info.get("model")
            if isinstance(m, str) and m.strip():
                state["model"] = _normalize_model_id(m)
            return None

        if t == "turn_context":
            m_info = payload.get("model_info") if isinstance(payload.get("model_info"), dict) else {}
            m = payload.get("model") or payload.get("model_name") or m_info.get("slug") or m_info.get("model")
            if isinstance(m, str) and m.strip():
                state["model"] = _normalize_model_id(m)
            tier = str(payload.get("service_tier") or "").lower()
            if tier:
                state["fast"] = tier in ("fast", "priority")
            return None

        if t == "response_item":
            m = payload.get("model") or payload.get("model_name")
            if isinstance(m, str) and m.strip():
                state["model"] = _normalize_model_id(m)
            return None

        if t == "thread_settings_applied":
            settings = payload.get("settings")
            if isinstance(settings, dict):
                tier = str(settings.get("service_tier") or "").lower()
                if tier:
                    state["fast"] = tier in ("fast", "priority")
                m = settings.get("model")
                if isinstance(m, str) and m.strip():
                    state["model"] = _normalize_model_id(m)
            return None

        if t == "event_msg" and payload.get("type") in ("model_rerouted", "rerouted"):
            r_info = payload.get("info") if isinstance(payload.get("info"), dict) else {}
            rerouted = (
                payload.get("to_model")
                or payload.get("destination_model")
                or payload.get("target_model")
                or payload.get("model")
                or r_info.get("to_model")
                or r_info.get("destination_model")
                or r_info.get("model")
            )
            if isinstance(rerouted, str) and rerouted.strip():
                state["model"] = _normalize_model_id(rerouted)
            return None

        if t != "event_msg" or payload.get("type") != "token_count":
            return None

        if ts is None:
            return None
        if ts <= _safe_int(state.get("fork_suppress_until_ms")):
            info = payload.get("info")
            if isinstance(info, dict) and isinstance(info.get("total_token_usage"), dict):
                tot = info["total_token_usage"]
                state["prev_totals"] = {
                    "in": _safe_int(tot.get("input_tokens")),
                    "cached": _safe_int(tot.get("cached_input_tokens")),
                    "write": _safe_int(tot.get("cache_write_input_tokens")),
                    "out": _safe_int(tot.get("output_tokens")),
                    "reason": _safe_int(tot.get("reasoning_output_tokens")),
                }
            return None

        info = payload.get("info")
        if not isinstance(info, dict):
            return None

        tot = info.get("total_token_usage")
        last = info.get("last_token_usage")
        prev = state.get("prev_totals")

        cur_tot = None
        if isinstance(tot, dict):
            cur_tot = {
                "in": _safe_int(tot.get("input_tokens")),
                "cached": _safe_int(tot.get("cached_input_tokens")),
                "write": _safe_int(tot.get("cache_write_input_tokens")),
                "out": _safe_int(tot.get("output_tokens")),
                "reason": _safe_int(tot.get("reasoning_output_tokens")),
            }
            if prev is not None and (cur_tot["in"] == prev["in"] and cur_tot["out"] == prev["out"]):
                return None

        if isinstance(last, dict):
            d_in = _safe_int(last.get("input_tokens"))
            d_cached = _safe_int(last.get("cached_input_tokens"))
            d_write = _safe_int(last.get("cache_write_input_tokens"))
            d_out = _safe_int(last.get("output_tokens"))
            d_reason = _safe_int(last.get("reasoning_output_tokens"))
            if cur_tot is not None and prev is not None:
                diff_in = max(0, cur_tot["in"] - prev["in"])
                if diff_in > 0 and d_in > diff_in:
                    d_in = diff_in
                    d_cached = max(0, cur_tot["cached"] - prev["cached"])
                    d_write = max(0, cur_tot["write"] - prev["write"])
                    d_out = max(0, cur_tot["out"] - prev["out"])
                    d_reason = max(0, cur_tot["reason"] - prev["reason"])
        elif cur_tot is not None:
            if prev is not None:
                d_in = max(0, cur_tot["in"] - prev["in"])
                d_cached = max(0, cur_tot["cached"] - prev["cached"])
                d_write = max(0, cur_tot["write"] - prev["write"])
                d_out = max(0, cur_tot["out"] - prev["out"])
                d_reason = max(0, cur_tot["reason"] - prev["reason"])
            else:
                d_in = cur_tot["in"]
                d_cached = cur_tot["cached"]
                d_write = cur_tot["write"]
                d_out = cur_tot["out"]
                d_reason = cur_tot["reason"]
        else:
            return None

        if cur_tot is not None:
            state["prev_totals"] = cur_tot

        uncached = max(0, d_in - d_cached - d_write)
        if uncached + d_cached + d_write + d_out <= 0:
            return None

        reason_tok = min(d_out, max(0, d_reason))
        text_out = max(0, d_out - reason_tok)
        model = _normalize_model_id(
            info.get("model") or info.get("model_name") or payload.get("model") or state.get("model") or "gpt-5-codex"
        )
        return {
            "cli": "Codex",
            "model": model,
            "speed": "codex:fast" if state.get("fast") else None,
            "timestampMs": ts,
            "uncachedInputTokens": uncached,
            "cachedInputTokens": d_cached,
            "cacheCreationTokens": d_write,
            "outputTokens": text_out,
            "reasoningTokens": reason_tok,
            "reportedCostUsd": None,
            "_lineOffset": _safe_int(state.get("_line_offset")),
        }

    deduped: dict[str, dict[str, Any]] = {}
    for fp in sorted(files):
        fname = Path(fp).name
        for rec in _read_jsonl_incremental(fp, cache_files, parse_line, init_state):
            if rec["timestampMs"] >= cutoff_ms:
                dkey = f"codex:{fname}:{_safe_int(rec.get('_lineOffset'))}"
                clean_rec = dict(rec)
                clean_rec.pop("_lineOffset", None)
                deduped[dkey] = clean_rec
    return list(deduped.values())

def _scan_opencode(home: Path, cache_files: dict[str, Any], cutoff_ms: int) -> list[dict[str, Any]]:
    data_dir = Path(os.environ.get("XDG_DATA_HOME") or (home / ".local" / "share")) / "opencode"
    if not data_dir.is_dir():
        return []

    deduped: dict[str, dict[str, Any]] = {}

    def parse_msg_data(msg_id: str, time_created: Any, data: dict[str, Any]) -> dict[str, Any] | None:
        role = data.get("role") or data.get("type")
        if role and role != "assistant":
            return None
        tokens = data.get("tokens")
        if not isinstance(tokens, dict):
            return None
        inp = _safe_int(tokens.get("input"))
        out = _safe_int(tokens.get("output"))
        reason = _safe_int(tokens.get("reasoning"))
        cache = tokens.get("cache") if isinstance(tokens.get("cache"), dict) else {}
        c_read = _safe_int(cache.get("read"))
        c_write = _safe_int(cache.get("write"))
        if inp + c_read + c_write + out + reason <= 0:
            return None

        t_obj = data.get("time") if isinstance(data.get("time"), dict) else {}
        ts = _parse_ts_ms(t_obj.get("completed") or t_obj.get("created") or time_created)
        if ts is None:
            return None

        model_raw = data.get("modelID")
        if not model_raw and isinstance(data.get("model"), dict):
            model_raw = data["model"].get("id") or data["model"].get("modelID")
        elif not model_raw and isinstance(data.get("model"), str):
            model_raw = data.get("model")
        model = _normalize_model_id(model_raw)

        cost_val = _safe_float(data.get("cost"))
        return {
            "cli": "OpenCode",
            "model": model,
            "timestampMs": ts,
            "uncachedInputTokens": inp,
            "cachedInputTokens": c_read,
            "cacheCreationTokens": c_write,
            "outputTokens": out,
            "reasoningTokens": reason,
            "reportedCostUsd": cost_val,
            "dedupKey": f"opencode:{msg_id}",
        }

    for db_path in sorted(data_dir.glob("opencode*.db")):
        db_str = str(db_path)
        try:
            st = db_path.stat()
            wal_path = Path(db_str + "-wal")
            wal_st = wal_path.stat() if wal_path.exists() else None
            fp_sig = f"{st.st_size}:{st.st_mtime_ns}:{wal_st.st_size if wal_st else 0}:{wal_st.st_mtime_ns if wal_st else 0}"
        except OSError:
            continue

        cached_entry = cache_files.get(db_str)
        if isinstance(cached_entry, dict) and cached_entry.get("fp_sig") == fp_sig and isinstance(cached_entry.get("records"), list):
            db_records = cached_entry["records"]
        else:
            db_records = []
            try:
                con = sqlite3.connect(f"file:{db_str}?mode=ro", uri=True, timeout=2.0)
                try:
                    tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
                    if "session_message" in tables:
                        cur = con.execute(
                            "SELECT id, time_created, data FROM session_message WHERE type = 'assistant'"
                        )
                        for m_id, t_created, raw_data in cur:
                            if not raw_data:
                                continue
                            try:
                                d = json.loads(raw_data)
                            except Exception:
                                continue
                            if isinstance(d, dict):
                                rec = parse_msg_data(str(m_id), t_created, d)
                                if rec:
                                    db_records.append(rec)
                    if "message" in tables:
                        cur = con.execute("SELECT id, time_created, data FROM message")
                        for m_id, t_created, raw_data in cur:
                            if not raw_data:
                                continue
                            try:
                                d = json.loads(raw_data)
                            except Exception:
                                continue
                            if isinstance(d, dict):
                                rec = parse_msg_data(str(m_id), t_created, d)
                                if rec:
                                    db_records.append(rec)
                finally:
                    con.close()
            except Exception:
                pass
            if len(db_records) > MAX_RECORDS_PER_SOURCE:
                db_records = db_records[-MAX_RECORDS_PER_SOURCE:]
            cache_files[db_str] = {"fp_sig": fp_sig, "records": db_records}

        for rec in db_records:
            if rec["timestampMs"] >= cutoff_ms:
                deduped[rec["dedupKey"]] = rec

    return list(deduped.values())

def _scan_pi(home: Path, cache_files: dict[str, Any], cutoff_ms: int) -> list[dict[str, Any]]:
    cli_name, dot_dir = "pi", ".pi"
    sessions_dir = home / dot_dir / "agent" / "sessions"
    if not sessions_dir.is_dir():
        return []
    files: set[str] = set()
    for path in sessions_dir.rglob("*.jsonl"):
        try:
            if path.stat().st_mtime_ns // 1_000_000 >= cutoff_ms - 86_400_000:
                files.add(str(path))
        except OSError:
            pass

    def parse_line(obj: dict[str, Any], _state: dict[str, Any]) -> dict[str, Any] | None:
        if obj.get("type") != "message":
            return None
        msg = obj.get("message")
        if not isinstance(msg, dict) or msg.get("role") != "assistant":
            return None
        usage = msg.get("usage")
        if not isinstance(usage, dict):
            return None
        inp = _safe_int(usage.get("input"))
        out = _safe_int(usage.get("output"))
        c_read = _safe_int(usage.get("cacheRead"))
        c_write = _safe_int(usage.get("cacheWrite"))
        reason = _safe_int(usage.get("reasoning"))
        if inp + out + c_read + c_write + reason <= 0:
            return None
        ts = _parse_ts_ms(msg.get("timestamp") or obj.get("timestamp"))
        if ts is None:
            return None
        model = _normalize_model_id(msg.get("model") or obj.get("model"))
        cost_obj = usage.get("cost") if isinstance(usage.get("cost"), dict) else {}
        raw_cost = _safe_float(cost_obj.get("total") if cost_obj else usage.get("cost"))
        cost_val = raw_cost if raw_cost is not None and raw_cost > 0 else None
        entry_id = str(obj.get("id") or msg.get("id") or "")
        return {
            "cli": cli_name,
            "model": model,
            "timestampMs": ts,
            "uncachedInputTokens": inp,
            "cachedInputTokens": c_read,
            "cacheCreationTokens": c_write,
            "outputTokens": max(0, out - reason),
            "reasoningTokens": reason,
            "reportedCostUsd": cost_val,
            "dedupKey": f"{cli_name}:{entry_id}:{ts}:{model}",
        }

    deduped: dict[str, dict[str, Any]] = {}
    for fp in sorted(files):
        for rec in _read_jsonl_incremental(fp, cache_files, parse_line):
            if rec["timestampMs"] >= cutoff_ms:
                deduped[rec["dedupKey"]] = rec
    return list(deduped.values())

def _scan_grok(home: Path, cache_files: dict[str, Any], cutoff_ms: int) -> list[dict[str, Any]]:
    sessions_dir = home / ".grok" / "sessions"
    if not sessions_dir.is_dir():
        return []
    files: set[str] = set()
    for path in sessions_dir.rglob("updates.jsonl"):
        try:
            if path.stat().st_mtime_ns // 1_000_000 >= cutoff_ms - 86_400_000:
                files.add(str(path))
        except OSError:
            pass

    def parse_line(obj: dict[str, Any], _state: dict[str, Any]) -> list[dict[str, Any]] | None:
        upd = obj.get("update")
        if not isinstance(upd, dict) or upd.get("sessionUpdate") != "turn_completed":
            return None
        meta = upd.get("_meta")
        if not isinstance(meta, dict) or meta.get("usageIsIncomplete") is True:
            return None
        ts = _parse_ts_ms(obj.get("timestamp"))
        if ts is None:
            return None
        session_id = str(obj.get("sessionId") or "")
        prompt_id = str(upd.get("promptId") or "")

        by_model = meta.get("byModel")
        out_recs: list[dict[str, Any]] = []
        if isinstance(by_model, list) and by_model:
            for item in by_model:
                if not isinstance(item, dict):
                    continue
                m = _normalize_model_id(item.get("model"))
                u = item.get("usage") if isinstance(item.get("usage"), dict) else item
                total_in = _safe_int(u.get("inputTokens"))
                c_read = _safe_int(u.get("cachedReadTokens"))
                c_write = _safe_int(u.get("cacheCreationTokens"))
                uncached = max(0, total_in - c_read - c_write)
                out_tok = _safe_int(u.get("outputTokens"))
                reason = _safe_int(u.get("reasoningTokens"))
                if uncached + c_read + c_write + out_tok <= 0:
                    continue
                ticks = _safe_float(u.get("costUsdTicks"))
                cost_usd = (ticks / 10_000_000_000.0) if (ticks is not None and u.get("costIsPartial") is not True) else None
                out_recs.append({
                    "cli": "Grok Build",
                    "model": m,
                    "timestampMs": ts,
                    "uncachedInputTokens": uncached,
                    "cachedInputTokens": c_read,
                    "cacheCreationTokens": c_write,
                    "outputTokens": max(0, out_tok - reason),
                    "reasoningTokens": reason,
                    "reportedCostUsd": cost_usd,
                    "dedupKey": f"grok:{session_id}:{prompt_id}:{m}:{ts}",
                })
            return out_recs

        total_in = _safe_int(meta.get("inputTokens"))
        c_read = _safe_int(meta.get("cachedReadTokens"))
        c_write = _safe_int(meta.get("cacheCreationTokens"))
        uncached = max(0, total_in - c_read - c_write)
        out_tok = _safe_int(meta.get("outputTokens"))
        reason = _safe_int(meta.get("reasoningTokens"))
        if uncached + c_read + c_write + out_tok <= 0:
            return None
        m = _normalize_model_id(meta.get("model") or "grok-4")
        ticks = _safe_float(meta.get("costUsdTicks"))
        cost_usd = (ticks / 10_000_000_000.0) if (ticks is not None and meta.get("costIsPartial") is not True) else None
        return [{
            "cli": "Grok Build",
            "model": m,
            "timestampMs": ts,
            "uncachedInputTokens": uncached,
            "cachedInputTokens": c_read,
            "cacheCreationTokens": c_write,
            "outputTokens": max(0, out_tok - reason),
            "reasoningTokens": reason,
            "reportedCostUsd": cost_usd,
            "dedupKey": f"grok:{session_id}:{prompt_id}:{m}:{ts}",
        }]

    deduped: dict[str, dict[str, Any]] = {}
    for fp in sorted(files):
        for rec in _read_jsonl_incremental(fp, cache_files, parse_line):
            if rec["timestampMs"] >= cutoff_ms:
                deduped[rec["dedupKey"]] = rec
    return list(deduped.values())

def _scan_codebuddy(home: Path, cache_files: dict[str, Any], cutoff_ms: int) -> list[dict[str, Any]]:
    projects_dir = home / ".codebuddy" / "projects"
    if not projects_dir.is_dir():
        return []
    files: set[str] = set()
    for path in projects_dir.rglob("*.jsonl"):
        try:
            if path.stat().st_mtime_ns // 1_000_000 >= cutoff_ms - 86_400_000:
                files.add(str(path))
        except OSError:
            pass

    def parse_line(obj: dict[str, Any], _state: dict[str, Any]) -> dict[str, Any] | None:
        if obj.get("type") != "message" or obj.get("role") != "assistant":
            return None
        pd = obj.get("providerData")
        if not isinstance(pd, dict):
            return None
        raw_u = pd.get("rawUsage") if isinstance(pd.get("rawUsage"), dict) else {}
        u = pd.get("usage") if isinstance(pd.get("usage"), dict) else {}
        if not raw_u and not u:
            return None
        ts = _parse_ts_ms(obj.get("timestamp"))
        if ts is None:
            return None
        total_in = _safe_int(raw_u.get("prompt_tokens") or u.get("inputTokens"))
        c_read = _safe_int(
            raw_u.get("cache_read_input_tokens")
            or raw_u.get("prompt_cache_hit_tokens")
            or raw_u.get("cached_tokens")
        )
        c_write = _safe_int(
            raw_u.get("cache_creation_input_tokens")
            or raw_u.get("prompt_cache_write_tokens")
        )
        uncached = max(0, total_in - c_read - c_write)
        out_tok = _safe_int(raw_u.get("completion_tokens") or u.get("outputTokens"))
        reason = _safe_int(raw_u.get("completion_thinking_tokens"))
        if uncached + c_read + c_write + out_tok <= 0:
            return None
        model = _normalize_model_id(pd.get("model") or pd.get("requestModelId") or "unknown")
        msg_id = str(pd.get("messageId") or pd.get("conversationRequestId") or obj.get("id") or "")
        return {
            "cli": "CodeBuddy Code",
            "model": model,
            "timestampMs": ts,
            "uncachedInputTokens": uncached,
            "cachedInputTokens": c_read,
            "cacheCreationTokens": c_write,
            "outputTokens": max(0, out_tok - reason),
            "reasoningTokens": reason,
            "reportedCostUsd": None,
            "dedupKey": f"codebuddy:{msg_id}",
        }

    deduped: dict[str, dict[str, Any]] = {}
    for fp in sorted(files):
        for rec in _read_jsonl_incremental(fp, cache_files, parse_line):
            if rec["timestampMs"] >= cutoff_ms:
                deduped[rec["dedupKey"]] = rec
    return list(deduped.values())

def _read_varint(buf: bytes, pos: int, end: int) -> tuple[int, int] | None:
    result = 0
    shift = 0
    while pos < end and shift < 64:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            return result, pos
        shift += 7
    return None

def _iter_proto_fields(buf: bytes, start: int = 0, end: int | None = None):
    if end is None:
        end = len(buf)
    pos = start
    while pos < end:
        tag_res = _read_varint(buf, pos, end)
        if tag_res is None:
            return
        tag, pos = tag_res
        field_no = tag >> 3
        wire_type = tag & 0x07
        if field_no <= 0:
            return
        if wire_type == 0:
            v_res = _read_varint(buf, pos, end)
            if v_res is None:
                return
            val, pos = v_res
            yield field_no, 0, val
        elif wire_type == 1:
            if pos + 8 > end:
                return
            pos += 8
        elif wire_type == 2:
            l_res = _read_varint(buf, pos, end)
            if l_res is None:
                return
            length, pos = l_res
            if length < 0 or pos + length > end:
                return
            yield field_no, 2, buf[pos : pos + length]
            pos += length
        elif wire_type == 5:
            if pos + 4 > end:
                return
            pos += 4
        else:
            return

def _proto_fields(buf: bytes) -> dict[int, list[int | bytes]]:
    res: dict[int, list[int | bytes]] = {}
    for fno, wt, val in _iter_proto_fields(buf):
        if wt in (0, 2):
            res.setdefault(fno, []).append(val)
    return res

def _proto_num(fields: dict[int, list[int | bytes]], key: int) -> int:
    vals = fields.get(key)
    if vals and isinstance(vals[0], int):
        return vals[0]
    return 0

def _proto_bytes(fields: dict[int, list[int | bytes]], key: int) -> bytes | None:
    vals = fields.get(key)
    if vals and isinstance(vals[0], bytes):
        return vals[0]
    return None

def _proto_nested(fields: dict[int, list[int | bytes]], key: int) -> dict[int, list[int | bytes]]:
    b = _proto_bytes(fields, key)
    return _proto_fields(b) if b is not None else {}

def _proto_text(fields: dict[int, list[int | bytes]], key: int) -> str:
    b = _proto_bytes(fields, key)
    if b is None:
        return ""
    try:
        return b.decode("utf-8").strip()
    except Exception:
        return ""

def _proto_timestamp(fields: dict[int, list[int | bytes]]) -> int | None:
    sec = _proto_num(fields, 1)
    if 1_600_000_000 <= sec <= 4_102_444_800:
        return sec * 1000 + (_proto_num(fields, 2) // 1_000_000)
    return None

def _agy_model_name(name: str, enum_id: int, is_ui_label: bool = False) -> str:
    if name:
        raw = name.strip()
        if not is_ui_label and " " not in raw and "(" not in raw:
            return _normalize_model_id(raw)
        norm = re.sub(r"\s*\([^)]*\)\s*$", "", raw.lower()).replace(" ", "-")
        if norm.startswith("claude-"):
            norm = re.sub(r"^claude-(4(?:\.\d+)?)-(sonnet|opus|haiku)", r"claude-\2-\1", norm).replace(".", "-")
        return _normalize_model_id(norm)
    return AGY_MODEL_ENUM.get(enum_id) or (f"antigravity-model-{enum_id}" if enum_id > 0 else "")


def _extract_agy_usages(data: dict[int, list[int | bytes]], usage_field: int, retry_field: int) -> list[dict[int, list[int | bytes]]]:
    usages: list[dict[int, list[int | bytes]]] = []
    usage_bytes = _proto_bytes(data, usage_field)
    if usage_bytes is not None:
        usages.append(_proto_fields(usage_bytes))
    for retry in data.get(retry_field, []):
        if isinstance(retry, bytes):
            ru = _proto_bytes(_proto_fields(retry), 2)
            if ru is not None:
                usages.append(_proto_fields(ru))
    return usages


def _extract_agy_db_session(db_path: Path, fallback_ts: int = 0) -> tuple[list[dict[str, Any]], int, int, str]:
    db_str = str(db_path)
    raw_candidates: list[dict[str, Any]] = []
    est_used = 0
    max_ctx = 0
    latest_model = ""
    try:
        con = sqlite3.connect(f"file:{db_str}?mode=ro", uri=True, timeout=2.0)
        try:
            tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            traj_ts: int | None = None
            if "trajectory_metadata_blob" in tables:
                for (blob,) in con.execute("SELECT data FROM trajectory_metadata_blob WHERE data IS NOT NULL"):
                    b = bytes(blob) if not isinstance(blob, bytes) else blob
                    traj_ts = traj_ts or _proto_timestamp(_proto_nested(_proto_fields(b), 2))

            ts_by_step_idx: dict[int, int] = {}
            ts_by_resp_id: dict[str, int] = {}
            ts_by_gen_idx: dict[int, int] = {}
            legacy_steps: list[tuple[int, str, int | None, list[dict[int, list[int | bytes]]]]] = []

            if "steps" in tables:
                step_cols = {r[1] for r in con.execute("PRAGMA table_info(steps)")}
                step_sql = (
                    "SELECT idx, step_type, metadata FROM steps WHERE metadata IS NOT NULL ORDER BY idx"
                    if "step_type" in step_cols
                    else "SELECT idx, 0, metadata FROM steps WHERE metadata IS NOT NULL ORDER BY idx"
                )
                for idx, step_type, blob in con.execute(step_sql):
                    if not isinstance(idx, int):
                        continue
                    b = bytes(blob) if not isinstance(blob, bytes) else blob
                    s_fields = _proto_fields(b)
                    s_ts = _proto_timestamp(_proto_nested(s_fields, 8)) or _proto_timestamp(_proto_nested(s_fields, 1))
                    if s_ts is not None:
                        ts_by_step_idx[idx] = s_ts
                        if step_type == 15 or 9 in s_fields:
                            s_usage = _proto_nested(s_fields, 9)
                            rid = _proto_text(s_usage, 11)
                            if rid:
                                ts_by_resp_id.setdefault(rid, s_ts)
                        g_link = _proto_nested(s_fields, 20)
                        if 3 in g_link:
                            ts_by_gen_idx.setdefault(_proto_num(g_link, 3), s_ts)
                    s_usages = _extract_agy_usages(s_fields, 9, 28)
                    m_fields = _proto_nested(s_fields, 24)
                    s_raw = _proto_text(m_fields, 12) or _proto_text(m_fields, 8)
                    s_enum = _proto_num(m_fields, 1)
                    s_model = _agy_model_name(s_raw, s_enum)
                    if s_model and s_model != "antigravity-unknown":
                        latest_model = _normalize_model_id(s_model)
                    if s_usages:
                        legacy_steps.append((idx, s_model, s_ts, s_usages))

            gen_rows: list[tuple[int, bool, int, str, str, int, int | None, list[dict[int, list[int | bytes]]]]] = []
            if "gen_metadata" in tables:
                for idx, blob in con.execute("SELECT idx, data FROM gen_metadata WHERE data IS NOT NULL ORDER BY idx"):
                    if not isinstance(idx, int):
                        continue
                    b = bytes(blob) if not isinstance(blob, bytes) else blob
                    root = _proto_fields(b)
                    if _proto_bytes(root, 1) is None:
                        continue
                    has_step_ref = 2 in root
                    step_idx_ref = _proto_num(root, 2)
                    chat = _proto_nested(root, 1)
                    ctx_meta = _proto_nested(_proto_nested(chat, 9), 10)
                    if ctx_meta:
                        u_est = _proto_num(ctx_meta, 1)
                        m_ctx = _proto_num(ctx_meta, 4)
                        if u_est > 0:
                            est_used = u_est
                        if m_ctx > 0:
                            max_ctx = m_ctx
                    resp_model = _proto_text(chat, 19).strip()
                    ui_label = _proto_text(chat, 21).strip()
                    enum_id = _proto_num(chat, 3)
                    direct_ts = _proto_timestamp(_proto_nested(_proto_nested(chat, 9), 4))
                    usages = _extract_agy_usages(chat, 4, 17)
                    if usages:
                        gen_rows.append((idx, has_step_ref, step_idx_ref, resp_model, ui_label, enum_id, direct_ts, usages))
                    else:
                        cand_m = _agy_model_name(resp_model or ui_label, enum_id, is_ui_label=bool(ui_label and not resp_model))
                        if cand_m and cand_m != "antigravity-unknown":
                            latest_model = cand_m

            def _is_concrete_resp_model(m: str) -> bool:
                return bool(m) and m.lower() != "gemini-default"

            if gen_rows:
                label_to_model: dict[str, str | None] = {}
                concrete_models: set[str] = set()
                has_unlabelled_concrete = False
                for _, _, _, resp_m, ui_lbl, _, _, _ in gen_rows:
                    if _is_concrete_resp_model(resp_m):
                        norm_m = _agy_model_name(resp_m, 0, is_ui_label=False)
                        concrete_models.add(norm_m)
                        if ui_lbl:
                            if ui_lbl not in label_to_model:
                                label_to_model[ui_lbl] = norm_m
                            elif label_to_model[ui_lbl] != norm_m:
                                label_to_model[ui_lbl] = None
                        else:
                            has_unlabelled_concrete = True
                sole_model = (
                    next(iter(concrete_models))
                    if len(concrete_models) == 1 and not has_unlabelled_concrete
                    else None
                )

                for e_idx, (gen_idx, has_step_ref, step_idx_ref, resp_m, ui_lbl, enum_id, direct_ts, usages) in enumerate(gen_rows):
                    if _is_concrete_resp_model(resp_m):
                        resolved_model = _agy_model_name(resp_m, enum_id, is_ui_label=False)
                        m_prio = 3
                    elif ui_lbl and label_to_model.get(ui_lbl):
                        resolved_model = label_to_model[ui_lbl] or "antigravity-unknown"
                        m_prio = 3
                    elif ui_lbl and sole_model:
                        resolved_model = sole_model
                        m_prio = 3
                    elif ui_lbl:
                        resolved_model = _agy_model_name(ui_lbl, enum_id, is_ui_label=True)
                        m_prio = 2
                    elif resp_m:
                        resolved_model = _agy_model_name(resp_m, enum_id, is_ui_label=False)
                        m_prio = 1
                    else:
                        resolved_model = _agy_model_name("", enum_id) or "antigravity-unknown"
                        m_prio = 0 if resolved_model == "antigravity-unknown" else 1
                    if resolved_model and resolved_model != "antigravity-unknown":
                        latest_model = resolved_model

                    for u_idx, usage in enumerate(usages):
                        uncached = _proto_num(usage, 1) + _proto_num(usage, 2)
                        c_read = _proto_num(usage, 5)
                        c_write = _proto_num(usage, 4)
                        u9 = _proto_num(usage, 9)
                        u10 = _proto_num(usage, 10)
                        u3 = _proto_num(usage, 3)
                        if u9 > 0 or u10 > 0:
                            text_out = u9
                            reason_out = u10
                        else:
                            text_out = u3
                            reason_out = 0
                        if uncached + c_read + c_write + text_out + reason_out <= 0:
                            continue
                        keys = []
                        for k in (11, 12, 7):
                            kid = _proto_text(usage, k)
                            if kid:
                                keys.append(f"agy:{k}:{kid}")
                        rid = _proto_text(usage, 11)
                        u_ts = (
                            direct_ts
                            or (ts_by_step_idx.get(step_idx_ref) if has_step_ref else None)
                            or (ts_by_resp_id.get(rid) if rid else None)
                            or ts_by_gen_idx.get(gen_idx)
                        )
                        raw_candidates.append({
                            "cli": "Antigravity",
                            "model": resolved_model or "antigravity-unknown",
                            "uiLabel": ui_lbl or None,
                            "modelPriority": m_prio,
                            "timestampMs": u_ts or traj_ts or fallback_ts,
                            "tsQuality": 2 if u_ts is not None else (1 if traj_ts is not None else 0),
                            "uncachedInputTokens": uncached,
                            "cachedInputTokens": c_read,
                            "cacheCreationTokens": c_write,
                            "outputTokens": text_out,
                            "reasoningTokens": reason_out,
                            "reportedCostUsd": None,
                            "keys": keys or [f"agy:{db_path.stem}:gen:{e_idx}:{u_idx}"],
                        })
            else:
                for e_idx, (idx, entry_model, entry_ts, usages) in enumerate(legacy_steps):
                    for u_idx, usage in enumerate(usages):
                        uncached = _proto_num(usage, 1) + _proto_num(usage, 2)
                        c_read = _proto_num(usage, 5)
                        c_write = _proto_num(usage, 4)
                        u9 = _proto_num(usage, 9)
                        u10 = _proto_num(usage, 10)
                        u3 = _proto_num(usage, 3)
                        text_out = u9 if (u9 > 0 or u10 > 0) else u3
                        reason_out = u10 if (u9 > 0 or u10 > 0) else 0
                        if uncached + c_read + c_write + text_out + reason_out <= 0:
                            continue
                        keys = [f"agy:{k}:{_proto_text(usage, k)}" for k in (11, 12, 7) if _proto_text(usage, k)]
                        model_id = _normalize_model_id(entry_model or "antigravity-unknown")
                        if model_id != "antigravity-unknown":
                            latest_model = model_id
                        raw_candidates.append({
                            "cli": "Antigravity",
                            "model": model_id,
                            "uiLabel": None,
                            "modelPriority": 1 if model_id != "antigravity-unknown" else 0,
                            "timestampMs": entry_ts or traj_ts or fallback_ts,
                            "tsQuality": 2 if entry_ts is not None else (1 if traj_ts is not None else 0),
                            "uncachedInputTokens": uncached,
                            "cachedInputTokens": c_read,
                            "cacheCreationTokens": c_write,
                            "outputTokens": text_out,
                            "reasoningTokens": reason_out,
                            "reportedCostUsd": None,
                            "keys": keys or [f"agy:{db_path.stem}:step:{e_idx}:{u_idx}"],
                        })
        finally:
            con.close()
    except Exception:
        pass

    key_to_group: dict[str, int] = {}
    groups: dict[int, dict[str, Any]] = {}
    for idx, cand in enumerate(raw_candidates):
        ks = cand["keys"]
        matched_gids = {key_to_group[k] for k in ks if k in key_to_group}
        if not matched_gids:
            gid = idx
            groups[gid] = dict(cand)
        else:
            gid = min(matched_gids)
            base = groups[gid]
            for other_gid in matched_gids - {gid}:
                other = groups.pop(other_gid, None)
                if other:
                    if other.get("modelPriority", 0) > base.get("modelPriority", 0):
                        base["model"] = other["model"]
                        base["modelPriority"] = other.get("modelPriority", 0)
                    if not base.get("uiLabel") and other.get("uiLabel"):
                        base["uiLabel"] = other["uiLabel"]
                    if other["tsQuality"] > base["tsQuality"]:
                        base["timestampMs"] = other["timestampMs"]
                        base["tsQuality"] = other["tsQuality"]
                    for tk in ("uncachedInputTokens", "cachedInputTokens", "cacheCreationTokens", "outputTokens", "reasoningTokens"):
                        base[tk] = max(base[tk], other[tk])
                for k2, g2 in list(key_to_group.items()):
                    if g2 == other_gid:
                        key_to_group[k2] = gid
            if cand.get("modelPriority", 0) > base.get("modelPriority", 0):
                base["model"] = cand["model"]
                base["modelPriority"] = cand.get("modelPriority", 0)
            if not base.get("uiLabel") and cand.get("uiLabel"):
                base["uiLabel"] = cand["uiLabel"]
            if cand["tsQuality"] > base["tsQuality"]:
                base["timestampMs"] = cand["timestampMs"]
                base["tsQuality"] = cand["tsQuality"]
            for tk in ("uncachedInputTokens", "cachedInputTokens", "cacheCreationTokens", "outputTokens", "reasoningTokens"):
                base[tk] = max(base[tk], cand[tk])
        for k in ks:
            key_to_group[k] = gid

    db_records: list[dict[str, Any]] = []
    for rec in groups.values():
        rec.pop("keys", None)
        rec.pop("tsQuality", None)
        rec.pop("modelPriority", None)
        db_records.append(rec)
    return db_records, est_used, max_ctx, latest_model


def _scan_antigravity(home: Path, cache_files: dict[str, Any], cutoff_ms: int) -> list[dict[str, Any]]:
    conv_dirs = [
        home / ".gemini" / "antigravity-cli" / "conversations",
        home / ".gemini" / "antigravity" / "conversations",
    ]
    dbs: list[Path] = []
    seen_real: set[str] = set()
    for d in conv_dirs:
        if d.is_dir():
            for p in d.glob("*.db"):
                try:
                    rp = os.path.realpath(p)
                    if rp in seen_real:
                        continue
                    seen_real.add(rp)
                    if p.stat().st_mtime_ns // 1_000_000 >= cutoff_ms - 86_400_000:
                        dbs.append(p)
                except OSError:
                    pass

    all_records: list[dict[str, Any]] = []
    for db_path in sorted(dbs):
        db_str = str(db_path)
        try:
            st = db_path.stat()
            wal_path = Path(db_str + "-wal")
            wal_st = wal_path.stat() if wal_path.exists() else None
            fallback_ts = st.st_mtime_ns // 1_000_000
            fp_sig = f"v4:{st.st_size}:{st.st_mtime_ns}:{wal_st.st_size if wal_st else 0}:{wal_st.st_mtime_ns if wal_st else 0}"
        except OSError:
            continue

        cached_entry = cache_files.get(db_str)
        if isinstance(cached_entry, dict) and cached_entry.get("fp_sig") == fp_sig and isinstance(cached_entry.get("records"), list):
            db_records = cached_entry["records"]
        else:
            db_records, _, _, _ = _extract_agy_db_session(db_path, fallback_ts)
            cache_files[db_str] = {"fp_sig": fp_sig, "records": db_records}

        for rec in db_records:
            if rec["timestampMs"] >= cutoff_ms:
                all_records.append(rec)

    return all_records

def _load_daily_archive() -> dict[str, Any]:
    p = _cache_dir() / "usage-daily-archive-v1.json"
    if not p.is_file():
        return {"version": ARCHIVE_VERSION, "seededTimezones": [], "byTz": {}}
    try:
        data = json.loads(p.read_text("utf-8"))
        if isinstance(data, dict) and data.get("version") == ARCHIVE_VERSION and isinstance(data.get("byTz"), dict):
            if not isinstance(data.get("seededTimezones"), list):
                data["seededTimezones"] = []
            return data
    except Exception:
        pass
    return {"version": ARCHIVE_VERSION, "seededTimezones": [], "byTz": {}}


def _save_daily_archive(archive: dict[str, Any]) -> None:
    p = _cache_dir() / "usage-daily-archive-v1.json"
    tmp = p.with_suffix(".tmp")
    try:
        tmp.write_text(json.dumps(archive, ensure_ascii=False), "utf-8")
        tmp.replace(p)
    except Exception:
        pass


def _compute_activity_summary(tz_archive: dict[str, dict[str, Any]], today_start: dt.datetime) -> dict[str, Any]:
    today_key = today_start.strftime("%Y-%m-%d")
    yesterday_key = (today_start - dt.timedelta(days=1)).strftime("%Y-%m-%d")

    day_totals: dict[str, tuple[int, float]] = {}
    for dk, m_map in tz_archive.items():
        if dk > today_key or not isinstance(m_map, dict):
            continue
        tok = 0
        cost = 0.0
        for entry in m_map.values():
            if isinstance(entry, dict):
                tok += _safe_int(entry.get("totalTokens"))
                cost += float(_safe_float(entry.get("costUsd")) or 0.0)
        if tok > 0:
            day_totals[dk] = (tok, cost)

    active_dates = sorted(day_totals.keys())
    active_days = len(active_dates)

    longest_streak = 0
    cur_run = 0
    prev_date: dt.date | None = None
    for dk in active_dates:
        try:
            d_obj = dt.date.fromisoformat(dk)
        except ValueError:
            continue
        if prev_date is not None and (d_obj - prev_date).days == 1:
            cur_run += 1
        else:
            cur_run = 1
        if cur_run > longest_streak:
            longest_streak = cur_run
        prev_date = d_obj

    current_streak = 0
    anchor_key = today_key if today_key in day_totals else (yesterday_key if yesterday_key in day_totals else None)
    if anchor_key is not None:
        cursor = dt.date.fromisoformat(anchor_key)
        while cursor.isoformat() in day_totals:
            current_streak += 1
            cursor -= dt.timedelta(days=1)

    peak_day_date: str | None = None
    peak_day_tokens = 0
    for dk, (tok, _) in day_totals.items():
        if tok > peak_day_tokens or (tok == peak_day_tokens and peak_day_date is not None and dk > peak_day_date):
            peak_day_tokens = tok
            peak_day_date = dk

    cur_week_mon = today_start - dt.timedelta(days=today_start.weekday())
    heat_start = cur_week_mon - dt.timedelta(weeks=25)
    total_heat_days = (today_start - heat_start).days + 1
    heat_days: list[dict[str, Any]] = []
    for i in range(total_heat_days):
        d_dt = heat_start + dt.timedelta(days=i)
        dk = d_dt.strftime("%Y-%m-%d")
        tok, cost = day_totals.get(dk, (0, 0.0))
        heat_days.append({
            "date": dk,
            "totalTokens": tok,
            "costUsd": round(cost, 6),
        })

    return {
        "days": heat_days,
        "activeDays": active_days,
        "currentStreak": current_streak,
        "longestStreak": longest_streak,
        "peakDayDate": peak_day_date,
        "peakDayTokens": peak_day_tokens,
    }


def collect_summary(
    days: int = 30,
    tz_name: str = "UTC",
    refresh: bool = False,
    period: str | None = None,
) -> dict[str, Any]:
    now_dt = dt.datetime.now(dt.timezone.utc)
    now_ms = int(now_dt.timestamp() * 1000)

    resolved_tz_key = tz_name.strip() if isinstance(tz_name, str) and tz_name.strip() else "LOCAL"
    try:
        from zoneinfo import ZoneInfo
        tz = ZoneInfo(tz_name) if tz_name else dt.datetime.now().astimezone().tzinfo
    except Exception:
        tz = dt.datetime.now().astimezone().tzinfo or dt.timezone.utc

    local_now = now_dt.astimezone(tz)
    today_start = local_now.replace(hour=0, minute=0, second=0, microsecond=0)
    today_key = today_start.strftime("%Y-%m-%d")
    month_start = today_start.replace(day=1)
    month_prefix = today_start.strftime("%Y-%m-")

    norm_period = period.strip().lower() if isinstance(period, str) and period.strip() else None
    if norm_period not in ("today", "week", "month", "all"):
        norm_period = None

    if norm_period == "today":
        effective_period = "today"
        effective_days = 1
        hourly = True
        start_dt = today_start
        period_cutoff_ms = int(start_dt.timestamp() * 1000)
    elif norm_period == "week":
        effective_period = "week"
        effective_days = 7
        hourly = False
        start_dt = today_start - dt.timedelta(days=today_start.weekday())
        period_cutoff_ms = int(start_dt.timestamp() * 1000)
    elif norm_period == "month":
        effective_period = "month"
        _, days_in_month = calendar.monthrange(today_start.year, today_start.month)
        effective_days = days_in_month
        hourly = False
        start_dt = month_start
        period_cutoff_ms = int(start_dt.timestamp() * 1000)
    elif norm_period == "all":
        effective_period = "all"
        effective_days = 365
        hourly = False
        start_dt = today_start
        period_cutoff_ms = 0
    else:
        effective_period = "custom"
        effective_days = max(1, min(365, int(days)))
        hourly = effective_days == 1
        if hourly:
            cur_hour = local_now.replace(minute=0, second=0, microsecond=0)
            start_dt = cur_hour - dt.timedelta(hours=23)
        else:
            start_dt = today_start - dt.timedelta(days=effective_days - 1)
        period_cutoff_ms = int(start_dt.timestamp() * 1000)

    archive = _load_daily_archive()
    seeded_tzs: list[str] = archive.setdefault("seededTimezones", [])
    tz_archive: dict[str, dict[str, Any]] = archive.setdefault("byTz", {}).setdefault(resolved_tz_key, {})

    rolling_30d_start = today_start - dt.timedelta(days=29)
    rolling_30d_start_key = rolling_30d_start.strftime("%Y-%m-%d")
    rolling_30d_cutoff_ms = int(rolling_30d_start.timestamp() * 1000)
    if resolved_tz_key not in seeded_tzs or norm_period == "all" or refresh:
        scan_cutoff_ms = 0
    else:
        scan_cutoff_ms = min(period_cutoff_ms, rolling_30d_cutoff_ms)

    scan_cache = {"version": CACHE_VERSION, "files": {}} if refresh else _load_scan_cache()
    cache_files = scan_cache.setdefault("files", {})
    home = Path.home()

    records: list[dict[str, Any]] = []
    records.extend(_scan_codex(home, cache_files, scan_cutoff_ms))
    records.extend(_scan_claude(home, cache_files, scan_cutoff_ms))
    records.extend(_scan_antigravity(home, cache_files, scan_cutoff_ms))
    records.extend(_scan_opencode(home, cache_files, scan_cutoff_ms))
    records.extend(_scan_pi(home, cache_files, scan_cutoff_ms))
    records.extend(_scan_grok(home, cache_files, scan_cutoff_ms))
    records.extend(_scan_codebuddy(home, cache_files, scan_cutoff_ms))

    _save_scan_cache(scan_cache)

    scan_start_day_key = (
        ""
        if scan_cutoff_ms <= 0
        else dt.datetime.fromtimestamp(scan_cutoff_ms / 1000.0, tz=dt.timezone.utc).astimezone(tz).strftime("%Y-%m-%d")
    )

    live_daily: dict[str, dict[str, dict[str, Any]]] = {}
    priced_records: list[tuple[dict[str, Any], dt.datetime, dict[str, Any]]] = []

    for rec in records:
        ts_ms = rec["timestampMs"]
        if ts_ms < scan_cutoff_ms or ts_ms > now_ms + 3_600_000:
            continue
        priced = _price_record(rec)
        tot_tok = priced["totalTokens"]
        if tot_tok <= 0:
            continue
        rec_dt = dt.datetime.fromtimestamp(ts_ms / 1000.0, tz=dt.timezone.utc).astimezone(tz)
        priced_records.append((rec, rec_dt, priced))

        day_key = rec_dt.strftime("%Y-%m-%d")
        if scan_start_day_key and day_key < scan_start_day_key:
            continue
        cli = rec["cli"]
        model = rec["model"]
        m_key = f"{cli}::{model}"
        day_slot = live_daily.setdefault(day_key, {})
        d_entry = day_slot.setdefault(
            m_key,
            {
                "cli": cli,
                "model": model,
                "events": 0,
                "uncachedInputTokens": 0,
                "cachedInputTokens": 0,
                "cacheCreationTokens": 0,
                "outputTokens": 0,
                "reasoningTokens": 0,
                "totalTokens": 0,
                "costUsd": 0.0,
                "providerReportedCostUsd": 0.0,
                "modelPricedCostUsd": 0.0,
                "unpricedEvents": 0,
                "unpricedTokens": 0,
                "cacheSavingsUsd": 0.0,
                "categoryCostUsd": {"input": 0.0, "cacheRead": 0.0, "cacheWrite": 0.0, "output": 0.0},
                "uiLabels": [],
            },
        )
        cost_usd = priced["costUsd"]
        cost_src = priced["costSource"]
        d_entry["events"] += 1
        d_entry["uncachedInputTokens"] += rec["uncachedInputTokens"]
        d_entry["cachedInputTokens"] += rec["cachedInputTokens"]
        d_entry["cacheCreationTokens"] += rec["cacheCreationTokens"]
        d_entry["outputTokens"] += rec["outputTokens"]
        d_entry["reasoningTokens"] += rec["reasoningTokens"]
        d_entry["totalTokens"] += tot_tok
        d_entry["costUsd"] += cost_usd
        d_entry["cacheSavingsUsd"] += priced["cacheSavingsUsd"]
        for ck in ("input", "cacheRead", "cacheWrite", "output"):
            d_entry["categoryCostUsd"][ck] += priced["categoryCostUsd"][ck]
        if cost_src == "providerReported":
            d_entry["providerReportedCostUsd"] += cost_usd
        elif cost_src == "modelPriced":
            d_entry["modelPricedCostUsd"] += cost_usd
        else:
            d_entry["unpricedEvents"] += 1
            d_entry["unpricedTokens"] += tot_tok
        ui_lbl = rec.get("uiLabel")
        if isinstance(ui_lbl, str) and ui_lbl and ui_lbl not in d_entry["uiLabels"] and len(d_entry["uiLabels"]) < 4:
            d_entry["uiLabels"].append(ui_lbl)

    for day_key, live_models in live_daily.items():
        arch_day = tz_archive.setdefault(day_key, {})
        for m_key, live_entry in live_models.items():
            existing = arch_day.get(m_key)
            if not isinstance(existing, dict) or _safe_int(live_entry.get("totalTokens")) >= _safe_int(existing.get("totalTokens")):
                arch_day[m_key] = live_entry

    prune_before_key = (today_start - dt.timedelta(days=400)).strftime("%Y-%m-%d")
    for dk in [k for k in tz_archive.keys() if k < prune_before_key]:
        tz_archive.pop(dk, None)
    if resolved_tz_key not in seeded_tzs:
        seeded_tzs.append(resolved_tz_key)
    _save_daily_archive(archive)

    rolling_30d_cost_by_cli: dict[str, float] = {}
    for dk, m_map in tz_archive.items():
        if dk < rolling_30d_start_key or dk > today_key or not isinstance(m_map, dict):
            continue
        for entry in m_map.values():
            if isinstance(entry, dict):
                c_name = str(entry.get("cli") or "")
                c_cost = float(_safe_float(entry.get("costUsd")) or 0.0)
                if c_name and c_cost > 0:
                    rolling_30d_cost_by_cli[c_name] = round(rolling_30d_cost_by_cli.get(c_name, 0.0) + c_cost, 6)

    activity = _compute_activity_summary(tz_archive, today_start)

    buckets: list[dict[str, Any]] = []
    bucket_index: dict[str, dict[str, Any]] = {}
    bucket_granularity = "hour" if hourly else "day"

    if hourly:
        for i in range(24):
            b_dt = start_dt + dt.timedelta(hours=i)
            key = b_dt.strftime("%Y-%m-%dT%H:00")
            entry = {
                "date": key,
                "label": b_dt.strftime("%H:00"),
                "totalTokens": 0,
                "costUsd": 0.0,
                "byCli": {},
            }
            buckets.append(entry)
            bucket_index[key] = entry
    elif effective_period == "all":
        active_arch_keys = sorted(k for k, v in tz_archive.items() if k <= today_key and v)
        earliest_key = active_arch_keys[0] if active_arch_keys else today_key
        try:
            earliest_date = dt.date.fromisoformat(earliest_key)
        except ValueError:
            earliest_date = today_start.date()
        span_days = max(1, (today_start.date() - earliest_date).days + 1)
        effective_days = span_days
        if span_days <= 60:
            chart_days = max(14, span_days)
            c_start = today_start - dt.timedelta(days=chart_days - 1)
            for i in range(chart_days):
                b_dt = c_start + dt.timedelta(days=i)
                key = b_dt.strftime("%Y-%m-%d")
                entry = {
                    "date": key,
                    "label": b_dt.strftime("%m-%d"),
                    "totalTokens": 0,
                    "costUsd": 0.0,
                    "byCli": {},
                }
                buckets.append(entry)
                bucket_index[key] = entry
        else:
            bucket_granularity = "month"
            cur_y, cur_m = earliest_date.year, earliest_date.month
            end_y, end_m = today_start.year, today_start.month
            while (cur_y, cur_m) <= (end_y, end_m):
                key = f"{cur_y:04d}-{cur_m:02d}"
                entry = {
                    "date": key,
                    "label": f"{cur_y % 100:02d}-{cur_m:02d}",
                    "totalTokens": 0,
                    "costUsd": 0.0,
                    "byCli": {},
                }
                buckets.append(entry)
                bucket_index[key] = entry
                if cur_m == 12:
                    cur_y += 1
                    cur_m = 1
                else:
                    cur_m += 1
    else:
        for i in range(effective_days):
            b_dt = start_dt + dt.timedelta(days=i)
            key = b_dt.strftime("%Y-%m-%d")
            label = b_dt.strftime("%d") if effective_period == "month" else b_dt.strftime("%m-%d")
            entry = {
                "date": key,
                "label": label,
                "totalTokens": 0,
                "costUsd": 0.0,
                "byCli": {},
            }
            buckets.append(entry)
            bucket_index[key] = entry

    totals = {
        "events": 0,
        "uncachedInputTokens": 0,
        "cachedInputTokens": 0,
        "cacheCreationTokens": 0,
        "outputTokens": 0,
        "reasoningTokens": 0,
        "totalTokens": 0,
        "costUsd": 0.0,
        "providerReportedCostUsd": 0.0,
        "modelPricedCostUsd": 0.0,
        "unpricedEvents": 0,
        "unpricedTokens": 0,
        "cacheSavingsUsd": 0.0,
        "categoryCostUsd": {"input": 0.0, "cacheRead": 0.0, "cacheWrite": 0.0, "output": 0.0},
    }

    by_cli: dict[str, dict[str, Any]] = {}
    by_model: dict[str, dict[str, Any]] = {}

    def _accumulate_chunk(
        bucket: dict[str, Any] | None,
        cli: str,
        model: str,
        events: int,
        uncached: int,
        cached: int,
        cache_write: int,
        out_tok: int,
        reason_tok: int,
        tot_tok: int,
        cost_usd: float,
        rep_cost: float,
        mod_cost: float,
        unpriced_ev: int,
        unpriced_tok: int,
        cache_sav: float,
        cat_cost: dict[str, Any],
        ui_labels: list[str],
    ) -> None:
        if tot_tok <= 0:
            return
        totals["events"] += events
        totals["uncachedInputTokens"] += uncached
        totals["cachedInputTokens"] += cached
        totals["cacheCreationTokens"] += cache_write
        totals["outputTokens"] += out_tok
        totals["reasoningTokens"] += reason_tok
        totals["totalTokens"] += tot_tok
        totals["costUsd"] += cost_usd
        totals["providerReportedCostUsd"] += rep_cost
        totals["modelPricedCostUsd"] += mod_cost
        totals["unpricedEvents"] += unpriced_ev
        totals["unpricedTokens"] += unpriced_tok
        totals["cacheSavingsUsd"] += cache_sav
        for ck in ("input", "cacheRead", "cacheWrite", "output"):
            totals["categoryCostUsd"][ck] += float(_safe_float(cat_cost.get(ck)) or 0.0)

        if bucket is not None:
            bucket["totalTokens"] += tot_tok
            bucket["costUsd"] += cost_usd
            c_slot = bucket["byCli"].setdefault(cli, {"totalTokens": 0, "costUsd": 0.0})
            c_slot["totalTokens"] += tot_tok
            c_slot["costUsd"] += cost_usd

        p_entry = by_cli.setdefault(
            cli,
            {
                "cli": cli,
                "events": 0,
                "totalTokens": 0,
                "uncachedInputTokens": 0,
                "cachedInputTokens": 0,
                "cacheCreationTokens": 0,
                "outputTokens": 0,
                "reasoningTokens": 0,
                "costUsd": 0.0,
                "providerReportedCostUsd": 0.0,
                "modelPricedCostUsd": 0.0,
                "unpricedTokens": 0,
                "cacheSavingsUsd": 0.0,
            },
        )
        p_entry["events"] += events
        p_entry["totalTokens"] += tot_tok
        p_entry["uncachedInputTokens"] += uncached
        p_entry["cachedInputTokens"] += cached
        p_entry["cacheCreationTokens"] += cache_write
        p_entry["outputTokens"] += out_tok
        p_entry["reasoningTokens"] += reason_tok
        p_entry["costUsd"] += cost_usd
        p_entry["providerReportedCostUsd"] += rep_cost
        p_entry["modelPricedCostUsd"] += mod_cost
        p_entry["unpricedTokens"] += unpriced_tok
        p_entry["cacheSavingsUsd"] += cache_sav

        m_key = f"{cli}::{model}"
        m_entry = by_model.setdefault(
            m_key,
            {
                "cli": cli,
                "model": model,
                "events": 0,
                "totalTokens": 0,
                "inputTokens": 0,
                "outputTokens": 0,
                "costUsd": 0.0,
                "costSource": "unpriced",
                "isVariantEndpoint": _is_variant_endpoint(model),
                "uiLabels": [],
                "_repCost": 0.0,
                "_modCost": 0.0,
                "_unpricedEvents": 0,
            },
        )
        m_entry["events"] += events
        m_entry["totalTokens"] += tot_tok
        m_entry["inputTokens"] += uncached + cached + cache_write
        m_entry["outputTokens"] += out_tok + reason_tok
        m_entry["costUsd"] += cost_usd
        m_entry["_repCost"] += rep_cost
        m_entry["_modCost"] += mod_cost
        m_entry["_unpricedEvents"] += unpriced_ev
        for lbl in ui_labels:
            if isinstance(lbl, str) and lbl and lbl not in m_entry["uiLabels"] and len(m_entry["uiLabels"]) < 4:
                m_entry["uiLabels"].append(lbl)

    if hourly and effective_period == "today":
        for rec, rec_dt, priced in priced_records:
            ts_ms = rec["timestampMs"]
            if ts_ms < period_cutoff_ms or ts_ms > now_ms + 3_600_000:
                continue
            b_key = rec_dt.strftime("%Y-%m-%dT%H:00")
            bucket = bucket_index.get(b_key)
            if bucket is None:
                continue
            tot_tok = priced["totalTokens"]
            if tot_tok <= 0:
                continue
            cost_usd = priced["costUsd"]
            cli = rec["cli"]
            bucket["totalTokens"] += tot_tok
            bucket["costUsd"] += cost_usd
            c_slot = bucket["byCli"].setdefault(cli, {"totalTokens": 0, "costUsd": 0.0})
            c_slot["totalTokens"] += tot_tok
            c_slot["costUsd"] += cost_usd
        today_map = tz_archive.get(today_key)
        if isinstance(today_map, dict):
            for entry in today_map.values():
                if not isinstance(entry, dict):
                    continue
                _accumulate_chunk(
                    bucket=None,
                    cli=str(entry.get("cli") or "Unknown"),
                    model=str(entry.get("model") or "unknown"),
                    events=_safe_int(entry.get("events")),
                    uncached=_safe_int(entry.get("uncachedInputTokens")),
                    cached=_safe_int(entry.get("cachedInputTokens")),
                    cache_write=_safe_int(entry.get("cacheCreationTokens")),
                    out_tok=_safe_int(entry.get("outputTokens")),
                    reason_tok=_safe_int(entry.get("reasoningTokens")),
                    tot_tok=_safe_int(entry.get("totalTokens")),
                    cost_usd=float(_safe_float(entry.get("costUsd")) or 0.0),
                    rep_cost=float(_safe_float(entry.get("providerReportedCostUsd")) or 0.0),
                    mod_cost=float(_safe_float(entry.get("modelPricedCostUsd")) or 0.0),
                    unpriced_ev=_safe_int(entry.get("unpricedEvents")),
                    unpriced_tok=_safe_int(entry.get("unpricedTokens")),
                    cache_sav=float(_safe_float(entry.get("cacheSavingsUsd")) or 0.0),
                    cat_cost=entry.get("categoryCostUsd") if isinstance(entry.get("categoryCostUsd"), dict) else {},
                    ui_labels=entry.get("uiLabels") if isinstance(entry.get("uiLabels"), list) else [],
                )
    elif hourly:
        for rec, rec_dt, priced in priced_records:
            ts_ms = rec["timestampMs"]
            if ts_ms < period_cutoff_ms or ts_ms > now_ms + 3_600_000:
                continue
            b_key = rec_dt.strftime("%Y-%m-%dT%H:00")
            bucket = bucket_index.get(b_key)
            if bucket is None:
                continue
            cost_usd = priced["costUsd"]
            cost_src = priced["costSource"]
            tot_tok = priced["totalTokens"]
            ui_lbl = rec.get("uiLabel")
            _accumulate_chunk(
                bucket=bucket,
                cli=rec["cli"],
                model=rec["model"],
                events=1,
                uncached=rec["uncachedInputTokens"],
                cached=rec["cachedInputTokens"],
                cache_write=rec["cacheCreationTokens"],
                out_tok=rec["outputTokens"],
                reason_tok=rec["reasoningTokens"],
                tot_tok=tot_tok,
                cost_usd=cost_usd,
                rep_cost=cost_usd if cost_src == "providerReported" else 0.0,
                mod_cost=cost_usd if cost_src == "modelPriced" else 0.0,
                unpriced_ev=1 if cost_src == "unpriced" else 0,
                unpriced_tok=tot_tok if cost_src == "unpriced" else 0,
                cache_sav=priced["cacheSavingsUsd"],
                cat_cost=priced["categoryCostUsd"],
                ui_labels=[ui_lbl] if isinstance(ui_lbl, str) and ui_lbl else [],
            )
    else:
        min_day_key = "" if effective_period == "all" else start_dt.strftime("%Y-%m-%d")
        max_day_key = (start_dt + dt.timedelta(days=effective_days - 1)).strftime("%Y-%m-%d") if effective_period != "all" else today_key
        for dk, m_map in sorted(tz_archive.items()):
            if (min_day_key and dk < min_day_key) or dk > max_day_key or not isinstance(m_map, dict):
                continue
            b_key = dk[:7] if bucket_granularity == "month" else dk
            bucket = bucket_index.get(b_key)
            for entry in m_map.values():
                if not isinstance(entry, dict):
                    continue
                _accumulate_chunk(
                    bucket=bucket,
                    cli=str(entry.get("cli") or "Unknown"),
                    model=str(entry.get("model") or "unknown"),
                    events=_safe_int(entry.get("events")),
                    uncached=_safe_int(entry.get("uncachedInputTokens")),
                    cached=_safe_int(entry.get("cachedInputTokens")),
                    cache_write=_safe_int(entry.get("cacheCreationTokens")),
                    out_tok=_safe_int(entry.get("outputTokens")),
                    reason_tok=_safe_int(entry.get("reasoningTokens")),
                    tot_tok=_safe_int(entry.get("totalTokens")),
                    cost_usd=float(_safe_float(entry.get("costUsd")) or 0.0),
                    rep_cost=float(_safe_float(entry.get("providerReportedCostUsd")) or 0.0),
                    mod_cost=float(_safe_float(entry.get("modelPricedCostUsd")) or 0.0),
                    unpriced_ev=_safe_int(entry.get("unpricedEvents")),
                    unpriced_tok=_safe_int(entry.get("unpricedTokens")),
                    cache_sav=float(_safe_float(entry.get("cacheSavingsUsd")) or 0.0),
                    cat_cost=entry.get("categoryCostUsd") if isinstance(entry.get("categoryCostUsd"), dict) else {},
                    ui_labels=entry.get("uiLabels") if isinstance(entry.get("uiLabels"), list) else [],
                )

    for m_entry in by_model.values():
        rep_c = m_entry.pop("_repCost", 0.0)
        mod_c = m_entry.pop("_modCost", 0.0)
        unp_e = m_entry.pop("_unpricedEvents", 0)
        if rep_c > 0 and mod_c > 0:
            m_entry["costSource"] = "mixed"
        elif rep_c > 0:
            m_entry["costSource"] = "providerReported"
        elif mod_c > 0:
            m_entry["costSource"] = "modelPriced"
        elif unp_e == 0 and m_entry["events"] > 0:
            m_entry["costSource"] = "providerReported"
        else:
            m_entry["costSource"] = "unpriced"

    sorted_providers = sorted(
        by_cli.values(), key=lambda x: (x["totalTokens"], x["costUsd"]), reverse=True
    )
    sorted_models = sorted(
        by_model.values(), key=lambda x: (x["costUsd"], x["totalTokens"]), reverse=True
    )[:80]

    return {
        "generatedAt": now_dt.isoformat(),
        "period": effective_period,
        "days": effective_days,
        "granularity": bucket_granularity,
        "totals": totals,
        "buckets": buckets,
        "byProvider": sorted_providers,
        "byModel": sorted_models,
        "rolling30dCostByCli": rolling_30d_cost_by_cli,
        "activity": activity,
    }

def _iso_from_epoch_sec(sec: Any) -> str | None:
    f = _safe_float(sec)
    if f is None or f <= 0:
        return None
    if f > 10_000_000_000:
        f = f / 1000.0
    try:
        return dt.datetime.fromtimestamp(f, tz=dt.timezone.utc).isoformat()
    except Exception:
        return None

def _make_window(
    wid: str,
    label: str,
    used_pct: float,
    resets_at: str | None = None,
    duration_ms: int | None = None,
    group_label: str | None = None,
) -> dict[str, Any]:
    clamped_used = max(0.0, min(100.0, float(used_pct)))
    return {
        "id": wid,
        "label": label,
        "usedPercent": round(clamped_used, 1),
        "remainingPercent": round(max(0.0, 100.0 - clamped_used), 1),
        "resetsAt": resets_at,
        "windowDurationMs": duration_ms,
        "groupLabel": group_label,
    }

def _has_codex_oauth(home: Path) -> bool:
    codex_home = Path(os.environ.get("CODEX_HOME") or (home / ".codex"))
    auth_file = codex_home / "auth.json"
    if not auth_file.is_file():
        return False
    try:
        raw = json.loads(auth_file.read_text("utf-8"))
    except Exception:
        return False
    if not isinstance(raw, dict):
        return False
    mode = str(raw.get("auth_mode") or "").strip().lower()
    if mode in ("apikey", "api_key"):
        return False
    tokens = raw.get("tokens")
    if isinstance(tokens, dict):
        for key in ("access_token", "refresh_token", "accessToken", "refreshToken"):
            val = tokens.get(key)
            if isinstance(val, str) and val.strip():
                return True
    for key in ("access_token", "refresh_token", "accessToken", "refreshToken"):
        val = raw.get(key)
        if isinstance(val, str) and val.strip():
            return True
    return mode in ("chatgpt", "oauth")

def _recent_codex_session_files(sessions_dir: Path, limit: int = 8) -> list[Path]:
    if not sessions_dir.is_dir():
        return []
    found: list[Path] = []
    dirs_visited = 0

    def walk_desc(directory: Path) -> None:
        nonlocal dirs_visited
        if len(found) >= limit or dirs_visited >= 64:
            return
        dirs_visited += 1
        try:
            entries = list(os.scandir(directory))
        except OSError:
            return
        subdirs = sorted((e for e in entries if e.is_dir(follow_symlinks=False)), key=lambda e: e.name, reverse=True)
        jsonl_files = [Path(e.path) for e in entries if e.is_file(follow_symlinks=False) and e.name.endswith(".jsonl")]
        if jsonl_files:
            jsonl_files.sort(
                key=lambda p: (p.stat().st_mtime_ns if p.exists() else 0, p.name),
                reverse=True,
            )
            for p in jsonl_files:
                found.append(p)
                if len(found) >= limit:
                    return
        for sd in subdirs:
            walk_desc(Path(sd.path))
            if len(found) >= limit:
                return

    walk_desc(sessions_dir)
    return found[:limit]

def _resolve_codex_binary(home: Path) -> str | None:
    found = shutil.which("codex")
    if found:
        return found
    for candidate in (
        home / ".local" / "share" / "pnpm" / "bin" / "codex",
        home / ".npm-global" / "bin" / "codex",
        home / ".local" / "bin" / "codex",
        Path("/usr/local/bin/codex"),
        Path("/usr/bin/codex"),
    ):
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    return None

def _probe_codex_limits(home: Path) -> dict[str, Any]:
    now_iso = dt.datetime.now(dt.timezone.utc).isoformat()
    codex_home = Path(os.environ.get("CODEX_HOME") or (home / ".codex"))
    if not _has_codex_oauth(home):
        return {
            "cli": "Codex",
            "planType": None,
            "updatedAt": now_iso,
            "source": "unavailable",
            "status": "unconfigured",
            "message": "未检测到 Codex OAuth 登录凭证",
            "windows": [],
            "credits": None,
        }

    def fallback_from_sessions() -> dict[str, Any] | None:
        sessions_dir = codex_home / "sessions"
        candidates = _recent_codex_session_files(sessions_dir, limit=8)
        for path in candidates:
            try:
                with open(path, "rb") as f:
                    st_size = os.fstat(f.fileno()).st_size
                    f.seek(max(0, st_size - 262144))
                    lines = f.read().splitlines()
                for raw_line in reversed(lines):
                    if b"rate_limits" not in raw_line:
                        continue
                    obj = json.loads(raw_line.decode("utf-8", errors="replace"))
                    payload = obj.get("payload") if isinstance(obj.get("payload"), dict) else {}
                    rl = payload.get("rate_limits")
                    if not isinstance(rl, dict):
                        continue
                    windows = []
                    for key, default_label, default_mins in (("primary", "5h 窗口", 300), ("secondary", "7d 窗口", 10080)):
                        slot = rl.get(key)
                        if isinstance(slot, dict) and _safe_float(slot.get("used_percent")) is not None:
                            mins = _safe_int(slot.get("window_minutes")) or default_mins
                            lbl = "5h 窗口" if mins == 300 else ("7d 窗口" if mins == 10080 else default_label)
                            windows.append(
                                _make_window(
                                    key,
                                    lbl,
                                    float(slot["used_percent"]),
                                    _iso_from_epoch_sec(slot.get("resets_at")),
                                    mins * 60_000,
                                )
                            )
                    if windows:
                        plan = rl.get("plan_type")
                        return {
                            "cli": "Codex",
                            "planType": str(plan) if plan else None,
                            "updatedAt": obj.get("timestamp") or now_iso,
                            "source": "session_fallback",
                            "status": "ok",
                            "message": None,
                            "windows": windows,
                            "credits": None,
                        }
            except Exception:
                continue
        return None

    codex_bin = _resolve_codex_binary(home)
    if codex_bin:
        try:
            proc = subprocess.Popen(
                [codex_bin, "app-server"],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                start_new_session=True,
            )
            try:
                req = (
                    json.dumps({
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "initialize",
                        "params": {"clientInfo": {"name": "luma-gui", "version": "1.0"}},
                    })
                    + "\n"
                    + json.dumps({"jsonrpc": "2.0", "method": "initialized", "params": {}})
                    + "\n"
                    + json.dumps({"jsonrpc": "2.0", "id": 2, "method": "account/rateLimits/read", "params": {}})
                    + "\n"
                )
                out, _ = proc.communicate(input=req, timeout=3.0)
                for line in out.splitlines():
                    if not line.strip():
                        continue
                    msg = json.loads(line)
                    if msg.get("id") == 2 and isinstance(msg.get("result"), dict):
                        res = msg["result"]
                        rl = res.get("rateLimits") if isinstance(res.get("rateLimits"), dict) else res
                        windows = []
                        for key, default_label, default_mins in (("primary", "5h 窗口", 300), ("secondary", "7d 窗口", 10080)):
                            slot = rl.get(key)
                            if isinstance(slot, dict) and _safe_float(slot.get("usedPercent") if "usedPercent" in slot else slot.get("used_percent")) is not None:
                                used_p = _safe_float(slot.get("usedPercent") if "usedPercent" in slot else slot.get("used_percent")) or 0.0
                                mins = _safe_int(slot.get("windowDurationMins") or slot.get("window_minutes")) or default_mins
                                lbl = "5h 窗口" if mins == 300 else ("7d 窗口" if mins == 10080 else default_label)
                                resets = slot.get("resetsAt") or slot.get("resets_at")
                                resets_iso = resets if isinstance(resets, str) else _iso_from_epoch_sec(resets)
                                windows.append(_make_window(key, lbl, used_p, resets_iso, mins * 60_000))
                        if windows:
                            plan = rl.get("planType") or rl.get("plan_type")
                            return {
                                "cli": "Codex",
                                "planType": str(plan) if plan else None,
                                "updatedAt": now_iso,
                                "source": "live",
                                "status": "ok",
                                "message": None,
                                "windows": windows,
                                "credits": None,
                            }
            finally:
                try:
                    os.killpg(proc.pid, signal.SIGKILL)
                except Exception:
                    try:
                        proc.kill()
                    except Exception:
                        pass
                try:
                    proc.wait(timeout=0.2)
                except Exception:
                    pass
        except Exception:
            pass

    fb = fallback_from_sessions()
    if fb is not None:
        return fb

    return {
        "cli": "Codex",
        "planType": "ChatGPT OAuth",
        "updatedAt": now_iso,
        "source": "unavailable",
        "status": "error",
        "message": "无法读取 Codex OAuth 实时限额，请检查网络或重新登录",
        "windows": [],
        "credits": None,
    }

def _extract_claude_oauth(home: Path) -> tuple[str, str | None] | None:
    cred_paths = [
        home / ".claude" / ".credentials.json",
        home / ".config" / "claude" / ".credentials.json",
    ]
    for cp in cred_paths:
        if cp.is_file():
            try:
                d = json.loads(cp.read_text("utf-8"))
                if not isinstance(d, dict):
                    continue
                has_oauth_container = isinstance(d.get("claudeAiOauth"), dict)
                oauth = d["claudeAiOauth"] if has_oauth_container else d
                t = oauth.get("accessToken") or oauth.get("access_token")
                rt = oauth.get("refreshToken") or oauth.get("refresh_token")
                sub_type = oauth.get("subscriptionType") or oauth.get("plan")
                if isinstance(t, str) and t.strip():
                    tok = t.strip()
                    if not has_oauth_container and tok.startswith("sk-ant-api"):
                        continue
                    return (tok, str(sub_type) if sub_type else None)
                if has_oauth_container and isinstance(rt, str) and rt.strip():
                    return ("", str(sub_type) if sub_type else None)
            except Exception:
                pass
    return None

def _probe_claude_limits(home: Path) -> dict[str, Any]:
    now_iso = dt.datetime.now(dt.timezone.utc).isoformat()
    extracted = _extract_claude_oauth(home)
    if extracted is None:
        return {
            "cli": "Claude Code",
            "planType": None,
            "updatedAt": now_iso,
            "source": "unavailable",
            "status": "unconfigured",
            "message": "未配置 Claude.ai OAuth 凭证（API Key 模式无订阅窗口限额）",
            "windows": [],
            "credits": None,
        }
    token, sub_type = extracted
    if not token:
        return {
            "cli": "Claude Code",
            "planType": sub_type or "Claude OAuth",
            "updatedAt": now_iso,
            "source": "unavailable",
            "status": "error",
            "message": "Claude OAuth 令牌已失效，请在 Claude Code 中重新登录",
            "windows": [],
            "credits": None,
        }

    try:
        req = urllib.request.Request(
            "https://api.anthropic.com/api/oauth/usage",
            headers={
                "Authorization": f"Bearer {token}",
                "anthropic-beta": "oauth-2025-04-20",
                "Accept": "application/json",
                "User-Agent": "claude-code/2.0",
            },
        )
        with urllib.request.urlopen(req, timeout=3.5) as resp:
            body = json.loads(resp.read().decode("utf-8"))
        windows = []
        mapping = [
            ("five_hour", "5h 窗口", 5 * 3_600_000),
            ("seven_day", "7d 总窗口", 7 * 86_400_000),
            ("seven_day_sonnet", "7d Sonnet", 7 * 86_400_000),
            ("seven_day_opus", "7d Opus", 7 * 86_400_000),
        ]
        for key, label, dur_ms in mapping:
            slot = body.get(key)
            if isinstance(slot, dict) and _safe_float(slot.get("utilization")) is not None:
                windows.append(
                    _make_window(
                        key,
                        label,
                        float(slot["utilization"]),
                        slot.get("resets_at"),
                        dur_ms,
                    )
                )
        credits = None
        extra = body.get("extra_usage")
        if isinstance(extra, dict) and extra.get("is_enabled"):
            used_c = _safe_float(extra.get("used_credits"))
            limit_c = _safe_float(extra.get("monthly_limit"))
            if used_c is not None:
                credits = {
                    "label": "额外用量额度",
                    "used": round(used_c / 100.0, 2),
                    "limit": round(limit_c / 100.0, 2) if limit_c else None,
                    "remaining": round(max(0.0, (limit_c - used_c) / 100.0), 2) if limit_c else None,
                    "unit": "USD",
                }
        return {
            "cli": "Claude Code",
            "planType": str(sub_type) if sub_type else "Claude OAuth",
            "updatedAt": now_iso,
            "source": "live",
            "status": "ok",
            "message": None,
            "windows": windows,
            "credits": credits,
        }
    except Exception as exc:
        return {
            "cli": "Claude Code",
            "planType": str(sub_type) if sub_type else None,
            "updatedAt": now_iso,
            "source": "unavailable",
            "status": "error",
            "message": f"读取限额失败：{type(exc).__name__}",
            "windows": [],
            "credits": None,
        }

ANTIGRAVITY_QUOTA_URLS = (
    "https://daily-cloudcode-pa.googleapis.com",
    "https://cloudcode-pa.googleapis.com",
)
ANTIGRAVITY_USER_AGENT = (
    "antigravity/hub/2.24.0 (aidev_client; os_type=linux; arch=arm64; cl=963137146)"
)

def _antigravity_summary_reset_time(
    bucket: dict[str, Any],
    duration_ms: int | None,
    requested_at_ms: int,
) -> str | None:
    reset_time = bucket.get("resetTime")
    if not isinstance(reset_time, str) or not reset_time.strip():
        return None
    reset_ms = _parse_ts_ms(reset_time)
    if reset_ms is None:
        return None
    rem_frac = _safe_float(bucket.get("remainingFraction"))
    desc = bucket.get("description")
    has_desc = isinstance(desc, str) and bool(desc.strip())
    if (
        rem_frac is not None
        and abs(rem_frac - 1.0) < 1e-9
        and not has_desc
        and duration_ms is not None
        and abs(reset_ms - requested_at_ms - duration_ms) <= 60_000
    ):
        return None
    return reset_time.strip()

def _parse_antigravity_summary_windows(
    payload: dict[str, Any],
    requested_at_ms: int | None = None,
) -> list[dict[str, Any]]:
    req_ms = (
        requested_at_ms
        if requested_at_ms is not None
        else int(dt.datetime.now(dt.timezone.utc).timestamp() * 1000)
    )
    groups = payload.get("groups")
    if not (
        isinstance(groups, list)
        and any(isinstance(g, dict) and isinstance(g.get("buckets"), list) and g.get("buckets") for g in groups)
    ):
        groups = [{"buckets": payload.get("buckets")}]

    windows: list[dict[str, Any]] = []
    for g in groups:
        if not isinstance(g, dict):
            continue
        g_name = str(g.get("displayName") or "").strip()
        for b in g.get("buckets") or []:
            if not isinstance(b, dict) or b.get("disabled") is True:
                continue
            rem_frac = _safe_float(b.get("remainingFraction"))
            if rem_frac is None:
                continue
            bid = str(b.get("bucketId") or "").strip()
            if not bid:
                continue
            if bid.startswith("gemini-argon-"):
                family = g_name or "Gemini Argon"
            elif bid.startswith("gemini-"):
                family = "Gemini"
            elif bid.startswith("3p-"):
                family = "Claude & GPT"
            else:
                family = g_name or bid
            win_type = str(b.get("window") or "").strip()
            dur_ms = (
                (7 * 86_400_000)
                if win_type == "weekly"
                else ((5 * 3_600_000) if win_type == "5h" else None)
            )
            win_lbl = (
                "7d 窗口"
                if win_type == "weekly"
                else (
                    "5h 窗口"
                    if win_type == "5h"
                    else (str(b.get("displayName") or "").strip() or "配额")
                )
            )
            used_pct = max(0.0, min(100.0, (1.0 - rem_frac) * 100.0))
            reset_time = _antigravity_summary_reset_time(b, dur_ms, req_ms)
            windows.append(
                _make_window(
                    bid,
                    f"{family} · {win_lbl}",
                    used_pct,
                    reset_time,
                    dur_ms,
                    group_label=family,
                )
            )
    return windows

def _parse_antigravity_model_windows(payload: dict[str, Any]) -> list[dict[str, Any]]:
    models_map = payload.get("models")
    if not isinstance(models_map, dict) or not models_map:
        return []
    seen_groups: dict[str, dict[str, Any]] = {}
    for m_id, m_info in models_map.items():
        if not isinstance(m_info, dict):
            continue
        low = m_id.lower()
        if low.startswith(("chat_", "tab_")):
            continue
        prov = str(m_info.get("modelProvider") or "")
        if prov == "MODEL_PROVIDER_GOOGLE" or low.startswith("gemini-"):
            gid, grp = "gemini", "Gemini"
        elif prov == "MODEL_PROVIDER_ANTHROPIC" or low.startswith("claude-"):
            gid, grp = "claude", "Claude"
        elif prov == "MODEL_PROVIDER_OPENAI" or low.startswith("gpt-"):
            gid, grp = "gpt", "GPT"
        else:
            continue

        has_weekly = isinstance(m_info.get("weeklyQuotaInfo"), dict) and (
            _safe_float(m_info["weeklyQuotaInfo"].get("remainingFraction")) is not None
        )
        for suffix, q_key, dur_ms, s_lbl in (
            ("primary", "quotaInfo", None, f"{grp} · 主窗口" if has_weekly else grp),
            ("weekly", "weeklyQuotaInfo", 7 * 86_400_000, f"{grp} · 7d 窗口"),
        ):
            q_info = m_info.get(q_key)
            if not isinstance(q_info, dict):
                continue
            rem_frac = _safe_float(q_info.get("remainingFraction"))
            if rem_frac is None:
                continue
            used_pct = max(0.0, min(100.0, (1.0 - rem_frac) * 100.0))
            reset_time = q_info.get("resetTime") if isinstance(q_info.get("resetTime"), str) else None
            wid = f"{gid}:{suffix}" if suffix == "weekly" else gid
            cand = _make_window(wid, s_lbl, used_pct, reset_time, dur_ms, group_label=grp)
            prev = seen_groups.get(wid)
            if prev is None or cand["usedPercent"] > prev["usedPercent"]:
                seen_groups[wid] = cand
            elif cand["usedPercent"] == prev["usedPercent"]:
                c_ms = _parse_ts_ms(cand.get("resetsAt"))
                p_ms = _parse_ts_ms(prev.get("resetsAt"))
                if c_ms is not None and (p_ms is None or c_ms < p_ms):
                    seen_groups[wid] = cand
    return list(seen_groups.values())

def _merge_antigravity_host_windows(
    host_windows_list: list[list[dict[str, Any]]],
) -> list[dict[str, Any]]:
    merged: dict[str, dict[str, Any]] = {}
    for windows in host_windows_list:
        for win in windows:
            wid = str(win.get("id") or "")
            if not wid:
                continue
            prev = merged.get(wid)
            if prev is None or win["usedPercent"] > prev["usedPercent"]:
                merged[wid] = win
            elif win["usedPercent"] == prev["usedPercent"] and win.get("resetsAt") and not prev.get("resetsAt"):
                merged[wid] = win

    def _sort_key(w: dict[str, Any]) -> tuple[int, int, str]:
        wid = str(w.get("id") or "")
        if wid.startswith("gemini-") and not wid.startswith("gemini-argon-"):
            fam_rank = 0
        elif wid.startswith("gemini-argon-"):
            fam_rank = 1
        elif wid.startswith("3p-"):
            fam_rank = 2
        else:
            fam_rank = 3
        win_rank = 0 if wid.endswith("-5h") else (1 if wid.endswith("-weekly") else 2)
        return (fam_rank, win_rank, wid)

    return sorted(merged.values(), key=_sort_key)

def _refresh_antigravity_access_token(
    refresh_token: str,
    client_id: str,
    client_secret: str,
    timeout: float = 2.5,
) -> str | None:
    try:
        form = urllib.parse.urlencode(
            {
                "client_id": client_id,
                "client_secret": client_secret,
                "refresh_token": refresh_token,
                "grant_type": "refresh_token",
            }
        ).encode("utf-8")
        req = urllib.request.Request(
            "https://oauth2.googleapis.com/token",
            data=form,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
        at = payload.get("access_token")
        return at.strip() if isinstance(at, str) and at.strip() else None
    except Exception:
        return None

def _extract_antigravity_oauth(home: Path) -> dict[str, Any] | None:
    token_files = [
        home / ".gemini" / "antigravity-cli" / "antigravity-oauth-token",
        home / ".gemini" / "antigravity" / "acp_token.json",
    ]
    access_token: str | None = None
    refresh_token: str | None = None
    client_id: str | None = None
    client_secret: str | None = None
    expiry_ms: int | None = None
    email: str | None = None
    project_id: str | None = None

    for tf in token_files:
        if tf.is_file():
            try:
                raw = json.loads(tf.read_text("utf-8"))
                if not isinstance(raw, dict):
                    continue
                email = raw.get("id_token_email") or raw.get("email") or email
                proj = raw.get("project_id") or raw.get("projectId")
                if isinstance(proj, str) and proj.strip():
                    project_id = proj.strip()
                tok = raw.get("token") if "token" in raw else raw
                if isinstance(tok, str):
                    tok = json.loads(tok)
                if isinstance(tok, dict):
                    at = tok.get("access_token") or tok.get("accessToken")
                    rt = tok.get("refresh_token") or tok.get("refreshToken")
                    cid = tok.get("client_id") or tok.get("clientId") or raw.get("client_id")
                    csec = tok.get("client_secret") or tok.get("clientSecret") or raw.get("client_secret")
                    exp = tok.get("expiry") or tok.get("expiry_date") or raw.get("expiry_date")
                    if isinstance(rt, str) and rt.strip():
                        refresh_token = rt.strip()
                    if isinstance(cid, str) and cid.strip():
                        client_id = cid.strip()
                    if isinstance(csec, str) and csec.strip():
                        client_secret = csec.strip()
                    if exp is not None:
                        expiry_ms = _parse_ts_ms(exp)
                    if isinstance(at, str) and at.strip():
                        access_token = at.strip()
                        break
            except Exception:
                continue

    if not access_token and not refresh_token:
        return None
    return {
        "access_token": access_token,
        "refresh_token": refresh_token,
        "client_id": client_id,
        "client_secret": client_secret,
        "expiry_ms": expiry_ms,
        "email": email,
        "project_id": project_id,
    }

def _probe_antigravity_limits(home: Path) -> dict[str, Any]:
    now_dt = dt.datetime.now(dt.timezone.utc)
    now_iso = now_dt.isoformat()
    now_ms = int(now_dt.timestamp() * 1000)
    oauth = _extract_antigravity_oauth(home)
    if oauth is None:
        return {
            "cli": "Antigravity",
            "planType": None,
            "updatedAt": now_iso,
            "source": "unavailable",
            "status": "unconfigured",
            "message": "未检测到 Antigravity OAuth 登录凭证",
            "windows": [],
            "credits": None,
        }

    deadline = time.monotonic() + 5.0
    access_token: str | None = oauth["access_token"]
    refresh_token: str | None = oauth["refresh_token"]
    client_id: str | None = oauth["client_id"]
    client_secret: str | None = oauth["client_secret"]
    expiry_ms: int | None = oauth["expiry_ms"]
    email: str | None = oauth["email"]
    project_id: str | None = oauth["project_id"]

    if (
        (not access_token or (expiry_ms is not None and expiry_ms <= now_ms + 60_000))
        and refresh_token
        and client_id
        and client_secret
    ):
        rem = max(0.5, min(2.5, deadline - time.monotonic()))
        refreshed = _refresh_antigravity_access_token(
            refresh_token, client_id, client_secret, timeout=rem
        )
        if refreshed:
            access_token = refreshed

    if not access_token:
        return {
            "cli": "Antigravity",
            "planType": email or "Google OAuth",
            "updatedAt": now_iso,
            "source": "unavailable",
            "status": "error",
            "message": "OAuth 令牌刷新失败，可在 Antigravity 会话中重新登录",
            "windows": [],
            "credits": None,
        }

    headers = {
        "Authorization": f"Bearer {access_token}",
        "Content-Type": "application/json",
        "User-Agent": ANTIGRAVITY_USER_AGENT,
    }
    body_bytes = json.dumps({"project": project_id} if project_id else {}).encode("utf-8")

    def _query_hosts_concurrently(endpoint_suffix: str, parse_fn: Any, max_wait: float) -> list[list[dict[str, Any]]]:
        if max_wait <= 0.2:
            return []
        req_timeout = max(0.5, min(2.8, max_wait))
        host_pool = concurrent.futures.ThreadPoolExecutor(max_workers=len(ANTIGRAVITY_QUOTA_URLS))

        def _fetch_one(host: str) -> list[dict[str, Any]] | None:
            req_ms = int(dt.datetime.now(dt.timezone.utc).timestamp() * 1000)
            req = urllib.request.Request(
                f"{host}{endpoint_suffix}",
                data=body_bytes,
                headers=headers,
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=req_timeout) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            if isinstance(data, dict):
                return parse_fn(data, req_ms)
            return None

        futs = [host_pool.submit(_fetch_one, h) for h in ANTIGRAVITY_QUOTA_URLS]
        collected: list[list[dict[str, Any]]] = []
        try:
            done, not_done = concurrent.futures.wait(futs, timeout=max_wait)
            for f in not_done:
                f.cancel()
            for f in done:
                try:
                    res = f.result()
                    if res is not None:
                        collected.append(res)
                except Exception:
                    continue
        finally:
            host_pool.shutdown(wait=False, cancel_futures=True)
        return collected

    summary_host_windows = _query_hosts_concurrently(
        "/v1internal:retrieveUserQuotaSummary",
        _parse_antigravity_summary_windows,
        max_wait=min(3.0, deadline - time.monotonic()),
    )
    merged_windows = _merge_antigravity_host_windows(summary_host_windows)

    rem_budget = deadline - time.monotonic()
    if not merged_windows and rem_budget > 0.6:
        model_host_windows = _query_hosts_concurrently(
            "/v1internal:fetchAvailableModels",
            lambda data, _req_ms: _parse_antigravity_model_windows(data),
            max_wait=min(2.5, rem_budget),
        )
        merged_windows = _merge_antigravity_host_windows(model_host_windows)

    if merged_windows:
        return {
            "cli": "Antigravity",
            "planType": email or "Google OAuth",
            "updatedAt": now_iso,
            "source": "live",
            "status": "ok",
            "message": None,
            "windows": merged_windows[:10],
            "credits": None,
        }

    return {
        "cli": "Antigravity",
        "planType": email or "Google OAuth",
        "updatedAt": now_iso,
        "source": "unavailable",
        "status": "error",
        "message": "OAuth 令牌已过期或配额端点暂不可达，可在 Antigravity 会话中刷新登录",
        "windows": [],
        "credits": None,
    }

def _extract_grok_oauth(home: Path) -> tuple[str, str | None] | None:
    auth_file = home / ".grok" / "auth.json"
    if not auth_file.is_file():
        return None
    try:
        raw = json.loads(auth_file.read_text("utf-8"))
    except Exception:
        return None
    if not isinstance(raw, dict):
        return None
    cred = (
        raw.get("https://auth.x.ai::b1a00492-073a-47ea-816f-4c329264a828")
        or raw.get("https://accounts.x.ai/sign-in")
    )
    if not isinstance(cred, dict):
        for v in raw.values():
            if isinstance(v, dict) and (v.get("key") or v.get("access_token") or v.get("refresh_token")):
                cred = v
                break
    if not isinstance(cred, dict):
        return None
    auth_mode = str(cred.get("auth_mode") or "").strip().lower()
    if auth_mode in ("api_key", "apikey"):
        return None
    email = str(cred.get("email") or "").strip() or None
    token = str(cred.get("key") or cred.get("access_token") or "").strip()
    has_refresh = isinstance(cred.get("refresh_token"), str) and bool(cred.get("refresh_token").strip())
    if not token:
        if has_refresh or auth_mode in ("oidc", "oauth"):
            return ("", email)
        return None
    if token.startswith("xai-") and not has_refresh and auth_mode not in ("oidc", "oauth"):
        return None
    return (token, email)

def _probe_grok_limits(home: Path) -> dict[str, Any]:
    now_iso = dt.datetime.now(dt.timezone.utc).isoformat()
    extracted = _extract_grok_oauth(home)
    if extracted is None:
        return {
            "cli": "Grok Build",
            "planType": None,
            "updatedAt": now_iso,
            "source": "unavailable",
            "status": "unconfigured",
            "message": "未检测到 Grok OAuth 登录凭证",
            "windows": [],
            "credits": None,
        }
    token, email = extracted
    if not token:
        return {
            "cli": "Grok Build",
            "planType": email or "xAI",
            "updatedAt": now_iso,
            "source": "unavailable",
            "status": "error",
            "message": "Grok OAuth 令牌已失效，请在 Grok Build 中重新登录",
            "windows": [],
            "credits": None,
        }
    try:
        req = urllib.request.Request(
            "https://cli-chat-proxy.grok.com/v1/billing?format=credits",
            headers={
                "Authorization": f"Bearer {token}",
                "Accept": "application/json",
                "User-Agent": "grok-cli/1.0",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=3.5) as resp:
                body = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as he:
            err_txt = ""
            try:
                err_txt = he.read().decode("utf-8", errors="replace")
            except Exception:
                pass
            if he.code == 403 and "WKE=privacy-mode:" in err_txt:
                return {
                    "cli": "Grok Build",
                    "planType": email or "xAI",
                    "updatedAt": now_iso,
                    "source": "unavailable",
                    "status": "error",
                    "message": "请在 xAI 账户设置中开启 Privacy Mode 以查看 Grok 限额",
                    "windows": [],
                    "credits": None,
                }
            raise

        windows = []
        credits = None
        cfg = body.get("config") if isinstance(body.get("config"), dict) else {}
        period = cfg.get("currentPeriod") if isinstance(cfg.get("currentPeriod"), dict) else {}
        p_type = str(period.get("type") or "").replace("USAGE_PERIOD_TYPE_", "")
        used_pct = _safe_float(cfg.get("creditUsagePercent"))
        if used_pct is None and p_type in ("WEEKLY", "MONTHLY") and period.get("start") and period.get("end"):
            used_pct = 0.0
        if used_pct is not None:
            dur_ms = (7 * 86_400_000) if p_type == "WEEKLY" else ((30 * 86_400_000) if p_type == "MONTHLY" else None)
            lbl = "7d 订阅额度" if p_type == "WEEKLY" else ("月度订阅额度" if p_type == "MONTHLY" else "订阅额度")
            end_iso = period.get("end") if isinstance(period.get("end"), str) else None
            windows.append(_make_window("subscription", lbl, used_pct, end_iso, dur_ms))

        rem_cents = _safe_float(body.get("effectiveRemaining") or body.get("remainingCredits"))
        tot_cents = _safe_float(body.get("totalCredits") or body.get("monthlyCredits"))
        if rem_cents is not None:
            rem_usd = rem_cents / 100.0
            tot_usd = (tot_cents / 100.0) if (tot_cents and tot_cents > 0) else None
            used_usd = max(0.0, tot_usd - rem_usd) if tot_usd is not None else 0.0
            credits = {
                "label": "可用额度",
                "used": round(used_usd, 2),
                "limit": round(tot_usd, 2) if tot_usd else None,
                "remaining": round(rem_usd, 2),
                "unit": "USD",
            }
            if not windows and tot_usd and tot_usd > 0:
                windows.append(_make_window("credits", "月度额度", (used_usd / tot_usd) * 100.0, None, 30 * 86_400_000))
        return {
            "cli": "Grok Build",
            "planType": email or str(body.get("plan") or "xAI"),
            "updatedAt": now_iso,
            "source": "live",
            "status": "ok",
            "message": None,
            "windows": windows,
            "credits": credits,
        }
    except Exception as exc:
        return {
            "cli": "Grok Build",
            "planType": email,
            "updatedAt": now_iso,
            "source": "unavailable",
            "status": "error",
            "message": f"读取 Grok 额度失败：{type(exc).__name__}",
            "windows": [],
            "credits": None,
        }

def _has_claude_oauth(home: Path) -> bool:
    return _extract_claude_oauth(home) is not None

def _has_antigravity_oauth(home: Path) -> bool:
    return _extract_antigravity_oauth(home) is not None

def _has_grok_oauth(home: Path) -> bool:
    return _extract_grok_oauth(home) is not None


def collect_limits(fresh: bool = False, probe_timeout: float = 7.5) -> dict[str, Any]:
    cache_path = _cache_dir() / "usage-limits-cache-v3.json"
    now_sec = time.time()
    if not fresh and cache_path.is_file():
        try:
            cached = json.loads(cache_path.read_text("utf-8"))
            if isinstance(cached, dict) and now_sec - float(cached.get("cachedAtEpoch", 0)) < LIMITS_CACHE_TTL_SECONDS:
                providers = cached.get("providers")
                if isinstance(providers, list):
                    cached["providers"] = [
                        p for p in providers if isinstance(p, dict) and p.get("status") != "unconfigured"
                    ]
                return cached
        except Exception:
            pass

    home = Path.home()
    candidate_probes = [
        ("Codex", _has_codex_oauth, _probe_codex_limits),
        ("Claude Code", _has_claude_oauth, _probe_claude_limits),
        ("Antigravity", _has_antigravity_oauth, _probe_antigravity_limits),
        ("Grok Build", _has_grok_oauth, _probe_grok_limits),
    ]
    active_probes = [(name, fn) for name, has_oauth, fn in candidate_probes if has_oauth(home)]

    results_map: dict[str, dict[str, Any]] = {}
    timed_out = False
    if active_probes:
        pool = concurrent.futures.ThreadPoolExecutor(max_workers=len(active_probes))
        fut_map = {pool.submit(fn, home): name for name, fn in active_probes}
        try:
            done, not_done = concurrent.futures.wait(fut_map, timeout=probe_timeout)
            for fut in done:
                name = fut_map[fut]
                try:
                    results_map[name] = fut.result()
                except Exception as exc:
                    results_map[name] = {
                        "cli": name,
                        "planType": None,
                        "updatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                        "source": "unavailable",
                        "status": "error",
                        "message": f"探测异常：{type(exc).__name__}",
                        "windows": [],
                        "credits": None,
                    }
            if not_done:
                timed_out = True
                now_timeout_iso = dt.datetime.now(dt.timezone.utc).isoformat()
                for fut in not_done:
                    fut.cancel()
                    name = fut_map[fut]
                    results_map[name] = {
                        "cli": name,
                        "planType": None,
                        "updatedAt": now_timeout_iso,
                        "source": "unavailable",
                        "status": "error",
                        "message": "实时限额探测超时，请检查网络后刷新重试",
                        "windows": [],
                        "credits": None,
                    }
        finally:
            pool.shutdown(wait=False, cancel_futures=True)

    def _provider_sort_rank(item: dict[str, Any]) -> int:
        has_data = bool(item.get("windows")) or item.get("credits") is not None
        if has_data:
            return 0
        if item.get("status") == "error":
            return 1
        return 2

    ordered = [
        results_map[name]
        for name, _ in active_probes
        if name in results_map and results_map[name].get("status") != "unconfigured"
    ]
    ordered.sort(key=_provider_sort_rank)

    payload = {
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "cachedAtEpoch": now_sec,
        "providers": ordered,
    }
    if not timed_out:
        try:
            cache_path.write_text(json.dumps(payload, ensure_ascii=False), "utf-8")
        except Exception:
            pass
    return payload


def _parse_days_or_period_arg(raw_arg: str | None) -> tuple[int, str | None]:
    if not raw_arg:
        return 30, None
    norm = raw_arg.strip().lower()
    if norm in ("today", "week", "month", "all"):
        return 30, norm
    try:
        return int(norm), None
    except ValueError:
        return 30, None


def main(argv: list[str]) -> int:
    cmd = argv[1] if len(argv) > 1 else "summary"
    if cmd == "summary":
        days, period = _parse_days_or_period_arg(argv[2] if len(argv) > 2 else None)
        tz_name = argv[3] if len(argv) > 3 else "UTC"
        refresh = "--refresh" in argv
        res = collect_summary(days=days, tz_name=tz_name, refresh=refresh, period=period)
        sys.stdout.write(json.dumps(res, ensure_ascii=False))
        return 0
    if cmd == "limits":
        fresh = "--fresh" in argv
        res = collect_limits(fresh=fresh)
        sys.stdout.write(json.dumps(res, ensure_ascii=False))
        return 0
    if cmd == "all":
        days, period = _parse_days_or_period_arg(argv[2] if len(argv) > 2 else None)
        tz_name = argv[3] if len(argv) > 3 else "UTC"
        refresh = "--refresh" in argv
        res = {
            "summary": collect_summary(days=days, tz_name=tz_name, refresh=refresh, period=period),
            "limits": collect_limits(fresh=refresh),
        }
        sys.stdout.write(json.dumps(res, ensure_ascii=False))
        return 0
    sys.stderr.write(f"Unknown command: {cmd}\n")
    return 1

if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

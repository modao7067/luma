# Adapted from t3code UsageService/UsageLimitSources, junhoyeo/tokscale, and MoonshotAI/kimi-cli.
from __future__ import annotations

import calendar
import contextlib
import datetime as dt
import glob
import gzip
import hashlib
import io
import json
import math
import os
import re
import shutil
import signal
import sqlite3
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


def _is_zh() -> bool:
    return os.environ.get("LUMA_LANG", "zh").strip().lower().startswith("zh")


def _lang_code() -> str:
    return "zh" if _is_zh() else "en"


def _tr(zh: str, en: str) -> str:
    return zh if _is_zh() else en


CACHE_VERSION = 6
ARCHIVE_VERSION = 2
SCANNER_REV = 4
PRICING_CACHE_VERSION = 1
GUARD_BYTES = 64
MAX_RECORDS_PER_SOURCE = 25000
LIMITS_CACHE_TTL_SECONDS = 60
PRICING_CACHE_TTL_SECONDS = 86400
PRICING_OFFLINE_BACKOFF_SECONDS = 300
MAX_HTTP_RESPONSE_BYTES = 1_048_576
MAX_PRICING_RESPONSE_BYTES = 33_554_432
MAX_SQLITE_SNAPSHOT_BYTES = 67_108_864

RateTuple = tuple[float, float, float, float, tuple[int, float, float, float, float] | None]

MODELS_DEV_API_URL = "https://models.dev/api.json"
LITELLM_PRICES_URLS = (
    "https://cdn.jsdelivr.net/gh/BerriAI/litellm@main/model_prices_and_context_window.json",
    "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json",
)
TRUSTED_MODELS_DEV_PROVIDERS = (
    "anthropic",
    "openai",
    "google",
    "deepseek",
    "moonshotai",
    "zhipuai",
    "xai",
    "minimax",
    "mistral",
    "cohere",
    "alibaba",
    "moonshotai-cn",
    "minimax-cn",
    "alibaba-cn",
    "google-vertex",
)
PRICING_SOURCE_CATALOG = (
    {
        "id": "models.dev",
        "name": "models.dev",
        "repo": "anomalyco/models.dev",
        "url": "https://github.com/anomalyco/models.dev",
        "siteUrl": "https://models.dev",
    },
    {
        "id": "litellm",
        "name": "LiteLLM",
        "repo": "BerriAI/litellm",
        "url": "https://github.com/BerriAI/litellm",
        "siteUrl": "https://models.litellm.ai",
    },
)
MODEL_PRICING_ALIASES: dict[str, str] = {
    "gemini-pro-orchestrator": "gemini-4-carbon",
    "gemini-pro-orchestrator-argon": "gemini-4-argon",
    "gemini-4": "gemini-4-carbon",
    "deepseek-v4": "deepseek-v4-pro",
    "kimi-for-coding": "kimi-k2.7-code",
    "kimi-for-coding-highspeed": "kimi-k2.7-code-highspeed",
    "k2p5": "kimi-k2-thinking",
    "k2-p5": "kimi-k2-thinking",
    "k2p6": "kimi-k2.6",
    "k2-p6": "kimi-k2.6",
    "kimi-k2p6": "kimi-k2.6",
    "kimi-k2.5-thinking": "kimi-k2-thinking",
    "k2d6-agent": "kimi-k2.6",
    "k3": "kimi-k3",
    "k3-256k": "kimi-k3",
    "k3-agent": "kimi-k3",
    "k3-agent-swarm": "kimi-k3",
    "k3.1-pro": "kimi-k3.1-pro",
    "k3.1-pro-highspeed": "kimi-k3.1-pro-highspeed",
    "kimi-k2.5-nvfp4": "kimi-k2.5",
    "kimi-k2-instruct-0905": "kimi-k2.5",
}

MODEL_RATES_PER_M: dict[str, RateTuple] = {
    "gpt-6.1": (2.50, 15.00, 0.25, 2.50, None),
    "gpt-6": (2.50, 15.00, 0.25, 2.50, None),
    "codex-auto-review": (0.40, 1.60, 0.04, 0.40, None),
    "gpt-5.4": (2.50, 15.00, 0.25, 2.50, (272_000, 5.00, 22.50, 0.50, 5.00)),
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
    "claude-opus-5-5": (4.00, 20.00, 0.20, 5.00, None),
    "claude-sonnet-5-5": (2.00, 10.00, 0.10, 2.50, None),
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
    "gemini-4-hydrogen": (1.50, 7.50, 0.075, 1.50, None),
    "gemini-4-carbon": (2.00, 10.00, 0.10, 2.00, None),
    "gemini-4-argon": (2.00, 10.00, 0.10, 2.00, None),
    "gemini-4": (2.00, 10.00, 0.10, 2.00, None),
    "gemini-pro-orchestrator-argon": (2.00, 10.00, 0.10, 2.00, None),
    "gemini-pro-orchestrator": (2.00, 10.00, 0.10, 2.00, None),
    "gemini-3.8-flash": (0.75, 3.75, 0.075, 0.75, None),
    "gemini-3.7-flash": (0.75, 3.75, 0.075, 0.75, None),
    "gemini-3.6-flash": (0.75, 3.75, 0.075, 0.75, None),
    "gemini-3.5-flash-lite": (0.10, 0.40, 0.025, 0.10, None),
    "gemini-3.5-flash": (0.15, 0.60, 0.0375, 0.15, None),
    "gemini-3.1-flash-lite": (0.25, 1.50, 0.025, 0.25, None),
    "gemini-3.1-pro": (2.00, 12.00, 0.20, 2.00, (200_000, 4.00, 18.00, 0.40, 4.00)),
    "gemini-3-pro": (1.25, 10.00, 0.3125, 1.25, (200_000, 2.50, 15.00, 0.625, 2.50)),
    "gemini-3-flash": (0.15, 0.60, 0.0375, 0.15, None),
    "gemini-2.5-pro": (1.25, 10.00, 0.3125, 1.25, (200_000, 2.50, 15.00, 0.625, 2.50)),
    "gemini-2.5-flash": (0.15, 0.60, 0.0375, 0.15, None),
    "gpt-oss-120b": (0.09, 0.36, 0.045, 0.09, None),
    "grok-4.7-build-fast": (4.00, 12.00, 1.00, 4.00, None),
    "grok-4.7-build": (2.00, 6.00, 0.50, 2.00, None),
    "grok-4.6-build": (2.00, 6.00, 0.50, 2.00, None),
    "grok-4.20": (3.00, 15.00, 0.75, 3.00, None),
    "grok-4": (3.00, 15.00, 0.75, 3.00, None),
    "grok-code-fast-1": (0.20, 1.50, 0.02, 0.20, None),
    "grok-3": (3.00, 15.00, 0.75, 3.00, None),
    "grok-3-mini": (0.30, 0.50, 0.075, 0.30, None),
    "deepseek-v4-pro": (0.66, 1.98, 0.022, 0.66, None),
    "deepseek-v4-flash": (0.15, 0.60, 0.003, 0.15, None),
    "deepseek-v4.1-flash": (0.10, 0.40, 0.025, 0.10, None),
    "deepseek-flash": (0.15, 0.60, 0.003, 0.15, None),
    "deepseek-v4": (0.27, 1.10, 0.07, 0.27, None),
    "deepseek-chat": (0.28, 0.42, 0.028, 0.28, None),
    "deepseek-reasoner": (0.28, 0.42, 0.028, 0.28, None),
    "deepseek-v3": (0.27, 1.10, 0.07, 0.27, None),
    "deepseek-r1": (0.55, 2.19, 0.14, 0.55, None),
    "kimi-k3.1-pro-highspeed": (4.00, 20.00, 0.20, 4.00, None),
    "kimi-k3.1-pro": (2.00, 10.00, 0.20, 2.00, None),
    "kimi-k3": (3.00, 15.00, 0.30, 3.00, None),
    "kimi-k2.7-code-highspeed": (1.90, 8.00, 0.38, 1.90, None),
    "kimi-k2.7-code": (0.95, 4.00, 0.19, 0.95, None),
    "kimi-k2.6": (0.95, 4.00, 0.16, 0.95, None),
    "kimi-k2-thinking": (0.574, 2.294, 0.574, 0.574, None),
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
    342: "gpt-oss-120b",
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
    1050: "gemini-3.1-flash-lite",
    1071: "gemini-3.6-flash",
    1072: "gemini-3.6-flash",
    1073: "gemini-3.6-flash",
    1084: "gemini-3-flash-preview",
    1298: "gemini-3.7-flash",
    1299: "gemini-3.7-flash",
    1300: "gemini-3.7-flash",
    1318: "gemini-3.8-flash",
    1319: "gemini-3.8-flash",
    1320: "gemini-3.8-flash",
    1322: "gemini-3.8-flash-tiered",
    1400: "claude-opus-5-5",
    1401: "claude-opus-5-5",
    1402: "claude-opus-5-5",
    1403: "claude-sonnet-5-5",
    1404: "claude-sonnet-5-5",
    1405: "claude-sonnet-5-5",
    1417: "gemini-4-argon",
    1418: "gemini-4-argon",
    1419: "gemini-4-argon",
    1427: "gemini-4-carbon",
    1437: "gemini-4-carbon",
    1438: "gemini-4-carbon",
    1461: "gemini-4-hydrogen",
    1462: "gemini-4-hydrogen",
    1463: "gemini-4-hydrogen",
    1464: "gemini-4-hydrogen",
    1528: "gemini-4-hydrogen",
    1667: "gemini-pro-orchestrator",
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
        if val <= 0:
            return None
        if val < 10_000_000_000:
            return int(val * 1000)
        n = int(val)
        if n > 10_000_000_000_000:
            return n // 1000
        return n
    if isinstance(val, str):
        s = val.strip()
        if not s:
            return None
        if s.isdigit():
            return _parse_ts_ms(int(s))
        if re.fullmatch(r"\d+\.\d+", s):
            try:
                return _parse_ts_ms(float(s))
            except Exception:
                return None
        try:
            if s.endswith("Z"):
                s = s[:-1] + "+00:00"
            s = re.sub(r"\.(\d{6})\d+", r".\1", s)
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

_PRICING_LOCK = threading.Lock()
_PRICING_RUNTIME_STATE: dict[str, Any] = {
    "cache_path": "",
    "mtime_ns": -2,
    "checked_mono": 0.0,
    "catalog": None,
}


def _pricing_cache_path() -> Path:
    return _cache_dir() / f"pricing-catalog-v{PRICING_CACHE_VERSION}.json"


def _coerce_rate_tuple(val: Any) -> RateTuple | None:
    if not isinstance(val, (list, tuple)) or len(val) < 4:
        return None
    in_m = _safe_float(val[0])
    out_m = _safe_float(val[1])
    read_m = _safe_float(val[2])
    write_m = _safe_float(val[3])
    if in_m is None or out_m is None or read_m is None or write_m is None:
        return None
    tiered: tuple[int, float, float, float, float] | None = None
    raw_tier = val[4] if len(val) >= 5 else None
    if isinstance(raw_tier, (list, tuple)) and len(raw_tier) >= 5:
        thresh = _safe_int(raw_tier[0])
        t_in = _safe_float(raw_tier[1])
        t_out = _safe_float(raw_tier[2])
        t_read = _safe_float(raw_tier[3])
        t_write = _safe_float(raw_tier[4])
        if thresh > 0 and t_in is not None and t_out is not None and t_read is not None and t_write is not None:
            tiered = (thresh, t_in, t_out, t_read, t_write)
    return (in_m, out_m, read_m, write_m, tiered)


def _serialize_rate_tuple(rate: RateTuple) -> list[Any]:
    in_m, out_m, read_m, write_m, tiered = rate
    return [
        round(in_m, 6),
        round(out_m, 6),
        round(read_m, 6),
        round(write_m, 6),
        [tiered[0], round(tiered[1], 6), round(tiered[2], 6), round(tiered[3], 6), round(tiered[4], 6)]
        if tiered is not None
        else None,
    ]


def _pricing_backoff_path() -> Path:
    return _cache_dir() / "pricing-catalog-backoff-v1.json"


def _validate_http_url(url: Any) -> str | None:
    if not isinstance(url, str):
        return None
    cleaned = url.strip()
    if not cleaned or any(ch in cleaned for ch in "\r\n\x00"):
        return None
    try:
        parsed = urllib.parse.urlparse(cleaned)
    except Exception:
        return None
    if parsed.scheme.lower() not in ("http", "https") or not parsed.netloc:
        return None
    return cleaned


def _read_response_bytes(resp: Any, max_bytes: int = MAX_HTTP_RESPONSE_BYTES) -> bytes:
    try:
        raw = resp.read(max_bytes + 1)
    except TypeError:
        raw = resp.read()
    if not isinstance(raw, (bytes, bytearray)):
        raise ValueError("invalid response body")
    data = bytes(raw)
    if len(data) > max_bytes:
        raise ValueError("response too large")
    return data


def _atomic_write_text(path: Path, text: str, mode: int | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=str(path.parent),
            prefix=f".{path.name}.",
            suffix=".tmp",
            delete=False,
        ) as fh:
            tmp_path = Path(fh.name)
            if mode is not None and hasattr(os, "fchmod"):
                os.fchmod(fh.fileno(), mode)
            fh.write(text)
            fh.flush()
            try:
                os.fsync(fh.fileno())
            except OSError:
                pass
        if mode is not None:
            try:
                os.chmod(tmp_path, mode)
            except Exception:
                pass
        os.replace(tmp_path, path)
        tmp_path = None
    finally:
        if tmp_path is not None:
            try:
                tmp_path.unlink(missing_ok=True)
            except Exception:
                pass


def _run_daemon_tasks(tasks: dict[str, Any], timeout: float) -> dict[str, tuple[bool, Any]]:
    results: dict[str, tuple[bool, Any]] = {}
    lock = threading.Lock()
    done_events: list[threading.Event] = []

    def _runner(key: str, fn: Any, ev: threading.Event) -> None:
        try:
            val = fn()
            with lock:
                results[key] = (True, val)
        except Exception as exc:
            with lock:
                results[key] = (False, type(exc).__name__)
        finally:
            ev.set()

    for key, fn in tasks.items():
        ev = threading.Event()
        done_events.append(ev)
        t = threading.Thread(
            target=_runner,
            args=(key, fn, ev),
            daemon=True,
        )
        t.start()

    deadline = time.monotonic() + max(0.0, timeout)
    for ev in done_events:
        rem = deadline - time.monotonic()
        if rem <= 0.0:
            break
        ev.wait(timeout=rem)

    with lock:
        return dict(results)


def _safe_stat_sig(path: Path) -> tuple[int, int] | None:
    try:
        st = os.stat(path)
        return (st.st_size, st.st_mtime_ns)
    except OSError:
        return None


def _db_fingerprint_sig(
    db_path: Path,
    extra_paths: tuple[Path, ...] = (),
) -> str | None:
    db_sig = _safe_stat_sig(db_path)
    if db_sig is None:
        return None
    parts = [f"{db_sig[0]}:{db_sig[1]}"]
    for p in (Path(str(db_path) + "-wal"), Path(str(db_path) + "-journal"), *extra_paths):
        s = _safe_stat_sig(p)
        parts.append(f"{s[0]}:{s[1]}" if s is not None else "0:0")
    return "|".join(parts)


@contextlib.contextmanager
def _open_readonly_sqlite(db_path: Path, timeout: float = 2.0):
    abs_path = Path(os.path.abspath(db_path))
    if not abs_path.is_file():
        raise FileNotFoundError(str(abs_path))
    wal_path = Path(str(abs_path) + "-wal")
    wal_sig = _safe_stat_sig(wal_path)
    is_wal_hdr = False
    try:
        with open(abs_path, "rb") as fh:
            hdr = fh.read(20)
            is_wal_hdr = len(hdr) >= 20 and hdr[18:20] == b"\x02\x02"
    except OSError:
        pass
    if wal_sig is not None and wal_sig[0] > 0:
        db_sig = _safe_stat_sig(abs_path)
        db_bytes = db_sig[0] if db_sig is not None else 0
        if db_bytes + wal_sig[0] <= MAX_SQLITE_SNAPSHOT_BYTES:
            with tempfile.TemporaryDirectory(prefix="luma-sqlite-") as tmpdir:
                t_db = Path(tmpdir) / "db.sqlite"
                shutil.copy2(abs_path, t_db)
                try:
                    shutil.copy2(wal_path, Path(str(t_db) + "-wal"))
                except FileNotFoundError:
                    shutil.copy2(abs_path, t_db)
                shm_path = Path(str(abs_path) + "-shm")
                try:
                    shutil.copy2(shm_path, Path(str(t_db) + "-shm"))
                except OSError:
                    pass
                con = sqlite3.connect(f"{t_db.as_uri()}?mode=ro", uri=True, timeout=timeout)
                try:
                    yield con
                finally:
                    con.close()
            return
        uri = f"{abs_path.as_uri()}?mode=ro"
    elif is_wal_hdr or wal_sig is not None:
        uri = f"{abs_path.as_uri()}?mode=ro&immutable=1"
    else:
        uri = f"{abs_path.as_uri()}?mode=ro"
    con = sqlite3.connect(uri, uri=True, timeout=timeout)
    try:
        yield con
    finally:
        con.close()


def _fetch_remote_json(url: str, timeout: float = 6.5) -> Any | None:
    safe_url = _validate_http_url(url)
    if safe_url is None:
        return None
    try:
        req = urllib.request.Request(
            safe_url,
            headers={
                "User-Agent": "Luma/1.0",
                "Accept": "application/json",
                "Accept-Encoding": "gzip",
            },
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = _read_response_bytes(resp, MAX_PRICING_RESPONSE_BYTES)
            enc = (resp.headers.get("Content-Encoding") or "").lower()
        if "gzip" in enc or raw[:2] == b"\x1f\x8b":
            with gzip.GzipFile(fileobj=io.BytesIO(raw)) as gz:
                raw = gz.read(MAX_PRICING_RESPONSE_BYTES + 1)
            if len(raw) > MAX_PRICING_RESPONSE_BYTES:
                return None
        return json.loads(raw.decode("utf-8"))
    except Exception:
        return None


def _extract_models_dev_rate(cost: Any) -> RateTuple | None:
    if not isinstance(cost, dict):
        return None
    in_m = _safe_float(cost.get("input"))
    out_m = _safe_float(cost.get("output"))
    if in_m is None or out_m is None or (in_m <= 0.0 and out_m <= 0.0):
        return None
    if in_m > 1000.0 or out_m > 2000.0:
        return None
    cr_raw = _safe_float(cost.get("cache_read"))
    read_m = cr_raw if cr_raw is not None else in_m
    cw_raw = _safe_float(cost.get("cache_write"))
    write_m = cw_raw if (cw_raw is not None and cw_raw > 0.0) else in_m

    tiered: tuple[int, float, float, float, float] | None = None
    tiers_raw = cost.get("tiers")
    best_thresh = 0
    best_tier_obj: dict[str, Any] | None = None
    if isinstance(tiers_raw, list):
        for item in tiers_raw:
            if not isinstance(item, dict):
                continue
            tier_meta = item.get("tier")
            if isinstance(tier_meta, dict) and tier_meta.get("type") == "context":
                sz = _safe_int(tier_meta.get("size"))
                if sz > 0 and (best_thresh == 0 or sz < best_thresh):
                    best_thresh = sz
                    best_tier_obj = item
    if best_tier_obj is None and isinstance(cost.get("context_over_200k"), dict):
        best_thresh = 200_000
        best_tier_obj = cost["context_over_200k"]
    if best_tier_obj is not None and best_thresh > 0:
        t_in = _safe_float(best_tier_obj.get("input"))
        t_out = _safe_float(best_tier_obj.get("output"))
        if t_in is not None and t_out is not None and (t_in > 0.0 or t_out > 0.0):
            t_cr = _safe_float(best_tier_obj.get("cache_read"))
            t_read = t_cr if t_cr is not None else t_in
            t_cw = _safe_float(best_tier_obj.get("cache_write"))
            t_write = t_cw if (t_cw is not None and t_cw > 0.0) else t_in
            tiered = (best_thresh, t_in, t_out, t_read, t_write)
    return (in_m, out_m, read_m, write_m, tiered)


def _parse_models_dev_rates(payload: Any) -> tuple[dict[str, RateTuple], dict[str, RateTuple]]:
    primary: dict[str, RateTuple] = {}
    secondary: dict[str, RateTuple] = {}
    if not isinstance(payload, dict):
        return primary, secondary

    def _collect_provider(models_obj: Any, dest: dict[str, RateTuple], check_modality: bool) -> None:
        if not isinstance(models_obj, dict):
            return
        local_map: dict[str, RateTuple] = {}
        for raw_mid, m_info in models_obj.items():
            if not isinstance(raw_mid, str) or not isinstance(m_info, dict):
                continue
            if check_modality:
                mods = m_info.get("modalities")
                if isinstance(mods, dict):
                    out_mods = mods.get("output")
                    if isinstance(out_mods, list) and out_mods and "text" not in out_mods:
                        continue
            rate = _extract_models_dev_rate(m_info.get("cost"))
            if rate is None:
                continue
            norm = _normalize_model_id(raw_mid)
            if not norm or norm == "unknown":
                continue
            if norm not in local_map or raw_mid.strip().lower() == norm:
                local_map[norm] = rate
        for norm, rate in local_map.items():
            dest.setdefault(norm, rate)

    trusted_set = set(TRUSTED_MODELS_DEV_PROVIDERS)
    for prov in TRUSTED_MODELS_DEV_PROVIDERS:
        p_info = payload.get(prov)
        if isinstance(p_info, dict):
            _collect_provider(p_info.get("models"), primary, check_modality=False)

    for prov, p_info in payload.items():
        if not isinstance(prov, str) or prov in trusted_set or not isinstance(p_info, dict):
            continue
        low_prov = prov.lower()
        if "plan" in low_prov or "free" in low_prov:
            continue
        _collect_provider(p_info.get("models"), secondary, check_modality=True)

    return primary, secondary


_LITELLM_BEDROCK_PREFIX_RE = re.compile(
    r"^(?:(?:us|eu|ap|au|ca|global)\.)?(?:anthropic|amazon|meta|mistral|cohere|deepseek)\.",
    re.IGNORECASE,
)
_LITELLM_CANONICAL_PREFIXES = (
    "anthropic/",
    "openai/",
    "gemini/",
    "deepseek/",
    "moonshot/",
    "xai/",
    "zai/",
    "dashscope/",
    "qwen/",
    "minimax/",
)
_LITELLM_SKIP_MODES = {
    "embedding",
    "image_generation",
    "audio_transcription",
    "audio_speech",
    "moderation",
    "rerank",
    "video_generation",
}


def _parse_litellm_rates(payload: Any) -> dict[str, RateTuple]:
    ranked: dict[str, tuple[int, RateTuple]] = {}
    if not isinstance(payload, dict):
        return {}
    for raw_key, info in payload.items():
        if not isinstance(raw_key, str) or raw_key == "sample_spec" or not isinstance(info, dict):
            continue
        mode = str(info.get("mode") or "").strip().lower()
        if mode in _LITELLM_SKIP_MODES:
            continue
        in_tok = _safe_float(info.get("input_cost_per_token"))
        out_tok = _safe_float(info.get("output_cost_per_token"))
        if in_tok is None or out_tok is None:
            continue
        in_m = round(in_tok * 1_000_000.0, 6)
        out_m = round(out_tok * 1_000_000.0, 6)
        if (in_m <= 0.0 and out_m <= 0.0) or in_m > 1000.0 or out_m > 2000.0:
            continue
        cr_tok = _safe_float(info.get("cache_read_input_token_cost"))
        read_m = round(cr_tok * 1_000_000.0, 6) if cr_tok is not None else in_m
        cw_tok = _safe_float(info.get("cache_creation_input_token_cost"))
        write_m = round(cw_tok * 1_000_000.0, 6) if (cw_tok is not None and cw_tok > 0.0) else in_m

        tiered: tuple[int, float, float, float, float] | None = None
        t_in_tok = _safe_float(info.get("input_cost_per_token_above_200k_tokens"))
        t_out_tok = _safe_float(info.get("output_cost_per_token_above_200k_tokens"))
        if t_in_tok is not None and t_out_tok is not None and (t_in_tok > 0.0 or t_out_tok > 0.0):
            t_in = round(t_in_tok * 1_000_000.0, 6)
            t_out = round(t_out_tok * 1_000_000.0, 6)
            t_cr_tok = _safe_float(info.get("cache_read_input_token_cost_above_200k_tokens"))
            t_read = round(t_cr_tok * 1_000_000.0, 6) if t_cr_tok is not None else t_in
            t_cw_tok = _safe_float(info.get("cache_creation_input_token_cost_above_200k_tokens"))
            t_write = round(t_cw_tok * 1_000_000.0, 6) if (t_cw_tok is not None and t_cw_tok > 0.0) else t_in
            tiered = (200_000, t_in, t_out, t_read, t_write)

        stripped_key = _LITELLM_BEDROCK_PREFIX_RE.sub("", raw_key.strip())
        norm = _normalize_model_id(stripped_key)
        if not norm or norm == "unknown":
            continue
        low_raw = raw_key.strip().lower()
        if low_raw == norm:
            prio = 0
        elif low_raw.startswith(_LITELLM_CANONICAL_PREFIXES):
            prio = 1
        elif "/" not in low_raw and stripped_key.lower() == norm:
            prio = 2
        else:
            prio = 3
        rate: RateTuple = (in_m, out_m, read_m, write_m, tiered)
        prev = ranked.get(norm)
        if prev is None or prio < prev[0]:
            ranked[norm] = (prio, rate)
    return {k: v[1] for k, v in ranked.items()}


def _merge_rate_maps(
    base_map: dict[str, RateTuple],
    override_map: dict[str, RateTuple],
) -> dict[str, RateTuple]:
    merged = dict(base_map)
    for key, rate in override_map.items():
        prev = merged.get(key)
        if (
            rate[4] is None
            and prev is not None
            and prev[4] is not None
            and abs(rate[0] - prev[0]) < 1e-6
            and abs(rate[1] - prev[1]) < 1e-6
        ):
            merged[key] = (rate[0], rate[1], rate[2], rate[3], prev[4])
        else:
            merged[key] = rate
    return merged


def _build_runtime_catalog(
    online_rates: dict[str, RateTuple],
    mode: str,
    updated_at: str,
    active_sources: list[str],
) -> dict[str, Any]:
    merged = _merge_rate_maps(MODEL_RATES_PER_M, online_rates)
    sorted_keys = sorted(merged.keys(), key=len, reverse=True)
    digest_payload = json.dumps(
        [[k, _serialize_rate_tuple(merged[k])] for k in sorted(merged.keys())],
        separators=(",", ":"),
    ).encode("utf-8")
    return {
        "mode": mode,
        "updatedAt": updated_at,
        "activeSources": list(active_sources),
        "modelCount": len(merged),
        "rates": merged,
        "sortedKeys": sorted_keys,
        "digest": _fnv1a64(digest_payload),
        "memo": {},
    }


def _load_cached_pricing_payload(path: Path) -> dict[str, Any] | None:
    try:
        if not path.is_file():
            return None
        obj = json.loads(path.read_text("utf-8"))
        if not isinstance(obj, dict) or obj.get("version") != PRICING_CACHE_VERSION:
            return None
        raw_rates = obj.get("rates")
        if not isinstance(raw_rates, dict) or not raw_rates:
            return None
        parsed_rates: dict[str, RateTuple] = {}
        for k, v in raw_rates.items():
            if isinstance(k, str) and k:
                rt = _coerce_rate_tuple(v)
                if rt is not None:
                    parsed_rates[k] = rt
        if not parsed_rates:
            return None
        return {
            "fetchedAtEpoch": _safe_int(obj.get("fetchedAtEpoch")),
            "updatedAt": str(obj.get("updatedAt") or ""),
            "activeSources": [str(s) for s in (obj.get("activeSources") or []) if isinstance(s, str)],
            "rates": parsed_rates,
        }
    except Exception:
        return None


def _get_pricing_catalog(force_reload_disk: bool = False) -> dict[str, Any]:
    path = _pricing_cache_path()
    path_str = str(path)
    now_mono = time.monotonic()
    with _PRICING_LOCK:
        cached_cat = _PRICING_RUNTIME_STATE.get("catalog")
        if (
            not force_reload_disk
            and cached_cat is not None
            and _PRICING_RUNTIME_STATE.get("cache_path") == path_str
            and (now_mono - float(_PRICING_RUNTIME_STATE.get("checked_mono") or 0.0)) < 2.0
        ):
            return cached_cat
        try:
            st_mtime = path.stat().st_mtime_ns
        except Exception:
            st_mtime = -1
        if (
            not force_reload_disk
            and cached_cat is not None
            and _PRICING_RUNTIME_STATE.get("cache_path") == path_str
            and _PRICING_RUNTIME_STATE.get("mtime_ns") == st_mtime
        ):
            _PRICING_RUNTIME_STATE["checked_mono"] = now_mono
            return cached_cat

    disk = _load_cached_pricing_payload(path) if st_mtime >= 0 else None
    if disk is not None:
        cat = _build_runtime_catalog(
            online_rates=disk["rates"],
            mode="cached",
            updated_at=disk["updatedAt"],
            active_sources=disk["activeSources"],
        )
    else:
        cat = _build_runtime_catalog(
            online_rates={},
            mode="builtin",
            updated_at="",
            active_sources=[],
        )
    with _PRICING_LOCK:
        _PRICING_RUNTIME_STATE["cache_path"] = path_str
        _PRICING_RUNTIME_STATE["mtime_ns"] = st_mtime
        _PRICING_RUNTIME_STATE["checked_mono"] = now_mono
        _PRICING_RUNTIME_STATE["catalog"] = cat
    return cat


def _fetch_litellm_json() -> Any | None:
    for url in LITELLM_PRICES_URLS:
        data = _fetch_remote_json(url)
        if isinstance(data, dict) and data:
            return data
    return None


def _sync_pricing_catalog(force: bool = False) -> dict[str, Any]:
    path = _pricing_cache_path()
    backoff_path = _pricing_backoff_path()
    now_sec = int(time.time())
    disk = _load_cached_pricing_payload(path)
    if (
        not force
        and disk is not None
        and disk["fetchedAtEpoch"] > 0
        and (now_sec - disk["fetchedAtEpoch"]) < PRICING_CACHE_TTL_SECONDS
    ):
        return _get_pricing_catalog()
    if not force and backoff_path.is_file():
        try:
            backoff_raw = json.loads(backoff_path.read_text("utf-8"))
            failed_at = _safe_int(backoff_raw.get("failedAtEpoch")) if isinstance(backoff_raw, dict) else 0
            if failed_at > 0 and 0 <= (now_sec - failed_at) < PRICING_OFFLINE_BACKOFF_SECONDS:
                return _get_pricing_catalog()
        except Exception:
            pass

    task_res = _run_daemon_tasks(
        {
            "md": lambda: _fetch_remote_json(MODELS_DEV_API_URL),
            "ll": _fetch_litellm_json,
        },
        timeout=8.0,
    )
    md_raw = task_res["md"][1] if "md" in task_res and task_res["md"][0] else None
    ll_raw = task_res["ll"][1] if "ll" in task_res and task_res["ll"][0] else None

    md_primary, md_secondary = _parse_models_dev_rates(md_raw) if md_raw is not None else ({}, {})
    ll_rates = _parse_litellm_rates(ll_raw) if ll_raw is not None else {}

    active_sources: list[str] = []
    if md_primary or md_secondary:
        active_sources.append("models.dev")
    if ll_rates:
        active_sources.append("litellm")

    merged_online = _merge_rate_maps(md_secondary, ll_rates)
    merged_online = _merge_rate_maps(merged_online, md_primary)

    if merged_online:
        updated_iso = dt.datetime.now(dt.timezone.utc).isoformat()
        payload = {
            "version": PRICING_CACHE_VERSION,
            "fetchedAtEpoch": now_sec,
            "updatedAt": updated_iso,
            "activeSources": active_sources,
            "rates": {k: _serialize_rate_tuple(v) for k, v in sorted(merged_online.items())},
        }
        try:
            _atomic_write_text(path, json.dumps(payload, separators=(",", ":")))
        except Exception:
            pass
        try:
            backoff_path.unlink(missing_ok=True)
        except Exception:
            pass
        cat = _build_runtime_catalog(
            online_rates=merged_online,
            mode="online",
            updated_at=updated_iso,
            active_sources=active_sources,
        )
        try:
            st_mtime = path.stat().st_mtime_ns
        except Exception:
            st_mtime = -1
        with _PRICING_LOCK:
            _PRICING_RUNTIME_STATE["cache_path"] = str(path)
            _PRICING_RUNTIME_STATE["mtime_ns"] = st_mtime
            _PRICING_RUNTIME_STATE["checked_mono"] = time.monotonic()
            _PRICING_RUNTIME_STATE["catalog"] = cat
        return cat

    try:
        _atomic_write_text(backoff_path, json.dumps({"failedAtEpoch": now_sec}, separators=(",", ":")))
    except Exception:
        pass
    return _get_pricing_catalog(force_reload_disk=True)


def _pricing_source_metadata(catalog: dict[str, Any]) -> dict[str, Any]:
    active_set = set(catalog.get("activeSources") or [])
    sources_out = []
    for item in PRICING_SOURCE_CATALOG:
        sources_out.append({
            "id": item["id"],
            "name": item["name"],
            "repo": item["repo"],
            "url": item["url"],
            "siteUrl": item["siteUrl"],
            "active": item["id"] in active_set,
        })
    return {
        "mode": str(catalog.get("mode") or "builtin"),
        "updatedAt": str(catalog.get("updatedAt") or ""),
        "modelCount": _safe_int(catalog.get("modelCount")),
        "sources": sources_out,
    }


def _lookup_model_rate_in_catalog(model_id: str, catalog: dict[str, Any]) -> RateTuple | None:
    m = _normalize_model_id(model_id)
    if m.endswith(":fast"):
        m = m[:-5]
    if not m or m in ("unknown", "<synthetic>", "synthetic", "gemini-default", "antigravity-unknown"):
        return None
    memo: dict[str, RateTuple | None] = catalog["memo"]
    if m in memo:
        return memo[m]
    rates: dict[str, RateTuple] = catalog["rates"]

    def _resolve() -> RateTuple | None:
        alias_direct = MODEL_PRICING_ALIASES.get(m)
        if alias_direct and alias_direct in rates:
            return rates[alias_direct]
        if m in rates:
            return rates[m]
        cleaned = m
        for suffix in (
            "-thinking",
            "-extra-low",
            "-xhigh",
            "-medium",
            "-high",
            "-low",
            "-max",
            "-preview",
            "-latest",
            "-trial",
            "-exp",
            "-canary",
            "-tiered",
        ):
            if cleaned.endswith(suffix):
                cleaned = cleaned[: -len(suffix)]
                alias_clean = MODEL_PRICING_ALIASES.get(cleaned)
                if alias_clean and alias_clean in rates:
                    return rates[alias_clean]
                if cleaned in rates:
                    return rates[cleaned]
                if f"{cleaned}-preview" in rates:
                    return rates[f"{cleaned}-preview"]
        alias_clean = MODEL_PRICING_ALIASES.get(cleaned)
        if alias_clean and alias_clean in rates:
            return rates[alias_clean]
        if cleaned in rates:
            return rates[cleaned]
        if f"{cleaned}-preview" in rates:
            return rates[f"{cleaned}-preview"]
        alt_hyphen = cleaned.replace(".", "-")
        if alt_hyphen in rates:
            return rates[alt_hyphen]
        if f"{alt_hyphen}-preview" in rates:
            return rates[f"{alt_hyphen}-preview"]
        for key in catalog["sortedKeys"]:
            if cleaned == key or cleaned.startswith(key + "-") or cleaned.startswith(key + "."):
                return rates[key]
            if alt_hyphen == key or alt_hyphen.startswith(key + "-"):
                return rates[key]
        return None

    ans = _resolve()
    if len(memo) < 4096:
        memo[m] = ans
    return ans


def _lookup_model_rate(model_id: str) -> RateTuple | None:
    if not isinstance(model_id, str):
        return None
    return _lookup_model_rate_in_catalog(model_id, _get_pricing_catalog())


def _price_record(
    rec: dict[str, Any],
    catalog: dict[str, Any] | None = None,
    event_count: int = 1,
) -> dict[str, Any]:
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
    active_cat = catalog if catalog is not None else _get_pricing_catalog()
    rate = _lookup_model_rate_in_catalog(model, active_cat)
    is_explicit_free = model.lower().endswith("-free") or model.lower().endswith(":free")

    cat_in = 0.0
    cat_read = 0.0
    cat_write = 0.0
    cat_out = 0.0
    cache_savings = 0.0

    if rate is not None:
        in_m, out_m, read_m, write_m, tiered = rate
        prompt_tokens = uncached + cached + cache_write
        if tiered is not None and (prompt_tokens / max(1, event_count)) > tiered[0]:
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
    try:
        _atomic_write_text(path, json.dumps(cache, ensure_ascii=False, separators=(",", ":")))
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
            new_resume = resume_offset
            while True:
                line_offset = f.tell()
                raw_line_with_end = f.readline()
                if not raw_line_with_end:
                    break
                if not raw_line_with_end.endswith(b"\n"):
                    break
                new_resume = f.tell()
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
            if prev is not None and (
                cur_tot["in"] == prev.get("in")
                and cur_tot["cached"] == prev.get("cached")
                and cur_tot["write"] == prev.get("write")
                and cur_tot["out"] == prev.get("out")
                and cur_tot["reason"] == prev.get("reason")
            ):
                return None

        if isinstance(last, dict):
            d_in = _safe_int(last.get("input_tokens"))
            d_cached = _safe_int(last.get("cached_input_tokens"))
            d_write = _safe_int(last.get("cache_write_input_tokens"))
            d_out = _safe_int(last.get("output_tokens"))
            d_reason = _safe_int(last.get("reasoning_output_tokens"))
            if cur_tot is not None and prev is not None:
                p_in = _safe_int(prev.get("in"))
                p_out = _safe_int(prev.get("out"))
                if cur_tot["in"] >= p_in and cur_tot["out"] >= p_out:
                    diff_in = cur_tot["in"] - p_in
                    diff_out = cur_tot["out"] - p_out
                    if d_in > diff_in or d_out > diff_out:
                        d_in = diff_in
                        d_cached = max(0, cur_tot["cached"] - _safe_int(prev.get("cached")))
                        d_write = max(0, cur_tot["write"] - _safe_int(prev.get("write")))
                        d_out = diff_out
                        d_reason = max(0, cur_tot["reason"] - _safe_int(prev.get("reason")))
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
        fp_sig = _db_fingerprint_sig(db_path)
        if fp_sig is None:
            continue

        cached_entry = cache_files.get(db_str)
        if isinstance(cached_entry, dict) and cached_entry.get("fp_sig") == fp_sig and isinstance(cached_entry.get("records"), list):
            db_records = cached_entry["records"]
        else:
            db_records = []
            scan_ok = False
            try:
                with _open_readonly_sqlite(db_path, timeout=2.0) as con:
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
                scan_ok = True
            except Exception:
                db_records = []
                scan_ok = False
            if scan_ok:
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

def _grok_has_incomplete_usage(u: dict[str, Any]) -> bool:
    return any(
        u.get(k) is True
        for k in ("usageIsIncomplete", "usage_is_incomplete")
    )


def _grok_has_untrustworthy_cost(u: dict[str, Any]) -> bool:
    return _grok_has_incomplete_usage(u) or any(
        u.get(k) is True
        for k in ("costIsPartial", "cost_is_partial")
    )


def _grok_extract_ticks(u: dict[str, Any]) -> float | None:
    if _grok_has_untrustworthy_cost(u):
        return None
    for k in ("costUsdTicks", "cost_usd_ticks", "costInUsdTicks", "cost_in_usd_ticks"):
        v = _safe_float(u.get(k))
        if v is not None and v > 0:
            return v
    return None


def _grok_usage_record(
    u: dict[str, Any],
    raw_model: Any,
    ts: int,
    dedup_key: str,
    parent_upd: dict[str, Any] | None = None,
) -> dict[str, Any] | None:
    total_in = _safe_int(u.get("inputTokens") or u.get("input_tokens") or u.get("promptTokens"))
    out_tok = _safe_int(u.get("outputTokens") or u.get("output_tokens") or u.get("completionTokens"))
    c_read = _safe_int(u.get("cachedReadTokens") or u.get("cacheReadTokens") or u.get("cache_read_input_tokens"))
    c_write = _safe_int(
        u.get("cacheCreationTokens")
        or u.get("cachedWriteTokens")
        or u.get("cacheWriteTokens")
        or u.get("cache_creation_input_tokens")
    )
    reason = _safe_int(u.get("reasoningTokens") or u.get("thoughtTokens") or u.get("thinkingTokens"))
    if total_in + out_tok + c_read + c_write + reason <= 0:
        return None
    rep_tot_raw = u.get("totalTokens") if "totalTokens" in u else u.get("total_tokens")
    rep_tot = _safe_int(rep_tot_raw) if rep_tot_raw is not None else None
    is_inclusive = rep_tot is None or rep_tot == (total_in + out_tok)
    uncached = max(0, total_in - c_read - c_write) if is_inclusive else total_in
    out_excl = max(0, out_tok - reason) if is_inclusive else out_tok
    if uncached + c_read + c_write + out_excl + reason <= 0:
        return None
    m = _normalize_model_id(raw_model or "grok-4")
    if parent_upd is not None and _grok_has_untrustworthy_cost(parent_upd):
        ticks = None
    else:
        ticks = _grok_extract_ticks(u)
        if ticks is None and parent_upd is not None:
            ticks = _grok_extract_ticks(parent_upd)
    cost_usd = (ticks / 10_000_000_000.0) if (ticks is not None and ticks > 0) else None
    return {
        "cli": "Grok Build",
        "model": m,
        "timestampMs": ts,
        "uncachedInputTokens": uncached,
        "cachedInputTokens": c_read,
        "cacheCreationTokens": c_write,
        "outputTokens": out_excl,
        "reasoningTokens": reason,
        "reportedCostUsd": cost_usd,
        "dedupKey": dedup_key,
    }


def _grok_session_model_from_dir(session_dir: Path) -> str:
    sig_path = session_dir / "signals.json"
    if sig_path.is_file():
        try:
            sig = json.loads(sig_path.read_text("utf-8"))
            if isinstance(sig, dict):
                m = str(sig.get("modelId") or sig.get("model") or sig.get("primaryModelId") or "").strip()
                if m:
                    return _normalize_model_id(m)
        except Exception:
            pass
    sum_path = session_dir / "summary.json"
    if sum_path.is_file():
        try:
            sm = json.loads(sum_path.read_text("utf-8"))
            if isinstance(sm, dict):
                m = str(sm.get("modelId") or sm.get("model") or sm.get("primaryModelId") or "").strip()
                if m:
                    return _normalize_model_id(m)
        except Exception:
            pass
    return "grok-4.7-build"


def _scan_grok(home: Path, cache_files: dict[str, Any], cutoff_ms: int) -> list[dict[str, Any]]:
    grok_home = Path(os.environ.get("GROK_HOME") or (home / ".grok"))
    sessions_dir = grok_home / "sessions"
    unified_log = grok_home / "logs" / "unified.jsonl"
    if not sessions_dir.is_dir() and not unified_log.is_file():
        return []
    deduped: dict[str, dict[str, Any]] = {}
    usage_json_dirs: set[Path] = set()
    covered_sids: set[str] = set()
    session_dirs_by_sid: dict[str, Path] = {}

    if sessions_dir.is_dir():
        for ws_dir in sorted(sessions_dir.iterdir()):
            if not ws_dir.is_dir():
                continue
            for s_dir in sorted(ws_dir.iterdir()):
                if s_dir.is_dir():
                    session_dirs_by_sid[s_dir.name] = s_dir

        for uj_path in sorted(sessions_dir.rglob("usage.json")):
            try:
                st = uj_path.stat()
            except OSError:
                continue
            usage_json_dirs.add(uj_path.parent)
            covered_sids.add(uj_path.parent.name)
            if st.st_mtime_ns // 1_000_000 < cutoff_ms - 86_400_000:
                continue
            cache_key = f"grok_usage::{uj_path}"
            fp_sig = f"{st.st_mtime_ns}:{st.st_size}"
            cached = cache_files.get(cache_key)
            if isinstance(cached, dict) and cached.get("fp_sig") == fp_sig and isinstance(cached.get("records"), list):
                file_records = cached["records"]
            else:
                file_records = []
                try:
                    data = json.loads(uj_path.read_text("utf-8"))
                except Exception:
                    data = None
                if isinstance(data, dict):
                    session_id = str(data.get("sessionId") or uj_path.parent.name)
                    covered_sids.add(session_id)
                    sess_obj = data.get("session") if isinstance(data.get("session"), dict) else {}
                    default_model = sess_obj.get("primaryModelId") or "grok-4"
                    turns = data.get("turns")
                    if isinstance(turns, list) and turns:
                        for idx, turn in enumerate(turns):
                            if not isinstance(turn, dict):
                                continue
                            ts = _parse_ts_ms(turn.get("endedAt") or data.get("updatedAt"))
                            if ts is None:
                                continue
                            turn_id = str(turn.get("turnNumber") if turn.get("turnNumber") is not None else idx)
                            model_usage = turn.get("modelUsage")
                            if isinstance(model_usage, dict) and model_usage:
                                for raw_m, mu in model_usage.items():
                                    if isinstance(mu, dict):
                                        rec = _grok_usage_record(
                                            mu,
                                            raw_m,
                                            ts,
                                            f"grok:{session_id}:turn:{turn_id}:{_normalize_model_id(raw_m)}",
                                            turn,
                                        )
                                        if rec:
                                            file_records.append(rec)
                            else:
                                raw_m = turn.get("primaryModelId") or default_model
                                rec = _grok_usage_record(
                                    turn,
                                    raw_m,
                                    ts,
                                    f"grok:{session_id}:turn:{turn_id}:{_normalize_model_id(raw_m)}",
                                )
                                if rec:
                                    file_records.append(rec)
                    elif sess_obj:
                        ts = _parse_ts_ms(data.get("updatedAt"))
                        if ts is not None:
                            model_usage = sess_obj.get("modelUsage")
                            if isinstance(model_usage, dict) and model_usage:
                                for raw_m, mu in model_usage.items():
                                    if isinstance(mu, dict):
                                        rec = _grok_usage_record(
                                            mu,
                                            raw_m,
                                            ts,
                                            f"grok:{session_id}:session:{_normalize_model_id(raw_m)}",
                                            sess_obj,
                                        )
                                        if rec:
                                            file_records.append(rec)
                            else:
                                rec = _grok_usage_record(
                                    sess_obj,
                                    default_model,
                                    ts,
                                    f"grok:{session_id}:session:{_normalize_model_id(default_model)}",
                                )
                                if rec:
                                    file_records.append(rec)
                    if len(file_records) > MAX_RECORDS_PER_SOURCE:
                        file_records = file_records[-MAX_RECORDS_PER_SOURCE:]
                    cache_files[cache_key] = {"fp_sig": fp_sig, "records": file_records}

            for rec in file_records:
                if rec["timestampMs"] >= cutoff_ms:
                    deduped[rec["dedupKey"]] = rec
                    sid_part = rec["dedupKey"].split(":", 2)[1] if ":" in rec["dedupKey"] else ""
                    if sid_part:
                        covered_sids.add(sid_part)

        update_files: list[str] = []
        for path in sessions_dir.rglob("updates.jsonl"):
            if path.parent in usage_json_dirs:
                continue
            try:
                if path.stat().st_mtime_ns // 1_000_000 >= cutoff_ms - 86_400_000:
                    update_files.append(str(path))
            except OSError:
                pass

        def parse_line(obj: dict[str, Any], state: dict[str, Any]) -> list[dict[str, Any]] | None:
            params = obj.get("params") if isinstance(obj.get("params"), dict) else obj
            upd = params.get("update")
            if not isinstance(upd, dict) or upd.get("sessionUpdate") != "turn_completed":
                return None
            params_meta = params.get("_meta") if isinstance(params.get("_meta"), dict) else {}
            ts = _parse_ts_ms(params_meta.get("agentTimestampMs") or obj.get("timestamp"))
            if ts is None:
                return None
            session_id = str(params.get("sessionId") or obj.get("sessionId") or "")
            prompt_id = str(upd.get("prompt_id") or upd.get("promptId") or "").strip()
            seq_idx = int(state.get("u_idx") or 0)
            state["u_idx"] = seq_idx + 1
            turn_ident = f"prompt:{prompt_id}" if prompt_id else f"idx:{seq_idx}:{params_meta.get('eventId') or ts}"

            usage_obj = upd.get("usage")
            if isinstance(usage_obj, dict):
                model_usage = usage_obj.get("modelUsage")
                out_recs: list[dict[str, Any]] = []
                if isinstance(model_usage, dict) and model_usage:
                    for raw_m, mu in model_usage.items():
                        if isinstance(mu, dict):
                            rec = _grok_usage_record(
                                mu,
                                raw_m,
                                ts,
                                f"grok:{session_id}:{turn_ident}:{_normalize_model_id(raw_m)}",
                                upd,
                            )
                            if rec:
                                out_recs.append(rec)
                    return out_recs or None
                raw_m = usage_obj.get("primaryModelId") or usage_obj.get("model") or "grok-4"
                rec = _grok_usage_record(
                    usage_obj,
                    raw_m,
                    ts,
                    f"grok:{session_id}:{turn_ident}:{_normalize_model_id(raw_m)}",
                    upd,
                )
                return [rec] if rec else None

            meta = upd.get("_meta")
            if not isinstance(meta, dict) or _grok_has_incomplete_usage(meta):
                return None
            by_model = meta.get("byModel")
            if isinstance(by_model, list) and by_model:
                out_recs = []
                for item in by_model:
                    if not isinstance(item, dict):
                        continue
                    u = item.get("usage") if isinstance(item.get("usage"), dict) else item
                    raw_m = item.get("model")
                    rec = _grok_usage_record(
                        u,
                        raw_m,
                        ts,
                        f"grok:{session_id}:{turn_ident}:{_normalize_model_id(raw_m)}",
                        meta,
                    )
                    if rec:
                        out_recs.append(rec)
                return out_recs or None
            raw_m = meta.get("model") or "grok-4"
            rec = _grok_usage_record(
                meta,
                raw_m,
                ts,
                f"grok:{session_id}:{turn_ident}:{_normalize_model_id(raw_m)}",
                meta,
            )
            return [rec] if rec else None

        for fp in sorted(update_files):
            fp_sid = Path(fp).parent.name
            recs = _read_jsonl_incremental(fp, cache_files, parse_line)
            if recs:
                covered_sids.add(fp_sid)
            for rec in recs:
                sid_part = rec["dedupKey"].split(":", 2)[1] if ":" in rec["dedupKey"] else ""
                if sid_part:
                    covered_sids.add(sid_part)
                if rec["timestampMs"] >= cutoff_ms:
                    prev_rec = deduped.get(rec["dedupKey"])
                    if prev_rec is None or _total_tokens(rec) >= _total_tokens(prev_rec):
                        deduped[rec["dedupKey"]] = rec

    if unified_log.is_file():
        try:
            u_st = unified_log.stat()
        except OSError:
            u_st = None
        if u_st is not None and u_st.st_mtime_ns // 1_000_000 >= cutoff_ms - 86_400_000:
            u_cache_key = f"grok_unified::{unified_log}"
            u_fp_sig = f"{u_st.st_mtime_ns}:{u_st.st_size}"
            u_cached = cache_files.get(u_cache_key)
            if isinstance(u_cached, dict) and u_cached.get("fp_sig") == u_fp_sig and isinstance(u_cached.get("records"), list):
                uni_records = u_cached["records"]
            else:
                uni_records = []
                pid_models: dict[int, str] = {}
                sid_models: dict[str, str] = {}
                try:
                    with unified_log.open("r", encoding="utf-8", errors="replace") as uf:
                        for idx, raw_ln in enumerate(uf):
                            if "inference_done" not in raw_ln and "model" not in raw_ln and "subagent" not in raw_ln:
                                continue
                            try:
                                obj = json.loads(raw_ln)
                            except Exception:
                                continue
                            if not isinstance(obj, dict):
                                continue
                            msg = obj.get("msg")
                            pid = obj.get("pid") if isinstance(obj.get("pid"), int) else None
                            ctx = obj.get("ctx") if isinstance(obj.get("ctx"), dict) else {}
                            if msg in ("subagent spawn credentials", "subagent completed"):
                                sub_id = str(ctx.get("subagent_id") or "").strip()
                                eff_m = str(ctx.get("effective_model") or ctx.get("effective_model_raw") or "").strip()
                                if sub_id and eff_m:
                                    sid_models[sub_id] = _normalize_model_id(eff_m)
                                continue
                            if msg == "subagent read parent config (live)":
                                pm = str(ctx.get("session_model_id") or ctx.get("parent_model") or ctx.get("global_model_id") or "").strip()
                                if pid is not None and pm:
                                    pid_models[pid] = _normalize_model_id(pm)
                                continue
                            if msg != "shell.turn.inference_done":
                                continue
                            sid = str(obj.get("sid") or "").strip()
                            if not sid:
                                continue
                            ts = _parse_ts_ms(obj.get("ts"))
                            if ts is None:
                                continue
                            p_tok = _safe_int(ctx.get("prompt_tokens"))
                            c_read = _safe_int(ctx.get("cached_prompt_tokens"))
                            c_tok = _safe_int(ctx.get("completion_tokens"))
                            r_tok = _safe_int(ctx.get("reasoning_tokens"))
                            if p_tok + c_tok <= 0:
                                continue
                            m_str = (
                                str(ctx.get("model") or "").strip()
                                or sid_models.get(sid)
                                or (pid_models.get(pid) if pid is not None else None)
                                or ""
                            )
                            uni_records.append({
                                "sid": sid,
                                "model": _normalize_model_id(m_str) if m_str else "",
                                "timestampMs": ts,
                                "uncachedInputTokens": max(0, p_tok - c_read),
                                "cachedInputTokens": c_read,
                                "cacheCreationTokens": 0,
                                "outputTokens": max(0, c_tok - r_tok),
                                "reasoningTokens": r_tok,
                                "dedupKey": f"grok-unified:{sid}:{idx}:{ts}",
                            })
                    if len(uni_records) > MAX_RECORDS_PER_SOURCE:
                        uni_records = uni_records[-MAX_RECORDS_PER_SOURCE:]
                    cache_files[u_cache_key] = {"fp_sig": u_fp_sig, "records": uni_records}
                except OSError:
                    uni_records = []

            uni_covered: set[str] = set()
            for item in uni_records:
                sid = str(item.get("sid") or "")
                if not sid or sid in covered_sids:
                    continue
                uni_covered.add(sid)
                if int(item["timestampMs"]) < cutoff_ms:
                    continue
                m_resolved = str(item.get("model") or "")
                if not m_resolved or m_resolved == "unknown":
                    s_dir = session_dirs_by_sid.get(sid)
                    m_resolved = _grok_session_model_from_dir(s_dir) if s_dir is not None else "grok-4.7-build"
                deduped[item["dedupKey"]] = {
                    "cli": "Grok Build",
                    "model": m_resolved,
                    "timestampMs": int(item["timestampMs"]),
                    "uncachedInputTokens": int(item["uncachedInputTokens"]),
                    "cachedInputTokens": int(item["cachedInputTokens"]),
                    "cacheCreationTokens": 0,
                    "outputTokens": int(item["outputTokens"]),
                    "reasoningTokens": int(item["reasoningTokens"]),
                    "reportedCostUsd": None,
                    "dedupKey": item["dedupKey"],
                }
            covered_sids.update(uni_covered)

    for sid, s_dir in session_dirs_by_sid.items():
        if sid in covered_sids:
            continue
        sig_path = s_dir / "signals.json"
        if not sig_path.is_file():
            continue
        try:
            sig_st = sig_path.stat()
            if sig_st.st_mtime_ns // 1_000_000 < cutoff_ms:
                continue
            sig_obj = json.loads(sig_path.read_text("utf-8"))
        except Exception:
            continue
        if not isinstance(sig_obj, dict):
            continue
        eff_total = _safe_int(sig_obj.get("totalTokensBeforeCompaction")) + _safe_int(sig_obj.get("contextTokensUsed"))
        if eff_total <= 0:
            continue
        ts = sig_st.st_mtime_ns // 1_000_000
        m_resolved = _grok_session_model_from_dir(s_dir)
        d_key = f"grok:{sid}:signals"
        deduped[d_key] = {
            "cli": "Grok Build",
            "model": m_resolved,
            "timestampMs": ts,
            "uncachedInputTokens": eff_total,
            "cachedInputTokens": 0,
            "cacheCreationTokens": 0,
            "outputTokens": 0,
            "reasoningTokens": 0,
            "reportedCostUsd": None,
            "dedupKey": d_key,
        }

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

    cur_fp_id = ""

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
        c_read = _safe_int(
            raw_u.get("cache_read_input_tokens")
            or raw_u.get("cacheReadInputTokens")
            or raw_u.get("cacheTokens")
            or raw_u.get("prompt_cache_hit_tokens")
            or raw_u.get("cached_tokens")
            or u.get("cacheReadInputTokens")
            or u.get("cacheTokens")
        )
        c_write = _safe_int(
            raw_u.get("cache_creation_input_tokens")
            or raw_u.get("cacheCreationInputTokens")
            or raw_u.get("cachedWriteTokens")
            or raw_u.get("prompt_cache_write_tokens")
            or u.get("cacheCreationInputTokens")
            or u.get("cachedWriteTokens")
        )
        out_raw = _safe_int(
            raw_u.get("output_tokens")
            or raw_u.get("outputTokens")
            or raw_u.get("completion_tokens")
            or u.get("outputTokens")
            or u.get("output_tokens")
        )
        reason = _safe_int(
            raw_u.get("completion_thinking_tokens")
            or raw_u.get("completionThinkingTokens")
            or raw_u.get("reasoningTokens")
            or u.get("reasoningTokens")
        )
        miss_val = (
            raw_u.get("cachedMissTokens")
            if "cachedMissTokens" in raw_u
            else (
                raw_u.get("cache_miss_tokens")
                if "cache_miss_tokens" in raw_u
                else (u.get("cachedMissTokens") if "cachedMissTokens" in u else u.get("cache_miss_tokens"))
            )
        )
        if miss_val is not None:
            uncached = _safe_int(miss_val)
        elif ("input_tokens" in raw_u or "inputTokens" in raw_u or "inputTokens" in u or "input_tokens" in u):
            inp_raw = _safe_int(
                raw_u.get("input_tokens")
                or raw_u.get("inputTokens")
                or u.get("inputTokens")
                or u.get("input_tokens")
            )
            tot_raw = (
                raw_u.get("total_tokens")
                if "total_tokens" in raw_u
                else (raw_u.get("totalTokens") if "totalTokens" in raw_u else (u.get("totalTokens") if "totalTokens" in u else u.get("total_tokens")))
            )
            if tot_raw is not None:
                tot = _safe_int(tot_raw)
                incl_tot = inp_raw + out_raw
                excl_tot = incl_tot + c_read + c_write + max(0, reason)
                uncached = max(0, inp_raw - c_read) if (c_read > 0 and tot == incl_tot and incl_tot != excl_tot) else inp_raw
            else:
                uncached = inp_raw
        else:
            prompt_in = _safe_int(raw_u.get("prompt_tokens") or u.get("prompt_tokens"))
            uncached = max(0, prompt_in - c_read - c_write)
        out_tok = max(0, out_raw - reason) if ("completion_tokens" in raw_u and "output_tokens" not in raw_u and "outputTokens" not in raw_u) else out_raw
        if uncached + c_read + c_write + out_tok + reason <= 0:
            return None
        model = _normalize_model_id(pd.get("model") or pd.get("requestModelId") or "unknown")
        raw_mid = pd.get("messageId") or pd.get("conversationRequestId") or obj.get("id")
        if isinstance(raw_mid, str) and raw_mid.strip():
            msg_id = raw_mid.strip()
        elif isinstance(raw_mid, (int, float)) and not isinstance(raw_mid, bool):
            msg_id = str(raw_mid)
        else:
            msg_id = f"{cur_fp_id}:{_safe_int(_state.get('_line_offset'))}"
        return {
            "cli": "CodeBuddy Code",
            "model": model,
            "timestampMs": ts,
            "uncachedInputTokens": uncached,
            "cachedInputTokens": c_read,
            "cacheCreationTokens": c_write,
            "outputTokens": out_tok,
            "reasoningTokens": reason,
            "reportedCostUsd": None,
            "dedupKey": f"codebuddy:{msg_id}",
        }

    deduped: dict[str, dict[str, Any]] = {}
    for fp in sorted(files):
        cur_fp_id = _fnv1a64(fp.encode("utf-8"))[:12]
        for rec in _read_jsonl_incremental(fp, cache_files, parse_line):
            if rec["timestampMs"] >= cutoff_ms:
                deduped[rec["dedupKey"]] = rec
    return list(deduped.values())


def _iter_dsh_json_objects(path: Path, on_obj: Any) -> bool:
    try:
        with path.open("rb") as fh:
            magic = fh.read(4)
    except OSError:
        return False

    def _consume_lines(line_iter: Any) -> None:
        for idx, raw_line in enumerate(line_iter):
            if idx == 0 and raw_line.startswith("\ufeff"):
                raw_line = raw_line[1:]
            line = raw_line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except Exception:
                continue
            if isinstance(obj, dict) and isinstance(obj.get("type"), str):
                on_obj(obj)

    if magic == b"\x28\xb5\x2f\xfd":
        try:
            from compression import zstd
            with zstd.ZstdFile(path, "rb") as zf:
                with io.TextIOWrapper(zf, encoding="utf-8", errors="replace") as wrapper:
                    _consume_lines(wrapper)
            return True
        except ImportError:
            proc: subprocess.Popen[bytes] | None = None
            wrapper = None
            try:
                proc = subprocess.Popen(
                    ["zstd", "-dc", "--", str(path)],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.DEVNULL,
                )
                if proc.stdout is None:
                    return False
                wrapper = io.TextIOWrapper(proc.stdout, encoding="utf-8", errors="replace")
                deadline = time.monotonic() + 15.0
                _consume_lines(wrapper)
                rem = max(0.1, deadline - time.monotonic())
                rc = proc.wait(timeout=rem)
                return rc == 0
            except Exception:
                return False
            finally:
                if wrapper is not None:
                    try:
                        wrapper.close()
                    except Exception:
                        pass
                if proc is not None:
                    if proc.stdout is not None:
                        try:
                            proc.stdout.close()
                        except Exception:
                            pass
                    if proc.poll() is None:
                        try:
                            proc.kill()
                        except Exception:
                            pass
                    try:
                        proc.wait(timeout=1.0)
                    except Exception:
                        pass
        except Exception:
            return False
    try:
        with path.open("r", encoding="utf-8", errors="replace") as fh:
            _consume_lines(fh)
        return True
    except OSError:
        return False


def _extract_dsh_event_usage(data: dict[str, Any], event_type: str) -> dict[str, Any] | None:
    if event_type in ("assistant/message", "compaction/summary") and isinstance(data.get("usage"), dict):
        return data["usage"]
    if event_type not in ("assistant/message", "assistant/attempt"):
        return None
    stream = data.get("stream")
    if not isinstance(stream, list):
        return None
    for item in reversed(stream):
        if not isinstance(item, dict):
            continue
        chunk = item.get("chunk") if isinstance(item.get("chunk"), dict) else item
        if chunk.get("type") == "usage" and isinstance(chunk.get("usage"), dict):
            return chunk["usage"]
    return None


def _dsh_fork_boundary_stream(path: Path) -> tuple[bool, int, bool]:
    st_info: dict[str, Any] = {
        "session_seen": False,
        "seeded_v3": False,
        "seed_cut": 0,
        "seed_fixed": False,
        "last_tagged_cut": None,
    }

    def _on_meta(obj: dict[str, Any]) -> None:
        ev_type = obj.get("type")
        if ev_type == "session/end-seed":
            d = obj.get("data")
            if isinstance(d, dict) and d.get("inherited") is True and isinstance(obj.get("seq"), int):
                st_info["last_tagged_cut"] = obj["seq"]
        elif ev_type == "session" and not st_info["session_seen"]:
            st_info["session_seen"] = True
            if "seedLength" in obj and obj.get("seedLength") is not None:
                st_info["seed_cut"] = max(0, _safe_int(obj.get("seedLength")))
                st_info["seed_fixed"] = True
            elif obj.get("isSeeded") is True:
                st_info["seeded_v3"] = True

    if not _iter_dsh_json_objects(path, _on_meta):
        return False, 0, False
    if st_info["seed_fixed"]:
        return True, st_info["seed_cut"], False
    if st_info["seeded_v3"]:
        if st_info["last_tagged_cut"] is None:
            return True, 0, True
        return True, max(0, st_info["last_tagged_cut"]), False
    return True, 0, False


def _scan_deepseek(home: Path, cache_files: dict[str, Any], cutoff_ms: int) -> list[dict[str, Any]]:
    dsh_home = Path(os.environ.get("DSH_HOME") or (home / ".dsh"))
    sessions_dir = dsh_home / "sessions"
    if not sessions_dir.is_dir():
        return []
    deduped: dict[str, dict[str, Any]] = {}
    candidates: list[Path] = []
    for path in sessions_dir.rglob("session*.jsonl*"):
        name = path.name
        if name.endswith((".lock", ".tmp")) or not name.endswith((".jsonl", ".jsonl.zst", ".jsonl.zstd")):
            continue
        try:
            st = path.stat()
        except OSError:
            continue
        if st.st_mtime_ns // 1_000_000 >= cutoff_ms - 86_400_000:
            candidates.append(path)

    for path in sorted(candidates):
        try:
            st = path.stat()
        except OSError:
            continue
        cache_key = f"dsh::{path}"
        fp_sig = f"{st.st_mtime_ns}:{st.st_size}"
        cached = cache_files.get(cache_key)
        if isinstance(cached, dict) and cached.get("fp_sig") == fp_sig and isinstance(cached.get("records"), list):
            file_records = cached["records"]
        else:
            ok_boundary, seed_cut, missing_tagged_cut = _dsh_fork_boundary_stream(path)
            if not ok_boundary or missing_tagged_cut:
                continue

            dsh_state: dict[str, Any] = {
                "session_id": path.parent.name or "unknown",
                "session_header_seen": False,
                "fallback_provider": "",
                "fallback_model": "",
                "last_settlement": None,
            }
            step_started: dict[tuple[int, int], int] = {}
            seen_local: set[str] = set()
            file_records = []

            def _on_dsh_obj(obj: dict[str, Any]) -> None:
                ev_type = obj["type"]
                data = obj.get("data") if isinstance(obj.get("data"), dict) else {}

                if ev_type == "session":
                    dsh_state["session_header_seen"] = True
                    sid = str(obj.get("id") or "").strip()
                    if sid:
                        dsh_state["session_id"] = sid
                elif ev_type == "request/header":
                    hdr = data.get("header") if isinstance(data.get("header"), dict) else {}
                    cfg = hdr.get("config") if isinstance(hdr.get("config"), dict) else {}
                    if isinstance(cfg.get("provider"), str) and cfg["provider"].strip():
                        dsh_state["fallback_provider"] = cfg["provider"].strip()
                    if isinstance(cfg.get("model"), str) and cfg["model"].strip():
                        dsh_state["fallback_model"] = cfg["model"].strip()
                elif ev_type == "request/context":
                    if isinstance(data.get("provider"), str) and data["provider"].strip():
                        dsh_state["fallback_provider"] = data["provider"].strip()
                    if isinstance(data.get("model"), str) and data["model"].strip():
                        dsh_state["fallback_model"] = data["model"].strip()
                elif ev_type == "step/start":
                    turn_v = data.get("turn")
                    step_v = data.get("step")
                    if isinstance(turn_v, int) and isinstance(step_v, int):
                        ts_start = _parse_ts_ms(obj.get("time"))
                        if ts_start is not None:
                            step_started[(turn_v, step_v)] = ts_start
                        else:
                            step_started.pop((turn_v, step_v), None)
                elif ev_type == "llm/retry-started":
                    turn_v = data.get("turn")
                    step_v = data.get("step")
                    if isinstance(turn_v, int) and isinstance(step_v, int):
                        ts_retry = _parse_ts_ms(obj.get("time"))
                        if ts_retry is not None:
                            step_started[(turn_v, step_v)] = ts_retry
                        else:
                            step_started.pop((turn_v, step_v), None)
                        ls = dsh_state["last_settlement"]
                        if ls is not None and ls[0] == turn_v and ls[1] == step_v:
                            dsh_state["last_settlement"] = None
                elif ev_type in ("assistant/message", "assistant/attempt", "compaction/summary"):
                    seq_val = obj.get("seq")
                    if dsh_state["session_header_seen"] and seed_cut > 0 and isinstance(seq_val, int) and seq_val < seed_cut:
                        return
                    usage = _extract_dsh_event_usage(data, ev_type)
                    if not isinstance(usage, dict):
                        return
                    uncached = _safe_int(usage.get("inputTokens"))
                    c_read = _safe_int(usage.get("cacheReadTokens"))
                    c_write = _safe_int(usage.get("cacheWriteTokens"))
                    out_raw = _safe_int(usage.get("outputTokens"))
                    reason = _safe_int(usage.get("reasoningTokens"))
                    out_tok = max(0, out_raw - reason)
                    if uncached + c_read + c_write + out_tok + reason <= 0:
                        return
                    raw_ts = _parse_ts_ms(obj.get("time"))
                    if raw_ts is None:
                        return

                    is_summary = ev_type == "compaction/summary"
                    is_attempt = ev_type == "assistant/attempt"
                    msg_obj = data.get("message") if isinstance(data.get("message"), dict) else {}
                    source = msg_obj.get("source") if isinstance(msg_obj.get("source"), dict) else {}
                    prov_id = str(source.get("provider") or dsh_state["fallback_provider"] or "deepseek").strip()
                    if prov_id.lower().startswith("migrated:"):
                        return

                    replay = source.get("replayState") if isinstance(source.get("replayState"), dict) else {}
                    resp_meta = replay.get("response") if isinstance(replay.get("response"), dict) else {}
                    resp_model_raw = str(resp_meta.get("responseModel") or "").strip()
                    if resp_model_raw.lower() == "keepalive":
                        resp_model_raw = ""
                    raw_model = (
                        resp_model_raw
                        or (source.get("model") if isinstance(source.get("model"), str) and source["model"].strip() else None)
                        or dsh_state["fallback_model"]
                        or "deepseek-chat"
                    )
                    model = _normalize_model_id(raw_model)

                    turn_v = data.get("turn")
                    step_v = data.get("step")
                    slot = (turn_v, step_v) if isinstance(turn_v, int) and isinstance(step_v, int) else None
                    anchor_ts = step_started.get(slot) if (not is_summary and slot is not None) else None
                    rec_ts = anchor_ts if (anchor_ts is not None and 0 < anchor_ts <= raw_ts) else raw_ts

                    cur_sid = dsh_state["session_id"]
                    if is_attempt:
                        att_id = str(data.get("attemptId") or data.get("retryId") or "").strip()
                        if att_id:
                            ident = f"attempt-id:{att_id}"
                        elif isinstance(seq_val, int):
                            ident = f"attempt-session:{cur_sid}:seq:{seq_val}"
                        elif slot is not None:
                            ident = f"attempt-session:{cur_sid}:turn:{slot[0]}:step:{slot[1]}:{raw_ts}"
                        else:
                            ident = f"attempt-session:{cur_sid}:{raw_ts}"
                    else:
                        msg_id = str(msg_obj.get("id") or "").strip()
                        cmp_id = str(data.get("compactionId") or "").strip() if is_summary else ""
                        if msg_id:
                            ident = f"msg:{msg_id}"
                        elif cmp_id:
                            ident = f"cmp:{cmp_id}"
                        elif isinstance(seq_val, int):
                            ident = f"seq:{seq_val}"
                        else:
                            ident = f"sid:{cur_sid}"

                    kind = "summary:" if is_summary else ("attempt:" if is_attempt else "")
                    dedup_key = f"dsh:{kind}{ident}:{raw_ts}:{prov_id}:{model}:{uncached}:{out_tok}:{c_read}:{c_write}:{reason}"
                    if dedup_key in seen_local:
                        return
                    seen_local.add(dedup_key)

                    rec = {
                        "cli": "DeepSeek Harness",
                        "model": model,
                        "timestampMs": rec_ts,
                        "uncachedInputTokens": uncached,
                        "cachedInputTokens": c_read,
                        "cacheCreationTokens": c_write,
                        "outputTokens": out_tok,
                        "reasoningTokens": reason,
                        "reportedCostUsd": None,
                        "dedupKey": dedup_key,
                    }
                    if is_summary:
                        file_records.append(rec)
                        return
                    if slot is not None:
                        ls = dsh_state["last_settlement"]
                        if ls is not None and ls[0] == slot[0] and ls[1] == slot[1]:
                            file_records[ls[2]] = rec
                            return
                        idx = len(file_records)
                        file_records.append(rec)
                        dsh_state["last_settlement"] = (slot[0], slot[1], idx)
                    else:
                        file_records.append(rec)
                        dsh_state["last_settlement"] = None

            if not _iter_dsh_json_objects(path, _on_dsh_obj):
                continue

            if len(file_records) > MAX_RECORDS_PER_SOURCE:
                file_records = file_records[-MAX_RECORDS_PER_SOURCE:]
            cache_files[cache_key] = {"fp_sig": fp_sig, "records": file_records}

        for rec in file_records:
            if rec["timestampMs"] >= cutoff_ms:
                deduped[rec["dedupKey"]] = rec

    return list(deduped.values())


def _read_kimi_cli_default_model(kimi_root: Path) -> str:
    toml_path = kimi_root / "config.toml"
    if toml_path.is_file():
        try:
            import tomllib
            cfg = tomllib.loads(toml_path.read_text("utf-8"))
            if isinstance(cfg, dict):
                def_key = str(cfg.get("default_model") or "").strip()
                models_map = cfg.get("models") if isinstance(cfg.get("models"), dict) else {}
                if def_key and isinstance(models_map.get(def_key), dict):
                    m_name = str(models_map[def_key].get("model") or "").strip()
                    if m_name:
                        return _normalize_model_id(m_name)
                if def_key:
                    return _normalize_model_id(def_key)
        except Exception:
            pass
    json_path = kimi_root / "config.json"
    if json_path.is_file():
        try:
            cfg = json.loads(json_path.read_text("utf-8"))
            if isinstance(cfg, dict):
                m_name = str(cfg.get("model") or cfg.get("default_model") or "").strip()
                if m_name:
                    return _normalize_model_id(m_name)
        except Exception:
            pass
    return "kimi-for-coding"


def _concrete_kimi_code_model(raw: Any) -> str | None:
    if not isinstance(raw, str):
        return None
    s = raw.strip()
    if s.lower().startswith("kimi-code/"):
        s = s[len("kimi-code/"):].strip()
    if len(s) >= 4 and s.startswith("__") and s.endswith("__"):
        return None
    if not s or s.lower() in ("default", "auto", "config", "unknown"):
        return None
    return _normalize_model_id(s)


def _scan_kimi(home: Path, cache_files: dict[str, Any], cutoff_ms: int) -> list[dict[str, Any]]:
    roots: list[Path] = []
    for raw_root in (
        os.environ.get("KIMI_SHARE_DIR"),
        str(home / ".kimi"),
        os.environ.get("KIMI_CODE_HOME"),
        str(home / ".kimi-code"),
    ):
        if not raw_root:
            continue
        p = Path(raw_root).expanduser()
        if p not in roots and (p / "sessions").is_dir():
            roots.append(p)
    if not roots:
        return []

    deduped: dict[str, dict[str, Any]] = {}
    default_models: dict[Path, str] = {}

    for root in roots:
        sessions_dir = root / "sessions"
        for wire_path in sorted(sessions_dir.rglob("wire.jsonl")):
            try:
                st = wire_path.stat()
            except OSError:
                continue
            fallback_ts = st.st_mtime_ns // 1_000_000
            if fallback_ts < cutoff_ms - 86_400_000:
                continue
            is_kimi_code = wire_path.parent.parent.name == "agents"
            if not is_kimi_code:
                root_default_model = default_models.get(root)
                if root_default_model is None:
                    root_default_model = _read_kimi_cli_default_model(root)
                    default_models[root] = root_default_model
                fp_sig = f"{st.st_mtime_ns}:{st.st_size}:{root_default_model}"
            else:
                root_default_model = "kimi-for-coding"
                fp_sig = f"{st.st_mtime_ns}:{st.st_size}"

            cache_key = f"kimi::{wire_path}"
            cached = cache_files.get(cache_key)
            if isinstance(cached, dict) and cached.get("fp_sig") == fp_sig and isinstance(cached.get("records"), list):
                file_records = cached["records"]
            else:
                file_records = []
                try:
                    with wire_path.open("r", encoding="utf-8", errors="replace") as fh:
                        if is_kimi_code:
                            session_id = wire_path.parent.parent.parent.name or "unknown"
                            agent_id = wire_path.parent.name or "main"
                            latest_req_model: str | None = None
                            pending_req_ms: int | None = None
                            for idx, raw_ln in enumerate(fh):
                                if idx == 0 and raw_ln.startswith("\ufeff"):
                                    raw_ln = raw_ln[1:]
                                ln = raw_ln.strip()
                                if not ln:
                                    continue
                                try:
                                    obj = json.loads(ln)
                                except Exception:
                                    continue
                                if not isinstance(obj, dict):
                                    continue
                                ltype = obj.get("type")
                                if ltype == "llm.request":
                                    cm = _concrete_kimi_code_model(obj.get("model"))
                                    if cm:
                                        latest_req_model = cm
                                    pending_req_ms = _parse_ts_ms(obj.get("time"))
                                    continue
                                if ltype != "usage.record" or obj.get("usageScope") != "turn":
                                    continue
                                req_ms = pending_req_ms
                                pending_req_ms = None
                                u = obj.get("usage")
                                if not isinstance(u, dict):
                                    continue
                                inp = _safe_int(u.get("inputOther") if "inputOther" in u else u.get("input_other"))
                                out_tok = _safe_int(u.get("output"))
                                c_read = _safe_int(u.get("inputCacheRead") if "inputCacheRead" in u else u.get("input_cache_read"))
                                c_write = _safe_int(u.get("inputCacheCreation") if "inputCacheCreation" in u else u.get("input_cache_creation"))
                                if inp + out_tok + c_read + c_write <= 0:
                                    continue
                                model = _concrete_kimi_code_model(obj.get("model")) or latest_req_model or "kimi-for-coding"
                                wire_ms = _parse_ts_ms(obj.get("time"))
                                ts = req_ms if (req_ms is not None and wire_ms is not None and wire_ms > req_ms) else (wire_ms or fallback_ts)
                                file_records.append({
                                    "cli": "Kimi Code",
                                    "model": model,
                                    "timestampMs": ts,
                                    "uncachedInputTokens": inp,
                                    "cachedInputTokens": c_read,
                                    "cacheCreationTokens": c_write,
                                    "outputTokens": out_tok,
                                    "reasoningTokens": 0,
                                    "reportedCostUsd": None,
                                    "dedupKey": f"kimi-code:{session_id}:{agent_id}:{idx}:{ts}",
                                })
                        else:
                            session_id = wire_path.parent.name or "unknown"
                            by_msg_id: dict[str, tuple[int, bool]] = {}
                            for idx, raw_ln in enumerate(fh):
                                if idx == 0 and raw_ln.startswith("\ufeff"):
                                    raw_ln = raw_ln[1:]
                                ln = raw_ln.strip()
                                if not ln:
                                    continue
                                try:
                                    obj = json.loads(ln)
                                except Exception:
                                    continue
                                if not isinstance(obj, dict) or obj.get("type") == "metadata":
                                    continue
                                msg = obj.get("message")
                                if not isinstance(msg, dict) or msg.get("type") != "StatusUpdate":
                                    continue
                                payload = msg.get("payload")
                                if not isinstance(payload, dict):
                                    continue
                                tu = payload.get("token_usage")
                                if not isinstance(tu, dict):
                                    continue
                                inp = _safe_int(tu.get("input_other") if "input_other" in tu else tu.get("inputOther"))
                                out_tok = _safe_int(tu.get("output"))
                                c_read = _safe_int(tu.get("input_cache_read") if "input_cache_read" in tu else tu.get("inputCacheRead"))
                                c_write = _safe_int(tu.get("input_cache_creation") if "input_cache_creation" in tu else tu.get("inputCacheCreation"))
                                tot_cand = inp + out_tok + c_read + c_write
                                if tot_cand <= 0:
                                    continue
                                ts_parsed = _parse_ts_ms(obj.get("timestamp"))
                                has_wire_ts = ts_parsed is not None
                                ts = ts_parsed if ts_parsed is not None else fallback_ts
                                raw_mid = str(payload.get("message_id") or "").strip()
                                dedup_key = f"kimi:{session_id}:{raw_mid or f'line-{idx}'}"
                                rec = {
                                    "cli": "Kimi Code",
                                    "model": root_default_model,
                                    "timestampMs": ts,
                                    "uncachedInputTokens": inp,
                                    "cachedInputTokens": c_read,
                                    "cacheCreationTokens": c_write,
                                    "outputTokens": out_tok,
                                    "reasoningTokens": 0,
                                    "reportedCostUsd": None,
                                    "dedupKey": dedup_key,
                                }
                                if raw_mid and raw_mid in by_msg_id:
                                    prev_idx, prev_wire = by_msg_id[raw_mid]
                                    prev_rec = file_records[prev_idx]
                                    prev_tot = _total_tokens(prev_rec)
                                    replace = (
                                        tot_cand > prev_tot
                                        or (
                                            tot_cand == prev_tot
                                            and (
                                                (has_wire_ts and not prev_wire)
                                                or (has_wire_ts == prev_wire and ts >= int(prev_rec["timestampMs"]))
                                            )
                                        )
                                    )
                                    if replace:
                                        file_records[prev_idx] = rec
                                        by_msg_id[raw_mid] = (prev_idx, has_wire_ts)
                                else:
                                    if raw_mid:
                                        by_msg_id[raw_mid] = (len(file_records), has_wire_ts)
                                    file_records.append(rec)
                except OSError:
                    continue

                if len(file_records) > MAX_RECORDS_PER_SOURCE:
                    file_records = file_records[-MAX_RECORDS_PER_SOURCE:]
                cache_files[cache_key] = {"fp_sig": fp_sig, "records": file_records}

            for rec in file_records:
                if rec["timestampMs"] >= cutoff_ms:
                    deduped[rec["dedupKey"]] = rec

    return list(deduped.values())


def _extract_qoder_usage_buckets(usage: dict[str, Any]) -> tuple[int, int, int, int, int] | None:
    if "promptTokenCount" in usage or "candidatesTokenCount" in usage:
        prompt_tok = _safe_int(usage.get("promptTokenCount"))
        c_read = _safe_int(usage.get("cachedContentTokenCount"))
        c_write = 0
        uncached = max(0, prompt_tok - c_read)
        cand_tok = _safe_int(usage.get("candidatesTokenCount"))
        reason = _safe_int(usage.get("thoughtsTokenCount"))
        tot = _safe_int(usage.get("totalTokenCount"))
        out_tok = max(0, cand_tok - reason) if (tot > 0 and tot == prompt_tok + cand_tok) else cand_tok
    else:
        inp_raw = _safe_int(
            usage.get("input_tokens")
            or usage.get("inputTokens")
            or usage.get("prompt_tokens")
            or usage.get("promptTokens")
        )
        c_read = _safe_int(
            usage.get("cache_read_input_tokens")
            or usage.get("cacheReadInputTokens")
            or usage.get("cache_read_tokens")
            or usage.get("cacheReadTokens")
        )
        c_write_base = _safe_int(
            usage.get("cache_creation_input_tokens")
            or usage.get("cacheCreationInputTokens")
            or usage.get("cache_write_tokens")
            or usage.get("cacheWriteTokens")
        )
        cc_sub = usage.get("cache_creation") if isinstance(usage.get("cache_creation"), dict) else {}
        c_write_1h = _safe_int(cc_sub.get("ephemeral_1h_input_tokens") or usage.get("ephemeral_1h_input_tokens"))
        c_write = max(c_write_base, c_write_1h)
        has_split = any(
            k in usage
            for k in (
                "cache_read_input_tokens",
                "cacheReadInputTokens",
                "cache_creation_input_tokens",
                "cacheCreationInputTokens",
            )
        )
        uncached = inp_raw if has_split else max(0, inp_raw - c_read - c_write)
        out_raw = _safe_int(
            usage.get("output_tokens")
            or usage.get("outputTokens")
            or usage.get("completion_tokens")
            or usage.get("completionTokens")
        )
        reason = _safe_int(
            usage.get("thinking_tokens")
            or usage.get("thinkingTokens")
            or usage.get("reasoning_tokens")
            or usage.get("reasoningTokens")
        )
        out_tok = max(0, out_raw - reason)
    if uncached + c_read + c_write + out_tok + reason <= 0:
        return None
    return uncached, c_read, c_write, out_tok, reason


def _scan_qoder(home: Path, cache_files: dict[str, Any], cutoff_ms: int) -> list[dict[str, Any]]:
    base_roots: list[Path] = []
    for raw_root in (
        os.environ.get("QODER_CONFIG_DIR"),
        os.environ.get("QODER_HOME"),
        str(home / ".qoder"),
        os.environ.get("QODERCN_CONFIG_DIR"),
        str(home / ".qoder-cn"),
    ):
        if not raw_root:
            continue
        base_p = Path(raw_root).expanduser()
        if base_p not in base_roots and base_p.is_dir():
            base_roots.append(base_p)
    if not base_roots:
        return []

    project_sids: set[str] = set()
    files: list[str] = []
    for base_p in base_roots:
        projects_dir = base_p / "projects"
        if projects_dir.is_dir():
            for path in projects_dir.rglob("*.jsonl"):
                name = path.name
                if name.endswith(".checkpoints.jsonl"):
                    continue
                if name == "journal.jsonl" and any(p.name == "subagents" for p in path.parents):
                    continue
                project_sids.add(path.stem)
                try:
                    if path.stat().st_mtime_ns // 1_000_000 >= cutoff_ms - 86_400_000:
                        files.append(str(path))
                except OSError:
                    pass
        logs_sess_dir = base_p / "logs" / "sessions"
        if logs_sess_dir.is_dir():
            for sid_dir in logs_sess_dir.iterdir():
                if not sid_dir.is_dir() or sid_dir.name in project_sids:
                    continue
                seg_dir = sid_dir / "segments"
                target_dir = seg_dir if seg_dir.is_dir() else sid_dir
                for path in target_dir.rglob("*.jsonl"):
                    try:
                        if path.stat().st_mtime_ns // 1_000_000 >= cutoff_ms - 86_400_000:
                            files.append(str(path))
                    except OSError:
                        pass

    cur_fp_id = ""

    def parse_line(obj: dict[str, Any], state: dict[str, Any]) -> dict[str, Any] | None:
        etype = str(obj.get("type") or obj.get("role") or "")
        if etype == "runtime-config":
            rm = str(obj.get("model") or "").strip()
            if rm and rm.lower() not in ("<synthetic>", "auto"):
                state["active_model"] = rm
            return None
        if etype == "user":
            msg_u = obj.get("message") if isinstance(obj.get("message"), dict) else {}
            bmk = str(obj.get("businessModelKey") or msg_u.get("businessModelKey") or "").strip()
            if bmk and bmk.lower() not in ("<synthetic>", "auto"):
                state["active_model"] = bmk
            return None
        if etype == "model.response.completed":
            data = obj.get("data") if isinstance(obj.get("data"), dict) else {}
            u_src = data.get("usage") if isinstance(data.get("usage"), dict) else (data or (obj.get("usage") if isinstance(obj.get("usage"), dict) else obj))
            if not isinstance(u_src, dict):
                return None
            buckets = _extract_qoder_usage_buckets(u_src)
            if buckets is None:
                return None
            cand_m = str(data.get("model") or obj.get("model") or "").strip()
            raw_model = cand_m if (cand_m and cand_m.lower() != "auto") else str(state.get("active_model") or cand_m or "auto")
            if not raw_model or raw_model.lower() == "<synthetic>":
                return None
            ts = _parse_ts_ms(obj.get("ts") or obj.get("timestamp") or data.get("timestamp"))
            if ts is None:
                return None
            uncached, c_read, c_write, out_tok, reason = buckets
            model = _normalize_model_id(raw_model)
            raw_rid = obj.get("request_id") or data.get("request_id")
            if isinstance(raw_rid, str) and raw_rid.strip():
                req_id = raw_rid.strip()
            elif isinstance(raw_rid, (int, float)) and not isinstance(raw_rid, bool):
                req_id = str(raw_rid)
            else:
                req_id = f"{cur_fp_id}:{_safe_int(state.get('_line_offset'))}:{ts}:{model}"
            return {
                "cli": "Qoder CLI",
                "model": model,
                "timestampMs": ts,
                "uncachedInputTokens": uncached,
                "cachedInputTokens": c_read,
                "cacheCreationTokens": c_write,
                "outputTokens": out_tok,
                "reasoningTokens": reason,
                "reportedCostUsd": None,
                "dedupKey": f"qoder:{req_id}",
            }
        if etype != "assistant":
            return None
        msg = obj.get("message") if isinstance(obj.get("message"), dict) else {}
        cand_m = str(msg.get("model") or obj.get("model") or "").strip()
        raw_model = cand_m if (cand_m and cand_m.lower() != "auto") else str(state.get("active_model") or cand_m or "")
        if not raw_model or raw_model.lower() == "<synthetic>":
            return None
        usage = msg.get("usage") if isinstance(msg.get("usage"), dict) else (obj.get("usage") if isinstance(obj.get("usage"), dict) else None)
        if not isinstance(usage, dict):
            return None
        ts = _parse_ts_ms(obj.get("timestamp") or msg.get("timestamp"))
        if ts is None:
            return None
        buckets = _extract_qoder_usage_buckets(usage)
        if buckets is None:
            return None
        uncached, c_read, c_write, out_tok, reason = buckets
        model = _normalize_model_id(raw_model)
        raw_mid = msg.get("id") or obj.get("requestId") or obj.get("uuid")
        if isinstance(raw_mid, str) and raw_mid.strip():
            msg_id = raw_mid.strip()
        elif isinstance(raw_mid, (int, float)) and not isinstance(raw_mid, bool):
            msg_id = str(raw_mid)
        else:
            msg_id = f"{cur_fp_id}:{_safe_int(state.get('_line_offset'))}:{ts}:{model}"
        return {
            "cli": "Qoder CLI",
            "model": model,
            "timestampMs": ts,
            "uncachedInputTokens": uncached,
            "cachedInputTokens": c_read,
            "cacheCreationTokens": c_write,
            "outputTokens": out_tok,
            "reasoningTokens": reason,
            "reportedCostUsd": _safe_float(obj.get("costUSD")),
            "dedupKey": f"qoder:{msg_id}",
        }

    deduped: dict[str, dict[str, Any]] = {}
    for fp in sorted(files):
        cur_fp_id = _fnv1a64(fp.encode("utf-8"))[:12]
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
            yield field_no, 1, buf[pos : pos + 8]
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
            yield field_no, 5, buf[pos : pos + 4]
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
        low_compact = " ".join(raw.lower().split())
        if low_compact == "orchestrator (carbon)":
            return "gemini-pro-orchestrator"
        if low_compact == "orchestrator (argon)":
            return "gemini-pro-orchestrator-argon"
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


def _extract_agy_db_session_checked(
    db_path: Path,
    fallback_ts: int = 0,
) -> tuple[list[dict[str, Any]], int, int, str, bool]:
    raw_candidates: list[dict[str, Any]] = []
    est_used = 0
    max_ctx = 0
    latest_model = ""
    scan_ok = False
    try:
        with _open_readonly_sqlite(db_path, timeout=2.0) as con:
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
                        uncached = _proto_num(usage, 2)
                        c_read = _proto_num(usage, 5)
                        c_write = _proto_num(usage, 4)
                        u9 = _proto_num(usage, 9)
                        u10 = _proto_num(usage, 10)
                        u3 = _proto_num(usage, 3)
                        if u9 > 0 or u10 > 0:
                            reason_out = u9
                            text_out = u10
                        else:
                            reason_out = 0
                            text_out = u3
                        if uncached + c_read + c_write + text_out + reason_out <= 0:
                            continue
                        u_enum_id = _proto_num(usage, 1)
                        u_sub_model = AGY_MODEL_ENUM.get(u_enum_id, "")
                        usage_model = resolved_model or "antigravity-unknown"
                        usage_prio = m_prio
                        if (
                            not resolved_model
                            or resolved_model in ("antigravity-unknown", "orchestrator")
                            or resolved_model.startswith("gemini-pro-orchestrator")
                        ) and u_sub_model and not u_sub_model.startswith("gemini-pro-orchestrator"):
                            usage_model = u_sub_model
                            usage_prio = max(m_prio, 2)
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
                            "model": usage_model,
                            "uiLabel": ui_lbl or None,
                            "modelPriority": usage_prio,
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
                        uncached = _proto_num(usage, 2)
                        c_read = _proto_num(usage, 5)
                        c_write = _proto_num(usage, 4)
                        u9 = _proto_num(usage, 9)
                        u10 = _proto_num(usage, 10)
                        u3 = _proto_num(usage, 3)
                        text_out = u10 if (u9 > 0 or u10 > 0) else u3
                        reason_out = u9 if (u9 > 0 or u10 > 0) else 0
                        if uncached + c_read + c_write + text_out + reason_out <= 0:
                            continue
                        keys = [f"agy:{k}:{_proto_text(usage, k)}" for k in (11, 12, 7) if _proto_text(usage, k)]
                        model_id = _normalize_model_id(entry_model or "antigravity-unknown")
                        u_enum_id = _proto_num(usage, 1)
                        u_sub_model = AGY_MODEL_ENUM.get(u_enum_id, "")
                        if (
                            model_id in ("unknown", "antigravity-unknown", "orchestrator")
                            or model_id.startswith("gemini-pro-orchestrator")
                        ) and u_sub_model and not u_sub_model.startswith("gemini-pro-orchestrator"):
                            model_id = u_sub_model
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
        scan_ok = True
    except Exception:
        scan_ok = False

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
    return db_records, est_used, max_ctx, latest_model, scan_ok


def _extract_agy_db_session(db_path: Path, fallback_ts: int = 0) -> tuple[list[dict[str, Any]], int, int, str]:
    db_records, est_used, max_ctx, latest_model, _ = _extract_agy_db_session_checked(db_path, fallback_ts)
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
        db_stat = _safe_stat_sig(db_path)
        sig_body = _db_fingerprint_sig(db_path)
        if db_stat is None or sig_body is None:
            continue
        fallback_ts = db_stat[1] // 1_000_000
        fp_sig = f"v4:{sig_body}"

        cached_entry = cache_files.get(db_str)
        if isinstance(cached_entry, dict) and cached_entry.get("fp_sig") == fp_sig and isinstance(cached_entry.get("records"), list):
            db_records = cached_entry["records"]
        else:
            db_records, _, _, _, scan_ok = _extract_agy_db_session_checked(db_path, fallback_ts)
            if scan_ok:
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
    try:
        _atomic_write_text(p, json.dumps(archive, ensure_ascii=False))
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


def _reprice_archived_entry(entry: dict[str, Any], catalog: dict[str, Any]) -> None:
    reprice_buckets = entry.get("repriceBuckets")
    fixed_rep = entry.get("fixedReported")
    if isinstance(reprice_buckets, dict) or isinstance(fixed_rep, dict):
        fr = fixed_rep if isinstance(fixed_rep, dict) else {}
        rep_cost = float(_safe_float(fr.get("costUsd")) or 0.0)
        tot_cost = rep_cost
        mod_cost = 0.0
        unp_ev = 0
        unp_tok = 0
        cache_sav = float(_safe_float(fr.get("cacheSavingsUsd")) or 0.0)
        fixed_cats = fr.get("categoryCostUsd") if isinstance(fr.get("categoryCostUsd"), dict) else {}
        cat_cost = {
            ck: float(_safe_float(fixed_cats.get(ck)) or 0.0)
            for ck in ("input", "cacheRead", "cacheWrite", "output")
        }
        if isinstance(reprice_buckets, dict):
            for rb in reprice_buckets.values():
                if not isinstance(rb, dict):
                    continue
                b_events = max(1, _safe_int(rb.get("events")))
                b_unc = _safe_int(rb.get("uncachedInputTokens"))
                b_cr = _safe_int(rb.get("cachedInputTokens"))
                b_cw = _safe_int(rb.get("cacheCreationTokens"))
                b_out = _safe_int(rb.get("outputTokens"))
                b_rea = _safe_int(rb.get("reasoningTokens"))
                if b_unc + b_cr + b_cw + b_out + b_rea <= 0:
                    continue
                priced = _price_record(
                    {
                        "model": entry.get("model"),
                        "speed": rb.get("speed"),
                        "uncachedInputTokens": b_unc,
                        "cachedInputTokens": b_cr,
                        "cacheCreationTokens": b_cw,
                        "outputTokens": b_out,
                        "reasoningTokens": b_rea,
                    },
                    catalog=catalog,
                    event_count=b_events,
                )
                tot_cost += priced["costUsd"]
                cache_sav += priced["cacheSavingsUsd"]
                for ck in ("input", "cacheRead", "cacheWrite", "output"):
                    cat_cost[ck] += priced["categoryCostUsd"][ck]
                cost_src = priced["costSource"]
                if cost_src == "providerReported":
                    rep_cost += priced["costUsd"]
                elif cost_src == "modelPriced":
                    mod_cost += priced["costUsd"]
                else:
                    unp_ev += _safe_int(rb.get("events"))
                    unp_tok += priced["totalTokens"]
        entry["costUsd"] = tot_cost
        entry["providerReportedCostUsd"] = rep_cost
        entry["modelPricedCostUsd"] = mod_cost
        entry["unpricedEvents"] = unp_ev
        entry["unpricedTokens"] = unp_tok
        entry["cacheSavingsUsd"] = cache_sav
        entry["categoryCostUsd"] = cat_cost
        return

    rep_cost = float(_safe_float(entry.get("providerReportedCostUsd")) or 0.0)
    if rep_cost > 0.0:
        return
    events = max(1, _safe_int(entry.get("events")))
    uncached = _safe_int(entry.get("uncachedInputTokens"))
    cached = _safe_int(entry.get("cachedInputTokens"))
    cache_write = _safe_int(entry.get("cacheCreationTokens"))
    out_tok = _safe_int(entry.get("outputTokens"))
    reason_tok = _safe_int(entry.get("reasoningTokens"))
    tot_tok = uncached + cached + cache_write + out_tok + reason_tok
    if tot_tok <= 0:
        return
    priced = _price_record(
        {
            "model": entry.get("model"),
            "uncachedInputTokens": uncached,
            "cachedInputTokens": cached,
            "cacheCreationTokens": cache_write,
            "outputTokens": out_tok,
            "reasoningTokens": reason_tok,
        },
        catalog=catalog,
        event_count=events,
    )
    cost_usd = priced["costUsd"]
    cost_src = priced["costSource"]
    entry["totalTokens"] = tot_tok
    entry["costUsd"] = cost_usd
    entry["cacheSavingsUsd"] = priced["cacheSavingsUsd"]
    entry["categoryCostUsd"] = dict(priced["categoryCostUsd"])
    if cost_src == "providerReported":
        entry["providerReportedCostUsd"] = cost_usd
        entry["modelPricedCostUsd"] = 0.0
        entry["unpricedEvents"] = 0
        entry["unpricedTokens"] = 0
    elif cost_src == "modelPriced":
        entry["providerReportedCostUsd"] = 0.0
        entry["modelPricedCostUsd"] = cost_usd
        entry["unpricedEvents"] = 0
        entry["unpricedTokens"] = 0
    else:
        entry["providerReportedCostUsd"] = 0.0
        entry["modelPricedCostUsd"] = 0.0
        entry["unpricedEvents"] = events
        entry["unpricedTokens"] = tot_tok


def collect_summary(
    days: int = 30,
    tz_name: str = "UTC",
    refresh: bool = False,
    period: str | None = None,
    sync_pricing: bool = True,
) -> dict[str, Any]:
    now_dt = dt.datetime.now(dt.timezone.utc)
    now_ms = int(now_dt.timestamp() * 1000)

    clean_tz_name = tz_name.strip() if isinstance(tz_name, str) and tz_name.strip() else ""
    resolved_tz_key = clean_tz_name or "LOCAL"
    try:
        from zoneinfo import ZoneInfo
        tz = ZoneInfo(clean_tz_name) if clean_tz_name else (dt.datetime.now().astimezone().tzinfo or dt.timezone.utc)
    except Exception:
        resolved_tz_key = "LOCAL"
        tz = dt.datetime.now().astimezone().tzinfo or dt.timezone.utc

    local_now = now_dt.astimezone(tz)
    today_start = dt.datetime.combine(local_now.date(), dt.time.min, tzinfo=tz)
    today_key = today_start.strftime("%Y-%m-%d")
    month_start = dt.datetime.combine(local_now.date().replace(day=1), dt.time.min, tzinfo=tz)
    month_prefix = today_start.strftime("%Y-%m-")

    norm_period = period.strip().lower() if isinstance(period, str) and period.strip() else None
    if norm_period not in ("today", "week", "month", "all"):
        norm_period = None

    hourly_start_utc: dt.datetime | None = None
    hourly_count = 0
    if norm_period == "today":
        effective_period = "today"
        effective_days = 1
        hourly = True
        start_dt = today_start
        next_day_start = dt.datetime.combine(today_start.date() + dt.timedelta(days=1), dt.time.min, tzinfo=tz)
        hourly_start_utc = start_dt.astimezone(dt.timezone.utc)
        next_day_utc = next_day_start.astimezone(dt.timezone.utc)
        hourly_count = max(1, int(round((next_day_utc - hourly_start_utc).total_seconds() / 3600.0)))
        period_cutoff_ms = int(hourly_start_utc.timestamp() * 1000)
    elif norm_period == "week":
        effective_period = "week"
        effective_days = 7
        hourly = False
        start_dt = dt.datetime.combine(today_start.date() - dt.timedelta(days=today_start.weekday()), dt.time.min, tzinfo=tz)
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
            cur_hour_utc = cur_hour.astimezone(dt.timezone.utc)
            hourly_start_utc = cur_hour_utc - dt.timedelta(hours=23)
            hourly_count = 24
            start_dt = hourly_start_utc.astimezone(tz)
            period_cutoff_ms = int(hourly_start_utc.timestamp() * 1000)
        else:
            start_dt = dt.datetime.combine(today_start.date() - dt.timedelta(days=effective_days - 1), dt.time.min, tzinfo=tz)
            period_cutoff_ms = int(start_dt.timestamp() * 1000)

    pricing_catalog = _sync_pricing_catalog(force=refresh) if sync_pricing else _get_pricing_catalog()
    pricing_digest = str(pricing_catalog.get("digest") or "")

    archive = _load_daily_archive()
    seeded_tzs: list[str] = archive.setdefault("seededTimezones", [])
    by_tz_all: dict[str, Any] = archive.setdefault("byTz", {})
    tz_archive: dict[str, dict[str, Any]] = by_tz_all.setdefault(resolved_tz_key, {})
    pricing_changed = bool(pricing_digest) and archive.get("pricingDigest") != pricing_digest
    scanner_changed = archive.get("scannerRev") != SCANNER_REV

    if pricing_changed:
        for tz_days in by_tz_all.values():
            if not isinstance(tz_days, dict):
                continue
            for day_models in tz_days.values():
                if not isinstance(day_models, dict):
                    continue
                for arch_entry in day_models.values():
                    if isinstance(arch_entry, dict):
                        _reprice_archived_entry(arch_entry, pricing_catalog)

    rolling_30d_start = today_start - dt.timedelta(days=29)
    rolling_30d_start_key = rolling_30d_start.strftime("%Y-%m-%d")
    rolling_30d_cutoff_ms = int(rolling_30d_start.timestamp() * 1000)
    if resolved_tz_key not in seeded_tzs or norm_period == "all" or refresh or pricing_changed or scanner_changed:
        scan_cutoff_ms = 0
    else:
        scan_cutoff_ms = min(period_cutoff_ms, rolling_30d_cutoff_ms)

    scan_cache = {"version": CACHE_VERSION, "files": {}} if (refresh or scanner_changed) else _load_scan_cache()
    cache_files = scan_cache.setdefault("files", {})
    home = Path.home()

    records: list[dict[str, Any]] = []
    records.extend(_scan_codex(home, cache_files, scan_cutoff_ms))
    records.extend(_scan_claude(home, cache_files, scan_cutoff_ms))
    records.extend(_scan_antigravity(home, cache_files, scan_cutoff_ms))
    records.extend(_scan_opencode(home, cache_files, scan_cutoff_ms))
    records.extend(_scan_pi(home, cache_files, scan_cutoff_ms))
    records.extend(_scan_deepseek(home, cache_files, scan_cutoff_ms))
    records.extend(_scan_kimi(home, cache_files, scan_cutoff_ms))
    records.extend(_scan_grok(home, cache_files, scan_cutoff_ms))
    records.extend(_scan_qoder(home, cache_files, scan_cutoff_ms))
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
        priced = _price_record(rec, catalog=pricing_catalog)
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
                "fixedReported": {
                    "events": 0,
                    "tokens": 0,
                    "costUsd": 0.0,
                    "cacheSavingsUsd": 0.0,
                    "categoryCostUsd": {"input": 0.0, "cacheRead": 0.0, "cacheWrite": 0.0, "output": 0.0},
                },
                "repriceBuckets": {},
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
            fr = d_entry["fixedReported"]
            fr["events"] += 1
            fr["tokens"] += tot_tok
            fr["costUsd"] += cost_usd
            fr["cacheSavingsUsd"] += priced["cacheSavingsUsd"]
            for ck in ("input", "cacheRead", "cacheWrite", "output"):
                fr["categoryCostUsd"][ck] += priced["categoryCostUsd"][ck]
        else:
            if cost_src == "modelPriced":
                d_entry["modelPricedCostUsd"] += cost_usd
            else:
                d_entry["unpricedEvents"] += 1
                d_entry["unpricedTokens"] += tot_tok
            prompt_tok = (
                rec["uncachedInputTokens"]
                + rec["cachedInputTokens"]
                + rec["cacheCreationTokens"]
            )
            tier_band = (
                0 if prompt_tok <= 128_000 else (1 if prompt_tok <= 200_000 else (2 if prompt_tok <= 272_000 else 3))
            )
            rate_obj = _lookup_model_rate_in_catalog(model, pricing_catalog)
            cat_above = 1 if (rate_obj is not None and rate_obj[4] is not None and prompt_tok > rate_obj[4][0]) else 0
            speed_str = str(rec.get("speed") or "")
            rb_key = f"{speed_str}|{tier_band}|{cat_above}"
            rb = d_entry["repriceBuckets"].setdefault(
                rb_key,
                {
                    "speed": speed_str or None,
                    "events": 0,
                    "uncachedInputTokens": 0,
                    "cachedInputTokens": 0,
                    "cacheCreationTokens": 0,
                    "outputTokens": 0,
                    "reasoningTokens": 0,
                },
            )
            rb["events"] += 1
            rb["uncachedInputTokens"] += rec["uncachedInputTokens"]
            rb["cachedInputTokens"] += rec["cachedInputTokens"]
            rb["cacheCreationTokens"] += rec["cacheCreationTokens"]
            rb["outputTokens"] += rec["outputTokens"]
            rb["reasoningTokens"] += rec["reasoningTokens"]
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
    if pricing_digest:
        archive["pricingDigest"] = pricing_digest
    archive["scannerRev"] = SCANNER_REV
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

    hourly_start_epoch_sec = int(hourly_start_utc.timestamp()) if hourly_start_utc is not None else 0
    if hourly and hourly_start_utc is not None:
        for i in range(hourly_count):
            b_dt = (hourly_start_utc + dt.timedelta(hours=i)).astimezone(tz)
            key = b_dt.strftime("%Y-%m-%dT%H:00")
            entry = {
                "date": key,
                "label": b_dt.strftime("%H:00"),
                "totalTokens": 0,
                "costUsd": 0.0,
                "byCli": {},
            }
            buckets.append(entry)
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
            slot = (ts_ms // 1000 - hourly_start_epoch_sec) // 3600
            bucket = buckets[slot] if 0 <= slot < len(buckets) else None
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
            slot = (ts_ms // 1000 - hourly_start_epoch_sec) // 3600
            bucket = buckets[slot] if 0 <= slot < len(buckets) else None
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
        "pricingSource": _pricing_source_metadata(pricing_catalog),
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
            "message": _tr("未检测到 Codex OAuth 登录凭证", "Codex OAuth credentials not detected"),
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
                    lbl_5h = _tr("5h 窗口", "5h window")
                    lbl_7d = _tr("7d 窗口", "7d window")
                    for key, default_label, default_mins in (("primary", lbl_5h, 300), ("secondary", lbl_7d, 10080)):
                        slot = rl.get(key)
                        if isinstance(slot, dict) and _safe_float(slot.get("used_percent")) is not None:
                            mins = _safe_int(slot.get("window_minutes")) or default_mins
                            lbl = lbl_5h if mins == 300 else (lbl_7d if mins == 10080 else default_label)
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
                    try:
                        msg = json.loads(line)
                    except Exception:
                        continue
                    if isinstance(msg, dict) and msg.get("id") == 2 and isinstance(msg.get("result"), dict):
                        res = msg["result"]
                        rl = res.get("rateLimits") if isinstance(res.get("rateLimits"), dict) else res
                        windows = []
                        lbl_5h = _tr("5h 窗口", "5h window")
                        lbl_7d = _tr("7d 窗口", "7d window")
                        for key, default_label, default_mins in (("primary", lbl_5h, 300), ("secondary", lbl_7d, 10080)):
                            slot = rl.get(key)
                            if isinstance(slot, dict) and _safe_float(slot.get("usedPercent") if "usedPercent" in slot else slot.get("used_percent")) is not None:
                                used_p = _safe_float(slot.get("usedPercent") if "usedPercent" in slot else slot.get("used_percent")) or 0.0
                                mins = _safe_int(slot.get("windowDurationMins") or slot.get("window_minutes")) or default_mins
                                lbl = lbl_5h if mins == 300 else (lbl_7d if mins == 10080 else default_label)
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
                for pipe in (proc.stdin, proc.stdout):
                    if pipe is not None:
                        try:
                            pipe.close()
                        except Exception:
                            pass
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
        "message": _tr("无法读取 Codex OAuth 实时限额，请检查网络或重新登录", "Unable to fetch live Codex OAuth limits; check network or sign in again"),
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
            "message": _tr("未配置 Claude.ai OAuth 凭证（API Key 模式无订阅窗口限额）", "Claude.ai OAuth credentials not configured (API key mode has no subscription window limits)"),
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
            "message": _tr("Claude OAuth 令牌已失效，请在 Claude Code 中重新登录", "Claude OAuth token expired; sign in again in Claude Code"),
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
            body = json.loads(_read_response_bytes(resp).decode("utf-8"))
        windows = []
        mapping = [
            ("five_hour", _tr("5h 窗口", "5h window"), 5 * 3_600_000),
            ("seven_day", _tr("7d 总窗口", "7d overall"), 7 * 86_400_000),
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
                    "label": _tr("额外用量额度", "Extra usage credits"),
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
            "message": _tr(f"读取限额失败：{type(exc).__name__}", f"Failed to read limits: {type(exc).__name__}"),
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
                _tr("7d 窗口", "7d window")
                if win_type == "weekly"
                else (
                    _tr("5h 窗口", "5h window")
                    if win_type == "5h"
                    else (str(b.get("displayName") or "").strip() or _tr("配额", "Quota"))
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
            ("primary", "quotaInfo", None, f"{grp} · {_tr('主窗口', 'Primary')}" if has_weekly else grp),
            ("weekly", "weeklyQuotaInfo", 7 * 86_400_000, f"{grp} · {_tr('7d 窗口', '7d window')}"),
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
            payload = json.loads(_read_response_bytes(resp).decode("utf-8"))
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
            "message": _tr("未检测到 Antigravity OAuth 登录凭证", "Antigravity OAuth credentials not detected"),
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
            "message": _tr("OAuth 令牌刷新失败，可在 Antigravity 会话中重新登录", "OAuth token refresh failed; sign in again in an Antigravity session"),
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

        def _fetch_one(host: str) -> list[dict[str, Any]] | None:
            safe_url = _validate_http_url(f"{host}{endpoint_suffix}")
            if safe_url is None:
                return None
            req_ms = int(dt.datetime.now(dt.timezone.utc).timestamp() * 1000)
            req = urllib.request.Request(
                safe_url,
                data=body_bytes,
                headers=headers,
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=req_timeout) as resp:
                data = json.loads(_read_response_bytes(resp).decode("utf-8"))
            if isinstance(data, dict):
                return parse_fn(data, req_ms)
            return None

        task_res = _run_daemon_tasks(
            {h: (lambda host=h: _fetch_one(host)) for h in ANTIGRAVITY_QUOTA_URLS},
            timeout=max_wait,
        )
        collected: list[list[dict[str, Any]]] = []
        for h in ANTIGRAVITY_QUOTA_URLS:
            item = task_res.get(h)
            if item is not None and item[0] and item[1] is not None:
                collected.append(item[1])
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
        "message": _tr("OAuth 令牌已过期或配额端点暂不可达，可在 Antigravity 会话中刷新登录", "OAuth token expired or quota endpoint unreachable; refresh sign-in in an Antigravity session"),
        "windows": [],
        "credits": None,
    }

class _GrpcStatusError(Exception):
    def __init__(self, status_code: int, message: str = "") -> None:
        super().__init__(f"gRPC {status_code}: {message}")
        self.status_code = status_code
        self.grpc_message = message


def _decode_grok_grpc_web_billing(raw_bytes: bytes) -> dict[str, Any]:
    pos = 0
    n = len(raw_bytes)
    payload_parts: list[bytes] = []
    trailers: dict[str, str] = {}
    while pos + 5 <= n:
        flags = raw_bytes[pos]
        ln = int.from_bytes(raw_bytes[pos + 1 : pos + 5], "big")
        pos += 5
        if pos + ln > n:
            raise ValueError("Truncated gRPC-Web frame")
        chunk = raw_bytes[pos : pos + ln]
        pos += ln
        if flags & 0x80:
            for line in chunk.decode("utf-8", errors="replace").splitlines():
                if ":" in line:
                    k, v = line.split(":", 1)
                    trailers[k.strip().lower()] = v.strip()
        else:
            payload_parts.append(chunk)

    grpc_status = trailers.get("grpc-status")
    if grpc_status is not None and grpc_status != "0":
        code_int = int(grpc_status) if grpc_status.lstrip("-").isdigit() else -1
        msg = urllib.parse.unquote(trailers.get("grpc-message", ""))
        raise _GrpcStatusError(code_int, msg)

    raw_payload = b"".join(payload_parts)
    top = _proto_fields(raw_payload)
    cfg_bytes = _proto_bytes(top, 1) or raw_payload
    cfg = _proto_fields(cfg_bytes)

    used_pct: float | None = None
    for fno, wt, val in _iter_proto_fields(cfg_bytes):
        if fno != 1 or not isinstance(val, bytes):
            continue
        if wt == 5:
            cand = struct.unpack("<f", val)[0]
            if math.isfinite(cand) and 0.0 <= cand <= 100.0:
                used_pct = cand
                break
        elif wt == 1:
            cand = struct.unpack("<d", val)[0]
            if math.isfinite(cand) and 0.0 <= cand <= 100.0:
                used_pct = cand
                break

    start_ms = _proto_timestamp(_proto_nested(cfg, 4))
    end_ms = _proto_timestamp(_proto_nested(cfg, 5))
    period_msg = _proto_nested(cfg, 8)
    period_enum = _proto_num(period_msg, 1)
    if start_ms is None and period_msg:
        start_ms = _proto_timestamp(_proto_nested(period_msg, 2))
    if end_ms is None and period_msg:
        end_ms = _proto_timestamp(_proto_nested(period_msg, 3))

    if used_pct is None and (start_ms is not None or end_ms is not None or period_enum in (2, 3)):
        used_pct = 0.0

    windows: list[dict[str, Any]] = []
    if used_pct is not None:
        span_days = ((end_ms - start_ms) / 86_400_000.0) if (start_ms and end_ms and end_ms > start_ms) else None
        if period_enum == 2 or (span_days is not None and 4.0 <= span_days <= 12.0):
            dur_ms: int | None = 7 * 86_400_000
            lbl = _tr("7d 订阅额度", "7d subscription")
        elif period_enum == 3 or (span_days is not None and 20.0 <= span_days <= 45.0):
            dur_ms = 30 * 86_400_000
            lbl = _tr("月度订阅额度", "Monthly subscription")
        else:
            dur_ms = int(end_ms - start_ms) if (start_ms and end_ms and end_ms > start_ms) else None
            lbl = _tr("订阅额度", "Subscription")
        end_iso = _iso_from_epoch_sec(end_ms / 1000.0) if end_ms else None
        windows.append(_make_window("subscription", lbl, used_pct, end_iso, dur_ms))

    return {
        "windows": windows,
        "credits": None,
        "plan": "SuperGrok" if windows else "xAI",
    }


def _extract_grok_oauth(home: Path) -> dict[str, Any] | None:
    env_token = (os.environ.get("GROK_BEARER_TOKEN") or "").strip()
    if env_token:
        return {
            "token": env_token,
            "email": None,
            "refreshToken": "",
            "expiresAt": None,
            "oidcIssuer": "https://auth.x.ai",
            "oidcClientId": "",
            "authFile": None,
            "scopeKey": None,
        }
    grok_home = Path(os.environ.get("GROK_HOME") or str(home / ".grok")).expanduser()
    auth_file = grok_home / "auth.json"
    if not auth_file.is_file():
        return None
    try:
        raw = json.loads(auth_file.read_text("utf-8"))
    except Exception:
        return None
    if not isinstance(raw, dict):
        return None

    scope_key: str | None = None
    cred: dict[str, Any] | None = None
    for preferred in (
        "https://auth.x.ai::b1a00492-073a-47ea-816f-4c329264a828",
        "https://accounts.x.ai/sign-in",
    ):
        cand = raw.get(preferred)
        if isinstance(cand, dict):
            scope_key = preferred
            cred = cand
            break
    if cred is None:
        for k, v in raw.items():
            if isinstance(v, dict) and (v.get("key") or v.get("access_token") or v.get("refresh_token")):
                scope_key = str(k)
                cred = v
                break
    if cred is None:
        return None

    auth_mode = str(cred.get("auth_mode") or "").strip().lower()
    if auth_mode in ("api_key", "apikey"):
        return None
    email = str(cred.get("email") or "").strip() or None
    token = str(cred.get("key") or cred.get("access_token") or "").strip()
    ref = str(cred.get("refresh_token") or "").strip()
    if not token:
        if not ref and auth_mode not in ("oidc", "oauth"):
            return None
    elif token.startswith("xai-") and not ref and auth_mode not in ("oidc", "oauth"):
        return None

    exp_ms = _parse_ts_ms(cred.get("expires_at"))
    exp_sec = (exp_ms / 1000.0) if exp_ms is not None else _safe_float(cred.get("expires_at"))
    issuer = str(cred.get("oidc_issuer") or "https://auth.x.ai").strip().rstrip("/")
    client_id = str(cred.get("oidc_client_id") or "").strip()
    if not client_id and scope_key and "::" in scope_key:
        client_id = scope_key.split("::", 1)[1].strip()

    return {
        "token": token,
        "email": email,
        "refreshToken": ref,
        "expiresAt": exp_sec,
        "oidcIssuer": issuer,
        "oidcClientId": client_id,
        "authFile": auth_file,
        "scopeKey": scope_key,
    }


def _refresh_grok_access_token(auth: dict[str, Any]) -> str | None:
    ref = str(auth.get("refreshToken") or "").strip()
    client_id = str(auth.get("oidcClientId") or "").strip()
    if not ref or not client_id:
        return None
    safe_issuer = _validate_http_url(auth.get("oidcIssuer") or "https://auth.x.ai")
    if safe_issuer is None:
        return None
    issuer = safe_issuer.rstrip("/")
    body_bytes = urllib.parse.urlencode({
        "grant_type": "refresh_token",
        "client_id": client_id,
        "refresh_token": ref,
    }).encode("utf-8")
    req = urllib.request.Request(
        f"{issuer}/oauth2/token",
        data=body_bytes,
        method="POST",
        headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "Accept": "application/json",
            "User-Agent": "Grok Build",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=4.5) as resp:
            payload = json.loads(_read_response_bytes(resp).decode("utf-8"))
    except Exception:
        return None
    if not isinstance(payload, dict):
        return None
    new_tok = str(payload.get("access_token") or "").strip()
    if not new_tok:
        return None
    new_ref = str(payload.get("refresh_token") or ref).strip()
    exp_in = _safe_float(payload.get("expires_in")) or 3600.0
    new_exp = time.time() + exp_in

    auth_file = auth.get("authFile")
    scope_key = auth.get("scopeKey")
    if isinstance(auth_file, Path):
        if not isinstance(scope_key, str) or not auth_file.is_file():
            return None
        try:
            raw = json.loads(auth_file.read_text("utf-8"))
            if not isinstance(raw, dict) or not isinstance(raw.get(scope_key), dict):
                return None
            entry = raw[scope_key]
            if "access_token" in entry and "key" not in entry:
                entry["access_token"] = new_tok
            else:
                entry["key"] = new_tok
            entry["refresh_token"] = new_ref
            old_exp = entry.get("expires_at")
            if isinstance(old_exp, str) and "-" in old_exp:
                entry["expires_at"] = dt.datetime.fromtimestamp(new_exp, tz=dt.timezone.utc).isoformat()
            else:
                entry["expires_at"] = int(new_exp)
            _atomic_write_text(auth_file, json.dumps(raw, ensure_ascii=False, indent=2), mode=0o600)
        except Exception:
            return None
    auth["token"] = new_tok
    auth["refreshToken"] = new_ref
    auth["expiresAt"] = new_exp
    return new_tok


def _probe_grok_limits(home: Path) -> dict[str, Any]:
    now_sec = time.time()
    now_iso = dt.datetime.now(dt.timezone.utc).isoformat()
    auth = _extract_grok_oauth(home)
    if auth is None:
        return {
            "cli": "Grok Build",
            "planType": None,
            "updatedAt": now_iso,
            "source": "unavailable",
            "status": "unconfigured",
            "message": _tr("未检测到 Grok OAuth 登录凭证", "Grok OAuth credentials not detected"),
            "windows": [],
            "credits": None,
        }
    token = str(auth.get("token") or "").strip()
    email = auth.get("email")
    exp_at = _safe_float(auth.get("expiresAt"))
    refreshed_once = False
    if (not token or (exp_at is not None and exp_at > 0 and exp_at <= now_sec + 60)) and auth.get("refreshToken"):
        refreshed = _refresh_grok_access_token(auth)
        if refreshed:
            token = refreshed
            refreshed_once = True
    if not token:
        return {
            "cli": "Grok Build",
            "planType": email or "xAI",
            "updatedAt": now_iso,
            "source": "unavailable",
            "status": "error",
            "message": _tr("Grok OAuth 令牌已失效，请在 Grok Build 中重新登录", "Grok OAuth token expired; sign in again in Grok Build"),
            "windows": [],
            "credits": None,
        }

    def _fetch_rest(bearer: str) -> dict[str, Any]:
        req = urllib.request.Request(
            "https://cli-chat-proxy.grok.com/v1/billing?format=credits",
            headers={
                "Authorization": f"Bearer {bearer}",
                "Accept": "application/json",
                "User-Agent": "grok-cli/1.0",
            },
        )
        with urllib.request.urlopen(req, timeout=3.0) as resp:
            return json.loads(_read_response_bytes(resp).decode("utf-8"))

    def _fetch_grpc_web(bearer: str) -> dict[str, Any]:
        req = urllib.request.Request(
            "https://grok.com/grok_api_v2.GrokBuildBilling/GetGrokCreditsConfig",
            data=b"\x00\x00\x00\x00\x00",
            method="POST",
            headers={
                "Authorization": f"Bearer {bearer}",
                "X-XAI-Token-Auth": "xai-grok-cli",
                "Accept": "*/*",
                "Content-Type": "application/grpc-web+proto",
                "User-Agent": "Grok Build",
                "Origin": "https://grok.com",
                "Referer": "https://grok.com/?_s=usage",
                "x-grpc-web": "1",
                "x-user-agent": "connect-es/2.1.1",
            },
        )
        with urllib.request.urlopen(req, timeout=4.5) as resp:
            return _decode_grok_grpc_web_billing(_read_response_bytes(resp))

    privacy_blocked = False
    rest_exc: Exception | None = None
    try:
        try:
            body = _fetch_rest(token)
        except urllib.error.HTTPError as he:
            if he.code == 401 and not refreshed_once and auth.get("refreshToken"):
                try:
                    he.close()
                except Exception:
                    pass
                refreshed = _refresh_grok_access_token(auth)
                if refreshed:
                    token = refreshed
                    refreshed_once = True
                    body = _fetch_rest(token)
                else:
                    raise
            else:
                err_txt = ""
                try:
                    err_txt = _read_response_bytes(he, 16384).decode("utf-8", errors="replace")
                except Exception:
                    pass
                finally:
                    try:
                        he.close()
                    except Exception:
                        pass
                if he.code == 403 and "WKE=privacy-mode:" in err_txt:
                    privacy_blocked = True
                raise

        windows: list[dict[str, Any]] = []
        credits = None
        cfg = body.get("config") if isinstance(body.get("config"), dict) else {}
        period = cfg.get("currentPeriod") if isinstance(cfg.get("currentPeriod"), dict) else {}
        p_type = str(period.get("type") or "").replace("USAGE_PERIOD_TYPE_", "")
        used_pct = _safe_float(cfg.get("creditUsagePercent"))
        if used_pct is None and p_type in ("WEEKLY", "MONTHLY") and period.get("start") and period.get("end"):
            used_pct = 0.0
        if used_pct is not None:
            dur_ms = (7 * 86_400_000) if p_type == "WEEKLY" else ((30 * 86_400_000) if p_type == "MONTHLY" else None)
            lbl = (
                _tr("7d 订阅额度", "7d subscription")
                if p_type == "WEEKLY"
                else (_tr("月度订阅额度", "Monthly subscription") if p_type == "MONTHLY" else _tr("订阅额度", "Subscription"))
            )
            end_iso = period.get("end") if isinstance(period.get("end"), str) else None
            windows.append(_make_window("subscription", lbl, used_pct, end_iso, dur_ms))

        rem_cents = _safe_float(body.get("effectiveRemaining") or body.get("remainingCredits"))
        tot_cents = _safe_float(body.get("totalCredits") or body.get("monthlyCredits"))
        if rem_cents is not None:
            rem_usd = rem_cents / 100.0
            tot_usd = (tot_cents / 100.0) if (tot_cents and tot_cents > 0) else None
            used_usd = max(0.0, tot_usd - rem_usd) if tot_usd is not None else 0.0
            credits = {
                "label": _tr("可用额度", "Available credits"),
                "used": round(used_usd, 2),
                "limit": round(tot_usd, 2) if tot_usd else None,
                "remaining": round(rem_usd, 2),
                "unit": "USD",
            }
            if not windows and tot_usd and tot_usd > 0:
                windows.append(_make_window("credits", _tr("月度额度", "Monthly credits"), (used_usd / tot_usd) * 100.0, None, 30 * 86_400_000))
        if windows or credits is not None:
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
        rest_exc = exc

    try:
        try:
            grpc_res = _fetch_grpc_web(token)
        except (_GrpcStatusError, urllib.error.HTTPError) as g_err:
            is_auth_err = (
                (isinstance(g_err, urllib.error.HTTPError) and g_err.code == 401)
                or (
                    isinstance(g_err, _GrpcStatusError)
                    and (
                        g_err.status_code == 16
                        or (
                            g_err.status_code == 7
                            and any(k in g_err.grpc_message.lower() for k in ("bad-credentials", "unauthenticated", "expired", "token"))
                        )
                    )
                )
            )
            if is_auth_err and not refreshed_once and auth.get("refreshToken"):
                refreshed = _refresh_grok_access_token(auth)
                if refreshed:
                    token = refreshed
                    refreshed_once = True
                    grpc_res = _fetch_grpc_web(token)
                else:
                    raise
            else:
                raise
        if grpc_res.get("windows") or grpc_res.get("credits") is not None:
            return {
                "cli": "Grok Build",
                "planType": email or str(grpc_res.get("plan") or "SuperGrok"),
                "updatedAt": now_iso,
                "source": "live",
                "status": "ok",
                "message": None,
                "windows": grpc_res.get("windows") or [],
                "credits": grpc_res.get("credits"),
            }
    except Exception as grpc_exc:
        if privacy_blocked:
            return {
                "cli": "Grok Build",
                "planType": email or "xAI",
                "updatedAt": now_iso,
                "source": "unavailable",
                "status": "error",
                "message": _tr("请在 xAI 账户设置中开启 Privacy Mode 以查看 Grok 限额", "Enable Privacy Mode in xAI account settings to view Grok limits"),
                "windows": [],
                "credits": None,
            }
        err_obj = rest_exc or grpc_exc
        return {
            "cli": "Grok Build",
            "planType": email,
            "updatedAt": now_iso,
            "source": "unavailable",
            "status": "error",
            "message": _tr(f"读取 Grok 额度失败：{type(err_obj).__name__}", f"Failed to read Grok credits: {type(err_obj).__name__}"),
            "windows": [],
            "credits": None,
        }

    return {
        "cli": "Grok Build",
        "planType": email or "xAI",
        "updatedAt": now_iso,
        "source": "live",
        "status": "ok",
        "message": None,
        "windows": [],
        "credits": None,
    }


def _extract_deepseek_auth(home: Path) -> dict[str, str] | None:
    api_key = (os.environ.get("DEEPSEEK_API_KEY") or os.environ.get("DEEPSEEK_KEY") or "").strip()
    platform_token = ""
    platform_issuer = "https://platform.deepseek.com"
    dsh_home = Path(os.environ.get("DSH_HOME") or str(home / ".dsh")).expanduser()
    cred_path = dsh_home / ".credentials.yaml"
    if cred_path.is_file():
        try:
            text = cred_path.read_text("utf-8", errors="replace")
        except OSError:
            text = ""
        if text:
            if not api_key:
                m_key = re.search(r"(?:^|[{,\s])DEEPSEEK_API_KEY:\s*([^\s,}#]+)", text, re.M)
                if m_key:
                    api_key = m_key.group(1).strip("'\"")
            lines = text.splitlines()
            start = next(
                (
                    (idx, len(line) - len(line.lstrip()))
                    for idx, line in enumerate(lines)
                    if re.match(r"^\s*deepseek-account-platform/[^:]+:\s*$", line)
                ),
                None,
            )
            if start is not None:
                start_idx, base_indent = start
                for line in lines[start_idx + 1 :]:
                    stripped = line.strip()
                    if stripped and (len(line) - len(line.lstrip())) <= base_indent:
                        break
                    m_tok = re.match(r"^\s+token:\s*([^\s,}#]+)", line)
                    if m_tok:
                        platform_token = m_tok.group(1).strip("'\"")
                    m_iss = re.match(r"^\s+issuer:\s*([^\s,}#]+)", line)
                    if m_iss:
                        platform_issuer = m_iss.group(1).strip("'\"").rstrip("/")
    if not api_key and not platform_token:
        return None
    return {
        "apiKey": api_key,
        "platformToken": platform_token,
        "platformIssuer": platform_issuer or "https://platform.deepseek.com",
    }


def _probe_deepseek_limits(home: Path) -> dict[str, Any]:
    now_iso = dt.datetime.now(dt.timezone.utc).isoformat()
    auth = _extract_deepseek_auth(home)
    if auth is None:
        return {
            "cli": "DeepSeek Harness",
            "planType": None,
            "updatedAt": now_iso,
            "source": "unavailable",
            "status": "unconfigured",
            "message": _tr("未检测到 DeepSeek 登录凭证", "DeepSeek credentials not detected"),
            "windows": [],
            "credits": None,
        }

    last_exc: Exception | None = None
    api_key = auth.get("apiKey") or ""
    if api_key:
        try:
            req = urllib.request.Request(
                "https://api.deepseek.com/user/balance",
                headers={
                    "Authorization": f"Bearer {api_key}",
                    "Accept": "application/json",
                    "User-Agent": "luma-cli/1.0",
                },
            )
            with urllib.request.urlopen(req, timeout=4.0) as resp:
                payload = json.loads(_read_response_bytes(resp).decode("utf-8"))
            infos = [x for x in (payload.get("balance_infos") or []) if isinstance(x, dict)]
            chosen = (
                next((x for x in infos if (_safe_float(x.get("total_balance")) or 0.0) > 0), None)
                or next((x for x in infos if str(x.get("currency") or "").upper() == "CNY"), None)
                or (infos[0] if infos else None)
            )
            if chosen is not None:
                bal = _safe_float(chosen.get("total_balance")) or 0.0
                unit = str(chosen.get("currency") or "CNY").upper()
                return {
                    "cli": "DeepSeek Harness",
                    "planType": "API Balance",
                    "updatedAt": now_iso,
                    "source": "live",
                    "status": "ok",
                    "message": None,
                    "windows": [],
                    "credits": {
                        "label": _tr("账户余额", "Account balance"),
                        "used": 0.0,
                        "limit": None,
                        "remaining": round(bal, 2),
                        "unit": unit,
                    },
                }
        except Exception as exc:
            last_exc = exc

    platform_token = auth.get("platformToken") or ""
    safe_issuer = _validate_http_url(auth.get("platformIssuer") or "https://platform.deepseek.com")
    if platform_token and safe_issuer is not None:
        platform_issuer = safe_issuer.rstrip("/")
        try:
            req = urllib.request.Request(
                f"{platform_issuer}/api/v0/users/get_user_summary",
                headers={
                    "x-dsh-auth-token": platform_token,
                    "Accept": "application/json",
                    "User-Agent": "luma-cli/1.0",
                },
            )
            with urllib.request.urlopen(req, timeout=4.0) as resp:
                payload = json.loads(_read_response_bytes(resp).decode("utf-8"))
            biz = ((payload.get("data") or {}).get("biz_data")) if isinstance(payload.get("data"), dict) else None
            wallets = [w for w in ((biz or {}).get("normal_wallets") or []) if isinstance(w, dict)]
            chosen = (
                next((w for w in wallets if (_safe_float(w.get("balance")) or 0.0) > 0), None)
                or next((w for w in wallets if str(w.get("currency") or "").upper() == "CNY"), None)
                or (wallets[0] if wallets else None)
            )
            if chosen is not None:
                bal = _safe_float(chosen.get("balance")) or 0.0
                unit = str(chosen.get("currency") or "CNY").upper()
                return {
                    "cli": "DeepSeek Harness",
                    "planType": "DSH Account",
                    "updatedAt": now_iso,
                    "source": "live",
                    "status": "ok",
                    "message": None,
                    "windows": [],
                    "credits": {
                        "label": _tr("账户余额", "Account balance"),
                        "used": 0.0,
                        "limit": None,
                        "remaining": round(bal, 2),
                        "unit": unit,
                    },
                }
        except Exception as exc:
            last_exc = exc

    err_name = type(last_exc).__name__ if last_exc is not None else "Unavailable"
    return {
        "cli": "DeepSeek Harness",
        "planType": "DeepSeek",
        "updatedAt": now_iso,
        "source": "unavailable",
        "status": "error",
        "message": _tr(f"读取 DeepSeek 余额失败：{err_name}", f"Failed to read DeepSeek balance: {err_name}"),
        "windows": [],
        "credits": None,
    }

def _extract_kimi_oauth(home: Path) -> dict[str, Any] | None:
    default_base = (os.environ.get("KIMI_CODE_BASE_URL") or "https://api.kimi.com/coding/v1").strip().rstrip("/")
    oauth_host = (
        os.environ.get("KIMI_CODE_OAUTH_HOST")
        or os.environ.get("KIMI_OAUTH_HOST")
        or "https://auth.kimi.com"
    ).strip().rstrip("/")

    roots: list[Path] = []
    for raw_root in (
        os.environ.get("KIMI_SHARE_DIR"),
        str(home / ".kimi"),
        os.environ.get("KIMI_CODE_HOME"),
        str(home / ".kimi-code"),
    ):
        if not raw_root:
            continue
        p = Path(raw_root).expanduser()
        if p not in roots and p.is_dir():
            roots.append(p)

    for root in roots:
        base_url = default_base
        cfg_api_key = ""
        toml_path = root / "config.toml"
        if toml_path.is_file():
            try:
                import tomllib
                cfg = tomllib.loads(toml_path.read_text("utf-8"))
                providers = cfg.get("providers") if isinstance(cfg, dict) and isinstance(cfg.get("providers"), dict) else {}
                for pk, pv in providers.items():
                    if not isinstance(pv, dict):
                        continue
                    b_url = str(pv.get("base_url") or "").strip().rstrip("/")
                    if "kimi-code" in str(pk).lower() or "/coding" in b_url:
                        if b_url:
                            base_url = b_url
                        ak = str(pv.get("api_key") or "").strip()
                        if ak:
                            cfg_api_key = ak
                        break
            except Exception:
                pass

        cred_file = root / "credentials" / "kimi-code.json"
        if cred_file.is_file():
            try:
                raw_cred = json.loads(cred_file.read_text("utf-8"))
            except Exception:
                raw_cred = None
            if isinstance(raw_cred, dict):
                acc = str(raw_cred.get("access_token") or "").strip()
                ref = str(raw_cred.get("refresh_token") or "").strip()
                exp = _safe_float(raw_cred.get("expires_at"))
                if acc or ref:
                    return {
                        "accessToken": acc,
                        "refreshToken": ref,
                        "expiresAt": exp,
                        "baseUrl": base_url,
                        "oauthHost": oauth_host,
                        "credFile": cred_file,
                    }

        if cfg_api_key:
            return {
                "accessToken": cfg_api_key,
                "refreshToken": "",
                "expiresAt": None,
                "baseUrl": base_url,
                "oauthHost": oauth_host,
                "credFile": None,
            }
    env_key = (os.environ.get("KIMI_CODE_API_KEY") or "").strip()
    if env_key:
        return {
            "accessToken": env_key,
            "refreshToken": "",
            "expiresAt": None,
            "baseUrl": default_base,
            "oauthHost": oauth_host,
            "credFile": None,
        }
    return None


def _refresh_kimi_access_token(auth: dict[str, Any]) -> str | None:
    ref = str(auth.get("refreshToken") or "").strip()
    if not ref:
        return None
    safe_oauth_host = _validate_http_url(auth.get("oauthHost") or "https://auth.kimi.com")
    if safe_oauth_host is None:
        return None
    oauth_host = safe_oauth_host.rstrip("/")
    form_body = urllib.parse.urlencode({
        "client_id": "17e5f671-d194-4dfb-9706-5516cb48c098",
        "grant_type": "refresh_token",
        "refresh_token": ref,
    }).encode("utf-8")
    req = urllib.request.Request(
        f"{oauth_host}/api/oauth/token",
        data=form_body,
        method="POST",
        headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "Accept": "application/json",
            "X-Msh-Platform": "kimi_cli",
            "User-Agent": "kimi-cli/1.0",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=4.0) as resp:
            payload = json.loads(_read_response_bytes(resp).decode("utf-8"))
    except Exception:
        return None
    if not isinstance(payload, dict):
        return None
    new_acc = str(payload.get("access_token") or "").strip()
    if not new_acc:
        return None
    exp_in = _safe_float(payload.get("expires_in")) or 3600.0
    new_ref = str(payload.get("refresh_token") or ref).strip()
    new_exp = time.time() + exp_in
    cred_file = auth.get("credFile")
    if isinstance(cred_file, Path):
        try:
            existing: dict[str, Any] = {}
            if cred_file.is_file():
                try:
                    loaded = json.loads(cred_file.read_text("utf-8"))
                    if isinstance(loaded, dict):
                        existing = loaded
                except Exception:
                    existing = {}
            existing.update({
                "access_token": new_acc,
                "refresh_token": new_ref,
                "expires_at": new_exp,
                "scope": str(payload.get("scope") or existing.get("scope") or ""),
                "token_type": str(payload.get("token_type") or existing.get("token_type") or "Bearer"),
                "expires_in": exp_in,
            })
            _atomic_write_text(cred_file, json.dumps(existing, ensure_ascii=False), mode=0o600)
        except Exception:
            return None
    auth["accessToken"] = new_acc
    auth["refreshToken"] = new_ref
    auth["expiresAt"] = new_exp
    return new_acc


def _kimi_reset_iso(data: dict[str, Any], now_sec: float) -> str | None:
    for key in ("reset_at", "resetAt", "reset_time", "resetTime"):
        val = data.get(key)
        if isinstance(val, str) and val.strip():
            ts_ms = _parse_ts_ms(val)
            if ts_ms is not None:
                return _iso_from_epoch_sec(ts_ms / 1000.0)
    for key in ("reset_in", "resetIn", "ttl"):
        sec = _safe_float(data.get(key))
        if sec is not None and sec > 0:
            return _iso_from_epoch_sec(now_sec + sec)
    return None


def _probe_kimi_limits(home: Path) -> dict[str, Any]:
    now_sec = time.time()
    now_iso = dt.datetime.now(dt.timezone.utc).isoformat()
    auth = _extract_kimi_oauth(home)
    if auth is None:
        return {
            "cli": "Kimi Code",
            "planType": None,
            "updatedAt": now_iso,
            "source": "unavailable",
            "status": "unconfigured",
            "message": _tr("未检测到 Kimi Code 登录凭证", "Kimi Code credentials not detected"),
            "windows": [],
            "credits": None,
        }
    token = str(auth.get("accessToken") or "").strip()
    exp_at = _safe_float(auth.get("expiresAt"))
    if (not token or (exp_at is not None and exp_at > 0 and exp_at <= now_sec + 60)) and auth.get("refreshToken"):
        refreshed = _refresh_kimi_access_token(auth)
        if refreshed:
            token = refreshed
    if not token:
        return {
            "cli": "Kimi Code",
            "planType": "Kimi Code",
            "updatedAt": now_iso,
            "source": "unavailable",
            "status": "error",
            "message": _tr("Kimi Code OAuth 令牌已过期，请重新登录", "Kimi Code OAuth token expired; sign in again"),
            "windows": [],
            "credits": None,
        }

    safe_base = _validate_http_url(auth.get("baseUrl") or "https://api.kimi.com/coding/v1")
    if safe_base is None:
        return {
            "cli": "Kimi Code",
            "planType": "Kimi Code",
            "updatedAt": now_iso,
            "source": "unavailable",
            "status": "error",
            "message": _tr("Kimi Code 接口地址无效", "Invalid Kimi Code base URL"),
            "windows": [],
            "credits": None,
        }
    base_url = safe_base.rstrip("/")
    try:
        def _fetch(bearer: str) -> dict[str, Any]:
            req = urllib.request.Request(
                f"{base_url}/usages",
                headers={
                    "Authorization": f"Bearer {bearer}",
                    "Accept": "application/json",
                    "X-Msh-Platform": "kimi_cli",
                    "User-Agent": "kimi-cli/1.0",
                },
            )
            with urllib.request.urlopen(req, timeout=4.0) as resp:
                return json.loads(_read_response_bytes(resp).decode("utf-8"))

        try:
            body = _fetch(token)
        except urllib.error.HTTPError as he:
            if he.code == 401 and auth.get("refreshToken"):
                try:
                    he.close()
                except Exception:
                    pass
                refreshed = _refresh_kimi_access_token(auth)
                if refreshed:
                    body = _fetch(refreshed)
                else:
                    raise
            else:
                raise

        if isinstance(body, dict) and isinstance(body.get("data"), dict):
            body = body["data"]

        user_obj = body.get("user") if isinstance(body.get("user"), dict) else {}
        mem_obj = user_obj.get("membership") if isinstance(user_obj.get("membership"), dict) else {}
        raw_level = str(mem_obj.get("level") or "").strip()
        plan_label = raw_level.removeprefix("LEVEL_").replace("_", " ").title() if raw_level else "Kimi Code"

        windows: list[dict[str, Any]] = []
        usage_map = body.get("usage")
        if isinstance(usage_map, dict):
            lim_v = _safe_float(usage_map.get("limit"))
            used_v = _safe_float(usage_map.get("used"))
            if used_v is None and lim_v is not None:
                rem_v = _safe_float(usage_map.get("remaining"))
                if rem_v is not None:
                    used_v = max(0.0, lim_v - rem_v)
            if lim_v is not None and lim_v > 0 and used_v is not None:
                lbl = str(usage_map.get("name") or usage_map.get("title") or _tr("7d 订阅限额", "Weekly limit"))
                windows.append(
                    _make_window(
                        "weekly",
                        lbl,
                        (used_v / lim_v) * 100.0,
                        _kimi_reset_iso(usage_map, now_sec),
                        7 * 86_400_000,
                    )
                )

        raw_limits = body.get("limits")
        if isinstance(raw_limits, list):
            for idx, item in enumerate(raw_limits):
                if not isinstance(item, dict):
                    continue
                detail = item.get("detail") if isinstance(item.get("detail"), dict) else item
                win_meta = item.get("window") if isinstance(item.get("window"), dict) else {}
                lim_v = _safe_float(detail.get("limit"))
                used_v = _safe_float(detail.get("used"))
                if used_v is None and lim_v is not None:
                    rem_v = _safe_float(detail.get("remaining"))
                    if rem_v is not None:
                        used_v = max(0.0, lim_v - rem_v)
                if lim_v is None or lim_v <= 0 or used_v is None:
                    continue
                dur = _safe_int(win_meta.get("duration") or item.get("duration") or detail.get("duration"))
                t_unit = str(win_meta.get("timeUnit") or item.get("timeUnit") or detail.get("timeUnit") or "").upper()
                dur_ms: int | None = None
                auto_lbl = _tr(f"限额 #{idx + 1}", f"Limit #{idx + 1}")
                if dur > 0:
                    if "MIN" in t_unit:
                        dur_ms = dur * 60_000
                        auto_lbl = _tr(f"{dur // 60}h 限额", f"{dur // 60}h limit") if (dur >= 60 and dur % 60 == 0) else _tr(f"{dur}m 限额", f"{dur}m limit")
                    elif "HOUR" in t_unit:
                        dur_ms = dur * 3_600_000
                        auto_lbl = _tr(f"{dur}h 限额", f"{dur}h limit")
                    elif "DAY" in t_unit:
                        dur_ms = dur * 86_400_000
                        auto_lbl = _tr(f"{dur}d 限额", f"{dur}d limit")
                    elif "WEEK" in t_unit:
                        dur_ms = dur * 7 * 86_400_000
                        auto_lbl = _tr("7d 订阅限额", "Weekly limit") if dur == 1 else _tr(f"{dur}w 限额", f"{dur}w limit")
                    elif "MONTH" in t_unit:
                        dur_ms = dur * 30 * 86_400_000
                        auto_lbl = _tr("月度限额", "Monthly limit") if dur == 1 else _tr(f"{dur}mo 限额", f"{dur}mo limit")
                    else:
                        dur_ms = dur * 1000
                lbl = str(item.get("name") or item.get("title") or detail.get("name") or detail.get("title") or auto_lbl)
                windows.append(
                    _make_window(
                        f"limit_{idx}",
                        lbl,
                        (used_v / lim_v) * 100.0,
                        _kimi_reset_iso(detail, now_sec) or _kimi_reset_iso(item, now_sec),
                        dur_ms,
                    )
                )

        usages_map = body.get("usages")
        if isinstance(usages_map, dict):
            has_short = any((w.get("windowDurationMs") or 0) in range(1, 6 * 3_600_000 + 1) for w in windows)
            pool_5h = usages_map.get("limit_5h")
            if not has_short and isinstance(pool_5h, dict):
                ratio_5h = _safe_float(pool_5h.get("used_ratio") if "used_ratio" in pool_5h else pool_5h.get("usedRatio"))
                if ratio_5h is not None:
                    windows.append(
                        _make_window(
                            "limit_5h",
                            _tr("5h 限额", "5h limit"),
                            max(0.0, min(100.0, ratio_5h * 100.0)),
                            _kimi_reset_iso(pool_5h, now_sec),
                            5 * 3_600_000,
                        )
                    )
            has_long = any((w.get("windowDurationMs") or 0) >= 7 * 86_400_000 for w in windows)
            pool_mo = usages_map.get("limit_month_total") or usages_map.get("limit_month_code")
            if not has_long and isinstance(pool_mo, dict):
                ratio_mo = _safe_float(pool_mo.get("used_ratio") if "used_ratio" in pool_mo else pool_mo.get("usedRatio"))
                if ratio_mo is not None:
                    windows.append(
                        _make_window(
                            "monthly",
                            _tr("月度限额", "Monthly limit"),
                            max(0.0, min(100.0, ratio_mo * 100.0)),
                            _kimi_reset_iso(pool_mo, now_sec),
                            30 * 86_400_000,
                        )
                    )

        return {
            "cli": "Kimi Code",
            "planType": plan_label,
            "updatedAt": now_iso,
            "source": "live",
            "status": "ok",
            "message": None,
            "windows": windows,
            "credits": None,
        }
    except Exception as exc:
        return {
            "cli": "Kimi Code",
            "planType": "Kimi Code",
            "updatedAt": now_iso,
            "source": "unavailable",
            "status": "error",
            "message": _tr(f"读取 Kimi Code 限额失败：{type(exc).__name__}", f"Failed to read Kimi Code limits: {type(exc).__name__}"),
            "windows": [],
            "credits": None,
        }


def _has_claude_oauth(home: Path) -> bool:
    return _extract_claude_oauth(home) is not None

def _has_antigravity_oauth(home: Path) -> bool:
    return _extract_antigravity_oauth(home) is not None

def _has_grok_oauth(home: Path) -> bool:
    return _extract_grok_oauth(home) is not None

def _has_deepseek_auth(home: Path) -> bool:
    return _extract_deepseek_auth(home) is not None

def _has_kimi_oauth(home: Path) -> bool:
    return _extract_kimi_oauth(home) is not None


def collect_limits(fresh: bool = False, probe_timeout: float = 7.5) -> dict[str, Any]:
    cache_path = _cache_dir() / "usage-limits-cache-v4.json"
    now_sec = time.time()
    lang = _lang_code()
    if not fresh and cache_path.is_file():
        try:
            cached = json.loads(cache_path.read_text("utf-8"))
            if (
                isinstance(cached, dict)
                and cached.get("lang", "zh") == lang
                and now_sec - float(cached.get("cachedAtEpoch", 0)) < LIMITS_CACHE_TTL_SECONDS
            ):
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
        ("DeepSeek Harness", _has_deepseek_auth, _probe_deepseek_limits),
        ("Kimi Code", _has_kimi_oauth, _probe_kimi_limits),
    ]
    active_probes = [(name, fn) for name, has_oauth, fn in candidate_probes if has_oauth(home)]

    results_map: dict[str, dict[str, Any]] = {}
    timed_out = False
    if active_probes:
        task_res = _run_daemon_tasks(
            {name: (lambda p=p: p(home)) for name, p in active_probes},
            timeout=probe_timeout,
        )
        for name, _ in active_probes:
            if name not in task_res:
                timed_out = True
                results_map[name] = {
                    "cli": name,
                    "planType": None,
                    "updatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                    "source": "unavailable",
                    "status": "error",
                    "message": _tr("实时限额探测超时，请检查网络后刷新重试", "Live limit probe timed out; check network and refresh"),
                    "windows": [],
                    "credits": None,
                }
            else:
                ok, val = task_res[name]
                if ok:
                    results_map[name] = val
                else:
                    exc_name = str(val or "Exception")
                    results_map[name] = {
                        "cli": name,
                        "planType": None,
                        "updatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                        "source": "unavailable",
                        "status": "error",
                        "message": _tr(f"探测异常：{exc_name}", f"Probe error: {exc_name}"),
                        "windows": [],
                        "credits": None,
                    }

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
        "lang": lang,
        "providers": ordered,
    }
    if not timed_out:
        try:
            _atomic_write_text(cache_path, json.dumps(payload, ensure_ascii=False))
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

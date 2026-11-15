from __future__ import annotations

import http.client
import json
import math
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

try:
    from .i18n import tr
except ImportError:
    def tr(zh: str, en: str) -> str:
        return zh if os.environ.get("LUMA_LANG", "zh").strip().lower().startswith("zh") else en

TIMEOUT = 4
MAX_RESPONSE_BYTES = 1_048_576
AGENT = "luma-balance/1"
OFFICIAL_URL = "https://api.deepseek.com/user/balance"
ACCOUNT_URL = "https://platform.deepseek.com/api/v0/users/get_user_summary"
SYMBOLS = {"CNY": "¥", "USD": "$"}

def _home() -> Path:
    return Path(os.environ.get("DSH_HOME") or (Path.home() / ".dsh"))

def _read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return ""

def _api_key(text: str) -> str:
    match = re.search(r"(?:^|[{,\s])" + re.escape("DEEPSEEK_API_KEY") + r":\s*([^\s,}]+)", text, re.M)
    return match.group(1).strip("'\"") if match else ""

def _account_token(text: str) -> tuple[str, str]:
    lines = text.splitlines()
    start = next(((i, len(line) - len(line.lstrip())) for i, line in enumerate(lines)
                  if re.match(r"^\s*deepseek-account-platform/default:\s*$", line)), None)
    if start is None:
        return "", ""
    index, indent = start
    token = issuer = ""
    for line in lines[index + 1:]:
        stripped = line.strip()
        if stripped and (len(line) - len(line.lstrip())) <= indent:
            break
        value = re.match(r"^\s+token:\s*([^\s,}]+)", line)
        if value:
            token = value.group(1).strip("'\"")
        origin = re.match(r"^\s+issuer:\s*([^\s,}]+)", line)
        if origin:
            issuer = origin.group(1).strip("'\"").rstrip("/")
    return token, issuer or "https://platform.deepseek.com"

def _get(url: str, headers: dict[str, str]) -> dict:
    parsed_url = urllib.parse.urlparse(url)
    if parsed_url.scheme not in ("http", "https") or not parsed_url.netloc:
        raise ValueError("Invalid URL scheme")
    request = urllib.request.Request(url, headers={"User-Agent": AGENT, **headers})
    with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
        if response.status != 200:
            raise ValueError(f"HTTP {response.status}")
        raw = response.read(MAX_RESPONSE_BYTES + 1)
        if len(raw) > MAX_RESPONSE_BYTES:
            raise ValueError("Response too large")
        payload = json.loads(raw.decode("utf-8"))
        if not isinstance(payload, dict):
            raise ValueError("Invalid JSON payload")
        return payload

def _fetch_json(url: str, headers: dict[str, str]) -> tuple[dict | None, str | None]:
    try:
        return _get(url, headers), None
    except urllib.error.HTTPError as exc:
        code = exc.code
        try:
            exc.close()
        except Exception:
            pass
        return None, f"HTTP {code}"
    except (urllib.error.URLError, OSError, http.client.HTTPException):
        return None, tr("网络不可用", "Network unavailable")
    except ValueError as exc:
        return None, str(exc)[:80]

def _amount(value) -> str:
    if isinstance(value, bool):
        return "0.00"
    try:
        num = float(value)
        if not math.isfinite(num):
            return "0.00"
        if abs(num) < 0.005:
            num = 0.0
        return f"{num:.2f}"
    except (TypeError, ValueError):
        return "0.00"

def _money(currency: str, value) -> str:
    return f"{SYMBOLS.get(currency, currency + ' ')}{_amount(value)}"

def _official(text: str) -> dict:
    key = os.environ.get("DEEPSEEK_API_KEY") or _api_key(text)
    if not key:
        return {"status": "unavailable", "reason": tr("未找到 DEEPSEEK_API_KEY", "DEEPSEEK_API_KEY not found")}
    payload, reason = _fetch_json(OFFICIAL_URL, {"Authorization": "Bearer " + key})
    if payload is None:
        return {"status": "unavailable", "reason": reason}
    wallets = []
    raw_wallets = payload.get("balance_infos")
    for info in raw_wallets if isinstance(raw_wallets, list) else []:
        if not isinstance(info, dict):
            continue
        currency = str(info.get("currency") or "")
        wallets.append({
            "currency": currency,
            "total": _amount(info.get("total_balance")),
            "granted": _amount(info.get("granted_balance")),
            "toppedUp": _amount(info.get("topped_up_balance")),
        })
    return {"status": "ready", "available": bool(payload.get("is_available")), "wallets": wallets}

def _account(text: str) -> dict:
    token, origin = _account_token(text)
    if not token:
        return {"status": "unavailable", "reason": tr("未登录 DSH 账号", "Not signed in to DSH account")}
    payload, reason = _fetch_json(origin + "/api/v0/users/get_user_summary", {"x-dsh-auth-token": token})
    if payload is None:
        return {"status": "unavailable", "reason": reason}
    raw_data = payload.get("data")
    raw_biz = raw_data.get("biz_data") if isinstance(raw_data, dict) else None
    data = raw_biz if isinstance(raw_biz, dict) else {}
    def wallet(w):
        return {"currency": str(w.get("currency") or ""), "total": _amount(w.get("balance"))}
    normal_wallets = data.get("normal_wallets")
    bonus_wallets = data.get("bonus_wallets")
    total_costs = data.get("total_costs")
    wallets = [wallet(w) for w in (normal_wallets if isinstance(normal_wallets, list) else []) if isinstance(w, dict)]
    bonus = [wallet(w) for w in (bonus_wallets if isinstance(bonus_wallets, list) else []) if isinstance(w, dict)]
    spent = [{"currency": str(c.get("currency") or ""), "amount": _amount(c.get("amount"))}
             for c in (total_costs if isinstance(total_costs, list) else []) if isinstance(c, dict)]
    return {"status": "ready", "wallets": wallets, "bonus": bonus, "spent": spent}

def money(currency: str, value: str) -> str:
    return _money(currency, value)

def official_rows(official: dict) -> list[dict]:
    base_label = tr("API Key 余额", "API key balance")
    if official.get("status") != "ready":
        return [{"label": base_label, "value": tr("不可用", "Unavailable")}]
    wallets = official.get("wallets") or []
    if not wallets:
        return [{"label": base_label, "value": tr("无钱包", "No wallet")}]
    return [{"label": f"{base_label} · {w.get('currency') or ''}", "value": money(w.get("currency") or "", w.get("total"))}
            for w in wallets]

def account_rows(account: dict) -> list[dict]:
    base_label = tr("账户余额", "Account balance")
    if account.get("status") != "ready":
        return [{"label": base_label, "value": tr("不可用", "Unavailable")}]
    rows = [{"label": f"{base_label} · {w.get('currency') or ''}", "value": money(w.get("currency") or "", w.get("total"))}
            for w in account.get("wallets") or []]
    return rows or [{"label": base_label, "value": tr("无钱包", "No wallet")}]

def rows(official: dict, account: dict) -> list[dict]:
    if account.get("status") == "ready" and account.get("wallets"):
        return account_rows(account)
    if official.get("status") == "ready" and official.get("wallets"):
        return official_rows(official)
    return [{"label": tr("余额", "Balance"), "value": tr("不可用", "Unavailable")}]

def official_result() -> dict:
    return _official(_read(_home() / ".credentials.yaml"))

def account_result() -> dict:
    return _account(_read(_home() / ".credentials.yaml"))

def collect() -> dict:
    official, account = official_result(), account_result()
    return {"official": official, "account": account, "rows": rows(official, account)}

def main() -> None:
    if len(sys.argv) > 1 and sys.argv[1] != "read":
        raise SystemExit("usage: balances.py read")
    print(json.dumps(collect(), ensure_ascii=False))

if __name__ == "__main__":
    main()

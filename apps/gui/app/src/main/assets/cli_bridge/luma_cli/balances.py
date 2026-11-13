from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

TIMEOUT = 4
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
    request = urllib.request.Request(url, headers={"User-Agent": AGENT, **headers})
    with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
        if response.status != 200:
            raise ValueError(f"HTTP {response.status}")
        return json.loads(response.read().decode("utf-8"))

def _amount(value) -> str:
    try:
        return f"{float(value):.2f}"
    except (TypeError, ValueError):
        return "0.00"

def _money(currency: str, value) -> str:
    return f"{SYMBOLS.get(currency, currency + ' ')}{_amount(value)}"

def _official(text: str) -> dict:
    key = os.environ.get("DEEPSEEK_API_KEY") or _api_key(text)
    if not key:
        return {"status": "unavailable", "reason": "未找到 DEEPSEEK_API_KEY"}
    try:
        payload = _get(OFFICIAL_URL, {"Authorization": "Bearer " + key})
    except (ValueError, urllib.error.URLError, urllib.error.HTTPError, OSError, json.JSONDecodeError) as exc:
        reason = "网络不可用" if isinstance(exc, (urllib.error.URLError, OSError)) else str(exc)[:80]
        return {"status": "unavailable", "reason": reason}
    wallets = []
    for info in payload.get("balance_infos") or []:
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
        return {"status": "unavailable", "reason": "未登录 DSH 账号"}
    try:
        payload = _get(origin + "/api/v0/users/get_user_summary", {"x-dsh-auth-token": token})
    except (ValueError, urllib.error.URLError, urllib.error.HTTPError, OSError, json.JSONDecodeError) as exc:
        reason = "网络不可用" if isinstance(exc, (urllib.error.URLError, OSError)) else str(exc)[:80]
        return {"status": "unavailable", "reason": reason}
    data = (payload.get("data") or {}).get("biz_data") or {}
    def wallet(w):
        return {"currency": str(w.get("currency") or ""), "total": _amount(w.get("balance"))}
    wallets = [wallet(w) for w in (data.get("normal_wallets") or []) if isinstance(w, dict)]
    bonus = [wallet(w) for w in (data.get("bonus_wallets") or []) if isinstance(w, dict)]
    spent = [{"currency": str(c.get("currency") or ""), "amount": _amount(c.get("amount"))}
             for c in (data.get("total_costs") or []) if isinstance(c, dict)]
    return {"status": "ready", "wallets": wallets, "bonus": bonus, "spent": spent}

def money(currency: str, value: str) -> str:
    try:
        return f"{SYMBOLS.get(currency, currency + ' ')}{float(value):.2f}"
    except (TypeError, ValueError):
        return SYMBOLS.get(currency, currency + " ") + "0.00"

def official_rows(official: dict) -> list[dict]:
    if official.get("status") != "ready":
        return [{"label": "API Key 余额", "value": "不可用"}]
    wallets = official.get("wallets") or []
    if not wallets:
        return [{"label": "API Key 余额", "value": "无钱包"}]
    return [{"label": f"API Key 余额 · {w.get('currency') or ''}", "value": money(w.get("currency") or "", w.get("total"))}
            for w in wallets]

def account_rows(account: dict) -> list[dict]:
    if account.get("status") != "ready":
        return [{"label": "账户余额", "value": "不可用"}]
    rows = [{"label": f"账户余额 · {w.get('currency') or ''}", "value": money(w.get("currency") or "", w.get("total"))}
            for w in account.get("wallets") or []]
    return rows or [{"label": "账户余额", "value": "无钱包"}]

def rows(official: dict, account: dict) -> list[dict]:
    if account.get("status") == "ready" and account.get("wallets"):
        return account_rows(account)
    if official.get("status") == "ready" and official.get("wallets"):
        return official_rows(official)
    return [{"label": "余额", "value": "不可用"}]

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

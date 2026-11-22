import copy
import json
import os
import pathlib
import re
import shutil
import stat
import subprocess
import threading
import time
import urllib.parse
from .agy_stream import AgyPeer
from .base import Session, item, parse_ts_ms
from .i18n import lang_code, tr
from .media import display_tool_output
from .transport import Rejected
from .metrics import agy_usage, agy_steps, agy_session_metrics

PROTOCOL = "antigravity-cli-stream-json"
FULL_ACCESS = "always-proceed"
_ID = re.compile(r"[a-zA-Z0-9_-]{1,128}")
_CHILD_TERMINAL = {"succeeded", "failed", "cancelled", "closed"}
_LOG_TAIL = ("brain", ".system_generated", "logs", "transcript.jsonl")
_ROW_INDEX = re.compile(r"agy:(.+):(\d+):(agent_response|tool)")
_SWARM_BOARD_PING_RE = re.compile(r"^\[swarm\] new board message #\d+$")
_SWARM_MENTION_RE = re.compile(r"(?<![A-Za-z0-9_-])@([A-Za-z0-9_.-]*[A-Za-z0-9_-])")
_SWARM_STATUS_MAP = {
    "working": ("running", "swarm:working"),
    "waiting": ("succeeded", "swarm:waiting"),
    "stopped": ("succeeded", "swarm:stopped"),
    "killed": ("closed", "swarm:killed"),
}
STREAM_STALL_SECONDS = 3.0

def native_error(value):
    message = value if isinstance(value, str) else tr("agy 返回了错误", "agy returned an error")
    if re.search(r"auth|sign.?in|log.?in", message, re.I):
        return tr("agy 尚未登录；请在当前运行环境的终端运行 agy，完成原生登录后重连。",
                  "agy is not signed in; run `agy` in a terminal of the current environment to sign in, then reconnect.")
    return message

def json_values(text):
    if not isinstance(text, str): return
    decoder, index = json.JSONDecoder(), 0
    while True:
        start = text.find("{", index)
        if start < 0: return
        try: value, index = decoder.raw_decode(text, start)
        except json.JSONDecodeError: index = start + 1
        else:
            if isinstance(value, dict): yield value

def invoke_child(output):
    for value in json_values(output):
        ident = value.get("conversationId")
        if isinstance(ident, str) and _ID.fullmatch(ident):
            uri = value.get("logAbsoluteUri")
            return ident, uri if isinstance(uri, str) else ""
    return "", ""

def _iter_json_dicts(output):
    if isinstance(output, dict):
        yield output
        return
    if isinstance(output, str):
        yield from json_values(output)

def _parse_json_dict(raw):
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str) and raw:
        try:
            value = json.loads(raw)
        except (ValueError, TypeError):
            return {}
        return value if isinstance(value, dict) else {}
    return {}

def _swarm_role(params):
    ws = params.get("workspace") if isinstance(params, dict) and isinstance(params.get("workspace"), str) else ""
    return f"swarm · {ws}" if ws in ("share", "branch") else "swarm"

def _swarm_task(brief, slice_text):
    if slice_text and brief:
        return f"{slice_text}\n\n{brief}"
    return slice_text or brief

def swarm_added_workers(params, output):
    params = params if isinstance(params, dict) else {}
    base_name = params.get("name") if isinstance(params.get("name"), str) else ""
    brief = params.get("brief") if isinstance(params.get("brief"), str) else ""
    param_model = params.get("model") if isinstance(params.get("model"), str) else ""
    tasks = params.get("tasks") if isinstance(params.get("tasks"), list) else None
    n = params.get("n")
    role = _swarm_role(params)
    workers, seen_ids = [], set()
    single_index = 0
    is_multi_param = bool(base_name) and (isinstance(tasks, list) or (isinstance(n, int) and n > 1))
    for value in _iter_json_dicts(output):
        if value.get("error"):
            continue
        resolved_model = value.get("model") if isinstance(value.get("model"), str) else param_model
        cids = value.get("conversation_ids")
        if isinstance(cids, list) and cids:
            names = value.get("names") if isinstance(value.get("names"), list) else []
            group = base_name if (is_multi_param or (bool(base_name) and len(cids) > 1)) else ""
            for idx, cid in enumerate(cids):
                if not isinstance(cid, str) or not _ID.fullmatch(cid) or cid in seen_ids:
                    continue
                seen_ids.add(cid)
                wname = names[idx] if idx < len(names) and isinstance(names[idx], str) and names[idx] else (
                    f"{base_name}-{idx + 1}" if base_name else ""
                )
                slice_raw = tasks[idx] if tasks and idx < len(tasks) and isinstance(tasks[idx], str) else ""
                workers.append({
                    "id": cid, "name": wname, "role": role,
                    "task": _swarm_task(brief, slice_raw.strip()),
                    "model": resolved_model, "group": group,
                })
            continue
        cid = value.get("conversation_id")
        if isinstance(cid, str) and _ID.fullmatch(cid) and cid not in seen_ids:
            seen_ids.add(cid)
            wname = value.get("name") if isinstance(value.get("name"), str) and value.get("name") else (
                f"{base_name}-{single_index + 1}" if is_multi_param and base_name else base_name
            )
            slice_raw = tasks[single_index] if tasks and single_index < len(tasks) and isinstance(tasks[single_index], str) else ""
            workers.append({
                "id": cid, "name": wname, "role": role,
                "task": _swarm_task(brief, slice_raw.strip()),
                "model": resolved_model, "group": base_name if is_multi_param else "",
            })
            single_index += 1
    return workers

def swarm_status_updates(output):
    updates = []
    for value in _iter_json_dicts(output):
        if value.get("error"):
            continue
        members = value.get("members")
        if not isinstance(members, list):
            continue
        for member in members:
            if not isinstance(member, dict) or member.get("role") == "coordinator":
                continue
            name = member.get("name") if isinstance(member.get("name"), str) else ""
            cid = member.get("conversation_id")
            ident = cid if isinstance(cid, str) and _ID.fullmatch(cid) else ""
            if not name and not ident:
                continue
            model = member.get("model") if isinstance(member.get("model"), str) else ""
            killed = bool(member.get("killed"))
            raw_state = member.get("state") if isinstance(member.get("state"), str) else ""
            if killed or raw_state == "killed":
                status, native_status = "closed", "swarm:killed"
            elif raw_state in _SWARM_STATUS_MAP:
                status, native_status = _SWARM_STATUS_MAP[raw_state]
            else:
                status, native_status = "", ""
            updates.append({
                "id": ident, "name": name, "model": model,
                "status": status, "nativeStatus": native_status,
            })
    return updates

def swarm_model_update(params, output):
    params = params if isinstance(params, dict) else {}
    parsed = False
    for value in _iter_json_dicts(output):
        parsed = True
        if value.get("error"):
            return "", ""
        name = value.get("name") if isinstance(value.get("name"), str) else ""
        model = value.get("model") if isinstance(value.get("model"), str) else ""
        if model and (name or isinstance(params.get("name"), str)):
            return name or (params.get("name") or ""), model
    if parsed:
        return "", ""
    name = params.get("name") if isinstance(params.get("name"), str) else ""
    model = params.get("model") if isinstance(params.get("model"), str) else ""
    return (name, model) if model else ("", "")

def swarm_kill_target(params, output):
    params = params if isinstance(params, dict) else {}
    parsed = False
    for value in _iter_json_dicts(output):
        parsed = True
        if value.get("error"):
            return ""
        name = value.get("name") if isinstance(value.get("name"), str) else ""
        if name or value.get("status") == "killed":
            return name or (params.get("name") if isinstance(params.get("name"), str) else "")
    if parsed:
        return ""
    return params.get("name") if isinstance(params.get("name"), str) else ""

def swarm_mentions(text):
    if not isinstance(text, str) or "@" not in text:
        return [], False
    names = _SWARM_MENTION_RE.findall(text)
    return names, ("all" in names)

def child_last_step_index(steps):
    if not steps:
        return -1
    last = steps[-1]
    if isinstance(last, dict) and type(last.get("step_index")) is int:
        return last["step_index"]
    return len(steps) - 1

def child_transcript_detail(messages, reply_detail=""):
    for message in reversed(messages):
        if message.get("role") == "assistant" and message.get("text"):
            return message["text"][:500]
        if message.get("status") == "running" and message.get("toolName"):
            return message["toolName"]
    for message in reversed(messages):
        if message.get("role") == "tool" and message.get("toolName") == "post_message" and message.get("status") != "failed":
            parsed = _parse_json_dict(message.get("input"))
            text_val = parsed.get("text")
            if isinstance(text_val, str) and text_val.strip():
                return text_val.strip()[:500]
    return reply_detail

def child_log_path(ident, uri):
    if not _ID.fullmatch(ident or ""): return ""
    candidates = []
    if isinstance(uri, str) and uri.startswith("file://"):
        raw = urllib.parse.unquote(uri[len("file://"):])
        if raw.startswith("/") and not raw.startswith("//"): candidates.append(pathlib.Path(raw))
    homes = []
    for key in ("GEMINI_HOME",):
        if os.environ.get(key): homes.append(pathlib.Path(os.environ[key]))
    homes.append(pathlib.Path.home() / ".gemini")
    for home in homes:
        root = home if home.name == "antigravity-cli" else home / "antigravity-cli"
        candidates.append(root / "brain" / ident / ".system_generated" / "logs" / "transcript.jsonl")
    for path in candidates:
        try: resolved = path.resolve(strict=False)
        except OSError: continue
        parts = resolved.parts
        if len(parts) >= 5 and parts[-5] == "brain" and parts[-4] == ident and parts[-3:] == _LOG_TAIL[1:]:
            return str(resolved)
    return ""

def transcript_args(call):
    args = call.get("args") if isinstance(call.get("args"), dict) else {}
    decoded = {}
    for key, value in args.items():
        try: decoded[key] = json.loads(value) if isinstance(value, str) else value
        except (ValueError, TypeError): decoded[key] = value
    return decoded

def read_full_step(path, target_index):
    if type(target_index) is not int: return None
    try:
        with open(path, "rb") as handle:
            for line in handle:
                try: value = json.loads(line)
                except ValueError: continue
                if isinstance(value, dict) and value.get("step_index") == target_index:
                    return value
    except OSError: pass
    return None

def read_transcript(path):
    try:
        with open(path, "rb") as handle:
            handle.seek(0, 2)
            size = handle.tell()
            if size > 2_000_000:
                handle.seek(-2_000_000, 2); handle.readline()
            else: handle.seek(0)
            raw = handle.read()
    except OSError: return []
    steps = []
    for line in raw.decode("utf-8", "replace").splitlines():
        try: value = json.loads(line)
        except ValueError: continue
        if isinstance(value, dict): steps.append(value)
    return steps

def child_transcript_state(steps):
    idx = len(steps) - 1
    while idx >= 0:
        step = steps[idx]
        kind, state = step.get("type"), step.get("status")
        if kind == "ERROR_MESSAGE": return "failed"
        if kind in ("SYSTEM_MESSAGE", "USER_INPUT"): return "running"
        if kind == "PLANNER_RESPONSE":
            return "idle" if state == "DONE" and not step.get("tool_calls") else "running"
        if kind == "GENERIC":
            start = idx
            while start >= 0 and steps[start].get("type") == "GENERIC":
                start -= 1
            trailing = steps[start + 1 : idx + 1]
            if any(g.get("status") in ("RUNNING", "ACTIVE") for g in trailing):
                return "running"
            if start >= 0 and steps[start].get("type") == "PLANNER_RESPONSE" and steps[start].get("status") == "DONE":
                calls = steps[start].get("tool_calls")
                if isinstance(calls, list) and len(calls) > 0 and len(trailing) == len(calls):
                    wait_indices = [i for i, c in enumerate(calls) if isinstance(c, dict) and c.get("name") == "swarm_wait"]
                    if wait_indices and all(trailing[i].get("status") == "DONE" for i in wait_indices):
                        return "idle"
            return "running"
        idx -= 1
    return ""

_AGY_DIRECTIVE_RE = re.compile(
    r"(?m)^[ \t]*YOU MUST TAKE ONE OF THE FOLLOWING TWO ACTIONS:[\s\S]*?(?:Never mix two or more languages in the same response\.?|\Z)"
)
_AGY_BG_META_RE = re.compile(
    r"^(?:(?:Created|Completed|Updated) At:\s+\d{4}-\d{2}-\d{2}T\S+|Task Description:\s+.*|Tool is running as a background task with task id:\s*\S+.*|Task logs are available at:\s*file://\S+.*)$"
)
_AGY_TASK_RESULT_HEADER_RE = re.compile(
    r'^Task id "[^"]+" [^\n]*?(?::\s*\n|\n|$)'
)

_BG_LAUNCH_TOOLS = {"run_command", "schedule", "run_workflow"}
_BG_HEADER_LINE_RE = re.compile(r"^Tool is running as a background task with task id:\s*([A-Za-z0-9_./:-]{1,200})\s*$")
_BG_LOG_URI_RE = re.compile(r"^Task logs are available at:\s*file://(\S+)\s*$")
_CRON_CANCELLED_MSG_RE = re.compile(r'^Cron schedule "([^"]+)" cancelled\b', re.M)
_TASK_KILLED_MSG_RE = re.compile(r'^Task id "[^"]+" (?:killed|was cancel+ed|terminated|aborted)\b', re.I)

_AGY_CREATED_AT_RE = re.compile(r"(?m)^Created At:\s+(\d{4}-\d{2}-\d{2}T\S+)")
_AGY_COMPLETED_AT_RE = re.compile(r"(?m)^Completed At:\s+(\d{4}-\d{2}-\d{2}T\S+)")

def extract_agy_tool_timestamps(raw):
    body, _ = unwrap_agy_tool_text(raw)
    if not body:
        return 0, 0
    head = body[:512]
    cm = _AGY_CREATED_AT_RE.search(head)
    dm = _AGY_COMPLETED_AT_RE.search(head)
    return (parse_ts_ms(cm.group(1)) if cm else 0), (parse_ts_ms(dm.group(1)) if dm else 0)

def unwrap_agy_tool_text(raw):
    if not isinstance(raw, str) or not raw.strip():
        return "", False
    stripped = raw.strip()
    if stripped.startswith("{"):
        try:
            parsed = json.loads(stripped)
        except (ValueError, TypeError):
            parsed = None
        if isinstance(parsed, dict):
            has_exit = parsed.get("exit_code") is not None
            out = parsed.get("output")
            return (out if isinstance(out, str) else ""), has_exit
    return raw, False

def extract_agy_bg_task_id(raw, tool_name="", generic_state=None):
    if generic_state is not None and generic_state not in ("RUNNING", "ACTIVE"):
        return ""
    if tool_name and tool_name not in _BG_LAUNCH_TOOLS:
        return ""
    body, has_exit = unwrap_agy_tool_text(raw)
    if has_exit or not body.strip():
        return ""
    header_lines = [ln.strip() for ln in body.lstrip().splitlines()[:4]]
    if any(
        ln.startswith(("Completed At:", "Command exited with code", "The tool's output was truncated", "Showing lines "))
        for ln in header_lines
    ):
        return ""
    candidates = header_lines[:2]
    if len(candidates) == 2 and not candidates[0].startswith("Created At:"):
        candidates = candidates[:1]
    for ln in candidates:
        match = _BG_HEADER_LINE_RE.match(ln)
        if match:
            return match.group(1)
    return ""

def extract_agy_bg_log_path(raw):
    body, _ = unwrap_agy_tool_text(raw)
    if not body or "file://" not in body:
        return ""
    for ln in body.lstrip().splitlines()[:8]:
        match = _BG_LOG_URI_RE.match(ln.strip())
        if match:
            path = urllib.parse.unquote(match.group(1)).strip()
            if path.startswith("/"):
                return path
    return ""

def read_agy_bg_log_tail(log_path, prev_stat=None, max_bytes=16384):
    if not isinstance(log_path, str) or not log_path.startswith("/"):
        return None, prev_stat
    try:
        st = os.stat(log_path)
    except OSError:
        return None, prev_stat
    if not stat.S_ISREG(st.st_mode):
        return None, prev_stat
    sig = (st.st_mtime_ns, st.st_size)
    if sig == prev_stat:
        return None, prev_stat
    if st.st_size <= 0:
        return "", sig
    try:
        with open(log_path, "rb") as handle:
            start = max(0, st.st_size - max_bytes)
            if start > 0:
                handle.seek(start)
            raw = handle.read(max_bytes)
    except OSError:
        return None, prev_stat
    if start > 0:
        cut_nl = raw.find(b"\n")
        cut_cr = raw.find(b"\r")
        cuts = [c for c in (cut_nl, cut_cr) if c >= 0]
        if cuts:
            raw = raw[min(cuts) + 1:]
    decoded = raw.decode("utf-8", errors="replace").replace("\r\n", "\n")
    out_lines = []
    for line in decoded.split("\n"):
        if "\r" in line:
            parts = [part for part in line.split("\r") if part]
            out_lines.append(parts[-1] if parts else "")
        else:
            out_lines.append(line)
    cleaned = "\n".join(out_lines).rstrip()
    if len(cleaned) > 16000:
        cleaned = cleaned[-16000:]
    return cleaned, sig

def is_agy_oneshot_timer(raw_outcome, tool_name="", params=None):
    body, _ = unwrap_agy_tool_text(raw_outcome)
    if "Task Description: Timer:" in body:
        return True
    params = params if isinstance(params, dict) else {}
    return tool_name == "schedule" and bool(params.get("DurationSeconds")) and not params.get("CronExpression")

def agy_timer_condition(params):
    if not isinstance(params, dict):
        return ""
    cond = str(params.get("TimerCondition") or "").strip()
    return "" if not cond or cond.lower() == "never" else cond

def _matching_bg_task_ids(task_id, bg_tasks):
    if not task_id:
        return []
    if task_id in bg_tasks:
        return [task_id]
    suffix = "/" + task_id
    return [tid for tid in bg_tasks if tid.endswith(suffix)]

def _settle_terminal_child_messages(messages, child_status):
    if child_status not in ("closed", "cancelled", "failed") or not isinstance(messages, list):
        return messages
    settled = []
    changed = False
    for msg in messages:
        if isinstance(msg, dict) and msg.get("status") in ("running", "in_progress", "pending"):
            settled.append({**msg, "status": "cancelled"})
            changed = True
        else:
            settled.append(msg)
    return settled if changed else messages

def clean_agy_tool_output(raw):
    if not isinstance(raw, str) or not raw.strip():
        return ""
    unwrapped, has_exit = unwrap_agy_tool_text(raw)
    if unwrapped or has_exit or (raw.strip().startswith("{") and '"output"' in raw):
        body = unwrapped
    else:
        body = raw
    body = body.replace("\r\n", "\n").replace("\r", "\n")
    if not body.strip():
        return ""
    has_bg = (
        "Tool is running as a background task with task id:" in body
        or "Task logs are available at: file://" in body
        or "YOU MUST TAKE ONE OF THE FOLLOWING TWO ACTIONS:" in body
    )
    if not has_bg:
        return body.rstrip() if body.strip() else ""
    stripped = _AGY_DIRECTIVE_RE.sub("", body)
    lines = [line for line in stripped.splitlines() if not _AGY_BG_META_RE.match(line.strip())]
    joined = "\n".join(lines).rstrip()
    return joined if joined.strip() else ""

def extract_agy_task_result(body):
    if not isinstance(body, str) or not body.strip():
        return ""
    rest = _AGY_TASK_RESULT_HEADER_RE.sub("", body, count=1)
    return clean_agy_tool_output(rest)

def parse_system_message(content):
    if not isinstance(content, str): return "", ""
    blocks = re.findall(r"<SYSTEM_MESSAGE>\s*([\s\S]*?)\s*</SYSTEM_MESSAGE>", content)
    raw = (blocks[-1].rsplit("<SYSTEM_MESSAGE>", 1)[-1] if blocks else content).strip()
    match = re.match(r"^\[Message\][^\n]*?\bsender=(\S+)(?:\s+priority=\S+)?\s+content=([\s\S]*)$", raw)
    if match: return match.group(1), match.group(2).strip()
    return "", raw

def project_child_transcript(ident, steps):
    messages, open_tools = [], []
    bg_tasks, oneshot_timers, conditional_timers = {}, set(), {}

    def settle_open_tools(target_status):
        while open_tools:
            msg_idx, _, _ = open_tools.pop(0)
            if 0 <= msg_idx < len(messages) and messages[msg_idx].get("status") in ("running", "in_progress", "pending"):
                messages[msg_idx] = {**messages[msg_idx], "status": target_status}

    def settle_bg(tid, target_status="completed", extra_outcome="", settled_at_ms=0):
        oneshot_timers.discard(tid)
        conditional_timers.pop(tid, None)
        msg_idx = bg_tasks.pop(tid, None)
        if msg_idx is not None and 0 <= msg_idx < len(messages):
            cur = messages[msg_idx]
            updates = {}
            if cur.get("status") in ("running", "in_progress", "pending"):
                updates["status"] = target_status
                if settled_at_ms > 0:
                    updates["completedAtMs"] = settled_at_ms
            if extra_outcome:
                prev = (cur.get("outcome") or "").strip()
                updates["outcome"] = f"{prev}\n{extra_outcome}" if prev and extra_outcome not in prev else extra_outcome
            if updates:
                messages[msg_idx] = {**cur, **updates}

    def handle_manage_task(params, raw_output, settled_at_ms=0):
        if not isinstance(params, dict):
            return
        action = params.get("Action")
        task_id = params.get("TaskId") if isinstance(params.get("TaskId"), str) else ""
        lower_out = (raw_output or "").lower()
        if action == "kill" and task_id:
            if not any(err in lower_out for err in ("invalid", "failed", "error")):
                for matched in _matching_bg_task_ids(task_id, bg_tasks):
                    settle_bg(matched, "cancelled", settled_at_ms=settled_at_ms)
        elif action == "status" and task_id:
            if any(term in lower_out for term in ("completed", "finished", "exited", "not running", "not found", "cancelled", "canceled", "killed")):
                t_state = "cancelled" if ("cancel" in lower_out or "killed" in lower_out) else "completed"
                for matched in _matching_bg_task_ids(task_id, bg_tasks):
                    settle_bg(matched, t_state, settled_at_ms=settled_at_ms)
        elif action == "list":
            if "no running" in lower_out or "no background" in lower_out:
                for tid in list(bg_tasks):
                    settle_bg(tid, "completed", settled_at_ms=settled_at_ms)
                return
            try:
                parsed = json.loads(raw_output or "")
            except (ValueError, TypeError):
                return
            if isinstance(parsed, list):
                live = {
                    str(entry.get("taskId") or entry.get("TaskId") or entry.get("id") or "")
                    for entry in parsed
                    if isinstance(entry, dict)
                }
                live.discard("")
                for tid in list(bg_tasks):
                    if tid not in live and not any(tid.endswith("/" + l) or l.endswith("/" + tid) for l in live):
                        settle_bg(tid, "completed", settled_at_ms=settled_at_ms)

    for step in steps:
        if not isinstance(step, dict): continue
        index, kind, state = step.get("step_index"), step.get("type"), step.get("status")
        step_ts = parse_ts_ms(step.get("created_at"))
        status = "running" if state in ("RUNNING", "ACTIVE") else "failed" if state == "ERROR" else "completed"
        base = f"agy:{ident}:{index}:{kind}"
        if kind in ("PLANNER_RESPONSE", "USER_INPUT", "SYSTEM_MESSAGE", "ERROR_MESSAGE") and open_tools:
            settle_open_tools("failed" if kind == "ERROR_MESSAGE" else "cancelled")
        if kind == "PLANNER_RESPONSE":
            thinking = step.get("thinking")
            calls = [c for c in (step.get("tool_calls") if isinstance(step.get("tool_calls"), list) else []) if isinstance(c, dict)]
            if isinstance(thinking, str) and thinking:
                think_status = "completed" if calls or state == "DONE" else status
                messages.append(item(base + ":think", "tool", thinking[:16000], kind="reasoning", status=think_status, turnId=ident,
                                     timestampMs=step_ts, completedAtMs=step_ts if think_status != "running" else 0))
            if calls:
                for number, call in enumerate(calls):
                    name = call.get("name") if isinstance(call.get("name"), str) else ""
                    args = transcript_args(call)
                    raw = json.dumps(args, ensure_ascii=False)
                    msg_idx = len(messages)
                    messages.append(item(f"{base}:tool:{number}", "tool", name, kind="tool", toolName=name,
                        input=raw[:16000], status="failed" if status == "failed" else "running", turnId=ident, timestampMs=step_ts))
                    open_tools.append((msg_idx, name, args))
                continue
            content = step.get("content") if isinstance(step.get("content"), str) else ""
            if content:
                messages.append(item(base + ":text", "assistant", content[:16000], status=status, turnId=ident, timestampMs=step_ts))
        elif kind == "GENERIC":
            content = step.get("content") if isinstance(step.get("content"), str) else ""
            if open_tools:
                msg_idx, cname, decoded = open_tools.pop(0)
                cleaned = clean_agy_tool_output(content)
                t_created, t_completed = extract_agy_tool_timestamps(content)
                tid = extract_agy_bg_task_id(content, cname, generic_state=state)
                tool_status = "running" if tid else ("running" if state in ("RUNNING", "ACTIVE") else status)
                if 0 <= msg_idx < len(messages):
                    cur_msg = messages[msg_idx]
                    start_ts = cur_msg.get("timestampMs") or t_created or step_ts
                    done_ts = t_completed or step_ts
                    updates = {"outcome": cleaned[:16000], "status": tool_status}
                    if start_ts > 0:
                        updates["timestampMs"] = start_ts
                    if tool_status not in ("running", "in_progress", "pending") and done_ts > 0:
                        updates["completedAtMs"] = done_ts
                    messages[msg_idx] = {**cur_msg, **updates}
                if tid:
                    bg_tasks[tid] = msg_idx
                    if is_agy_oneshot_timer(content, cname, decoded):
                        oneshot_timers.add(tid)
                        cond = agy_timer_condition(decoded)
                        if cond:
                            conditional_timers[tid] = cond
                if cname == "manage_task" and state == "DONE":
                    handle_manage_task(decoded, content, settled_at_ms=step_ts)
            elif content:
                messages.append(item(base + ":out", "assistant", content[:16000], status=status, turnId=ident, timestampMs=step_ts))
        elif kind == "USER_INPUT":
            content = step.get("content") if isinstance(step.get("content"), str) else ""
            if content: messages.append(item(base + ":user", "user", content[:16000], status="completed", turnId=ident, timestampMs=step_ts))
        elif kind == "ERROR_MESSAGE":
            content = step.get("content") if isinstance(step.get("content"), str) else ""
            messages.append(item(base + ":error", "assistant", content[:16000] or tr("agy 子代理错误", "agy subagent error"), status="failed", turnId=ident, timestampMs=step_ts))
        elif kind == "SYSTEM_MESSAGE":
            sender, body = parse_system_message(step.get("content"))
            if "All your subagents and background tasks have been stopped" in body:
                for tid in list(bg_tasks):
                    settle_bg(tid, "cancelled", settled_at_ms=step_ts)
            for cron_match in _CRON_CANCELLED_MSG_RE.finditer(body):
                for matched in _matching_bg_task_ids(cron_match.group(1).strip(), bg_tasks):
                    settle_bg(matched, "completed", settled_at_ms=step_ts)
            if sender and sender in bg_tasks:
                if sender in oneshot_timers:
                    settle_bg(sender, "completed", settled_at_ms=step_ts)
                else:
                    t_status = "cancelled" if _TASK_KILLED_MSG_RE.match(body) else "completed"
                    settle_bg(sender, t_status, extract_agy_task_result(body)[:16000], settled_at_ms=step_ts)
            elif conditional_timers and (sender or (body and not _SWARM_BOARD_PING_RE.match(body.strip()))):
                for tid, cond in list(conditional_timers.items()):
                    if cond.lower() == "any" or (sender and sender == cond):
                        settle_bg(tid, "completed", settled_at_ms=step_ts)
            if body and not _SWARM_BOARD_PING_RE.match(body.strip()):
                messages.append(item(base + ":system", "user", body[:16000], status="completed", turnId=ident, timestampMs=step_ts))
    return messages[-500:]

class AgyChildTailMixin:
    def _swarm_state(self):
        with self.lock:
            groups = getattr(self, "swarm_groups", None)
            if groups is None:
                groups = {}
                self.swarm_groups = groups
            workers = getattr(self, "swarm_workers", None)
            if workers is None:
                workers = set()
                self.swarm_workers = workers
            revived = getattr(self, "swarm_revived_steps", None)
            if revived is None:
                revived = {}
                self.swarm_revived_steps = revived
            stopped = getattr(self, "swarm_stopped_steps", None)
            if stopped is None:
                stopped = {}
                self.swarm_stopped_steps = stopped
            highs = getattr(self, "child_step_highs", None)
            if highs is None:
                highs = {}
                self.child_step_highs = highs
        return groups, workers, revived, stopped, highs

    def _is_swarm_subagent(self, cid, agent):
        _, worker_set, _, _, _ = self._swarm_state()
        if cid in worker_set:
            return True
        role = agent.get("role") or ""
        return role == "swarm" or role.startswith("swarm · ")

    def _resolve_swarm_member_ids(self, name="", ident=""):
        if ident and ident in self.subagents:
            return [ident]
        if not name:
            return []
        exact = [cid for cid, agent in self.subagents.items() if agent.get("name") == name]
        if len(exact) > 1:
            live = [
                cid for cid in exact
                if self.subagents[cid].get("status") != "closed"
                and self.subagents[cid].get("nativeStatus") not in ("swarm:killed", "manage_subagents")
            ]
            return [live[-1]] if live else [exact[-1]]
        return exact

    def _resolve_swarm_mention_ids(self, targets, mentions_all):
        groups, _, _, _, _ = self._swarm_state()
        matched, seen = [], set()
        if mentions_all:
            for cid, agent in self.subagents.items():
                if not self._is_swarm_subagent(cid, agent):
                    continue
                if agent.get("status") == "closed" or agent.get("nativeStatus") in ("swarm:killed", "manage_subagents"):
                    continue
                if cid not in seen:
                    seen.add(cid)
                    matched.append(cid)
            return matched
        for target in targets:
            for cid in groups.get(target, ()):
                agent = self.subagents.get(cid)
                if (agent and cid not in seen and agent.get("status") != "closed"
                        and agent.get("nativeStatus") not in ("swarm:killed", "manage_subagents")):
                    seen.add(cid)
                    matched.append(cid)
            exact, prefixed = [], []
            prefix = target + "-"
            for cid, agent in self.subagents.items():
                if not self._is_swarm_subagent(cid, agent):
                    continue
                if agent.get("status") == "closed" or agent.get("nativeStatus") in ("swarm:killed", "manage_subagents"):
                    continue
                wname = agent.get("name") or ""
                if wname == target:
                    exact.append(cid)
                elif wname.startswith(prefix) and wname[len(prefix):].isdigit():
                    prefixed.append(cid)
            candidates = (exact[-1:] if len(exact) > 1 else exact) + prefixed
            for cid in candidates:
                if cid not in seen:
                    seen.add(cid)
                    matched.append(cid)
        return matched

    def _current_child_step_high(self, cid):
        _, _, _, _, highs = self._swarm_state()
        if cid in highs:
            return highs[cid]
        path = self.subagents.get(cid, {}).get("logPath", "")
        if path:
            high = child_last_step_index(read_transcript(path))
            highs[cid] = high
            return high
        return -1

    def note_swarm_tool(self, ident, turn, params, output, allow_live=True, step_index=None):
        params = params if isinstance(params, dict) else {}
        action = params.get("action")
        if action == "add":
            workers = swarm_added_workers(params, output)
            if not workers:
                return False
            sibling_ids = {w["id"] for w in workers}
            groups, worker_set, _, _, _ = self._swarm_state()
            for worker in workers:
                cid = worker["id"]
                with self.lock:
                    worker_set.add(cid)
                    if hasattr(self, "parent_children"):
                        self.parent_children.add(cid)
                    if worker.get("group"):
                        lst = groups.setdefault(worker["group"], [])
                        if cid not in lst:
                            lst.append(cid)
                self.register_swarm_child(cid, ident, turn, worker, params=params, siblings=sibling_ids, step_index=step_index)
            return True
        if action == "status":
            updates = swarm_status_updates(output)
            if not updates:
                return False
            _, worker_set, revived, stopped, _ = self._swarm_state()
            with self.lock:
                for entry in updates:
                    for cid in self._resolve_swarm_member_ids(entry.get("name", ""), entry.get("id", "")):
                        worker_set.add(cid)
                        old = self.subagents.get(cid, {})
                        fields = {}
                        if entry.get("model"):
                            fields["model"] = entry["model"]
                        ns = entry.get("nativeStatus", "")
                        st = entry.get("status", "")
                        if ns == "swarm:killed":
                            fields["status"] = "closed"
                            fields["nativeStatus"] = "swarm:killed"
                            settled_msgs = _settle_terminal_child_messages(old.get("messages", []), "closed")
                            if settled_msgs != old.get("messages", []):
                                fields["messages"] = settled_msgs
                                fields["detail"] = child_transcript_detail(settled_msgs, old.get("detail", ""))
                            revived.pop(cid, None)
                            stopped.pop(cid, None)
                        elif old.get("nativeStatus") not in ("swarm:killed", "manage_subagents"):
                            if ns == "swarm:working":
                                if allow_live or old.get("status", "unknown") in ("unknown", "pending"):
                                    fields["status"] = st
                                    fields["nativeStatus"] = ns
                                    stopped.pop(cid, None)
                            elif ns == "swarm:waiting":
                                fields["status"] = st
                                fields["nativeStatus"] = ns
                                revived.pop(cid, None)
                                stopped.pop(cid, None)
                            elif ns == "swarm:stopped":
                                fields["status"] = st
                                fields["nativeStatus"] = ns
                                revived.pop(cid, None)
                                stopped[cid] = self._current_child_step_high(cid)
                        if fields:
                            self.subagent(cid, **fields)
            return True
        if action == "model":
            name, model = swarm_model_update(params, output)
            if not model:
                return False
            with self.lock:
                targets = self._resolve_swarm_member_ids(name=name)
                for cid in targets:
                    self.subagent(cid, model=model)
            return bool(targets)
        if action == "kill":
            name = swarm_kill_target(params, output)
            if not name:
                return False
            _, worker_set, revived, stopped, _ = self._swarm_state()
            with self.lock:
                targets = self._resolve_swarm_member_ids(name=name)
                for cid in targets:
                    worker_set.add(cid)
                    revived.pop(cid, None)
                    stopped.pop(cid, None)
                    old = self.subagents[cid]
                    settled_msgs = _settle_terminal_child_messages(old.get("messages", []), "closed")
                    if old.get("status") not in ("closed", "cancelled"):
                        extra = {"messages": settled_msgs, "detail": child_transcript_detail(settled_msgs, old.get("detail", ""))} if settled_msgs != old.get("messages", []) else {}
                        self.subagent(cid, status="closed", nativeStatus="swarm:killed", **extra)
                    elif settled_msgs != old.get("messages", []):
                        self.subagent(cid, messages=settled_msgs, detail=child_transcript_detail(settled_msgs, old.get("detail", "")))
            return bool(targets)
        return False

    def note_post_message(self, params, output="", allow_revive=True):
        params = params if isinstance(params, dict) else {}
        text_val = params.get("text") if isinstance(params.get("text"), str) else ""
        for value in _iter_json_dicts(output):
            if value.get("error"):
                return False
        targets, mentions_all = swarm_mentions(text_val)
        if not targets and not mentions_all:
            return False
        _, _, revived, stopped, _ = self._swarm_state()
        with self.lock:
            matched = self._resolve_swarm_mention_ids(targets, mentions_all)
            if not allow_revive:
                return bool(matched)
            for cid in matched:
                agent = self.subagents.get(cid, {})
                if agent.get("status") == "closed" or agent.get("nativeStatus") in ("swarm:killed", "manage_subagents"):
                    continue
                stopped.pop(cid, None)
                revived[cid] = self._current_child_step_high(cid)
                if agent.get("status") != "running" or agent.get("nativeStatus") != "revived":
                    self.subagent(cid, status="running", nativeStatus="revived")
            return bool(matched)

    def apply_child_transcript(self, ident, steps, reply_detail="", protected=("manage_subagents", "swarm:killed")):
        messages = project_child_transcript(ident, steps)
        state = child_transcript_state(steps)
        last_idx = child_last_step_index(steps)
        _, _, revived, stopped, highs = self._swarm_state()
        with self.lock:
            if self.close_pending or self.released:
                return False
            highs[ident] = last_idx
            old = self.subagents.get(ident)
            if not old:
                return False
            fields = {}
            cur_native = old.get("nativeStatus")
            if state and cur_native not in protected:
                if cur_native == "revived":
                    if state != "idle" or last_idx > revived.get(ident, -1):
                        revived.pop(ident, None)
                        fields.update(status="succeeded" if state == "idle" else state, nativeStatus="transcript:" + state)
                elif cur_native == "swarm:stopped":
                    if last_idx > stopped.get(ident, -1):
                        stopped.pop(ident, None)
                        fields.update(status="succeeded" if state == "idle" else state, nativeStatus="transcript:" + state)
                else:
                    fields.update(status="succeeded" if state == "idle" else state, nativeStatus="transcript:" + state)
            eff_status = fields.get("status", old.get("status"))
            messages = _settle_terminal_child_messages(messages, eff_status)
            detail = reply_detail if state == "idle" and reply_detail else child_transcript_detail(messages, reply_detail)
            fields["messages"] = messages
            fields["detail"] = detail
            if all(old.get(key) == value for key, value in fields.items()):
                return False
            self.subagent(ident, **fields)
            return True

    def note_manage(self, params, known_children=None):
        if not isinstance(params, dict):
            return
        action = params.get("Action")
        if action == "kill_all":
            idents = list(self.subagents if known_children is None else known_children)
        elif action == "kill":
            idents = params.get("ConversationIds") if isinstance(params.get("ConversationIds"), list) else []
        else:
            return
        with self.lock:
            for ident in idents:
                if ident in self.subagents:
                    old = self.subagents[ident]
                    settled_msgs = _settle_terminal_child_messages(old.get("messages", []), "cancelled")
                    if old.get("status") not in ("cancelled", "closed"):
                        extra = {"messages": settled_msgs, "detail": child_transcript_detail(settled_msgs, old.get("detail", ""))} if settled_msgs != old.get("messages", []) else {}
                        self.subagent(ident, status="cancelled", nativeStatus="manage_subagents", **extra)
                    elif settled_msgs != old.get("messages", []):
                        self.subagent(ident, messages=settled_msgs, detail=child_transcript_detail(settled_msgs, old.get("detail", "")))

    def ensure_child_tail(self):
        if self.tail_thread and self.tail_thread.is_alive() and not self.tail_stop.is_set():
            return
        self.restart_child_tail()

    def changed_transcript(self, path):
        try:
            stat = pathlib.Path(path).stat()
        except OSError:
            return None
        version = (stat.st_mtime_ns, stat.st_size)
        if self.transcript_versions.get(path) == version:
            return None
        steps = read_transcript(path)
        self.transcript_versions[path] = version
        return steps

class AntigravityCliSession(AgyChildTailMixin, Session):
    input_evidence_protocol = 1
    argv = ["agy"]
    startup_timeout = 30

    def __init__(self, config, save):
        super().__init__(config, save)
        self.condition = threading.Condition(self.lock)
        self.ready = None
        self.startup_error = None
        self.current_submission = None
        self.turn_steps = {}
        self.last_checkpoint = 0
        self.checkpoint_timer = None
        self.checkpoint_generation = 0
        self.last_result_turn = None
        self.requested_model = ""
        self.turn_has_output = False
        self.tail_stop = threading.Event()
        self.tail_thread = None
        self.transcript_versions = {}
        self.parent_offset = 0
        self.parent_calls = []
        self.parent_children = set()
        self.stream_high = None
        self.stream_turn = None
        self.last_frame = 0.0
        self.pending_transcript = {}
        self.index_rows = {}
        self.restored_rows = set()
        self.restored_replay_buf = {}
        self.swarm_groups = {}
        self.swarm_workers = set()
        self.swarm_revived_steps = {}
        self.swarm_stopped_steps = {}
        self.child_step_highs = {}
        self.swarm_child_steps = {}
        self.handled_swarm_steps = set()
        self.swarm_replay_high = None
    def environment(self):
        env = os.environ.copy()
        env["LUMA_LANG"] = lang_code()
        if self.config.get("browserConfig"): env["LUMA_BROWSER_CONFIG"] = self.config["browserConfig"]
        return env

    def discover(self):
        if not shutil.which(self.argv[0]): raise Rejected(tr("未找到 agy；请在当前运行环境安装并完成 agy 原生登录。", "agy not found; install agy in the current environment and complete native sign-in."))
        try:
            version = subprocess.run([*self.argv, "--version"], env=self.environment(), cwd=self.config["cwd"],
                capture_output=True, timeout=10, check=False)
            match = re.search(rb"(\d+)\.(\d+)\.(\d+)", version.stdout)
            if version.returncode or not match or tuple(map(int, match.groups())) < (1, 1, 15):
                raise Rejected(tr("此 agy 未提供所需 stream-json 接口；请使用 1.1.15 或更新版本。", "This agy build lacks the required stream-json interface; upgrade to 1.1.15 or newer."))
            result = subprocess.run([*self.argv, "models"], env=self.environment(), cwd=self.config["cwd"],
                capture_output=True, timeout=10, check=False)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise Rejected(tr("agy 目录探测失败；请在终端核对安装与原生登录。", "Failed to probe agy catalog; check installation and native sign-in in terminal.")) from exc
        if result.returncode: raise Rejected(tr("无法读取 agy 模型目录；请在终端运行 agy 完成原生登录并核对配置。", "Unable to read agy model catalog; run `agy` in terminal to complete sign-in and verify config."))
        models = []
        for line in result.stdout.decode("utf-8").strip().splitlines():
            parts = line.split("\t")
            if len(parts) != 2 or not all(parts) or any(m["id"] == parts[0] for m in models):
                raise Rejected(tr("agy 模型目录格式无效", "Invalid agy model catalog format"))
            models.append({"id": parts[0], "label": parts[1], "efforts": [], "supportsImages": False})
        if not models: raise Rejected(tr("agy 未返回可用模型", "agy returned no available models"))
        self.update(models=models)

    def restore_projection(self):
        saved = self.config.get("localProjection")
        if not saved: return
        if (saved.get("version"), saved.get("nativeProtocol"), saved.get("nativeId")) != (1, PROTOCOL, self.data["nativeId"]):
            raise Rejected(tr("agy 本地投影与原生会话身份不匹配", "Local agy projection does not match native session identity"))
        for row in saved.get("messages", []):
            value = dict(row)
            if value.get("status") in ("running", "in_progress", "pending"): value["status"] = "unknown"
            if value.get("toolName") == "invoke_subagent" and value["id"].endswith(":subagent"):
                value["id"] = value["id"].removesuffix(":subagent") + ":tool"
                value = {**self.rows.get(value["id"], {}), **value}
            self.row(value)
            if value.get("role") == "assistant" and value.get("id"):
                self.restored_rows.add(value["id"])
            match = _ROW_INDEX.fullmatch(value.get("id", ""))
            if match:
                index = int(match.group(2))
                self.index_rows[(index, match.group(3))] = value["id"]
                if self.stream_high is None or index >= self.stream_high:
                    self.stream_high, self.stream_turn = index, match.group(1)
        for child in saved.get("subAgents", []):
            fields = {k: v for k, v in child.items() if k != "id"}
            if fields.get("itemId", "").endswith(":subagent"):
                fields["itemId"] = fields["itemId"].removesuffix(":subagent") + ":tool"
            if not fields.get("logPath"):
                fields["logPath"] = child_log_path(child["id"], "")
            self.subagent(child["id"], **fields)
            role_val = fields.get("role") or ""
            if role_val == "swarm" or role_val.startswith("swarm · "):
                self.swarm_workers.add(child["id"])
                self.parent_children.add(child["id"])
        saved_groups = saved.get("swarmGroups")
        if isinstance(saved_groups, dict):
            for group_name, cids in saved_groups.items():
                if isinstance(group_name, str) and isinstance(cids, list):
                    self.swarm_groups[group_name] = [c for c in cids if isinstance(c, str)]
        saved_steps = saved.get("handledSwarmSteps")
        if isinstance(saved_steps, list):
            self.handled_swarm_steps.update(idx for idx in saved_steps if type(idx) is int)
        self.swarm_replay_high = self.stream_high
        self.last_result_turn = saved.get("lastResultTurn")
        self.update(metrics=saved.get("metrics", []), historySource="luma-observed")
        if saved.get("unsettledSubmission"):
            self.update(error=tr("上次 agy 回合终态未确认；仅恢复本地已观察内容，未重发消息。",
                                 "Previous agy turn final state was not confirmed; restored locally observed content without resending."))

    def checkpoint(self, force=False, strict=False):
        with self.lock:
            if self.close_pending or self.released:
                return
            left = 1 - (time.monotonic() - self.last_checkpoint)
            if not force and left > 0:
                if self.checkpoint_timer is None:
                    self.checkpoint_timer = threading.Timer(left, self.flush_projection, args=(self.checkpoint_generation,))
                    self.checkpoint_timer.daemon = True; self.checkpoint_timer.start()
                return
            self.checkpoint_generation += 1
            if self.checkpoint_timer:
                self.checkpoint_timer.cancel(); self.checkpoint_timer = None
            projection = {"version": 1, "nativeProtocol": PROTOCOL,
                "nativeId": self.data["nativeId"], "messages": list(self.rows.values()),
                "subAgents": list(self.subagents.values()), "metrics": self.data["metrics"],
                "lastResultTurn": self.last_result_turn, "unsettledSubmission": self.current_submission}
            if self.swarm_groups:
                projection["swarmGroups"] = self.swarm_groups
            if self.handled_swarm_steps:
                projection["handledSwarmSteps"] = sorted(self.handled_swarm_steps)
            self.config["localProjection"] = copy.deepcopy(projection)
            try: self.save()
            except OSError:
                self.update(error=tr("无法保存 agy 本地投影；原生执行状态仍以实际事件为准。",
                                     "Unable to save local agy projection; native execution state still follows live events."))
                if strict: raise Rejected(tr("无法保存发送状态，尚未写入 agy", "Unable to save delivery state; not written to agy yet")) from None
            else: self.last_checkpoint = time.monotonic()

    def flush_projection(self, generation):
        with self.lock:
            if self.close_pending or self.released or generation != self.checkpoint_generation:
                return
            self.checkpoint_timer = None
            self.checkpoint(force=True)

    def connect(self):
        if self.config.get("nativeProtocol") != PROTOCOL: raise Rejected(tr("此会话不属于 agy stream-json，不能切换原生协议", "This session does not belong to agy stream-json; cannot switch native protocol"))
        if self.config.get("creationUnknown") and not self.data["nativeId"]:
            raise Rejected(tr("上次 agy 创建结果未知；请在原生会话列表核对 ID，不能自动再建", "Previous agy session creation result is unknown; verify ID in native session list instead of recreating"))
        self.restore_projection()
        self.discover()
        model = self.config.get("model", "")
        if model and model not in {m["id"] for m in self.data["models"]}: raise Rejected(tr("原会话模型不在 agy 当前目录中，请在终端核对", "Original session model is not in the current agy catalog; verify in terminal"))
        self.launch(model)

    def launch(self, model):
        native = self.data["nativeId"]
        self.requested_model = model
        args = [*self.argv, "--input-format", "stream-json", "--output-format", "stream-json",
                "--add-dir", self.config["cwd"], "--disable-slash-commands", "--dangerously-skip-permissions"]
        if model: args += ["--model", model]
        if native: args += ["--conversation", native]
        if not native:
            self.config["creationUnknown"] = True; self.save()
        with self.condition:
            self.ready = None; self.startup_error = None
            try:
                peer = AgyPeer(args, self.config["cwd"], self.environment(),
                    lambda frame: self.event(frame) if self.peer is peer else None,
                    lambda error: self.peer_exited(peer, error))
            except OSError:
                if not native: self.config["creationUnknown"] = False; self.save()
                raise
            self.peer = peer; peer.start_read()
            deadline = time.monotonic() + self.startup_timeout
            while self.ready is None and self.startup_error is None:
                left = deadline - time.monotonic()
                if left <= 0: self.startup_error = tr("agy 未在 30 秒内返回原生 init；不要自动重建或重发", "agy did not return native init within 30s; do not recreate or resend automatically")
                else: self.condition.wait(left)
            failure = self.startup_error
        if failure:
            peer.close(interrupt=True); raise Rejected(failure)
        if peer.closed or peer.proc.poll() is not None:
            peer.close(); raise Rejected(tr("agy 在初始化后退出，请在终端核对原生登录与配置", "agy exited after initialization; verify native sign-in and config in terminal"))
        self.config["model"] = model; self.save()
        self.update(connected=True, running=False, model=self.ready.get("model") or "", effort="", notice="",
            permission=FULL_ACCESS, permissions=[{"id": FULL_ACCESS, "label": "Always proceed"}],
            historySource="luma-observed", historyReady=True,
            capabilities={"queue": False, "steer": False, "stop": True, "images": False,
                          "skills": False, "compact": False, "rename": False})
        self.restart_child_tail()
        self.checkpoint(force=True)

    def event(self, frame):
        with self.condition:
            kind = frame.get("event")
            if kind == "init":
                if self.ready is not None: raise ValueError(tr("agy 重复 init", "Duplicate agy init"))
                ident, info = frame.get("conversation_id"), frame.get("init")
                if not isinstance(ident, str) or not _ID.fullmatch(ident) or not isinstance(info, dict): raise ValueError(tr("agy init 身份无效", "Invalid agy init identity"))
                if self.data["nativeId"] and ident != self.data["nativeId"]: raise ValueError(tr("agy 恢复了不同原生会话，已拒绝接入", "agy resumed a different native session; refused to attach"))
                if info.get("permission_mode") != FULL_ACCESS: raise ValueError(tr("agy 未确认已选全权限模式", "agy did not confirm full-access mode"))
                if self.requested_model and info.get("model") != self.requested_model: raise ValueError(tr("agy 未确认所选原生模型", "agy did not confirm the selected native model"))
                self.config["creationUnknown"] = False
                self.update(nativeId=ident)
                self.ready = info; self.condition.notify_all(); return
            payload = frame.get("step_update" if kind == "step_update" else "result")
            if not isinstance(payload, dict): return
            if self.ready is None:
                if kind == "result" and payload.get("status") == "ERROR":
                    if payload.get("conversation_id") == "" and not self.data["nativeId"]:
                        self.config["creationUnknown"] = False; self.save()
                    self.startup_error = native_error(payload.get("error")); self.condition.notify_all()
                return
            if payload.get("conversation_id") != self.data["nativeId"]: return
            if kind == "step_update": self.step(payload)
            elif kind == "result" and self.current_submission: self.result(payload)

    def accept_submission(self):
        ident = self.current_submission
        if not ident: return
        if self.config.get("receipts", {}).get(ident) != "accepted": self.receipt(ident, "accepted")
        self.consumed(ident)
        if ident in self.rows: self.row({**self.rows[ident], "status": "completed"})

    def step(self, step):
        index, state, kind = step.get("step_index"), step.get("state"), step.get("step_type")
        if type(index) is not int or index < 0 or state not in ("ACTIVE", "DONE", "ERROR"): raise ValueError(tr("agy step 身份或状态无效", "Invalid agy step identity or state"))
        turn = self.current_submission or "background"
        row_kind = "tool" if kind == "subagent" else kind
        self.last_frame = time.monotonic()
        if row_kind in ("agent_response", "tool"):
            if self.stream_high is None or index >= self.stream_high:
                self.stream_high, self.stream_turn = index, turn
            self.pending_transcript.pop(index, None)
        filled = self.index_rows.get((index, row_kind))
        ident = filled or f"agy:{turn}:{index}:{row_kind}"
        if filled is None and row_kind in ("agent_response", "tool"): self.index_rows[(index, row_kind)] = ident
        if kind in ("user_input", "agent_response", "tool", "subagent"): self.accept_submission()
        status = {"ACTIVE": "running", "DONE": "completed", "ERROR": "failed"}[state]
        if kind == "agent_response":
            delta = step.get("text_delta", "")
            if not isinstance(delta, str): raise ValueError(tr("agy text_delta 无效", "Invalid agy text_delta"))
            old = self.rows.get(ident, item(ident, "assistant", "", turnId=turn))
            if old.get("source") == "transcript":
                delta = ""
            elif ident in self.restored_rows:
                prev_text = old.get("text", "")
                buf = self.restored_replay_buf.get(ident, "") + delta
                self.restored_replay_buf[ident] = buf
                if prev_text.startswith(buf):
                    new_text = prev_text
                elif buf.startswith(prev_text):
                    new_text = buf
                    self.restored_rows.discard(ident)
                    self.restored_replay_buf.pop(ident, None)
                else:
                    new_text = prev_text
                if state != "ACTIVE":
                    self.restored_rows.discard(ident)
                    self.restored_replay_buf.pop(ident, None)
                self.row({**old, "text": new_text, "status": status})
                self.turn_has_output = True
                self.checkpoint(force=state != "ACTIVE")
                return
            if delta or ident in self.rows:
                self.row({**old, "text": old["text"] + delta, "status": status})
                self.turn_has_output = True
        elif kind in ("tool", "subagent"):
            info = step.get("tool_info") or {}; name = info.get("name") or step.get("tool_name", "")
            old = self.rows.get(ident, item(ident, "tool", name, kind="tool", turnId=turn))
            changes = {"toolName": name or old.get("toolName", ""), "status": "failed" if info.get("error") else status}
            if "parameters" in info: changes["input"] = json.dumps(info["parameters"], ensure_ascii=False)
            if "output" in info: changes["outcome"] = clean_agy_tool_output(display_tool_output(None, info["output"]))
            if info.get("error"): changes["outcome"] = clean_agy_tool_output(display_tool_output(None, info["error"]))
            self.row({**old, **changes})
            self._reanchor_cli_swarm(index, ident, turn)
            if not info.get("error") and state != "ERROR":
                for child in (step.get("subagent_info") or {}).get("subagents", []):
                    child_id = child.get("conversation_id")
                    if isinstance(child_id, str) and _ID.fullmatch(child_id):
                        self.register_child(child_id, ident, turn, child.get("type_name", ""),
                            child.get("role", ""), child.get("initial_prompt", ""), child.get("log_uri", ""))
                if name == "invoke_subagent": self.note_invoke(ident, turn, info)
                elif name == "manage_subagents" and state == "DONE": self.note_manage(info.get("parameters") or _parse_json_dict(old.get("input")))
                elif name == "swarm" and state == "DONE":
                    params = info.get("parameters") if isinstance(info.get("parameters"), dict) else _parse_json_dict(old.get("input"))
                    with self.lock:
                        if self.note_swarm_tool(ident, turn, params, info.get("output", ""), allow_live=True, step_index=index):
                            self.handled_swarm_steps.add(index)
                elif name == "post_message" and state == "DONE":
                    params = info.get("parameters") if isinstance(info.get("parameters"), dict) else _parse_json_dict(old.get("input"))
                    with self.lock:
                        if self.note_post_message(params, info.get("output", ""), allow_revive=True):
                            self.handled_swarm_steps.add(index)
        if state == "DONE" and isinstance(step.get("usage"), dict):
            self.turn_steps[index] = step["usage"]
            total = agy_steps(list(self.turn_steps.values()))
            self.usage(total, tr("本回合", "This turn"))
        self.checkpoint(force=state != "ACTIVE")

    def note_invoke(self, ident, turn, info):
        params = info.get("parameters") if isinstance(info.get("parameters"), dict) else {}
        child_id, uri = invoke_child(info.get("output"))
        if not child_id: return
        self.register_child(child_id, ident, turn, params.get("TypeName") or "",
            params.get("Role") or "", params.get("Prompt") or "", uri, params.get("Model", ""))

    def register_swarm_child(self, child_id, ident, turn, worker, params=None, siblings=None, step_index=None):
        if type(step_index) is int and child_id:
            with self.lock:
                self.swarm_child_steps[child_id] = step_index
        self.register_child(
            child_id,
            ident,
            turn,
            worker.get("name", ""),
            worker.get("role", "swarm"),
            worker.get("task", ""),
            "",
            worker.get("model") or "inherit",
        )

    def _reanchor_cli_swarm(self, index, ident, turn):
        if not ident:
            return
        with self.lock:
            for cid, step_idx in list(self.swarm_child_steps.items()):
                if step_idx == index:
                    old = self.subagents.get(cid, {})
                    if not old.get("itemId") or old["itemId"] not in self.rows:
                        self.subagent(cid, itemId=ident, turnId=turn or old.get("turnId", ""))

    def _reanchor_all_cli_swarm(self):
        with self.lock:
            if self.close_pending or self.released:
                return
            for cid, step_idx in list(self.swarm_child_steps.items()):
                old = self.subagents.get(cid, {})
                if old.get("itemId") and old["itemId"] in self.rows:
                    continue
                suffixes = (f":{step_idx}:tool", f":{step_idx}:subagent")
                anchor = next((r for key, r in self.rows.items() if key.endswith(suffixes)), {})
                if anchor.get("id"):
                    self.subagent(cid, itemId=anchor["id"], turnId=anchor.get("turnId") or old.get("turnId", ""))

    def register_child(self, child_id, ident, turn, name, role, task, uri, model=""):
        fields = {"name": name, "role": role, "task": task, "itemId": ident, "turnId": turn}
        if isinstance(model, str) and model: fields["model"] = model
        path = child_log_path(child_id, uri)
        if path: fields["logPath"] = path
        if self.subagents.get(child_id, {}).get("status", "unknown") == "unknown": fields["status"] = "running"
        self.subagent(child_id, **fields)
        if path: self.ensure_child_tail()

    def restart_child_tail(self):
        self.tail_stop.set()
        self.tail_stop = threading.Event()
        self.transcript_versions = {}
        self.tail_thread = threading.Thread(target=self.tail_children, args=(self.tail_stop,), name="LumaAgyChildLog", daemon=True)
        self.tail_thread.start()

    def refresh_parent_log(self):
        with self.lock:
            if self.close_pending or self.released: return
            native_id = self.data["nativeId"]
        path = child_log_path(native_id, "")
        if not path: return
        full_path = str(pathlib.Path(path).with_name("transcript_full.jsonl"))
        changed = False
        try:
            with open(path, "rb") as source:
                if os.fstat(source.fileno()).st_size < self.parent_offset:
                    self.parent_offset = 0; self.parent_calls = []; self.parent_children.clear()
                    self.swarm_child_steps.clear(); self.handled_swarm_steps.clear()
                source.seek(self.parent_offset)
                while True:
                    line = source.readline()
                    if not line or not line.endswith(b"\n"): break
                    self.parent_offset = source.tell()
                    try: step = json.loads(line)
                    except ValueError: continue
                    if not isinstance(step, dict): continue
                    if step.get("truncated_fields"):
                        step = read_full_step(full_path, step.get("step_index")) or step
                    self.note_transcript_step(step)
                    if step.get("type") == "PLANNER_RESPONSE":
                        self.parent_calls = [c for c in step.get("tool_calls", []) if isinstance(c, dict)]
                    elif step.get("type") == "GENERIC" and self.parent_calls:
                        call = self.parent_calls.pop(0)
                        if step.get("status") != "DONE": continue
                        index = step.get("step_index")
                        info = {"parameters": transcript_args(call), "output": step.get("content", "")}
                        cname = call.get("name")
                        with self.lock:
                            if self.close_pending or self.released: return
                            if cname == "invoke_subagent":
                                child_id, _ = invoke_child(info["output"])
                                if child_id: self.parent_children.add(child_id)
                                suffixes = (f":{index}:tool", f":{index}:subagent")
                                anchor = next((r for key, r in self.rows.items() if key.endswith(suffixes)), {})
                                self.note_invoke(anchor.get("id", ""), anchor.get("turnId", ""), info)
                                changed = bool(child_id) or changed
                            elif cname == "manage_subagents":
                                self.note_manage(info["parameters"], self.parent_children)
                                changed = True
                            elif cname == "swarm" and index not in self.handled_swarm_steps:
                                suffixes = (f":{index}:tool", f":{index}:subagent")
                                anchor = next((r for key, r in self.rows.items() if key.endswith(suffixes)), {})
                                turn_id = anchor.get("turnId") or self.current_submission or self.stream_turn or ""
                                allow_live = self.swarm_replay_high is None or (type(index) is int and index > self.swarm_replay_high)
                                if self.note_swarm_tool(anchor.get("id", ""), turn_id, info["parameters"], info["output"], allow_live=allow_live, step_index=index):
                                    if type(index) is int:
                                        self.handled_swarm_steps.add(index)
                                    changed = True
                            elif cname == "post_message" and index not in self.handled_swarm_steps:
                                allow_live = self.swarm_replay_high is None or (type(index) is int and index > self.swarm_replay_high)
                                if self.note_post_message(info["parameters"], info["output"], allow_revive=allow_live):
                                    if type(index) is int:
                                        self.handled_swarm_steps.add(index)
                                    changed = True
        except OSError: return
        with self.lock:
            if self.close_pending or self.released: return
            if self.backfill_held_steps(): changed = True
            self._reanchor_all_cli_swarm()
            if changed: self.checkpoint()

    def note_transcript_step(self, step):
        index, kind = step.get("step_index"), step.get("type")
        if type(index) is not int or kind not in ("PLANNER_RESPONSE", "GENERIC"): return
        call = None
        if kind == "GENERIC":
            if not self.parent_calls: return
            call = self.parent_calls[0]
        elif not (step.get("status") == "DONE" and isinstance(step.get("content"), str) and step["content"].strip()):
            return
        with self.lock:
            if self.stream_high is None or index <= self.stream_high: return
            self.pending_transcript[index] = (step, call)

    def backfill_held_steps(self):
        with self.lock:
            if self.close_pending or self.released: return False
            if self.stream_high is not None:
                for index in [i for i in self.pending_transcript if i <= self.stream_high]:
                    del self.pending_transcript[index]
            if not self.pending_transcript or time.monotonic() - self.last_frame < STREAM_STALL_SECONDS: return False
            turn = self.current_submission or self.stream_turn or "background"
            for index in sorted(self.pending_transcript):
                step, call = self.pending_transcript[index]
                state = step.get("status")
                status = "running" if state in ("RUNNING", "ACTIVE") else "failed" if state == "ERROR" else "completed"
                step_ts = parse_ts_ms(step.get("created_at"))
                content = step.get("content") if isinstance(step.get("content"), str) else ""
                if step.get("type") == "PLANNER_RESPONSE":
                    key = (index, "agent_response")
                    ident = self.index_rows.setdefault(key, f"agy:{turn}:{index}:agent_response")
                    old = self.rows.get(ident, item(ident, "assistant", "", turnId=turn))
                    row_data = {**old, "text": content, "status": status, "source": "transcript"}
                    if step_ts and not old.get("timestampMs"):
                        row_data["timestampMs"] = step_ts
                    self.row(row_data)
                    self.turn_has_output = True
                else:
                    key = (index, "tool")
                    ident = self.index_rows.setdefault(key, f"agy:{turn}:{index}:tool")
                    name = call.get("name") if isinstance(call.get("name"), str) else ""
                    old = self.rows.get(ident, item(ident, "tool", name, kind="tool", toolName=name, turnId=turn))
                    t_created, t_completed = extract_agy_tool_timestamps(content)
                    done_ts = t_completed or step_ts
                    changes = {"toolName": old.get("toolName") or name, "status": status, "source": "transcript"}
                    if not old.get("timestampMs") and (t_created or step_ts):
                        changes["timestampMs"] = t_created or step_ts
                    if status != "running" and done_ts:
                        changes["completedAtMs"] = done_ts
                    if not old.get("input"): changes["input"] = json.dumps(transcript_args(call), ensure_ascii=False)[:16000]
                    if content and status != "running": changes["outcome"] = clean_agy_tool_output(content)[:16000]
                    self.row({**old, **changes})
                    self._reanchor_cli_swarm(index, ident, turn)
            self.pending_transcript.clear()
            return True

    def tail_children(self, stop):
        while not stop.wait(0.2):
            self.refresh_parent_log()
            with self.lock:
                jobs = [(ident, child.get("logPath")) for ident, child in self.subagents.items() if child.get("logPath")]
            for ident, path in jobs:
                if stop.is_set(): return
                self.refresh_child_log(ident, path)

    def refresh_child_log(self, ident, path):
        with self.lock:
            if self.close_pending or self.released: return
        steps = self.changed_transcript(path)
        if steps is None: return
        with self.lock:
            if self.close_pending or self.released: return
            if not self.apply_child_transcript(ident, steps, protected=("manage_subagents", "swarm:killed")):
                return
            self.checkpoint()

    def usage(self, usage, scope):
        native_id = self.data.get("nativeId") or self.config.get("nativeId", "")
        model = self.data.get("model") or self.config.get("model", "")
        rows = agy_session_metrics(
            native_id,
            model=model,
            step_usages=list(self.turn_steps.values()) if self.turn_steps else None,
            result_usage=usage if scope in ("CLI 累计", "CLI total") else None,
        )
        if not rows:
            rows = agy_usage(usage, scope, model=model)
        self.update(metrics=rows)

    def result(self, result):
        status = result.get("status")
        if status not in ("SUCCESS", "ERROR", "CANCELED", "INTERRUPTED", "INVALID", "WAITING", "RUNNING"):
            raise ValueError(tr("agy result 状态无效", "Invalid agy result status"))
        if status in ("WAITING", "RUNNING"):
            self.update(error=tr(f"agy 返回了非终态结果（{status}）；请核对，不会自动重发。",
                                 f"agy returned a non-terminal result ({status}); please verify, will not resend automatically."))
            self.checkpoint(force=True); return
        if status == "SUCCESS": self.accept_submission()
        turn = self.current_submission
        if status != "SUCCESS" and turn and self.config.get("receipts", {}).get(turn) != "accepted":
            self.settle_uncertain(turn)
        cancelled = status in ("CANCELED", "INTERRUPTED") or (status == "ERROR" and
            (result.get("error") == "interrupted" or result.get("finished_reason") == "interrupted"))
        if not self.turn_has_output and result.get("response"):
            self.row(item(f"agy:{turn}:result", "assistant", result["response"], turnId=turn,
                          status="cancelled" if cancelled else "completed" if status == "SUCCESS" else "failed"))
        for ident, row in list(self.rows.items()):
            if row.get("turnId") == turn and row.get("status") == "running" and row.get("role") == "assistant":
                self.row({**row, "status": "cancelled" if cancelled else "completed" if status == "SUCCESS" else "failed"})
        self.settle_incomplete_rows("unknown")
        if isinstance(result.get("usage"), dict): self.usage(result["usage"], tr("CLI 累计", "CLI total"))
        self.last_result_turn = result.get("num_turns")
        self.current_submission = None
        error = None if status == "SUCCESS" or cancelled else native_error(result.get("error"))
        if result.get("denied_actions"):
            sep = tr("、", ", ")
            error = tr("agy 原生策略拒绝了操作：", "agy native policy denied actions: ") + sep.join(str(a.get("display_name") or a.get("action", "")) for a in result["denied_actions"])
        self.update(running=False, error=error)
        self.checkpoint(force=True)

    def prompt(self, payload):
        self.current_submission = payload["submissionId"]; self.turn_steps = {}; self.turn_has_output = False
        self.update(running=True, error=None)
        self.row(item(payload["submissionId"], "user", payload["text"], turnId=payload["submissionId"], status="pending", origin="local"))
        try:
            self.checkpoint(force=True, strict=True)
            self.peer.send({"event": "user", "message": {"content": payload["text"]}})
        except Rejected:
            self.current_submission = None; self.update(running=False); raise
        except Exception:
            self.update(connected=not self.peer.closed, running=not self.peer.closed,
                        error=tr("提交到 agy 的结果未知；请核对，不要自动重发。", "Submission result to agy is unknown; verify before resending."))
            self.checkpoint(force=True)
            raise
        return False

    def peer_exited(self, peer, error):
        with self.condition:
            if self.peer is not peer: return
            if self.ready is None:
                self.startup_error = self.startup_error or str(error); self.condition.notify_all()
            active = self.current_submission is not None
            if active: self.settle_incomplete_rows("unknown")
            self.tail_stop.set()
            self.update(connected=False, running=False, **({"error": tr("agy 已退出，本回合未收到原生结算；结果未知，请核对后重连。", "agy exited before the turn settled; result is unknown, verify before reconnecting.")} if active else
                {} if peer.expected_close else {"error": str(error)}))
            self.checkpoint(force=True)

    def configure(self, payload):
        if self.data["running"]: raise Rejected(tr("请等待 agy 当前回合结束", "Wait for the current agy turn to finish"))
        if payload.get("effort") or payload.get("speed"): raise Rejected(tr("agy 强度随原生模型 ID 选择；没有独立强度或速度接口", "agy effort is selected via native model ID; no separate effort or speed setting"))
        if payload.get("permission") and payload["permission"] != FULL_ACCESS: raise Rejected(tr("此 agy 入口只支持已选全权限模式", "This agy entry only supports full-access mode"))
        model = payload.get("model")
        if model and model != self.data["model"]:
            if model not in {m["id"] for m in self.data["models"]}: raise Rejected(tr("模型不在 agy 原生目录中", "Model is not in the native agy catalog"))
            self.update(connected=False)
            self.peer.close()
            self.launch(model)
        return {}

    def refresh(self):
        self.discover()
        rows = agy_session_metrics(self.data.get("nativeId"), model=self.data.get("model") or self.config.get("model", ""))
        if rows:
            self.update(metrics=rows)
        return {}

    def stop(self):
        with self.operation:
            if not self.current_submission: return {}
            self.update(connecting=True, connected=False)
            try:
                self.peer.close(interrupt=True)
                if self.current_submission is None and self.peer.proc.poll() is not None:
                    self.launch(self.config.get("model", ""))
            finally:
                self.update(connecting=False)
        return {}

    def release_mode(self):
        if (
            self.config.get("nativeId")
            and self.data.get("historyReady")
            and isinstance(self.config.get("localProjection"), dict)
            and self.config["localProjection"].get("nativeId") == self.config.get("nativeId")
        ):
            return "process"
        return None

    def fast_release_blocker(self):
        with self.lock:
            blocker = super().fast_release_blocker()
            if blocker is not None:
                return blocker
            if getattr(self, "pending_transcript", None):
                return tr("原生回合尚未结算", "Native turn has not settled")
            return None

    def release_blocker(self):
        return super().release_blocker()

    def finish_observer_close(self):
        tail_stop = getattr(self, "tail_stop", None)
        if tail_stop is not None:
            tail_stop.set()
        with self.lock:
            self.checkpoint_generation = getattr(self, "checkpoint_generation", 0) + 1
            timer = getattr(self, "checkpoint_timer", None)
            if timer is not None:
                timer.cancel()
                self.checkpoint_timer = None
            tail_thread = getattr(self, "tail_thread", None)
        if tail_thread is not None and tail_thread.is_alive() and threading.current_thread() is not tail_thread:
            tail_thread.join(timeout=2.0)
        super().finish_observer_close()

# Adapted from anthropics/claude-agent-sdk-python d38e51e (_internal/sessions.py), MIT License; see CLAUDE-SDK-LICENSE.txt.
import json
import re
from typing import Any

_TranscriptEntry = dict[str, Any]
_TRANSCRIPT_ENTRY_TYPES = {"user", "assistant", "progress", "system", "attachment"}
_SANITIZE_RE = re.compile(r"[^a-zA-Z0-9]")
MAX_SANITIZED_LENGTH = 200

def _simple_hash(s: str) -> str:
    h = 0
    for ch in s:
        char = ord(ch)
        h = (h << 5) - h + char
        h = h & 0xFFFFFFFF
        if h >= 0x80000000:
            h -= 0x100000000
    h = abs(h)
    if h == 0:
        return "0"
    digits = "0123456789abcdefghijklmnopqrstuvwxyz"
    out = []
    n = h
    while n > 0:
        out.append(digits[n % 36])
        n //= 36
    return "".join(reversed(out))

def _sanitize_path(name: str) -> str:
    sanitized = _SANITIZE_RE.sub("-", name)
    if len(sanitized) <= MAX_SANITIZED_LENGTH:
        return sanitized
    h = _simple_hash(name)
    return f"{sanitized[:MAX_SANITIZED_LENGTH]}-{h}"

def _parse_transcript_entries(content: str) -> list[_TranscriptEntry]:
    entries: list[_TranscriptEntry] = []
    start = 0
    length = len(content)

    while start < length:
        end = content.find("\n", start)
        if end == -1:
            end = length

        line = content[start:end].strip()
        start = end + 1
        if not line:
            continue

        try:
            entry = json.loads(line)
        except (json.JSONDecodeError, ValueError):
            continue

        if not isinstance(entry, dict):
            continue
        entry_type = entry.get("type")
        if entry_type in _TRANSCRIPT_ENTRY_TYPES and isinstance(entry.get("uuid"), str):
            entries.append(entry)

    return entries

def _build_conversation_chain(
    entries: list[_TranscriptEntry],
) -> list[_TranscriptEntry]:
    if not entries:
        return []

    by_uuid: dict[str, _TranscriptEntry] = {}
    for entry in entries:
        by_uuid[entry["uuid"]] = entry

    entry_index: dict[str, int] = {}
    for i, entry in enumerate(entries):
        entry_index[entry["uuid"]] = i

    parent_uuids: set[str] = set()
    for entry in entries:
        parent = entry.get("parentUuid")
        if parent:
            parent_uuids.add(parent)

    terminals = [e for e in entries if e["uuid"] not in parent_uuids]

    leaves: list[_TranscriptEntry] = []
    for terminal in terminals:
        walk_cur: _TranscriptEntry | None = terminal
        walk_seen: set[str] = set()
        while walk_cur is not None:
            uid = walk_cur["uuid"]
            if uid in walk_seen:
                break
            walk_seen.add(uid)
            if walk_cur.get("type") in ("user", "assistant"):
                leaves.append(walk_cur)
                break
            parent = walk_cur.get("parentUuid")
            walk_cur = by_uuid.get(parent) if parent else None

    if not leaves:
        return []

    main_leaves = [
        leaf
        for leaf in leaves
        if not leaf.get("isSidechain")
        and not leaf.get("teamName")
        and not leaf.get("isMeta")
    ]

    def _pick_best(candidates: list[_TranscriptEntry]) -> _TranscriptEntry:
        best = candidates[0]
        best_idx = entry_index.get(best["uuid"], -1)
        for cur in candidates[1:]:
            cur_idx = entry_index.get(cur["uuid"], -1)
            if cur_idx > best_idx:
                best = cur
                best_idx = cur_idx
        return best

    leaf = _pick_best(main_leaves) if main_leaves else _pick_best(leaves)

    chain: list[_TranscriptEntry] = []
    chain_seen: set[str] = set()
    chain_cur: _TranscriptEntry | None = leaf
    while chain_cur is not None:
        uid = chain_cur["uuid"]
        if uid in chain_seen:
            break
        chain_seen.add(uid)
        chain.append(chain_cur)
        parent = chain_cur.get("parentUuid")
        chain_cur = by_uuid.get(parent) if parent else None

    chain.reverse()
    return _recover_orphaned_parallel_tool_results(by_uuid, chain, chain_seen)

def _assistant_message_id(entry: _TranscriptEntry) -> str | None:
    if entry.get("type") != "assistant":
        return None
    message = entry.get("message")
    if not isinstance(message, dict):
        return None
    message_id = message.get("id")
    return message_id if isinstance(message_id, str) and message_id else None

def _content_block_ids(
    entry: _TranscriptEntry, block_type: str, id_field: str
) -> list[str]:
    message = entry.get("message")
    if not isinstance(message, dict):
        return []
    content = message.get("content")
    if not isinstance(content, list):
        return []
    ids: list[str] = []
    for block in content:
        if isinstance(block, dict) and block.get("type") == block_type:
            block_id = block.get(id_field)
            if isinstance(block_id, str):
                ids.append(block_id)
    return ids

def _is_tool_result_entry(entry: _TranscriptEntry) -> bool:
    if entry.get("type") != "user":
        return False
    parent = entry.get("parentUuid")
    if not isinstance(parent, str) or not parent:
        return False
    message = entry.get("message")
    if not isinstance(message, dict):
        return False
    content = message.get("content")
    if not isinstance(content, list):
        return False
    return any(
        isinstance(block, dict) and block.get("type") == "tool_result"
        for block in content
    )

def _same_writer(a: _TranscriptEntry, b: _TranscriptEntry) -> bool:
    return bool(a.get("isSidechain")) == bool(b.get("isSidechain")) and a.get(
        "agentId"
    ) == b.get("agentId")

def _timestamp_sort_key(entry: _TranscriptEntry) -> str:
    timestamp = entry.get("timestamp")
    return timestamp if isinstance(timestamp, str) else ""

def _recover_orphaned_parallel_tool_results(
    by_uuid: dict[str, _TranscriptEntry],
    chain: list[_TranscriptEntry],
    seen: set[str],
) -> list[_TranscriptEntry]:
    chain_assistants = [e for e in chain if e.get("type") == "assistant"]
    if not chain_assistants:
        return chain

    anchor_by_message_id: dict[str, _TranscriptEntry] = {}
    for entry in chain_assistants:
        message_id = _assistant_message_id(entry)
        if message_id:
            anchor_by_message_id[message_id] = entry
    if not anchor_by_message_id:
        return chain

    siblings_by_message_id: dict[str, list[_TranscriptEntry]] = {}
    call_owner: dict[str, _TranscriptEntry | None] = {}
    tool_result_entries: list[_TranscriptEntry] = []
    for entry in by_uuid.values():
        message_id = _assistant_message_id(entry)
        if message_id:
            siblings_by_message_id.setdefault(message_id, []).append(entry)
            for call_id in _content_block_ids(entry, "tool_use", "id"):
                if call_id not in call_owner:
                    call_owner[call_id] = entry
                    continue
                owner = call_owner[call_id]
                same_response = (
                    owner is not None and _assistant_message_id(owner) == message_id
                )
                call_owner[call_id] = entry if same_response else None
        elif _is_tool_result_entry(entry):
            tool_result_entries.append(entry)

    results_by_assistant: dict[str, list[_TranscriptEntry]] = {}
    for result in tool_result_entries:
        results_by_assistant.setdefault(result["parentUuid"], []).append(result)
        source = result.get("sourceToolAssistantUUID")
        if isinstance(source, str):
            source_entry = by_uuid.get(source)
            if source_entry is not None and _same_writer(result, source_entry):
                results_by_assistant.setdefault(source, []).append(result)
        for call_id in _content_block_ids(result, "tool_result", "tool_use_id"):
            owner = call_owner.get(call_id)
            if owner is not None and _same_writer(result, owner):
                results_by_assistant.setdefault(owner["uuid"], []).append(result)

    answered_on_chain: set[str] | None = None
    file_position: dict[str, int] | None = None

    processed: set[str] = set()
    inserts: dict[str, list[_TranscriptEntry]] = {}
    for assistant in chain_assistants:
        message_id = _assistant_message_id(assistant)
        if not message_id or message_id in processed:
            continue
        processed.add(message_id)

        group = siblings_by_message_id.get(message_id, [assistant])
        member_uuids = {member["uuid"] for member in group}
        orphaned_siblings = [m for m in group if m["uuid"] not in seen]
        orphaned_results: list[_TranscriptEntry] = []
        strays: list[_TranscriptEntry] = []
        collected: set[str] = set()
        for member in group:
            for result in results_by_assistant.get(member["uuid"], ()):
                if result["uuid"] in seen or result["uuid"] in collected:
                    continue
                collected.add(result["uuid"])
                if result["parentUuid"] in member_uuids:
                    orphaned_results.append(result)
                else:
                    strays.append(result)

        if strays:
            if answered_on_chain is None or file_position is None:
                answered_on_chain = {
                    call_id
                    for entry in chain
                    for call_id in _content_block_ids(
                        entry, "tool_result", "tool_use_id"
                    )
                }
                file_position = {uid: i for i, uid in enumerate(by_uuid)}
            answered = set(answered_on_chain)
            for result in orphaned_results:
                answered.update(
                    _content_block_ids(result, "tool_result", "tool_use_id")
                )
            positions = file_position
            strays.sort(key=lambda e: positions[e["uuid"]])
            for result in strays:
                call_ids = _content_block_ids(result, "tool_result", "tool_use_id")
                answers_open_call = False
                for call_id in call_ids:
                    owner = call_owner.get(call_id)
                    if (
                        call_id not in answered
                        and owner is not None
                        and owner["uuid"] in member_uuids
                    ):
                        answers_open_call = True
                        break
                if not answers_open_call:
                    continue
                answered.update(call_ids)
                orphaned_results.append(result)

        if not orphaned_siblings and not orphaned_results:
            continue

        orphaned_siblings.sort(key=_timestamp_sort_key)
        orphaned_results.sort(key=_timestamp_sort_key)
        recovered = orphaned_siblings + orphaned_results
        for entry in recovered:
            seen.add(entry["uuid"])
        inserts[anchor_by_message_id[message_id]["uuid"]] = recovered

    if not inserts:
        return chain

    result_chain: list[_TranscriptEntry] = []
    for entry in chain:
        result_chain.append(entry)
        result_chain.extend(inserts.get(entry["uuid"], ()))
    return result_chain

def _is_visible_message(entry: _TranscriptEntry) -> bool:
    entry_type = entry.get("type")
    if entry_type != "user" and entry_type != "assistant":
        return False
    if entry.get("isMeta"):
        return False
    if entry.get("isSidechain"):
        return False
    return not entry.get("teamName")

def _is_internal_message(entry: _TranscriptEntry) -> bool:
    return bool(entry.get("isMeta") or entry.get("isSidechain") or
                entry.get("teamName") or entry.get("parent_tool_use_id"))

def _is_human_turn(entry: _TranscriptEntry) -> bool:
    if _is_internal_message(entry) or entry.get("isCompactSummary"):
        return False
    origin = entry.get("origin")
    return not (isinstance(origin, dict) and isinstance(origin.get("kind"), str)
                and origin["kind"] != "human")


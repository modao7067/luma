import base64
import difflib
import json
import mimetypes
from pathlib import Path
from .i18n import tr

MAX_BYTES = 64 * 1024 * 1024

def media_blocks(value):
    if isinstance(value, list):
        result = []
        for block in value:
            for resource in media_blocks(block):
                if resource not in result: result.append(resource)
        return result
    if not isinstance(value, dict): return []
    kind = value.get("type")
    mime = value.get("mimeType") or value.get("mime_type") or ""
    name = value.get("name") or value.get("filename") or ""
    source = None
    if kind in ("image", "audio", "document"):
        native = value.get("source") or {}
        if isinstance(native, dict) and native.get("type") == "base64":
            mime = native.get("media_type") or mime
            source = inline(native.get("data"), mime)
        elif isinstance(native, dict) and native.get("type") == "url": source = native.get("url")
        elif value.get("data") is not None: source = inline(value["data"], mime or ("image/*" if kind == "image" else "audio/*" if kind == "audio" else "application/octet-stream"))
        else: source = value.get("url") or value.get("uri") or value.get("path")
        if not mime: mime = "image/*" if kind == "image" else "audio/*" if kind == "audio" else "application/octet-stream"
    elif kind in ("file", "attachment", "resource_link"):
        source = value.get("uri") or value.get("url") or value.get("path")
    elif kind == "image_url":
        source = value.get("image_url")
        if isinstance(source, dict): source = source.get("url")
        mime = mime or "image/*"
    elif kind == "resource":
        resource = value.get("resource") or {}
        mime = resource.get("mimeType") or mime
        name = name or str(resource.get("uri", "")).rsplit("/", 1)[-1]
        if "blob" in resource: source = inline(resource["blob"], mime)
        elif "text" in resource: source = inline(base64.b64encode(resource["text"].encode()).decode(), mime or "text/plain")
        else: source = resource.get("uri")
    elif kind == "content": return media_blocks(value.get("content"))
    if not isinstance(source, str) or not source: return []
    result = {"source": source, "name": name, "mimeType": mime}
    size = value.get("sizeBytes", value.get("size"))
    if isinstance(size, int) and not isinstance(size, bool) and size >= 0: result["sizeBytes"] = size
    return [result]

def inline(data, mime):
    if not isinstance(data, str): return None
    if len(data) > MAX_BYTES * 4 // 3 + 4096: return "luma-error:" + tr("附件超过64MiB", "Attachment exceeds 64 MiB")
    return data if data.startswith("data:") else "data:" + (mime or "application/octet-stream") + ";base64," + data

def format_diff_block(block):
    if not isinstance(block, dict):
        return ""
    path = block.get("path").strip() if isinstance(block.get("path"), str) else ""
    old_raw = block.get("oldText")
    if old_raw is None and "old_text" in block:
        old_raw = block.get("old_text")
    old_text = old_raw if isinstance(old_raw, str) else ""
    new_raw = block.get("newText")
    if new_raw is None and "new_text" in block:
        new_raw = block.get("new_text")
    new_text = new_raw if isinstance(new_raw, str) else ""
    from_label = "/dev/null" if old_raw is None and not old_text else (path or "a")
    to_label = path or "b"
    diff_lines = list(difflib.unified_diff(old_text.splitlines(), new_text.splitlines(), fromfile=from_label, tofile=to_label, lineterm=""))
    if diff_lines:
        return "\n".join(diff_lines)
    return new_text if new_text else path

def _extract_structured_text(value):
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        parts = []
        has_structured = False
        for item in value:
            extracted = _extract_structured_text(item)
            if extracted is not None:
                has_structured = True
                if extracted:
                    parts.append(extracted)
            elif isinstance(item, dict) and media_blocks(item):
                has_structured = True
        if has_structured:
            return "\n".join(parts)
        return None
    if not isinstance(value, dict):
        return None
    if media_blocks(value):
        return ""
    kind = value.get("type")
    if kind == "content":
        return _extract_structured_text(value.get("content"))
    if kind == "diff":
        return format_diff_block(value)
    if "text" in value:
        return value["text"] if isinstance(value["text"], str) else ""
    if "thinking" in value and isinstance(value["thinking"], str):
        return value["thinking"]
    if "stdout" in value or "stderr" in value:
        parts = []
        for key in ("stdout", "stderr"):
            chunk = value.get(key)
            if isinstance(chunk, str) and chunk:
                parts.append(chunk)
        if parts:
            return "\n".join(parts)
        if "output" in value:
            extracted = _extract_structured_text(value["output"])
            if extracted is not None:
                return extracted
        if all(value.get(k) in (None, "") for k in ("stdout", "stderr") if k in value):
            return ""
    if "output" in value:
        extracted = _extract_structured_text(value["output"])
        if extracted is not None:
            return extracted
    if "content" in value:
        extracted = _extract_structured_text(value["content"])
        if extracted is not None:
            return extracted
    if "result" in value:
        extracted = _extract_structured_text(value["result"])
        if extracted is not None:
            return extracted
    if "error" in value:
        err = value["error"]
        if isinstance(err, dict) and isinstance(err.get("message"), str):
            return err["message"]
        extracted = _extract_structured_text(err)
        if extracted is not None:
            return extracted
    if "message" in value and isinstance(value["message"], str) and set(value.keys()) <= {"message", "code", "name", "type", "status", "details", "stack"}:
        return value["message"]
    return None

def is_json_fallback_output(value, rendered):
    if not isinstance(rendered, str) or not rendered or isinstance(value, str):
        return False
    s = rendered.strip()
    if not (len(s) >= 2 and ((s[0] == "{" and s[-1] == "}") or (s[0] == "[" and s[-1] == "]"))):
        return False
    return _extract_structured_text(value) is None

def display_content(value):
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "\n".join(filter(None, (display_content(v) for v in value)))
    if not isinstance(value, dict):
        return ""
    extracted = _extract_structured_text(value)
    if extracted is not None:
        return extracted
    return json.dumps(display_object(value), indent=2, ensure_ascii=False)

def display_tool_output(content=None, raw_output=None):
    content_text = display_content(content) if content is not None else ""
    raw_text = display_content(raw_output) if raw_output is not None else ""
    if content_text:
        if raw_text and not is_json_fallback_output(raw_output, raw_text):
            if is_json_fallback_output(content, content_text):
                return raw_text
            if len(raw_text) > len(content_text) and content_text.strip() in raw_text:
                return raw_text
        return content_text
    return raw_text

def display_object(value):
    if isinstance(value, list): return [display_object(v) for v in value]
    if not isinstance(value, dict): return value
    return {k: tr("[附件数据]", "[attachment data]") if isinstance(v, str) and len(v) > 256 and (k in ("data", "blob", "image_url", "imageUrl", "audioUrl") or v.startswith("data:")) else display_object(v) for k, v in value.items()}

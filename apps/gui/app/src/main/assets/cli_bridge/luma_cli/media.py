import base64
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

def display_content(value):
    if isinstance(value, str): return value
    if isinstance(value, list): return "\n".join(filter(None, (display_content(v) for v in value)))
    if not isinstance(value, dict): return ""
    if media_blocks(value): return ""
    if value.get("type") == "content": return display_content(value.get("content"))
    if "text" in value: return value["text"] if isinstance(value["text"], str) else ""
    if "thinking" in value: return value["thinking"]
    return json.dumps(display_object(value), ensure_ascii=False)

def display_object(value):
    if isinstance(value, list): return [display_object(v) for v in value]
    if not isinstance(value, dict): return value
    return {k: tr("[附件数据]", "[attachment data]") if isinstance(v, str) and len(v) > 256 and (k in ("data", "blob", "image_url", "imageUrl", "audioUrl") or v.startswith("data:")) else display_object(v) for k, v in value.items()}

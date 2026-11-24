import base64
import copy
import datetime as dt
import fcntl
import json
import math
import mimetypes
import os
import re
import threading
import uuid
import time
from pathlib import Path
from .i18n import lang_code, tr
from .transport import Peer, Rejected, Conflict
from .media import media_blocks, display_content
from .native_owner import non_mcp_background_child_alive

_MEDIA_CACHE_LOCK = threading.Lock()
_IMAGE_EXTENSIONS = {
    "image/webp": ".webp",
    "image/jpeg": ".jpg",
    "image/jpg": ".jpg",
    "image/png": ".png",
    "image/gif": ".gif",
    "image/svg+xml": ".svg",
}

def _media_extension(mime_type):
    mime = (mime_type or "").split(";", 1)[0].strip().lower()
    return _IMAGE_EXTENSIONS.get(mime) or mimetypes.guess_extension(mime) or ".bin"

def ensure_root_media_cache_access(sessions_dir, file_path=None):
    if os.geteuid() != 0:
        return None
    sessions_dir = Path(sessions_dir)
    try:
        parent_stat = sessions_dir.parent.stat()
    except OSError:
        return None
    app_uid, app_gid = parent_stat.st_uid, parent_stat.st_gid
    if app_uid == 0:
        return None
    try:
        os.chmod(sessions_dir, 0o711)
    except OSError:
        pass
    media_cache = sessions_dir / "media-cache"
    if media_cache.is_dir():
        try:
            os.chmod(media_cache, 0o700)
            os.chown(media_cache, app_uid, app_gid)
        except OSError:
            pass
        if file_path is not None:
            try:
                os.chmod(file_path, 0o600)
                os.chown(file_path, app_uid, app_gid)
            except OSError:
                pass
        else:
            try:
                for entry in media_cache.iterdir():
                    if entry.is_file():
                        try:
                            os.chmod(entry, 0o600)
                            os.chown(entry, app_uid, app_gid)
                        except OSError:
                            pass
            except OSError:
                pass
    return (app_uid, app_gid)

def text(value):
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "\n".join(text(v) for v in value if isinstance(v, (dict, str)))
    if isinstance(value, dict):
        return value.get("text", value.get("thinking", ""))
    return ""

def user_text(value):
    if isinstance(value, list):
        return "\n".join(text(v) for v in value if isinstance(v, str) or isinstance(v, dict) and v.get("type") == "text")
    return text(value)

_TS_MICRO_LIMIT = 10_000_000_000_000

def parse_ts_ms(val):
    if isinstance(val, (int, float)) and not isinstance(val, bool) and math.isfinite(val):
        if val < 1_000_000_000:
            return 0
        if val < 10_000_000_000:
            return int(val * 1000)
        n = int(val)
        return n // 1000 if n > _TS_MICRO_LIMIT else n
    if isinstance(val, str):
        s = val.strip()
        if not s:
            return 0
        if s.isdigit():
            return parse_ts_ms(int(s))
        if re.fullmatch(r"\d+\.\d+", s):
            try:
                return parse_ts_ms(float(s))
            except ValueError:
                return 0
        try:
            if s.endswith("Z"):
                s = s[:-1] + "+00:00"
            s = re.sub(r"\.(\d{6})\d+", r".\1", s)
            parsed = dt.datetime.fromisoformat(s)
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=dt.timezone.utc)
            return max(0, int(parsed.timestamp() * 1000))
        except Exception:
            return 0
    return 0

def item(ident, role, body="", **extra):
    if not extra.get("timestampMs"):
        extra.pop("timestampMs", None)
    if not extra.get("completedAtMs"):
        extra.pop("completedAtMs", None)
    return {"id": str(ident), "role": role, "text": body, **extra}

def image_blocks(paths):
    result = []
    for name in paths:
        path = Path(name)
        mime = mimetypes.guess_type(name)[0]
        if mime not in ("image/png", "image/jpeg", "image/webp", "image/gif"):
            raise Rejected(tr("不支持的图片类型", "Unsupported image type"))
        if not path.is_file() or path.stat().st_size > 20 * 1024 * 1024:
            raise Rejected(tr("图片不可读或超过 20 MiB", "Image is unreadable or exceeds 20 MiB"))
        result.append({"type": "image", "data": base64.b64encode(path.read_bytes()).decode(), "mimeType": mime})
    return result

def _same_json(a, b):
    if type(a) is not type(b):
        return False
    if isinstance(a, dict):
        return len(a) == len(b) and all(k in b and _same_json(v, b[k]) for k, v in a.items())
    if isinstance(a, list):
        return len(a) == len(b) and all(_same_json(x, y) for x, y in zip(a, b))
    return a == b

class DeliveryPersistenceError(OSError):
    def __init__(self, phase, cause):
        self.phase = phase
        super().__init__(tr(f"发送账本保存失败（{type(cause).__name__}），待发已暂停", f"Failed to save delivery ledger ({type(cause).__name__}); pending messages paused"))

class Session:
    input_evidence_protocol = 0
    dialect = "rpc"
    correlated_delivery = False
    busy_default_steer = True
    check_peer_background_children = True
    def __init__(self, config, save):
        self.config, self.save = config, save
        self.lock = threading.RLock()
        self.operation = threading.RLock()
        self.answer_lock = threading.Lock()
        self.peer = None
        self.pending_requests = {}
        self.rows = {}
        self.media_files = {}
        self.subagents = {}
        self.seq = 0
        self.delivery_wake = threading.Event()
        self.delivery_worker = None
        self.delivery_storage_error = None
        self.released = False
        self.close_pending = False
        self.close_error = None
        self.terminate_on_close = False
        self.action_leases = 0
        self.last_activity = time.monotonic()
        self.release_snapshot_state = (False, False, False, False)
        self._probe_lock = threading.Lock()
        self._probe_cache = {}
        self._snapshot_probe_tls = threading.local()
        self._watch_notify = None
        for delivery in config.get("deliveries", {}).values():
            if delivery["state"] == "dispatching": delivery["state"] = "unknown"
        self.data = {"cli": config["cli"], "nativeId": config.get("nativeId", ""), "title": "",
                     "connected": False, "running": False, "model": config.get("model", ""), "effort": "",
                     "models": [], "permissions": [], "permission": "", "queue": [], "requests": [],
                     "metrics": [], "skills": [], "mcp": [], "capabilities": {}, "notice": "",
                     "permissionNotice": None, "planModeSupported": False, "planMode": False,
                     "skipPermissionsActive": False, "error": None}

    def touch(self):
        self.last_activity = time.monotonic()

    def _emit_watch(self):
        cb = self._watch_notify
        if cb:
            try: cb()
            except Exception: pass

    def update(self, **fields):
        if not fields: return
        notify_watch = False
        with self.lock:
            if fields.get("running") or self.data.get("running"):
                self.touch()
            changed = any(k not in self.data or not _same_json(self.data[k], v) for k, v in fields.items())
            if fields.get("nativeId"):
                if self.config.get("nativeId") != fields["nativeId"]:
                    changed = True
                self.config["nativeId"] = fields["nativeId"]
                self.save()
            if not changed:
                return
            notify_watch = any(
                k in fields and (k not in self.data or not _same_json(self.data[k], fields[k]))
                for k in ("running", "requests", "connected")
            )
            self.data.update(fields)
            self.touch()
            self.seq += 1
            self.delivery_wake.set()
        if notify_watch:
            self._emit_watch()

    def media(self, content):
        resources = media_blocks(content)
        directory = self.config.get("ownerDirectory")
        if not directory or not any(r["source"].startswith("data:") for r in resources): return resources
        with _MEDIA_CACHE_LOCK:
            lock = Path(directory).parent / "media-cache.lock"
            try:
                with lock.open("a+b") as handle:
                    os.chmod(lock, 0o600)
                    fcntl.flock(handle, fcntl.LOCK_EX)
                    return self._media(content)
            except OSError:
                return [{**r, "source": "luma-error:" + tr("无法保存会话附件", "Unable to save session attachment")} if r["source"].startswith("data:") else r for r in resources]

    def _media(self, content):
        resources = media_blocks(content)
        directory = self.config.get("ownerDirectory")
        if not directory: return resources
        for resource in resources:
            source = resource["source"]
            if not source.startswith("data:"): continue
            old = self.media_files.get(source)
            if old and Path(old).is_file():
                resource["source"] = old; resource["sizeBytes"] = Path(old).stat().st_size
                continue
            header, separator, data = source.partition(",")
            if not separator or ";base64" not in header: continue
            target = Path(directory).parent / "media-cache"
            target.mkdir(mode=0o700, parents=True, exist_ok=True)
            path = target / (str(uuid.uuid4()) + _media_extension(resource["mimeType"]))
            try:
                raw = base64.b64decode(data, validate=True)
                if len(raw) > 64 * 1024 * 1024: raise ValueError(tr("附件过大", "Attachment too large"))
                with path.open("xb") as out:
                    os.chmod(path, 0o600); out.write(raw)
                ensure_root_media_cache_access(target.parent, path)
                self.media_files[source] = str(path)
                while len(self.media_files) > 32 or sum(len(key) for key in self.media_files) > 16 * 1024 * 1024: self.media_files.pop(next(iter(self.media_files)))
                files = sorted(target.glob("*"), key=lambda f: f.stat().st_mtime)
                size = sum(f.stat().st_size for f in files)
                for old_file in files:
                    if size <= 192 * 1024 * 1024: break
                    if old_file == path: continue
                    old_size = old_file.stat().st_size; old_file.unlink(); size -= old_size
                resource["source"] = str(path); resource["sizeBytes"] = len(raw)
            except (OSError, ValueError):
                path.unlink(missing_ok=True)
                resource["source"] = "luma-error:" + tr("内嵌附件损坏或不可保存", "Embedded attachment is corrupted or cannot be saved")
        return resources

    def row(self, value):
        notify_watch = False
        with self.lock:
            if value.get("role") == "user" and not value.get("media"):
                delivery = self.config.get("deliveries", {}).get(value["id"], {})
                paths = delivery.get("payload", {}).get("images", [])
                if paths: value = {**value, "media": [{"source": p, "mimeType": mimetypes.guess_type(p)[0] or "image/*"} for p in paths]}
            old = self.rows.get(value["id"])
            if old is not None and _same_json(old, value):
                return
            if not self.data.get("running") or self.data.get("requests"):
                notify_watch = True
            self.rows[value["id"]] = value
            self.touch()
            self.seq += 1
        if notify_watch:
            self._emit_watch()

    def settle_incomplete_rows(self, status):
        with self.lock:
            for key, row in list(self.rows.items()):
                if row.get("status") in ("running", "in_progress", "pending"):
                    self.row({**row, "status": status})

    def subagent(self, ident, **fields):
        if not ident: return
        notify_watch = False
        with self.lock:
            existing = self.subagents.get(ident)
            old = existing if existing is not None else {"id": ident}
            for key in ("itemId", "turnId"):
                if old.get(key): fields[key] = old[key]
            nxt = {**old, **fields}
            if existing is not None and _same_json(existing, nxt):
                return
            if not self.data.get("running") and old.get("status") != nxt.get("status"):
                notify_watch = True
            self.subagents[ident] = nxt
            self.touch()
            self.seq += 1
        if notify_watch:
            self._emit_watch()

    def _save_delivery_state(self, phase):
        try:
            self.save()
        except Exception as cause:
            error = DeliveryPersistenceError(phase, cause)
            self.delivery_storage_error = str(error)
            self.data["error"] = str(error)
            self.seq += 1
            self.diagnostic("delivery-save-failed-" + phase, cause)
            self.delivery_wake.set()
            raise error from cause
        if self.delivery_storage_error is not None:
            if self.data.get("error") == self.delivery_storage_error: self.data["error"] = None
            self.delivery_storage_error = None
            self.seq += 1
            self.diagnostic("delivery-save-recovered")
            self.delivery_wake.set()

    def _checkpoint_native_evidence(self, phase):
        if self.delivery_storage_error is not None: return
        try: self._save_delivery_state(phase)
        except DeliveryPersistenceError: pass

    def _is_settled_uncertain(self, ident):
        if self.config.get("settledUnknown", {}).get(ident):
            return True
        delivery = self.config.get("deliveries", {}).get(ident)
        return bool(delivery and delivery.get("settled"))

    def settle_uncertain(self, ident):
        with self.lock:
            if not ident:
                return
            receipts = self.config.setdefault("receipts", {})
            if receipts.get(ident) in ("accepted", "rejected"):
                return
            if ident in receipts:
                self.config.setdefault("settledUnknown", {})[ident] = True
            delivery = self.config.get("deliveries", {}).get(ident)
            if delivery and delivery.get("state") in ("dispatching", "unknown"):
                delivery["settled"] = True
            try:
                self._checkpoint_native_evidence("settle-uncertain")
            finally:
                self.seq += 1
                self.delivery_wake.set()

    def receipt(self, ident, value, *, require_durable=False):
        with self.lock:
            receipts = self.config.setdefault("receipts", {})
            if receipts.get(ident) == "accepted" and value != "accepted": return
            receipts[ident] = value
            if value in ("accepted", "rejected"):
                settled = self.config.get("settledUnknown")
                if isinstance(settled, dict):
                    settled.pop(ident, None)
            delivery = self.config.get("deliveries", {}).get(ident)
            if delivery and value in ("accepted", "rejected"):
                delivery["state"] = value
                delivery.pop("settled", None)
            try:
                if require_durable: self._save_delivery_state("receipt")
                else: self._checkpoint_native_evidence("receipt")
            finally:
                self.seq += 1
                self.delivery_wake.set()

    def is_snapshot_probe(self):
        return bool(getattr(self._snapshot_probe_tls, "active", False))

    def cached_snapshot_probe(self, key, probe_fn, ttl=1.0, cold_timeout=0.2, pending_value=None):
        if not self.is_snapshot_probe():
            return probe_fn()
        now = time.monotonic()
        with self._probe_lock:
            if len(self._probe_cache) > 8:
                self._probe_cache.clear()
            entry = self._probe_cache.get(key)
            if entry is not None and not entry["running"] and entry["ts"] is not None and now - entry["ts"] < ttl:
                return entry["value"]
            if entry is not None and entry["ts"] is not None:
                if not entry["running"] and now - entry["ts"] >= ttl:
                    self._start_snapshot_probe_locked(key, probe_fn)
                return entry["value"]
            if entry is None:
                entry = {"ts": None, "value": pending_value, "running": False, "done": threading.Event()}
                self._probe_cache[key] = entry
            if not entry["running"]:
                self._start_snapshot_probe_locked(key, probe_fn)
            done = entry["done"]
        done.wait(cold_timeout)
        with self._probe_lock:
            cur = self._probe_cache.get(key)
            if cur is not None and cur["ts"] is not None:
                return cur["value"]
            return pending_value

    def _start_snapshot_probe_locked(self, key, probe_fn):
        entry = self._probe_cache.get(key)
        if entry is None:
            entry = {"ts": None, "value": None, "running": False, "done": threading.Event()}
            self._probe_cache[key] = entry
        entry["running"] = True
        done = entry["done"]
        def run():
            try:
                value = probe_fn()
            except Exception:
                value = entry["value"]
            now = time.monotonic()
            changed = False
            with self._probe_lock:
                cur = self._probe_cache.get(key)
                if cur is entry:
                    changed = entry["ts"] is not None and entry["value"] != value
                    entry["ts"] = now
                    entry["value"] = value
                    entry["running"] = False
            done.set()
            if changed:
                with self.lock:
                    self.seq += 1
        threading.Thread(target=run, daemon=True, name="LumaReleaseProbe").start()

    def _build_snapshot_locked(self, since, mode, releasable):
        release_state = (bool(mode), releasable, self.released, self.close_pending)
        if release_state != self.release_snapshot_state:
            self.release_snapshot_state = release_state
            self.seq += 1
        if since == self.seq: return {"unchanged": True, "revision": self.seq}
        observer = {"connected": False, "connecting": False, "canOpenTui": False} if self.close_pending else {}
        data_copy = {
            k: ([dict(x) if isinstance(x, dict) else x for x in v] if isinstance(v, list)
                else dict(v) if isinstance(v, dict) else v)
            for k, v in self.data.items()
        }
        messages_copy = [dict(r) for r in self.rows.values()]
        subagents_copy = [
            {**a, "messages": [dict(m) for m in a["messages"]]} if isinstance(a.get("messages"), list) else dict(a)
            for a in self.subagents.values()
        ]
        deliveries = self.config.get("deliveries", {})
        return {
            **data_copy, **observer,
            "capabilities": {**self.data["capabilities"], "release": bool(mode)},
            "releasable": releasable, "released": self.released, "releasePending": self.close_pending,
            "error": self.delivery_storage_error or self.close_error or self.data.get("error"),
            "messages": messages_copy, "subAgents": subagents_copy, "revision": self.seq,
            "receipts": dict(self.config.get("receipts", {})),
            "inputEvidenceProtocol": self.input_evidence_protocol,
            "consumedSubmissions": dict(self.config.get("consumedSubmissions", {})),
            "admittedDisplaySubmissions": dict(self.config.get("admittedDisplaySubmissions", {})),
            "deliveries": {key: {"state": value["state"]} for key, value in deliveries.items()},
            "takebacks": {key: {"operationId": value["takebackId"], "payload": dict(value["payload"])}
                          for key, value in deliveries.items()
                          if value.get("takebackId") and value["state"] == "cancelled"},
            "hostQueue": [{"id": key, "text": value["payload"]["text"],
                           "status": tr("插话 · 等待接收", "Steer · Waiting") if value["payload"].get("steer") else tr("待发", "Queued"),
                           "clientUserMessageId": key,
                           "canRemove": not bool(value["payload"].get("steer")),
                           "canSteer": bool(self.data["running"] and not value["payload"].get("steer") and self.can_steer(value["payload"])),
                           "canEdit": not bool(value["payload"].get("steer")), "hostOwned": True}
                          for key, value in deliveries.items() if value["state"] == "queued"] + self.extra_host_queue(),
        }

    def snapshot(self, since=None, touch=True):
        with self.lock:
            if touch:
                self.touch()
            mode = self.release_mode()
            mem_blocker = self.fast_release_blocker()
            if not mode or mem_blocker is not None:
                return self._build_snapshot_locked(since, mode, False)
        prev_probe = getattr(self._snapshot_probe_tls, "active", False)
        self._snapshot_probe_tls.active = True
        try:
            external_blocker = self.release_blocker()
        finally:
            self._snapshot_probe_tls.active = prev_probe
        with self.lock:
            mode = self.release_mode()
            mem_blocker = self.fast_release_blocker()
            releasable = bool(mode) and mem_blocker is None and external_blocker is None
            return self._build_snapshot_locked(since, mode, releasable)

    def extra_host_queue(self):
        return []

    def start(self, argv, env_overrides=None):
        env = os.environ.copy()
        env["LUMA_LANG"] = lang_code()
        if self.config.get("browserConfig"):
            env["LUMA_BROWSER_CONFIG"] = self.config["browserConfig"]
        if env_overrides: env.update(env_overrides)
        self.peer = Peer(argv, self.config["cwd"], self.event, self.exited, env, lambda: bool(self.pending_requests))
        self.peer.start_read()

    def consumed(self, ident):
        with self.lock:
            if not ident or self.config.get("consumedSubmissions", {}).get(ident): return
            self.config.setdefault("consumedSubmissions", {})[ident] = True
            row = self.rows.get(ident)
            if row: self.row({**row, "origin": "native"})
            try: self._checkpoint_native_evidence("consumed")
            finally: self.seq += 1

    def call(self, method, params=None, timeout=45, pause_for_user=True, on_uncertain=None):
        options = {"pause_for_user": False} if not pause_for_user else {}
        if on_uncertain is not None: options["on_uncertain"] = on_uncertain
        return self.peer.call(method, params, self.dialect, timeout, **options)

    def diagnostic(self, event, error=None):
        directory = self.config.get("ownerDirectory")
        if not directory: return
        try:
            target = Path(directory); target.mkdir(mode=0o700, parents=True, exist_ok=True)
            file = target / "native-owner.log"
            with self.lock:
                if file.exists() and file.stat().st_size > 64 * 1024:
                    file.write_bytes(file.read_bytes()[-32 * 1024:])
                with file.open("a") as out:
                    os.chmod(file, 0o600)
                    out.write(json.dumps({"time":time.time(), "event":event, "cause":type(error).__name__ if error else ""}) + "\n")
        except OSError: pass

    def exited(self, error):
        self.diagnostic("observer-disconnected", error)
        self.update(connected=False, requests=[], error=str(error))

    def request(self, key, raw, title, body, choices=None, questions=None, external_url=None, submit_choice=None, skip_choice=None):
        with self.lock:
            self.pending_requests[str(key)] = raw
            requests = [r for r in self.data["requests"] if r["id"] != str(key)]
            requests.append({"id": str(key), "title": title, "body": body,
                             "choices": choices or [], "questions": questions or [],
                             **({"submitChoice": submit_choice, "skipChoice": skip_choice} if questions else {}),
                             **({"externalUrl": external_url} if external_url else {})})
            self.update(requests=requests)

    def answered(self, key):
        with self.lock:
            self.pending_requests.pop(key, None)
            self.update(requests=[r for r in self.data["requests"] if r["id"] != key])

    def submit(self, payload):
        ident = payload["submissionId"]
        with self.operation:
            with self.lock: self.require_observer()
            receipts = self.config.setdefault("receipts", {})
            if ident in receipts:
                return {"receipt": receipts[ident]}
            self.validate_prompt(payload)
            self.receipt(ident, "unknown", require_durable=True)
            try:
                accepted = self.prompt(payload)
            except Rejected:
                self.receipt(ident, "rejected")
                raise
            if accepted:
                self.receipt(ident, "accepted")
            return {"receipt": receipts[ident]}

    def can_steer(self, payload):
        return bool(self.data["capabilities"].get("steer"))

    def validate_prompt(self, payload):
        if not self.data["connected"]:
            raise Rejected(tr("CLI 未连接", "CLI is not connected"))
        if not payload.get("text", "").strip() and not payload.get("images"):
            raise Rejected(tr("请输入消息或图片", "Enter a message or attach an image"))
        caps = self.data["capabilities"]
        if payload.get("images") and not caps.get("images"):
            raise Rejected(tr("此 CLI 协议当前不支持图片", "This CLI protocol does not support images"))
        if payload.get("skills") and not caps.get("skills"):
            raise Rejected(tr("此 CLI 未提供逐消息 Skill 选择", "This CLI does not support per-message skill selection"))
        if payload.get("steer") and not self.can_steer(payload):
            raise Rejected(tr("此 CLI 未提供运行中引导", "This CLI does not support steering while running"))
        if self.data["running"] and not caps.get("queue") and not payload.get("steer"):
            raise Rejected(tr("此 CLI 没有原生待发队列，请等待当前回合结束", "This CLI has no native pending queue; wait for the current turn to finish"))

    def admit(self, payload):
        ident = payload["submissionId"]
        if not isinstance(ident, str) or not ident or len(ident) > 160: raise Rejected(tr("无效发送标识", "Invalid submission ID"))
        payload = copy.deepcopy(payload)
        with self.lock:
            self.require_observer()
            deliveries = self.config.setdefault("deliveries", {})
            old = deliveries.get(ident)
            if old:
                if old["payload"] != payload: raise Rejected(tr("发送标识已用于不同内容", "Submission ID was already used for different content"))
                return {"hostAdmitted": True, "deliveryState": old["state"]}
            if ident in self.config.get("receipts", {}):
                return {"hostAdmitted": True, "deliveryState": self.config["receipts"][ident]}
            if not payload.get("text", "").strip() and not payload.get("images"):
                raise Rejected(tr("请输入消息或图片", "Enter a message or attach an image"))
            deliveries[ident] = {"state": "queued", "payload": payload,
                "settings": {k: self.data.get(k, "") for k in ("model", "effort", "permission", "speed")}}
            try: self._save_delivery_state("admission")
            except DeliveryPersistenceError:
                del deliveries[ident]
                raise
            self.seq += 1
            self.start_delivery_worker()
            self.delivery_wake.set()
            return {"hostAdmitted": True, "deliveryState": "queued"}

    def start_delivery_worker(self):
        with self.lock:
            if self.released or self.close_pending or not any(d["state"] == "queued" for d in self.config.get("deliveries", {}).values()): return
            if self.delivery_worker and self.delivery_worker.is_alive(): return
            self.delivery_worker = threading.Thread(target=self.deliver_pending, daemon=True, name="LumaDelivery")
            self.delivery_worker.start()
            self.delivery_wake.set()

    def deliver_pending(self):
        while True:
            with self.lock:
                if self.released or self.close_pending or not any(d["state"] == "queued" for d in self.config.get("deliveries", {}).values()):
                    self.delivery_worker = None
                    return
            self.delivery_wake.wait()
            self.delivery_wake.clear()
            with self.operation:
                with self.lock:
                    if self.released or self.close_pending: continue
                    deliveries = self.config.get("deliveries", {})
                    if self.delivery_storage_error is not None: continue
                    if not self.data["connected"] or self.data.get("connecting"): continue
                    if not self.correlated_delivery and any(
                        v in ("unknown", "pending") and not (v == "unknown" and self._is_settled_uncertain(k))
                        for k, v in self.config.get("receipts", {}).items()
                    ): continue
                    caps = self.data["capabilities"]
                    def runnable(value):
                        if value["state"] != "queued": return False
                        if not self.data["running"]: return True
                        can_steer = self.can_steer(value["payload"])
                        want_steer = bool(value["payload"].get("steer")) or self.busy_default_steer
                        return (want_steer and can_steer) or bool(caps.get("queue"))
                    candidate = next(((key, value) for key, value in deliveries.items() if runnable(value)), None)
                    if not candidate: continue
                    ident, delivery = candidate
                    can_steer = self.can_steer(delivery["payload"])
                    want_steer = bool(delivery["payload"].get("steer")) or self.busy_default_steer
                    payload = copy.deepcopy(delivery["payload"])
                    payload["steer"] = bool(self.data["running"] and want_steer and can_steer)
                    previous = copy.deepcopy(delivery)
                    receipts = self.config.setdefault("receipts", {})
                    had_receipt = ident in receipts
                    previous_receipt = receipts.get(ident)
                    def restore_before_dispatch():
                        delivery.clear(); delivery.update(previous)
                        if had_receipt: receipts[ident] = previous_receipt
                        else: receipts.pop(ident, None)
                        self.seq += 1
                    try:
                        if any(self.data.get(k, "") != v for k, v in delivery["settings"].items()):
                            raise Rejected(tr("待发消息的会话设置已改变", "Session settings changed while message was queued"))
                        self.validate_prompt(payload)
                    except Rejected as exc:
                        delivery["error"] = str(exc)
                        try: self.receipt(ident, "rejected", require_durable=True)
                        except DeliveryPersistenceError: restore_before_dispatch()
                        else: self.update(error=str(exc))
                        continue
                    delivery["state"] = "dispatching"
                    receipts[ident] = "unknown"
                    try: self._save_delivery_state("claim")
                    except DeliveryPersistenceError:
                        restore_before_dispatch()
                        continue
                    self.seq += 1
                try:
                    if self.prompt(payload): self.receipt(ident, "accepted")
                except DeliveryPersistenceError:
                    pass
                except Rejected as exc:
                    try: self.receipt(ident, "rejected")
                    except DeliveryPersistenceError: pass
                    else: self.update(error=str(exc))
                except Exception as exc:
                    self.update(error=str(exc))
                finally:
                    with self.lock:
                        if delivery["state"] == "dispatching":
                            delivery["state"] = "unknown"
                            if self.delivery_storage_error is None:
                                try: self._save_delivery_state("settlement")
                                except DeliveryPersistenceError: pass
                            self.seq += 1
                        if self.delivery_storage_error is None: self.delivery_wake.set()

    def steer_pending(self, ident):
        with self.lock:
            self.require_observer()
            if not self.data["running"]: raise Rejected(tr("当前任务已结束，消息将按队列顺序发送", "Current turn has ended; message will be sent in queue order"))
            delivery = self.config.get("deliveries", {}).get(ident)
            if not delivery or delivery["state"] != "queued": raise Rejected(tr("消息已离开待发队列", "Message is no longer in the pending queue"))
            if not self.can_steer(delivery["payload"]): raise Rejected(tr("此消息不支持在运行中插话", "This message cannot steer while running"))
            if delivery["payload"].get("steer"): return {"steered": True}
            delivery["payload"]["steer"] = True
            try: self._save_delivery_state("steer")
            except DeliveryPersistenceError:
                delivery["payload"]["steer"] = False
                raise
            self.seq += 1
            self.start_delivery_worker()
            self.delivery_wake.set()
            return {"steered": True}

    def cancel_pending(self, ident):
        with self.lock:
            self.require_observer()
            delivery = self.config.get("deliveries", {}).get(ident)
            if not delivery or delivery["state"] != "queued": raise Rejected(tr("消息已离开待发队列", "Message is no longer in the pending queue"))
            delivery["state"] = "cancelled"
            try: self._save_delivery_state("cancellation")
            except DeliveryPersistenceError:
                delivery["state"] = "queued"
                raise
            self.seq += 1
            self.delivery_wake.set()
            return {"cancelled": True}

    def take_back(self, ident, operation_id, expected):
        if not isinstance(operation_id, str) or not 0 < len(operation_id) <= 160:
            raise Rejected(tr("无效取回标识", "Invalid take-back ID"))
        with self.lock:
            self.require_observer()
            delivery = self.config.get("deliveries", {}).get(ident)
            if not delivery: raise Rejected(tr("消息已离开待发队列", "Message is no longer in the pending queue"))
            payload = delivery["payload"]
            actual = {key: payload.get(key, [] if key != "text" else "") for key in ("text", "images", "skills")}
            if actual != expected: raise Rejected(tr("取回内容与原待发身份不一致", "Take-back payload does not match queued item"))
            if delivery.get("takebackId") == operation_id and delivery["state"] == "cancelled":
                return {"takenBack": True}
            if delivery["state"] != "queued" or delivery.get("takebackId") or ident in self.config.get("receipts", {}):
                raise Rejected(tr("消息已开始投递，不能返回输入框", "Message dispatch has started and cannot be returned to composer"))
            delivery["state"] = "cancelled"
            delivery["takebackId"] = operation_id
            try: self._save_delivery_state("takeback")
            except DeliveryPersistenceError:
                delivery["state"] = "queued"
                delivery.pop("takebackId", None)
                raise
            self.seq += 1
            self.delivery_wake.set()
            return {"takenBack": True}

    def action(self, name, data):
        with self.lock:
            self.require_observer()
            self.touch()
            self.action_leases += 1
        try: return self._action(name, data)
        finally:
            with self.lock:
                self.action_leases -= 1
                self.touch()

    def _action(self, name, data):
        if name == "submit": return self.admit(data)
        if name == "steer-pending": return self.steer_pending(data["id"])
        if name == "cancel-pending": return self.cancel_pending(data["id"])
        if name == "take-back": return self.take_back(data["id"], data["operationId"], data["expectedPayload"])
        if name == "send":
            return self.submit(data)
        if name == "answer":
            with self.answer_lock: return self.answer(data)
        if name == "stop": return self.stop()
        if name == "terminal": return {"terminal": self.terminal()}
        with self.operation:
            if name == "refresh":
                result = self.refresh()
                with self.lock:
                    if self.delivery_storage_error is not None: self._save_delivery_state("recovery")
                return result
            if name == "configure":
                if any(
                    d["state"] in ("queued", "dispatching", "unknown") and not (d["state"] == "unknown" and self._is_settled_uncertain(k))
                    for k, d in self.config.get("deliveries", {}).items()
                ):
                    raise Rejected(tr("请等待已提交消息结算后修改设置", "Wait for submitted messages to settle before changing settings"))
                return self.configure(data)
            if name == "rename": return self.rename(data["title"])
            if name == "compact": return self.compact()
            if name == "queue": return self.queue_change(data)
        raise Rejected(tr("不支持的操作：", "Unsupported action: ") + name)

    def require_observer(self):
        if self.close_pending: raise Conflict(tr("会话资源正在释放，请使用原会话身份重新连接完成清理", "Session resources are being released; reconnect with original session identity to finish cleanup"))
        if self.released: raise Conflict(tr("会话运行资源已释放，请使用原会话身份重新连接", "Session runtime resources have been released; reconnect with original session identity"))

    def release_mode(self):
        return None

    def fast_release_blocker(self):
        with self.lock:
            if self.released: return tr("会话运行资源已释放", "Session runtime resources already released")
            if self.close_pending: return tr("会话资源释放尚未完成", "Session resource release is still in progress")
            if not self.release_mode(): return tr("此原生入口尚不支持安全释放后恢复", "This native entry does not support safe release and resume")
            if not self.config.get("nativeId"): return tr("原生会话身份尚未确认", "Native session identity has not been confirmed")
            if self.action_leases: return tr("会话操作仍在进行", "Session action is still in progress")
            if not self.data["connected"] or self.data.get("connecting") or not self.peer or self.peer.closed:
                return tr("原生连接状态尚未确认", "Native connection state has not been confirmed")
            if self.data["running"] or getattr(self, "current_submission", None): return tr("原生回合尚未结算", "Native turn has not settled")
            if self.pending_requests or self.data["requests"]: return tr("原生请求等待处理", "Native request is awaiting user action")
            if self.data["queue"] or self.data.get("nativeQueuePending"): return tr("原生队列尚未清空", "Native queue is not empty")
            if self.delivery_storage_error is not None: return tr("发送账本尚未可靠保存", "Delivery ledger has not been reliably saved")
            if any(
                d["state"] in ("queued", "dispatching", "unknown", "pending") and not (d["state"] == "unknown" and self._is_settled_uncertain(k))
                for k, d in self.config.get("deliveries", {}).items()
            ):
                return tr("后台消息尚未结算", "Background messages have not settled")
            if any(
                r in ("unknown", "pending", "dispatching", "queued") and not (r == "unknown" and self._is_settled_uncertain(k))
                for k, r in self.config.get("receipts", {}).items()
            ):
                return tr("原生送达尚未确认", "Native delivery receipt has not been confirmed")
            terminal = {"succeeded", "completed", "failed", "cancelled", "closed", "aborted"}
            if any(a.get("status") not in terminal for a in self.subagents.values()): return tr("子代理尚未确认结束", "Subagent has not been confirmed finished")
            if any(r.get("role") == "tool" and r.get("status") in ("running", "in_progress", "pending", "unknown") for r in self.rows.values()):
                return tr("工具状态尚未确认结束", "Tool status has not been confirmed finished")
            if getattr(self.peer, "pending", {}): return tr("原生协议请求尚未结束", "Native protocol request is still pending")
            return None

    def release_blocker(self):
        with self.lock:
            blocker = self.fast_release_blocker()
            if blocker is not None:
                return blocker
            pid = None
            if self.check_peer_background_children:
                proc = getattr(self.peer, "proc", None) if self.peer else None
                cand = getattr(proc, "pid", None)
                if isinstance(cand, int) and cand > 0:
                    pid = cand
        if pid is None:
            return None
        return self.cached_snapshot_probe(
            ("peer-bg", pid),
            lambda: tr("后台子进程仍在运行", "Background subprocess is still running") if non_mcp_background_child_alive(pid) else None,
            pending_value=tr("正在确认后台子进程状态", "Checking background subprocess status"),
        )

    def release_resources(self, native, protocol=None):
        if not self.operation.acquire(blocking=False): raise Conflict(tr("会话操作仍在进行", "Session action is still in progress"))
        try:
            self.terminate_on_close = True
            with self.lock:
                if not native or native != self.config.get("nativeId"): raise Conflict(tr("原生会话身份不匹配", "Native session identity mismatch"))
                if protocol is not None and protocol != self.config.get("nativeProtocol"): raise Conflict(tr("原生协议身份不匹配", "Native protocol identity mismatch"))
                already_pending = self.close_pending
                if not already_pending:
                    blocker = self.fast_release_blocker()
                    if blocker: raise Conflict(blocker)
            if not already_pending:
                blocker = self.release_blocker()
                if blocker: raise Conflict(blocker)
                with self.lock:
                    if not native or native != self.config.get("nativeId"): raise Conflict(tr("原生会话身份不匹配", "Native session identity mismatch"))
                    if protocol is not None and protocol != self.config.get("nativeProtocol"): raise Conflict(tr("原生协议身份不匹配", "Native protocol identity mismatch"))
                    if not self.close_pending:
                        blocker = self.fast_release_blocker()
                        if blocker: raise Conflict(blocker)
                        self.save()
                        self.close_pending = True
                        self.seq += 1
                        self.delivery_wake.set()
            self.finish_observer_close()
            original_protocol = self.config.get("nativeProtocol")
            return {"released": True, "nativeId": native, **({"nativeProtocol": original_protocol} if original_protocol is not None else {})}
        finally:
            self.terminate_on_close = False
            self.operation.release()

    def destroy_resources(self):
        with self.lock:
            self.close_pending = True
            self.released = True
            self.seq += 1
            self.delivery_wake.set()
            peer = self.peer
            self.peer = None
        tail_stop = getattr(self, "tail_stop", None)
        if tail_stop is not None:
            try: tail_stop.set()
            except Exception: pass
        if peer is not None:
            try: peer.close()
            except Exception: pass
        browser = getattr(self, "auth_browser", None)
        if browser is not None:
            try: browser.close()
            except Exception: pass
        owner = getattr(self, "owner", None)
        if owner is not None:
            try: owner.stop(force=True)
            except Exception: pass
        temp_mod = getattr(self, "permission_module", None)
        if temp_mod is not None:
            try: temp_mod.unlink(missing_ok=True)
            except OSError: pass
            self.permission_module = None
        with self._probe_lock:
            self._probe_cache.clear()
        with self.lock:
            self.close_pending = False
            self.close_error = None
            self.rows.clear(); self.subagents.clear(); self.media_files.clear()
            self.data.update(connected=False, released=True, models=[], skills=[], mcp=[], metrics=[])
            self.seq += 1

    def retire_observer(self):
        if not self.operation.acquire(blocking=False): raise Conflict(tr("旧会话操作仍在进行", "Previous session action is still in progress"))
        try:
            with self.lock:
                if self.action_leases: raise Conflict(tr("旧会话操作仍在进行", "Previous session action is still in progress"))
                self.close_pending = True
                self.seq += 1
                self.delivery_wake.set()
            self.finish_observer_close()
        finally: self.operation.release()

    def finish_observer_close(self):
        try:
            if self.peer: self.peer.close()
            owner = getattr(self, "owner", None)
            if getattr(self, "terminate_on_close", False) and owner is not None:
                owner.stop()
            temp_mod = getattr(self, "permission_module", None)
            if temp_mod is not None:
                try: temp_mod.unlink(missing_ok=True)
                except OSError: pass
                self.permission_module = None
        except Exception as exc:
            with self.lock:
                self.close_error = tr("会话资源释放未完成，请重试释放或使用原记录重新连接完成清理", "Session resource release incomplete; retry release or reconnect with original record to finish cleanup")
                self.seq += 1
            self.diagnostic("observer-close-failed", exc)
            raise OSError(self.close_error) from exc
        with self._probe_lock:
            self._probe_cache.clear()
        with self.lock:
            self.released = True
            self.close_pending = False
            self.close_error = None
            self.peer = None
            self.rows.clear(); self.subagents.clear(); self.media_files.clear()
            self.data.update(connected=False, released=True, models=[], skills=[], mcp=[], metrics=[])
            self.seq += 1

    def event(self, frame): pass
    def refresh(self): return {}
    def configure(self, data): raise Rejected(tr("此原生协议未提供当前设置接口", "This native protocol does not support changing settings"))
    def rename(self, title): raise Rejected(tr("此原生协议未提供改名接口", "This native protocol does not support renaming"))
    def compact(self): raise Rejected(tr("此原生协议未提供压缩接口", "This native protocol does not support compaction"))
    def stop(self): raise Rejected(tr("此原生协议未提供中断接口", "This native protocol does not support stopping"))
    def queue_change(self, data): raise Rejected(tr("此原生队列没有该操作", "This native queue does not support that action"))
    def answer(self, data): raise Rejected(tr("审批不存在或已过期", "Approval request does not exist or has expired"))
    def terminal(self): raise Rejected(tr("此原生入口尚未提供共享 TUI", "This native entry does not support shared TUI"))

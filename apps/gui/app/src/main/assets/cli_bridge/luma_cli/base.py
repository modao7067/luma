import base64
import copy
import fcntl
import json
import mimetypes
import os
import threading
import uuid
import time
from pathlib import Path
from .transport import Peer, Rejected, Conflict
from .media import media_blocks, display_content

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

def item(ident, role, body="", **extra):
    return {"id": str(ident), "role": role, "text": body, **extra}

def image_blocks(paths):
    result = []
    for name in paths:
        path = Path(name)
        mime = mimetypes.guess_type(name)[0]
        if mime not in ("image/png", "image/jpeg", "image/webp", "image/gif"):
            raise Rejected("不支持的图片类型")
        if not path.is_file() or path.stat().st_size > 20 * 1024 * 1024:
            raise Rejected("图片不可读或超过 20 MiB")
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
        super().__init__(f"发送账本保存失败（{type(cause).__name__}），待发已暂停")

class Session:
    input_evidence_protocol = 0
    dialect = "rpc"
    correlated_delivery = False
    busy_default_steer = True
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
        self.release_snapshot_state = (False, False, False, False)
        for delivery in config.get("deliveries", {}).values():
            if delivery["state"] == "dispatching": delivery["state"] = "unknown"
        self.data = {"cli": config["cli"], "nativeId": config.get("nativeId", ""), "title": "",
                     "connected": False, "running": False, "model": config.get("model", ""), "effort": "",
                     "models": [], "permissions": [], "permission": "", "queue": [], "requests": [],
                     "metrics": [], "skills": [], "mcp": [], "capabilities": {}, "notice": "", "error": None}

    def update(self, **fields):
        if not fields: return
        with self.lock:
            changed = any(k not in self.data or not _same_json(self.data[k], v) for k, v in fields.items())
            if fields.get("nativeId"):
                if self.config.get("nativeId") != fields["nativeId"]:
                    changed = True
                self.config["nativeId"] = fields["nativeId"]
                self.save()
            if not changed:
                return
            self.data.update(fields)
            self.seq += 1
            self.delivery_wake.set()

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
                return [{**r, "source": "luma-error:无法保存会话附件"} if r["source"].startswith("data:") else r for r in resources]

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
                if len(raw) > 64 * 1024 * 1024: raise ValueError("附件过大")
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
                resource["source"] = "luma-error:内嵌附件损坏或不可保存"
        return resources

    def row(self, value):
        with self.lock:
            if value.get("role") == "user" and not value.get("media"):
                delivery = self.config.get("deliveries", {}).get(value["id"], {})
                paths = delivery.get("payload", {}).get("images", [])
                if paths: value = {**value, "media": [{"source": p, "mimeType": mimetypes.guess_type(p)[0] or "image/*"} for p in paths]}
            old = self.rows.get(value["id"])
            if old is not None and _same_json(old, value):
                return
            self.rows[value["id"]] = value
            self.seq += 1

    def settle_incomplete_rows(self, status):
        with self.lock:
            for key, row in list(self.rows.items()):
                if row.get("status") in ("running", "in_progress", "pending"):
                    self.row({**row, "status": status})

    def subagent(self, ident, **fields):
        if not ident: return
        with self.lock:
            existing = self.subagents.get(ident)
            old = existing if existing is not None else {"id": ident}
            for key in ("itemId", "turnId"):
                if old.get(key): fields[key] = old[key]
            nxt = {**old, **fields}
            if existing is not None and _same_json(existing, nxt):
                return
            self.subagents[ident] = nxt
            self.seq += 1

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

    def receipt(self, ident, value, *, require_durable=False):
        with self.lock:
            receipts = self.config.setdefault("receipts", {})
            if receipts.get(ident) == "accepted" and value != "accepted": return
            receipts[ident] = value
            delivery = self.config.get("deliveries", {}).get(ident)
            if delivery and value in ("accepted", "rejected"):
                delivery["state"] = value
            try:
                if require_durable: self._save_delivery_state("receipt")
                else: self._checkpoint_native_evidence("receipt")
            finally:
                self.seq += 1
                self.delivery_wake.set()

    def snapshot(self, since=None):
        with self.lock:
            mode = self.release_mode()
            releasable = self.release_blocker() is None
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
                               "status": "插话 · 等待接收" if value["payload"].get("steer") else "待发",
                               "clientUserMessageId": key,
                               "canRemove": not bool(value["payload"].get("steer")),
                               "canSteer": bool(self.data["running"] and not value["payload"].get("steer") and self.can_steer(value["payload"])),
                               "canEdit": not bool(value["payload"].get("steer")), "hostOwned": True}
                              for key, value in deliveries.items() if value["state"] == "queued"] + self.extra_host_queue(),
            }

    def extra_host_queue(self):
        return []

    def start(self, argv, env_overrides=None):
        env = os.environ.copy()
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
            raise Rejected("CLI 未连接")
        if not payload.get("text", "").strip() and not payload.get("images"):
            raise Rejected("请输入消息或图片")
        caps = self.data["capabilities"]
        if payload.get("images") and not caps.get("images"):
            raise Rejected("此 CLI 协议当前不支持图片")
        if payload.get("skills") and not caps.get("skills"):
            raise Rejected("此 CLI 未提供逐消息 Skill 选择")
        if payload.get("steer") and not self.can_steer(payload):
            raise Rejected("此 CLI 未提供运行中引导")
        if self.data["running"] and not caps.get("queue") and not payload.get("steer"):
            raise Rejected("此 CLI 没有原生待发队列，请等待当前回合结束")

    def admit(self, payload):
        ident = payload["submissionId"]
        if not isinstance(ident, str) or not ident or len(ident) > 160: raise Rejected("无效发送标识")
        payload = copy.deepcopy(payload)
        with self.lock:
            self.require_observer()
            deliveries = self.config.setdefault("deliveries", {})
            old = deliveries.get(ident)
            if old:
                if old["payload"] != payload: raise Rejected("发送标识已用于不同内容")
                return {"hostAdmitted": True, "deliveryState": old["state"]}
            if ident in self.config.get("receipts", {}):
                return {"hostAdmitted": True, "deliveryState": self.config["receipts"][ident]}
            if not payload.get("text", "").strip() and not payload.get("images"):
                raise Rejected("请输入消息或图片")
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
                    if not self.correlated_delivery and any(v in ("unknown", "pending") for v in self.config.get("receipts", {}).values()): continue
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
                            raise Rejected("待发消息的会话设置已改变")
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
            if not self.data["running"]: raise Rejected("当前任务已结束，消息将按队列顺序发送")
            delivery = self.config.get("deliveries", {}).get(ident)
            if not delivery or delivery["state"] != "queued": raise Rejected("消息已离开待发队列")
            if not self.can_steer(delivery["payload"]): raise Rejected("此消息不支持在运行中插话")
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
            if not delivery or delivery["state"] != "queued": raise Rejected("消息已离开待发队列")
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
            raise Rejected("无效取回标识")
        with self.lock:
            self.require_observer()
            delivery = self.config.get("deliveries", {}).get(ident)
            if not delivery: raise Rejected("消息已离开待发队列")
            payload = delivery["payload"]
            actual = {key: payload.get(key, [] if key != "text" else "") for key in ("text", "images", "skills")}
            if actual != expected: raise Rejected("取回内容与原待发身份不一致")
            if delivery.get("takebackId") == operation_id and delivery["state"] == "cancelled":
                return {"takenBack": True}
            if delivery["state"] != "queued" or delivery.get("takebackId") or ident in self.config.get("receipts", {}):
                raise Rejected("消息已开始投递，不能返回输入框")
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
            self.action_leases += 1
        try: return self._action(name, data)
        finally:
            with self.lock: self.action_leases -= 1

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
                if any(d["state"] in ("queued", "dispatching", "unknown") for d in self.config.get("deliveries", {}).values()):
                    raise Rejected("请等待已提交消息结算后修改设置")
                return self.configure(data)
            if name == "rename": return self.rename(data["title"])
            if name == "compact": return self.compact()
            if name == "queue": return self.queue_change(data)
        raise Rejected("不支持的操作：" + name)

    def require_observer(self):
        if self.close_pending: raise Conflict("会话资源正在释放，请使用原会话身份重新连接完成清理")
        if self.released: raise Conflict("会话运行资源已释放，请使用原会话身份重新连接")

    def release_mode(self):
        return None

    def release_blocker(self):
        if self.released: return "会话运行资源已释放"
        if self.close_pending: return "会话资源释放尚未完成"
        if not self.release_mode(): return "此原生入口尚不支持安全释放后恢复"
        if not self.config.get("nativeId"): return "原生会话身份尚未确认"
        if self.action_leases: return "会话操作仍在进行"
        if not self.data["connected"] or self.data.get("connecting") or not self.peer or self.peer.closed:
            return "原生连接状态尚未确认"
        if self.data["running"] or getattr(self, "current_submission", None): return "原生回合尚未结算"
        if self.pending_requests or self.data["requests"]: return "原生请求等待处理"
        if self.data["queue"] or self.data.get("nativeQueuePending"): return "原生队列尚未清空"
        if self.delivery_storage_error is not None: return "发送账本尚未可靠保存"
        if any(d["state"] in ("queued", "dispatching", "unknown", "pending") for d in self.config.get("deliveries", {}).values()):
            return "后台消息尚未结算"
        if any(r in ("unknown", "pending", "dispatching", "queued") for r in self.config.get("receipts", {}).values()):
            return "原生送达尚未确认"
        terminal = {"succeeded", "completed", "failed", "cancelled", "closed", "aborted"}
        if any(a.get("status") not in terminal for a in self.subagents.values()): return "子代理尚未确认结束"
        if any(r.get("role") == "tool" and r.get("status") in ("running", "in_progress", "pending", "unknown") for r in self.rows.values()):
            return "工具状态尚未确认结束"
        if getattr(self.peer, "pending", {}): return "原生协议请求尚未结束"
        return None

    def release_resources(self, native, protocol=None):
        if not self.operation.acquire(blocking=False): raise Conflict("会话操作仍在进行")
        try:
            self.terminate_on_close = True
            with self.lock:
                if not native or native != self.config.get("nativeId"): raise Conflict("原生会话身份不匹配")
                if protocol is not None and protocol != self.config.get("nativeProtocol"): raise Conflict("原生协议身份不匹配")
                if not self.close_pending:
                    blocker = self.release_blocker()
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
        with self.lock:
            self.close_pending = False
            self.close_error = None
            self.rows.clear(); self.subagents.clear(); self.media_files.clear()
            self.data.update(connected=False, released=True, models=[], skills=[], mcp=[], metrics=[])
            self.seq += 1

    def retire_observer(self):
        if not self.operation.acquire(blocking=False): raise Conflict("旧会话操作仍在进行")
        try:
            with self.lock:
                if self.action_leases: raise Conflict("旧会话操作仍在进行")
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
                self.close_error = "会话资源释放未完成，请重试释放或使用原记录重新连接完成清理"
                self.seq += 1
            self.diagnostic("observer-close-failed", exc)
            raise OSError(self.close_error) from exc
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
    def configure(self, data): raise Rejected("此原生协议未提供当前设置接口")
    def rename(self, title): raise Rejected("此原生协议未提供改名接口")
    def compact(self): raise Rejected("此原生协议未提供压缩接口")
    def stop(self): raise Rejected("此原生协议未提供中断接口")
    def queue_change(self, data): raise Rejected("此原生队列没有该操作")
    def answer(self, data): raise Rejected("审批不存在或已过期")
    def terminal(self): raise Rejected("此原生入口尚未提供共享 TUI")

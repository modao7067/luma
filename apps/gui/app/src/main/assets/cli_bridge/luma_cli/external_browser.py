import hmac
import json
import os
from pathlib import Path
import secrets
import shlex
import shutil
import socket
import sys
import tempfile
import threading
from urllib.parse import parse_qs, urlsplit

def valid_google_auth_url(url):
    if not isinstance(url, str) or not url or len(url) > 16384: return False
    if any(ord(c) <= 0x20 or ord(c) == 0x7f for c in url): return False
    try:
        value = urlsplit(url)
        if (value.scheme != "https" or value.netloc != "accounts.google.com" or value.fragment
                or value.path not in ("/o/oauth2/auth", "/o/oauth2/v2/auth")):
            return False
        query = parse_qs(value.query)
        targets = query.get("redirect_uri", [])
        if len(targets) != 1: return False
        target = urlsplit(targets[0])
        return (target.scheme == "http" and target.hostname == "127.0.0.1" and target.port is not None
                and 0 < target.port < 65536 and not target.username and not target.password
                and target.path == "/" and not target.query and not target.fragment
                and not any(key in query for key in ("code", "access_token", "refresh_token", "id_token")))
    except ValueError:
        return False

class BrowserRelay:
    def __init__(self, opened, rejected=lambda: None):
        self.opened, self.rejected = opened, rejected
        self.directory = Path(tempfile.mkdtemp(prefix="luma-google-auth-", dir="/tmp"))
        self.directory.chmod(0o700)
        self.path = self.directory / "open"
        self.nonce = secrets.token_hex(32)
        self.socket = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
        self.socket.bind(str(self.path)); self.path.chmod(0o600)
        self.socket.settimeout(.25)
        self.closed = threading.Event()
        self.thread = threading.Thread(target=self.receive, daemon=True, name="LumaNativeBrowser")
        self.thread.start()

    def _ensure_opener(self, name):
        helper = str(Path(__file__).absolute())
        opener = self.directory / name
        if not opener.is_file():
            fd = os.open(opener, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o700)
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                fh.write("#!/bin/sh\nexec /usr/bin/python3 -I -B " + shlex.quote(helper) + ' "$1"\n')
            os.chmod(opener, 0o700)
        return opener

    def environment(self):
        opener = self._ensure_opener("luma-auth-browser")
        return {"BROWSER": str(opener),
                "LUMA_AUTH_BROWSER_SOCKET": str(self.path), "LUMA_AUTH_BROWSER_NONCE": self.nonce}

    def xdg_environment(self, base_path=""):
        self._ensure_opener("xdg-open")
        path_value = f"{self.directory}:{base_path}" if base_path else str(self.directory)
        return {**self.environment(), "PATH": path_value}

    def receive(self):
        while not self.closed.is_set():
            try:
                raw = self.socket.recv(32768)
                data = json.loads(raw)
                if not isinstance(data, dict): continue
                nonce = data.get("nonce")
                if not isinstance(nonce, str) or not hmac.compare_digest(nonce, self.nonce): continue
                if valid_google_auth_url(data.get("url")): self.opened(data["url"])
                else: self.rejected()
            except socket.timeout: pass
            except (OSError, ValueError, TypeError):
                if self.closed.is_set(): break

    def close(self):
        self.closed.set(); self.socket.close()
        if self.thread is not threading.current_thread(): self.thread.join(1)
        shutil.rmtree(self.directory, ignore_errors=True)

def main():
    if len(sys.argv) != 2: return 0
    try:
        data = {"nonce": os.environ["LUMA_AUTH_BROWSER_NONCE"]}
        if valid_google_auth_url(sys.argv[1]): data["url"] = sys.argv[1]
        with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM) as peer:
            peer.connect(os.environ["LUMA_AUTH_BROWSER_SOCKET"])
            peer.send(json.dumps(data).encode())
        return 0
    except (KeyError, OSError): return 0

if __name__ == "__main__": raise SystemExit(main())

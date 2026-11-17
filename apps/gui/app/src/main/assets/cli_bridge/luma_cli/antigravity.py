import shutil
from .acp import AcpSession
from .external_browser import BrowserRelay
from .i18n import tr
from .transport import Rejected

class AntigravitySession(AcpSession):
    argv = ["agy_acp_server.par", "--uid="]
    auth_timeout = 300
    replay_release = True
    release_empty = True

    def start(self, argv):
        self.authenticating = False
        self.auth_cancelled = False
        self.auth_url_rejected = False
        self.auth_browser = BrowserRelay(self.open_auth_browser, self.reject_auth_browser)
        try:
            super().start(argv, self.auth_browser.environment())
        except Exception:
            self.auth_browser.close()
            raise

    def authenticate(self, info):
        if not any(m.get("id") == "oauth-personal" for m in info.get("authMethods", [])):
            raise Rejected(tr("此官方 ACP 未提供 oauth-personal；不能改用其它认证或个人订阅 headless。", "This official ACP does not offer oauth-personal; cannot fall back to another auth method."))
        with self.lock:
            self.authenticating = True
            self.auth_cancelled = False
        try:
            self.call("authenticate", {"methodId": "oauth-personal"}, timeout=self.auth_timeout, pause_for_user=False)
        except TimeoutError:
            raise Rejected(tr("Google 登录未在原生 5 分钟窗口内确认；本次官方 ACP 已关闭，请重连核对登录。", "Google sign-in was not confirmed within the 5-minute window; official ACP closed, please reconnect.")) from None
        except Exception:
            if self.auth_cancelled: raise Rejected(tr("已取消 Antigravity 登录", "Antigravity sign-in cancelled")) from None
            if self.auth_url_rejected: raise Rejected(tr("官方 ACP 请求了不受支持的授权网址，已拒绝打开；请在原生客户端核对。", "Official ACP requested an unsupported authorization URL and was blocked; verify in the native client.")) from None
            raise
        finally:
            with self.lock:
                self.authenticating = False
                self.answered("antigravity-auth-browser")
        if self.auth_cancelled: raise Rejected(tr("已取消 Antigravity 登录", "Antigravity sign-in cancelled"))
        if self.auth_url_rejected: raise Rejected(tr("官方 ACP 请求了不受支持的授权网址，已拒绝打开；请在原生客户端核对。", "Official ACP requested an unsupported authorization URL and was blocked; verify in the native client."))

    def open_auth_browser(self, url):
        with self.lock:
            if not self.authenticating or self.auth_cancelled: return
            self.request("antigravity-auth-browser", {"nativeAuthBrowser": True}, tr("登录 Google Antigravity", "Sign in to Google Antigravity"),
                         tr("在浏览器完成 Google 登录；返回后由官方 ACP 确认。关闭网页不等于取消登录。", "Complete Google sign-in in your browser; official ACP will confirm when you return. Closing the tab does not cancel sign-in."),
                         [{"id": "cancel", "label": tr("取消登录", "Cancel sign-in")}], external_url=url)

    def reject_auth_browser(self):
        with self.lock:
            if not self.authenticating: return
            self.auth_url_rejected = True
        if self.peer.proc.poll() is None:
            try: self.peer.proc.terminate()
            except ProcessLookupError: pass

    def answer(self, p):
        if p.get("id") != "antigravity-auth-browser": return super().answer(p)
        with self.lock:
            if (not self.authenticating or p["id"] not in self.pending_requests
                    or p.get("choice") != "cancel"):
                raise Rejected(tr("登录请求不存在、已结束或动作无效", "Sign-in request does not exist, has ended, or action is invalid"))
            self.auth_cancelled = True
            self.answered(p["id"])
        self.peer.close()
        return {}

    def exited(self, error):
        browser = getattr(self, "auth_browser", None)
        if browser: browser.close()
        self.authenticating = False
        self.pending_requests.pop("antigravity-auth-browser", None)
        super().exited(error)

    def connect(self):
        if self.config.get("nativeProtocol", "antigravity-acp") != "antigravity-acp":
            raise Rejected(tr("此会话不属于 Google 官方 ACP；请使用原入口恢复，不能切换协议或会话存储。", "This session does not belong to official Google ACP; resume via its original entry without switching protocol or storage."))
        if "nativeProtocol" not in self.config:
            self.config["nativeProtocol"] = "antigravity-acp"
            self.save()
        if self.config.get("creationUnknown") and not self.config.get("nativeId"):
            raise Rejected(tr("上次原生创建结果未知；请从 CLI 会话列表取得原 ID 后接入，不能自动再建", "Previous native session creation result is unknown; attach using the original ID from CLI session list instead of recreating"))
        if not shutil.which(self.argv[0]):
            raise Rejected(tr("未找到 Google 官方 agy_acp_server.par；请安装官方 ACP 发行包，将匹配的 localharness_external 放在旁边并加入 PATH。安装 agy CLI 不等于安装 ACP。",
                              "Official Google agy_acp_server.par not found; install the official ACP package with matching localharness_external on PATH. Installing agy CLI alone does not include ACP."))
        try:
            super().connect()
        except Exception:
            browser = getattr(self, "auth_browser", None)
            if self.peer: self.peer.close()
            if browser: browser.close()
            diagnostic = bytes(self.peer.diagnostic) if self.peer else b""
            if b"third_party/tcmalloc/" in diagnostic and b"FATAL ERROR" in diagnostic:
                raise Rejected(tr("Google 官方 Antigravity ACP 在本机启动失败（TCMalloc 地址空间不兼容）；当前入口不可用，请等待官方兼容版本。",
                                  "Official Google Antigravity ACP failed to start on this device (TCMalloc address space incompatibility); wait for a compatible official build.")) from None
            raise
        self.update(connected=True)

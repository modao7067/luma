import os
import re
import secrets
import shutil
import signal
import socket
import subprocess
import time
from .codebuddy_http import CodeBuddyHttpPeer
from .native_owner import NativeOwner, process_start
from .transport import Rejected, spawn_process

class CodeBuddyOwner(NativeOwner):
    def launch(self,overrides=None):
        if self.handle.get("kind"):
            if self.handle["kind"]!="codebuddy-server" or not self.alive():raise Rejected("CodeBuddy 原生执行所有者不可用")
            return
        binary=shutil.which("codebuddy")
        if not binary:raise Rejected("未找到 CodeBuddy Code；请在当前 Linux 终端安装 @tencent-ai/codebuddy-code 并运行 codebuddy → /login")
        try:
            probe=subprocess.run([binary,"--version"],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=15,check=False)
        except (OSError,subprocess.TimeoutExpired) as exc:
            raise Rejected("CodeBuddy 版本探测失败；请在当前 Linux 终端核对已安装版本") from exc
        version=probe.stdout.decode("utf-8",errors="replace").strip()
        match=re.search(r"(\d+)\.(\d+)\.(\d+)",version)
        if probe.returncode or not match or tuple(map(int,match.groups()))<(2,180,0):
            raise Rejected("CodeBuddy GUI 需要 2.180.0 或更新版本；请核对已安装版本")
        sock=socket.socket();sock.bind(("127.0.0.1",0));port=sock.getsockname()[1];sock.close()
        password=secrets.token_urlsafe(36)
        file=self.directory/"codebuddy-gateway-password"
        with file.open("w") as out:os.chmod(file,0o600);out.write(password)
        argv=[binary,"--serve","--host","127.0.0.1","--port",str(port),"--auth","password"]
        controls=self.session.config.get("codebuddyControls",{})
        if controls.get("permission"):argv += ["--permission-mode",controls["permission"]]
        if controls.get("effort") in ("enabled","disabled"):
            argv += ["--settings",__import__("json").dumps({"alwaysThinkingEnabled":controls["effort"]=="enabled"})]
        elif controls.get("effort"):argv += ["--effort",controls["effort"]]
        env={**os.environ,**(overrides or {}),"CODEBUDDY_GATEWAY_AUTH":"password","CODEBUDDY_GATEWAY_PASSWORD":password,"SERVER__HOST":"127.0.0.1","DISABLE_AUTOUPDATER":"1"}
        if self.session.config.get("browserConfig"):env["LUMA_BROWSER_CONFIG"]=self.session.config["browserConfig"]
        proc=spawn_process(argv,cwd=self.session.config["cwd"],env=env,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
        self.proc=proc
        self.handle.update(kind="codebuddy-server",pid=proc.pid,processStart=process_start(proc.pid),port=port)
        self.session.save()

    def connect_http(self,event,exited,waiting_for_user):
        password=(self.directory/"codebuddy-gateway-password").read_text().strip()
        deadline=time.monotonic()+35
        while True:
            try:
                peer=CodeBuddyHttpPeer(self.handle["port"],password,self,event,exited,waiting_for_user,connection_id=self.handle.get("connectionId"))
                self.handle["connectionId"]=peer.connection_id;self.session.save()
                return peer
            except (ConnectionError,OSError):
                if not self.alive() or time.monotonic()>=deadline:raise Rejected("CodeBuddy 原生服务未能启动") from None
                time.sleep(.1)

    def stop(self, force=False):
        if self.handle.get("kind")!="codebuddy-server":return
        pid=self.handle.get("pid");start=self.handle.get("processStart")
        if not pid or process_start(pid)!=start:return
        try: os.kill(pid,signal.SIGTERM)
        except ProcessLookupError: return
        until=time.monotonic()+5
        while process_start(pid)==start and time.monotonic()<until:time.sleep(.05)
        if force and process_start(pid)==start:
            try: os.kill(pid,signal.SIGKILL)
            except ProcessLookupError: pass
            until=time.monotonic()+1
            while process_start(pid)==start and time.monotonic()<until:time.sleep(.05)
        if not force and process_start(pid)==start:raise OSError("CodeBuddy 原生进程尚未退出")
        proc=getattr(self,"proc",None)
        if proc is not None:
            try: proc.wait(timeout=1)
            except subprocess.TimeoutExpired: pass

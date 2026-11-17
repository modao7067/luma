#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
import argparse
import base64
import json
import os
import pathlib
import secrets
import shutil
import signal
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[3]

def opencode_config():
    return {"model": "luma-mimo/mimo-v2.6-flash", "providers": {"luma-mimo": {
        "name": "Official Xiaomi MiMo", "package": "@opencode/ai/providers/openai-compatible",
        "settings": {"baseURL": "https://api.xiaomimimo.com/v1", "apiKey": "{env:LUMA_MIMO_API_KEY}"},
        "models": {"mimo-v2.6-flash": {"name": "MiMo V2.6 Flash", "compatibility": {"reasoningField": "reasoning_content"},
            "capabilities": {"tools": True, "input": ["text"], "output": ["text"], "temperature": False},
            "limit": {"context": 1048576, "output": 1024},
            "variants": [{"id": "non-thinking", "body": {"thinking": {"type": "disabled"}}}]}}}}}

def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]

def live_group_members(pgid):
    members = []
    for entry in pathlib.Path("/proc").iterdir():
        if not entry.name.isdigit(): continue
        try:
            fields = (entry / "stat").read_text().rsplit(")", 1)[1].split()
            if int(fields[2]) == pgid and fields[0] != "Z": members.append(int(entry.name))
        except (FileNotFoundError, ProcessLookupError): pass
    return members

def stop(process):
    if process is None: return
    if live_group_members(process.pid):
        os.killpg(process.pid, signal.SIGTERM)
        deadline = time.monotonic() + 8
        while live_group_members(process.pid) and time.monotonic() < deadline: time.sleep(0.05)
        if live_group_members(process.pid): os.killpg(process.pid, signal.SIGKILL)
    process.wait(timeout=8)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--key-file", required=True, type=pathlib.Path)
    parser.add_argument("--evidence", type=pathlib.Path, default=ROOT / "artifacts/test-runs/mimo-live")
    parser.add_argument("--only", choices=["both", "codex", "opencode"], default="both")
    parser.add_argument("--test-method")
    parser.add_argument("--also-test", action="append", default=[])
    parser.add_argument("--codex-command", default=os.getenv("LUMA_LIVE_CODEX_COMMAND", json.dumps([shutil.which("codex") or "codex"])))
    parser.add_argument("--codex-work")
    parser.add_argument("--codex-host-work")
    parser.add_argument("--codex-native-home")
    parser.add_argument("--codex-launch-config", type=pathlib.Path)
    parser.add_argument("--opencode-command", default=os.getenv("LUMA_LIVE_OPENCODE_COMMAND", json.dumps([shutil.which("opencode") or "opencode"])))
    parser.add_argument("--opencode-work", help="Native guest cwd when the command prefix enters another environment")
    parser.add_argument("--opencode-host-work", help="Host-visible counterpart for the fixture file assertion")
    parser.add_argument("--opencode-launch-config", type=pathlib.Path, help="Write non-secret config for a command-prefix wrapper")
    parser.add_argument("--opencode-service-password-file", type=pathlib.Path, help="Private fixture service password consumed by a command-prefix wrapper")
    parser.add_argument("--opencode-existing-run", type=pathlib.Path, help="Reuse only a previous self-owned fixture to verify persisted history without inference")
    parser.add_argument("--opencode-existing-id", help="Existing isolated session ID for a read-only recovery check")
    parser.add_argument("--opencode-recovery-messages", help="Prior native-message evidence filename, for recovery without new inference")
    args = parser.parse_args()
    args.evidence = args.evidence.resolve()
    args.evidence.mkdir(parents=True, exist_ok=True)
    run = pathlib.Path(tempfile.mkdtemp(prefix="luma-mimo-native-", dir="/tmp"))
    run.chmod(0o700)
    key = args.key_file.read_text().strip()
    assert key and args.key_file.stat().st_mode & 0o077 == 0, "API key must be in a private file"
    password = secrets.token_hex(32)
    if args.opencode_service_password_file:
        args.opencode_service_password_file.write_text(password)
        args.opencode_service_password_file.chmod(0o600)
    logs = []
    processes = {}
    stop_watch = threading.Event()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def native_env(name):
        base = args.opencode_existing_run if name == "opencode" and args.opencode_existing_run else run
        home = base / name / "home"
        home.mkdir(parents=True, exist_ok=True)
        work = base / name / "work"
        work.mkdir(exist_ok=True)
        if not (work / "seed.txt").exists(): (work / "seed.txt").write_text("LUMA_MIMO_SEED_ONLY\n")
        env = {"PATH": "/usr/local/bin:/usr/bin:/bin", "HOME": str(home), "LANG": "C.UTF-8", "TERM": "xterm-256color",
               "XDG_DATA_HOME": str(home / "data"), "XDG_CONFIG_HOME": str(home / "config"),
               "XDG_CACHE_HOME": str(home / "cache"), "XDG_STATE_HOME": str(home / "state"),
               "LUMA_MIMO_API_KEY": key}
        return home, work, env

    def launch(name, argv, work, env):
        process = subprocess.Popen(argv, cwd=work, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, bufsize=1, start_new_session=True)
        processes[name] = process
        def drain():
            with (args.evidence / (name + "-server.log")).open("a") as out:
                for line in process.stdout:
                    out.write(line.replace(key, "[REDACTED_API_KEY]").replace(password, "[REDACTED_SERVICE_PASSWORD]"))
                    out.flush()
        thread = threading.Thread(target=drain, daemon=True)
        thread.start()
        logs.append(thread)
        return process

    def wait_port(port, process):
        for _ in range(200):
            if process.poll() is not None:
                raise RuntimeError("Isolated CLI exited before readiness; see redacted server log")
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                    return
            except OSError:
                time.sleep(0.1)
        raise TimeoutError("Isolated CLI startup timeout")

    test_env = {"PATH": "/usr/local/bin:/usr/bin:/bin", "HOME": "/root", "JAVA_HOME": toolenv.JAVA_HOME,
                "LANG": "C.UTF-8", "LUMA_LIVE_EVIDENCE": str(args.evidence)}
    def watch_restart(name, start):
        control = run / (name + "-control")
        control.mkdir()
        test_env["LUMA_LIVE_" + name.upper() + "_RESTART_DIR"] = str(control)
        def watch():
            while not stop_watch.wait(0.1):
                if (control / "restart.request").is_file():
                    stop(processes.get(name))
                    try:
                        start()
                        (control / "restart.done").touch()
                    except Exception as error:
                        (args.evidence / (name + "-restart-error.txt")).write_text(str(error))
                    return
        threading.Thread(target=watch, daemon=True).start()
    try:
        if args.only in ("both", "codex"):
            home, work, env = native_env("codex")
            codex_home = home / "codex"
            codex_home.mkdir()
            env["CODEX_HOME"] = str(codex_home)
            config_text = '''model="mimo-v2.6-flash"
model_provider="luma_mimo"
web_search="disabled"
[model_providers.luma_mimo]
name="Official Xiaomi MiMo"
base_url="https://api.xiaomimimo.com/v1"
wire_api="responses"
env_key="LUMA_MIMO_API_KEY"
requires_openai_auth=false
supports_websockets=false
request_max_retries=0
stream_max_retries=0
[shell_environment_policy]
inherit="none"
exclude=["LUMA_MIMO_API_KEY"]
'''+ f'''[shell_environment_policy.set]
HOME="{args.codex_native_home or home}"
PATH="/usr/local/bin:/usr/bin:/bin"
'''
            (codex_home / "config.toml").write_text(config_text)
            if args.codex_launch_config:
                args.codex_launch_config.write_text(config_text)
            port = free_port()
            url = f"ws://127.0.0.1:{port}"
            def start_codex(argv=json.loads(args.codex_command) + ["app-server", "--listen", url], cwd=work, child_env=env, bind_port=port):
                process = launch("codex", argv, cwd, child_env)
                wait_port(bind_port, process)
                return process
            start_codex()
            watch_restart("codex", start_codex)
            test_env.update(LUMA_LIVE_CODEX_URL=url, LUMA_LIVE_CODEX_WORK=args.codex_work or str(work))
            if args.codex_host_work:
                test_env["LUMA_LIVE_CODEX_HOST_WORK"] = args.codex_host_work
        if args.only in ("both", "opencode"):
            home, work, env = native_env("opencode")
            config = opencode_config()
            if args.opencode_launch_config:
                args.opencode_launch_config.write_text(json.dumps(config))
            env.update(OPENCODE_PASSWORD=password, OPENCODE_CONFIG_CONTENT=json.dumps(config))
            port = free_port()
            url = f"http://127.0.0.1:{port}"
            command = json.loads(args.opencode_command) + ["serve", "--hostname", "127.0.0.1", "--port", str(port)]
            authorization = "Basic " + base64.b64encode(("opencode:" + password).encode()).decode()
            def start_opencode(argv=command, cwd=work, child_env=env, bind_port=port, endpoint=url, auth=authorization):
                process = launch("opencode", argv, cwd, child_env)
                wait_port(bind_port, process)
                for _ in range(150):
                    try:
                        req = urllib.request.Request(endpoint + "/api/info", headers={"Authorization": auth})
                        with opener.open(req, timeout=1) as response:
                            info = json.load(response)
                        (args.evidence / "opencode-info.json").write_text(json.dumps(info, indent=2))
                        return process
                    except OSError:
                        time.sleep(0.1)
                raise TimeoutError("OpenCode API readiness timeout")
            start_opencode()
            test_env.update(LUMA_LIVE_OPENCODE_URL=url, LUMA_LIVE_OPENCODE_WORK=args.opencode_work or str(work), LUMA_LIVE_OPENCODE_PASSWORD=password)
            if args.opencode_host_work:
                test_env["LUMA_LIVE_OPENCODE_HOST_WORK"] = args.opencode_host_work
            if args.opencode_existing_id:
                test_env["LUMA_LIVE_OPENCODE_EXISTING_ID"] = args.opencode_existing_id
            if args.opencode_recovery_messages:
                test_env["LUMA_LIVE_OPENCODE_RECOVERY_MESSAGES"] = args.opencode_recovery_messages
            watch_restart("opencode", start_opencode)
        test_class = "io.github.modao7067.luma.gui.agent.MiMoLiveIntegrationTest"
        if args.test_method: tests = [test_class + "." + args.test_method]
        else:
            tests = []
            if args.only in ("both", "codex"): tests.append(test_class + ".codexToolsSettingsApprovalQueueStopAndRestart")
            if args.only in ("both", "opencode"): tests.append(test_class + ".opencodeStreamToolsApprovalQueueStopAndRestart")
        command = [str(ROOT / "gradlew"), ":apps:gui:testDebugUnitTest", "--rerun", "--offline", "--console=plain"]
        for pattern in tests: command += ["--tests", pattern]
        for pattern in args.also_test: command += ["--tests", pattern]
        result = subprocess.run(command, cwd=ROOT, env=test_env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=600)
        output = result.stdout.replace(key, "[REDACTED_API_KEY]").replace(password, "[REDACTED_SERVICE_PASSWORD]")
        log = args.evidence / ("gradle-" + args.only + ".log")
        if log.exists():
            attempt = 1
            while log.with_name(log.stem + f"-attempt-{attempt:02}" + log.suffix).exists(): attempt += 1
            log.rename(log.with_name(log.stem + f"-attempt-{attempt:02}" + log.suffix))
        log.write_text(output)
        print(output)
        print("Isolated fixture:", run)
        print("Evidence:", args.evidence)
        return result.returncode
    finally:
        stop_watch.set()
        for process in list(processes.values()):
            stop(process)
        for thread in logs:
            thread.join(timeout=2)

if __name__ == "__main__":
    raise SystemExit(main())

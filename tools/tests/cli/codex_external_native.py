#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
import argparse
import asyncio
import websockets
import http.server
import importlib.util
import json
import os
import pathlib
import socket
import subprocess
import tempfile
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('gui_protocol_fixture', ROOT / 'tools/tests/verify-gui-protocol.py')
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--codex', default='/root/.codex/packages/app-server-daemon/releases/0.166.0-aarch64-unknown-linux-musl/bin/codex')
    parser.add_argument('--output', type=pathlib.Path, default=ROOT / 'verification/gui-external-codex-20261104')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    run = pathlib.Path(tempfile.mkdtemp(prefix='native-', dir=args.output))
    home = run / 'home'; home.mkdir(mode=0o700)
    work = run / 'work'; work.mkdir()
    socket_dir = pathlib.Path(tempfile.mkdtemp(prefix='luma-external-'))
    sock = socket_dir / 'server.sock'
    fixture.Model.released.clear(); fixture.Model.requests.clear()
    loop = asyncio.new_event_loop()
    async def start_drop(): return await websockets.unix_serve(fixture.dropped_request, path=str(socket_dir / "drop.sock"))
    drop_server = loop.run_until_complete(start_drop())
    threading.Thread(target=loop.run_forever, daemon=True).start()
    model = http.server.ThreadingHTTPServer(('127.0.0.1', 0), fixture.Model)
    threading.Thread(target=model.serve_forever, daemon=True).start()
    (home / 'config.toml').write_text(f'''model="fixture"
model_provider="fixture"
[model_providers.fixture]
name="Isolated external owner fixture"
base_url="http://127.0.0.1:{model.server_port}/v1"
wire_api="responses"
requires_openai_auth=false
[projects."{work}"]
trust_level="trusted"
''')
    env = {key: value for key, value in os.environ.items() if 'TOKEN' not in key and 'API_KEY' not in key and not key.startswith('LUMA_TEST_')}
    env.update(HOME=str(home), CODEX_HOME=str(home), JAVA_HOME=toolenv.JAVA_HOME,
               LUMA_TEST_EXTERNAL_CODEX_SOCKET=str(sock), LUMA_TEST_EXTERNAL_CODEX_BINARY=args.codex,
               LUMA_TEST_EXTERNAL_CODEX_WORK=str(work), LUMA_TEST_EXTERNAL_CODEX_DROP_SOCKET=str(socket_dir / "drop.sock"), LUMA_TEST_EXTERNAL_MODEL_URL=f'http://127.0.0.1:{model.server_port}')
    result = None
    try:
        with (run / 'server.log').open('w') as log:
            owner = subprocess.Popen([args.codex, 'app-server', '--listen', 'unix://' + str(sock)], cwd=work, env=env, stdout=log, stderr=log)
            try:
                for _ in range(250):
                    if owner.poll() is not None: raise RuntimeError('Isolated AppServer exited; see server.log')
                    if sock.exists(): break
                    time.sleep(.1)
                else: raise RuntimeError('Isolated AppServer socket not ready')
                command = [str(ROOT / 'gradlew'), '-I', str(ROOT / 'tools/tests/ui/compose/compose-tests.gradle'), ':apps:gui:testDebugUnitTest', '--offline', '--no-daemon',
                           '--tests', '*ExternalCodexAttachmentTest', '--tests', '*CodexExternalRpcIntegrationTest',
                           '--tests', '*CodexProxyDiagnosticsTest',
                           '--tests', '*ManagedSessionPolicyTest', '--tests', '*SessionManagementProtocolTest',
                           '--tests', '*SessionOutboxStoreTest', '--tests', '*TerminalLaunchPolicyTest',
                           '--tests', '*SessionManagementIntegrationTest.rootExternal*', ':apps:gui:compileDebugKotlin',
                           ':apps:gui:testDebugUnitTest', '--tests', '*AcpCliCreationIntegrationTest.noRootBridgeAllows*']
                result = subprocess.run(command, cwd=ROOT, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                (run / 'gradle.log').write_text(result.stdout)
                print(result.stdout)
                if owner.poll() is not None: raise RuntimeError('Proxy detach stopped the isolated owner')
            finally:
                owner.terminate()
                try: owner.wait(timeout=5)
                except subprocess.TimeoutExpired: owner.kill(); owner.wait()
    finally:
        fixture.Model.released.set(); model.shutdown(); model.server_close()
        async def close_drop():
            drop_server.close(); await drop_server.wait_closed()
        asyncio.run_coroutine_threadsafe(close_drop(), loop).result(timeout=5)
        loop.call_soon_threadsafe(loop.stop)
        sock.unlink(missing_ok=True); (socket_dir / 'drop.sock').unlink(missing_ok=True); socket_dir.rmdir()
    (run / 'model-requests.json').write_text(json.dumps(fixture.Model.requests, indent=2))
    summary = {'gradleExit': result.returncode, 'modelRequests': len(fixture.Model.requests), 'transport': 'official Codex proxy -> private Unix socket',
               'scope': 'production Kotlin RPC + shared external-attachment setup, isolated owner only; no installed App changes', 'ownerSurvivedProxyDetach': True, 'droppedMutationCalls': len(fixture.drop_calls)}
    (run / 'result.json').write_text(json.dumps(summary, indent=2))
    print(run / 'result.json')
    assert result.returncode == 0, 'See gradle.log'
    assert len(fixture.Model.requests) == 2, 'Expected two isolated fixture requests'
    assert len(fixture.drop_calls) == 1, 'A dropped mutation must never be replayed'

if __name__ == '__main__': main()

#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
import argparse
import asyncio
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import websockets

class Model(http.server.BaseHTTPRequestHandler):
    release = threading.Event()
    requests = []
    def log_message(self, *args): pass
    def do_GET(self):
        if self.path == '/release': self.release.set(); value = {'released': True}
        elif self.path == '/hold': self.release.clear(); value = {'released': False}
        elif self.path == '/requests': value = {'count': len(self.requests)}
        else: self.send_error(404); return
        self.send_response(200); self.end_headers(); self.wfile.write(json.dumps(value).encode())
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        number = len(self.requests) + 1; self.requests.append(request)
        self.send_response(200); self.send_header('Content-Type', 'text/event-stream'); self.end_headers()
        def event(value):
            self.wfile.write(('event: ' + value['type'] + '\ndata: ' + json.dumps(value) + '\n\n').encode()); self.wfile.flush()
        try:
            event({'type': 'response.created', 'response': {'id': f'response-{number}', 'status': 'in_progress'}})
            self.release.wait(40)
            item = {'id': f'answer-{number}', 'type': 'message', 'role': 'assistant', 'status': 'completed',
                    'content': [{'type': 'output_text', 'text': 'Local fixture response.', 'annotations': []}]}
            event({'type': 'response.output_item.added', 'output_index': 0, 'item': {**item, 'status': 'in_progress', 'content': []}})
            event({'type': 'response.output_item.done', 'output_index': 0, 'item': item})
            event({'type': 'response.completed', 'response': {'id': f'response-{number}', 'status': 'completed', 'output': [item],
                  'usage': {'input_tokens': 2, 'output_tokens': 3, 'total_tokens': 5}}})
        except (BrokenPipeError, ConnectionResetError): pass

class Client:
    def __init__(self, ws):
        self.ws = ws; self.sequence = 0; self.pending = {}; self.messages = []
        self.reader = asyncio.create_task(self.read())
    async def read(self):
        async for raw in self.ws:
            value = json.loads(raw); self.messages.append(value)
            if 'id' in value and 'method' not in value:
                waiter = self.pending.pop(value['id'], None)
                if waiter is not None: waiter.set_result(value)
    async def call(self, method, params):
        self.sequence += 1; ident = self.sequence
        future = asyncio.get_running_loop().create_future(); self.pending[ident] = future
        await self.ws.send(json.dumps({'id': ident, 'method': method, 'params': params}))
        reply = await asyncio.wait_for(future, 15)
        print(method, json.dumps(reply, ensure_ascii=False)[:1400], flush=True)
        return reply
    async def event(self, method, after=0, predicate=lambda p: True):
        async def wait():
            while True:
                for value in self.messages[after:]:
                    if value.get('method') == method and predicate(value.get('params', {})): return value
                await asyncio.sleep(.01)
        return await asyncio.wait_for(wait(), 15)

async def controller_checks(directory, env, native_url, work, model_url, skip_ledger=False, fault_only=False):
    wire = []; fault_calls = []; fault_queue = []
    async def proxy(downstream):
        async with websockets.connect(native_url) as upstream:
            async def pipe(source, target, direction):
                async for raw in source:
                    wire.append({'direction': direction, 'message': json.loads(raw)})
                    await target.send(raw)
            jobs = [asyncio.create_task(pipe(downstream, upstream, 'request')), asyncio.create_task(pipe(upstream, downstream, 'event'))]
            _, pending = await asyncio.wait(jobs, return_when=asyncio.FIRST_COMPLETED)
            for job in pending: job.cancel()
    async def fault(ws):
        async for raw in ws:
            value = json.loads(raw); method = value.get('method'); ident = value.get('id')
            fault_calls.append(value)
            if ident is None: continue
            if method == 'initialize': reply = {}
            elif method == 'thread/queue/add':
                params = value['params']
                queued = {'id': 'native-fault-queue', 'input': params['input'], 'clientUserMessageId': params['clientUserMessageId']}
                fault_queue.append(queued); reply = {'queuedSubmission': queued}
            elif method == 'thread/queue/list': reply = {'data': fault_queue, 'nextCursor': None}
            elif method == 'thread/queue/start':
                await ws.send(json.dumps({'id': ident, 'error': {'code': -32602, 'message': 'Sandbox failed for fixture'}})); continue
            else: raise AssertionError(f'Unexpected fault-carrier call: {method}')
            await ws.send(json.dumps({'id': ident, 'result': reply}))
    proxy_server = await websockets.serve(proxy, '127.0.0.1', 0)
    fault_server = await websockets.serve(fault, '127.0.0.1', 0)
    root = Path(__file__).resolve().parents[3]
    env = {**env, 'JAVA_HOME': toolenv.JAVA_HOME,
           'LUMA_STOP_URL': f'ws://127.0.0.1:{proxy_server.sockets[0].getsockname()[1]}',
           'LUMA_STOP_FAULT_URL': f'ws://127.0.0.1:{fault_server.sockets[0].getsockname()[1]}',
           'LUMA_STOP_WORK': str(work), 'LUMA_STOP_MODEL': model_url}
    gradle_env = dict(env); gradle_env['HOME'] = os.environ['HOME']
    try:
        with (directory / 'gradle.log').open('w') as log:
            test = 'io.github.modao7067.luma.gui.session.CodexStopPermissionsIntegrationTest'
            if fault_only: test += '.acceptedQueueButRejectedStartShowsTheNativeErrorWithoutRestoringOrReplayingTheInput'
            command = [str(root / 'gradlew'), '-I', str(root / 'tools/tests/ui/compose/compose-tests.gradle'),
                       ':apps:gui:testDebugUnitTest', '--tests', test]
            if not skip_ledger and not fault_only: command += ['--tests', 'io.github.modao7067.luma.gui.session.QueuedOutboxTest', '--tests', 'io.github.modao7067.luma.gui.session.SubmissionLedgerTest']
            command += [':apps:gui:compileDebugKotlin', '--offline', '--console=plain']
            process = await asyncio.create_subprocess_exec(*command, cwd=root, env=gradle_env, stdout=log, stderr=log)
            result = await process.wait()
    finally:
        proxy_server.close(); fault_server.close()
        await proxy_server.wait_closed(); await fault_server.wait_closed()
        (directory / 'wire.json').write_text(json.dumps(wire, ensure_ascii=False, indent=2))
        (directory / 'fault-calls.json').write_text(json.dumps(fault_calls, ensure_ascii=False, indent=2))
    if result == 0:
        requests = [value['message'] for value in wire if value['direction'] == 'request']
        adds = [value for value in requests if value.get('method') == 'thread/queue/add']
        starts = [value for value in requests if value.get('method') == 'thread/queue/start']
        assert len(adds) == (0 if fault_only else 3), adds
        assert len(starts) == (0 if fault_only else 1), starts
        if not fault_only:
            assert len([value for value in requests if value.get('method') == 'turn/steer']) == 1
            response = next(value['message'] for value in wire if value['direction'] == 'event' and value['message'].get('id') == adds[-1]['id'])
            assert starts[0]['params']['queuedSubmissionId'] == response['result']['queuedSubmission']['id']
        assert len([value for value in fault_calls if value.get('method') == 'thread/queue/add']) == 1
        assert len([value for value in fault_calls if value.get('method') == 'thread/queue/start']) == 1
    print('CONTROLLER_GRADLE', result, directory / 'gradle.log', flush=True)
    return result

async def run(output, controller=False, resume=False, skip_ledger=False, fault_only=False):
    output.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='native-', dir=output))
    home = directory / 'home'; home.mkdir(mode=0o700)
    codex = home / '.codex'; codex.mkdir(mode=0o700)
    work = directory / 'work'; work.mkdir()
    model = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Model)
    threading.Thread(target=model.serve_forever, daemon=True).start()
    (codex / 'config.toml').write_text(f'''model="fixture"
model_provider="fixture"
[model_providers.fixture]
name="Local stop-permissions regression fixture"
base_url="http://127.0.0.1:{model.server_port}/v1"
wire_api="responses"
requires_openai_auth=false
[projects."{work}"]
trust_level="trusted"
''')
    env = {key: value for key, value in os.environ.items() if 'API_KEY' not in key and key not in ('OPENAI_ACCESS_TOKEN', 'CODEX_HOME', 'HOME')}
    env.update(HOME=str(home), CODEX_HOME=str(codex), XDG_CONFIG_HOME=str(home / '.config'), XDG_DATA_HOME=str(home / '.local/share'))
    with socket.socket() as listener: listener.bind(('127.0.0.1', 0)); port = listener.getsockname()[1]
    url = f'ws://127.0.0.1:{port}'; client = None; result = {}
    with (directory / 'server.log').open('w') as log:
        process = subprocess.Popen(['codex', 'app-server', '--listen', url], cwd=work, env=env, stdout=log, stderr=log)
        try:
            if controller:
                for _ in range(100):
                    try:
                        with socket.create_connection(('127.0.0.1', port), timeout=.1): break
                    except OSError: await asyncio.sleep(.1)
                code = await controller_checks(directory, env, url, work, f'http://127.0.0.1:{model.server_port}', skip_ledger, fault_only)
                result.update(gradleExit=code, modelRequests=len(Model.requests))
                (directory / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
                print('RESULT', directory / 'result.json', flush=True)
                return code
            for _ in range(100):
                try: ws = await websockets.connect(url); break
                except OSError: await asyncio.sleep(.1)
            else: raise TimeoutError('isolated app-server startup')
            client = Client(ws)
            await client.call('initialize', {'clientInfo': {'name': 'luma_stop_permissions_fixture', 'version': '1'}, 'capabilities': {'experimentalApi': True}})
            await ws.send(json.dumps({'method': 'initialized'}))
            created = await client.call('thread/start', {'cwd': str(work), 'approvalPolicy': 'on-request', 'sandbox': 'workspace-write', 'historyMode': 'paginated'})
            thread = created['result']['thread']['id']
            await client.call('thread/section/move', {'threadId': thread, 'sectionId': None})
            await client.call('thread/resume', {'threadId': thread, 'excludeTurns': True})
            result['first'] = await client.call('thread/queue/add', {'threadId': thread, 'clientUserMessageId': 'first-message', 'input': [{'type': 'text', 'text': 'Hold until interrupted.'}]})
            turn = (await client.event('turn/started'))['params']['turn']['id']
            for _ in range(100):
                if Model.requests: break
                await asyncio.sleep(.02)
            result['interrupt'] = await client.call('turn/interrupt', {'threadId': thread, 'turnId': turn})
            result['completed'] = await client.event('turn/completed', predicate=lambda p: p.get('turn', {}).get('id') == turn)
            Model.release.set()
            result['permissions'] = await client.call('permissionProfile/list', {'cwd': str(work), 'limit': 100})
            options = result['permissions']['result']['data']
            target = next(value['id'] for value in options if value.get('allowed') and value['id'] != ':workspace')
            after = len(client.messages)
            result['settingsAck'] = await client.call('thread/settings/update', {'threadId': thread, 'permissions': target})
            result['settingsEvent'] = await client.event('thread/settings/updated', after)
            after = len(client.messages)
            result['second'] = await client.call('thread/queue/add', {'threadId': thread, 'clientUserMessageId': 'second-message', 'input': [{'type': 'text', 'text': 'Answer after stop and permission change.'}]})
            if resume:
                result['explicitStart'] = await client.call('thread/queue/start', {'threadId': thread, 'queuedSubmissionId': result['second']['result']['queuedSubmission']['id']})
                result['secondCompleted'] = await client.event('turn/completed', after)
            await asyncio.sleep(2)
            result['afterSecond'] = client.messages[after:]
            result['queue'] = await client.call('thread/queue/list', {'threadId': thread, 'limit': 100})
            result['modelRequests'] = len(Model.requests)
            if resume:
                assert result['secondCompleted']['params']['turn']['status'] == 'completed'
                assert result['queue']['result']['data'] == []
                assert result['modelRequests'] == 2
            else:
                assert len(result['queue']['result']['data']) == 1
                assert result['modelRequests'] == 1
            print('AFTER_SECOND', json.dumps(result['afterSecond'], ensure_ascii=False)[-9000:], flush=True)
        finally:
            if client is not None:
                (directory / 'events.json').write_text(json.dumps(client.messages, ensure_ascii=False, indent=2))
                await client.ws.close(); client.reader.cancel()
            process.terminate()
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
            Model.release.set(); model.shutdown(); model.server_close()
    (directory / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print('RESULT', directory / 'result.json', flush=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--controller', action='store_true'); parser.add_argument('--resume-after-stop', action='store_true')
    parser.add_argument('--skip-ledger-tests', action='store_true', help='Only the two native/controller cases; reuse already-passing ledger checks')
    parser.add_argument('--fault-only', action='store_true', help='Only the accepted-delivery/start-failure Controller regression')
    args = parser.parse_args()
    raise SystemExit(asyncio.run(run(args.output.resolve(), args.controller, args.resume_after_stop, args.skip_ledger_tests, args.fault_only)) or 0)

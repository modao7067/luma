#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
import asyncio, struct, zlib, http.server, json, os, pathlib, socket, subprocess, tempfile, threading, time
import websockets

ROOT = pathlib.Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / 'verification/gui-protocol'

class Model(http.server.BaseHTTPRequestHandler):
    released = threading.Event()
    requests = []
    def log_message(self, *args): pass
    def do_GET(self):
        if self.path != '/release': self.send_error(404); return
        self.released.set(); self.send_response(200); self.end_headers(); self.wfile.write(b'ok')
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        self.requests.append(request)
        self.released.wait(40)
        item = {'id': 'fixture-answer-' + str(len(self.requests)), 'type': 'message', 'role': 'assistant', 'status': 'completed', 'content': [{'type': 'output_text', 'text': 'Fixture streamed answer.', 'annotations': []}]}
        empty = dict(item, status='in_progress', content=[])
        events = [
            {'type': 'response.created', 'response': {'id': 'fixture', 'status': 'in_progress'}},
            {'type': 'response.output_item.added', 'output_index': 0, 'item': empty},
            {'type': 'response.content_part.added', 'output_index': 0, 'content_index': 0, 'item_id': item['id'], 'part': {'type': 'output_text', 'text': '', 'annotations': []}},
            {'type': 'response.output_text.delta', 'output_index': 0, 'content_index': 0, 'item_id': item['id'], 'delta': 'Fixture streamed '},
            {'type': 'response.output_text.delta', 'output_index': 0, 'content_index': 0, 'item_id': item['id'], 'delta': 'answer.'},
            {'type': 'response.output_item.done', 'output_index': 0, 'item': item},
            {'type': 'response.completed', 'response': {'id': 'fixture', 'status': 'completed', 'output': [item], 'usage': {'input_tokens': 1, 'output_tokens': 3, 'total_tokens': 4}}},
        ]
        self.send_response(200); self.send_header('Content-Type', 'text/event-stream'); self.end_headers()
        for event in events:
            try: self.wfile.write(('event: '+event['type']+'\ndata: '+json.dumps(event)+'\n\n').encode()); self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError): break

async def dropped_request(ws):
    async for text in ws:
        message = json.loads(text)
        if message.get('method') == 'initialize':
            await ws.send(json.dumps({'id':message['id'], 'result':{}}))
        elif message.get('method') == 'thread/queue/add':
            drop_calls.append(message)
            await ws.close()
            return

drop_calls = []
def main():
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    run = pathlib.Path(tempfile.mkdtemp(prefix='transport-', dir=EVIDENCE))
    home = run/'home'; home.mkdir(mode=0o700)
    work = run/'work'; work.mkdir()
    (work/'example.txt').write_text('Owned fixture file.\n')
    def chunk(kind, data): return struct.pack('>I',len(data)) + kind + data + struct.pack('>I',zlib.crc32(kind+data))
    (work/'image.png').write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR',struct.pack('>IIBBBBB',1,1,8,2,0,0,0)) + chunk(b'IDAT',zlib.compress(b'\x00\xff\xff\xff')) + chunk(b'IEND',b''))
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Model)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    (home/'config.toml').write_text(f'''model="fixture"
model_provider="fixture"
[model_providers.fixture]
name="Local production transport fixture"
base_url="http://127.0.0.1:{server.server_port}/v1"
wire_api="responses"
requires_openai_auth=false
[projects."{work}"]
trust_level="trusted"
''')
    env = {k:v for k,v in os.environ.items() if 'API_KEY' not in k and k != 'OPENAI_ACCESS_TOKEN'}
    env['CODEX_HOME'] = str(home)
    env['JAVA_HOME'] = toolenv.JAVA_HOME
    with socket.socket() as sock: sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    url = f'ws://127.0.0.1:{port}'
    with (run/'server.log').open('w') as log:
        proc = subprocess.Popen(['codex','app-server','--listen',url], cwd=work, env=env, stdout=log, stderr=log)
        try:
            for _ in range(100):
                try:
                    with socket.create_connection(('127.0.0.1',port),timeout=.1): break
                except OSError: time.sleep(.1)
            loop = asyncio.new_event_loop()
            async def start_drop(): return await websockets.serve(dropped_request, '127.0.0.1', 0)
            drop_server = loop.run_until_complete(start_drop())
            drop_port = drop_server.sockets[0].getsockname()[1]
            threading.Thread(target=loop.run_forever, daemon=True).start()
            env['LUMA_TEST_DROP_URL'] = f'ws://127.0.0.1:{drop_port}'
            env.update(LUMA_TEST_CODEX_URL=url,LUMA_TEST_CODEX_WORK=str(work),LUMA_TEST_MODEL_URL=f'http://127.0.0.1:{server.server_port}')
            result = subprocess.run([str(ROOT/'gradlew'), ':apps:gui:testDebugUnitTest', '--offline', '--no-daemon', '--rerun'], cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            (run/'gradle.log').write_text(result.stdout)
            print(result.stdout)
        finally:
            proc.terminate()
            try: proc.wait(timeout=5)
            except subprocess.TimeoutExpired: proc.kill(); proc.wait()
            Model.released.set(); server.shutdown(); server.server_close()
    (run/'model-requests.json').write_text(json.dumps(Model.requests,indent=2))
    summary={'gradleExit':result.returncode,'modelRequests':len(Model.requests),'realBackend':'Codex app-server','model':'local deterministic fixture','scope':'production Kotlin transport + pure projection and submission-state tests','droppedMutationCalls':len(drop_calls)}
    (run/'result.json').write_text(json.dumps(summary,indent=2))
    print(run/'result.json')
    assert len(drop_calls) == 1, f'Expected exactly one mutation despite disconnect, got {len(drop_calls)}'
    raise SystemExit(result.returncode)

if __name__ == '__main__': main()

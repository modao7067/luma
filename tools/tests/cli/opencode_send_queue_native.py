#!/usr/bin/env python3
import argparse
import base64
import http.server
import json
import os
import pathlib
import secrets
import signal
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--binary', type=pathlib.Path, default=pathlib.Path('/usr/bin/opencode'))
    p.add_argument('--output', type=pathlib.Path, required=True)
    args = p.parse_args(); binary = args.binary.resolve()
    requests, checks = [], {}; hold, release = threading.Event(), threading.Event()
    report = {'cli': 'OpenCode', 'scope': 'Ubuntu native HTTP; isolated HOME + loopback fake provider; no account or paid inference', 'checks': checks}

    class Model(http.server.BaseHTTPRequestHandler):
        def log_message(self, *unused): pass
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers['Content-Length']))); requests.append(body)
            if any('HOLD_FIRST' in str(m.get('content', '')) for m in body.get('messages', []) if m.get('role') == 'user') and not release.is_set():
                hold.set(); release.wait(25)
            try:
                self.send_response(200); self.send_header('Content-Type', 'text/event-stream'); self.end_headers()
                for delta, finish in [({'role': 'assistant', 'content': 'fixture answer'}, None), ({}, 'stop')]:
                    f = {'id': 'fixture', 'object': 'chat.completion.chunk', 'created': 1, 'model': 'fixture',
                         'choices': [{'index': 0, 'delta': delta, 'finish_reason': finish}]}
                    self.wfile.write(('data: ' + json.dumps(f) + '\n\n').encode()); self.wfile.flush()
                self.wfile.write(b'data: [DONE]\n\n')
            except (BrokenPipeError, ConnectionResetError): pass

    with tempfile.TemporaryDirectory(prefix='luma-opencode-send-') as tmp:
        home = pathlib.Path(tmp); work = home / 'work'; work.mkdir()
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Model)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        with socket.socket() as sock: sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
        config = {'model': 'fixture/fixture', 'providers': {'fixture': {'package': '@opencode/ai/providers/openai-compatible',
            'settings': {'baseURL': f'http://127.0.0.1:{server.server_port}/v1', 'apiKey': 'public-local-fixture'},
            'models': {'fixture': {'name': 'Local fixture', 'capabilities': {'tools': True, 'input': ['text'], 'output': ['text']},
                                   'limit': {'context': 100000, 'output': 256}}}}}}
        password = secrets.token_hex(24)
        auth = 'Basic ' + base64.b64encode(('opencode:' + password).encode()).decode()
        env = {'OPENCODE_PASSWORD': password, 'PATH': '/usr/local/bin:/usr/bin:/bin', 'HOME': tmp, 'LANG': 'C.UTF-8',
               'OPENCODE_CONFIG_CONTENT': json.dumps(config), 'XDG_DATA_HOME': tmp + '/data', 'XDG_CONFIG_HOME': tmp + '/config',
               'XDG_CACHE_HOME': tmp + '/cache', 'XDG_STATE_HOME': tmp + '/state',
               'HTTP_PROXY': 'http://127.0.0.1:9', 'HTTPS_PROXY': 'http://127.0.0.1:9', 'NO_PROXY': '127.0.0.1,localhost'}
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        def call(path, body=None, method=None):
            r = urllib.request.Request(f'http://127.0.0.1:{port}' + path, data=None if body is None else json.dumps(body).encode(),
                method=method, headers={'Content-Type': 'application/json', 'Authorization': auth})
            with opener.open(r, timeout=15) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        def wait(predicate, timeout=30):
            end = time.monotonic() + timeout
            while not predicate() and time.monotonic() < end: time.sleep(.04)
            assert predicate(), 'native condition timed out'
        log = tempfile.TemporaryFile()
        proc = subprocess.Popen([str(binary), 'serve', '--hostname', '127.0.0.1', '--port', str(port)], env=env, cwd=work,
                                stdout=log, stderr=log, start_new_session=True)
        path = None
        try:
            end = time.monotonic() + 30
            while True:
                try: report['nativeInfo'] = call('/api/info'); break
                except OSError:
                    if proc.poll() is not None or time.monotonic() > end: raise
                    time.sleep(.1)
            native = call('/api/session', {'location': {'directory': str(work)}, 'model': {'providerID': 'fixture', 'id': 'fixture'}})['data']['id']
            path = '/api/session/' + native
            def messages(): return call(path + '/message?order=asc&limit=100')['data']
            def inbox(): return call(path + '/inbox')['data']
            def active(): return native in call('/api/session/active')['data']
            def prompt(ident, text): return call(path + '/prompt', {'id': ident, 'text': text, 'delivery': 'queue'})
            prompt('msg_luma_first', 'HOLD_FIRST'); assert hold.wait(20)
            prompt('msg_luma_remove', 'QUEUE_REMOVE')
            wait(lambda: any(q['id'] == 'msg_luma_remove' for q in inbox()))
            report['queued'] = {'inbox': inbox(), 'messages': messages()}
            assert not any(m['id'] == 'msg_luma_remove' for m in messages())
            call(path + '/inbox/msg_luma_remove', method='DELETE')
            assert not any(q['id'] == 'msg_luma_remove' for q in inbox())
            assert not any(m['id'] == 'msg_luma_remove' for m in messages())
            checks['nativeInboxOnlyAndRemoveNoUserResidue'] = True
            prompt('msg_luma_consume', 'QUEUE_CONSUME')
            wait(lambda: any(q['id'] == 'msg_luma_consume' for q in inbox()))
            call(path + '/interrupt', {}, method='POST'); release.set()
            wait(lambda: not active())
            report['stopped'] = {'inbox': inbox(), 'messages': messages()}
            prompt('msg_luma_after', 'AFTER_STOP')
            wait(lambda: not active() and not inbox() and any(m['id'] == 'msg_luma_after' for m in messages()))
            report['after'] = {'session': call(path)['data'], 'messages': messages(), 'inbox': inbox()}
            ids = [m['id'] for m in report['after']['messages'] if m.get('type') == 'user']
            assert ids.count('msg_luma_consume') == 1 and ids.count('msg_luma_after') == 1
            assert 'msg_luma_remove' not in ids
            checks['interruptThenNextSendConsumesQueueOnceSameId'] = report['after']['session']['id'] == native
            assert checks['interruptThenNextSendConsumesQueueOnceSameId']
            report['result'] = 'PASS'
        except Exception as exc:
            report['result'] = 'FAIL'; report['error'] = str(exc)
            if path:
                try: report['failure'] = {'messages': messages(), 'inbox': inbox(), 'active': active()}
                except OSError: pass
            raise
        finally:
            release.set()
            if proc.poll() is None:
                os.killpg(proc.pid, signal.SIGTERM)
                try: proc.wait(timeout=8)
                except subprocess.TimeoutExpired: os.killpg(proc.pid, signal.SIGKILL); proc.wait()
            log.close(); server.shutdown(); server.server_close()
            report['modelRequests'] = requests; report['ownedProcessExit'] = proc.poll()
            args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
            print(json.dumps({'result': report.get('result'), 'output': str(args.output)}))

if __name__ == '__main__': main()

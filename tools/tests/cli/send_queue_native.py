#!/usr/bin/env python3
import argparse
import http.server
import json
import os
import pathlib
import sys
import tempfile
import threading
import time

sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.server import Manager

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('cli', choices=['pi'])
    p.add_argument('--output', required=True, type=pathlib.Path)
    args = p.parse_args()
    frames, requests, checks = [], [], {}
    hold, release = threading.Event(), threading.Event()

    class Model(http.server.BaseHTTPRequestHandler):
        def log_message(self, *unused): pass
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            requests.append(body)
            last = str(body.get('messages', [])[-1])
            if 'HOLD_FIRST' in last:
                hold.set(); release.wait(20)
            try:
                self.send_response(200); self.send_header('Content-Type', 'text/event-stream'); self.end_headers()
                for delta, finish in [({'role': 'assistant'}, None), ({'content': 'fixture answer'}, None), ({}, 'stop')]:
                    frame = {'id': 'fixture', 'object': 'chat.completion.chunk', 'created': 1, 'model': 'fixture',
                             'choices': [{'index': 0, 'delta': delta, 'finish_reason': finish}]}
                    self.wfile.write(('data: ' + json.dumps(frame) + '\n\n').encode()); self.wfile.flush()
                self.wfile.write(b'data: [DONE]\n\n')
            except (BrokenPipeError, ConnectionResetError): pass

    report = {'cli': args.cli, 'scope': 'Ubuntu native RPC; isolated HOME + localhost fake provider; no account/key/inference', 'checks': checks}
    with tempfile.TemporaryDirectory(prefix='luma-send-queue-') as tmp:
        home = pathlib.Path(tmp); work = home / 'work'; work.mkdir()
        tooling = os.environ.get('LUMA_CLI_TOOLING_DIR', '')
        path_value = (tooling + ':' if tooling else '') + '/usr/local/bin:/usr/bin:/bin'
        os.environ.clear(); os.environ.update(HOME=tmp, PATH=path_value,
            LANG='C.UTF-8', PI_CODING_AGENT_DIR=str(home / 'agent'), XDG_CONFIG_HOME=str(home / 'config'), XDG_DATA_HOME=str(home / 'data'),
            XDG_CACHE_HOME=str(home / 'cache'), PI_NO_TITLE='1', NO_COLOR='1', DO_NOT_TRACK='1',
            HTTP_PROXY='http://127.0.0.1:9', HTTPS_PROXY='http://127.0.0.1:9', NO_PROXY='127.0.0.1,localhost')
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Model)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        agent = home / 'agent'; agent.mkdir()
        (agent / 'models.json').write_text(json.dumps({'providers': {'fixture': {
            'baseUrl': f'http://127.0.0.1:{server.server_port}/v1', 'apiKey': 'fixture-not-a-real-key', 'api': 'openai-completions',
            'models': [{'id': 'fixture', 'name': 'Fixture', 'reasoning': False, 'input': ['text'],
                        'cost': {'input': 0, 'output': 0, 'cacheRead': 0, 'cacheWrite': 0}, 'contextWindow': 32000, 'maxTokens': 1024}]}}}))
        manager = Manager(home / 'bridge'); s = None

        def wait(predicate, timeout=30):
            end = time.monotonic() + timeout
            while not predicate() and time.monotonic() < end: time.sleep(.02)
            assert predicate(), 'native condition timed out'

        def users(): return [r for r in s.rows.values() if r.get('role') == 'user']
        try:
            s = manager.connect({'id': 'fixture', 'cli': args.cli, 'cwd': str(work), 'model': 'fixture/fixture'})
            native = s.data['nativeId']; event = s.peer.event
            def observe(frame): frames.append(frame); event(frame)
            s.peer.event = observe
            call = s.call
            def observe_call(method, *a, **kw):
                result = call(method, *a, **kw)
                if method == 'get_state': report.setdefault('nativeStates', []).append(result)
                return result
            s.call = observe_call
            assert s.data['permissions'] == []
            s.submit({'submissionId': 'first', 'text': 'HOLD_FIRST'})
            assert hold.wait(20)
            s.submit({'submissionId': 'queued', 'text': 'QUEUE_SECOND'})
            wait(lambda: len(s.data['queue']) == 1)
            report['queued'] = s.snapshot()
            assert s.config['receipts']['queued'] == 'accepted'
            assert not any('QUEUE_SECOND' in r.get('text', '') for r in users())
            checks['nativeQueueHasNoPrematureUserRow'] = True
            s.stop()
            stopped = s.call('get_state')
            report['stopped'] = s.snapshot()
            checks['abortNotStreaming'] = not stopped['isStreaming']
            assert checks['abortNotStreaming']
            release.set()
            s.submit({'submissionId': 'after', 'text': 'AFTER_STOP'})
            wait(lambda: not s.data['running'] and any('AFTER_STOP' in r.get('text', '') for r in users()))
            s.refresh(); report['after'] = s.snapshot()
            assert s.data['nativeId'] == native and s.config['receipts']['after'] == 'accepted'
            assert len([r for r in users() if r.get('text') == 'AFTER_STOP']) == 1
            assert len([r for r in users() if r.get('text') == 'QUEUE_SECOND']) == 1
            assert not s.data['queue']
            checks['sameNativeIdNextSendAndQueuedConsumptionExactlyOnce'] = True
            report['result'] = 'PASS'
        except Exception as exc:
            report['result'] = 'FAIL'; report['error'] = str(exc)
            if s: report['failure'] = s.snapshot()
            raise
        finally:
            release.set()
            if s and s.peer: s.peer.close()
            server.shutdown(); server.server_close()
            report['frames'] = frames; report['modelRequests'] = requests
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
            print(json.dumps({'result': report.get('result'), 'cli': args.cli, 'output': str(args.output)}))

if __name__ == '__main__': main()

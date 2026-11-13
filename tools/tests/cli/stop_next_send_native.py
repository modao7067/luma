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
import uuid

sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.server import Manager
from luma_cli.transport import Rejected

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('cli', choices=['dsh', 'kimi', 'claude'])
    p.add_argument('--binary', required=True, type=pathlib.Path)
    p.add_argument('--output', required=True, type=pathlib.Path)
    args = p.parse_args(); binary = args.binary.resolve()
    frames, requests, checks = [], [], {}
    hold, release = threading.Event(), threading.Event()

    class Model(http.server.BaseHTTPRequestHandler):
        def log_message(self, *unused): pass
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            requests.append(body)
            if any('HOLD_FIRST' in str(m.get('content', '')) for m in body.get('messages', []) if m.get('role') == 'user') and not release.is_set():
                hold.set(); release.wait(25)
            try:
                self.send_response(200); self.send_header('Content-Type', 'text/event-stream'); self.end_headers()
                if args.cli == 'kimi':
                    for delta, finish in [({'role': 'assistant'}, None), ({'content': 'fixture answer'}, None), ({}, 'stop')]:
                        f = {'id': 'fixture', 'object': 'chat.completion.chunk', 'created': 1, 'model': 'fixture',
                             'choices': [{'index': 0, 'delta': delta, 'finish_reason': finish}]}
                        self.wfile.write(('data: ' + json.dumps(f) + '\n\n').encode()); self.wfile.flush()
                    self.wfile.write(b'data: [DONE]\n\n')
                else:
                    events = [
                        {'type': 'message_start', 'message': {'id': 'fixture-' + str(len(requests)), 'type': 'message', 'role': 'assistant',
                         'model': body.get('model', 'fixture'), 'content': [], 'stop_reason': None, 'usage': {'input_tokens': 5, 'output_tokens': 0}}},
                        {'type': 'content_block_start', 'index': 0, 'content_block': {'type': 'text', 'text': ''}},
                        {'type': 'content_block_delta', 'index': 0, 'delta': {'type': 'text_delta', 'text': 'fixture answer'}},
                        {'type': 'content_block_stop', 'index': 0},
                        {'type': 'message_delta', 'delta': {'stop_reason': 'end_turn', 'stop_sequence': None}, 'usage': {'output_tokens': 4}},
                        {'type': 'message_stop'}]
                    for f in events:
                        self.wfile.write(('event: ' + f['type'] + '\ndata: ' + json.dumps(f) + '\n\n').encode()); self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError): pass

    report = {'cli': args.cli, 'scope': 'Ubuntu native CLI; isolated HOME + loopback fake provider; no personal auth or paid inference', 'checks': checks}
    with tempfile.TemporaryDirectory(prefix='luma-stop-next-') as tmp:
        home = pathlib.Path(tmp); work = home / 'work'; work.mkdir(); bin_dir = home / 'bin'; bin_dir.mkdir()
        (bin_dir / args.cli).symlink_to(binary)
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Model)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        base = f'http://127.0.0.1:{server.server_port}'
        os.environ.clear(); os.environ.update(HOME=tmp, PATH=str(bin_dir) + ':/usr/local/bin:/usr/bin:/bin', LANG='C.UTF-8',
            DSH_HOME=tmp + '/dsh', KIMI_CODE_HOME=tmp + '/kimi', CLAUDE_CONFIG_DIR=tmp + '/claude',
            XDG_CONFIG_HOME=tmp + '/config', XDG_DATA_HOME=tmp + '/data', XDG_CACHE_HOME=tmp + '/cache',
            DEEPSEEK_API_KEY='public-local-fixture', DEEPSEEK_BASE_URL=base, ANTHROPIC_API_KEY='public-local-fixture', ANTHROPIC_BASE_URL=base,
            ANTHROPIC_MODEL='fixture', ANTHROPIC_DEFAULT_OPUS_MODEL='fixture', ANTHROPIC_DEFAULT_SONNET_MODEL='fixture', ANTHROPIC_DEFAULT_HAIKU_MODEL='fixture',
            MIMO_API_KEY='public-local-fixture', DSH_TELEMETRY_MODE='OFF', CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC='1', DISABLE_AUTOUPDATER='1',
            HTTP_PROXY='http://127.0.0.1:9', HTTPS_PROXY='http://127.0.0.1:9', NO_PROXY='127.0.0.1,localhost', NO_COLOR='1')
        if args.cli == 'dsh':
            (home / 'dsh').mkdir(); (home / 'dsh/cordis.patch.yml').write_text('- id: session-telemetry-otel\n  disabled: true\n')
        if args.cli == 'kimi':
            (home / 'kimi').mkdir(); (home / 'kimi/config.toml').write_text('default_model = "fixture"\ntelemetry = false\n'
                f'[providers.fixture]\ntype = "openai"\nbase_url = "{base}/v1"\napi_key_env = "MIMO_API_KEY"\n'
                '[models.fixture]\nprovider = "fixture"\nmodel = "fixture"\nmax_context_size = 100000\ncapabilities = ["tool_use"]\n')
        manager = Manager(home / 'bridge'); s = None
        def wait(predicate, timeout=30):
            end = time.monotonic() + timeout
            while not predicate() and time.monotonic() < end: time.sleep(.02)
            assert predicate(), 'native condition timed out'
        try:
            cli = {'dsh': 'DeepSeek Harness', 'kimi': 'Kimi Code', 'claude': 'Claude Code'}[args.cli]
            s = manager.connect({'id': 'fixture', 'cli': cli, 'cwd': str(work)})
            event = s.peer.event
            def observe(f): frames.append(f); event(f)
            s.peer.event = observe; native = s.data['nativeId']
            first, after = str(uuid.uuid4()), str(uuid.uuid4())
            s.submit({'submissionId': first, 'text': 'HOLD_FIRST'})
            assert hold.wait(25), 'native CLI did not reach loopback provider'
            try: s.submit({'submissionId': 'unsupported-queue', 'text': 'MUST_NOT_QUEUE'})
            except Rejected: checks['busySendRejectedWithoutReceipt'] = 'unsupported-queue' not in s.config.get('receipts', {})
            else: raise AssertionError('CLI without native queue accepted busy submission')
            s.stop(); wait(lambda: not s.data['running'])
            report['stopped'] = s.snapshot(); release.set()
            target = 'read-only' if args.cli == 'dsh' else 'plan'
            s.configure({'permission': target}); report['configured'] = s.snapshot()
            assert s.data['permission'] == target
            s.submit({'submissionId': after, 'text': 'AFTER_STOP'})
            wait(lambda: not s.data['running'])
            report['after'] = s.snapshot()
            assert s.data['connected'] and s.data['nativeId'] == native
            assert s.config['receipts'][after] == 'accepted'
            assert len([r for r in s.rows.values() if r.get('role') == 'user' and r.get('text') == 'AFTER_STOP']) == 1
            assert not s.data['queue'] and not s.data['requests']
            checks['nativeStopPermissionNextSendSameIdExactlyOnce'] = True
            report['result'] = 'PASS'
        except Exception as exc:
            report['result'] = 'FAIL'; report['error'] = str(exc)
            if s: report['failure'] = s.snapshot()
            raise
        finally:
            release.set()
            for owned in manager.sessions.values():
                if owned.peer: owned.peer.close()
            server.shutdown(); server.server_close()
            report['frames'] = frames; report['modelRequests'] = requests
            args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
            print(json.dumps({'result': report.get('result'), 'cli': args.cli, 'output': str(args.output)}))

if __name__ == '__main__': main()

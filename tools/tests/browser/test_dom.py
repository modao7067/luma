#!/usr/bin/env python3
import argparse
import http.server
import json
import pathlib
import subprocess
import tempfile
import threading
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[3]
ASSET = ROOT / 'apps/gui/app/src/main/assets/browser/actions.js'

class Fixture(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.end_headers()
        self.wfile.write(b'<!doctype html><html><head><meta charset="utf-8"><style>body{margin:20px}button,input,div{margin:4px}</style></head><body>Owned browser fixture</body></html>')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=pathlib.Path)
    args = parser.parse_args()
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    with tempfile.TemporaryDirectory(prefix='luma-browser-test-') as directory:
        session = 'luma-dom-' + uuid.uuid4().hex[:10]
        base = ['agent-browser', '--session', session, '--profile', directory, '--json']
        def run(*command, code=None):
            result = subprocess.run([*base, *command], input=code, text=True, capture_output=True, timeout=60)
            if result.returncode:
                raise RuntimeError(result.stderr or result.stdout)
            value = json.loads(result.stdout)
            if not value.get('success'): raise RuntimeError(value)
            return value.get('data', {})
        try:
            run('open', f'http://127.0.0.1:{server.server_port}/fixture')
            run('eval', '--stdin', code='window.lumaApi = (' + ASSET.read_text() + '); true')
            result = run('eval', '--stdin', code='(' + pathlib.Path(__file__).with_name('dom-cases.js').read_text() + ')()')['result']
            old = run('eval', '--stdin', code='window.lumaApi({action:"read"})')['result']
            run('open', f'http://127.0.0.1:{server.server_port}/new-document')
            run('eval', '--stdin', code='window.lumaApi = (' + ASSET.read_text() + '); true')
            stale = run('eval', '--stdin', code='window.lumaApi(' + json.dumps({'action':'click','snapshot':old['snapshot'],'index':0}) + ')')['result']
            assert stale['ok'] is False and stale['code'] == 'STALE_SNAPSHOT' and stale['dispatched'] is False, stale
            result['checks'].append('full document navigation discards the old snapshot')
            result['passed'] += 1
            if args.output:
                args.output.parent.mkdir(parents=True, exist_ok=True)
                args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
            print(json.dumps(result, ensure_ascii=False, indent=2))
        finally:
            try: run('close')
            finally:
                server.shutdown(); server.server_close(); thread.join(timeout=3)

if __name__ == '__main__': main()

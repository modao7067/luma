#!/usr/bin/env python3
import argparse
import copy
import json
import os
import pathlib
import resource
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.antigravity_cli import AntigravityCliSession, PROTOCOL

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--agy', type=pathlib.Path, default=pathlib.Path('/root/.local/bin/agy'))
    args = parser.parse_args()
    out = args.output.resolve();out.mkdir(parents=True, exist_ok=False)
    for name in ('home', 'tmp', 'workspace'): (out/name).mkdir()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    frames, sessions, checks = [], [], {}
    phase = {'invoked': False, 'kill': False, 'killed': False}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *unused): pass
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            contents = body.get('contents', [])
            first = json.dumps(contents[0] if contents else {})
            child = '<SYSTEM_MESSAGE>' in first and 'CHILD_FIXTURE_OK' in first
            response = any('functionResponse' in part for part in (contents[-1].get('parts', []) if contents else []))
            if not body.get('tools'): parts = [{'text': 'Fixture title'}]
            elif child and not response:
                parts = [{'text': 'fixture child thought', 'thought': True}, {'functionCall': {
                    'name': 'run_command', 'args': {'CommandLine': 'sleep 1; printf CHILD_TOOL_OK',
                    'WaitMsBeforeAsync': 10000, 'toolSummary': 'Isolated child tool'}}}]
            elif child: parts = [{'text': 'CHILD_FIXTURE_OK'}]
            elif not phase['invoked']:
                phase['invoked'] = True
                parts = [{'functionCall': {'name': 'invoke_subagent', 'args': {
                    'Role': 'fixture helper', 'TypeName': 'self', 'Model': 'inherit',
                    'Prompt': 'Run one fixture tool then reply CHILD_FIXTURE_OK.'}}}]
            elif phase['kill'] and not phase['killed']:
                phase['killed'] = True
                parts = [{'functionCall': {'name': 'manage_subagents', 'args': {'Action': 'kill_all'}}}]
            else: parts = [{'text': 'PARENT_FIXTURE_OK'}]
            reply = {'candidates': [{'content': {'role': 'model', 'parts': parts}, 'finishReason': 'STOP'}],
                'usageMetadata': {'promptTokenCount': 10, 'candidatesTokenCount': 3, 'totalTokenCount': 13}}
            raw = ('data: '+json.dumps(reply)+'\n\n').encode()
            self.send_response(200);self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Content-Length', str(len(raw)));self.end_headers();self.wfile.write(raw)

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True);thread.start()
    env = {'HOME': str(out/'home'), 'GEMINI_HOME': str(out/'home/.gemini'), 'TMPDIR': str(out/'tmp'),
        'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'NO_PROXY': 'localhost,127.0.0.1,::1',
        'GEMINI_API_KEY': 'fixture-not-a-real-key', 'GOOGLE_GEMINI_BASE_URL': f'http://127.0.0.1:{server.server_port}'}
    event = AntigravityCliSession.event
    def capture(s, frame): frames.append(frame);return event(s, frame)
    def discover(s): s.update(models=[{'id': 'gemini-3.8-flash-low', 'label': 'Fixture', 'efforts': [], 'supportsImages': False}])
    def wait(predicate):
        end = time.monotonic()+25
        while not predicate() and time.monotonic() < end: time.sleep(.02)
        assert predicate(), 'native fixture condition timed out'
    try:
        with patch.dict(os.environ, env, clear=True), patch.object(AntigravityCliSession, 'discover', discover), \
            patch.object(AntigravityCliSession, 'event', capture), patch.object(AntigravityCliSession, 'argv',
                [str(args.agy), '--settings', '{"modelProvider":"gemini"}']):
            s = AntigravityCliSession({'cli': 'Antigravity', 'cwd': str(out/'workspace'),
                'model': 'gemini-3.8-flash-low', 'nativeProtocol': PROTOCOL}, lambda: None)
            sessions.append(s);s.connect();s.submit({'submissionId': 'launch', 'text': 'Run one fixture helper then answer.'})
            wait(lambda: any(c.get('status') == 'running' for c in s.subagents.values()))
            checks['nativeStructuredDiscoveryRunning'] = True
            wait(lambda: any(c.get('status') == 'succeeded' and c.get('detail') == 'CHILD_FIXTURE_OK' for c in s.subagents.values()))
            wait(lambda: not s.data['running'])
            child = next(iter(s.subagents.values()));ident = child['id']
            assert len(s.subagents) == 1 and child['role'] == 'fixture helper'
            assert 'CHILD_FIXTURE_OK' in child['task']
            assert child['model'] == 'inherit'
            checks['nativeChildModelSelection'] = True
            tools = [r for r in s.rows.values() if r.get('toolName') == 'invoke_subagent']
            assert len(tools) == 1 and tools[0]['status'] == 'completed'
            assert child['itemId'] == tools[0]['id']
            assert any(m.get('kind') == 'reasoning' and m['text'] == 'fixture child thought' for m in child['messages'])
            assert any(m.get('toolName') == 'run_command' and 'CHILD_TOOL_OK' in m.get('outcome', '') and m['status'] == 'completed' for m in child['messages'])
            checks['realChildReasoningToolOutputAndIdle'] = True
            checks['singleLaunchToolAcrossNativeTypeChange'] = True
            s.checkpoint(force=True);config = copy.deepcopy(s.config);s.peer.close()
            config['localProjection']['subAgents'] = []
            restored = AntigravityCliSession(config, lambda: None);sessions.append(restored);restored.connect()
            wait(lambda: restored.subagents.get(ident, {}).get('status') == 'succeeded')
            assert restored.subagents[ident]['messages'] == child['messages']
            assert restored.subagents[ident]['model'] == 'inherit'
            assert not restored.current_submission
            checks['sameIdReopenRepairsMissingProjectionWithoutPrompt'] = True
            phase['kill'] = True
            restored.submit({'submissionId': 'kill', 'text': 'Kill all fixture helpers.'})
            wait(lambda: restored.subagents[ident]['status'] == 'cancelled' and not restored.data['running'])
            checks['realNativeManageKill'] = True
            checks['passed'] = True
    finally:
        for s in sessions:
            if s.peer: s.peer.close()
        server.shutdown();server.server_close();thread.join(2)
        (out/'frames.json').write_text(json.dumps(frames, ensure_ascii=False, indent=2))
        for i, s in enumerate(sessions):
            if s.peer: (out/f'native-{i}.stderr').write_bytes(s.peer.diagnostic)
        (out/'result.json').write_text(json.dumps(checks, indent=2))
        print(json.dumps(checks, indent=2))

if __name__ == '__main__': main()

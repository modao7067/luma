#!/usr/bin/env python3
import argparse
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import sys
import tempfile
import time
import traceback
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
MODEL = 'mimo-v2.6-flash'
BASE = 'https://api.xiaomimimo.com'
CLIS = {'pi': 'pi', 'dsh': 'DeepSeek Harness'}

class LiveRun:
    def __init__(self, cli, key, output, scratch=None, environment='Ubuntu 26.04.1 ARM64 chroot (not App NoRoot PRoot)'):
        self.cli, self.key, self.output = cli, key, output
        self.tmp = tempfile.TemporaryDirectory(prefix='luma-mimo-' + cli + '-', dir=scratch)
        self.home = Path(self.tmp.name)
        self.work = self.home / 'work'
        self.work.mkdir()
        self.token = secrets.token_urlsafe(40)
        (self.home / 'token').write_text(self.token)
        (self.home / 'token').chmod(0o600)
        self.result = {'cli': CLIS[cli], 'model': MODEL, 'environment': environment,
                       'provider': BASE, 'stages': [], 'snapshots': [], 'usage': [], 'nativeStderr': []}
        self.proc = None
        self.logs = []
        self.configure_home()

    def record(self, stage, **data):
        self.result['stages'].append({'stage': stage, 'seconds': round(time.monotonic() - self.started, 3), **data})
        print(self.cli, stage, json.dumps(data, ensure_ascii=False), flush=True)

    def configure_home(self):
        agent = self.home / 'agent'
        agent.mkdir()
        model = {'id': MODEL, 'name': 'MiMo V2.6 Flash live', 'reasoning': False, 'input': ['text'],
                 'contextWindow': 1000000, 'maxTokens': 2048,
                 'compat': {'supportsDeveloperRole': False, 'maxTokensField': 'max_tokens', 'supportsStore': False}}
        (agent / 'models.json').write_text(json.dumps({'providers': {'mimo-live': {
            'baseUrl': BASE + '/v1', 'apiKey': '$MIMO_API_KEY' if self.cli == 'pi' else 'MIMO_API_KEY', 'authHeader': True, 'api': 'openai-completions', 'models': [model]}}}))
        (agent / 'settings.json').write_text(json.dumps({'thinkingLevel': 'off'}))
        dsh = self.home / 'dsh'
        dsh.mkdir()
        (dsh / 'cordis.patch.yml').write_text('''- id: llm-deepseek
  config:
    baseURL: https://api.xiaomimimo.com/anthropic
    apiKeyEnv: MIMO_API_KEY
    reasoningEffort: off
    maxTokens: 2048
    models:
      - id: mimo-v2.6-flash
        name: MiMo V2.6 Flash live
        contextWindow: 1000000
        maxTokens: 2048
        inputModalities: [text]
- id: acp
  config:
    provider: deepseek-official
    model: mimo-v2.6-flash
- id: agent-default-model
  config:
    provider: deepseek-official
    model: mimo-v2.6-flash
- id: session-persistence-jsonl
  config:
    root: !!js dshHomePath('sessions')
    compression: none
- id: session-telemetry-otel
  disabled: true
''')
        self.env = {'HOME': str(self.home), 'PATH': str(ROOT / 'verification/gui-cli-adapters-20261102/tooling') + ':/usr/local/bin:/usr/bin:/bin',
                    'LANG': 'C.UTF-8', 'PI_CODING_AGENT_DIR': str(agent), 'DSH_HOME': str(dsh),
                    'XDG_CONFIG_HOME': str(self.home / 'config'), 'XDG_DATA_HOME': str(self.home / 'data'),
                    'XDG_CACHE_HOME': str(self.home / 'cache'), 'MIMO_API_KEY': self.key,
                    'NO_COLOR': '1', 'PI_NO_TITLE': '1', 'DO_NOT_TRACK': '1', 'DSH_TELEMETRY_MODE': 'OFF'}
        self.secret = 'luma-owned-' + secrets.token_hex(5)
        (self.work / 'input.txt').write_text(self.secret + '\n')

    def start(self):
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0)); self.port = sock.getsockname()[1]
        log = tempfile.TemporaryFile()
        self.logs.append(log)
        self.proc = subprocess.Popen([sys.executable, '-I', '-B', str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge/main.py'),
                                      str(self.port), str(self.home / 'token'), str(self.home / 'bridge')],
                                     cwd=self.work, env=self.env, stdout=log, stderr=log, start_new_session=True)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            try:
                if self.call('/health')[0] == 200: return
            except OSError: pass
            if self.proc.poll() is not None: raise RuntimeError('Production bridge exited during startup')
            time.sleep(.05)
        raise TimeoutError('Production bridge health timeout')

    def shutdown(self):
        if self.proc:
            group = self.proc.pid
            try: os.killpg(group, signal.SIGTERM)
            except ProcessLookupError: pass
            try: self.proc.wait(timeout=8)
            except subprocess.TimeoutExpired:
                os.killpg(group, signal.SIGKILL); self.proc.wait(timeout=5)
            try: os.killpg(group, signal.SIGKILL)
            except ProcessLookupError: pass
            self.proc = None
        for log in self.logs:
            log.seek(0)
            value = log.read().decode(errors='replace')
            if value: self.result['nativeStderr'].append(value)
            log.close()
        self.logs.clear()

    def call(self, path='', data=None):
        headers = {'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json', 'Connection': 'close'}
        req = urllib.request.Request('http://127.0.0.1:' + str(self.port) + path,
                                     data=json.dumps(data).encode() if data is not None else None, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=55) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as exc:
            return exc.code, json.load(exc)

    def action(self, name, data=None, expected=200):
        status, value = self.call('/sessions/owned/' + name, data or {})
        if status != expected: raise AssertionError((name, status, value))
        self.capture(name, value)
        return value

    def capture(self, why, value):
        self.result['snapshots'].append({'why': why, 'seconds': round(time.monotonic() - self.started, 3), 'view': value})

    def get(self):
        status, value = self.call('/sessions/owned')
        assert status == 200, value
        return value

    def wait(self, predicate, timeout=100, label='settled', approvals=True):
        deadline = time.monotonic() + timeout
        revision = None
        while time.monotonic() < deadline:
            value = self.get()
            if value.get('revision') != revision:
                self.capture(label, value); revision = value.get('revision')
            if value.get('requests') and approvals:
                for request in value['requests']:
                    options = request.get('choices', [])
                    choice = next((o for o in options if o['id'] in ('yes', 'allow_once') or 'once' in o['id'].lower()), None)
                    if not choice: raise AssertionError(('Unrecognized native approval', request))
                    self.action('answer', {'id': request['id'], 'choice': choice['id']})
                    self.record('native_approval_answered', request=request, selected=choice['id'])
            if predicate(value): return value
            if value.get('error') and not value.get('connecting'):
                raise AssertionError(('Native error', value['error']))
            time.sleep(.08)
        raise TimeoutError((label, self.get()))

    def connect(self, native=None):
        data = {'id': 'owned', 'cli': CLIS[self.cli], 'cwd': str(self.work)}
        if self.cli != 'dsh': data['model'] = 'mimo-live/' + MODEL
        if native: data['nativeId'] = native
        status, value = self.call('/sessions/connect', data)
        assert status == 200, value
        self.capture('connect', value)
        return self.wait(lambda s: s['connected'] and not s.get('connecting'), timeout=60, label='initialized')

    def collect_usage(self):
        def find(value, path):
            if isinstance(value, dict):
                if isinstance(value.get('usage'), dict):
                    self.result['usage'].append({'source': str(path.relative_to(self.home)), 'usage': value['usage'],
                                                 'model': value.get('model'), 'type': value.get('type', value.get('role'))})
                for nested in value.values(): find(nested, path)
            elif isinstance(value, list):
                for nested in value: find(nested, path)
        for path in self.home.rglob('*.jsonl'):
            for line in path.read_text(errors='replace').splitlines():
                try: find(json.loads(line), path)
                except json.JSONDecodeError: pass

    def run(self):
        self.started = time.monotonic()
        try:
            self.start()
            value = self.connect(); native = value['nativeId']
            assert MODEL in value['model'], value['model']
            assert value['models'] and any(MODEL in m['id'] for m in value['models'])
            self.record('initialized', nativeId=native, model=value['model'], capabilities=value['capabilities'],
                        permissions=value['permissions'], notice=value['notice'])
            self.action('configure', {'model': value['model']})
            efforts = next(m['efforts'] for m in value['models'] if m['id'] == value['model'])
            if 'off' in efforts: self.action('configure', {'effort': 'off'})
            if self.cli == 'dsh':
                self.action('configure', {'permission': 'danger-full-access'}, expected=422)
            else:
                self.action('configure', {'permission': 'full-access'}, expected=422)
                self.action('rename', {'title': 'MiMo isolated acceptance'})
            self.record('native_settings', efforts=efforts)
            prompt = ('This is an isolated acceptance test. Use your actual file tools to read input.txt, then write output.txt '
                      'containing that exact line followed by one newline character (not literal backslash-n). Use a command tool to run `printf LUMA_COMMAND_OK`. '
                      'Do not inspect outside this working directory; do not install or use network. Do not delegate. '
                      'After these three actions reply with the line from input.txt and LUMA_NATIVE_OK. Maximum 5 tool calls.')
            self.action('send', {'submissionId': 'tools', 'text': prompt})
            value = self.wait(lambda s: not s['running'] and s.get('receipts', {}).get('tools') == 'accepted', label='tool_roundtrip')
            assert (self.work / 'output.txt').is_file(), 'Native write did not create output.txt at the owned workspace'
            assert (self.work / 'output.txt').read_text() == self.secret + '\n', 'Native output.txt differs from expected line + newline'
            tools = [m for m in value['messages'] if m['role'] == 'tool']
            assert any('LUMA_COMMAND_OK' in (m.get('outcome', '') + m.get('detail', '')) for m in tools), tools
            assert any('LUMA_NATIVE_OK' in m['text'] for m in value['messages'] if m['role'] == 'assistant'), value
            self.action('send', {'submissionId': 'tools', 'text': 'DO NOT DISPATCH duplicate'})
            stream_views = [s for s in self.result['snapshots'] if s['why'] == 'tool_roundtrip' and s['view'].get('running')]
            assert any(m.get('status') in ('running', 'in_progress') for s in stream_views for m in s['view'].get('messages', [])), 'No incremental running projection observed'
            self.record('file_read_write_command_stream', completedTools=len([t for t in tools if t.get('status') == 'completed']), incrementalViews=len(stream_views))
            again = self.connect(native)
            assert again['nativeId'] == native and again['messages'] == value['messages']
            self.record('observer_same_id_reconnect')
            self.action('send', {'submissionId': 'slow', 'text': 'Use a command tool to run exactly `sleep 12; printf LUMA_SLOW_DONE`. Do not delegate. After it ends reply SLOW_DONE. No other actions.'})
            slow = self.wait(lambda s: s['running'] and any(m['role'] == 'tool' and m.get('status') in ('running', 'in_progress') for m in s['messages']), timeout=60, label='slow_tool_running')
            live_tools = {m['id'] for m in slow['messages'] if m['role'] == 'tool' and m.get('status') in ('running', 'in_progress')}
            refreshed = self.action('refresh')
            assert live_tools <= {m['id'] for m in refreshed['messages']}, 'Refresh dropped live tool projection'
            self.record('live_tool_survives_refresh', toolIds=sorted(live_tools))
            if self.cli == 'dsh':
                self.action('send', {'submissionId': 'busy-rejected', 'text': 'Should not enqueue'}, expected=422)
                self.record('native_no_queue_busy_rejected')
            else:
                self.action('send', {'submissionId': 'queued', 'text': 'Reply only LUMA_QUEUE_OK. No tools.'})
                queued = self.action('refresh')
                assert any(m['label'] == '原生待发消息' and m['value'] == '1' for m in queued['metrics']), queued
                assert all(not q['canEdit'] and not q['canRemove'] and not q['canSteer'] for q in queued['queue']), queued
                self.record('native_queue_observed', queuedMessages=queued['queue'])
            self.action('stop')
            value = self.wait(lambda s: not s['running'], timeout=50, label='stopped')
            value = self.action('refresh')
            assert not value['running'], value
            assert not any(m['role'] == 'tool' and 'LUMA_SLOW_DONE' in m.get('outcome', '') for m in value['messages']), 'Slow command completed before stop'
            self.record('native_stop_settled', running=value['running'], receipts=value['receipts'], remainingNativeQueue=value['queue'])
            self.shutdown(); self.start()
            value = self.connect(native)
            assert value['nativeId'] == native
            if self.cli == 'dsh': assert not value['messages'] and '不重放历史' in value['notice'], value
            else: assert any('LUMA_NATIVE_OK' in m['text'] for m in value['messages']), value
            self.record('durable_same_id_resume', historyRows=len(value['messages']), notice=value['notice'])
            self.action('send', {'submissionId': 'resumed', 'text': 'Without reading any files or using tools, reply with the exact luma-owned- code you read earlier, then LUMA_RESUME_OK. Keep the reply under 30 words.'})
            value = self.wait(lambda s: not s['running'] and s.get('receipts', {}).get('resumed') == 'accepted', label='resumed_context')
            assert any(self.secret in m['text'] and 'LUMA_RESUME_OK' in m['text'] for m in value['messages'] if m['role'] == 'assistant'), value
            self.record('resumed_context_real_inference', metrics=value['metrics'])
            self.result['status'] = 'passed'
        except Exception as exc:
            self.result['status'] = 'failed'; self.result['error'] = type(exc).__name__ + ': ' + str(exc)
            self.result['failureSite'] = traceback.format_exc()
            self.result['ownedFileCheck'] = {name: (self.work / name).read_text(errors='replace') if (self.work / name).is_file() else None for name in ('input.txt', 'output.txt')}
            print(self.cli, 'FAILED', str(exc).replace(self.key, '[REDACTED]'), flush=True)
        finally:
            self.shutdown(); self.collect_usage()
            if self.result.get('status') == 'failed':
                for path in (self.home / 'dsh' / 'logs').glob('startup-*'):
                    self.result['nativeStderr'].append(path.read_text(errors='replace'))
            self.result['durationSeconds'] = round(time.monotonic() - self.started, 3)
            self.output.mkdir(parents=True, exist_ok=True)
            serialized = json.dumps(self.result, ensure_ascii=False, indent=2).replace(self.key, '[REDACTED]').replace(self.token, '[CAPABILITY]')
            (self.output / (self.cli + '-live.json')).write_text(serialized + '\n')
            self.tmp.cleanup()
        return self.result['status'] == 'passed'

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('cli', choices=CLIS)
    parser.add_argument('--key-file', required=True, type=Path)
    parser.add_argument('--scratch-root', type=Path)
    parser.add_argument('--environment', default='Ubuntu 26.04.1 ARM64 chroot (not App NoRoot PRoot)')
    parser.add_argument('--output', type=Path, default=ROOT / 'verification/gui-cli-live-20261103/native')
    args = parser.parse_args()
    key = args.key_file.read_text().strip()
    if not key: parser.error('Empty key file')
    return 0 if LiveRun(args.cli, key, args.output, args.scratch_root, args.environment).run() else 1

if __name__ == '__main__': sys.exit(main())

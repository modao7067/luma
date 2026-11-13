#!/usr/bin/env python3
import argparse
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.claude import permission_options
from luma_cli.transport import Peer, Rejected

def run(output=None):
    executable = shutil.which('claude')
    if not executable: raise SystemExit('claude is not installed in this Ubuntu environment')
    report = {'scope': 'Ubuntu control only; no user prompt, credentials or paid inference', 'uid': os.geteuid()}
    with tempfile.TemporaryDirectory(prefix='luma-claude-permissions-') as tmp:
        env = {'PATH': os.environ.get('PATH', '/usr/bin:/bin'), 'HOME': tmp, 'CLAUDE_CONFIG_DIR': tmp + '/.claude',
               'CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC': '1', 'DISABLE_AUTOUPDATER': '1',
               'HTTP_PROXY': 'http://127.0.0.1:9', 'HTTPS_PROXY': 'http://127.0.0.1:9',
               'ALL_PROXY': 'http://127.0.0.1:9', 'NO_PROXY': '127.0.0.1,localhost'}
        report['version'] = subprocess.run([executable, '--version'], cwd=tmp, env=env, check=True,
            capture_output=True, text=True, timeout=15).stdout.strip()
        help_output = subprocess.run([executable, '--help'], cwd=tmp, env=env, check=True,
            capture_output=True, text=True, timeout=15).stdout
        options, bypass = permission_options(help_output, os.geteuid() == 0)
        report['offeredModes'] = [m['id'] for m in options]
        argv = [executable, '--print', '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose',
                '--permission-prompt-tool', 'stdio', '--no-session-persistence', '--setting-sources', '',
                '--strict-mcp-config', '--mcp-config', '{"mcpServers":{}}']
        if bypass: argv += ['--allow-dangerously-skip-permissions']

        def probe(flags, callback):
            peer = Peer(argv + flags, tmp, lambda e: None, lambda e: None, env)
            peer.start_read()
            try:
                info = peer.call('initialize', {'hooks': None}, 'claude', timeout=20)
                return callback(peer, info)
            finally: peer.close()

        def controls(peer, info):
            result = {'initialMode': info.get('current_permission_mode'), 'sets': []}
            assert result['initialMode'] == 'default', result
            for mode in ('auto', 'dontAsk', 'plan', 'default'):
                if mode not in report['offeredModes']: continue
                reply = peer.call('set_permission_mode', {'mode': mode}, 'claude', timeout=10)
                assert reply.get('mode', mode) == mode, reply
                result['sets'].append({'requested': mode, 'reply': reply})
            if not bypass:
                try: peer.call('set_permission_mode', {'mode': 'bypassPermissions'}, 'claude', timeout=10)
                except Rejected as exc: result['unarmedBypassRefusal'] = str(exc)
                else: raise AssertionError('Unarmed bypass was unexpectedly accepted')
            return result
        report['control'] = probe([], controls)
        if 'auto' in report['offeredModes']:
            def startup(peer, info):
                mode = info.get('current_permission_mode')
                assert mode == 'auto', mode
                return mode
            report['explicitAutoStartup'] = probe(['--permission-mode', 'auto'], startup)
        if os.geteuid() == 0:
            report['rootRefusals'] = []
            expected = '--dangerously-skip-permissions cannot be used with root/sudo privileges for security reasons'
            for flags in (['--allow-dangerously-skip-permissions'], ['--permission-mode', 'bypassPermissions']):
                child = subprocess.run(argv + flags, input='', cwd=tmp, env=env, capture_output=True, text=True, timeout=20)
                assert child.returncode != 0 and expected in child.stderr, (child.returncode, 'Missing native root refusal')
                report['rootRefusals'].append({'flags': flags, 'exitCode': child.returncode, 'message': expected})
        assert not (pathlib.Path(tmp) / '.claude/settings.json').exists()
    data = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if output:
        output.parent.mkdir(parents=True, exist_ok=True); output.write_text(data)
    print(data, end='')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=pathlib.Path)
    args = parser.parse_args(); run(args.output)

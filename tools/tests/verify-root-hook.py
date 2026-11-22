from pathlib import Path
REPO = Path(__file__).resolve().parents[2]
import os
import pty
import select
import shlex
import subprocess
import tempfile
import time
import json

here = Path(__file__).resolve().parent
source = Path(REPO / 'runtime/root/src/main/java/io/github/modao7067/luma/root/RootShellIntegration.java')
with tempfile.TemporaryDirectory(prefix='luma-root-hook-') as temp_dir:
    temp = Path(temp_dir)
    classes = temp / 'root-hook-classes'
    subprocess.run(['javac', '--release', '17', '-d', str(classes), str(source), str(here / 'RootHookDump.java')], check=True)
    def generated(*args):
        return subprocess.check_output(['java', '-cp', str(classes), 'io.github.modao7067.luma.root.RootHookDump', *args], text=True)
    script, command = generated(), generated('command')
    subprocess.run(['bash', '-n'], input=script, text=True, check=True)
    parts = shlex.split(command)
    assert parts[:4] == ['exec', '/data/local/linux/bin/ubuntu', '/bin/bash', '-c']
    assert parts[4] == script
    assert 'exec /bin/bash --login' in script
    assert not any(x in script for x in ['trap ', 'sleep ', 'while ', '.bashrc', '/etc/profile', 'apt '])
    (temp / 'root-startup.sh').write_text(script + '\n')

    login_env = os.environ.copy()
    login_env.pop('COLORTERM', None)
    login_probe = script.replace('exec /bin/bash --login',
        "exec /bin/bash --login -c 'test \"$COLORTERM\" = truecolor && printf TRUECOLOR_OK'")
    login_result = subprocess.check_output(['/bin/bash', '-c', login_probe], env=login_env, text=True)
    assert login_result.endswith('TRUECOLOR_OK'), login_result
    print('PASS COLORTERM=truecolor survives fresh Ubuntu login shell')

    test_script = script.replace('exec /bin/bash --login', "export PS1='STATUS:$?> '\nexec /bin/bash --noprofile --norc -i")
    master, slave = pty.openpty()
    env = {'PATH': '/usr/bin:/bin', 'HOME': str(temp), 'TERM': 'xterm-256color', 'PS1': 'STATUS:$?> '}
    proc = subprocess.Popen(['/bin/bash', '-c', test_script], stdin=slave, stdout=slave, stderr=slave, env=env, start_new_session=True)
    os.close(slave)
    def prompt():
        result = bytearray()
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            ready, _, _ = select.select([master], [], [], .1)
            if ready:
                chunk = os.read(master, 65536)
                result.extend(chunk)
                if result.endswith((b'STATUS:0> ', b'STATUS:1> ')):
                    return bytes(result)
        raise AssertionError('test shell prompt not received: ' + repr(bytes(result[-600:])))
    try:
        first = prompt()
        assert b'\x1b]133;' not in first, 'initial prompt must have no status lamp'
        samples = []
        for text, expected in [('for ((i=0;i<20000;i++)); do :; done', 0), ('false', 1), ('true', 0)]:
            os.write(master, text.encode() + b'\n')
            data = prompt()
            assert data.count(b'\x1b]133;C\x07') == 1, 'one start marker per command, no loop polling'
            assert data.count(f'\x1b]133;D;{expected}\x07'.encode()) == 1
            assert data.endswith(f'STATUS:{expected}> '.encode()), 'exit code must remain visible to PS1'
            samples.append(dict(commandKind='loop' if 'for ' in text else text, exitStatus=expected, startEvents=1, endEvents=1))
        os.write(master, b'bash --noprofile --norc -c \'declare -F __luma_root_command_finished >/dev/null; echo CHILD:$?\'\n')
        data = prompt()
        assert b'CHILD:1' in data, 'shell hook functions must not leak into children'
        os.write(master, b'exit\n')
        assert proc.wait(timeout=2) == 0
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.wait()
        os.close(master)
    result = dict(passed=True, commandQuoteRoundTrip=True, originalLauncherAndLoginBash=True,
                  initialNoLamp=True, samples=samples, loopIterations=20000,
                  preservesExitStatus=True, noDebugTrap=True, childFunctionNotExported=True,
                  boundary='Independent PTY with isolated Bash, not an Android app session or actual host profile startup')
    (temp / 'root-hook-check.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))

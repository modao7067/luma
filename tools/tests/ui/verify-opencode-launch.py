#!/usr/bin/env python3
import json
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[3]

def java_server_script(launcher, password):
    source = (ROOT / "runtime/noroot/src/main/java/io/github/modao7067/luma/GuiRuntimeBridge.java").read_text()
    section = source.split("public static String connectOpenCode(", 1)[1].split("public static String connectNativeCli(", 1)[0]
    expression = section.split('String script=', 1)[1].split(';\n', 1)[0]
    pieces = re.findall(r'"(?:[^"\\]|\\.)*"|quote\((?:launcherPath|passwordPath)\)', expression)
    values = {"quote(launcherPath)": shlex.quote(str(launcher)), "quote(passwordPath)": shlex.quote(str(password))}
    return ''.join(values[piece] if piece in values else json.loads(piece) for piece in pieces).replace('{port}', '39123')

def kotlin_tui_command(work, password):
    source = (ROOT / "apps/gui/app/src/main/java/io/github/modao7067/luma/gui/runtime/AgentRuntime.kt").read_text()
    section = source.split('else if (cli == "OpenCode") {', 1)[1].split('} else {', 1)[0]
    literal = re.search(r'^\s*command = (".*")$', section, re.MULTILINE).group(1)
    command = json.loads(literal.replace(r'\$', '$'))
    for key, value in {"cwd": str(work), "password": str(password), "server.url": "http://127.0.0.1:39123", "threadId": "ses_fixture"}.items():
        command = command.replace('${quote(' + key + ')}', shlex.quote(value))
    assert '${' not in command, "New Kotlin template needs an explicit fixture value"
    return command

class OpenCodeLaunchTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="luma opencode '")
        self.base = Path(self.temp.name)
        self.home = self.base / "home with spaces"
        self.home.mkdir()
        self.work = self.base / "work with spaces"
        self.work.mkdir()
        self.password = self.base / "private fixture password"
        self.password.write_text("isolated-fixture-only\n")
        self.output = self.base / "invocation.json"
        self.launcher = self.base / "opencode launch.py"
        source = (ROOT / "apps/gui/app/src/main/java/io/github/modao7067/luma/gui/runtime/RuntimeScripts.kt").read_text()
        self.launcher.write_text(textwrap.dedent(source.split('val openCodeServer = """', 1)[1].split('""".trimIndent()', 1)[0]).lstrip())
        (self.home / ".profile").write_text('. "$HOME/.bashrc"\n')
        (self.home / ".bashrc").write_text('case $- in *i*) ;; *) return;; esac\nexport PATH="$HOME/.opencode/bin:$PATH"\n')
        tools = self.base / 'fixture tools'
        tools.mkdir()
        (tools / 'cat').symlink_to('/usr/bin/cat')
        self.path = str(tools)

    def tearDown(self):
        self.temp.cleanup()

    def fake_cli(self, directory, identity):
        directory.mkdir(parents=True, exist_ok=True)
        executable = directory / "opencode"
        executable.write_text("#!/usr/bin/python3\n" + textwrap.dedent(f"""
            import json, os, sys
            from pathlib import Path
            Path(os.environ['LUMA_FIXTURE_OUTPUT']).write_text(json.dumps({{
                'identity': {identity!r}, 'argv': sys.argv[1:], 'cwd': os.getcwd(),
                'passwordMatches': os.environ.get('OPENCODE_PASSWORD') == 'isolated-fixture-only',
                'path': os.environ['PATH'],
            }}))
        """))
        executable.chmod(0o755)
        return executable

    def shell(self, command):
        environment = {"HOME": str(self.home), "PATH": self.path, "LUMA_FIXTURE_OUTPUT": str(self.output)}
        return subprocess.run(['/bin/bash', '--noprofile', '--norc', '-c', '. "$HOME/.profile"; ' + command],
                              cwd=self.work, env=environment, text=True, capture_output=True, timeout=5)

    def invocation(self, command):
        result = self.shell(command)
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(self.output.read_text())

    def test_old_noninteractive_profile_cannot_discover_native_install(self):
        self.fake_cli(self.home / '.opencode/bin', 'native')
        result = self.shell('command -v opencode >/dev/null 2>&1 || exit 127')
        self.assertEqual(127, result.returncode)

    def test_service_finds_native_install_and_keeps_launch_contract(self):
        self.fake_cli(self.home / '.opencode/bin', 'native')
        value = self.invocation(java_server_script(self.launcher, self.password))
        self.assertEqual('native', value['identity'])
        self.assertEqual(['serve', '--hostname', '127.0.0.1', '--port', '39123'], value['argv'])
        self.assertTrue(value['passwordMatches'])
        self.assertEqual(str(self.work), value['cwd'])

    def test_tui_finds_same_native_install_and_keeps_attach_contract(self):
        self.fake_cli(self.home / '.opencode/bin', 'native')
        value = self.invocation(kotlin_tui_command(self.work, self.password))
        self.assertEqual('native', value['identity'])
        self.assertEqual(['--server', 'http://127.0.0.1:39123', '--session', 'ses_fixture'], value['argv'])
        self.assertTrue(value['passwordMatches'])
        self.assertEqual(str(self.work), value['cwd'])

    def test_existing_path_selection_is_preserved_without_path_change(self):
        selected = self.base / "chosen CLI"
        self.fake_cli(selected, 'selected')
        self.fake_cli(self.home / '.opencode/bin', 'native')
        self.path = str(selected) + ':' + self.path
        for command in (java_server_script(self.launcher, self.password), kotlin_tui_command(self.work, self.password)):
            value = self.invocation(command)
            self.assertEqual('selected', value['identity'])
            self.assertEqual(self.path, value['path'])

    def test_missing_cli_remains_an_explicit_127_failure(self):
        for command in (java_server_script(self.launcher, self.password), kotlin_tui_command(self.work, self.password)):
            result = self.shell(command)
            self.assertEqual(127, result.returncode)
            self.assertIn('opencode', result.stderr.lower())
            self.assertFalse(self.output.exists())

if __name__ == '__main__':
    unittest.main(verbosity=2)

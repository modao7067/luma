#!/usr/bin/env python3
import importlib.util
import contextlib
import io
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[2]


def load_gate(name, directory=REPO / 'tools/tests'):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), directory / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


paths = load_gate('verify-no-host-paths')
comments = load_gate('verify-jvm-comments')
python_comments = load_gate('verify-python-comments')
runner = load_gate('verify', REPO / 'tools')


class HostPathRulesTest(unittest.TestCase):
    def test_rejects_development_rules_and_agent_configs_outside_product_assets(self):
        for name in ('AGENTS.md', 'apps/gui/CLAUDE.md', 'tools/.codex/config.toml',
                     'tools/.claude/settings.local.json', 'tools/.cursor/rules/ui.mdc',
                     'apps/gui/SKILL.md', 'tools/' + 'PROJECT' + '_MAP.md'):
            with self.subTest(path=name):
                self.assertEqual(len(paths.scan_source_path(name)), 1)

    def test_builtin_environment_instructions_are_explicit_product_exceptions(self):
        self.assertEqual(len(paths.PRODUCT_INSTRUCTION_FILES), 8)
        for name in paths.PRODUCT_INSTRUCTION_FILES:
            self.assertEqual(paths.scan_source_path(name), [])
        self.assertTrue(paths.scan_source_path('runtime/noroot/src/main/assets/workspace/.agents/private.md'))
        self.assertEqual(paths.scan_source_path('README.md'), [])

    def test_rejects_checkout_and_alias_paths_in_code_and_fixtures(self):
        checkout = '/workspace/' + 'luma'
        alias = '/workspace/docs/' + 'luma'
        for value in (checkout, checkout + '/Main.kt', alias, alias + '/report.md'):
            with self.subTest(path=value):
                findings = paths.scan_text('Fixture.kt', f'val path = "{value}"')
                self.assertEqual(len(findings), 1)
                self.assertIn('Fixture.kt:1:', findings[0])

    def test_checkout_detection_does_not_ban_synthetic_guest_paths_on_other_hosts(self):
        checkout = Path('/home/builder/private-checkout')
        self.assertTrue(paths.checkout_sample_pattern(checkout).search(str(checkout / 'Main.kt')))
        for checkout in ('/workspace', '/workspace/project', '/workspace/app', '/workspace/repo', '/root', '/tmp'):
            pattern = paths.checkout_sample_pattern(Path(checkout))
            self.assertIsNone(pattern.search('/workspace/project/Main.kt'))
            self.assertIsNone(pattern.search('/root/.bashrc'))
            self.assertIsNotNone(pattern.search('/workspace/' + 'luma/Main.kt'))

    def test_preserves_synthetic_paths_and_guest_contracts(self):
        text = '\n'.join((
            '/workspace', '/workspace/project/src/Main.kt', '/workspace/app/Dockerfile',
            '/workspace/' + 'luma-example/Main.kt', '/root/.bashrc', '/sdcard/Download',
            '/opt/luma/bin', '/workspace/example-app.apk',
        ))
        self.assertEqual(paths.scan_text('Fixture.kt', text), [])

    def test_old_host_rules_still_reject_private_values(self):
        for value in ('/workspace/' + 'PRoot-R5', '/opt/' + 'eta-aapt2/aapt2', 'local.' + 'luma.rootgui'):
            self.assertTrue(paths.scan_text('Fixture.kt', value))


class JvmCommentRulesTest(unittest.TestCase):
    def test_finds_line_block_and_doc_comments_with_line_numbers(self):
        text = 'val value = 1\n// explanation\n/* detail */\n/** documentation */\n'
        findings = comments.scan_comments('Fixture.kt', text)
        self.assertEqual(len(findings), 3)
        for line, finding in enumerate(findings, 2):
            self.assertIn(f'Fixture.kt:{line}:', finding)

    def test_ignores_urls_characters_backticks_and_escaped_strings(self):
        text = 'val url = "https://example.test"\nval code = "\\\" // fixture /* text */"\n'
        text += "val slash = '/'\nval quote = '\\\''\nfun `fixture // name`() = Unit\n"
        self.assertEqual(comments.scan_comments('Fixture.kt', text), [])

    def test_ignores_multiline_markdown_and_code_fixtures(self):
        text = 'val fixture = """\n# Heading\n// sample code\n/* sample block */\nhttps://example.test\n"""'
        self.assertEqual(comments.scan_comments('Fixture.kt', text), [])

    def test_finds_comments_inside_kotlin_string_interpolations(self):
        for quote in ('"', '"""'):
            text = 'val label = ' + quote + '${run { /* explanation */ "// fixture" }}' + quote
            self.assertEqual(len(comments.scan_comments('Fixture.kt', text)), 1)

    def test_nested_comments_do_not_hide_following_comments(self):
        text = '/* outer /* inner */ remaining */\nval value = 1\n// explanation'
        self.assertEqual(len(comments.scan_comments('Fixture.kt', text)), 2)

    def test_java_text_blocks_keep_escaped_delimiters_inside_the_string(self):
        text = 'String value = """\nescaped \\"""\n// fixture text\n""";\n// actual comment'
        findings = comments.scan_comments('Fixture.java', text)
        self.assertEqual(len(findings), 1)
        self.assertIn('Fixture.java:5:', findings[0])

    def test_gradle_gstrings_do_not_hide_expression_comments(self):
        text = 'def value = "${run { /* explanation */ \'// fixture\' }}"'
        self.assertEqual(len(comments.scan_comments('build.gradle', text)), 1)

    def test_preserves_provenance_without_exempting_the_whole_file(self):
        text = '// Copyright 2026 Example contributors\n// SPDX-License-Identifier: Apache-2.0\n'
        text += 'val value = 1\n// explanation\n'
        findings = comments.scan_comments('Fixture.kt', text)
        self.assertEqual(len(findings), 1)
        self.assertIn('Fixture.kt:4:', findings[0])
        for notice in (
            '/* Adapted Apache-2.0 lens from Example. */',
            '// Adapted from Example (MIT).\n// Source details and notices: THIRD_PARTY_NOTICES.md',
        ):
            self.assertEqual(comments.scan_comments('Fixture.kt', notice), [])
        adjacent = '// Copyright 2026 Example contributors\n// explanation\n'
        self.assertEqual(len(comments.scan_comments('Fixture.kt', adjacent)), 1)

    def test_checks_java_and_gradle_without_treating_dollar_braces_as_kotlin(self):
        for name in ('Fixture.java', 'build.gradle', 'build.gradle.kts'):
            self.assertEqual(len(comments.scan_comments(name, '"https://example.test"; // detail')), 1)
        self.assertEqual(comments.scan_comments('Fixture.java', 'String value = "${ // fixture }";'), [])

    def test_upstream_exclusions_are_specific_not_all_shared_sources(self):
        self.assertFalse(comments.is_first_party_jvm('shared/terminal-emulator/java/com/termux/terminal/TerminalEmulator.java'))
        self.assertFalse(comments.is_first_party_jvm('apps/gui/third-party/example/Source.kt'))
        self.assertTrue(comments.is_first_party_jvm('shared/ui/java/example/TerminalFileTransfer.java'))
        self.assertTrue(comments.is_first_party_jvm('tools/tests/ui/compose/Fixture.kt'))
        self.assertTrue(comments.is_first_party_jvm('apps/gui/app/src/main/java/example/Preview.kt'))


class SourceInventoryTest(unittest.TestCase):
    def test_cli_checks_unchanged_tracked_and_new_files_but_respects_ignores(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            (root / 'tools/tests').mkdir(parents=True)
            shutil.copyfile(REPO / 'tools/luma', root / 'tools/luma')
            (root / 'tools/luma').chmod(0o755)
            for name in ('verify-no-host-paths', 'verify-jvm-comments'):
                shutil.copyfile(REPO / 'tools/tests' / (name + '.py'), root / 'tools/tests' / (name + '.py'))
            (root / '.gitignore').write_text('ignored/\n')
            (root / 'ignored').mkdir()
            (root / 'ignored/Fixture.kt').write_text('// ignored comment\n')
            (root / 'Tracked.kt').write_text('// tracked comment\n')
            subprocess.run(['git', '-C', str(root), 'add', 'Tracked.kt'], check=True)
            (root / 'New.kt').write_text('val url = "https://example.test"\n// new comment\n')
            result = subprocess.run(['python3', str(root / 'tools/tests/verify-jvm-comments.py')],
                cwd='/tmp', text=True, capture_output=True)
            self.assertEqual(result.returncode, 1)
            self.assertIn('Tracked.kt:1:', result.stderr)
            self.assertIn('New.kt:2:', result.stderr)
            self.assertNotIn('ignored/Fixture.kt', result.stderr)
            (root / 'New.kt').write_text('val url = "https://example.test"\n')
            (root / 'Tracked.kt').write_text('val fixture = "// comment as test data"\n')
            result = subprocess.run(['python3', str(root / 'tools/tests/verify-jvm-comments.py')],
                cwd='/tmp', text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            (root / 'New.kt').write_text('val path = "/workspace/' + 'luma/Main.kt"\n')
            result = subprocess.run(['python3', str(root / 'tools/tests/verify-no-host-paths.py')],
                cwd='/tmp', text=True, capture_output=True)
            self.assertEqual(result.returncode, 1)
            self.assertIn('New.kt:1:', result.stderr)
            (root / 'New.kt').write_text('val path = "/workspace/project/Main.kt"\n')
            (root / 'tools/AGENTS.md').write_text('Private development instructions\n')
            result = subprocess.run(['python3', str(root / 'tools/tests/verify-no-host-paths.py')],
                cwd='/tmp', text=True, capture_output=True)
            self.assertEqual(result.returncode, 1)
            self.assertIn('tools/AGENTS.md: private development-agent rules/config file', result.stderr)


class PythonCommentRulesTest(unittest.TestCase):
    def test_strings_and_command_help_are_data_not_comments(self):
        source = 'COMMAND_DESCRIPTION = """Build the example.\n# help text\n"""\nvalue = "https://example.test/#fragment"\n'
        self.assertEqual(python_comments.scan_comments('fixture.py', source), [])

    def test_finds_module_class_function_and_async_docstrings(self):
        source = '\n'.join(('"""module"""', 'class Example:', '    """class"""',
            'def run():', '    """function"""', 'async def wait():', '    """async"""'))
        self.assertEqual(len(python_comments.scan_comments('fixture.py', source)), 4)

    def test_preserves_shebang_and_provenance_but_rejects_explanation(self):
        source = '#!/usr/bin/env python3\n# SPDX-License-Identifier: GPL-3.0-only\n# explanation\n'
        findings = python_comments.scan_comments('fixture.py', source)
        self.assertEqual(len(findings), 1)
        self.assertIn('fixture.py:3:', findings[0])

    def test_invalid_source_does_not_silently_pass(self):
        findings = python_comments.scan_comments('fixture.py', 'def broken(:\n')
        self.assertEqual(len(findings), 1)
        self.assertIn('cannot parse Python source', findings[0])

    def test_required_license_docstrings_remain_valid(self):
        source = '"""Copyright 2026 Example contributors.\nSPDX-License-Identifier: MIT\n"""\nvalue = 1\n'
        self.assertEqual(python_comments.scan_comments('fixture.py', source), [])


class StaticRunnerTest(unittest.TestCase):
    def test_reports_all_failed_gates_and_scope_instead_of_stopping_after_one(self):
        calls = []

        def run(command):
            name = Path(command[-1]).stem
            calls.append(name)
            return subprocess.CompletedProcess(command, 1 if name in ('verify-no-host-paths', 'verify-jvm-comments') else 0)

        with (
            patch.object(runner.subprocess, 'run', side_effect=run),
            patch.object(runner, 'verify_git_scope', side_effect=ValueError('Private/generated source file: fixture.apk')),
            contextlib.redirect_stdout(io.StringIO()),
            contextlib.redirect_stderr(io.StringIO()),
        ):
            with self.assertRaises(SystemExit) as raised:
                runner.static_checks()
        self.assertEqual(len(calls), 8)
        self.assertIn('verify-no-host-paths', str(raised.exception))
        self.assertIn('verify-jvm-comments', str(raised.exception))
        self.assertIn('Git scope', str(raised.exception))

    def test_ci_uses_the_same_entry_and_blocks_android_until_static_gates_pass(self):
        workflow = (REPO / '.github/workflows/ci.yml').read_text()
        self.assertIn('run: ./tools/luma verify --static', workflow)
        self.assertIn('needs: static-gates', workflow.split('  android:', 1)[1])


if __name__ == '__main__':
    unittest.main()

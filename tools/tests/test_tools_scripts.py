#!/usr/bin/env python3
import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

REPO = Path(__file__).resolve().parents[2]


def load_module(name, directory=REPO / 'tools'):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), directory / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


bootstrap = load_module('bootstrap')
verify = load_module('verify')
fetch_fonts = load_module('fetch-fonts')
build_tmux = load_module('build-native-tmux')
no_host_paths = load_module('verify-no-host-paths', REPO / 'tools/tests')


class BootstrapTest(unittest.TestCase):
    def test_cmd_fetch_propagates_failure_and_blocks_dependent_proot_build(self):
        calls = []

        def fake_run(command, env=None, check=False):
            calls.append(command)
            if command[1].endswith('fetch-native-sources.py'):
                return subprocess.CompletedProcess(command, 2)
            return subprocess.CompletedProcess(command, 0)

        def fake_missing(prefix):
            return ['item'] if prefix in ('fetch:native-src', 'build:native-src', 'build:proot') else []

        args = argparse.Namespace(no_native=False)
        with (
            patch.object(bootstrap, 'missing', side_effect=fake_missing),
            patch.object(bootstrap.subprocess, 'run', side_effect=fake_run),
            contextlib.redirect_stderr(io.StringIO()),
        ):
            rc = bootstrap.cmd_fetch(args)
        self.assertEqual(rc, 2)
        self.assertEqual(len(calls), 1)
        self.assertTrue(calls[0][1].endswith('fetch-native-sources.py'))

    def test_cmd_fetch_ghostty_honors_ndk_and_zig_env_vars(self):
        calls = []
        ensured = []

        def fake_run(command, env=None, check=False):
            calls.append(command)
            return subprocess.CompletedProcess(command, 0)

        def fake_ensure(name):
            ensured.append(name)
            return Path('/tmp/toolchain/' + name)

        args = argparse.Namespace(no_native=False)
        with tempfile.TemporaryDirectory() as tmp:
            ndk_dir = Path(tmp) / 'ndk'
            zig_bin = Path(tmp) / 'zig-bin'
            ndk_dir.mkdir()
            zig_bin.write_text('')
            with (
                patch.object(bootstrap, 'missing', side_effect=lambda p: ['so'] if p == 'build:ghostty' else []),
                patch.object(bootstrap, 'ensure_toolchain', side_effect=fake_ensure),
                patch.object(bootstrap.subprocess, 'run', side_effect=fake_run),
                patch.dict(os.environ, {'ANDROID_NDK_HOME': str(ndk_dir), 'LUMA_ZIG': str(zig_bin)}, clear=False),
            ):
                rc = bootstrap.cmd_fetch(args)
        self.assertEqual(rc, 0)
        self.assertEqual(ensured, ['ghostty-0e6afb2600d4'])
        self.assertEqual(len(calls), 1)
        self.assertIn(str(ndk_dir), calls[0])
        self.assertIn(str(zig_bin), calls[0])

    def test_cmd_import_validates_elf_sections_and_rejects_copy_or_digest_errors(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            repo = root / 'repo'
            old = root / 'old'
            rel = 'shared/terminal-ghostty/jniLibs/arm64-v8a/libluma_vt_jni.so'
            (repo / 'tools').mkdir(parents=True)
            (old / rel).parent.mkdir(parents=True)
            (old / rel).write_bytes(b'\x7fELF' + b'\x00' * 64)
            lock = {
                'schema': 1,
                'toolchains': {},
                'artifacts': {
                    rel: {
                        'bytes': 999,
                        'hash': 'elf-sections',
                        'origin': 'build:ghostty',
                        'sha256': 'expected-elf-digest',
                    },
                },
            }
            (repo / 'tools/artifacts.lock.json').write_text(json.dumps(lock))
            args = argparse.Namespace(source=str(old))
            with (
                patch.object(bootstrap, 'REPO', repo),
                patch.object(bootstrap, 'LOCK', repo / 'tools/artifacts.lock.json'),
                patch.object(bootstrap, 'elf_digest', return_value='expected-elf-digest'),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                rc = bootstrap.cmd_import(args)
            self.assertEqual(rc, 0)
            self.assertTrue((repo / rel).is_file())

            (old / 'build-tools/maven').mkdir(parents=True)
            (old / 'build-tools/maven/sample.jar').write_bytes(b'bad')
            (repo / 'tools/toolchain.json').write_text(json.dumps({
                'artifactSha256': {'$LUMA_ROOT/artifacts/maven/sample.jar': '0' * 64},
            }))
            with (
                patch.object(bootstrap, 'REPO', repo),
                patch.object(bootstrap, 'LOCK', repo / 'tools/artifacts.lock.json'),
                patch.object(bootstrap, 'elf_digest', return_value='expected-elf-digest'),
                contextlib.redirect_stdout(io.StringIO()),
                contextlib.redirect_stderr(io.StringIO()),
            ):
                rc = bootstrap.cmd_import(args)
            self.assertEqual(rc, 1)

            shutil.rmtree(repo / 'artifacts/maven')
            with (
                patch.object(bootstrap, 'REPO', repo),
                patch.object(bootstrap, 'LOCK', repo / 'tools/artifacts.lock.json'),
                patch.object(bootstrap, 'elf_digest', return_value='expected-elf-digest'),
                patch.object(bootstrap.shutil, 'copytree', side_effect=shutil.Error([('a', 'b', 'perm')])),
                contextlib.redirect_stdout(io.StringIO()),
                contextlib.redirect_stderr(io.StringIO()),
            ):
                rc = bootstrap.cmd_import(args)
            self.assertEqual(rc, 1)


class VerifyScopeTest(unittest.TestCase):
    def test_verify_git_scope_rejects_private_and_cache_files(self):
        forbidden = [
            'apps/gui/luma-release.properties',
            'tools/data.db',
            'backend/.env',
            'tools/.bash_history',
            'backend/root/root-backend.token',
            'tools/__pycache__/verify.cpython-312.pyc',
            'tools/verify.pyc',
            'tools/verify.pyo',
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for rel in forbidden:
                target = root / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text('fixture')
                with (
                    self.subTest(path=rel),
                    patch.object(verify, 'REPO', root),
                    patch.object(verify.subprocess, 'check_output', return_value=rel + '\n'),
                ):
                    with self.assertRaises(ValueError):
                        verify.verify_git_scope()

    def test_verify_strict_toolchain_cli_flag_rejects_missing_tool(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tools = root / 'tools'
            tools.mkdir()
            shutil.copy2(REPO / 'tools/toolenv.py', tools / 'toolenv.py')
            (tools / 'toolchain.json').write_text(json.dumps({
                'artifactSha256': {str(root / 'missing-tool'): '0' * 64},
            }))
            with (
                patch.object(verify, 'REPO', root),
                patch.object(verify, 'BASE', tools),
                patch.object(verify, 'static_checks'),
                patch.object(sys, 'argv', ['verify.py', '--strict-toolchain']),
                patch.object(sys, 'path', list(sys.path)),
                patch.dict(os.environ, {}, clear=False),
            ):
                os.environ.pop('LUMA_STRICT_TOOLCHAIN', None)
                with self.assertRaises(SystemExit) as raised:
                    verify.main()
            self.assertIn('Pinned tool missing', str(raised.exception))


class NoHostPathsEncodingTest(unittest.TestCase):
    def test_decodes_utf16_le_and_be_bom_and_skips_binary_nul(self):
        sample = 'path=/workspace/' + 'luma/Main.kt'
        le_bytes = sample.encode('utf-16')
        be_bytes = b'\xfe\xff' + sample.encode('utf-16-be')
        for label, raw in (('utf16le.txt', le_bytes), ('utf16be.txt', be_bytes)):
            with self.subTest(label=label):
                decoded = no_host_paths.decode_scannable_text(raw)
                self.assertIsNotNone(decoded)
                findings = no_host_paths.scan_text(label, decoded)
                self.assertEqual(len(findings), 1)
        self.assertIsNone(no_host_paths.decode_scannable_text(b'\x7fELF\x00\x01\x02\x03'))

    def test_scans_text_metadata_members_inside_tmux_zip(self):
        with tempfile.TemporaryDirectory() as tmp:
            bundle = Path(tmp) / 'tmux-debian-arm64.zip'
            with zipfile.ZipFile(bundle, 'w') as archive:
                archive.writestr('tmux', b'\x7fELF\x00\x00')
                archive.writestr('packages.json', json.dumps({'src': '/workspace/' + 'PRoot-R5/work'}))
                archive.writestr('LICENSES.txt', 'MIT')
            scanned, findings = no_host_paths.scan_tmux_bundle(bundle)
            self.assertEqual(scanned, 2)
            self.assertEqual(len(findings), 1)
            self.assertIn('tmux-debian-arm64.zip!packages.json', findings[0])


class AtomicDownloadRetryTest(unittest.TestCase):
    def test_fetch_fonts_unlinks_corrupt_cache_and_retries_once(self):
        good = b'valid-font-payload'
        expected = hashlib.sha256(good).hexdigest()
        downloads = []

        class FakeResponse(io.BytesIO):
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

        def fake_urlopen(url, timeout=120):
            downloads.append(url)
            return FakeResponse(good)

        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp)
            cached = cache / 'Sample.ttf'
            cached.write_bytes(b'corrupted-cache')
            with (
                patch.object(fetch_fonts.urllib.request, 'urlopen', side_effect=fake_urlopen),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                blob = fetch_fonts.load_verified_font(
                    'Sample.ttf', 'https://example.test/Sample.ttf', None, None, expected, cache,
                )
            self.assertEqual(blob, good)
            self.assertEqual(len(downloads), 1)
            self.assertEqual(cached.read_bytes(), good)

    def test_build_tmux_ensure_deb_unlinks_corrupt_cache_and_retries_once(self):
        good = b'valid-deb-bytes'
        expected = hashlib.sha256(good).hexdigest()
        downloads = []

        class FakeResponse(io.BytesIO):
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

        def fake_urlopen(url, timeout=120):
            downloads.append(url)
            return FakeResponse(good)

        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            deb = work / 'tmux.deb'
            deb.write_bytes(b'corrupted-deb')
            package = {'name': 'tmux', 'url': 'https://example.test/tmux.deb', 'sha256': expected}
            with patch.object(build_tmux.urllib.request, 'urlopen', side_effect=fake_urlopen):
                result = build_tmux.ensure_deb(package, work)
            self.assertEqual(result, deb)
            self.assertEqual(len(downloads), 1)
            self.assertEqual(deb.read_bytes(), good)


if __name__ == '__main__':
    unittest.main()

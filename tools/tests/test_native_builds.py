#!/usr/bin/env python3
import contextlib
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
from unittest import mock

REPO = Path(__file__).resolve().parents[2]


def executable(path, source):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source)
    path.chmod(0o755)


class NativeBuildTest(unittest.TestCase):
    def test_compute_chooses_compiler_by_runnability_not_executable_bit(self):
        for runnable in (False, True):
            with self.subTest(ndk_runnable=runnable), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                script = root / 'runtime/noroot/scripts/build-compute-check.sh'
                script.parent.mkdir(parents=True)
                shutil.copy2(REPO / script.relative_to(root), script)
                prebuilt = root / 'ndk/toolchains/llvm/prebuilt/linux-x86_64'
                (prebuilt / 'sysroot').mkdir(parents=True)
                builtins = prebuilt / 'lib/clang/18/lib/linux/libclang_rt.builtins-aarch64-android.a'
                builtins.parent.mkdir(parents=True)
                builtins.write_bytes(b'fixture')
                bin_dir = root / 'bin'
                executable(bin_dir / 'uname', '#!/bin/sh\necho aarch64\n')
                executable(bin_dir / 'glslc', f'#!{sys.executable}\nimport pathlib,sys\npathlib.Path(sys.argv[sys.argv.index("-o")+1]).write_bytes(bytes(4))\n')
                compiler = (
                    f'#!{sys.executable}\n'
                    'import json,os,pathlib,sys\n'
                    'if "--version" in sys.argv:\n'
                    '    sys.exit(0 if LABEL == "host" or os.environ["NDK_RUNNABLE"] == "1" else 126)\n'
                    'if LABEL == "ndk" and os.environ["NDK_RUNNABLE"] != "1": sys.exit(126)\n'
                    'with open(os.environ["COMPILER_LOG"], "a") as log: log.write(json.dumps([LABEL,sys.argv[1:]])+"\\n")\n'
                    'pathlib.Path(sys.argv[sys.argv.index("-o")+1]).write_bytes(b"fixture")\n'
                )
                for path, label in ((bin_dir / 'clang', 'host'), (prebuilt / 'bin/aarch64-linux-android31-clang', 'ndk')):
                    executable(path, compiler.replace('import json,os,pathlib,sys\n', f'import json,os,pathlib,sys\nLABEL={label!r}\n'))
                log = root / 'compiler.jsonl'
                env = dict(os.environ, ANDROID_NDK_HOME=str(root / 'ndk'), PATH=str(bin_dir) + os.pathsep + os.environ['PATH'],
                    NDK_RUNNABLE='1' if runnable else '0', COMPILER_LOG=str(log))
                result = subprocess.run(['bash', str(script)], env=env, text=True, capture_output=True, timeout=15)
                self.assertEqual(0, result.returncode, result.stderr)
                calls = [json.loads(line) for line in log.read_text().splitlines()]
                self.assertEqual(['ndk' if runnable else 'host'] * 2, [call[0] for call in calls])
                for _, args in calls:
                    self.assertIn('-Wl,-z,max-page-size=16384,-z,noexecstack,-z,relro,-z,now', args)
                    if not runnable:
                        self.assertIn(str(builtins), args)
                        self.assertIn('-nodefaultlibs', args)
                        self.assertIn('-lc', args)

    def test_persistent_proot_build_recompiles_shmem_and_refreshes_header(self):
        spec = importlib.util.spec_from_file_location('native_proot_build', REPO / 'tools/build-native-proot.py')
        builder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(builder)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            proot = root / 'source/proot'
            (proot / 'src').mkdir(parents=True)
            (proot / 'src/GNUmakefile').write_text('fixture')
            shmem = root / 'source/shmem'
            shmem.mkdir(parents=True)
            prefix = root / 'prefix'
            (prefix / 'lib').mkdir(parents=True)
            (prefix / 'lib/libtalloc.a').write_bytes(b'fixture')
            tools = {key: key.lower() for key in ('CC', 'AR', 'STRIP', 'OBJCOPY', 'OBJDUMP')}
            compiled = []
            def run(command, **kwargs):
                if command[0] == 'cc':
                    source = Path(command[command.index('-c') + 1])
                    compiled.append(source.read_bytes())
                    Path(command[command.index('-o') + 1]).write_bytes(source.read_bytes())
                elif command[0] == 'ar':
                    Path(command[2]).write_bytes(Path(command[3]).read_bytes())
                elif command[0] == 'make':
                    cwd = Path(kwargs['cwd'])
                    (cwd / 'loader').mkdir()
                    (cwd / 'proot').write_bytes((prefix / 'lib/libandroid-shmem.a').read_bytes())
                    (cwd / 'loader/loader').write_bytes(b'loader')
            with mock.patch.object(builder, 'PROOT_SRC', proot), mock.patch.object(builder, 'SHMEM_SRC', shmem), \
                    mock.patch.object(builder, 'prepare_toolchain', return_value=(root, tools)), \
                    mock.patch.object(builder.subprocess, 'check_output', return_value='#define LOADER_ADDRESS 0x10000\n'), \
                    mock.patch.object(builder.subprocess, 'run', side_effect=run), \
                    mock.patch.dict(os.environ, LP_PREFIX=str(prefix), LP_NOSTRIP='1'), contextlib.redirect_stdout(io.StringIO()):
                for version in ('first', 'second'):
                    (shmem / 'shmem.c').write_text(version)
                    (shmem / 'shm.h').write_text(version + ' header')
                    builder.build_proot(root, root / 'unused.tgz', root / 'build', root / 'out')
                    self.assertEqual(version.encode(), (root / 'out/libproot_exec.so').read_bytes())
                    self.assertEqual(version + ' header', (prefix / 'include/sys/shm.h').read_text())
            self.assertEqual([b'first', b'second'], compiled)


if __name__ == '__main__':
    unittest.main()

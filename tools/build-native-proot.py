#!/usr/bin/env python3
import argparse
import gzip
import hashlib
import io
import os
from pathlib import Path
import platform
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile

REPO = Path(__file__).resolve().parents[1]
THIRD_PARTY = REPO / 'runtime/noroot/third-party'
PROOT_SRC = THIRD_PARTY / 'proot'
SHMEM_SRC = THIRD_PARTY / 'libandroid-shmem'
NATIVE_SOURCES_DIR = REPO / 'runtime/noroot/src/main/assets/native-sources'
DEFAULT_TALLOC_TGZ = NATIVE_SOURCES_DIR / 'talloc.tgz'
DEFAULT_SOURCE_BUNDLE = NATIVE_SOURCES_DIR / 'proot-sources.tgz'
DEFAULT_OUT_DIR = REPO / 'runtime/noroot/src/main/jniLibs/arm64-v8a'
TALLOC_SHA256 = 'dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd'

CROSS_ANSWERS = """Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
"""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def iter_third_party_files() -> list[Path]:
    try:
        out = subprocess.check_output(
            ['git', '-C', str(REPO), 'ls-files', '-z', '--', 'runtime/noroot/third-party'],
            stderr=subprocess.DEVNULL,
        )
        files = [REPO / rel for rel in out.decode('utf-8').split('\0') if rel]
        existing = [p for p in files if p.is_file()]
        if existing:
            return sorted(existing)
    except (OSError, subprocess.CalledProcessError):
        pass
    return sorted(p for p in THIRD_PARTY.rglob('*') if p.is_file())


def package_sources(bundle_path: Path) -> None:
    entries: list[tuple[str, bytes, int]] = []
    for src in iter_third_party_files():
        rel = src.relative_to(THIRD_PARTY).as_posix()
        mode = 0o755 if os.access(src, os.X_OK) or rel.endswith(('.py', '.sh')) else 0o644
        entries.append(('runtime/noroot/third-party/' + rel, src.read_bytes(), mode))
    script_path = Path(__file__).resolve()
    entries.append(('tools/build-native-proot.py', script_path.read_bytes(), 0o755))
    entries.sort(key=lambda item: item[0])

    bundle_path.parent.mkdir(parents=True, exist_ok=True)
    partial = bundle_path.with_suffix(bundle_path.suffix + '.partial')
    with partial.open('wb') as raw:
        with gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as gz:
            with tarfile.open(fileobj=gz, mode='w') as archive:
                for name, data, mode in entries:
                    info = tarfile.TarInfo(name)
                    info.size = len(data)
                    info.mode = mode
                    info.mtime = 0
                    info.uid = 0
                    info.gid = 0
                    info.uname = 'root'
                    info.gname = 'root'
                    archive.addfile(info, io.BytesIO(data))
    partial.replace(bundle_path)


def can_execute(binary: Path) -> bool:
    if not binary.is_file() or not os.access(binary, os.X_OK):
        return False
    try:
        res = subprocess.run([str(binary), '--version'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return res.returncode == 0
    except OSError:
        return False


def prepare_toolchain(ndk_dir: Path, build_dir: Path) -> tuple[Path, dict[str, str]]:
    override_tools = os.environ.get('PROOT_R5_TOOLS') or os.environ.get('LUMA_PROOT_TOOLS')
    if override_tools:
        tools_bin = Path(override_tools).resolve()
        cc = tools_bin / 'aarch64-linux-android34-clang'
        if not cc.is_file():
            raise SystemExit(f'Missing compiler wrapper in {tools_bin}: {cc}')
        return tools_bin, {
            'CC': str(cc),
            'AR': str(tools_bin / 'ar'),
            'STRIP': str(tools_bin / 'strip'),
            'OBJCOPY': str(tools_bin / 'objcopy'),
            'OBJDUMP': str(tools_bin / 'objdump'),
        }

    prebuilt = None
    machine = platform.machine().lower()
    preferred_tags = ['linux-aarch64', 'linux-x86_64', 'darwin-x86_64'] if machine in ('aarch64', 'arm64') else ['linux-x86_64', 'linux-aarch64', 'darwin-x86_64']
    for tag in preferred_tags:
        candidate = ndk_dir / 'toolchains/llvm/prebuilt' / tag
        if (candidate / 'sysroot').is_dir():
            prebuilt = candidate
            break
    if prebuilt is None:
        raise SystemExit(f'Android NDK sysroot not found under {ndk_dir}')

    ndk_clang = prebuilt / 'bin/aarch64-linux-android34-clang'
    if can_execute(ndk_clang):
        tools_bin = prebuilt / 'bin'
        return tools_bin, {
            'CC': str(ndk_clang),
            'AR': str(tools_bin / 'llvm-ar'),
            'STRIP': str(tools_bin / 'llvm-strip'),
            'OBJCOPY': str(tools_bin / 'llvm-objcopy'),
            'OBJDUMP': str(tools_bin / 'llvm-objdump'),
        }

    host_clang = os.environ.get('HOST_CLANG') or shutil.which('clang')
    if not host_clang:
        raise SystemExit('Host clang is required when NDK prebuilt binaries cannot run on this architecture')

    host_res = Path(subprocess.check_output([host_clang, '-print-resource-dir'], text=True).strip())
    ndk_clang_libs = sorted((prebuilt / 'lib/clang').glob('*/lib'))
    if not ndk_clang_libs:
        raise SystemExit(f'NDK compiler-rt directory not found under {prebuilt / "lib/clang"}')

    tc_dir = build_dir / 'toolchain'
    rt_dir = tc_dir / 'rt'
    bin_dir = tc_dir / 'bin'
    if tc_dir.exists():
        shutil.rmtree(tc_dir)
    rt_dir.mkdir(parents=True)
    bin_dir.mkdir(parents=True)
    (rt_dir / 'include').symlink_to(host_res / 'include')
    (rt_dir / 'lib').symlink_to(ndk_clang_libs[-1])

    use_lld = shutil.which('aarch64-linux-gnu-ld') is None and shutil.which('ld.lld') is not None
    lld_flag = '  -fuse-ld=lld \\\n' if use_lld else ''
    wrapper = bin_dir / 'aarch64-linux-android34-clang'
    wrapper.write_text(
        '#!/bin/sh\n'
        f'exec "{host_clang}" \\\n'
        '  --target=aarch64-linux-android34 \\\n'
        f'  --sysroot="{prebuilt / "sysroot"}" \\\n'
        f'  -resource-dir="{rt_dir}" \\\n'
        '  -rtlib=compiler-rt \\\n'
        f'{lld_flag}'
        f'  --gcc-toolchain="{prebuilt}" \\\n'
        '  "$@"\n'
    )
    wrapper.chmod(wrapper.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    resolved: dict[str, str] = {'CC': str(wrapper)}
    for key, names in [
        ('AR', ('llvm-ar', 'ar')),
        ('STRIP', ('llvm-strip', 'strip')),
        ('OBJCOPY', ('llvm-objcopy', 'objcopy')),
        ('OBJDUMP', ('llvm-objdump', 'objdump')),
    ]:
        found = next((shutil.which(n) for n in names if shutil.which(n)), None)
        if not found:
            raise SystemExit(f'Missing required binary tool for {key}: {names}')
        link = bin_dir / key.lower()
        link.symlink_to(found)
        resolved[key] = str(link)
    return bin_dir, resolved


def resolve_ndk(explicit: Path | None) -> Path:
    if explicit is not None:
        return explicit.resolve()
    for key in ('ANDROID_NDK_HOME', 'LUMA_NDK_DIR'):
        val = os.environ.get(key)
        if val:
            return Path(val).resolve()
    return (REPO / 'artifacts/toolchain/android-ndk-r27c').resolve()


def resolve_talloc(explicit: Path | None) -> Path:
    candidates = [explicit] if explicit is not None else [
        DEFAULT_TALLOC_TGZ,
        REPO / 'artifacts/download-cache/talloc-2.4.3.tar.gz',
    ]
    for candidate in candidates:
        if candidate and candidate.is_file():
            actual = sha256_file(candidate)
            if actual != TALLOC_SHA256:
                raise SystemExit(f'SHA-256 mismatch for {candidate}: {actual} != {TALLOC_SHA256}')
            return candidate
    raise SystemExit('Missing talloc-2.4.3 archive; run `python3 tools/fetch-native-sources.py` first')


def build_proot(ndk_dir: Path, talloc_tgz: Path, build_dir: Path, out_dir: Path) -> None:
    if not (PROOT_SRC / 'src/GNUmakefile').is_file():
        raise SystemExit(f'Missing PRoot source tree at {PROOT_SRC}')
    if not (SHMEM_SRC / 'shmem.c').is_file():
        raise SystemExit(f'Missing libandroid-shmem source tree at {SHMEM_SRC}')

    build_dir.mkdir(parents=True, exist_ok=True)
    out_dir.mkdir(parents=True, exist_ok=True)
    tools_bin, tools = prepare_toolchain(ndk_dir, build_dir)

    prefix = Path(os.environ['LP_PREFIX']).resolve() if os.environ.get('LP_PREFIX') else (build_dir / 'prefix')
    (prefix / 'include/sys').mkdir(parents=True, exist_ok=True)
    (prefix / 'lib').mkdir(parents=True, exist_ok=True)

    base_env = os.environ.copy()
    base_env['PATH'] = f"{tools_bin}:{base_env.get('PATH', '')}"
    base_env['LC_ALL'] = 'C'
    base_env['PYTHONHASHSEED'] = '1'
    base_env['PYTHON'] = sys.executable

    libtalloc = prefix / 'lib/libtalloc.a'
    if not libtalloc.is_file():
        talloc_work = build_dir / 'talloc-work'
        if talloc_work.exists():
            shutil.rmtree(talloc_work)
        talloc_work.mkdir(parents=True)
        with tarfile.open(talloc_tgz, 'r:gz') as tf:
            tf.extractall(talloc_work)
        talloc_tree = talloc_work / 'talloc-2.4.3'
        (talloc_tree / 'cross-answers.txt').write_text(CROSS_ANSWERS)
        talloc_env = dict(
            base_env,
            CC=tools['CC'],
            AR=tools['AR'],
            CFLAGS=f"-O2 -fPIC -D__STDC_WANT_LIB_EXT1__=1 -ffile-prefix-map={talloc_tree}=.",
        )
        subprocess.run(
            [
                './configure',
                f'--prefix={prefix}',
                '--disable-rpath',
                '--disable-python',
                '--cross-compile',
                '--cross-answers=cross-answers.txt',
            ],
            cwd=talloc_tree,
            env=talloc_env,
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        subprocess.run(
            [sys.executable, 'buildtools/bin/waf', 'build', '--targets=talloc'],
            cwd=talloc_tree,
            env=talloc_env,
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        objs = sorted((talloc_tree / 'bin/default').glob('talloc.c.*.o'))
        if not objs:
            raise SystemExit('talloc build produced no object files')
        subprocess.run([tools['AR'], 'rcs', str(libtalloc), *[str(o) for o in objs]], env=base_env, check=True)
        shutil.copy2(talloc_tree / 'talloc.h', prefix / 'include/talloc.h')

    libshmem = prefix / 'lib/libandroid-shmem.a'
    shmem_work = build_dir / 'shmem'
    if shmem_work.exists():
        shutil.rmtree(shmem_work)
    shmem_work.mkdir(parents=True)
    shutil.copy2(SHMEM_SRC / 'shmem.c', shmem_work / 'shmem.c')
    shutil.copy2(SHMEM_SRC / 'shm.h', shmem_work / 'shm.h')
    shmem_obj = build_dir / 'shmem.o'
    subprocess.run(
        [
            tools['CC'],
            '-O2',
            '-fPIC',
            '-fstack-protector-strong',
            '-std=c11',
            f'-ffile-prefix-map={build_dir}=.',
            '-c',
            str(shmem_work / 'shmem.c'),
            '-o',
            str(shmem_obj),
        ],
        env=base_env,
        check=True,
    )
    subprocess.run([tools['AR'], 'rcs', str(libshmem), str(shmem_obj)], env=base_env, check=True)
    shutil.copy2(SHMEM_SRC / 'shm.h', prefix / 'include/sys/shm.h')

    proot_work = build_dir / 'proot'
    if proot_work.exists():
        shutil.rmtree(proot_work)
    shutil.copytree(PROOT_SRC, proot_work)
    proot_src_work = proot_work / 'src'

    macros = subprocess.check_output(
        [tools['CC'], '-E', '-dM', '-DNO_LIBC_HEADER', 'arch.h'],
        cwd=proot_src_work,
        env=base_env,
        text=True,
    )
    loader_address = None
    for line in macros.splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[0] == '#define' and parts[1] == 'LOADER_ADDRESS':
            loader_address = parts[2]
            break
    if not loader_address:
        raise SystemExit('Failed to determine LOADER_ADDRESS from arch.h')

    extra_cflags = os.environ.get('EXTRA_CFLAGS', '').strip()
    extra_libs = os.environ.get('LP_EXTRA_LIBS', '-llog')
    cflags_parts = [
        '-O2 -Wall -Wextra -fPIE -fstack-protector-strong',
        f'-ffile-prefix-map={build_dir}=.',
        f'-ffile-prefix-map={REPO}=.',
    ]
    if extra_cflags:
        cflags_parts.append(extra_cflags)
    cflags_parts.append('-DWITH_LIBANDROID_SHMEM -DPROOT_UNBUNDLE_LOADER=\\"/dev/null\\" -DVERSION=\\"5.1.107.92\\"')

    jobs = str(os.cpu_count() or 4)
    subprocess.run(
        [
            'make',
            f'-j{jobs}',
            f"CC={tools['CC']}",
            f"STRIP={tools['STRIP']}",
            f"OBJCOPY={tools['OBJCOPY']}",
            f"OBJDUMP={tools['OBJDUMP']}",
            'GIT=false',
            'HAS_LOADER_32BIT=',
            'PROOT_UNBUNDLE_LOADER=/dev/null',
            'PROOT_WITH_LIBANDROID_SHMEM=1',
            f'CPPFLAGS=-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I{prefix / "include"}',
            f"CFLAGS={' '.join(cflags_parts)}",
            f'LDFLAGS=-pie -L{prefix / "lib"} -ltalloc -landroid-shmem {extra_libs} -Wl,-z,noexecstack,-z,max-page-size=16384,-z,relro,-z,now',
            f'LOADER_LDFLAGS=-static -nostdlib -Wl,--build-id=none,-Ttext={loader_address},--rosegment,-z,noexecstack,-z,max-page-size=16384',
        ],
        cwd=proot_src_work,
        env=base_env,
        check=True,
        stdout=subprocess.DEVNULL,
    )

    exec_out = out_dir / 'libproot_exec.so'
    loader_out = out_dir / 'libproot_loader.so'
    shutil.copy2(proot_src_work / 'proot', exec_out)
    shutil.copy2(proot_src_work / 'loader/loader', loader_out)
    if os.environ.get('LP_NOSTRIP', '0') != '1':
        subprocess.run([tools['STRIP'], str(exec_out), str(loader_out)], env=base_env, check=True)
    print(f'Built {exec_out} ({exec_out.stat().st_size} bytes, sha256={sha256_file(exec_out)})')
    print(f'Built {loader_out} ({loader_out.stat().st_size} bytes, sha256={sha256_file(loader_out)})')


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--ndk', type=Path)
    parser.add_argument('--talloc', type=Path)
    parser.add_argument('--build-dir', type=Path)
    parser.add_argument('--out-dir', type=Path, default=DEFAULT_OUT_DIR)
    parser.add_argument('--source-bundle', type=Path, default=DEFAULT_SOURCE_BUNDLE)
    parser.add_argument('--package-sources-only', action='store_true')
    parser.add_argument('--no-package-sources', action='store_true')
    args = parser.parse_args()

    if args.package_sources_only:
        package_sources(args.source_bundle.resolve())
        print(f'Packaged {args.source_bundle} ({args.source_bundle.stat().st_size} bytes, sha256={sha256_file(args.source_bundle)})')
        return 0

    ndk_dir = resolve_ndk(args.ndk)
    talloc_tgz = resolve_talloc(args.talloc)
    out_dir = args.out_dir.resolve()
    if args.build_dir is not None:
        build_proot(ndk_dir, talloc_tgz, args.build_dir.resolve(), out_dir)
    else:
        with tempfile.TemporaryDirectory(prefix='luma-proot-build-') as tmp:
            build_proot(ndk_dir, talloc_tgz, Path(tmp), out_dir)

    if not args.no_package_sources:
        package_sources(args.source_bundle.resolve())
        print(f'Packaged {args.source_bundle} ({args.source_bundle.stat().st_size} bytes, sha256={sha256_file(args.source_bundle)})')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

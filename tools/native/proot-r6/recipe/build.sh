#!/usr/bin/env bash
# PRoot 5.1.107.92 (Luma L2 recipe) + talloc 2.4.3 + libandroid-shmem 0.7
# for aarch64 Android API 34.  This round's root only writes its own tree;
# the R5/R2 toolchain and sources are read-only inputs.
#
# Usage: build.sh <build-dir> <out-dir> [extra-patch ...]
# Env:   LP_PREFIX=<shared talloc/shmem prefix>
#        LP_EXTRA_LIBS="<link libs>"      default "-llog"
#        LP_LAZY_LIBANDROID=1             L2 lazy libandroid shim
#        LP_NOSTRIP=1
#        EXTRA_CFLAGS="..."
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
BASE=${PROOT_R5_BASE:-/workspace/PRoot-R5}
SRC="${SRC_ROOT:-$ROOT/work/src}"
PROOT_TREE="${PROOT_SRC:-$SRC/proot-5.1.107.92}"
TOOLS="${PROOT_R5_TOOLS:-$BASE/work/toolchain/bin}"
CC="$TOOLS/aarch64-linux-android34-clang"
BUILD=$(mkdir -p "${1:?build dir}" && cd "$1" && pwd)
OUT=$(mkdir -p "${2:?out dir}" && cd "$2" && pwd)
shift 2
EXTRA_PATCHES=("$@")

PREFIX="${LP_PREFIX:-$BUILD/prefix}"
mkdir -p "$BUILD" "$PREFIX/include/sys" "$PREFIX/lib" "$OUT"
export PATH="$TOOLS:$PATH"
export LC_ALL=C PYTHONHASHSEED=1 PYTHON=python3

log() { printf '[build %s] %s\n' "$(date +%H:%M:%S)" "$*"; }

if [[ ! -f "$PREFIX/lib/libtalloc.a" ]]; then
  log "talloc"
  rm -rf "$BUILD/talloc"
  cp -R "$SRC/talloc-2.4.3" "$BUILD/talloc"
  cat > "$BUILD/talloc/cross-answers.txt" <<'ANSWERS'
Checking uname sysname type: "Linux"
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
ANSWERS
  (
    cd "$BUILD/talloc"
    export CC="$CC" AR="$TOOLS/ar"
    export CFLAGS="-O2 -fPIC -D__STDC_WANT_LIB_EXT1__=1"
    ./configure --prefix="$PREFIX" --disable-rpath --disable-python \
      --cross-compile --cross-answers=cross-answers.txt > configure.log 2>&1
    python3 buildtools/bin/waf build --targets=talloc > build.log 2>&1
    "$TOOLS/ar" rcs "$PREFIX/lib/libtalloc.a" bin/default/talloc.c.*.o
    cp talloc.h "$PREFIX/include/talloc.h"
  )
fi

if [[ ! -f "$PREFIX/lib/libandroid-shmem.a" ]]; then
  log "libandroid-shmem"
  rm -rf "$BUILD/shmem"; mkdir -p "$BUILD/shmem"
  cp "$SRC/libandroid-shmem-0.7/shmem.c" "$SRC/libandroid-shmem-0.7/shm.h" "$BUILD/shmem/"
  patch -s -p1 -d "$BUILD/shmem" < "$ROOT/recipe/patches/shmem-app-temp.patch"
  if [[ "${LP_LAZY_LIBANDROID:-0}" == "1" ]]; then
    log "libandroid-shmem: lazy libandroid"
    patch -s -p1 -d "$BUILD/shmem" < "$ROOT/patches/0003-r2-lazy-libandroid.patch"
  fi
  "$CC" -O2 -fPIC -std=c11 -c "$BUILD/shmem/shmem.c" -o "$BUILD/shmem.o"
  "$TOOLS/ar" rcs "$PREFIX/lib/libandroid-shmem.a" "$BUILD/shmem.o"
  cp "$SRC/libandroid-shmem-0.7/shm.h" "$PREFIX/include/sys/shm.h"
fi

log "proot"
rm -rf "$BUILD/proot"
cp -R "$PROOT_TREE" "$BUILD/proot"
patch -s -p1 -d "$BUILD/proot" < "$ROOT/recipe/patches/proot-bionic-headers.patch"
for p in "${EXTRA_PATCHES[@]:-}"; do
  [[ -n "$p" ]] || continue
  log "apply $(basename "$p")"
  patch -s -p1 -d "$BUILD/proot" < "$p"
done

(
  cd "$BUILD/proot/src"
  LOADER_ADDRESS=$("$CC" -E -dM -DNO_LIBC_HEADER arch.h | awk '$2 == "LOADER_ADDRESS" {print $3}')
  log "LOADER_ADDRESS=$LOADER_ADDRESS"
  make -j"$(nproc)" \
    CC="$CC" STRIP="$TOOLS/strip" OBJCOPY="$TOOLS/objcopy" OBJDUMP="$TOOLS/objdump" \
    GIT=false HAS_LOADER_32BIT= PROOT_UNBUNDLE_LOADER=/dev/null PROOT_WITH_LIBANDROID_SHMEM=1 \
    CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$PREFIX/include" \
    CFLAGS="-O2 -Wall -Wextra -fPIE -fno-stack-protector -ffile-prefix-map=$BUILD=. -ffile-prefix-map=$ROOT=. ${EXTRA_CFLAGS:-} -DWITH_LIBANDROID_SHMEM -DPROOT_UNBUNDLE_LOADER=\"\\\"/dev/null\\\"\" -DVERSION=\"\\\"5.1.107.92\\\"\"" \
    LDFLAGS="-pie -L$PREFIX/lib -ltalloc -landroid-shmem ${LP_EXTRA_LIBS--llog} -Wl,-z,noexecstack,-z,max-page-size=16384,-z,relro,-z,now" \
    LOADER_LDFLAGS="-static -nostdlib -Wl,--build-id=none,-Ttext=$LOADER_ADDRESS,--rosegment,-z,noexecstack,-z,max-page-size=16384" \
    > build.log 2>&1
)

cp "$BUILD/proot/src/proot" "$OUT/libproot_exec.so"
cp "$BUILD/proot/src/loader/loader" "$OUT/libproot_loader.so"
if [[ "${LP_NOSTRIP:-0}" != "1" ]]; then
  "$TOOLS/strip" "$OUT/libproot_exec.so" "$OUT/libproot_loader.so"
fi
log "done: $OUT"

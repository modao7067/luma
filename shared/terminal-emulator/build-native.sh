#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd -- "$HERE/../.." && pwd)
SRC="$HERE/upstream-native-source/termux.c"
OUT="$HERE/src/main/jniLibs/arm64-v8a/libtermux.so"

NDK="${ANDROID_NDK_HOME:-${LUMA_NDK_DIR:-$REPO/artifacts/toolchain/android-ndk-r27c}}"
PREBUILT=""
for host_tag in linux-aarch64 linux-x86_64 darwin-x86_64; do
    if [[ -d "$NDK/toolchains/llvm/prebuilt/$host_tag/sysroot" ]]; then
        PREBUILT="$NDK/toolchains/llvm/prebuilt/$host_tag"
        break
    fi
done
if [[ -z "$PREBUILT" ]]; then
    echo "Android NDK sysroot not found under: $NDK" >&2
    exit 1
fi

SYSROOT="$PREBUILT/sysroot"
mkdir -p "$(dirname -- "$OUT")"

COMMON_FLAGS=(
    -shared -fPIC -O2 -std=c11 -Wall -Wextra
    "-ffile-prefix-map=$HERE=shared/terminal-emulator"
    -Wl,-soname,libtermux.so
    -Wl,-z,max-page-size=16384,-z,noexecstack,-z,relro,-z,now
    -Wl,--build-id=sha1
)

if [[ "$(uname -m)" == "aarch64" && ! -d "$NDK/toolchains/llvm/prebuilt/linux-aarch64" ]] && command -v clang >/dev/null 2>&1; then
    BUILTINS=$(find "$PREBUILT/lib/clang" -name "libclang_rt.builtins-aarch64-android.a" | head -n 1)
    CC=(clang --target=aarch64-linux-android31 --sysroot="$SYSROOT" -fuse-ld=lld -nodefaultlibs)
    LIBS=("$BUILTINS" -lc)
    STRIP=$(command -v llvm-strip || command -v strip)
else
    CC=("$PREBUILT/bin/aarch64-linux-android31-clang")
    LIBS=(-lc)
    STRIP="$PREBUILT/bin/llvm-strip"
fi

TMP="$OUT.tmp.$$"
trap 'rm -f "$TMP"' EXIT
"${CC[@]}" "${COMMON_FLAGS[@]}" "$SRC" "${LIBS[@]}" -o "$TMP"
"$STRIP" --strip-unneeded "$TMP"
chmod 0755 "$TMP"
mv "$TMP" "$OUT"
echo "Built $OUT ($(wc -c < "$OUT") bytes)"

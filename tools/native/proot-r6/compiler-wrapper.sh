#!/bin/sh
# Host clang 21 targeting Android aarch64 (API 34) with the NDK r27c sysroot.
# The NDK's own prebuilt clang is a linux-x86_64 binary and cannot run on this
# ARM64 host, so the host compiler is used with the NDK sysroot instead.
# Overridable: LUMA_NDK_DIR (NDK r27c), PROOT_R5_BASE (toolchain workspace), HOST_CLANG.
NDK_DIR=${LUMA_NDK_DIR:-/workspace/android-ndk-r27c}
PROOT_BASE=${PROOT_R5_BASE:-/workspace/PRoot-R5}
exec "${HOST_CLANG:-/usr/bin/clang}" \
  --target=aarch64-linux-android34 \
  --sysroot="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/sysroot" \
  -resource-dir=$PROOT_BASE/work/toolchain/rt \
  -rtlib=compiler-rt \
  --gcc-toolchain=$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64 \
  "$@"

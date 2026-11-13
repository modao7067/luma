#!/bin/bash
set -euo pipefail
luma_project=$(cd "$(dirname "$0")/.." && pwd)
luma_ndk=${ANDROID_NDK_HOME:-/workspace/android-ndk-r27c}/toolchains/llvm/prebuilt/linux-x86_64/sysroot
luma_libs=${LUMA_ANDROID_LIBS:-/workspace/android-libs}
luma_build="$luma_project/../../artifacts/compute-check"
mkdir -p "$luma_build"
glslc --target-env=vulkan1.1 "$luma_project/src/main/cpp/compute.comp" -o "$luma_build/compute.spv"
python3 - "$luma_build" <<'PY'
import pathlib,struct,sys
p=pathlib.Path(sys.argv[1]); data=(p/'compute.spv').read_bytes()
words=struct.unpack('<'+'I'*(len(data)//4),data)
(p/'compute-shader.h').write_text('static const uint32_t luma_shader[]={'+','.join(hex(w) for w in words)+'};\n')
PY
clang --target=aarch64-linux-android31 --sysroot="$luma_ndk" -fuse-ld=lld \
    -L"$luma_libs" -I"$luma_build" \
    -O2 -fPIE -pie -Wl,-z,max-page-size=16384,-z,noexecstack,-z,relro,-z,now \
    "$luma_project/src/main/cpp/compute-check.c" -lvulkan \
    -o "$luma_project/src/main/jniLibs/arm64-v8a/libluma_compute.so"
clang --target=aarch64-linux-android31 --sysroot="$luma_ndk" -fuse-ld=lld \
    -L"$luma_libs" -O2 -fPIE -pie -Wl,-z,max-page-size=16384,-z,noexecstack,-z,relro,-z,now \
    "$luma_project/src/main/cpp/exec-launcher.c" \
    -o "$luma_project/src/main/jniLibs/arm64-v8a/libluma_exec.so"

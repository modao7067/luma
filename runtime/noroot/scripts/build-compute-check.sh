#!/usr/bin/env bash
set -euo pipefail
luma_project=$(cd "$(dirname "$0")/.." && pwd)
luma_repo=$(cd "$luma_project/../.." && pwd)
ndk_root="${ANDROID_NDK_HOME:-${LUMA_NDK_DIR:-$luma_repo/artifacts/toolchain/android-ndk-r27c}}"
luma_sysroot=""
for host_tag in linux-aarch64 linux-x86_64 darwin-x86_64; do
    if [[ -d "$ndk_root/toolchains/llvm/prebuilt/$host_tag/sysroot" ]]; then
        luma_prebuilt="$ndk_root/toolchains/llvm/prebuilt/$host_tag"
        luma_sysroot="$luma_prebuilt/sysroot"
        break
    fi
done
if [[ -z "$luma_sysroot" ]]; then
    echo "Android NDK sysroot not found under: $ndk_root" >&2
    exit 1
fi
luma_build="$luma_repo/artifacts/compute-check"
mkdir -p "$luma_build" "$luma_project/src/main/jniLibs/arm64-v8a"
glslc --target-env=vulkan1.1 "$luma_project/src/main/cpp/compute.comp" -o "$luma_build/compute.spv"
python3 - "$luma_build" <<'PY'
import pathlib,struct,sys
p=pathlib.Path(sys.argv[1]); data=(p/'compute.spv').read_bytes()
words=struct.unpack('<'+'I'*(len(data)//4),data)
(p/'compute-shader.h').write_text('static const uint32_t luma_shader[]={'+','.join(hex(w) for w in words)+'};\n')
PY
extra_lib_flags=()
if [[ -n "${LUMA_ANDROID_LIBS:-}" ]]; then
    extra_lib_flags=(-L"$LUMA_ANDROID_LIBS")
fi
if "$luma_prebuilt/bin/aarch64-linux-android31-clang" --version >/dev/null 2>&1; then
    cc_cmd=("$luma_prebuilt/bin/aarch64-linux-android31-clang")
    extra_rt=()
elif [[ "$(uname -m)" == "aarch64" ]] && command -v clang >/dev/null 2>&1; then
    builtins=$(find "$luma_prebuilt/lib/clang" -name "libclang_rt.builtins-aarch64-android.a" | head -n 1)
    cc_cmd=(clang --target=aarch64-linux-android31 --sysroot="$luma_sysroot" -fuse-ld=lld -nodefaultlibs)
    extra_rt=("$builtins" -lc)
else
    echo "NDK compiler cannot run on this host; ARM64 hosts require clang and lld" >&2
    exit 1
fi
"${cc_cmd[@]}" ${extra_lib_flags[@]+"${extra_lib_flags[@]}"} -I"$luma_build" \
    -O2 -fPIE -pie -Wl,-z,max-page-size=16384,-z,noexecstack,-z,relro,-z,now \
    "$luma_project/src/main/cpp/compute-check.c" ${extra_rt[@]+"${extra_rt[@]}"} -lvulkan \
    -o "$luma_project/src/main/jniLibs/arm64-v8a/libluma_compute.so"
"${cc_cmd[@]}" ${extra_lib_flags[@]+"${extra_lib_flags[@]}"} \
    -O2 -fPIE -pie -Wl,-z,max-page-size=16384,-z,noexecstack,-z,relro,-z,now \
    "$luma_project/src/main/cpp/exec-launcher.c" ${extra_rt[@]+"${extra_rt[@]}"} \
    -o "$luma_project/src/main/jniLibs/arm64-v8a/libluma_exec.so"

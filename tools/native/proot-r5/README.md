# Audited ARM64 PRoot fchmodat2 build

This is the production source recipe for Luma's R5 fchmodat2 fix on top of
the existing L2 lazy-libandroid adaptation. Component versions and licenses
are unchanged: PRoot 5.1.107.92, talloc 2.4.3, libandroid-shmem 0.7.

`provenance.json` pins the unmodified upstream archives, patch order, shipped
executor/loader hashes, and accepted Android App-domain evidence. The current
build does **not** define USERLAND. Other ABIs and a USERLAND build were not
tested; no new performance or ACK measurements were made.

## Rebuild

The three upstream archives remain in the local, Git-ignored directory
`runtime/noroot/src/main/assets/native-sources/`. Copy or extract them into
this directory's `work/src/`; verify their hashes against `provenance.json`.
The shipped APK source bundle includes this directory as `r5-fchmodat2/` inside
`proot-recipes.tgz` alongside the upstream archives.

On the original Ubuntu ARM64 host, the compiler is clang 21 with the Android
NDK r27c sysroot and compiler-rt. The default tool directory is
`/workspace/PRoot-R5/work/toolchain/bin`; `compiler-wrapper.sh` records the
exact target, sysroot and resource-directory arguments. `ar`, `strip`,
`objcopy`, `objdump`, and `readelf` are LLVM tools. Another host must prepare
the equivalent toolchain and set `PROOT_R5_TOOLS` to its directory; do not
substitute the NDK r29 recipe and claim byte-identical output.

From this directory, after preparing the source archives and toolchain:

```sh
LP_LAZY_LIBANDROID=1 LP_EXTRA_LIBS="-llog" \
  bash recipe/build.sh work/build/FIXC out/FIXC \
  patches/0001-r5-fchmodat2.patch patches/0002-r5-fchmodat2-min.patch
```

Use a fresh build directory/prefix. Do not set EXTRA_CFLAGS, USERLAND,
PROOT_PROFILE, or R2_FORCE_ASHMEM_FALLBACK. The recipe applies the Bionic
header patch before the two PRoot patches, and the shared-memory temporary
directory patch before the L2 lazy-libandroid patch. It does not install
anything into Luma or update the reviewed asset manifest.

Expected output SHA-256:

- `libproot_exec.so`: `82b8beb6cb72e02d0d7829000e3fb2e5b1897be5c58659130eb6f0f89e0ea727`
- `libproot_loader.so`: `5abbee45cc8eb70d7a89b69a680af5dec775d2b945cb2296d6e5bd6de087e2e0`

## Source distribution

From the repository root, `python3 tools/native/proot-r6/package-sources.py`
regenerates `runtime/noroot/src/main/assets/native-sources/proot-recipes.tgz`
deterministically from the tracked `tools/native/proot-r5` and
`tools/native/proot-r6` recipes and patches. Review the asset change before
recording `tools/assets.json`.

Correctness acceptance and integration scope are recorded in
`docs/PROOT-FCHMODAT2-2026-10-30.md` at the repository root. APKs, ELF files,
upstream source archives, caches, and signing material keep their existing
Git exclusions; the patches and recipe are tracked so the change is reviewable.

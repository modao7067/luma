# PRoot R6: native hard-link publication with L2 compatibility

R6 adds `0004-l2-native-hardlinks.patch` to the shipped PRoot 5.1.107.92 + R5
fchmodat2 + L2 lazy-libandroid recipe. It keeps `--link2symlink` enabled.

For ordinary files, the extension first tries a native hard link. On a supporting
filesystem, the published inode survives deletion of its source staging directory.
Existing emulated links remain on the L2 reference-count path. Native-link
unavailability (EPERM, EACCES, EXDEV, EOPNOTSUPP, ENOSYS, EMLINK) retains that old
fallback; definite failures such as EEXIST do not move the source or replace the
destination. The staging-directory case was verified on this device's App data
filesystem, not on every filesystem supported by PRoot.

## Build

Extract the accompanying unmodified `proot.tgz`, `talloc.tgz`, and `shmem.tgz` into
`work/src`. Prepare the host clang 21 / NDK r27c ARM64 API 34 toolchain described
by `compiler-wrapper.sh`; the default is the existing read-only
`/workspace/PRoot-R5/work/toolchain/bin`. Set `PROOT_R5_TOOLS` for another location.

```sh
LP_LAZY_LIBANDROID=1 LP_EXTRA_LIBS=-llog \
  bash recipe/build.sh work/build/R6 out/R6 \
    patches/0001-r5-fchmodat2.patch \
    patches/0002-r5-fchmodat2-min.patch \
    patches/0004-l2-native-hardlinks.patch
```

`SRC_ROOT` can select an already extracted source directory. The recipe applies
the Bionic headers first and keeps the existing shmem/L2 build patches. The
candidate reused an isolated copy of the previously built R5 talloc/shmem static
prefix; no R5 source or build files were modified. USERLAND is not enabled.

## Evidence

`verification/gui-cli-live-20261103/proot-fix/` records the original dangling
publication, candidate atomic cleanup, legacy emulated-link operations, native
inode/link counts, EEXIST preservation, fchmodat2, and the real MiMo DSH roundtrip.
Key native checks passed with Seccomp 0 and Seccomp 2/filter 1. The installed
App-UID PRoot runs used the host bridge's SELinux domain and no App Activity.

`package-sources.py` deterministically builds `proot-recipes.tgz` from the
tracked `tools/native/proot-r5` and `tools/native/proot-r6` recipes and patches.
Binary installation remains the integrator's action.

# Third-party notices

NEXTERM ships third-party software inside its APK. The authoritative notice —
including the GPL-2.0 attribution, the modification notices, and the written
offers of source code — lives with the binaries it describes, so that it is
readable from the installed app and not only from this repository:

- [`app/src/main/assets/licenses/NOTICE.txt`](app/src/main/assets/licenses/NOTICE.txt)
- [`app/src/main/assets/licenses/GPL-2.0.txt`](app/src/main/assets/licenses/GPL-2.0.txt)
- [`app/src/main/assets/licenses/LGPL-3.0.txt`](app/src/main/assets/licenses/LGPL-3.0.txt)
- [`app/src/main/assets/licenses/GPL-3.0.txt`](app/src/main/assets/licenses/GPL-3.0.txt)
- [`app/src/main/assets/licenses/BSD-3-Clause-libandroid-shmem.txt`](app/src/main/assets/licenses/BSD-3-Clause-libandroid-shmem.txt)
- [`app/src/main/assets/licenses/Apache-2.0.txt`](app/src/main/assets/licenses/Apache-2.0.txt)

In the app these are reachable from **Settings → About and licences**.

## Binaries committed to this repository

Everything under `app/src/main/jniLibs/arm64-v8a/` is a prebuilt binary rather
than source. None of it is built here: Google publishes NDK prebuilts for
`linux-x86_64` only, so an aarch64 build host cannot compile them.

| File | Bytes | SHA-256 | Origin | Licence |
| --- | --- | --- | --- | --- |
| `libproot.so` | 239368 | `7da118895e971ea9fba4bb250b28af0f8db2edcbfdbaa8075cc645a0d7cf16fe` | Termux proot 5.1.107.89, **modified** | GPL-2.0 |
| `libproot-loader.so` | 18136 | `44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04` | Termux proot 5.1.107.89 | GPL-2.0 |
| `libproot-loader32.so` | 6244 | `25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34` | Termux proot 5.1.107.89 | GPL-2.0 |
| `libproot-static.so` | 1479432 | `fa10b1a7818c2f5b1dcb5834450570c368c9ecf66d31521509621b95c4538a45` | proot 5.3.0 upstream release | GPL-2.0 |
| `libproot-static-loader.so` | 66832 | `51c3427b112edc70d1979b48209c41f332616758138de3be659cc79e50436450` | proot 5.3.0, copied out of its `.rodata` | GPL-2.0 |
| `libtalloc.so` | 31440 | `34c92182bdbda07e009eaa09afa852c575c566691786ca456b28778222ba0afc` | talloc 2.4.3, **modified** | LGPL-3.0-or-later |
| `libandroid-shmem.so` | 14432 | `84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731` | libandroid-shmem 0.7 | BSD-3-Clause |

Two of them are modified, and both modifications are two-byte-scale ELF string
edits with no change to compiled code. `NOTICE.txt` describes each one exactly,
including the offset, so it can be reproduced from — or reversed back to — the
unmodified upstream file with a hex editor:

- `libproot.so` — DT_NEEDED `libtalloc.so.2` → `libtalloc.so`
- `libtalloc.so` — DT_SONAME `libtalloc.so.2` → `libtalloc.so`

Both exist for the same reason: Android's installer unpacks only files matching
`lib*.so` from `lib/<abi>/`, so a dependency whose name contains `.so.2` cannot
be shipped at all, and neither can the symlink upstream uses to provide the
plain name.

That same rule is why the proot binaries are named `lib*.so`. Android unpacks
only that pattern into `ApplicationInfo.nativeLibraryDir`, and since API 29 that
install-time directory is the sole place an app may execute a binary from —
app-writable storage is mounted W^X. The suffix is a packaging requirement, not
a claim about file type: the upstream build is a static executable and the
Termux build is a PIE.

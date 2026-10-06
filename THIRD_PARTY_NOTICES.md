# Third-party components

This is a personal Android tool. It bundles third-party components separately
from its application source. Runtime package hashes are recorded in
`artifacts/runtime-provenance.json`. Python package license files and Debian
copyright notices remain inside the packaged root filesystem.

| Component | Source | Notice |
| --- | --- | --- |
| PRoot 5.1.107.96 | https://github.com/termux/proot and https://github.com/termux/termux-packages/tree/master/packages/proot | GNU GPL v2, STMicroelectronics and contributors. COPYING is included in APK assets. |
| talloc 2.5.0 | https://talloc.samba.org/ and https://github.com/termux/termux-packages/tree/master/packages/libtalloc | Upstream LGPL v3 license and individual source notices; Termux package declares GPL-3.0. License text is included in APK assets. |
| libandroid-shmem 0.7 | https://github.com/termux/libandroid-shmem | BSD 3-Clause; full copyright/license included in APK assets. |
| Python 3.12 and Debian Bookworm userland | https://github.com/docker-library/python | PSF and individual Debian component licenses; license/copyright files kept in rootfs. |
| maimai-ffi 0.7.1 | https://pypi.org/project/maimai-ffi/0.7.1/ | MIT License, Copyright (c) 2024 Usagi no Niku. License file kept in site-packages. |
| httpx 0.28.1 and dependencies | https://github.com/encode/httpx | Individual licenses retained in installed dist-info directories. |
| cryptography 46.0.3 and dependencies | https://github.com/pyca/cryptography | Individual licenses retained in installed dist-info directories. |

PRoot and talloc ELF needed/soname strings are modified in place from
`libtalloc.so.2` to `libtalloc.so` so Android APK native library extraction can
package the library. The change is reproduced by `scripts/build_runtime.py`.
PRoot executables/loaders are renamed to `libproot*.so` for packaging and executed
as separate processes; application code does not link against PRoot/talloc.

The accompanying source ZIP includes the PRoot, talloc and libandroid-shmem
source archives under `third_party/`, plus the Termux build recipes and
`scripts/build_runtime.py` documenting the packaging modification. The app source
is MIT licensed. You may rebuild, replace the native runtime libraries and sign
the APK with your own Android signing key; no original private key is required.
This tool does not contain source copied from Diving-Fish or maimai.py. It uses
the documented interfaces and the separately distributed maimai-ffi binary.

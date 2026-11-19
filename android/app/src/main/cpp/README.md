# Native code (`libduvo.so`)

`libduvo.so` is Duvo's equivalent of Moonlight Android's `libmoonlight-core.so`: the GameStream
streaming core plus a JNI bridge to `app.duvo.remote.stream.MoonlightBridge`. It is built by the
Android Gradle Plugin with CMake (`CMakeLists.txt`) for `arm64-v8a` and `armeabi-v7a`.

| Path | What | Version | License |
|---|---|---|---|
| `duvo_jni.c` | JNI bridge (callbacks, start/stop, input). Adapted from Moonlight Android `moonlight-core/callbacks.c` + `simplejni.c` | — | GPLv3 (Duvo; portions Moonlight contributors) |
| `moonlight-common-c/` | git submodule, <https://github.com/moonlight-stream/moonlight-common-c> | commit `874ac9548f1bd6f095ef2b435c42cdde460e7821` (the one Moonlight Android 12.2 pins) | GPLv3 |
| `moonlight-common-c/enet/` | nested submodule (cgutman's ENet fork) | `aca87840b57f045a1f7f9299e4b1b9b8e2a5e2f1` | MIT |
| `moonlight-common-c/nanors/` | nested submodule (Reed-Solomon FEC) | `b1e3c22ca0cdc0bb83e3cd6ed1a2fc77869ed99a` | MIT |
| `third_party/openssl/` | prebuilt static `libcrypto.a` per ABI + headers | OpenSSL 4.0.2 (tag `openssl-4.0.2`, `f089acdf4bc7ba94a79f4bf6eb7362c3e7d14aa9`) | Apache-2.0 (`third_party/openssl/LICENSE.txt`) |
| `third_party/opus/` | prebuilt static `libopus.a` per ABI + headers | Opus 1.6.1 (tag `v1.6.1`, `22244de5a79bd1d6d623c32e72bf1954b56235be`) | BSD-3-Clause (`third_party/opus/COPYING`) |

Apache-2.0 and BSD-3-Clause are compatible with GPLv3; the combined app is distributed under GPLv3.

## Getting the sources

```sh
git submodule update --init --recursive
```

On Windows the nested ENet submodule needs long paths: `git config core.longpaths true`.

## Prebuilt OpenSSL / Opus

Same approach as Moonlight Android: moonlight-common-c needs libcrypto (AES-GCM/CBC, RNG) and the
audio path needs libopus; both are linked statically from prebuilt archives, so no Perl/autotools
toolchain is needed to build the app.

The archives are copied byte-for-byte from Moonlight Android 12.2
(<https://github.com/moonlight-stream/moonlight-android>, commit
`b48494cb96bff23d8886c4775cc4f39a1075495d`, `app/src/main/jni/moonlight-core/{openssl,opus}`), which
builds them with <https://github.com/cgutman/moonlight-mobile-deps> (commit `419349a`,
`openssl-android.sh` / `opus-android.sh`; OpenSSL configured `no-shared no-ssl3 no-stdio no-engine no-hw`,
Opus `--disable-shared --enable-static --with-pic`). Only `arm64-v8a` and `armeabi-v7a` are kept.

SHA-256:

```
d593ba8f576db9ff20d058bee7f0f154b4e000b447548533bc8f16eb95290b54  third_party/openssl/arm64-v8a/libcrypto.a
76abf9210207bb74d19c758e898090f5b4c45fdf1c9a9e8613431c7d9455d904  third_party/openssl/armeabi-v7a/libcrypto.a
d6c39b9135017ab76a42e364569f18622e6e3df691ac2d1d9d36ee4a02eb2e13  third_party/opus/arm64-v8a/libopus.a
3925fecb62e4a75095d658bcc1c4e309712a330699825b7790880cf21497719e  third_party/opus/armeabi-v7a/libopus.a
```

To rebuild them from source instead, run moonlight-mobile-deps' scripts with the NDK on Linux and
replace the files above (update this table and the hashes).

## Toolchain

NDK `29.0.14206865`, CMake `3.31.6` (pinned in `app/build.gradle.kts`). The library is linked with
16 KB page alignment (`-z max-page-size=16384`) for Android 15+ devices.

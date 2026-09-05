# AirPlay44

An experimental, local-only AirPlay screen-mirroring receiver for 32-bit
Android 4.4 TVs. It is designed for older Sharp TV hardware with API 19,
`armeabi-v7a`, about 1 GB RAM, and a hardware H.264 decoder.

AirPlay44 has no account, cloud service, ads, analytics, or billing. It is an
independent interoperability project and is not affiliated with Apple.

> **Alpha status:** the APK builds and its protocol helpers, manifest, ABI,
> signature, and native dependencies are tested automatically. Installation,
> discovery, pairing, video, and audio still require validation on the target
> Sharp Android 4.4 TV. Do not describe this release as hardware-verified yet.

## Implemented scope

| Capability | Implementation | Current evidence |
| --- | --- | --- |
| AirPlay/RAOP handshake | RPiPlay over C/C++ and JNI | Native library builds for API 19 |
| Bonjour discovery | JmDNS `_airplay._tcp` + `_raop._tcp` | TXT-record unit tests |
| Screen mirroring | H.264 Annex-B to synchronous `MediaCodec` | Parser tests; hardware pending |
| Mirroring audio | AAC-ELD `MediaCodec` to `AudioTrack` | Builds on API 19; hardware pending |
| Privacy | Local LAN only; no telemetry or account | Manifest and source review |

DRM-protected video such as Apple TV+ is not supported. Some Android 4.4 TV
firmware does not expose an AAC-ELD decoder, in which case video may work
without audio. The current alpha does not require a pairing PIN, so only use it
on a trusted home network.

## Install on a TV

1. Download the APK from the latest GitHub Release.
2. Copy it to a FAT32 USB drive.
3. On the TV, enable **Settings -> Application management -> Unknown sources**.
4. Open the APK from the TV's media/file browser and launch **AirPlay 4.4**.
5. Put the iPhone and TV on the same non-guest LAN.
6. On iPhone, open Control Center -> Screen Mirroring -> **客厅电视 AirPlay**.

Test the iPhone home screen or Photos first. If the receiver is not visible,
disable AP/client isolation on the router. Diagnostic logs:

```sh
adb logcat -s AirPlay44-Service AirPlay44-mDNS AirPlay44-Video \
  AirPlay44-Audio airplay_native rpiplay
```

## Build and test

Requirements:

- JDK 17
- Android SDK 35 and Build Tools 35.0.0
- Android NDK 25.2.9519653 (NDK 26+ cannot target API 19)
- CMake 3.22.1

```sh
./setup-deps.sh
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/25.2.9519653"
./scripts/build-openssl.sh
cp local.properties.example local.properties  # edit sdk.dir
./gradlew testDebugUnitTest lintDebug assembleDebug
./scripts/verify-apk.sh app/build/outputs/apk/debug/app-debug.apk
```

`setup-deps.sh` pins RPiPlay and libplist to exact commits and verifies the
OpenSSL 1.1.1w source checksum. OpenSSL is compiled statically against API 19;
using a recent prebuilt `libcrypto.so` would import functions unavailable on
Android 4.4.

## Architecture

```text
iPhone
  |  mDNS + AirPlay/RAOP
  v
JmDNS + RPiPlay (C/C++)
  |  JNI: Annex-B H.264 / AAC-ELD
  v
MediaCodec + SurfaceView / AudioTrack
```

## Licensing and attribution

AirPlay44 is GPL-3.0-or-later because it statically links RPiPlay. See
[`LICENSE`](LICENSE).

This Android 4.4 port is based on
[`phoria-sam-tg/localair`](https://github.com/phoria-sam-tg/localair) commit
`e6bd503`, with the following upstream components:

| Component | Pinned version | License |
| --- | --- | --- |
| [RPiPlay](https://github.com/FD-/RPiPlay) | `64d0341ed3bef098c940c9ed0675948870a271f9` | GPL-3.0 |
| [libplist](https://github.com/libimobiledevice/libplist) | `32428abacb909988e8e960a8845a6430b17b6a60` | LGPL-2.1-or-later |
| [OpenSSL](https://www.openssl.org/) | 1.1.1w | OpenSSL/SSLeay |

RPiPlay's FairPlay-compatible implementation is reverse engineered for
interoperability. Review the laws that apply where you use or distribute it.

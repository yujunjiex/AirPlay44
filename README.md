# localair

A small, native AirPlay 2 mirroring receiver for Android TV / Android-based
projectors. No ads, no telemetry, no account, ~10 MB APK.

Built because every app-store AirPlay receiver for Android TV is either
adware, paywalled, or both — and the underlying protocol stack
([RPiPlay](https://github.com/FD-/RPiPlay)) has been freely available for
years. localair is a thin Kotlin shell around RPiPlay's RTSP / FairPlay /
mirror code, plus a `MediaCodec` decode path that renders straight to a
`SurfaceView`.

## Status

| Feature                       | State        |
| ----------------------------- | ------------ |
| AirPlay 2 pairing handshake   | ✅ working    |
| FairPlay v2 (`/fp-setup`)     | ✅ working    |
| Mirror SETUP / RECORD         | ✅ working    |
| H.264 video → MediaCodec      | ✅ working    |
| AAC-ELD audio → AudioTrack    | ⏳ stubbed    |
| Tested hardware               | Xiaomi MiProjL1 (Android 9 / armv7) |

## Architecture

```
┌────────────── :app (Kotlin) ──────────────┐
│  MainActivity  → SurfaceView + waiting UI │
│  AirPlayService→ foreground svc, mDNS     │
│  MdnsAdvertiser→ NsdManager _airplay/_raop│
│  VideoDecoder  → MediaCodec → Surface     │
└─────────────────┬─────────────────────────┘
                  │  JNI
┌─────────────────▼─────────────────────────┐
│              :airplay (C/C++)              │
│  jni_bridge.cpp  raop_init / raop_start    │
│  video_sink.cpp  NAL → JNI callback        │
│  dnssd_stub.c    no-op libdns_sd shim      │
│                                            │
│  third_party/RPiPlay/lib  (RTSP, FairPlay, │
│                            mirror buffer)  │
│  third_party/libplist     (in-tree build)  │
│  com.android.ndk.thirdparty:openssl (AAR)  │
└────────────────────────────────────────────┘
```

The Kotlin layer never touches RTSP or crypto — it just owns the Surface
and feeds NAL units it gets from the JNI bridge into MediaCodec. mDNS is
done in Kotlin via `NsdManager`, replacing RPiPlay's libdns_sd-based
`dnssd.c` with a minimal stub (`dnssd_stub.c`) that satisfies the API.

## Build

Requires JDK 21 (Temurin recommended), Android SDK 35, NDK r27, CMake 3.22.

```sh
./setup-deps.sh                           # clone RPiPlay + libplist
cp local.properties.example local.properties   # then edit sdk.dir
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If you don't have a wireless ADB pairing UI on your Android TV
(common on older Xiaomi / FengOS builds), enabling **Developer options →
USB debugging** and rebooting once is usually enough — `adb connect <ip>:5555`
will then accept after you confirm the RSA prompt on the TV.

## Licensing

GPL-3.0-or-later. This project statically links RPiPlay, which is GPL-3.0,
so the combined work inherits GPL-3.0. See `LICENSE`.

Third-party components and their licenses:

| Component           | License           |
| ------------------- | ----------------- |
| RPiPlay             | GPL-3.0           |
| libplist            | LGPL-2.1-or-later |
| OpenSSL (libcrypto) | OpenSSL / Apache-2.0 dual |

## Credits

Standing on the shoulders of:

- [RPiPlay](https://github.com/FD-/RPiPlay) — entire AirPlay 2 / FairPlay 2 stack
- [shairplay](https://github.com/juhovh/shairplay) — RAOP groundwork RPiPlay forked from
- [libplist](https://github.com/libimobiledevice/libplist) — Apple plist parsing

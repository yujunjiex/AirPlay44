# AirPlay44 v0.1.0-alpha

First experimental build targeting 32-bit Android 4.4 TVs (`minSdk 19`,
`armeabi-v7a`).

## Included

- Local AirPlay/RAOP receiver based on pinned RPiPlay sources.
- Android 4.4-compatible JmDNS discovery with AirPlay and RAOP TXT records.
- Synchronous H.264 `MediaCodec` rendering for pre-API-21 devices.
- Synchronous AAC-ELD `MediaCodec` and legacy `AudioTrack` output.
- API-19 static OpenSSL build, avoiding newer Android runtime imports.
- No account, cloud service, telemetry, ads, or billing.

## Verification

- JVM protocol helper tests pass.
- Android Lint passes.
- Debug and signed release APKs build successfully.
- Release APK contract verifies API 19, `armeabi-v7a`, v1/v2 signatures, and
  legacy-safe native dependencies.

## Important alpha limitations

This release has **not yet been tested on the target Sharp Android 4.4 TV**.
Installation, Bonjour discovery, AirPlay pairing, video decoding, and audio
decoding remain hardware-validation gates. DRM-protected video is unsupported.
There is no pairing PIN in this alpha, so use it only on a trusted home LAN.

Start testing with the iPhone home screen or Photos. Please attach the exact TV
message or filtered ADB log when reporting installation, discovery, black-screen,
or audio issues.

## Release APK

`AirPlay44-v0.1.0-alpha.apk`

SHA-256: `3987613fbb627163d0f9377a6e4a418c161ea525c1b15e4d698be8cf2e84e9c9`

# AirPlay44 v0.1.1-rc1

Android 4.4 real-device release candidate addressing aspect-ratio distortion and unstable frame pacing reported on a Sharp TV with an iPhone 13 Pro.

## Fixed

- Uses the source and encoded dimensions reported by the AirPlay mirror stream instead of always configuring video as 1920x1080.
- Preserves the source aspect ratio with fit-center rendering: wide landscape content gets top/bottom bars and portrait content gets side bars.
- Maps AirPlay presentation timestamps to Android's monotonic clock and paces decoded frames instead of rendering decoder bursts immediately.
- Clears excessive decoder backlog and waits for the next IDR frame before resynchronizing, reducing progressive latency and reference-frame corruption.
- Reconfigures the decoder and surface layout when the sender rotates or changes stream geometry.

## Verification

- 13 JVM tests pass, including iPhone-style portrait and 19.5:9 landscape geometry cases and presentation-clock behavior.
- Android Lint passes.
- Clean-source dependency restoration and build pass.
- Debug and signed release APK contracts verify API 19, `armeabi-v7a`, v1/v2 signatures, and legacy-safe native dependencies.

## Real-device status

The original v0.1.0-alpha successfully connected and displayed AirPlay video on the target TV. This release candidate incorporates the resulting fixes, but the new aspect-ratio and pacing behavior still needs confirmation on that hardware.

The APK uses the same signing certificate as v0.1.0-alpha and has version code 2, so it can be installed as an update without uninstalling the previous version.

## APK

`AirPlay44-v0.1.1-rc1.apk`

SHA-256: `e94d9cc9cb1e214c6347a24374bdfdc1ff1db90e50a7a16a1a17457bc2fda1ef`

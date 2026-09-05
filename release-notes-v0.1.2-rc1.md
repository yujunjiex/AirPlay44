# AirPlay44 v0.1.2-rc1

Android 4.4 release candidate focused on session stability and full-screen
display when mirroring from iPhone 16 and iPhone 16 Pro.

## Changes

- Keep the mirror session alive while iOS closes an auxiliary AirPlay
  connection, instead of clearing video while audio continues.
- Keep the last decoded frame visible when the old dual-core TV decoder falls
  behind, then recover cleanly at the next H.264 IDR frame.
- Allow pause/resume without treating an auxiliary connection close as the end
  of the whole mirroring session.
- Fill a 16:9 TV in landscape without stretching. Wider iPhone screens are
  cropped equally on the left and right; portrait mirroring remains fully
  visible.
- Add geometry regression coverage for the native display sizes of iPhone 16
  (2556 x 1179) and iPhone 16 Pro (2622 x 1206).

## Compatibility and limitations

- Android 4.4 / API 19, `armeabi-v7a`.
- iPhone 16 and iPhone 16 Pro are supported through dynamic AirPlay stream
  dimensions; behavior is not tied to a hard-coded phone model.
- Landscape fill intentionally crops the extreme left and right edges on
  screens wider than 16:9. It does not distort the image.
- DRM-protected playback such as Apple TV+ is not supported.
- This remains a release candidate. The fixes are covered by automated tests
  and APK validation, but should still be confirmed on the target Sharp TV.

## Upgrade

Install this APK over v0.1.1-rc1. It uses the same application ID and signing
certificate, so uninstalling the previous version is not required.

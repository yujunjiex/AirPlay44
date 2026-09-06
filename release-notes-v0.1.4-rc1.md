# AirPlay44 v0.1.4-rc1

Hotfix release candidate for the startup black-screen and unrecoverable receiver
state introduced by v0.1.3-rc1.

## Changes

- Keep the effective low-latency presentation changes from v0.1.3-rc1: no
  60 ms startup buffer, a 20 ms scheduling cap, immediate catch-up rendering,
  single-pass H.264 inspection, and no per-frame native debug logging.
- Raise the decoder queue safety limit from 12 to 45 frames. When saturated,
  keep the existing decodable dependency chain on screen and wait for an IDR
  instead of clearing a slow decoder's startup frames.
- Make automatic MediaCodec recovery conservative: it only applies after video
  has rendered successfully and then produces no output for eight seconds while
  new video continues to arrive.
- Remove the experimental RPiPlay mirror-socket reuse patch. It could leave the
  receiver in a stale native session after the data connection closed.
- Recycle the native AirPlay receiver after a completed session so stale
  sockets and connection counters do not affect the next connection.
- Press the TV remote's OK/Enter key inside AirPlay44 to restart the receiver,
  clear video/audio state, and re-register mDNS without rebooting the TV.

## Compatibility and limitations

- Android 4.4 / API 19, `armeabi-v7a`.
- iPhone 16 and iPhone 16 Pro use dynamic AirPlay stream dimensions.
- Landscape display remains aspect-preserving with symmetric side cropping for
  content wider than the TV.
- DRM-protected playback such as Apple TV+ is not supported.
- This is a release candidate and still needs target-TV endurance testing.

## Upgrade

Install this APK over v0.1.3-rc1. It uses the same application ID and signing
certificate, so uninstalling the previous version is not required.

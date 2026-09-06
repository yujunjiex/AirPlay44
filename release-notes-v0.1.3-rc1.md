# AirPlay44 v0.1.3-rc1

Android 4.4 release candidate focused on lower mirroring latency, correct
landscape proportions, long-session recovery, and transient disconnects.

## Changes

- Replace the multi-second 90-frame video backlog with an adaptive low-latency
  queue capped at 12 frames. Backlogged output is rendered immediately instead
  of waiting on every stale presentation timestamp.
- Cap local presentation buffering at 20 ms and remove the previous 60 ms
  startup delay.
- Inspect every H.264 access unit once instead of three times and disable
  per-frame native debug logging to reduce CPU load on the dual-core A53 TV.
- Use the encoded/visible video size as the display aspect ratio. This avoids
  stretching when iOS source-orientation metadata is temporarily stale during
  full-screen or rotation transitions.
- Prefer an Android decoder's explicit visible crop while ignoring padded coded
  dimensions such as 1920 x 1088 when no crop is supplied.
- Detect a MediaCodec output stall and restart cleanly at the next H.264 IDR
  frame while keeping the last picture visible.
- Add a three-second session-end grace period so short iOS RTSP transitions can
  reconnect without clearing the picture.
- Keep the TV CPU and Wi-Fi awake in high-performance mode, monitor the native
  receiver, and restart it if the server exits unexpectedly.
- Allow the RPiPlay mirror data listener to accept a replacement TCP stream
  after a transient socket close; tighten TCP keepalive detection.
- Bound the audio queue to avoid seconds of accumulated sound latency.

## Compatibility and limitations

- Android 4.4 / API 19, `armeabi-v7a`.
- iPhone 16 and iPhone 16 Pro use dynamic AirPlay stream dimensions; no phone
  model is hard-coded.
- Landscape fill remains aspect-preserving. Content wider than the 16:9 TV is
  cropped equally on the left and right rather than stretched.
- DRM-protected playback such as Apple TV+ is not supported.
- This is a release candidate. Automated tests and APK checks cannot replace a
  long playback test on the target Sharp TV.

## Upgrade

Install this APK over v0.1.2-rc1. It uses the same application ID and signing
certificate, so uninstalling the previous version is not required.

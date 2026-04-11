#!/bin/sh
# Clones the third-party C libraries that the :airplay native module
# compiles in-tree. Re-runnable; skips anything already present.
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$ROOT/third_party"

clone_if_missing() {
    dest="$1"; url="$2"
    if [ -d "$dest" ]; then
        echo "skip $dest (already cloned)"
    else
        git clone --depth 1 "$url" "$dest"
    fi
}

clone_if_missing "$ROOT/third_party/RPiPlay" https://github.com/FD-/RPiPlay
clone_if_missing "$ROOT/third_party/libplist" https://github.com/libimobiledevice/libplist

echo
echo "deps ready. next: ./gradlew :app:assembleDebug"

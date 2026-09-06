#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
THIRD_PARTY="$ROOT/third_party"
RPIPLAY_REV="64d0341ed3bef098c940c9ed0675948870a271f9"
LIBPLIST_REV="32428abacb909988e8e960a8845a6430b17b6a60"
OPENSSL_VERSION="1.1.1w"
OPENSSL_SHA256="cf3098950cb4d853ad95c0841f1f9c6d3dc102dccfcacd521d93925208b76ac8"

mkdir -p "$THIRD_PARTY"

checkout_pinned() {
    destination="$1"
    repository="$2"
    revision="$3"
    if [ -d "$destination/.git" ]; then
        actual=$(git -C "$destination" rev-parse HEAD)
        if [ "$actual" != "$revision" ]; then
            echo "$destination is at $actual, expected $revision" >&2
            exit 1
        fi
        return
    fi
    git init -q "$destination"
    git -C "$destination" remote add origin "$repository"
    git -C "$destination" fetch -q --depth 1 origin "$revision"
    git -C "$destination" checkout -q --detach FETCH_HEAD
}

checkout_pinned "$THIRD_PARTY/RPiPlay" https://github.com/FD-/RPiPlay.git "$RPIPLAY_REV"
checkout_pinned "$THIRD_PARTY/libplist" https://github.com/libimobiledevice/libplist.git "$LIBPLIST_REV"

for RPIPLAY_PATCH in \
    "$ROOT/patches/rpiplay-video-dimensions.patch" \
    "$ROOT/patches/rpiplay-mirror-reconnect.patch"
do
    if git -C "$THIRD_PARTY/RPiPlay" apply --reverse --check "$RPIPLAY_PATCH" 2>/dev/null; then
        : # Patch is already applied.
    elif git -C "$THIRD_PARTY/RPiPlay" apply --check "$RPIPLAY_PATCH"; then
        git -C "$THIRD_PARTY/RPiPlay" apply "$RPIPLAY_PATCH"
    else
        echo "RPiPlay patch does not apply cleanly: $RPIPLAY_PATCH" >&2
        exit 1
    fi
done

OPENSSL_ROOT="$THIRD_PARTY/openssl-$OPENSSL_VERSION"
if [ ! -d "$OPENSSL_ROOT" ]; then
    archive="$THIRD_PARTY/openssl-$OPENSSL_VERSION.tar.gz"
    curl -L --fail --retry 3 -o "$archive" \
        "https://www.openssl.org/source/old/1.1.1/openssl-$OPENSSL_VERSION.tar.gz"
    if command -v sha256sum >/dev/null 2>&1; then
        actual=$(sha256sum "$archive" | awk '{print $1}')
    else
        actual=$(shasum -a 256 "$archive" | awk '{print $1}')
    fi
    if [ "$actual" != "$OPENSSL_SHA256" ]; then
        echo "OpenSSL checksum mismatch: $actual" >&2
        exit 1
    fi
    tar -xzf "$archive" -C "$THIRD_PARTY"
fi

echo "Pinned dependencies are ready."

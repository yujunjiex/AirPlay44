#!/bin/sh
set -eu

APK=${1:?Usage: verify-apk.sh path/to.apk}
SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
: "${SDK_ROOT:?Set ANDROID_SDK_ROOT or ANDROID_HOME}"

BUILD_TOOLS=${BUILD_TOOLS_VERSION:-35.0.0}
AAPT="$SDK_ROOT/build-tools/$BUILD_TOOLS/aapt"
APKSIGNER="$SDK_ROOT/build-tools/$BUILD_TOOLS/apksigner"
READELF="$SDK_ROOT/ndk/25.2.9519653/toolchains/llvm/prebuilt"

case "$(uname -s)" in
    Darwin) READELF="$READELF/darwin-x86_64/bin/llvm-readelf" ;;
    Linux) READELF="$READELF/linux-x86_64/bin/llvm-readelf" ;;
    *) echo "Unsupported verification host" >&2; exit 1 ;;
esac

badging=$($AAPT dump badging "$APK")
echo "$badging" | grep -q "sdkVersion:'19'"
echo "$badging" | grep -q "targetSdkVersion:'28'"
echo "$badging" | grep -q "package: name='io.github.yujunjiex.airplay44'"

libraries=$(unzip -Z1 "$APK" | grep '^lib/' | sort)
expected=$(printf '%s\n' \
    'lib/armeabi-v7a/libairplay_native.so' \
    'lib/armeabi-v7a/libc++_shared.so')
if [ "$libraries" != "$expected" ]; then
    echo "Unexpected native library set:" >&2
    echo "$libraries" >&2
    exit 1
fi

signature=$($APKSIGNER verify --verbose "$APK")
echo "$signature" | grep -q 'Verified using v1 scheme (JAR signing): true'
echo "$signature" | grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true'

temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT
unzip -q "$APK" 'lib/armeabi-v7a/libairplay_native.so' -d "$temporary"
native="$temporary/lib/armeabi-v7a/libairplay_native.so"
needed=$($READELF -d "$native" | grep NEEDED)
for library in liblog.so libandroid.so libm.so libc++_shared.so libdl.so libc.so; do
    echo "$needed" | grep -q "\[$library\]"
done
if echo "$needed" | grep -q '\[libcrypto.so\]'; then
    echo "libcrypto must be statically linked for Android 4.4" >&2
    exit 1
fi
if $READELF --dyn-syms "$native" | grep ' UND ' | grep -Eq 'getrandom|pthread_getname_np|dl_iterate_phdr'; then
    echo "Native library imports a post-API-19 symbol" >&2
    exit 1
fi

echo "APK contract verified: API 19, armeabi-v7a, v1/v2 signed, legacy-safe native dependencies."

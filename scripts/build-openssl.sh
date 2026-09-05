#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
OPENSSL_ROOT="$ROOT/third_party/openssl-1.1.1w"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK 25.2.9519653}"

case "$(uname -s)" in
    Darwin) NDK_HOST="darwin-x86_64" ;;
    Linux) NDK_HOST="linux-x86_64" ;;
    *) echo "Unsupported build host: $(uname -s)" >&2; exit 1 ;;
esac

export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST/bin:$PATH"
cd "$OPENSSL_ROOT"

if [ ! -f Makefile ]; then
    ./Configure android-arm -D__ANDROID_API__=19 no-shared no-tests no-ui-console
fi

make -j4 build_libs
echo "Built $OPENSSL_ROOT/libcrypto.a for armeabi-v7a/API 19"

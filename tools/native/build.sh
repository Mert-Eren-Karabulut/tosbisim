#!/usr/bin/env bash
# Build the native probe helpers for arm64 Android with the NDK. Output: tools/native/out/arm64-v8a/
set -eu
NDK="${ANDROID_NDK_HOME:-$(ls -d "$HOME"/Library/Android/sdk/ndk/* | sort -V | tail -1)}"
CC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android29-clang"
cd "$(dirname "$0")"
mkdir -p out/arm64-v8a
for src in *.c; do
  "$CC" -O2 -Wall -Wextra -o "out/arm64-v8a/${src%.c}" "$src"
  echo "built out/arm64-v8a/${src%.c}"
done

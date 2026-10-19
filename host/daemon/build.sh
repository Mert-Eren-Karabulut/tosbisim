#!/usr/bin/env bash
# Build simrelayd for arm64 Android with the NDK + CMake. Output: host/daemon/out/arm64-v8a/simrelayd
# Fetches pinned libopus + tinyalsa sources (deps.sh) and compiles them in.
set -eu
cd "$(dirname "$0")"
./deps.sh
NDK="${ANDROID_NDK_HOME:-$(ls -d "$HOME"/Library/Android/sdk/ndk/* | sort -V | tail -1)}"
CMAKE="$(ls -d "$HOME"/Library/Android/sdk/cmake/*/bin/cmake 2>/dev/null | sort -V | tail -1 || true)"
CMAKE="${CMAKE:-cmake}"
NINJA="$(dirname "$CMAKE")/ninja"
[ -x "$NINJA" ] || NINJA=ninja
"$CMAKE" -S . -B build/arm64-v8a -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=29 \
  -DCMAKE_MAKE_PROGRAM="$NINJA" >/dev/null
out=$("$CMAKE" --build build/arm64-v8a --target simrelayd 2>&1) || { echo "$out" | grep -E "error|Error" ; echo "BUILD FAILED"; exit 1; }
mkdir -p out/arm64-v8a && cp build/arm64-v8a/simrelayd out/arm64-v8a/simrelayd
# Every loadable segment 16 KB-aligned (Android 15+ 16 KB-page kernels won't run anything less)
READELF="$NDK/toolchains/llvm/prebuilt/$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)/bin/llvm-readelf"
for a in $("$READELF" -lW out/arm64-v8a/simrelayd | awk '$1 == "LOAD" { print $NF }'); do
  [ $((a)) -ge 16384 ] || { echo "simrelayd: a segment is aligned to $a, below 16 KB"; exit 1; }
done
echo "built out/arm64-v8a/simrelayd"

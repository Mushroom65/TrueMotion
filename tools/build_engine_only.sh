#!/bin/sh
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_NDK:?Set ANDROID_NDK to Android NDK 30.0.16248370}"
SKIA_DIR="${SKIA_DIR:-$ROOT/third_party/skia}"
OUT="${AM_NATIVE_OUT:-$ROOT/.native-engine-build}"
test "$(git -C "$SKIA_DIR" rev-parse HEAD)" = 95ee33d7ec7791ab9c071675710a14fe6408ae4f
cmake -S "$ROOT/app/src/main/cpp" -B "$OUT" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DAM_COMPAT=ON -DAM_ENGINE_ONLY=ON -DSKIA_DIR="$SKIA_DIR"
cmake --build "$OUT" --target amengine -j "${AM_BUILD_JOBS:-3}"
"$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$OUT/libamengine.so"
printf '%s\n' "$OUT/libamengine.so"

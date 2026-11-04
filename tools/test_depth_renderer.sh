#!/bin/sh
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"${CXX:-c++}" -std=c++20 -O2 -Wall -Wextra -Werror -I "$ROOT/app/src/main/cpp" "$ROOT/tests/native/depth_buffer_test.cpp" -o "$OUT/depth-test"
"$OUT/depth-test"

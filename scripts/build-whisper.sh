#!/usr/bin/env bash
# submodule の whisper.cpp を Linux 用共有ライブラリとしてビルドし、natives/ へ置く。

set -euo pipefail

cd "$(dirname "$0")/.." || exit 1

SOURCE_DIR="src/main/native/whisper"
OUTPUT_DIR="natives"
CONFIGURATION="${1:-Release}"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "このスクリプトは Linux 用です: $(uname -s)" >&2
  exit 1
fi

case "$(uname -m)" in
  x86_64|amd64)
    ARCH="x64"
    ;;
  aarch64|arm64)
    ARCH="arm64"
    ;;
  *)
    echo "未対応の CPU アーキテクチャです: $(uname -m)" >&2
    exit 1
    ;;
esac

BUILD_DIR="build/whisper-cpp-linux-${ARCH}"

if ! command -v cmake >/dev/null 2>&1; then
  echo "cmake が見つかりません。Ubuntu では次を実行してください:" >&2
  echo "  sudo apt install cmake build-essential" >&2
  exit 1
fi

if [[ ! -f "$SOURCE_DIR/CMakeLists.txt" ]]; then
  echo "whisper.cpp の submodule がありません: $SOURCE_DIR" >&2
  echo "  git submodule update --init --recursive" >&2
  exit 1
fi

cmake -S "$SOURCE_DIR" -B "$BUILD_DIR" \
  -DCMAKE_BUILD_TYPE="$CONFIGURATION" \
  -DCMAKE_BUILD_RPATH_USE_ORIGIN=ON \
  '-DCMAKE_BUILD_RPATH=$ORIGIN' \
  '-DCMAKE_INSTALL_RPATH=$ORIGIN' \
  -DBUILD_SHARED_LIBS=ON \
  -DWHISPER_BUILD_TESTS=OFF \
  -DWHISPER_BUILD_IS_DEV=OFF \
  -DWHISPER_BUILD_EXAMPLES=OFF \
  -DWHISPER_BUILD_SERVER=OFF

cmake --build "$BUILD_DIR" --config "$CONFIGURATION" --parallel "$(nproc)"

LIBRARY_LINKS=(
  "$BUILD_DIR/bin/libggml-base.so"
  "$BUILD_DIR/bin/libggml-cpu.so"
  "$BUILD_DIR/bin/libggml.so"
  "$BUILD_DIR/bin/libparakeet.so"
  "$BUILD_DIR/bin/libwhisper.so"
)

for library in "${LIBRARY_LINKS[@]}"; do
  if [[ ! -f "$library" ]]; then
    echo "必要な共有ライブラリがありません: $library" >&2
    exit 1
  fi
done

mkdir -p "$OUTPUT_DIR"
find "$OUTPUT_DIR" -maxdepth 1 -type f -name '*.so*' -delete

WHISPER_LIBRARY=""
for library in "${LIBRARY_LINKS[@]}"; do
  # 依存側が要求する SONAME（例: libggml.so.0）だけを通常ファイルとしてコピーする。
  # .so / .so.0 / .so.0.20.2 を全部入れると、jar 展開後は別 inode になり重複ロードされる。
  SONAME="$(readelf -d "$library" | sed -n 's/.*Library soname: \[\(.*\)\]/\1/p')"
  if [[ -z "$SONAME" ]]; then
    echo "SONAME を取得できません: $library" >&2
    exit 1
  fi
  cp -Lf "$library" "$OUTPUT_DIR/$SONAME"
  if [[ "$SONAME" == libwhisper.so.* ]]; then
    WHISPER_LIBRARY="$OUTPUT_DIR/$SONAME"
  fi
done

if [[ -z "$WHISPER_LIBRARY" || ! -f "$WHISPER_LIBRARY" ]]; then
  echo "libwhisper の SONAME ファイルが作成されませんでした: $OUTPUT_DIR" >&2
  exit 1
fi

MISSING_DEPENDENCIES="$(ldd "$WHISPER_LIBRARY" | sed -n '/not found/p')"
if [[ -n "$MISSING_DEPENDENCIES" ]]; then
  echo "libwhisper.so の依存ライブラリが不足しています:" >&2
  echo "$MISSING_DEPENDENCIES" >&2
  exit 1
fi

echo
echo "$OUTPUT_DIR に Linux ${ARCH} 用ライブラリを配置しました:"
find "$OUTPUT_DIR" -maxdepth 1 -type f -name '*.so*' -printf '  %f (%s bytes)\n' | sort

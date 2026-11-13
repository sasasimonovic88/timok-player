#!/bin/bash
#
# Rebuilds ffmpeg/src/main/jniLibs/<abi>/libffmpegJNI.so: an audio-only, LGPL build of FFmpeg
# statically linked into the Media3 FFmpeg JNI glue (ffmpeg_jni.cc, CMakeLists.txt; Apache 2.0).
#
# Only needed when changing FFmpeg version, decoders or ABIs. Normal app builds use the
# prebuilt .so files and need no NDK.
#
# Usage: ffmpeg/native/build_native.sh [work_dir]
# Env:   ANDROID_SDK (default ~/Library/Android/sdk), NDK_VERSION, CMAKE_VERSION
#
# License: FFmpeg is configured WITHOUT --enable-gpl / --enable-nonfree, so the result is LGPL 2.1+.
set -euo pipefail

FFMPEG_TAG="n6.0.1"
# Decoders for broadcast audio that Android boxes often lack (all LGPL in FFmpeg).
DECODERS=(mp2 mp3 ac3 eac3 dca truehd)
ABIS=(armeabi-v7a arm64-v8a x86_64)
MIN_API=23

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE_DIR="$(dirname "$SCRIPT_DIR")"
WORK_DIR="${1:-$MODULE_DIR/build/native-work}"
SDK="${ANDROID_SDK:-$HOME/Library/Android/sdk}"
NDK="$SDK/ndk/${NDK_VERSION:-$(ls "$SDK/ndk" | sort -V | tail -1)}"
CMAKE_DIR="$SDK/cmake/${CMAKE_VERSION:-$(ls "$SDK/cmake" | sort -V | tail -1)}"
case "$(uname -s)" in
    Darwin) HOST=darwin-x86_64 ;;
    *) HOST=linux-x86_64 ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
JOBS="$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)"

echo "NDK: $NDK"
echo "CMake: $CMAKE_DIR"
mkdir -p "$WORK_DIR"
FFMPEG_DIR="$WORK_DIR/ffmpeg"
if [[ ! -d "$FFMPEG_DIR" ]]; then
    git clone --depth 1 --branch "$FFMPEG_TAG" https://github.com/FFmpeg/FFmpeg.git "$FFMPEG_DIR"
fi

COMMON=(
    --target-os=android --enable-static --disable-shared --disable-doc --disable-programs
    --disable-everything --disable-avdevice --disable-avformat --disable-swscale
    --disable-postproc --disable-avfilter --disable-symver --enable-swresample
    --disable-v4l2-m2m --disable-vulkan --disable-network --disable-autodetect
)
for d in "${DECODERS[@]}"; do COMMON+=("--enable-decoder=$d"); done

for abi in "${ABIS[@]}"; do
    case "$abi" in
        armeabi-v7a) TRIPLE=armv7a-linux-androideabi; EXTRA=(--arch=arm --cpu=armv7-a --extra-cflags="-march=armv7-a -mfloat-abi=softfp" --extra-ldflags="-Wl,--fix-cortex-a8") ;;
        arm64-v8a) TRIPLE=aarch64-linux-android; EXTRA=(--arch=aarch64 --cpu=armv8-a) ;;
        x86_64) TRIPLE=x86_64-linux-android; EXTRA=(--arch=x86_64 --cpu=x86-64 --disable-asm) ;;
    esac
    echo "=== FFmpeg $abi"
    (
        cd "$FFMPEG_DIR"
        ./configure --libdir="android-libs/$abi" \
            --cross-prefix="$TOOLCHAIN/$TRIPLE$MIN_API-" \
            --cc="$TOOLCHAIN/$TRIPLE$MIN_API-clang" \
            --nm="$TOOLCHAIN/llvm-nm" --ar="$TOOLCHAIN/llvm-ar" \
            --ranlib="$TOOLCHAIN/llvm-ranlib" --strip="$TOOLCHAIN/llvm-strip" \
            "${EXTRA[@]}" "${COMMON[@]}" > "$WORK_DIR/configure-$abi.log"
        if ! grep -q "^License: LGPL" "$WORK_DIR/configure-$abi.log"; then
            echo "Refusing: FFmpeg configuration is not LGPL"; exit 1
        fi
        make -j"$JOBS" > "$WORK_DIR/make-$abi.log"
        make install-libs > /dev/null
        make clean > /dev/null
    )

    echo "=== JNI $abi"
    BUILD="$WORK_DIR/jni-$abi"
    rm -rf "$BUILD"
    # CMakeLists.txt expects FFmpeg next to it as ./ffmpeg.
    STAGE="$WORK_DIR/jni-src"
    mkdir -p "$STAGE"
    cp "$SCRIPT_DIR/CMakeLists.txt" "$SCRIPT_DIR/ffmpeg_jni.cc" "$STAGE/"
    ln -sfn "$FFMPEG_DIR" "$STAGE/ffmpeg"
    "$CMAKE_DIR/bin/cmake" -S "$STAGE" -B "$BUILD" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$CMAKE_DIR/bin/ninja" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$MIN_API" \
        -DCMAKE_BUILD_TYPE=Release > /dev/null
    "$CMAKE_DIR/bin/cmake" --build "$BUILD" > /dev/null
    OUT="$MODULE_DIR/src/main/jniLibs/$abi"
    mkdir -p "$OUT"
    "$TOOLCHAIN/llvm-strip" --strip-unneeded -o "$OUT/libffmpegJNI.so" "$BUILD/libffmpegJNI.so"
    ls -l "$OUT/libffmpegJNI.so"
done

grep -h "^License:" "$WORK_DIR"/configure-*.log | sort -u
echo "Done. FFmpeg $FFMPEG_TAG, decoders: ${DECODERS[*]}"

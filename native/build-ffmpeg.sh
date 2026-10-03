#!/usr/bin/env bash
# Rebuilds the static FFmpeg libraries used by libfpnative from the official source tarball.
#
#   native/build-ffmpeg.sh [abi...]      default ABIs: arm64-v8a x86_64
#
# Inputs (pinned): FFmpeg 7.1.5 "Péter" (https://ffmpeg.org/releases/ffmpeg-7.1.5.tar.xz,
# sha256 de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f), Android NDK 28.2.13676358 (r28c),
# API level 26. Output: native/out/<abi>/{include,lib}. LGPL-2.1+ build: no --enable-gpl / --enable-nonfree.
# Only the decoders/demuxers the app needs are enabled; the DST decoder is intentionally NOT built (DST-compressed
# DSDIFF must be reported as unsupported). Linker flag max-page-size=16384 keeps 16 KB page compatibility.
set -euo pipefail
cd "$(dirname "$0")"
HERE="$(pwd)"
FFVER=7.1.5
FFSHA=de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f
NDK_VERSION=28.2.13676358
API=26
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
NDK="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/$NDK_VERSION}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
ABIS=("$@")
[ ${#ABIS[@]} -eq 0 ] && ABIS=(arm64-v8a x86_64)

[ -x "$TC/bin/clang" ] || { echo "NDK $NDK_VERSION not found at $NDK" >&2; exit 1; }
# FP_NATIVE_WORK moves the (large, many-file) build tree off slow mounts, e.g. ~/.cache/fp-native in WSL.
WORK="${FP_NATIVE_WORK:-$HERE}"
mkdir -p "$WORK/src-cache" "$WORK/build" out
TARBALL="$WORK/src-cache/ffmpeg-$FFVER.tar.xz"
if [ ! -f "$TARBALL" ]; then
  curl -fsSL -o "$TARBALL.part" "https://ffmpeg.org/releases/ffmpeg-$FFVER.tar.xz"
  mv "$TARBALL.part" "$TARBALL"
fi
echo "$FFSHA  $TARBALL" | sha256sum -c -

COMMON=(
  --target-os=android --enable-cross-compile --enable-pic --enable-static --disable-shared
  --disable-everything --disable-autodetect --disable-programs --disable-doc --disable-network
  --disable-avdevice --disable-avfilter --disable-swscale --disable-postproc
  --enable-avformat --enable-avcodec --enable-swresample
  --enable-decoder=alac,wmav1,wmav2,wmapro,wmalossless,ape,dsd_lsbf,dsd_msbf,dsd_lsbf_planar,dsd_msbf_planar
  --enable-demuxer=mov,asf,ape,dsf,iff
  --ar="$TC/bin/llvm-ar" --nm="$TC/bin/llvm-nm" --ranlib="$TC/bin/llvm-ranlib" --strip="$TC/bin/llvm-strip"
  --sysroot="$TC/sysroot"
)

for ABI in "${ABIS[@]}"; do
  case "$ABI" in
    arm64-v8a) ARCH=aarch64; CPU=armv8-a; TRIPLE=aarch64-linux-android; EXTRA=() ;;
    x86_64) ARCH=x86_64; CPU=x86-64; TRIPLE=x86_64-linux-android; EXTRA=(--disable-x86asm) ;;
    *) echo "unsupported ABI $ABI" >&2; exit 1 ;;
  esac
  B="$WORK/build/$ABI"
  rm -rf "$B" "out/$ABI"
  mkdir -p "$B"
  tar -xJf "$TARBALL" -C "$B" --strip-components=1
  (
    cd "$B"
    ./configure --prefix="$HERE/out/$ABI" --arch="$ARCH" --cpu="$CPU" \
      --cc="$TC/bin/${TRIPLE}${API}-clang" --cxx="$TC/bin/${TRIPLE}${API}-clang++" \
      --extra-cflags="-O2 -fPIC -DANDROID" --extra-ldflags="-Wl,-z,max-page-size=16384" \
      "${COMMON[@]}" "${EXTRA[@]}" > "$HERE/out/configure-$ABI.log" 2>&1 \
      || { tail -40 "$HERE/out/configure-$ABI.log"; exit 1; }
    make -j"$(nproc)" > "$HERE/out/make-$ABI.log" 2>&1 || { tail -40 "$HERE/out/make-$ABI.log"; exit 1; }
    make install > /dev/null
  )
  cp "$B/ffbuild/config.mak" "out/$ABI/config.mak"
  echo "built FFmpeg $FFVER for $ABI"
done

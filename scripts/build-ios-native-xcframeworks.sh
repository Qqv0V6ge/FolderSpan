#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

MIN_IOS_VERSION="${MIN_IOS_VERSION:-14.0}"
WORK_DIR="${WORK_DIR:-$HOME/.cache/folderspan-ios-native}"
OUT_DIR="${OUT_DIR:-$HOME/ios-native-xcframeworks}"
JOBS="${JOBS:-$(sysctl -n hw.ncpu)}"
SKIP_DOWNLOAD="${SKIP_DOWNLOAD:-0}"

DOWNLOAD_RETRY="${DOWNLOAD_RETRY:-1}"
DOWNLOAD_CONNECT_TIMEOUT="${DOWNLOAD_CONNECT_TIMEOUT:-10}"
DOWNLOAD_MAX_TIME="${DOWNLOAD_MAX_TIME:-180}"

OPENSSL_VERSION="${OPENSSL_VERSION:-3.3.2}"
LIBSSH2_VERSION="${LIBSSH2_VERSION:-1.11.1}"
CURL_VERSION="${CURL_VERSION:-8.12.1}"
LIBSMB2_VERSION="${LIBSMB2_VERSION:-6.2}"

DOWNLOAD_DIR="$WORK_DIR/downloads"
SRC_DIR="$WORK_DIR/src"
BUILD_DIR="$WORK_DIR/build"
PREFIX_DIR="$WORK_DIR/prefix"

log() {
  printf '\n[%s] %s\n' "$(date '+%H:%M:%S')" "$*"
}

require_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "缺少工具: $1"
    exit 1
  fi
}

prepare_dirs() {
  mkdir -p "$DOWNLOAD_DIR" "$SRC_DIR" "$BUILD_DIR" "$PREFIX_DIR" "$OUT_DIR"
}

download_source() {
  local name="$1"
  local url="$2"
  local src_target="$SRC_DIR/$name"
  local archive="$DOWNLOAD_DIR/${name}.archive"

  if [[ "$SKIP_DOWNLOAD" == "1" ]]; then
    if [[ ! -d "$src_target" ]]; then
      echo "SKIP_DOWNLOAD=1 但未找到源码目录: $src_target"
      exit 1
    fi
    return
  fi

  log "下载源码: $name"
  curl --fail --location --retry "$DOWNLOAD_RETRY" --connect-timeout "$DOWNLOAD_CONNECT_TIMEOUT" --max-time "$DOWNLOAD_MAX_TIME" "$url" -o "$archive"

  rm -rf "$src_target"
  mkdir -p "$src_target"
  tar -xf "$archive" -C "$src_target" --strip-components=1
}

sdk_path() {
  xcrun --sdk "$1" --show-sdk-path
}

cmake_common_args() {
  local sdk="$1"
  local arch="$2"
  cat <<ARGS
-DCMAKE_SYSTEM_NAME=iOS
-DCMAKE_OSX_SYSROOT=$(sdk_path "$sdk")
-DCMAKE_OSX_ARCHITECTURES=$arch
-DCMAKE_OSX_DEPLOYMENT_TARGET=$MIN_IOS_VERSION
-DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY
-DCMAKE_BUILD_TYPE=Release
ARGS
}

build_openssl_for_slice() {
  local slice="$1"
  local sdk="$2"
  local arch="$3"

  local src="$SRC_DIR/openssl"
  local build="$BUILD_DIR/openssl/$slice"
  local prefix="$PREFIX_DIR/openssl/$slice"

  rm -rf "$build" "$prefix"
  mkdir -p "$build/src" "$prefix"
  rsync -a "$src/" "$build/src/"

  local min_flag config
  if [[ "$sdk" == "iphoneos" ]]; then
    min_flag="-miphoneos-version-min=$MIN_IOS_VERSION"
    config="ios64-xcrun"
  else
    min_flag="-mios-simulator-version-min=$MIN_IOS_VERSION"
    config="iossimulator-xcrun"
  fi

  log "编译 OpenSSL ($slice)"
  pushd "$build/src" >/dev/null
  export CC="$(xcrun --sdk "$sdk" -f clang)"
  export CFLAGS="-arch $arch $min_flag -isysroot $(sdk_path "$sdk")"
  export LDFLAGS="$CFLAGS"
  ./Configure "$config" no-shared no-tests no-docs no-engine --prefix="$prefix"
  make -j"$JOBS"
  make install_sw
  popd >/dev/null
}

build_libssh2_for_slice() {
  local slice="$1"
  local sdk="$2"
  local arch="$3"

  local src="$SRC_DIR/libssh2"
  local build="$BUILD_DIR/libssh2/$slice"
  local prefix="$PREFIX_DIR/libssh2/$slice"
  local openssl_prefix="$PREFIX_DIR/openssl/$slice"

  rm -rf "$build" "$prefix"

  log "编译 libssh2 ($slice)"
  cmake -S "$src" -B "$build" \
    $(cmake_common_args "$sdk" "$arch") \
    -DCMAKE_INSTALL_PREFIX="$prefix" \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_TESTING=OFF \
    -DENABLE_ZLIB_COMPRESSION=OFF \
    -DCRYPTO_BACKEND=OpenSSL \
    -DOPENSSL_ROOT_DIR="$openssl_prefix" \
    -DOPENSSL_INCLUDE_DIR="$openssl_prefix/include" \
    -DOPENSSL_SSL_LIBRARY="$openssl_prefix/lib/libssl.a" \
    -DOPENSSL_CRYPTO_LIBRARY="$openssl_prefix/lib/libcrypto.a"

  cmake --build "$build" --target install -j"$JOBS"
}

build_curl_for_slice() {
  local slice="$1"
  local sdk="$2"
  local arch="$3"

  local src="$SRC_DIR/curl"
  local build="$BUILD_DIR/curl/$slice"
  local prefix="$PREFIX_DIR/curl/$slice"
  local openssl_prefix="$PREFIX_DIR/openssl/$slice"

  rm -rf "$build" "$prefix"

  log "编译 libcurl ($slice)"
  cmake -S "$src" -B "$build" \
    $(cmake_common_args "$sdk" "$arch") \
    -DCMAKE_INSTALL_PREFIX="$prefix" \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_TESTING=OFF \
    -DBUILD_CURL_EXE=OFF \
    -DBUILD_LIBCURL_DOCS=OFF \
    -DCURL_USE_OPENSSL=ON \
    -DOPENSSL_ROOT_DIR="$openssl_prefix" \
    -DOPENSSL_INCLUDE_DIR="$openssl_prefix/include" \
    -DOPENSSL_SSL_LIBRARY="$openssl_prefix/lib/libssl.a" \
    -DOPENSSL_CRYPTO_LIBRARY="$openssl_prefix/lib/libcrypto.a" \
    -DCURL_ZLIB=OFF \
    -DCURL_DISABLE_LDAP=ON \
    -DCURL_DISABLE_LDAPS=ON

  cmake --build "$build" --target install -j"$JOBS"
}

build_libsmb2_for_slice() {
  local slice="$1"
  local sdk="$2"
  local arch="$3"

  local src="$SRC_DIR/libsmb2"
  local build="$BUILD_DIR/libsmb2/$slice"
  local prefix="$PREFIX_DIR/libsmb2/$slice"

  rm -rf "$build" "$prefix"

  log "编译 libsmb2 ($slice)"
  cmake -S "$src" -B "$build" \
    $(cmake_common_args "$sdk" "$arch") \
    -DCMAKE_INSTALL_PREFIX="$prefix" \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_TESTING=OFF \
    -DENABLE_TESTS=OFF \
    -DENABLE_EXAMPLES=OFF \
    -DENABLE_PROGRAMS=OFF \
    -DENABLE_PYTHON=OFF \
    -DENABLE_KRB5=OFF \
    -DCMAKE_DISABLE_FIND_PACKAGE_GSSAPI=ON

  cmake --build "$build" --target install -j"$JOBS"
}

copy_slice_library() {
  local framework_name="$1"
  local slice="$2"
  local source_lib="$3"
  local include_dir="$4"

  local target_dir="$OUT_DIR/${framework_name}.xcframework/${slice}"
  rm -rf "$target_dir"
  mkdir -p "$target_dir/include"

  cp "$source_lib" "$target_dir/"
  rsync -a "$include_dir/" "$target_dir/include/"
}

package_outputs() {
  log "打包 xcframework 目录结构"

  local device="ios-arm64"
  local sim="ios-arm64-simulator"

  copy_slice_library "libcurl" "$device" "$PREFIX_DIR/curl/$device/lib/libcurl.a" "$PREFIX_DIR/curl/$device/include"
  copy_slice_library "libcurl" "$sim" "$PREFIX_DIR/curl/$sim/lib/libcurl.a" "$PREFIX_DIR/curl/$sim/include"

  copy_slice_library "libssh2" "$device" "$PREFIX_DIR/libssh2/$device/lib/libssh2.a" "$PREFIX_DIR/libssh2/$device/include"
  copy_slice_library "libssh2" "$sim" "$PREFIX_DIR/libssh2/$sim/lib/libssh2.a" "$PREFIX_DIR/libssh2/$sim/include"

  copy_slice_library "libsmb2" "$device" "$PREFIX_DIR/libsmb2/$device/lib/libsmb2.a" "$PREFIX_DIR/libsmb2/$device/include"
  copy_slice_library "libsmb2" "$sim" "$PREFIX_DIR/libsmb2/$sim/lib/libsmb2.a" "$PREFIX_DIR/libsmb2/$sim/include"

  copy_slice_library "libssl" "$device" "$PREFIX_DIR/openssl/$device/lib/libssl.a" "$PREFIX_DIR/openssl/$device/include"
  copy_slice_library "libssl" "$sim" "$PREFIX_DIR/openssl/$sim/lib/libssl.a" "$PREFIX_DIR/openssl/$sim/include"

  copy_slice_library "libcrypto" "$device" "$PREFIX_DIR/openssl/$device/lib/libcrypto.a" "$PREFIX_DIR/openssl/$device/include"
  copy_slice_library "libcrypto" "$sim" "$PREFIX_DIR/openssl/$sim/lib/libcrypto.a" "$PREFIX_DIR/openssl/$sim/include"
}

main() {
  require_tool curl
  require_tool tar
  require_tool cmake
  require_tool make
  require_tool rsync
  require_tool xcrun

  prepare_dirs

  download_source "openssl" "https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz"
  download_source "libssh2" "https://www.libssh2.org/download/libssh2-${LIBSSH2_VERSION}.tar.gz"
  download_source "curl" "https://curl.se/download/curl-${CURL_VERSION}.tar.xz"
  download_source "libsmb2" "https://github.com/sahlberg/libsmb2/archive/refs/tags/libsmb2-${LIBSMB2_VERSION}.tar.gz"

  build_openssl_for_slice "ios-arm64" "iphoneos" "arm64"
  build_openssl_for_slice "ios-arm64-simulator" "iphonesimulator" "arm64"

  build_libssh2_for_slice "ios-arm64" "iphoneos" "arm64"
  build_libssh2_for_slice "ios-arm64-simulator" "iphonesimulator" "arm64"

  build_curl_for_slice "ios-arm64" "iphoneos" "arm64"
  build_curl_for_slice "ios-arm64-simulator" "iphonesimulator" "arm64"

  build_libsmb2_for_slice "ios-arm64" "iphoneos" "arm64"
  build_libsmb2_for_slice "ios-arm64-simulator" "iphonesimulator" "arm64"

  package_outputs

  log "构建完成，输出目录: $OUT_DIR"
  "$SCRIPT_DIR/verify-ios-native-libs.sh" "$OUT_DIR"
}

main "$@"

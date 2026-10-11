#!/usr/bin/env bash
set -euo pipefail

ROOT="${1:-${IOS_NATIVE_XCFRAMEWORKS_ROOT:-$HOME/ios-native-xcframeworks}}"
SLICES=("ios-arm64" "ios-arm64-simulator")

failures=0

check_file() {
  local file_path="$1"
  if [[ ! -f "$file_path" ]]; then
    echo "❌ 缺少文件: $file_path"
    failures=$((failures + 1))
    return 1
  fi
  return 0
}

check_stub_marker() {
  local file_path="$1"
  if strings "$file_path" | grep -Eiq 'curl stub|libssh2 stub|libsmb2 stub|ios_native_empty_stub'; then
    echo "❌ 检测到 stub 占位库: $file_path"
    failures=$((failures + 1))
  fi
}

check_symbol() {
  local file_path="$1"
  local symbol="$2"
  local nm_dump
  nm_dump="$(mktemp)"

  nm -gU "$file_path" > "$nm_dump"
  if ! grep -Fq -- "$symbol" "$nm_dump"; then
    echo "❌ 缺少符号 ${symbol}: $file_path"
    failures=$((failures + 1))
  fi

  rm -f "$nm_dump"
}

for slice in "${SLICES[@]}"; do
  curl_lib="$ROOT/libcurl.xcframework/$slice/libcurl.a"
  ssh2_lib="$ROOT/libssh2.xcframework/$slice/libssh2.a"
  smb2_lib="$ROOT/libsmb2.xcframework/$slice/libsmb2.a"
  ssl_lib="$ROOT/libssl.xcframework/$slice/libssl.a"
  crypto_lib="$ROOT/libcrypto.xcframework/$slice/libcrypto.a"

  check_file "$curl_lib" && check_symbol "$curl_lib" "_curl_easy_perform" && check_stub_marker "$curl_lib"
  check_file "$ssh2_lib" && check_symbol "$ssh2_lib" "_libssh2_session_handshake" && check_stub_marker "$ssh2_lib"
  check_file "$smb2_lib" && check_symbol "$smb2_lib" "_smb2_connect_share" && check_stub_marker "$smb2_lib"
  check_file "$ssl_lib" && check_stub_marker "$ssl_lib"
  check_file "$crypto_lib" && check_stub_marker "$crypto_lib"

done

if (( failures > 0 )); then
  echo
  echo "校验失败，共 ${failures} 项问题。"
  exit 1
fi

echo "✅ iOS 原生网络库校验通过: $ROOT"

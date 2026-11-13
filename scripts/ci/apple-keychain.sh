#!/usr/bin/env bash
set -euo pipefail

# 仅由 Apple 发布 Job 调用；绝不写入用户的登录钥匙串。
signing_dir="${RUNNER_TEMP:?}/folderspan-apple-signing"
keychain="$signing_dir/signing.keychain-db"
case "${1:-}" in
  setup)
    : "${APPLE_CERTIFICATE_BASE64:?缺少签名证书 Secret}"
    : "${APPLE_CERTIFICATE_PASSWORD:?缺少 P12 密码 Secret}"
    mkdir -m 700 "$signing_dir"
    umask 077
    # 在导入之前登记路径，后续任意一步失败时 always() 仍可清理。
    printf 'APPLE_SIGNING_DIR=%s\nAPPLE_KEYCHAIN=%s\n' "$signing_dir" "$keychain" >> "${GITHUB_ENV:?}"
    security list-keychains -d user > "$signing_dir/original-keychains.txt"
    printf '%s' "$APPLE_CERTIFICATE_BASE64" | base64 --decode > "$signing_dir/certificates.p12"
    password="$(openssl rand -hex 32)"
    security create-keychain -p "$password" "$keychain"
    security set-keychain-settings -lut 21600 "$keychain"
    security unlock-keychain -p "$password" "$keychain"
    security import "$signing_dir/certificates.p12" -P "$APPLE_CERTIFICATE_PASSWORD" \
      -k "$keychain" -T /usr/bin/codesign -T /usr/bin/productsign -T /usr/bin/productbuild
    security set-key-partition-list -S apple-tool:,apple:,codesign: -k "$password" "$keychain" >/dev/null
    python3 - "$signing_dir/original-keychains.txt" "$keychain" <<'PY'
import pathlib, shlex, subprocess, sys
original = shlex.split(pathlib.Path(sys.argv[1]).read_text())
subprocess.run(["security", "list-keychains", "-d", "user", "-s", sys.argv[2], *original], check=True)
PY
    rm -f "$signing_dir/certificates.p12"
    ;;
  cleanup)
    [[ -d "$signing_dir" ]] || exit 0
    cleanup_status=0
    python3 - "$signing_dir" <<'PY' || cleanup_status=$?
import pathlib, shlex, subprocess, sys
root = pathlib.Path(sys.argv[1])
original = root / "original-keychains.txt"
if original.exists():
    subprocess.run(["security", "list-keychains", "-d", "user", "-s", *shlex.split(original.read_text())], check=True)
manifest = root / "installed-profiles.txt"
if manifest.exists():
    for path in manifest.read_text().splitlines():
        pathlib.Path(path).unlink(missing_ok=True)
PY
    if [[ -f "$keychain" ]]; then
      security delete-keychain "$keychain" || cleanup_status=$?
    fi
    rm -rf -- "$signing_dir"
    exit "$cleanup_status"
    ;;
  *) echo "Usage: bash apple-keychain.sh setup|cleanup" >&2; exit 2 ;;
esac

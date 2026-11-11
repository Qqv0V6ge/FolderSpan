#!/usr/bin/env bash
set -euo pipefail

: "${APPLE_ID:?缺少 APPLE_ID}"
: "${APPLE_APP_SPECIFIC_PASSWORD:?缺少 Apple 专用密码}"
: "${APPLE_TEAM_ID:?缺少 APPLE_TEAM_ID}"
: "${APPLE_SIGNING_DIR:?缺少临时签名目录}"
shopt -s nullglob
packages=("$1"/dmg/*.dmg "$1"/pkg/*.pkg)
(( ${#packages[@]} > 0 )) || { echo '未找到 macOS 安装包' >&2; exit 1; }
for package in "${packages[@]}"; do
  if [[ "$package" == *.pkg ]]; then
    pkgutil --check-signature "$package"
  else
    codesign --verify --strict "$package"
  fi
  # notarytool 拒绝或超时会使任务失败，不发布未获批准的安装包。
  xcrun notarytool submit "$package" --apple-id "$APPLE_ID" \
    --password "$APPLE_APP_SPECIFIC_PASSWORD" --team-id "$APPLE_TEAM_ID" \
    --wait --timeout 30m --output-format json > "$APPLE_SIGNING_DIR/notary-result.json"
  python3 - "$APPLE_SIGNING_DIR/notary-result.json" <<'PY'
import json, pathlib, sys
result = json.loads(pathlib.Path(sys.argv[1]).read_text())
if result.get("status") != "Accepted":
    raise SystemExit(f"Apple 公证失败: {result.get('status')} (ID: {result.get('id')})")
print(f"Apple 公证通过: {result['id']}")
PY
  xcrun stapler staple "$package"
  xcrun stapler validate "$package"
done

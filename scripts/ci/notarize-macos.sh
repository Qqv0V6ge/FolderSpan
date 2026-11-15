#!/usr/bin/env bash
set -euo pipefail

: "${APPLE_ID:?缺少 APPLE_ID}"
: "${APPLE_APP_SPECIFIC_PASSWORD:?缺少 Apple 专用密码}"
: "${APPLE_TEAM_ID:?缺少 APPLE_TEAM_ID}"
: "${APPLE_SIGNING_DIR:?缺少临时签名目录}"
input="${1:?请输入 .app 路径或安装包输出目录}"
credentials=(--apple-id "$APPLE_ID" --password "$APPLE_APP_SPECIFIC_PASSWORD" --team-id "$APPLE_TEAM_ID")
work_dir="$(mktemp -d "$APPLE_SIGNING_DIR/notary.XXXXXX")"
trap 'rm -rf -- "$work_dir"' EXIT

run_stapler() {
  local action="$1" path="$2" delay status
  for delay in 5 10 20; do
    if xcrun stapler "$action" "$path"; then
      return 0
    else
      status=$?
    fi
    # EX_NOHOST can indicate a temporary CloudKit failure after notarization is accepted.
    if (( status != 68 )); then
      return "$status"
    fi
    printf '::warning::stapler %s 返回 68，%s 秒后重试：%s\n' "$action" "$delay" "$path" >&2
    sleep "$delay"
  done
  xcrun stapler "$action" "$path"
}

if [[ "$input" == *.app ]]; then
  packages=("$input")
else
  shopt -s nullglob
  dmgs=("$input"/dmg/*.dmg)
  pkgs=("$input"/pkg/*.pkg)
  if (( ${#dmgs[@]} != 1 || ${#pkgs[@]} != 1 )); then
    echo "公证需要恰好一个 DMG 和一个 PKG" >&2
    exit 1
  fi
  packages=("${dmgs[0]}" "${pkgs[0]}")
fi

for package in "${packages[@]}"; do
  archive="$package"
  case "$package" in
    *.pkg)
      signature="$(pkgutil --check-signature "$package")"
      installer="$(sed -n 's/^[[:space:]]*1\. Developer ID Installer: //p' <<< "$signature")"
      if [[ "$installer" != *" ($APPLE_TEAM_ID)" ]]; then
        echo "PKG 缺少配置团队的 Developer ID Installer 签名" >&2
        exit 1
      fi
      ;;
    *.app | *.dmg)
      flags=(--verify --strict)
      [[ "$package" != *.app ]] || flags+=(--deep)
      codesign "${flags[@]}" "$package"
      signature="$(codesign --display --verbose=4 "$package" 2>&1)"
      if ! grep -q '^Authority=Developer ID Application: ' <<< "$signature" ||
         ! grep -Fxq "TeamIdentifier=$APPLE_TEAM_ID" <<< "$signature" ||
         ! grep -q '^Timestamp=' <<< "$signature"; then
        echo "缺少配置团队带时间戳的 Developer ID Application 签名：$package" >&2
        exit 1
      fi
      if [[ "$package" == *.app ]]; then
        archive="$work_dir/$(basename "$package" .app).zip"
        ditto -c -k --keepParent "$package" "$archive"
      fi
      ;;
  esac

  submit_status=0
  xcrun notarytool submit "$archive" "${credentials[@]}" \
    --wait --timeout 30m --output-format json > "$work_dir/result.json" || submit_status=$?
  result="$(python3 - "$work_dir/result.json" <<'PY'
import json, pathlib, sys
try:
    receipt = json.loads(pathlib.Path(sys.argv[1]).read_text())
except (OSError, ValueError):
    raise SystemExit("Apple 公证未返回有效结果")
print(f"{receipt.get('status', 'Unknown')}\t{receipt.get('id', '')}")
PY
  )"
  IFS=$'\t' read -r status submission_id <<< "$result"
  if (( submit_status != 0 )) || [[ "$status" != Accepted || -z "$submission_id" ]]; then
    echo "Apple 公证失败：$package ($status, ID: $submission_id)" >&2
    if [[ -n "$submission_id" ]]; then
      xcrun notarytool log "$submission_id" "${credentials[@]}" >&2 || true
    fi
    exit 1
  fi
  echo "Apple 公证通过：$package (ID: $submission_id)"
  run_stapler staple "$package"
  run_stapler validate "$package"
done

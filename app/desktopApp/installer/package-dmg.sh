#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 || "$2" != *.dmg ]]; then
  echo "Usage: bash package-dmg.sh <FolderSpan.app> <output.dmg> <version>" >&2
  exit 2
fi
installer_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
payload="$1"
output="$2"
version="$3"
mkdir -p -- "$(dirname -- "$output")"
output_parent="$(cd -- "$(dirname -- "$output")" && pwd -P)"
output="$output_parent/$(basename -- "$output")"
work_dir="$(mktemp -d "$output_parent/.folderspan-dmg-build.XXXXXX")"
trap 'rm -rf -- "$work_dir"' EXIT
mkdir -p "$work_dir/volume"
bash "$installer_dir/build-installer.sh" "$payload" "$work_dir/volume/FolderSpan Installer.app" "$version"
# Payload lives inside the installer, so App Translocation cannot break a sibling-file lookup.
hdiutil create -volname FolderSpan -srcfolder "$work_dir/volume" -format UDZO -fs HFS+ "$work_dir/FolderSpan.dmg"
if [[ "${FOLDERSPAN_INSTALLER_SIGN:-false}" == true ]]; then
  identity="$FOLDERSPAN_INSTALLER_SIGN_IDENTITY"
  [[ "$identity" == "Developer ID Application:"* ]] || identity="Developer ID Application: $identity"
  signing_args=(--timestamp --sign "$identity")
  if [[ -n "${FOLDERSPAN_INSTALLER_SIGN_KEYCHAIN:-}" ]]; then
    signing_args+=(--keychain "$FOLDERSPAN_INSTALLER_SIGN_KEYCHAIN")
  fi
  codesign "${signing_args[@]}" "$work_dir/FolderSpan.dmg"
  codesign --verify "$work_dir/FolderSpan.dmg"
fi
mv -f -- "$work_dir/FolderSpan.dmg" "$output"
echo "DMG: $output"

#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: bash build-installer.sh <FolderSpan.app|--preview> <output.app> [version]" >&2
  exit 2
fi

installer_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
payload="$1"
output="$2"
version="${3:-1.0.0}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Invalid version" >&2; exit 2; }
[[ "$output" == *.app ]] || { echo "Output must be an .app bundle" >&2; exit 2; }

if [[ "$payload" != --preview ]]; then
  [[ -d "$payload" && ! -L "$payload" ]] || { echo "Missing FolderSpan.app payload" >&2; exit 2; }
  payload="$(cd -- "$payload" && pwd -P)"
  identifier="$(plutil -extract CFBundleIdentifier raw -o - "$payload/Contents/Info.plist")"
  [[ "$identifier" == com.folderspan ]] || { echo "Payload must be FolderSpan.app" >&2; exit 2; }
fi
mkdir -p -- "$(dirname -- "$output")"
output_parent="$(cd -- "$(dirname -- "$output")" && pwd -P)"
output="$output_parent/$(basename -- "$output")"
[[ "$output" != "$payload" && "$output" != "$payload/"* ]] || { echo "Output cannot replace or be inside the payload" >&2; exit 2; }
if [[ -e "$output" || -L "$output" ]]; then
  [[ ! -L "$output" && "$(plutil -extract CFBundleIdentifier raw -o - "$output/Contents/Info.plist")" == com.folderspan.installer ]] || {
    echo "Refusing to replace an unrelated output bundle" >&2; exit 2;
  }
fi

swift build --package-path "$installer_dir" -c release --product FolderSpanInstaller
binary_dir="$(swift build --package-path "$installer_dir" -c release --show-bin-path)"
# Newer Swift toolchains can raise the deployment target above Package.swift's baseline.
deployment_target="$(xcrun vtool -show-build "$binary_dir/FolderSpanInstaller" | awk '$1 == "minos" { print $2; exit }')"
[[ "$deployment_target" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || { echo "Cannot determine the macOS deployment target" >&2; exit 2; }
work_dir="$(mktemp -d "$output_parent/.folderspan-installer-build.XXXXXX")"
trap 'rm -rf -- "$work_dir"' EXIT
app="$work_dir/FolderSpan Installer.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp "$binary_dir/FolderSpanInstaller" "$app/Contents/MacOS/FolderSpanInstaller"
cp "$installer_dir/Resources/Info.plist" "$app/Contents/Info.plist"
plutil -replace CFBundleShortVersionString -string "$version" "$app/Contents/Info.plist"
plutil -replace CFBundleVersion -string "$version" "$app/Contents/Info.plist"
plutil -replace LSMinimumSystemVersion -string "$deployment_target" "$app/Contents/Info.plist"
if [[ "$payload" == --preview ]]; then
  plutil -insert FolderSpanInstallerPreview -bool true "$app/Contents/Info.plist"
  if [[ -n "${FOLDERSPAN_INSTALLER_PREVIEW_LANGUAGE:-}" ]]; then
    [[ "$FOLDERSPAN_INSTALLER_PREVIEW_LANGUAGE" == en || "$FOLDERSPAN_INSTALLER_PREVIEW_LANGUAGE" == zh ]] || { echo "Preview language must be en or zh" >&2; exit 2; }
    plutil -insert FolderSpanInstallerPreviewLanguage -string "$FOLDERSPAN_INSTALLER_PREVIEW_LANGUAGE" "$app/Contents/Info.plist"
  fi
fi
cp -R "$installer_dir/Resources/en.lproj" "$installer_dir/Resources/zh-Hans.lproj" "$app/Contents/Resources/"
cp "$installer_dir/../src/main/resources/packaging/icon.icns" "$app/Contents/Resources/app-icon.icns"
cp "$installer_dir/../src/main/resources/packaging/icon-1024.png" "$app/Contents/Resources/app-icon.png"
if [[ "$payload" != --preview ]]; then
  mkdir -p "$app/Contents/Resources/Payload"
  ditto "$payload" "$app/Contents/Resources/Payload/FolderSpan.app"
fi

if [[ "${FOLDERSPAN_INSTALLER_SIGN:-false}" == true ]]; then
  identity="${FOLDERSPAN_INSTALLER_SIGN_IDENTITY:-}"
  [[ -n "$identity" ]] || { echo "A Developer ID signing identity is required" >&2; exit 2; }
  [[ "$identity" == "Developer ID Application:"* ]] || identity="Developer ID Application: $identity"
  signing_args=(--force --options runtime --timestamp --sign "$identity")
  if [[ -n "${FOLDERSPAN_INSTALLER_SIGN_KEYCHAIN:-}" ]]; then
    signing_args+=(--keychain "$FOLDERSPAN_INSTALLER_SIGN_KEYCHAIN")
  fi
  [[ "$payload" == --preview ]] || codesign --verify --deep --strict "$app/Contents/Resources/Payload/FolderSpan.app"
  codesign "${signing_args[@]}" "$app"
  codesign --verify --deep --strict "$app"
else
  codesign --force --sign - "$app"
  codesign --verify "$app"
fi
if [[ -d "$output" ]]; then
  mv -- "$output" "$work_dir/previous.app"
fi
if ! mv -- "$app" "$output"; then
  [[ ! -d "$work_dir/previous.app" ]] || mv -- "$work_dir/previous.app" "$output"
  exit 1
fi
echo "Installer: $output (macOS $deployment_target+)"

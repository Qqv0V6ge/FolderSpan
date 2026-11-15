#!/usr/bin/env bash
set -euo pipefail

version=$1
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
appdir="$work/FolderSpan.AppDir"
output="release-artifacts/linux-channel/FolderSpan-${version}-x86_64.AppImage"

mkdir -p "$appdir/usr/bin" "$(dirname "$output")"
cp -a app/desktopApp/build/compose/binaries/main-release/app/FolderSpan/. "$appdir/"
install -m755 packaging/appimage/AppRun "$appdir/AppRun"
cp packaging/linux/folderspan.desktop "$appdir/"
cp "$appdir/lib/FolderSpan.png" "$appdir/folderspan.png"
ln -s ../../bin/FolderSpan "$appdir/usr/bin/folderspan"
test -x "$appdir/bin/FolderSpan"
desktop-file-validate "$appdir/folderspan.desktop"

curl --fail --location --retry 3 \
  https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage \
  -o "$work/appimagetool"
curl --fail --location --retry 3 \
  https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-x86_64 \
  -o "$work/runtime"
(
  cd "$work"
  sha256sum --check <<'SUMS'
ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0  appimagetool
2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d  runtime
SUMS
)
chmod +x "$work/appimagetool"
ARCH=x86_64 VERSION="$version" "$work/appimagetool" --appimage-extract-and-run \
  --runtime-file "$work/runtime" "$appdir" "$output"
python3 - "$output" <<'PY'
from pathlib import Path
import sys

with Path(sys.argv[1]).open("rb") as image:
    header = image.read(11)
assert header.startswith(b"\x7fELF") and header[8:11] == b"AI\x02", header
PY

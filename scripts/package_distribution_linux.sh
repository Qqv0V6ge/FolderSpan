#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
mkdir -p distribution-results "$RUNNER_TEMP/folderspan-linux"
exec > >(tee distribution-results/linux-packaging.log) 2>&1
work="$RUNNER_TEMP/folderspan-linux"
readarray -t asset < <(python3 - <<'PY'
import json
x=json.load(open('packaging/releases.json'))['1.0.1']['linux-x64']
print(x['url']); print(x['sha256'])
PY
)
curl --fail --location --retry 3 "${asset[0]}" -o "$work/payload.tar.gz"
echo "${asset[1]}  $work/payload.tar.gz" | sha256sum --check
mkdir -p "$work/FolderSpan.AppDir"
tar -xzf "$work/payload.tar.gz" -C "$work/FolderSpan.AppDir"
# Restore executable modes lost in the immutable v1.0.1 TAR.
chmod 755 "$work/FolderSpan.AppDir/bin/FolderSpan" "$work/FolderSpan.AppDir/lib/runtime/lib/jspawnhelper" "$work/FolderSpan.AppDir/lib/runtime/lib/jexec"
test -x "$work/FolderSpan.AppDir/bin/FolderSpan"
for notice in LICENSE NOTICE LICENSING.md THIRD_PARTY_NOTICES.txt; do
  python3 - "$notice" "$work/FolderSpan.AppDir/lib/app/resources/licenses/$notice" <<'PY'
import hashlib, json, sys
from pathlib import Path
expected = json.load(open('packaging/releases.json'))['1.0.1']['original-notice-sha256'][sys.argv[1]]
assert hashlib.sha256(Path(sys.argv[2]).read_bytes()).hexdigest() == expected, sys.argv[1]
PY
done
# Keep the immutable release's original notice and also deliver the refreshed one.
cp THIRD_PARTY_NOTICES.txt "$work/FolderSpan.AppDir/lib/app/resources/licenses/THIRD_PARTY_NOTICES-2026-10-27.txt"
xvfb-run -a python3 scripts/verify_distribution_linux.py linux-tar "$work/FolderSpan.AppDir/bin/FolderSpan"

install -m755 packaging/appimage/AppRun "$work/FolderSpan.AppDir/AppRun"
cp packaging/linux/folderspan.desktop "$work/FolderSpan.AppDir/"
cp "$work/FolderSpan.AppDir/lib/FolderSpan.png" "$work/FolderSpan.AppDir/folderspan.png"
mkdir -p "$work/FolderSpan.AppDir/usr/bin"
ln -s ../../bin/FolderSpan "$work/FolderSpan.AppDir/usr/bin/folderspan"
desktop-file-validate "$work/FolderSpan.AppDir/folderspan.desktop"
curl --fail --location --retry 3 https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage -o "$work/appimagetool"
echo "95cbe7cce9717fce90c484e34052ee7c7f1d7635b33c12525b4776826a7d29b6  $work/appimagetool" | sha256sum --check
curl --fail --location --retry 3 https://github.com/AppImage/type2-runtime/releases/download/continuous/runtime-x86_64 -o "$work/runtime"
echo "617aa77314d57c8b8e4fae9c3f0b35af7b30d5529cb69e98f64f92510ed98a46  $work/runtime" | sha256sum --check
chmod +x "$work/appimagetool"
appimage="$root/distribution-results/FolderSpan-1.0.1-x86_64.AppImage"
ARCH=x86_64 "$work/appimagetool" --appimage-extract-and-run --runtime-file "$work/runtime" "$work/FolderSpan.AppDir" "$appimage"
python3 - "$appimage" <<'PY'
from pathlib import Path
import sys
with Path(sys.argv[1]).open('rb') as stream: magic=stream.read(11)
assert magic.startswith(b'\x7fELF') and magic[8:11] == b'AI\x02', magic
PY
xvfb-run -a python3 scripts/verify_distribution_linux.py appimage "$appimage" --appimage-extract-and-run
sha256sum "$appimage" > distribution-results/AppImage.SHA256SUMS

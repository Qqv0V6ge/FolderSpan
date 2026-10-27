{
  lib,
  stdenv,
  fetchurl,
  autoPatchelfHook,
  makeWrapper,
  makeFontsConf,
  dejavu_fonts,
  alsa-lib,
  fontconfig,
  freetype,
  libGL,
  xorg,
  glib,
  dbus,
  systemd,
  libdrm,
  libgbm,
  gtk3,
  zlib,
}:
let
  supplementalNotices = fetchurl {
    url = "https://github.com/Qqv0V6ge/FolderSpan/releases/download/v1.0.1/THIRD_PARTY_NOTICES-2026-10-27.txt";
    sha256 = "d81da23f22a5bd68b4c9ef773f5a018c404a9d5f9a52ec79e01f526343ad8e86";
  };
  runtimeLibraries = [
    stdenv.cc.cc.lib alsa-lib fontconfig freetype libGL
    xorg.libX11 xorg.libXext xorg.libXi xorg.libXrender xorg.libXtst
    xorg.libXcomposite xorg.libXdamage xorg.libXfixes xorg.libXrandr
    glib dbus systemd libdrm libgbm gtk3 zlib
  ];
in
stdenv.mkDerivation {
  pname = "folderspan-bin";
  version = "1.0.1";
  src = fetchurl {
    url = "https://github.com/Qqv0V6ge/FolderSpan/releases/download/v1.0.1/desktop-linux-x64-no-pro-com.folderspan-1.0.1.tar.gz";
    sha256 = "58d8958d1777eee2590b28b83c12bb92e2e21ca5d4f12a794c109b04660b53c8";
  };
  sourceRoot = ".";
  nativeBuildInputs = [ autoPatchelfHook makeWrapper ];
  buildInputs = runtimeLibraries;
  runtimeDependencies = map lib.getLib runtimeLibraries;
  dontConfigure = true;
  dontBuild = true;
  dontStrip = true;
  postUnpack = ''
    chmod 755 bin/FolderSpan lib/runtime/lib/jspawnhelper lib/runtime/lib/jexec
  '';
  installPhase = ''
    runHook preInstall
    test -x bin/FolderSpan
    mkdir -p "$out/opt/folderspan" "$out/bin" "$out/share/applications" "$out/share/pixmaps"
    cp -a bin lib "$out/opt/folderspan/"
    cp ${supplementalNotices} "$out/opt/folderspan/lib/app/resources/licenses/THIRD_PARTY_NOTICES-2026-10-27.txt"
    cp ${../linux/folderspan.desktop} "$out/share/applications/folderspan.desktop"
    cp lib/FolderSpan.png "$out/share/pixmaps/folderspan.png"
    makeWrapper "$out/opt/folderspan/bin/FolderSpan" "$out/bin/folderspan" \
      --prefix LD_LIBRARY_PATH : "${lib.makeLibraryPath runtimeLibraries}" \
      --set FONTCONFIG_FILE "${makeFontsConf { fontDirectories = [ dejavu_fonts ]; }}"
    runHook postInstall
  '';
  meta = {
    description = "Manage local and remote files and share across devices";
    homepage = "https://folderspan.com/";
    # Application and bundled JRE; each library's terms remain in the payload.
    license = [ lib.licenses.asl20 {
      fullName = "GNU GPL version 2 with Classpath exception";
      spdxId = "GPL-2.0-only WITH Classpath-exception-2.0";
      url = "https://openjdk.org/legal/gplv2+ce.html";
      free = true;
      redistributable = true;
    } ];
    sourceProvenance = with lib.sourceTypes; [ binaryNativeCode binaryBytecode ];
    mainProgram = "folderspan";
    platforms = [ "x86_64-linux" ];
  };
}

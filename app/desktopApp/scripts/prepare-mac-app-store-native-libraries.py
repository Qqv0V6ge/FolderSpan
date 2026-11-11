#!/usr/bin/env python3
"""把当前架构的 JNI 库放入应用包，避免商店沙盒运行时从 JAR 解包执行。"""
import hashlib
import copy
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from zipfile import ZipFile


def main():
    output = Path(sys.argv[1])
    architecture = sys.argv[2]
    folder_access_source = Path(sys.argv[3])
    compatibility_classes = Path(sys.argv[4])
    patched_runtime = Path(sys.argv[5])
    patched_runtime.mkdir(parents=True, exist_ok=True)
    replacements = {
        "webrtc-java-0.20.0.jar": ("7c2b8349fd1a628b7ced59106ab4dd3314dd0a0e0944ea0e740f84ac9c4ff349",
            "dev/onvoid/webrtc/internal/NativeLoader.class"),
        "zstd-kmp-jvm-0.4.0.jar": ("70c169b6fb10c4106cfbdb0ce117838fc4bf29974316860a66185a1392056821",
            "com/squareup/zstd/Zstd__JvmZstdKt.class"),
    }
    patched = {}
    output.mkdir(parents=True, exist_ok=True)
    copied = {}
    with tempfile.TemporaryDirectory(prefix="folderspan-mac-native-") as temp:
        for archive in sorted(map(Path, sys.argv[6:])):
            if archive.suffix != ".jar":
                continue
            with ZipFile(archive) as jar:
                if archive.name in replacements:
                    expected, class_name = replacements[archive.name]
                    if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
                        raise RuntimeError("本机库加载器的依赖哈希已变更，需要重新验证：" + archive.name)
                    if class_name not in jar.namelist():
                        raise RuntimeError("依赖中的加载入口已变更：" + class_name)
                    destination = patched_runtime / archive.name
                    with ZipFile(destination, "w") as updated:
                        for entry in jar.infolist():
                            data = (compatibility_classes / class_name).read_bytes() if entry.filename == class_name else jar.read(entry)
                            updated.writestr(copy.copy(entry), data)
                    patched[archive.name] = {"originalSha256": expected,
                        "packagedSha256": hashlib.sha256(destination.read_bytes()).hexdigest(), "class": class_name}
                for entry in jar.infolist():
                    if not entry.filename.endswith((".dylib", ".jnilib")):
                        continue
                    name = Path(entry.filename).name
                    candidate = Path(temp) / name
                    candidate.write_bytes(jar.read(entry))
                    valid = subprocess.run(
                        ["/usr/bin/lipo", "-verify_arch", architecture, str(candidate)],
                        capture_output=True,
                    ).returncode == 0
                    if not valid:
                        continue
                    digest = hashlib.sha256(candidate.read_bytes()).hexdigest()
                    if name in copied and copied[name]["sha256"] != digest:
                        raise RuntimeError("发现同名但内容不同的 Mac 本机库：" + name)
                    destination = output / name
                    shutil.copyfile(candidate, destination)
                    destination.chmod(0o755)
                    copied[name] = {"sha256": digest, "source": archive.name}
    bridge = output / "libfolderspan-macos-access.dylib"
    subprocess.run([
        "/usr/bin/xcrun", "clang", "-dynamiclib", "-fobjc-arc", "-fblocks", "-arch", architecture,
        "-install_name", "@rpath/libfolderspan-macos-access.dylib",
        "-mmacosx-version-min=12.0", "-framework", "Foundation", "-framework", "AppKit",
        str(folder_access_source), "-o", str(bridge),
    ], check=True)
    bridge.chmod(0o755)
    copied[bridge.name] = {"sha256": hashlib.sha256(bridge.read_bytes()).hexdigest(), "source": folder_access_source.name}
    for old in output.iterdir():
        if old.is_file() and old.name not in copied and old.name != "native-libraries.json":
            old.unlink()
    if not any(name.startswith("libjnidispatch") for name in copied):
        raise RuntimeError("缺少当前架构的 JNA 本机库")
    if "libsqlitejdbc.dylib" not in copied:
        raise RuntimeError("缺少当前架构的 SQLite 本机库")
    if set(patched) != set(replacements):
        raise RuntimeError("缺少已验证的 WebRTC 或 Zstandard JVM 依赖")
    for old in patched_runtime.glob("*.jar"):
        if old.name not in patched:
            old.unlink()
    (output / "native-libraries.json").write_text(
        json.dumps({"architecture": architecture, "libraries": copied, "storeLoaderAdapters": patched}, indent=2) + "\n"
    )
    print("已准备 %d 个 %s 本机库" % (len(copied), architecture))


if __name__ == "__main__":
    main()

import hashlib
import json
import re
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory
from pathlib import Path


PACKAGING = Path(__file__).resolve().parents[2] / "packaging"
REPO = "https://github.com/Qqv0V6ge/FolderSpan"
OLD_VERSION = "1.0.1"
OLD_MSI_URL = f"{REPO}/releases/download/v1.0.1/desktop-windows-x64-no-pro-FolderSpan-1.0.1.msi"
OLD_MSI_HASH = "3B570C9AA31C0DD20CADCA98D6A695C4198E7AF24D2EEEE6FD0B3A4603B3D8A2"
OLD_TAR_HASH = "58d8958d1777eee2590b28b83c12bb92e2e21ca5d4f12a794c109b04660b53c8"
HISTORICAL_NOTICE = "THIRD_PARTY_NOTICES-2026-10-27.txt"


def digest(path):
    with path.open("rb") as package:
        return hashlib.file_digest(package, "sha256").hexdigest()


def asset_url(version, platform, path, edition="no-pro"):
    return f"{REPO}/releases/download/v{version}/desktop-{platform}-{edition}-{path.name}"


def copy_recipe(channel, destination, convert):
    for source in (PACKAGING / channel).rglob("*"):
        if source.is_file():
            target = destination / source.relative_to(PACKAGING / channel)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(convert(source, source.read_text(encoding="utf-8")), encoding="utf-8")


def chocolatey(edition, version, msi, destination):
    checksum = digest(msi).upper()
    url = asset_url(version, "windows-x64", msi, edition)
    title = "FolderSpan Pro" if edition == "pro" else "FolderSpan standard edition"

    def convert(source, text):
        if source.name == "VERIFICATION.txt":
            return f"{title} {version}\nInstaller: {url}\nSHA-256: {checksum}\n"
        text = (text.replace(OLD_MSI_URL, url).replace(OLD_VERSION, version).replace(OLD_MSI_HASH, checksum)
                .replace("tree/codex/channel-validation/", f"tree/v{version}/"))
        if edition == "pro" and source.suffix == ".nuspec":
            text = (text.replace("<id>folderspan</id>", "<id>folderspan-pro</id>")
                    .replace("<title>FolderSpan</title>", "<title>FolderSpan Pro</title>")
                    .replace(f"/blob/v{version}/LICENSE</licenseUrl>", f"/blob/v{version}/LICENSING.md</licenseUrl>"))
            text = re.sub(r"<!-- Standard-edition own code:.*?-->\n    ", "", text)
            text = re.sub(
                r"(?s)<description>.*?</description>",
                "<description>FolderSpan Pro is a file manager and sharing tool with account settings sync "
                "and official online services. This package installs the Windows x64 Pro MSI.</description>",
                text,
            )
        return text

    copy_recipe("chocolatey", destination, convert)


def one_asset(artifacts, platform, pattern, edition="no-pro"):
    package, = (artifacts / f"release-desktop-{platform}-{edition}").rglob(pattern)
    return package


def recipe_bundle(version, commit, version_code, artifacts, output):
    msi = one_asset(artifacts, "windows-x64", "*.msi")
    portable = one_asset(artifacts, "windows-arm64", "*.zip")
    linux_tar = one_asset(artifacts, "linux-x64", "*.tar.gz")
    msi_hash = digest(msi).upper()
    linux_hash = digest(linux_tar)
    properties = subprocess.check_output(["msiinfo", "export", str(msi), "Property"], text=True)
    msi_properties = dict(line.split("\t", 1) for line in properties.splitlines() if "\t" in line)
    install_version = msi_properties["ProductVersion"]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", install_version):
        raise ValueError("MSI ProductVersion must be a numeric install version.")

    def winget(_source, text):
        text = (text.replace(OLD_MSI_URL, asset_url(version, "windows-x64", msi))
                    .replace(OLD_VERSION, version).replace(OLD_MSI_HASH, msi_hash)
                    .replace("{55AD7B2E-FEC5-3687-8B9E-741855B61C98}", msi_properties["ProductCode"])
                    .replace("{ADD48935-D0F8-3AAE-8594-602D7756ADCC}", msi_properties["UpgradeCode"]))
        return re.sub(r"(?m)^([ ]*(?:PackageVersion|DisplayVersion):)[ ]*.*$",
                      lambda match: f"{match[1]} {install_version}", text)

    def scoop(_source, text):
        manifest = json.loads(text)
        manifest["version"] = version
        manifest["architecture"]["arm64"].update(
            url=asset_url(version, "windows-arm64", portable), hash=digest(portable),
        )
        return json.dumps(manifest, indent=2) + "\n"

    def aur(_source, text):
        notice_hash = "d81da23f22a5bd68b4c9ef773f5a018c404a9d5f9a52ec79e01f526343ad8e86"
        for historical in (HISTORICAL_NOTICE, notice_hash):
            text = re.sub(rf"\n +'.*{re.escape(historical)}.*'\)", ")", text)
            text = "".join(line for line in text.splitlines(keepends=True) if historical not in line)
        return text.replace(OLD_VERSION, version).replace(OLD_TAR_HASH, linux_hash)

    def nix(_source, text):
        text = re.sub(r"(?ms)^  supplementalNotices = fetchurl \{.*?^  \};\n", "", text)
        text = "".join(line for line in text.splitlines(keepends=True) if HISTORICAL_NOTICE not in line)
        return text.replace(OLD_VERSION, version).replace(OLD_TAR_HASH, linux_hash)

    def fdroid(_source, text):
        text = re.sub(r"(?ms)^  Published v1\.0\.1.*?(?=^  Historical code)", "", text)
        return (text.replace(OLD_VERSION, version)
                .replace("versionCode: 5", f"versionCode: {version_code}")
                .replace("androidVersionCode=5", f"androidVersionCode={version_code}")
                .replace("3b2627a46c6fb70d97746bd390df3bccc4675cd0", commit))

    with TemporaryDirectory() as directory:
        bundle = Path(directory)
        for channel, convert in (("winget", winget), ("scoop", scoop), ("aur", aur),
                                 ("nix", nix), ("fdroid", fdroid)):
            copy_recipe(channel, bundle / channel, convert)
        shutil.copyfile(PACKAGING / "linux/folderspan.desktop", bundle / "aur/folderspan.desktop")
        chocolatey("no-pro", version, msi, bundle / "chocolatey")
        chocolatey("pro", version, one_asset(artifacts, "windows-x64", "*.msi", "pro"), bundle / "chocolatey-pro")
        shutil.make_archive(str(output / f"FolderSpan-{version}-channel-recipes"), "zip", bundle)


if __name__ == "__main__":
    mode, version, *arguments = sys.argv[1:]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise SystemExit(f"Invalid release version: {version}")
    if mode == "chocolatey":
        edition, msi, destination = arguments
        if edition not in ("pro", "no-pro"):
            raise SystemExit(f"Invalid Chocolatey edition: {edition}")
        chocolatey(edition, version, Path(msi), Path(destination))
    elif mode == "recipes":
        commit, version_code, artifacts, output = arguments
        recipe_bundle(version, commit, version_code, Path(artifacts), Path(output))
    else:
        raise SystemExit(f"Unknown mode: {mode}")

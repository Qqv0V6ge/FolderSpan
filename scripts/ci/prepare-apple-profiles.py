#!/usr/bin/env python3
"""检查发布描述文件，生成本次 Job 的签名配置；不访问 Apple 账号。"""
import base64
import datetime
import os
import pathlib
import plistlib
import re
import subprocess
import sys


def read_profile(secret, filename, bundle_id, platform):
    root = pathlib.Path(os.environ["APPLE_SIGNING_DIR"])
    path = root / filename
    path.write_bytes(base64.b64decode(os.environ[secret], validate=True))
    profile = plistlib.loads(subprocess.check_output(["security", "cms", "-D", "-i", str(path)]))
    team = os.environ["APPLE_TEAM_ID"]
    entitlements = profile["Entitlements"]
    app_id = entitlements.get("application-identifier", entitlements.get("com.apple.application-identifier"))
    if profile["TeamIdentifier"] != [team] or app_id != f"{team}.{bundle_id}":
        raise ValueError(f"{secret}: 团队或 Bundle ID 不匹配 ({bundle_id})")
    if platform not in profile.get("Platform", []):
        raise ValueError(f"{secret}: 描述文件平台不匹配 ({platform})")
    if profile["ExpirationDate"] <= datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None):
        raise ValueError(f"{secret}: 描述文件已过期")
    if profile.get("ProvisionedDevices") or profile.get("ProvisionsAllDevices") or entitlements.get("get-task-allow"):
        raise ValueError(f"{secret}: 需要 App Store 分发描述文件")
    if not re.fullmatch(r"[A-Fa-f0-9-]{36}", profile["UUID"]):
        raise ValueError(f"{secret}: 无效 UUID")
    return profile


def prepare_ios():
    root = pathlib.Path(os.environ["APPLE_SIGNING_DIR"])
    profiles = {}
    settings = ["CODE_SIGN_STYLE = Manual", "CODE_SIGN_IDENTITY = Apple Distribution",
                f"DEVELOPMENT_TEAM = {os.environ['APPLE_TEAM_ID']}",
                "PROVISIONING_PROFILE_SPECIFIER = $(FOLDERSPAN_PROFILE_$(TARGET_NAME))"]
    for target, suffix, secret in [("iosApp", "", "IOS_APP_PROFILE_BASE64"),
                                   ("ShareExtension", ".ShareExtension", "IOS_SHARE_PROFILE_BASE64")]:
        bundle_id = "com.folderspan.FolderSpan" + suffix
        profile = read_profile(secret, target + ".mobileprovision", bundle_id, "iOS")
        uuid = profile["UUID"]
        # 同时支持旧版 Xcode 与 Xcode 16+ 的描述文件目录。
        for directory in ["Library/MobileDevice/Provisioning Profiles",
                          "Library/Developer/Xcode/UserData/Provisioning Profiles"]:
            destination = pathlib.Path.home() / directory / (uuid + ".mobileprovision")
            destination.parent.mkdir(parents=True, exist_ok=True)
            with destination.open("xb") as output:
                # 先登记再写入，部分写入失败也可清理；不覆盖已有配置。
                with (root / "installed-profiles.txt").open("a") as manifest:
                    manifest.write(str(destination) + "\n")
                output.write((root / (target + ".mobileprovision")).read_bytes())
        settings.append(f"FOLDERSPAN_PROFILE_{target} = {uuid}")
        profiles[bundle_id] = uuid
    (root / "ios-signing.xcconfig").write_text("\n".join(settings) + "\n")
    with (root / "ExportOptions.plist").open("wb") as output:
        plistlib.dump({"method": "app-store-connect", "destination": "export",
                      "signingStyle": "manual", "signingCertificate": "Apple Distribution",
                      "teamID": os.environ["APPLE_TEAM_ID"], "provisioningProfiles": profiles,
                      "manageAppVersionAndBuildNumber": False}, output)


def prepare_mac():
    # 现有商店沙盒 entitlements 固定到此团队，避免生成团队不一致的包。
    if os.environ["APPLE_TEAM_ID"] != "TG28DN3J2L":
        raise ValueError("请先同步 macOS App Store entitlements 中的团队 ID")
    read_profile("MACOS_APP_PROFILE_BASE64", "embedded.provisionprofile", "com.folderspan.FolderSpan", "OSX")
    read_profile("MACOS_RUNTIME_PROFILE_BASE64", "runtime.provisionprofile",
                 "com.oracle.java.com.folderspan.FolderSpan", "OSX")


if __name__ == "__main__":
    os.umask(0o077)
    if not re.fullmatch(r"[A-Z0-9]{10}", os.environ["APPLE_TEAM_ID"]):
        raise ValueError("无效的 APPLE_TEAM_ID")
    {"ios": prepare_ios, "macos": prepare_mac}[sys.argv[1]]()

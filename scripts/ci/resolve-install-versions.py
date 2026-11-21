"""Resolve install versions from source history, independently of workflow counters."""

import os
import re
import subprocess
import sys


def semantic_version(value):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", value):
        raise ValueError("Version must use MAJOR.MINOR.PATCH format.")
    return tuple(map(int, value.split(".")))


def derive_versions(version, revision, channel):
    major, minor, patch = semantic_version(version)
    if channel not in ("preview", "release"):
        raise ValueError("Build channel must be preview or release.")
    if not 1 <= major <= 127 or not 0 <= minor <= 255:
        raise ValueError("Install versions require major 1..127 and minor 0..255.")
    if not 0 <= patch <= 65_535:
        raise ValueError("Patch version must be 0..65535.")
    if not 1 <= revision <= 32_767:
        raise ValueError("Source revision must be 1..32767; never wrap an install version.")
    stage = int(channel == "release")
    build = revision * 2 + stage
    android = (major * 256 + minor) * 65_536 + build
    if android > 2_100_000_000:
        raise ValueError("Android versionCode exceeds 2100000000.")

    def apple(edition):
        ordinal = revision * 4 + stage * 2 + edition
        # Start above the old per-workflow Apple counters (Preview 40 / Release 25).
        return f"{1000 + ordinal // 10000}.{ordinal // 100 % 100}.{ordinal % 100}"

    return {
        "version": f"{major}.{minor}.{patch}",
        "source_revision": str(revision),
        "windows_package_version": f"{major}.{minor}.{build}",
        "android_version_code": str(android),
        "apple_build_number": apple(0),
        "apple_no_pro_build_number": apple(1),
    }


def git(*arguments):
    return subprocess.check_output(["git", *arguments], text=True).strip()


def main():
    if git("rev-parse", "--is-shallow-repository") != "false":
        raise ValueError("Install versions need full Git history; use checkout fetch-depth: 0.")
    versions = []
    for tag in git("tag", "--list", "v*").splitlines():
        value = tag.removeprefix("v")
        if re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", value):
            versions.append(semantic_version(value))
    baseline = max(versions, default=(1, 0, 0))
    requested = os.environ.get("BUILD_VERSION", "").strip().removeprefix("v")
    channel = os.environ["BUILD_CHANNEL"]
    if channel == "release" and not requested:
        raise ValueError("Set an explicit release version; preview alone may reuse the published baseline.")
    version = requested or ".".join(map(str, baseline))
    if semantic_version(version) < baseline:
        raise ValueError("Version is older than the published baseline; choose the current or a newer version.")
    result = derive_versions(version, int(git("rev-list", "--count", "HEAD")), channel)
    result["tag"] = "v" + result["version"]
    for name, value in result.items():
        print(f"{name}={value}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, subprocess.CalledProcessError) as error:
        sys.exit(f"::error::{error}")

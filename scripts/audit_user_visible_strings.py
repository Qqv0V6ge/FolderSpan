#!/usr/bin/env python3
"""Reject hardcoded Chinese literals and hash-based AppStrings resource keys.

Chinese text belongs in the Libres catalogs and must be read through AppStrings.
Comments are documentation rather than runtime string literals and are ignored.
"""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOTS = (ROOT / "app", ROOT / "core/src", ROOT / "proMain/kotlin", ROOT / "proMain/src")
STRING_CATALOGS = (
    ROOT / "core/src/commonMain/libres/strings/app_en.xml",
    ROOT / "core/src/commonMain/libres/strings/app_zhHans.xml",
)
EXCLUDED_PATH_PARTS = {"build", "generated"}
CHINESE_LITERAL = re.compile(
    r'"(?:\\.|[^"\\\r\n])*[\u3400-\u9fff](?:\\.|[^"\\\r\n])*"'
)
HASH_STYLE_RESOURCE_KEY = re.compile(
    r'name="([a-z][a-z0-9_]*_[0-9a-f]{8,})"'
)
COMMENT_PREFIXES = ("//", "*", "/*", "/**")


def is_source(path: Path) -> bool:
    return not any(part in EXCLUDED_PATH_PARTS for part in path.relative_to(ROOT).parts)


def main() -> int:
    comments = 0
    hardcoded: list[str] = []
    hash_style_keys: list[str] = []

    for source_root in SOURCE_ROOTS:
        for path in sorted(source_root.rglob("*.kt")):
            if not is_source(path):
                continue
            for line_number, line in enumerate(
                path.read_text(encoding="utf-8").splitlines(),
                start=1,
            ):
                if not CHINESE_LITERAL.search(line):
                    continue
                if line.lstrip().startswith(COMMENT_PREFIXES):
                    comments += 1
                    continue
                relative = path.relative_to(ROOT)
                hardcoded.append(f"{relative}:{line_number}: {line.strip()}")

    for catalog in STRING_CATALOGS:
        relative = catalog.relative_to(ROOT)
        for line_number, line in enumerate(
            catalog.read_text(encoding="utf-8").splitlines(),
            start=1,
        ):
            match = HASH_STYLE_RESOURCE_KEY.search(line)
            if match:
                hash_style_keys.append(
                    f"{relative}:{line_number}: {match.group(1)}"
                )

    print(f"comment: {comments}")
    if hardcoded:
        print("hardcoded-chinese-literals:")
        print("\n".join(hardcoded))
    if hash_style_keys:
        print("hash-style-resource-keys:")
        print("\n".join(hash_style_keys))
    if hardcoded or hash_style_keys:
        return 1

    print("AppStrings literal and semantic-key audit passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

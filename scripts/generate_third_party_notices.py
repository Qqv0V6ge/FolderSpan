#!/usr/bin/env python3
"""Render the checked-in upstream notices without network or dependency resolution."""

import argparse
import csv
import hashlib
import json
from pathlib import Path


def render_notices(root: Path) -> bytes:
    root = root.resolve()
    manifest = json.loads((root / "third-party/manifest.json").read_text())
    with (root / "third-party/maven/components.csv").open(
        encoding="utf-8-sig", newline=""
    ) as stream:
        components = list(csv.DictReader(stream))

    texts = {}
    for entry in manifest["texts"]:
        path = (root / entry["file"]).resolve()
        if not path.is_relative_to(root):
            raise ValueError(f"Notice path outside repository: {entry['file']}")
        payload = path.read_bytes()
        if hashlib.sha256(payload).hexdigest() != entry["sha256"]:
            raise ValueError(f"SHA-256 mismatch: {entry['file']}")
        if payload.startswith(b"\xca\xfe\xba\xbe") or b"\x00" in payload:
            raise ValueError(f"Refusing binary license text: {entry['file']}")
        payload.decode(entry.get("encoding", "utf-8"))
        texts[entry["file"]] = (entry, payload)

    for component in components:
        paths = component["license_texts"].split(";")
        if not all(path and path in texts for path in paths):
            raise ValueError(f"Missing license text for {component['component']}")

    header = (
        "FolderSpan standard edition — third-party notices\n\n"
        "Third-party components retain their own copyright and license terms.\n"
        "The FolderSpan Apache-2.0 license does not replace those terms.\n"
        "Platform-specific entries apply where the corresponding components are used.\n"
        "Bundled Java runtimes also retain their own runtime/legal directory.\n\n"
        + manifest["scope"]
        + "\n\nMaven component inventory\n"
        + "Component | Resolved configurations | License metadata\n"
    )
    chunks = [header.encode("utf-8")]
    for component in components:
        row = " | ".join(component[key] for key in (
            "component", "configurations", "license"
        ))
        chunks.append((row + "\n").encode("utf-8"))

    grouped = {}
    for entry, payload in texts.values():
        group = grouped.setdefault(entry["sha256"], {"entries": [], "payload": payload})
        group["entries"].append(entry)
    for number, group in enumerate(grouped.values(), 1):
        headings = [f"\n{'=' * 72}\nUpstream notice {number}\n"]
        for entry in group["entries"]:
            headings.append(f"\n{entry['name']}\nSource: {entry['source_url']}\n")
            if entry.get("applies_to"):
                headings.append(f"Applies to: {entry['applies_to']}\n")
        headings.append("\n--- Begin upstream text ---\n")
        chunks.append("".join(headings).encode("utf-8"))
        entry = group["entries"][0]
        payload = group["payload"]
        if entry.get("encoding", "utf-8") != "utf-8":
            payload = payload.decode(entry["encoding"]).encode("utf-8")
        chunks.extend([payload, b"\n--- End upstream text ---\n"])
    return b"".join(chunks)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify the generated file is current")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    expected = render_notices(root)
    target = root / "THIRD_PARTY_NOTICES.txt"
    if args.check:
        if not target.exists() or target.read_bytes() != expected:
            parser.exit(1, "THIRD_PARTY_NOTICES.txt is stale; regenerate it.\n")
        print("第三方声明与已核验来源一致。")
    else:
        target.write_bytes(expected)
        print(f"已生成 {target.name}（{len(expected):,} 字节）。")


if __name__ == "__main__":
    main()

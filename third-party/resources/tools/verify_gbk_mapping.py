#!/usr/bin/env python3
"""从归档的官方 CP936 数据重建并核对 GBK 差分资源；不写产品文件。"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path


SOURCE_SHA256 = "b86f601c575e9ab457380b6f7abef03c75499cc6075bdc8b4b27f3f2de74bf6a"
RESOURCE_SHA256 = "98491587282f1626ffffb379ff660b5fd9e3733512d8624d820ae431a35c69e1"
TABLE_SIZE = 126 * 190
UNDEFINED = 0xFFFF


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_source(data: bytes) -> dict[int, int]:
    if sha256(data) != SOURCE_SHA256:
        raise ValueError("归档 CP936 源数据 SHA-256 与已核实版本不同")
    mapping: dict[int, int] = {}
    for line in data.decode("ascii").splitlines():
        fields = line.split("#", 1)[0].split()
        if len(fields) >= 2:
            key, value = (int(field, 16) for field in fields[:2])
            if key in mapping:
                raise ValueError(f"重复映射键：{key:04X}")
            mapping[key] = value
    return mapping


def ordered_table(mapping: dict[int, int]) -> list[int]:
    table = []
    for lead in range(0x81, 0xFF):
        for trail in [*range(0x40, 0x7F), *range(0x80, 0xFF)]:
            table.append(mapping.get((lead << 8) | trail, UNDEFINED))
    if len(table) != TABLE_SIZE:
        raise ValueError("GBK 表长度异常")
    return table


def encode_delta(table: list[int]) -> bytes:
    result = bytearray()
    previous = 0
    for value in table:
        token = 0
        if value != UNDEFINED:
            delta = value - previous
            zigzag = delta * 2 if delta >= 0 else -delta * 2 - 1
            token = zigzag + 1
            previous = value
        while token >= 0x80:
            result.append((token & 0x7F) | 0x80)
            token >>= 7
        result.append(token)
    return bytes(result)


def main() -> None:
    resource_dir = Path(__file__).resolve().parents[1]
    root = resource_dir.parents[1]
    source = resource_dir / "upstream/unicode/CP936.TXT"
    product = root / "core/src/commonMain/composeResources/files/editor/gbk-delta-map.bin"
    table = ordered_table(read_source(source.read_bytes()))
    generated = encode_delta(table)
    current = product.read_bytes()
    if sha256(current) != RESOURCE_SHA256:
        raise ValueError("产品 GBK 资源已改变，需重新核对，不能沿用旧结论")
    if generated != current:
        raise ValueError("官方 CP936 数据生成结果与产品资源不同")
    print(json.dumps({
        "source_sha256": SOURCE_SHA256,
        "resource_sha256": sha256(current),
        "generated_sha256": sha256(generated),
        "entries": len(table),
        "defined_entries": sum(value != UNDEFINED for value in table),
        "undefined_entries": table.count(UNDEFINED),
        "bytes": len(generated),
        "exact_byte_match": True,
        "product_file_written": False,
        "historical_generation_tool_proved": False,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

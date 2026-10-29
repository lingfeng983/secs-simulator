#!/usr/bin/env python3
"""OpenAPI 静态校验：重复键、$ref 可解析、path 至少有一个操作。

重复键是 YAML 的静默陷阱：PyYAML 默认"后者覆盖前者"，
两个同名 path 只会留下一个，另一个端点的文档凭空消失。
用严格 loader 把重复键直接判成失败，CI/本地门禁才能挡住。

用法：python scripts/check-openapi.py [docs/openapi.yaml]
退出码：0 通过；1 有问题。
"""

import sys
from pathlib import Path

import yaml


class UniqueKeyLoader(yaml.SafeLoader):
    """按键去重并在重复时报错的 loader。"""


def construct_mapping(loader, node, deep=False):
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise ValueError(f"重复键 {key!r}（第 {key_node.start_mark.line + 1} 行）")
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, construct_mapping
)

OPERATIONS = ("get", "post", "put", "delete", "patch", "head", "options")


def collect_refs(node, refs):
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "$ref" and isinstance(value, str):
                refs.add(value)
            collect_refs(value, refs)
    elif isinstance(node, list):
        for item in node:
            collect_refs(item, refs)


def resolve(doc, ref):
    current = doc
    for part in ref[2:].split("/"):
        if not isinstance(current, dict) or part not in current:
            return False
        current = current[part]
    return True


def main() -> int:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else "docs/openapi.yaml")
    text = path.read_text(encoding="utf-8")

    try:
        doc = yaml.load(text, Loader=UniqueKeyLoader)
    except ValueError as error:
        print(f"[FAIL] {path}: {error}")
        return 1

    problems = []

    for name, item in doc.get("paths", {}).items():
        if not any(op in item for op in OPERATIONS):
            problems.append(f"path 没有任何操作: {name}")

    refs = set()
    collect_refs(doc, refs)
    for ref in sorted(refs):
        if ref.startswith("#/") and not resolve(doc, ref):
            problems.append(f"$ref 无法解析: {ref}")

    if problems:
        for problem in problems:
            print(f"[FAIL] {problem}")
        return 1

    print(f"[OK] {path}: {len(doc.get('paths', {}))} 个 path，$ref 全部可解析")
    return 0


if __name__ == "__main__":
    sys.exit(main())

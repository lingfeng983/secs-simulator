#!/usr/bin/env python3
"""OpenAPI 静态校验：重复键、$ref 可解析、path 至少有一个操作、path 清单快照、注册前缀覆盖。

重复键是 YAML 的静默陷阱：PyYAML 默认"后者覆盖前者"，
两个同名 path 只会留下一个，另一个端点的文档凭空消失。
用严格 loader 把重复键直接判成失败，CI/本地门禁才能挡住。

path 清单快照（openapi-paths.txt）挡的是另一类复发：实现新增了端点、
openapi 忘了回写（2026-11-24 评审 C1-C4 四项同一成因）。改契约必须同步快照，
评审时快照 diff 就是"这次动了哪些端点"的清单。

注册前缀覆盖：每个 path 必须落在 HttpApiServer.registerRoutes 注册的
HttpServer 上下文前缀下（前缀取自 ApiPaths.java 的常量值），挡"整条死声明"。

用法：python scripts/check-openapi.py [openapi.yaml] [--snapshot]
  --snapshot  按当前 yaml 重新生成 openapi-paths.txt（改完契约后运行）
退出码：0 通过；1 有问题。
"""

import re
import sys
from pathlib import Path

import yaml

# openapi.yaml 与本脚本同目录（secs-simulator-service/scripts/）。
SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_PATH = SCRIPT_DIR / "openapi.yaml"
SNAPSHOT_PATH = SCRIPT_DIR / "openapi-paths.txt"
API_PATHS_JAVA = (
    SCRIPT_DIR.parent
    / "secs-api-http/src/main/java/com/shimizukenta/secssim/api/http/ApiPaths.java"
)


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
        # JSON Pointer 转义（RFC 6901）：~1 → /，~0 → ~（顺序不能反）
        part = part.replace("~1", "/").replace("~0", "~")
        if not isinstance(current, dict) or part not in current:
            return False
        current = current[part]
    return True


def sorted_paths(doc):
    return sorted(doc.get("paths", {}).keys())


def registered_prefixes():
    """从 ApiPaths.java 解析以 /api/ 开头的字符串常量值。

    registerRoutes 用这些常量注册 HttpServer 上下文；取全集做前缀覆盖检查，
    比在脚本里再维护一份前缀清单少一处"忘了同步"。"""
    text = API_PATHS_JAVA.read_text(encoding="utf-8")
    return sorted(set(re.findall(r'"(/api/[^"]*)"', text)))


def concrete(path):
    """把 {param} 换成占位段，模拟 HttpServer 的最长前缀匹配。"""
    return re.sub(r"\{[^}]*\}", "x", path)


def covered(path, prefixes):
    target = concrete(path)
    for prefix in prefixes:
        if prefix.endswith("/"):
            if target.startswith(prefix):
                return True
        elif target == prefix or target.startswith(prefix + "/"):
            return True
    return False


def main() -> int:
    args = [a for a in sys.argv[1:] if a != "--snapshot"]
    snapshot_mode = "--snapshot" in sys.argv[1:]
    path = Path(args[0]) if args else DEFAULT_PATH
    text = path.read_text(encoding="utf-8")

    try:
        doc = yaml.load(text, Loader=UniqueKeyLoader)
    except ValueError as error:
        print(f"[FAIL] {path}: {error}")
        return 1

    paths = sorted_paths(doc)

    if snapshot_mode:
        SNAPSHOT_PATH.write_text("\n".join(paths) + "\n", encoding="utf-8")
        print(f"[OK] 已重新生成快照 {SNAPSHOT_PATH}: {len(paths)} 个 path")
        return 0

    problems = []

    for name, item in doc.get("paths", {}).items():
        if not any(op in item for op in OPERATIONS):
            problems.append(f"path 没有任何操作: {name}")

    refs = set()
    collect_refs(doc, refs)
    for ref in sorted(refs):
        if ref.startswith("#/"):
            if not resolve(doc, ref):
                problems.append(f"$ref 无法解析: {ref}")
        elif not ref.startswith("#"):
            # 外部 ref 本契约不使用；出现即提示（不解析外部文件，避免门禁依赖网络/路径）
            problems.append(f"外部 $ref 不受支持（本契约只用内部引用）: {ref}")

    # path 清单快照比对：yaml 与快照不一致即失败，防止端点增删悄悄绕过评审。
    if not SNAPSHOT_PATH.exists():
        problems.append(
            f"缺少 path 清单快照 {SNAPSHOT_PATH.name}，"
            "先运行 python scripts/check-openapi.py --snapshot 生成"
        )
    else:
        snapshot = [
            line.strip()
            for line in SNAPSHOT_PATH.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        if snapshot != paths:
            added = sorted(set(paths) - set(snapshot))
            removed = sorted(set(snapshot) - set(paths))
            for name in added:
                problems.append(f"path 快照缺少新增端点: {name}")
            for name in removed:
                problems.append(f"path 快照多出已删端点: {name}")
            if not added and not removed:
                problems.append("path 快照顺序与 yaml 排序不一致")
            problems.append(
                "yaml 与 openapi-paths.txt 不一致：确认契约改动后运行 "
                "python scripts/check-openapi.py --snapshot 同步快照"
            )

    # 注册前缀覆盖：挡整条死声明（path 不在任何 registerRoutes 上下文下）。
    if API_PATHS_JAVA.exists():
        prefixes = registered_prefixes()
        for name in paths:
            if not covered(name, prefixes):
                problems.append(
                    f"path 不在任何注册前缀下（疑似死声明）: {name}"
                )
    else:
        print(f"[WARN] 找不到 {API_PATHS_JAVA}，跳过注册前缀覆盖检查")

    if problems:
        for problem in problems:
            print(f"[FAIL] {problem}")
        return 1

    print(
        f"[OK] {path}: {len(paths)} 个 path，$ref 全部可解析，"
        "快照一致，注册前缀全覆盖"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
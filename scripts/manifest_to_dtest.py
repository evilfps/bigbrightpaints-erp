#!/usr/bin/env python3
import argparse
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Convert test manifests into a Surefire -Dtest selector")
    parser.add_argument("manifests", nargs="+", help="Manifest files containing one test class per line")
    return parser.parse_args()


def normalize_entry(entry: str) -> str:
    value = entry.strip()
    if not value or value.startswith("#"):
        return ""
    if value.endswith(".java"):
        value = value[:-5].replace("/", ".").replace("\\", ".")
        if value.startswith("erp-domain.src.test.java."):
            value = value[len("erp-domain.src.test.java."):]
    return value


def main() -> int:
    args = parse_args()
    selectors: list[str] = []
    seen: set[str] = set()

    for manifest in args.manifests:
        for raw_line in Path(manifest).read_text(encoding="utf-8").splitlines():
            selector = normalize_entry(raw_line)
            if not selector or selector in seen:
                continue
            seen.add(selector)
            selectors.append(selector)

    if not selectors:
        raise SystemExit("manifest resolved to an empty selector set")

    print(",".join(selectors))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Merge JaCoCo XML reports for changed-files coverage consumption")
    parser.add_argument("--output", required=True, help="Output jacoco.xml path")
    parser.add_argument("inputs", nargs="+", help="Input jacoco.xml files")
    return parser.parse_args()


def merge_line(
    current: tuple[int, int, int, int] | None,
    incoming: tuple[int, int, int, int],
) -> tuple[int, int, int, int]:
    if current is None:
        return incoming

    current_mi, current_ci, current_mb, current_cb = current
    incoming_mi, incoming_ci, incoming_mb, incoming_cb = incoming

    total_line = max(current_mi + current_ci, incoming_mi + incoming_ci)
    covered_line = min(total_line, current_ci + incoming_ci)
    missed_line = max(total_line - covered_line, 0)

    total_branch = max(current_mb + current_cb, incoming_mb + incoming_cb)
    covered_branch = min(total_branch, current_cb + incoming_cb)
    missed_branch = max(total_branch - covered_branch, 0)

    return missed_line, covered_line, missed_branch, covered_branch


def main() -> int:
    args = parse_args()
    packages: dict[str, dict[str, dict[int, tuple[int, int, int, int]]]] = {}

    for input_path in args.inputs:
        root = ET.parse(input_path).getroot()
        for pkg in root.findall("./package"):
            package_name = pkg.get("name", "")
            package_entry = packages.setdefault(package_name, {})
            for source_file in pkg.findall("./sourcefile"):
                source_name = source_file.get("name", "")
                source_entry = package_entry.setdefault(source_name, {})
                for line in source_file.findall("./line"):
                    line_number = int(line.get("nr", "0"))
                    incoming = (
                        int(line.get("mi", "0")),
                        int(line.get("ci", "0")),
                        int(line.get("mb", "0")),
                        int(line.get("cb", "0")),
                    )
                    source_entry[line_number] = merge_line(source_entry.get(line_number), incoming)

    report = ET.Element("report", {"name": "manifest-batch-merge"})
    for package_name in sorted(packages):
        pkg_node = ET.SubElement(report, "package", {"name": package_name})
        for source_name in sorted(packages[package_name]):
            source_node = ET.SubElement(pkg_node, "sourcefile", {"name": source_name})
            for line_number in sorted(packages[package_name][source_name]):
                missed_instr, covered_instr, missed_branch, covered_branch = packages[package_name][source_name][line_number]
                ET.SubElement(
                    source_node,
                    "line",
                    {
                        "nr": str(line_number),
                        "mi": str(missed_instr),
                        "ci": str(covered_instr),
                        "mb": str(missed_branch),
                        "cb": str(covered_branch),
                    },
                )

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(report).write(output_path, encoding="utf-8", xml_declaration=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

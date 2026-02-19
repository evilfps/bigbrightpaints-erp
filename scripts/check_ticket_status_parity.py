#!/usr/bin/env python3
import argparse
import json
import re
import sys
from pathlib import Path
from typing import Optional


ACTIVE_STATUSES = {
    "planned",
    "in_progress",
    "in_review",
    "ready",
    "waiting_for_push",
    "pending_review",
    "checks_failed",
}
TERMINAL_STATUSES = {"completed", "done", "merged", "blocked"}
COMPLETED_STATUSES = {"completed", "done", "merged"}

TICKET_STATUS_RE = re.compile(r"^status:\s*([A-Za-z0-9_-]+)\s*$", re.MULTILINE)
SLICE_STATUS_RE = re.compile(r"^  status:\s*([A-Za-z0-9_-]+)\s*$", re.MULTILINE)
SUMMARY_STATUS_RE = re.compile(r"^- status:\s*([A-Za-z0-9_-]+)\s*$", re.MULTILINE)
COMPLETED_MARKER_RE = re.compile(
    r"ticket marked (completed|done|merged)", re.IGNORECASE
)
BLOCKED_MARKER_RE = re.compile(r"ticket marked blocked", re.IGNORECASE)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Fail when ticket metadata status drifts from summary/timeline closure evidence."
        )
    )
    parser.add_argument("--tickets-root", default="tickets")
    parser.add_argument("--output", default="")
    return parser.parse_args()


def normalize_status(raw: Optional[str]) -> str:
    if not raw:
        return ""
    return raw.strip().strip("'\"").lower()


def find_first_status(pattern: re.Pattern[str], text: str) -> str:
    match = pattern.search(text)
    if not match:
        return ""
    return normalize_status(match.group(1))


def find_slice_statuses(text: str) -> list[str]:
    return [normalize_status(match.group(1)) for match in SLICE_STATUS_RE.finditer(text)]


def inspect_ticket(ticket_dir: Path) -> list[dict]:
    violations: list[dict] = []
    ticket_yaml = ticket_dir / "ticket.yaml"
    summary_md = ticket_dir / "SUMMARY.md"
    timeline_md = ticket_dir / "TIMELINE.md"

    ticket_status = ""
    summary_status = ""
    slice_statuses: list[str] = []

    ticket_text = ticket_yaml.read_text(encoding="utf-8")
    ticket_status = find_first_status(TICKET_STATUS_RE, ticket_text)
    slice_statuses = find_slice_statuses(ticket_text)
    if not ticket_status:
        violations.append(
            {
                "ticket": ticket_dir.name,
                "path": str(ticket_yaml),
                "reason": "top-level status missing in ticket.yaml",
            }
        )

    if not summary_md.exists():
        violations.append(
            {
                "ticket": ticket_dir.name,
                "path": str(summary_md),
                "reason": "missing SUMMARY.md",
            }
        )
    else:
        summary_text = summary_md.read_text(encoding="utf-8")
        summary_status = find_first_status(SUMMARY_STATUS_RE, summary_text)
        if not summary_status:
            violations.append(
                {
                    "ticket": ticket_dir.name,
                    "path": str(summary_md),
                    "reason": "status line missing in SUMMARY.md",
                }
            )
        if (
            "## Closure Evidence" in summary_text
            and ticket_status
            and ticket_status in ACTIVE_STATUSES
        ):
            violations.append(
                {
                    "ticket": ticket_dir.name,
                    "path": str(summary_md),
                    "reason": (
                        "SUMMARY.md has closure evidence while ticket.yaml status is active"
                    ),
                    "ticket_status": ticket_status,
                }
            )

    if ticket_status and summary_status and ticket_status != summary_status:
        violations.append(
            {
                "ticket": ticket_dir.name,
                "path": str(summary_md),
                "reason": "ticket.yaml status and SUMMARY.md status differ",
                "ticket_status": ticket_status,
                "summary_status": summary_status,
            }
        )

    if slice_statuses and all(s in TERMINAL_STATUSES for s in slice_statuses):
        if ticket_status in ACTIVE_STATUSES:
            violations.append(
                {
                    "ticket": ticket_dir.name,
                    "path": str(ticket_yaml),
                    "reason": (
                        "all slice statuses are terminal but ticket.yaml status is still active"
                    ),
                    "ticket_status": ticket_status,
                    "slice_statuses": sorted(set(slice_statuses)),
                }
            )

    if timeline_md.exists():
        timeline_text = timeline_md.read_text(encoding="utf-8")
        if COMPLETED_MARKER_RE.search(timeline_text):
            if ticket_status and ticket_status not in COMPLETED_STATUSES:
                violations.append(
                    {
                        "ticket": ticket_dir.name,
                        "path": str(timeline_md),
                        "reason": (
                            "timeline marks completed/done/merged but ticket.yaml status is not terminal-complete"
                        ),
                        "ticket_status": ticket_status,
                    }
                )
        if BLOCKED_MARKER_RE.search(timeline_text):
            if ticket_status and ticket_status != "blocked":
                violations.append(
                    {
                        "ticket": ticket_dir.name,
                        "path": str(timeline_md),
                        "reason": "timeline marks blocked but ticket.yaml status is not blocked",
                        "ticket_status": ticket_status,
                    }
                )

    return violations


def main() -> int:
    args = parse_args()
    tickets_root = Path(args.tickets_root)
    if not tickets_root.exists():
        summary = {
            "tickets_root": str(tickets_root),
            "tickets_scanned": 0,
            "violations": [],
            "passes": True,
        }
        print("[check_ticket_status_parity] summary:")
        print(json.dumps(summary, indent=2))
        return 0

    violations: list[dict] = []
    skipped_without_ticket_yaml: list[str] = []
    ticket_dirs = sorted(
        path
        for path in tickets_root.glob("TKT-ERP-STAGE-*")
        if path.is_dir()
    )
    for ticket_dir in ticket_dirs:
        if not (ticket_dir / "ticket.yaml").exists():
            skipped_without_ticket_yaml.append(ticket_dir.name)
            continue
        violations.extend(inspect_ticket(ticket_dir))

    summary = {
        "tickets_root": str(tickets_root),
        "tickets_scanned": len(ticket_dirs),
        "tickets_checked": len(ticket_dirs) - len(skipped_without_ticket_yaml),
        "tickets_skipped_without_ticket_yaml": skipped_without_ticket_yaml,
        "violations": violations,
        "passes": len(violations) == 0,
    }

    print("[check_ticket_status_parity] summary:")
    print(json.dumps(summary, indent=2))

    if args.output:
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(summary, indent=2), encoding="utf-8")

    return 0 if summary["passes"] else 1


if __name__ == "__main__":
    sys.exit(main())

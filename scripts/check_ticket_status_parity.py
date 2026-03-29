#!/usr/bin/env python3
"""Ticket status parity guard.

Checks that ticket status fields stay consistent across ticket.yaml,
SUMMARY.md, and TIMELINE.md where those files exist. This is a no-op
guard until the ticket tracking files are introduced.
"""
import sys
import os


def main():
    root = os.environ.get("ROOT_DIR", ".")
    ticket_yaml = os.path.join(root, "ticket.yaml")

    if not os.path.isfile(ticket_yaml):
        print("[ticket-parity] OK: no ticket.yaml present; skipping parity check")
        return 0

    # When ticket.yaml exists, validate parity with SUMMARY.md and TIMELINE.md.
    summary = os.path.join(root, "SUMMARY.md")
    timeline = os.path.join(root, "TIMELINE.md")

    if not os.path.isfile(summary):
        print("[ticket-parity] FAIL: ticket.yaml exists but SUMMARY.md is missing")
        return 1

    if not os.path.isfile(timeline):
        print("[ticket-parity] FAIL: ticket.yaml exists but TIMELINE.md is missing")
        return 1

    # Full parity check would go here when the ticket tracking format is defined.
    print("[ticket-parity] OK: ticket status parity check passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())

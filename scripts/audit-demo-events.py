#!/usr/bin/env python3
"""Audit AdsDemoAudit JSON lines from logcat; exits nonzero for incomplete/invalid sessions.

Usage: adb logcat -d -s AdsDemoAudit:I > events.log
       python3 scripts/audit-demo-events.py events.log [other.log ...]
Capture each process once; overlapping dumps intentionally count as duplicates.
"""
import collections
import json
import sys

sessions = collections.defaultdict(list)
for path in sys.argv[1:]:
    with open(path) as source:
        for line in source:
            if "AdsDemoAudit" not in line or "{" not in line:
                continue
            event = json.loads(line[line.index("{"):])
            if event["event"] in {"ad_position", "ad_impression", "ad_show_fail", "ad_bid_result", "ad_close", "ad_paid", "ad_reward_earned"}:
                sessions[event["session_id"]].append(event)
if not sessions:
    sys.exit("No show sessions found")
rows = collections.defaultdict(collections.Counter)
errors = []
for session_id, events in sessions.items():
    first = events[0]
    counts = collections.Counter(e["event"] for e in events)
    row = rows[(first["mediation_mode"], first["ad_type"])]
    row.update(counts)
    if counts["ad_position"] != 1 or counts["ad_impression"] + counts["ad_show_fail"] != 1:
        errors.append({"session_id": session_id, "error": "position != exactly one impression or failure", "counts": dict(counts)})
    if first["event"] != "ad_position":
        errors.append({"session_id": session_id, "error": "position not first"})
    for event in events:
        if any(event.get(key) != first.get(key) for key in ("mediation_mode", "ad_type", "ad_platform", "position", "ad_unit_id", "number")):
            errors.append({"session_id": session_id, "error": "session dimensions changed"})
    bids = [e for e in events if e["event"] == "ad_bid_result"]
    if len(bids) > 1:
        errors.append({"session_id": session_id, "error": "duplicate bid"})
    for bid in bids:
        candidates = [c for c in bid["candidates"] if c["available"]]
        order = bid["eligible_ad_types"].split(",")
        candidates.sort(key=lambda c: (c["price_usd"] is not None, c["price_usd"] or 0, -order.index(c["format"]), c["platform"] == "admob"), reverse=True)
        expected = candidates[0] if candidates else None
        if expected:
            if (bid["winner_platform"], bid["winner_format"], bid.get("winning_value")) != (expected["platform"], expected["format"], expected["price_usd"]):
                errors.append({"session_id": session_id, "error": "winner inconsistent with candidates"})
        elif bid["winner_platform"] != "none":
            errors.append({"session_id": session_id, "error": "winner without candidate"})
report = {"sessions": len(sessions), "groups": [{"mode": mode, "format": fmt, **dict(counts)} for (mode, fmt), counts in sorted(rows.items())], "errors": errors}
print(json.dumps(report, indent=2, ensure_ascii=False))
sys.exit(bool(errors))

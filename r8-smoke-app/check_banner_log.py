"""Check one cold-load slot from `adb logcat -v time`; screenshots are still required."""
import re
import sys
from pathlib import Path

if len(sys.argv) != 3:
    raise SystemExit("usage: python3 r8-smoke-app/check_banner_log.py LOG SLOT_ID")

events = [
    dict(re.findall(r"(\w+)=(\S+)", line))
    for line in dict.fromkeys(Path(sys.argv[1]).read_text().splitlines())
    if "ad_type=banner " in line and f"slot_id={sys.argv[2]} " in line
]

def named(name):
    return [event for event in events if event.get("ad_event") == name]

for name in ("ad_position", "ad_load", "ad_loaded"):
    assert len(named(name)) == 1, f"expected one {name}, got {len(named(name))}"
request = named("ad_load")[0]["request_id"]
result = named("ad_loaded")[0]
assert result.get("result") == "filled", "first load failed; later SDK recovery is not a first-load pass"
assert result.get("request_id") == request, "load result belongs to another request"
impressions = [e for e in named("ad_impression") if e.get("request_id") == request]
assert len(impressions) == 1, "initial revenue-bearing impression missing or duplicated"
assert not named("ad_paid"), "removed ad_paid event was emitted"
assert impressions[0]["response_id"] == result["response_id"], "response identity mismatch"
assert float(impressions[0]["value"]) >= 0, "impression has no valid revenue"
assert re.fullmatch(r"[A-Z]{3}", impressions[0].get("currency", "")), "impression has no valid currency"
assert not named("ad_show_fail"), "display failure recorded"
print("PASS: one first load, one attributed revenue-bearing impression; no display failure")

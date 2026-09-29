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

for name in ("ad_position", "ad_load_request", "ad_load_result"):
    assert len(named(name)) == 1, f"expected one {name}, got {len(named(name))}"
request = named("ad_load_request")[0]["request_id"]
result = named("ad_load_result")[0]
assert result.get("result") == "filled", "first load failed; later SDK recovery is not a first-load pass"
assert result.get("request_id") == request, "load result belongs to another request"
impressions = [e for e in named("ad_impression") if e.get("request_id") == request]
paid = [e for e in named("ad_paid") if e.get("request_id") == request]
assert len(impressions) == len(paid) == 1, "initial impression/paid missing or duplicated"
assert impressions[0]["session_id"] == paid[0]["session_id"], "display identity mismatch"
assert impressions[0]["response_id"] == paid[0]["response_id"] == result["response_id"], "response identity mismatch"
assert not named("ad_show_fail"), "display failure recorded"
print("PASS: one first load, one attributed impression and paid event; no display failure")

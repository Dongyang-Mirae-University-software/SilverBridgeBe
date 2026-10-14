#!/usr/bin/env python3
"""bandwidth_matrix 결과 요약(python3.9 호환). 인자: work 디렉터리 [기준 감지 결과 name(제한 없는 JPEG)]"""
import glob
import json
import os
import sys

work = sys.argv[1]
baseline = sys.argv[2] if len(sys.argv) > 2 else None


def first_danger(buckets):
    secs = sorted(b["sec"] for b in buckets if b["danger"])
    return secs[0] if secs else None


base_first = None
if baseline and os.path.exists("%s/result_%s.json" % (work, baseline)):
    base_first = first_danger(json.load(open("%s/result_%s.json" % (work, baseline)))["analysis"])
print("기준(제한 없음) 첫 danger: %s초" % base_first)
print("%-26s %5s %6s %6s %7s %8s %8s %8s %9s %7s" % (
    "name", "sent", "skip", "recv", "effFps", "latP50", "latP95", "latMax", "AIgapMax", "danger1"))
rows = []
for path in glob.glob(os.path.join(work, "result_bw_*.json")):
    r = json.load(open(path))
    sender, received = r["sender"], r["received"]
    lat = sender.get("latencyMs", {})
    skipped = sender.get("skipped", 0)
    rows.append((r["name"], sender["sent"], skipped, received.get("frames"),
                 round(received.get("frames", 0) / max(1.0, float(r["seconds"])), 1),
                 lat.get("p50"), lat.get("p95"), lat.get("max"),
                 (received.get("gapMs") or {}).get("max"), first_danger(r["analysis"])))
for row in sorted(rows, key=lambda x: (x[0].split("_")[2], -int(x[0].split("cap")[-1].replace(".0", "")), x[0])):
    print("%-26s %5s %6s %6s %7s %8s %8s %8s %9s %7s" % row)

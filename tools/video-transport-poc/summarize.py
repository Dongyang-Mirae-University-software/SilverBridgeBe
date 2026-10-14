#!/usr/bin/env python3
"""run_matrix.sh 결과 요약. harness/work 의 result_*.json·window_*.json·cpu_*.csv 를 읽어 표로 낸다(python3.9 호환)."""
import csv
import glob
import json
import os
import sys

work = sys.argv[1] if len(sys.argv) > 1 else "harness/work"


def cpu_in_window(name):
    path = os.path.join(work, "cpu_%s.csv" % name)
    win = os.path.join(work, "window_%s.json" % name)
    if not (os.path.exists(path) and os.path.exists(win)):
        return None
    window = json.load(open(win))
    start, end = window.get("start"), window.get("end")
    if not end:
        return None
    be, ai = [], []
    for row in csv.DictReader(open(path)):
        t = float(row["t"])
        if start <= t <= end:
            be.append(float(row["be_cpu"] or 0))
            ai.append(float(row["ai_cpu"] or 0))
    if not be:
        return None
    return {"samples": len(be), "be": sum(be) / len(be), "ai": sum(ai) / len(ai), "beMax": max(be), "aiMax": max(ai)}


def idle():
    path = os.path.join(work, "cpu_idle.csv")
    if not os.path.exists(path):
        return None
    rows = list(csv.DictReader(open(path)))
    if not rows:
        return None
    return {"be": sum(float(r["be_cpu"] or 0) for r in rows) / len(rows), "ai": sum(float(r["ai_cpu"] or 0) for r in rows) / len(rows)}


base = idle()
print("idle:", base)
print("%-22s %9s %7s %9s %8s %8s %8s %8s %7s %7s %7s" % (
    "name", "B/s sent", "frames", "gapMax", "gapP99", "PSNRavg", "PSNRp05", "PSNRmin", "BEcpu", "AIcpu", "n"))
for path in sorted(glob.glob(os.path.join(work, "result_*.json"))):
    r = json.load(open(path))
    name = r["name"]
    cpu = cpu_in_window(name) or {}
    gap = r["received"].get("gapMs", {})
    q = r.get("quality", {})
    print("%-22s %9s %7s %9s %8s %8s %8s %8s %7s %7s %7s" % (
        name, r.get("bytesPerSecondSent"), r["received"].get("frames"), gap.get("max"), gap.get("p99"),
        q.get("psnrMean"), q.get("psnrP05"), q.get("psnrMin"),
        "%.0f%%" % cpu["be"] if cpu else "-", "%.0f%%" % cpu["ai"] if cpu else "-", cpu.get("samples", "-")))

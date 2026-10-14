#!/usr/bin/env python3
"""감지 일치 비교: 같은 장면을 JPEG 경로와 H.264 경로로 보낸 결과(result_*.json 의 analysis 1초 구간)를 맞대어 본다.

기준 = JPEG 결과. 지표(장면별):
  구간 수, danger 일치율, 대표 종류 일치율, 신뢰도 차이(중앙값·p95), JPEG 위험 구간 중 놓친 수(누락),
  H.264 에서만 위험인 구간 수(추가), 첫 danger 전환 시각 차(초).
사용: python3 parity.py <work 디렉터리> <기준 name> <비교 name> [<비교 name> ...]
"""
import json
import statistics
import sys

work = sys.argv[1]
base_name = sys.argv[2]


def load(name):
    r = json.load(open("%s/result_%s.json" % (work, name)))
    return {b["sec"]: b for b in r["analysis"]}, r


def first_danger(buckets):
    secs = [s for s, b in sorted(buckets.items()) if b["danger"]]
    return secs[0] if secs else None


base, base_result = load(base_name)
print("기준 %s: 구간 %d, 첫 danger %s초, 수신 프레임 %s" % (
    base_name, len(base), first_danger(base), base_result["received"].get("frames")))
for name in sys.argv[3:]:
    other, result = load(name)
    common = sorted(set(base) & set(other))
    # 양 끝 구간은 장수가 적어 제외한다(시작·종료 시점 경계 효과)
    inner = [s for s in common if base[s]["n"] >= 8 and other[s]["n"] >= 8]
    if not inner:
        print(name, "비교 가능한 구간 없음")
        continue
    agree_danger = sum(1 for s in inner if base[s]["danger"] == other[s]["danger"])
    agree_type = sum(1 for s in inner if base[s]["type"] == other[s]["type"])
    diffs = sorted(abs(base[s]["maxConf"] - other[s]["maxConf"]) for s in inner)
    missed = [s for s in inner if base[s]["danger"] and not other[s]["danger"]]
    extra = [s for s in inner if (not base[s]["danger"]) and other[s]["danger"]]
    fd_base, fd_other = first_danger(base), first_danger(other)
    print("%-26s 구간 %3d | danger 일치 %5.1f%% | 종류 일치 %5.1f%% | 신뢰도차 중앙 %.3f p95 %.3f | 누락 %d 추가 %d | 첫 danger %s -> %s (차 %s초) | 전송 %s B/s" % (
        name, len(inner), 100.0 * agree_danger / len(inner), 100.0 * agree_type / len(inner),
        statistics.median(diffs), diffs[min(len(diffs) - 1, int(len(diffs) * 0.95))], len(missed), len(extra),
        fd_base, fd_other, (fd_other - fd_base) if (fd_base is not None and fd_other is not None) else "-",
        result.get("bytesPerSecondSent")))

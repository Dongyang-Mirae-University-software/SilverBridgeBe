# 낙상 클립 구간 분리 (2026-10-07)

## 배경
- 낙상은 AI에서 6초 유지돼야 `danger`가 된다(`FALL_HOLD_SEC=6`). BE는 모든 종류에 감지 시점 기준 앞 3초 + 뒤 2초를 요청해, 낙상 클립에는 이미 누워 있는 장면만 남고 넘어지는 순간이 빠졌다.
- AI는 앞 구간 상한 5 → 8, 합계 상한 8 → 10으로 올리는 PR①을 따로 진행한다.

## 변경 (마이그레이션 없음)
- `anomaly.clip.fall-pre-seconds`(`ANOMALY_CLIP_FALL_PRE_SECONDS`, 기본 3) / `fall-post-seconds`(`ANOMALY_CLIP_FALL_POST_SECONDS`, 기본 2) 추가. **기본값이 기존과 같아 AI 반영 전에 배포해도 동작이 같다.**
- `AnomalyProperties.Clip.preSecondsFor/postSecondsFor(DetectedType)`: 낙상만 낙상 값, 나머지는 기존 `pre/post`.
- `AiClipClient.requestClip(sessionId, detectedAt, detectedType)`: 종류를 받아 요청 본문 구간을 고른다. `AnomalyClipCaptureService`가 `event.detectedType()`을 넘긴다.
- 낙상 값은 AI 새 계약 범위(pre 0~8, post 0~3, 합계 1~10)로 **잘라서** 쓴다(`effectiveRetentionDays`와 같은 방식, 기동을 막지 않음). 기존 `pre/post`는 검증을 새로 넣지 않았다.

## 바뀌지 않는 것
클립 쿨다운·상황당 12개·저장·열람·삭제·판정 숨김·AI 호출 시간 제한 25초.
- 시간 제한 영향 없음: AI는 뒤 구간을 기다린 뒤 인코딩하고 처리 상한은 요청 수신부터 20초다. 낙상 뒤 구간이 2→1초로 줄면 대기는 오히려 짧아진다. 파일은 5초 → 9초분이라 FHD 기준 약 1.9MB → 약 3.4MB(10MB 상한 안).

## 운영 절차
1. AI PR①(앞 상한 8·합계 10)이 해당 서버가 붙는 AI에 반영됐는지 확인한다.
2. 각 서버 `.env.dev`에 `ANOMALY_CLIP_FALL_PRE_SECONDS=8`, `ANOMALY_CLIP_FALL_POST_SECONDS=1` 추가 후 `docker compose -f docker-compose.dev.yml up -d api`(restart 아님).
- ⚠️ AI 반영 전에 올리면 AI가 422로 거절하고 BE는 재시도하지 않아 **낙상 클립이 생기지 않는다**.

## 테스트
`AiClipClientTest` - 기본값이면 전 종류 3/2 / 낙상만 8/1·화재·흉기 3/2 / 낙상 범위 밖 값 clamp. `AnomalyClipCaptureServiceTest`는 종류 인자 반영.

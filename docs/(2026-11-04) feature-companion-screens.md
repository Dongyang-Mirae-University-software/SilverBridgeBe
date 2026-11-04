# 안부 말벗 화면 API - PR-B (2026-11-04)

> 노션 카드 "AI 기분 체크 조회 API"(보호자)·"AI 통계 집계 API"(관리자)·"알림 발송과 설정"(주의 알림·끄기).
> 디자인: Claude Design `SilverBridge-app.html` 보호자 웹 "AI 기분 체크", 관리자 웹 "AI 통계 > AI 안부 말벗·정서 분석"·대시보드 카드.
> 정책 `.claude/rules/policy-companion.md`. PR-A는 `(2026-11-04) feature-companion-relay.md`.

## 1. API

| 경로 | 역할 | 내용 |
|---|---|---|
| `GET /api/guardian/ward/{wardId}/companion/today` | GUARDIAN | 지금 기분(`current`), 오늘 대답한 대화 수, 다음 시간대, 시간대 5개(질문·상태·기분), 확인할 일(`attention`) |
| `GET /api/admin/ai-stats/companion` | ADMIN | 오늘 질문 수·응답률·대화 없는 어르신(수·목록)·알림 보낸 수·기분 분포·이어지는 어르신 |
| `GET·PUT /api/guardian/notification-type-setting` | GUARDIAN | `companionNotice`(끌 수 있음, 기본 true) 추가, `companionRisk`(필수) 그대로 |

기분 값 `JOY·CALM·WORRY·DEPRESSED`, null = 판단할 수 없음("확인할 수 없어요"). 시간대 상태 `UPCOMING·AVAILABLE·IN_PROGRESS·DONE·MISSED`.

## 2. 디자인과 다른 점 (FE 전달)

| 디자인 | 실제 |
|---|---|
| 시간대마다 어르신 대답 원문 | 없음(원문 미저장, 결정 B). 한 줄 요약도 지금은 null |
| 저녁 7시 | 18:00 - 응답 값 그대로 표시 |
| 예시 질문 문장 | 응답 `slots[].question` 그대로 |
| 관리자 "AI 서비스: 안부 말벗·기분 분석 정상" | 이번에 넣지 않음(말벗 AI 미배포, 결정 6) |

## 3. 규칙

- 하루 기분·연속 일수: `CompanionMoodDays`(마지막 판정 대화, 대화 없는 날 끊김, 기준일 오늘/어제).
- 주의 알림 `COMPANION_NOTICE`: 3일째부터 날마다 하루 1번, FCM만, 설정 기반, 일반 풀, 끈 보호자 제외. 문구 "○○님 안부 대화에서 걱정스럽거나 가라앉은 기분이 N일째 이어지고 있어요. 안부 전화를 드려 보세요."
- 보호자 조회: ACTIVE 연결 + 연결 이후 대화만, 남의 피보호자 403 `COMPANION_NOT_AUTHORIZED`.
- 관리자 응답률 = 보낸 안내 중 대답이 있는 (피보호자, 시간대) 비율, 안내 없으면 null. 알림 보낸 수 = 주의 알림 + 위험 알림(오늘).
- 질문 안내 대상 `*` = 이용 중인 모든 피보호자(기본은 허용 목록).

## 4. DB (V66, 가역)

`companion_notice_log`(ward_id·notice_date UNIQUE, mood WORRY/DEPRESSED, days) · `idx_companion_session_date` · `guardian_notification_preference.companion_notice_enabled`(기본 true).

## 5. 테스트

단위: `CompanionMoodDaysTest`·`CompanionGuardianServiceTest`·`CompanionStatsServiceTest`·`CompanionNoticeListenerTest` + 가드 갱신(설정 요청 키·관리자 기타 탭·설정 참조처·executor·원문 로그·V66 CHECK). 통합: `CompanionFullIntegrationTest`에 주의 알림 하루 1번·보호자 조회 권한 추가.

## 6. 남은 것

말벗 AI 배포 뒤 `.env.dev`·실연동 확인, AI 서비스 상태 칸, 피보호자 질문 안내 끄기 설정(그 뒤 `*` 기본화), PR-C(챗봇 마이크 STT).

# 영향 범위 점검 - 말벗 대화 내용 저장(동의·30일)·보호자 대화 보기·테스트 전체 공개 (2026-11-12)

- 템플릿 C(영향 범위). 대상: PR #476 `bee640ea`(점검 시점 dev `0f0e94ce` - 이후 커밋은 문서뿐, src 변경 0).
- 점검만 했다(코드 수정 없음). 빌드·단위 2591건·vkcs 통합 165건은 PR #476 기록 그대로.

## PHASE 0. 변경점과 불변식

| 변경 | 내용 |
|---|---|
| 정책 | 원문 미저장(결정 B) → **피보호자 동의 시에만** 말벗 문장·대답 글 저장, 30일 뒤 비움, 철회 즉시 비움 |
| 새 표 | `companion_turn`(턴 기분 항상 + 글 동의 시), `companion_transcript_consent` |
| 새 API | 피보호자 동의 GET/PUT, 보호자 `week`·`days/{date}`·`sessions/{id}`, today·days 칸 `sessionId` |
| 스위치 | `COMPANION_TEST_ALL_WARDS` - gosky `.env.dev`에 켜짐(2026-11-11 23:35) |

불변식
- **I-1** 대화 글은 `companion_turn` 두 칸 밖으로 나가지 않는다 - 보호자 `sessions/{id}` 응답만 예외(ACTIVE 연결·연결 이후·7일·지금 동의 있음).
- **I-2** 계정 생명주기의 모든 종료 경로(탈퇴·스윕·강제 탈퇴·역할 변경·동의 철회·30일)에서 글이 남지 않는다.
- **I-3** 테스트 전체 공개는 피보호자에게만 적용되고 다른 역할·알림 정책을 바꾸지 않는다.

## PHASE A·B. 사용처 전수와 판정

### 1. 글이 나갈 수 있는 경로 (I-1)

| 경로 | 위치 | 판정 | 근거 |
|---|---|---|---|
| 새 엔티티·표 사용처 | `domain/companion` 10개 파일만 | PASS | 관리자·다른 도메인·`global`에 `CompanionTurn`·`companion_turn`·동의 표 사용처 0 |
| API 로그 | `ApiLoggingAspect:43,48,56` | PASS | 메서드·URI·IP·오류 코드만, 본문·반환값 없음 |
| SQL 바인딩 로그 | `application.yaml:22` `show-sql: false`, logback 파일 없음 | PASS | 두 서버 `.env.dev`의 `LOGGING_LEVEL*`·SQL 관련 키 0개(값 미출력 확인) |
| 말벗 도메인 로그 | 전 파일 | PASS | `CompanionTranscriptLogGuardTest`(원문 변수 금지)·`LogRawExceptionGuardTest`(새 파일 4개) |
| 알림 문구·data | `CompanionRiskNotificationListener`·`CompanionNoticeListener`·누적 | PASS | 고정 문구·일수만, 턴 표를 읽지 않음 |
| 관리자 집계·감사 로그 | `CompanionStatsService`·`AdminAuditLogService` | PASS | 턴 표를 읽지 않음 |
| WS 이벤트 | - | 해당없음 | 말벗은 WS 발행 없음 |
| 보호자 응답 | `CompanionGuardianService.session` | PASS | 동의가 지금 없으면 남은 글이 있어도 싣지 않음, `Cache-Control: no-store` |
| **문서·고지 문구** | `SwaggerConfig:87`, `CompanionDtos:52·55`, `WardCompanionController:150·170`, `CompanionService:50`, `CompanionGuardianDtos:9` | **FAIL (M-1)** | "대답·응답 문장·받아 적은 글은 저장하지 않습니다"가 남음 - 동의한 피보호자는 저장된다 |
| DB 백업 | - | 해당없음 | BE DB 정기 덤프 없음(문서상 일회성 덤프만) - 생기면 30일 보관과 맞춰야 함 |

### 2. 계정 생명주기 × 동의·글 (I-2)

| 경로 | 판정 | 근거 |
|---|---|---|
| 본인 탈퇴 → purge(hard delete) | PASS | users → 동의 행 CASCADE, users → session → turn CASCADE(통합 테스트로 회원 행 삭제 검증) |
| 관리자 강제 탈퇴 `forceWithdraw` | PASS | 같은 파이프라인 |
| 스윕 purge(INACTIVE 잔존) | PASS | 같은 hard delete |
| 탈퇴 진행 중(최대 약 20분) 보호자 조회 | PASS(수용) | 연결 해제 리스너가 끊는다. 리스너가 실패하면(best-effort) purge 전까지 보일 수 있다 - 기분 화면과 같은 기존 한계 |
| 역할 변경 피보호자 → 보호자 | PASS | `clearOnRoleChange`가 동의 행 + 대화 삭제(턴 CASCADE), 통합 테스트 |
| 이용 제한 | 해당없음 | 피보호자는 이용 제한 금지(400), 정지 보호자는 토큰 무효화 |
| 연결 해제·재연결·강제 연결 | PASS | 세 API 모두 `findActiveConnectedAt` + `startedAt` 기준(D-1), 단위 테스트 |
| 동의 철회 | PASS | 동의 행 DELETE → 글 UPDATE NULL, `FOR SHARE` 경합 통합 테스트 |
| 30일 | PASS | 04:55, 부분 인덱스 조건과 쿼리 조건 일치 |

### 3. 잠금 순서 (Fable 판단)

| 트랜잭션 | 잡는 순서 |
|---|---|
| 턴 저장 `recordTurn` | 대화 행 UPDATE → 동의 행 `FOR SHARE` (Hibernate 7.2 `NativeQueryImpl.shouldFlush`: JPA 부트스트랩이면 native 쿼리 전에 전체 flush) |
| 역할 변경 `clearOnRoleChange` | 동의 행 DELETE → 대화 DELETE |
| 동의 철회 | 동의 행 DELETE → 턴 UPDATE |
| 탈퇴 purge | users DELETE → CASCADE(동의·대화 순서는 DB가 정함) |

→ 턴 저장과 역할 변경(또는 purge)이 같은 피보호자에게 겹치면 **순서가 반대라 교착**이 생길 수 있다(**L-1**). 철회·30일 정리와는 순환이 없다.

### 4. 테스트 전체 공개 (I-3)

| 사용처 | 판정 | 근거 |
|---|---|---|
| `isTestWard` 4곳(`today.testMode`·`openTest`·`sendTestPrompt`·`analysis`) | PASS | 역할은 `WardCompanionController` `hasRole('WARD')`, 보호자·관리자 403 |
| 테스트 대화 반복 × 위험 알림 | **주의 (L-2)** | 위험 알림은 대화당 1번이지만 테스트 대화는 분 20·시간 200 한도 안에서 몇 번이든 열 수 있다. 강제 푸시 + 대체 문자(과금)가 모든 피보호자에게 열렸다(기존 L-5가 시험 계정 → 전체로 넓어짐) |
| 테스트 안내 푸시 | PASS | 자기에게만, 분 3·시간 30 |
| 관리자 집계 | PASS(의도) | 테스트 대화 포함은 기존 결정 |

## 이슈

| 등급 | ID | 내용 | 제안 |
|---|---|---|---|
| 🟡 | **M-1** | 공개 Swagger(gosky 무인증)·피보호자 API 설명·DTO에 "대답·응답 문장·받아 적은 글은 저장하지 않습니다"가 남음. 동의 화면과 어긋나는 고지라 FE가 이 문구를 안내에 쓰면 동의 받을 때 사실과 다른 고지가 된다(개인정보 보호법 제22조·제23조 고지 사항) | 6곳을 "동의한 경우에만 저장, 30일 뒤 삭제, 철회 시 즉시 삭제"로 고치고, Swagger 문구를 고정하는 테스트(`CompanionTranscriptApiDocTest`)를 추가 |
| 🟢 | **L-1** | 턴 저장(대화 행 → 동의 행)과 역할 변경(동의 행 → 대화 행)·purge의 잠금 순서 역전 - 동시에 오면 PostgreSQL이 한쪽을 교착으로 끊는다. 턴 쪽이 끊기면 그 턴 기록(위험 알림 선점 포함)이 롤백되지만, 그 피보호자는 보호자로 바뀌거나 탈퇴하는 중이라 대화도 지워진다. 역할 변경 쪽이 끊기면 관리자 500 → 재시도 | `recordTurn` 첫 줄에서 동의 행을 먼저 잠근다(순서 통일: 동의 → 대화). 두 트랜잭션 경합 통합 테스트 |
| 🟢 | **L-2** | 테스트 전체 공개 중 반복 테스트 대화로 위험 알림(강제 푸시 + 대체 문자)을 여러 번 만들 수 있다 | 수용(gosky dev QA 기간 한정, 정책 문서 L-5에 이미 기록). QA 끝나면 스위치 끄기. 필요하면 테스트 대화의 위험 알림을 보호자당 하루 N건으로 제한 |
| 🟢 | **L-3** | 동의 화면 고지 4항목(목적·항목·보유기간·거부권과 불이익)은 FE 몫 - API는 `retentionDays`만 준다 | FE 전달(보고에만 정리) |
| 문서 | D-1 | `policy-auth.md` D-2 보관 기간 목록에 말벗 대화 내용 30일이 없다 | 이번 점검 커밋에서 정정 |

## PHASE C. 테스트로 고정할 것

- M-1: Swagger·`@Operation` 설명에 "저장하지 않" + (대답|응답 문장|받아 적은) 조합이 없도록 하는 문서 테스트.
- L-1: 턴 저장 트랜잭션이 동의 행을 대화 행보다 먼저 잠그는지 - 두 트랜잭션 경합 통합 테스트(역할 변경과 겹쳐도 둘 다 성공).

## 종합 판정

**조건부 PASS** · 🔴 0 · 🟠 0 · 🟡 1 · 🟢 3 · 문서 1(정정). 글이 저장소 밖으로 나가는 실제 경로와 생명주기 정리 누락은 0이다. M-1(고지 문구)은 동의 화면이 붙기 전에 고친다.

## 수정용 커밋 메시지 초안

```
fix: 말벗 저장 고지 문구를 동의 조건부로 정정하고 턴 저장 잠금 순서를 동의 → 대화로 통일

- Swagger·피보호자 API 설명·DTO의 "저장하지 않습니다"를 "동의한 경우에만 저장, 30일 뒤 삭제, 철회 시 즉시 삭제"로 (M-1)
- recordTurn이 동의 행을 먼저 FOR SHARE로 잠가 역할 변경·purge와 잠금 순서를 맞춤 (L-1)
- 문구 고정 테스트·경합 통합 테스트 추가
```

## 조치 (2026-11-12, `fix/companion-transcript-audit`)

| ID | 조치 | 고정 |
|---|---|---|
| M-1 | Swagger 말벗 태그·피보호자 턴 API 설명 2곳·`TurnResponse` 2칸·DTO/서비스/엔티티 주석을 "동의한 경우에만 저장, 30일 뒤 삭제, 철회 시 즉시 삭제(음성·사진은 저장 안 함)"로 정정 | `CompanionTranscriptApiDocTest`(문구 2건) |
| L-1 | `recordTurn`이 대화 행을 바꾸기 전에 `lockConsent`로 동의 행을 먼저 잠금 - 순서 동의 → 대화(역할 변경 정리와 같음) | `CompanionSessionWriterTest.consentLockedBeforeSessionChange`(잠글 때 대화 미변경 + 호출 순서) |
| L-2 | 테스트 대화 위험 알림 피보호자당 하루 1번(`claimTestRiskNotification` 조건부 UPDATE), 일반 대화 불변 | 단위 2건 + `CompanionFullIntegrationTest.테스트_대화_위험_알림은_하루_한_번`(실 DB) |
| L-3 | 동의 응답에 고지 4항목 `notice` 추가(FE가 그대로 표시) | `CompanionTranscriptServiceTest.noticeItems` |

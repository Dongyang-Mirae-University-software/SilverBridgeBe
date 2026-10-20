# 챗 중계 wardId 검증 점검 (2026-10-20)

> 템플릿 B(기능 점검), 보안·인가 중심. **점검만 했고 코드는 수정하지 않았다**(변이 테스트의 임시 수정은 모두 되돌림).
> 대상: BE PR #356 (`2b779ed`) + 연관 AI #23·#24 / FE #81 / 예약 #1 배포 상태. 배경 `(2026-10-20) fix-chat-reservation-security.md`.

## 종합 판정: **PASS** (🔴 0 · 🟠 0 · 🟡 1 · 🟢 5)

wardId 위조로 남의 예약 계정을 쓰는 경로는 막혔다(코드·단위 테스트·실서버 확인). 남은 것은 로그 위생(🟡)과 방어 보강(🟢)이다.

## PHASE -1 환경
- 날짜 2026-10-20 / 대상 `2b779ed`(#356) / 점검 시점 `origin/dev` = `5743e6f` / `./gradlew build` 통과(#356 PR 당시 전체 통과, 이번 점검의 변이 테스트에서 채팅 테스트 재실행) / 마이그레이션 최신 V61(이번 변경 없음)
- 반영: vkcs·gosky 모두 BE `2b779ed`, 둘 다 healthy, AI 구독 연결됨

## PHASE 0 대상과 엔드포인트 × 역할

| 엔드포인트 | 허용 역할 | 비고 |
|---|---|---|
| `POST /api/guardian/chat` | GUARDIAN | `sendAsGuardian`: `context.wardId`를 ACTIVE 연결로 검증 |
| `GET /api/guardian/chat/logs`, `/logs/{chatId}` | GUARDIAN | 토큰 ID로만 조회 |
| `POST /api/ward/chat` | WARD | `send`: 대리 키 3종 제거 |
| `GET /api/ward/chat/logs`, `/logs/{chatId}` | WARD | 토큰 ID로만 조회 |
| (관리자) | 없음 | 두 컨트롤러 모두 클래스 레벨 `@PreAuthorize`, ADMIN 403 |

파일: `chat/{controller,service,client,dto,config,listener}` (`ChatRelayService`, `Guardian/WardChatController`, `AiChatClient`, `ChatSlots`, `ChatLogProjection`, `AiMemberDataPurgeListener`).
`context`가 AI까지 가는 길: FE `ChatbotContent` → `ChatRelayRequest.context`(Map, 20항목·8KB) → `ChatRelayService.sanitizedContext` → AI `ChatContext`(알려진 필드만 사용, 모르는 키는 pydantic이 버림).

## PHASE A 보안·인가

| 시나리오 | 결과 | 근거 |
|---|---|---|
| 보호자 → 연결 없는 wardId | **PASS** 403 `CHAT_WARD_NOT_CONNECTED`, AI 미호출 | 단위 테스트 + **실서버**(gosky 시험 계정) 403 + `[IDOR-ATTEMPT]` 로그 |
| PENDING / REFUSED / DISCONNECTED / 탈퇴 직후 | **PASS**(코드) | `isActiveConnection`이 `StatusIn(ACTIVE)`만 true. 실 DB 통합 테스트는 없음(L-4) |
| wardId 타입 이상(숫자) | **PASS** 400 | 실서버 확인 |
| 공백 wardId | **PASS** 본인 상담, 대리 키 모두 제거 | 단위 테스트 |
| wardName·wardPhone 위조(ACTIVE 연결) | **PASS** | **실서버**: 위조 이름·전화를 보내고 AI 상담 기록 context를 대조 -> 위조 값 없음, DB 이름 있음 |
| 피보호자 경로의 대리 키 | **PASS** 제거 | 단위 테스트 |
| 관리자 토큰 | **PASS** 403 | 컨트롤러 보안 테스트 |
| 상담 본문·전화번호 로그 노출 | **PASS** | `ChatRelayLogGuardTest` 통과, IDOR 로그는 ID만 |
| 변이 테스트 | **PASS** | (1) 연결 검사 제거 -> 6건 중 3건 실패, (2) 대리 키 제거 로직 삭제 -> 2건 실패. 모두 되돌림 |

IDOR 결과: **PASS**.

## 이슈

### 🟡 M-1 `[IDOR-ATTEMPT]` 로그에 클라이언트가 보낸 wardId 원문을 남긴다
- `log.warn("... guardianId={}, wardId={}", ...)`의 wardId는 `context`에서 온 임의 문자열이다. 길이 제한은 `context` 전체 8KB뿐이고, 콘솔 로그 패턴이 평문(`%msg%n`)이라 줄바꿈 등이 걸러지지 않는다 -> **로그 위조·한 줄 8KB 로그 폭주**가 가능하다. 사용자 ID는 6자(`users.id VARCHAR(6)`)라 정상 값은 짧다.
- 영향: 인가 우회는 아니다. 로그 신뢰도·저장 비용 문제.
- 제안: 검증 전에 wardId 형식(영숫자 6자 이내)을 보고, 틀리면 원문 없이 `CHAT_INVALID_REQUEST`(또는 같은 403)로 거절 / 로그에는 길이 상한·제어문자 제거. (이 코드베이스의 다른 `[IDOR-ATTEMPT]`는 숫자 경로 변수라 같은 문제가 없다.)

### 🟢 L-1 속도 제한이 wardId 확인 뒤에 걸린다
- `sendAsGuardian`이 `resolveWard`(DB 조회 + WARN 로그)를 먼저 하고, `rateLimitService.check`는 `relay` 안에서 나중에 건다. 위조 wardId 요청은 전송 한도에 걸리지 않고 계속 403이 된다(모두 같은 응답이라 존재 여부 열거는 불가능). 전역 사용자 속도 제한이 있어 영향은 작다.
- 제안: 한도 확인을 `resolveWard` 앞으로. M-1과 같이 수정하면 된다.

### 🟢 L-2 클라이언트가 보낸 `name`·`phone` 등은 그대로 AI에 간다
- BE가 덮어쓰는 것은 `ward*` 3키뿐이다. FE는 `name`·`phone`·`birthDate` 등도 함께 보내고, AI는 `wardName`이 있으면 `name`을 덮어쓰지만 **`users.phone`이 NULL인 피보호자**는 BE가 `wardPhone`을 생략해 클라이언트의 `phone`이 예약 환자 연락처로 남는다(`users.phone`은 nullable).
- 영향: 같은 피보호자(ACTIVE 연결)에 한한 데이터 정합성. 계정 간 접근은 아님. 수용 가능, 필요하면 대리 상담 시 `name`·`phone`도 BE가 정하거나 제거.

### 🟢 L-3 연결이 AI 호출 도중 끝나는 경합
- 연결 확인은 요청 시점에 한 번이고 AI 호출은 최대 130초다. 그 사이 해제되면 마지막 예약이 만들어질 수 있다. SOS·복약 등 "요청 시점 ACTIVE" 정책과 같다. 수용.

### 🟢 L-4 `isActiveConnection`의 상태 필터를 실 DB로 검증하는 테스트가 없다
- 채팅 테스트는 `ConnectionService`를 목으로 대체한다. 파생 쿼리가 단순해 위험은 낮지만 PENDING/REFUSED/DISCONNECTED가 false임을 통합 테스트(`src/integrationTest`, vkcs)로 고정하면 다른 도메인(SOS·복약·이상감지)의 인가도 함께 보호된다.

### 🟢 L-5 `sendAsGuardian`과 `relay`가 활성화 확인·`validate`를 두 번 한다
- 동작은 같고 중복일 뿐이다. 정리해도 되고 두어도 된다.

## PHASE B 기능 정합성
- FE(`ChatbotContent`)는 `useGuardianActiveWards`가 준 ACTIVE 피보호자의 `partnerUserId`만 `wardId`로 보낸다 -> 정상 화면은 403을 만나지 않는다. FE가 보내는 `patientId`·`patientName` 등은 AI 스키마에 없어 무시된다.
- 실서버: ACTIVE 피보호자 대리 상담 200, AI 기록 context에 DB 이름만 있음. wardPhone이 없는 피보호자(L-2)는 시험 계정에 해당 데이터가 없어 확인하지 못함.

## PHASE C 구조·계약
- Swagger에 `CHAT_WARD_NOT_CONNECTED(403)`을 반영했다(컨트롤러 설명). FE 전달: 403 `CHAT_WARD_NOT_CONNECTED`일 때 "연결된 피보호자만 선택할 수 있어요" 안내(정상 화면에서는 나오지 않음).
- AI 서버는 BE 중계를 거친 `wardId`만 신뢰하는 구조다(수용 사항, 기존 문서와 동일).

## PHASE D 테스트
- 신규 `ChatRelayWardContextTest` 6건이 핵심 인가 케이스를 덮는다. 변이 테스트 2종을 모두 잡는다.
- 빠진 것: 실제 HTTP 직렬화로 403 본문(`code`)을 확인하는 컨트롤러 테스트, 통합 테스트(L-4), wardPhone NULL 케이스(L-2), M-1 로그 형식 테스트.

## 실서버에서 한 것 (시험 계정, 값 출력 없음)
1. 연결 없는 wardId 403, 숫자 wardId 400
2. ACTIVE 피보호자 대리 상담 1건 -> **시험 계정 상담 기록 1건 남음**("안녕하세요"). AI 기록의 context를 조회해 참/거짓만 확인

## 조치 제안 (수정은 별도 승인 후)
| 이슈 | 제안 | 규모 |
|---|---|---|
| M-1 + L-1 | `wardId` 형식 검증을 `resolveWard` 맨 앞에, 속도 제한을 그 앞으로, 로그는 상한·제어문자 제거 | 작음 (서비스 한 곳 + 테스트 2건) |
| L-4 | 연결 상태별 `isActiveConnection` 통합 테스트 | 작음 (vkcs 실행) |
| L-2 | 대리 상담 시 `name`·`phone`도 BE가 정함 | 선택 |

# 관리자 대시보드 집계 (PR ③)

> 2026-09-02 · 근거 문서: `docs/(2026-08-31) api-contract-anomaly-dashboard.md` §5
> 선행: PR ①(#236, V44 판정 스키마) · PR ②(#237, V45 보호자 응답·재촉)
> **마이그레이션 없음** - 전부 기존 테이블 집계다.

---

## 1. 무엇을 만들었나

관리자 콘솔의 두 탭에 대응하는 집계 API 2개.

| 엔드포인트 | 탭 | 내용 |
|---|---|---|
| `GET /api/admin/dashboard/safety` | 안전 현황 | AI 연결 상태 · 카메라 · 보호 사각지대 · 오늘 이상감지 |
| `GET /api/admin/dashboard/operation` | 운영 현황 | 회원 · 가입 추이 · 연결 · 문의 · 처리 대기 |

**탭마다 엔드포인트를 나눈 이유**: 한쪽 탭만 열어도 다른 쪽 쿼리가 돌지 않게 하기 위해서다.

이 기능은 오래 막혀 있었다. 2026-08-07 대시보드 재설계 때 **AI 오탐률 카드를 넣으려다 폐기**했는데, 오탐 판정을 기록하는 경로가 아예 없어 분자를 만들 데이터가 없었기 때문이다. PR ①②로 `anomaly_incident.review_status`와 보호자 응답이 생기면서 이제 그 지표를 만들 수 있게 됐다.

---

## 2. 이 PR이 지키는 정책

### 모르는 값을 0으로 채우지 않는다 (핵심)

| 값 | 언제 null | 왜 |
|---|---|---|
| `streamingCameras` | AI 미연결 | 0을 내려보내면 **우리 수신기 장애가 현장 카메라 전멸로** 표시된다 |
| `safetyEvents.disconnectedCameras` | AI 미연결 | 같은 이유 |
| `unansweredInquiries.longestWaitingHours` | 대기 문의 0건 | 0시간이면 "방금 들어온 문의가 있다"와 구분되지 않는다 |

그 외 모든 필드는 데이터가 0건이어도 **키가 항상 존재**한다(0 또는 빈 배열). 프론트에서 존재 여부를 분기할 필요가 없다.

### 오탐률은 응답률과 함께

`todayAnomaly.review`는 `pending`·`real`·`falseAlarm`·`conflicted` **네 값을 모두** 내린다. 응답 수는 `total - pending`이므로 프론트가 이렇게 쓸 수 있다.

> **"응답 3건 중 오탐 1건 (전체 5건, 응답률 60%)"**

오탐 건수만 단독으로 띄우면 분모가 거짓이 된다. 재촉을 해도 응답률은 100%가 되지 않기 때문이다. SOS 처리결과(ACK)가 전건 NULL인 채 "무응답 100%"로 읽힐 뻔한 함정과 같다.

### 0건인 유형은 항목을 만들지 않는다

`byType`에는 **실제로 집계된 유형만** 담긴다. "낙상 0건"은 안전하다는 뜻이 아니라 **AI 모델이 없다**는 뜻이라, 숫자로 보여주면 정확히 반대로 읽힌다.

### 날짜는 항상 KST

"오늘 가입", "오늘 문의", "오늘 이상감지", 가입 추이 모두 `AdminDashboardClock`(Asia/Seoul) 기준이다. 서버가 UTC로 돌면 09:00(KST) 이전 데이터가 전날로 밀려 **아침마다 지표가 되돌아간다**.

### 그 밖

- **서버 캐시 없음.** 폴링 30초 이상 권장. 캐시를 넣으면 "방금 처리했는데 안 줄어든다"가 생겨 관리자가 화면을 믿지 못한다.
- **조회를 감사 로그에 남기지 않는다.** 집계 숫자만 반환해 개인 식별 정보가 없고, 폴링 화면이라 기록하면 공지 수정 같은 실제 조작 이력이 묻힌다. (개인 이력을 열람하는 PR ④ 관리자 이상감지 로그는 그때 별도로 남긴다.)
- **`CONFLICTED`는 `safetyEvents`에 넣지 않는다.** 관리자는 이상감지 로그 페이지의 상태 필터로 본다. 오늘치 건수는 `todayAnomaly.review.conflicted`로 확인된다.
- 회원 수는 **ADMIN 제외 + `Status.ACTIVE`만**. 운영자는 서비스 이용자가 아니고, 탈퇴는 hard delete라 INACTIVE는 purge 실패 잔여물뿐이다.

---

## 3. 착수 전 확인한 것 (PHASE 0)

계약 문서가 당연하게 적어 둔 필드 중 **근거가 없는 것이 있었다.**

🔴 **`Camera` 엔티티에 스트리밍 상태 컬럼이 없다.** 필드는 `id·wardId·sessionId·deviceId·label·registeredBy·isActive`뿐이고, `isActive`는 사용자가 켜고 끄는 **등록 플래그**이지 실시간 연결 상태가 아니다. 즉 "지금 몇 대가 스트리밍 중인가"를 DB만으로는 답할 수 없다.

**택한 방법**: `AiLiveStreamSubscriber`의 구독 집합에서 파생한다. 구독에는 **AI가 라이브로 보고했고 우리 `camera` 테이블에도 등록된** 세션만 들어가므로, 그 크기가 곧 "스트리밍이 잡히는 등록 카메라 대수"다. 단 AI가 끊기면 그 집합은 "0대"가 아니라 **"알 수 없음"**이므로 `null`로 내린다.

*대안이었던 것*: `camera.last_seen_at` 컬럼 추가(V46) + AI 신호 수신 시 갱신. 더 정확하지만 이번 범위를 넘어선다. 스트리밍 상태를 이력으로 남길 필요가 생기면 그때 검토한다.

그 밖에 확인한 것:
- 옛 `/api/admin/dashboard/*`는 실제로 남아 있지 않았다(2026-05-19 삭제, 문서 주장과 일치)
- `/api/admin/**` → `hasRole("ADMIN")` 경로 규칙이 `SecurityConfig:107`에 있다
- `InquiryRepository.countByStatus`는 이미 있어 재사용했다
- `DetectedType`은 `FIRE`·`SMOKE`만 `isDetectable()`이라 `byType`은 자연히 그 둘만 나온다

---

## 4. 변경 파일

**신규**

| 파일 | 역할 |
|---|---|
| `admin/controller/AdminDashboardController.java` | 엔드포인트 2개 |
| `admin/service/AdminDashboardService.java` | 집계 조립 |
| `admin/service/AdminDashboardClock.java` | KST 날짜 경계 |
| `admin/dto/AdminSafetyDashboardResponse.java` | 안전 현황 응답 |
| `admin/dto/AdminOperationDashboardResponse.java` | 운영 현황 응답 |

**수정**

| 파일 | 변경 |
|---|---|
| `anomaly/client/AiLiveStreamSubscriber.java` | `isConnected()`·`subscribedSessionCount()` 조회 접근자 추가 (구독 로직 무변경) |
| `user/repository/UserRepository.java` | 회원 수·가입 추이·사각지대 집계 5종 |
| `connection/repository/ConnectionRepository.java` | 상태별 수, 방치된 요청 수 |
| `camera/repository/CameraRepository.java` | 카메라 보유 피보호자 수 |
| `inquiry/repository/InquiryRepository.java` | 오늘 문의 수, 최장 대기 문의 시각 |
| `anomaly/repository/AnomalyIncidentRepository.java` | 특정 시각 이후 시작된 상황 조회 |

**건드리지 않은 것**: 보호자·피보호자 API, 재촉 스케줄러, 알림 발송 경로, 기존 마이그레이션.

### 계획에서 하나 달라진 점

PHASE 1에서는 "기존 admin 컨트롤러 관례대로 경로 규칙(`SecurityConfig`)에만 맡긴다"고 했으나, 구현 중 **클래스 레벨 `@PreAuthorize("hasRole('ADMIN')")`를 추가**했다. 경로 규칙은 컨트롤러 밖에 있어 **"관리자 아닌 역할 차단"을 테스트로 고정할 수 없고**, 나중에 경로 패턴이 바뀌면 조용히 열린다. 경로 규칙은 그대로 살아 있으며 조이기만 한다.

---

## 5. 테스트

`./gradlew build` **전체 480건 통과 / 실패 0** (기존 461 + 신규 19)

| 테스트 | 고정하는 정책 |
|---|---|
| `AdminDashboardClockTest` | UTC 시각이어도 KST 날짜로 판정 / 자정 직전은 전날 / 하루 시작 = KST 00:00 |
| `AdminDashboardServiceTest$AiState` | AI 미연결이면 스트리밍·끊김이 **null** / 그래도 나머지 집계는 정상 응답 / 끊김 = 등록 - 구독 / 음수 방지 |
| `AdminDashboardServiceTest$TodayAnomaly` | 0건 유형은 항목 없음(낙상·흉기 미포함) / 판정 4종 집계 / 0건이면 total 0 + 빈 배열 |
| `AdminDashboardServiceTest$Operation` | 대기 문의 없으면 대기시간 **null** / 최장 대기시간 / 가입 추이 7일 채움·정렬 / 대기 연결 수 일관성 |
| `AdminDashboardControllerSecurityTest` | ADMIN 허용, GUARDIAN·WARD는 403 |

---

## 6. 검증 가이드

```bash
# 관리자 토큰으로
curl -H "Authorization: Bearer <ADMIN_TOKEN>" https://api.devdmu.gosky.kr/api/admin/dashboard/safety
curl -H "Authorization: Bearer <ADMIN_TOKEN>" https://api.devdmu.gosky.kr/api/admin/dashboard/operation
```

**확인 포인트**
- AI가 끊긴 상태에서 호출 → `aiConnected: false`, `streamingCameras: null`, 나머지는 정상 숫자 (500이 아님)
- 이상감지 상황이 0건인 날 → `todayAnomaly.total: 0`, `byType: []` (키는 존재)
- 관리자 아닌 토큰 → 403

---

## 7. 남은 것

- **PR ④ 관리자 이상감지 로그 + 정정 (V46)** - `resolvedBy`를 채우는 경로가 여기서 생긴다. 현재는 아무도 채울 수 없어 `CONFLICTED`가 그대로 남는다.
- 프론트 대시보드 화면 연동. 특히 오탐률은 **반드시 응답률과 함께** 그릴 것(§2).
- `camera.last_seen_at` 도입 여부 - 스트리밍 상태를 이력으로 남겨야 할 필요가 생기면.

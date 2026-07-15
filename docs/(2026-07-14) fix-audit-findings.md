# 점검 지적사항 일괄 수정 + 카카오 알림톡 채널

> 작업 2026-07-14 · 근거: `docs/(2026-07-14) audit-sos-to-anomaly-cumulative.md`
> 테스트: `./gradlew test` **295건 통과 / 실패 0** (기존 278 + 신규 17)

## 1. 보안 정책 변경 — IDOR 응답을 "404 위장"에서 "403 명시 안내"로

**요구**: 사용자 친화적으로 — 무슨 일이 일어났는지 직관적으로 보여줄 것.

| | 이전 | 이후 |
|---|---|---|
| 타인 카메라 접근 | 404 "카메라를 찾을 수 없습니다"(위장) | **403 "본인이 등록한 카메라만 사용할 수 있습니다."** |
| 타인 문의 접근 | 404 "문의를 찾을 수 없습니다"(위장) | **403 "본인이 작성한 문의만 볼 수 있습니다."** |
| 없는 자원 | 404 | 404 (변화 없음) |

- **트레이드오프(수용)**: "그 id의 자원이 존재한다"는 사실이 드러난다. 대신 **내용은 일절 주지 않고**(방 이름·세션ID·문의 본문 비노출), 시도는 `[IDOR-ATTEMPT]` WARN으로 남긴다. 비밀번호 재설정(2026-05-23)과 같은 판단 — 시니어 UX 우선.
- 정책은 `.claude/rules/domain-security-policy.md`에 기록(불변 규칙: 403 응답에 소유자·내용 정보를 싣지 말 것).

## 2. 🟠 H-1 — AI 세션 구독이 복구되지 않던 문제 (조용한 침묵)

`AiLiveStreamSubscriber`가 `subscribedSessions`를 WS 재연결 시에만 비워, 두 경로에서 이상감지가 **에러 없이 0건**이 됐다.

**수정**
1. **구독 재동기화** — `live_streams` 목록을 받을 때 목록에 없는 세션은 구독 기록에서 제거(`retainAll`). 카메라의 `session_id`는 영속이라, iPad가 같은 sessionId로 재접속하면 이제 다시 subscribe한다.
2. **카메라 등록 시 목록 재요청** — `CameraRegisteredEvent`(camera 도메인) → 구독자가 `{"action":"list"}` 재전송. AI는 세션 생성·종료 시에만 목록을 broadcast하므로, "스트리밍이 먼저 시작된 뒤 카메라 등록" 순서에서도 구독된다.
3. (L-1) subscribe 페이로드를 문자열 연결이 아닌 `ObjectMapper` 직렬화로.

**테스트**(신규 `AiLiveStreamSubscriberTest`): 등록 세션만 구독 / 중복 subscribe 안 함 / **목록에서 사라졌다 같은 id로 돌아오면 재구독** / 카메라 등록 시 목록 재요청.

## 3. 🟡 M-1 — STOMP 리스너 NPE (WS 접속 감사 로그 유실)

`StompEventListener.handleConnect/handleDisconnect`가 `getSessionAttributes()` null을 방어하지 않아 **접속·해제마다 NPE**(gosky 운영 로그에서 실물 확인). null-safe 조회로 수정(없으면 `unknown`) — 같은 패키지의 `StompSubscriptionAuthorizationInterceptor`와 동일 패턴.

## 4. 🟡 M-2 / M-3 · 🟢 L-2 / L-3

- **M-2**: `CameraControllerSecurityTest` 신설 — WARD 전용(등록·수정·삭제) / GUARDIAN 전용(연결 피보호자 카메라 조회) 인가를 AOP 레벨에서 고정(SOS 권한 테스트와 동일 패턴).
- **M-3**: `/api/guardian/cameras` → **`/api/guardian/camera`** (단수형 통일). FE 호출처 없음 확인 — 프론트는 라이브 스트림을 AI에 직접 붙는다.
- **L-2**: 관리자 문의 검색 키워드의 LIKE 와일드카드(`%`·`_`) 이스케이프 + 쿼리에 `ESCAPE '\'`. (인젝션이 아니라 검색 정확도 문제였다)
- **L-3**: `AnomalyNotificationCooldownTest` 신설 — 본인 1분/보호자 5분 TTL 분기, 쿨다운 차단, Redis 장애 fail-open.

## 5. 카카오 알림톡 채널 (카카오톡 채팅으로 도착)

### 왜 알림톡인가 (푸시가 아니라)
"카카오톡 채팅으로 뜨는 알림"은 **알림톡**이 유일한 현실적 경로다. 수신자를 **전화번호**로 식별하므로 카카오 로그인·친구 추가가 필요 없다(우리 사용자 전원에게 가능). 검토했다 접은 대안:
- **카카오 푸시 알림**(kapi `/v2/push/*`): 카카오톡이 아니라 **우리 앱 푸시(FCM/APNs 대행)** — 기존 FCM 직접 발송과 도착지가 같아 중복 알림만 늘고 이득이 없다.
- **카카오톡 메시지 API**(친구톡): 발신자·수신자가 **카카오 친구**여야 하고 광고성 분류·야간 발송 제한 — 시니어 대상 긴급 알림에 부적합.

이미 SMS로 쓰는 **Solapi 계정·SDK를 그대로** 사용한다(발신 프로필만 연결 — 신규 벤더 계약 불필요).

### 자유 문구는 불가 — 승인 템플릿 + 변수
알림톡은 **사전 심사에서 승인된 문구만** 나가고 서버는 `#{변수}`만 채운다(전체가 변수인 템플릿은 심사 반려). 상황이 달라지는 이상감지는 **골격을 고정하고 값만 변수로** 빼서 표현한다.

| | 값 |
|---|---|
| 발신 프로필(pfId) | `KA01PF240930145539248iUN6bVyplGB` |
| 템플릿 ID | `KA01TP260715015020754dXeU0ww3my9` |
| 상태 | **검수중**(2026-07-15 등록, 카카오 검수 1~3영업일) |
| 본문 | `[CareAI] 이상 감지` \ `#{wardName}님 #{location}에서 #{detectedTypeLabel}이(가) 감지되었습니다.` |
| 변수 | `wardName` · `location` · `detectedTypeLabel` — 알림 `data`의 동명 키에서 자동 바인딩(코드 일치 확인됨) |

- **선택 채널**: `ALIMTALK_ENABLED=false`(기본)이거나 승인 템플릿이 없으면 **조용히 스킵** → 지금 배포해도 **동작 변화 없음**.
- **SMS 대체발송 OFF**: 코드에서 `disableSms=true`, Solapi 콘솔에서도 꺼둘 것(문자 미선택자에게 문자 나가는 것 이중 차단).
- **승인 후 켜는 법**: `.env.dev`에
  ```
  ALIMTALK_ENABLED=true
  ALIMTALK_PF_ID=KA01PF240930145539248iUN6bVyplGB
  ALIMTALK_TEMPLATE_ANOMALY=KA01TP260715015020754dXeU0ww3my9
  ```

## 6. 변경 파일

**신규**: `camera/event/CameraRegisteredEvent` · `notification/config/AlimtalkProperties` · `notification/service/AlimtalkSender` · `notification/channel/KakaoAlimtalkNotificationChannel` · 테스트 4종(`CameraControllerSecurityTest`, `AnomalyNotificationCooldownTest`, `AiLiveStreamSubscriberTest`, `KakaoAlimtalkNotificationChannelTest`)

**수정**: `ErrorCode`(403 코드 2종) · `CameraService`(403+WARN, 등록 이벤트 발행) · `InquiryService`(403+WARN) · `AiLiveStreamSubscriber`(재동기화·재요청·직렬화) · `StompEventListener`(null-safe) · `GuardianCameraController`(단수형) · `InquiryRepository`/`AdminInquiryService`(LIKE 이스케이프) · `AnomalyNotificationListener`(알림톡 변수용 data 확장) · `application.yaml`(알림톡 설정) · 기존 테스트 3종(정책 변경 반영)

**마이그레이션 없음** (채널 enum은 `KAKAO_ALIMTALK` 기존 값 유지 — 이관 불필요).

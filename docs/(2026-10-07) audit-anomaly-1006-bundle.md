# 이상감지 10/6 변경 묶음 기능 점검 + 시연 날 기록 누락 조사 (2026-10-07, 템플릿 B)

- 범위: #315(문자 대체) · #320(흉기·낙상 라이브 활성화) · #322(보호자 안내 문구) · 10/5 #306(ANOM-G09)·#305(문자 폴백 상한) 중 이상감지 관련. 낙상 클립 구간 분리(`feature/fall-clip-window`)는 다른 세션 작업이라 **미포함**(머지 후 추가 점검).
- 사전 확인: 최신 마이그레이션 V58 / `./gradlew build -x test` 통과 / 관련 단위 테스트(anomaly·notification·global.enums) 68클래스 456건 통과.
- 방식: 별도 worktree, 코드 수정 없음. PHASE E는 gosky DB SELECT만(쓰기 없음).

## 종합 판정

| 구분 | 결과 |
|---|---|
| 코드(PHASE 0~D) | 🔴 0 / 🟠 0 / 🟡 3 / 🟢 6 - **머지 상태 양호, 즉시 조치 필요 없음**. 결정 필요 사항 Y-1 |
| 기록 누락(PHASE E) | **지워짐**(확신도 높음). 코드 결함 아님, 앱 밖 직접 DELETE로 보임. 누가·어떻게는 **미확인(단정 안 함)** |
| 낙상·흉기 저장 실패 가능성 | 없음 - `detected_type`에 CHECK 없음(V17이 삭제, V30·V42·V44는 NOT NULL만) |

### 결정이 필요한 것
1. **Y-1** 알림 쿨다운을 "전달 0건"이면 풀지(SOS-G09 방식) - 알림 빈도와의 트레이드오프라 사용자 결정.
2. **Y-2** 이상감지 리스너·쿨다운 로그의 예외 원문 제거 - 제안: 수정 PR(`fix/anomaly-1006-audit`).
3. **Y-3** 늦은 접수 시 알림톡+문자 중복 - 수용 문구만 정책 문서에 기록할지.
4. PHASE E 재발 방지: 시연 전후 gosky DB에 직접 DELETE(E2E 정리 등)를 하지 않는 운영 규칙, 시연 데이터 보존이 필요하면 별도 백업.

---

# Part 1. 코드 점검 (PHASE 0 ~ D)

---

## PHASE 0. 변경 파일

| 커밋 | 주요 변경 파일(main) |
|---|---|
| 24c9b9f | `notification/dispatch/NotificationDispatcher.java`(FORCED_PUSH_PLUS_SETTINGS에 문자 대체), `NotificationType.java`(주석), `channel/NotificationContent.java`(`smsFallbackText` 필드), `anomaly/listener/AnomalyNotificationListener.java`(대체 문구), Swagger 설명 2건, `FcmService` 주석 |
| 890db92 | `global/enums/DetectedType.java`(isDetectable = FIRE·FALL·WEAPON), `anomaly/dto/DetectedTypeLabel.java`(`withSubjectParticle`), `AnomalyNotificationListener`(종류별 본인 문구), `AnomalyJudge`(주석), `AdminSafetyDashboardResponse`(주석) |
| 1db12e0·d0af0e1 | `AnomalyNotificationListener`(보호자 흉기·낙상 안내, 대체 문자 = 푸시 문구) |
| ae71d17 | `AnomalyReviewReminderPlanner`·`AnomalyReviewReminderLogRepository`(요약 보호자 단위 2시간 미루기) |
| 15dd184 | `SmsFallbackLimiter`·`SmsFallbackProperties`·`NotificationDispatcher`(상한)·`application.yaml` |

### 알림 종류 × 수신자 × 채널

| 종류 | 수신자 | WS | FCM | 알림톡 | 사용자 문자 | 문자 대체 |
|---|---|---|---|---|---|---|
| ANOMALY_DETECTED | 보호자(ACTIVE 전원) | `anomaly-detected` 항상(정지 계정은 WS 퍼블리셔가 차단) | 강제 | 설정 ON + 템플릿(`application.yaml:257`) | 설정 ON일 때 | 전 채널 미전달 + 켠 문자 미시도일 때만, 상한 30/h (키 `ANOMALY_DETECTED:{userId}`) |
| ANOMALY_DETECTED_SELF | 피보호자 본인 | 동일 | 강제 | 매핑 없음 -> NOT_APPLICABLE(스킵) | 설정 ON일 때 | 동일 (키 `ANOMALY_DETECTED_SELF:{userId}`) |
| ANOMALY_REVIEW_REQUIRED / _SUMMARY(같은 타입 사용) | 보호자 | - | 설정(allowed=FCM만) | 불가(allowed 제외) | 불가 | 없음(SETTINGS_ONLY 경로) |
| ANOMALY_REVIEW_CONFLICTED | 응답 보호자 | - | 설정(FCM만) | 불가 | 불가 | 없음 |

쿨다운: 이력 `anomaly:cooldown:{session}:{type}` 1분 / 알림 `anomaly:notify:{user}:{session}:{type}` 보호자 5분·본인 3분 / 클립 `anomaly:clip:{session}:{type}` 5분 - 전부 type 포함.

---

## PHASE A. 보안·인가

- A-1 ✅ 정지·탈퇴 수신자 차단이 문자 대체보다 먼저다. `NotificationDispatcher.java:112-113`에서 `FORCED_PUSH_PLUS_SETTINGS`는 `withReceivableRecipient(...)` 안에서만 `dispatchForcedPushPlusSettings` -> `smsFallback`(195행)을 부른다. 차단되면 리미터·SMS 모두 건드리지 않는다(`이용제한_이상감지도_미발송` 테스트로 고정).
- A-2 ✅ 본인 알림톡 미발송. `application.yaml:252-260` templates에 `ANOMALY_DETECTED`만 있음, `KakaoAlimtalkNotificationChannel.java:45-50`이 type으로 템플릿 조회 -> SELF는 NOT_APPLICABLE. 리스너는 본인에게 `ANOMALY_DETECTED_SELF`로 dispatch(`AnomalyNotificationListener.java:97-99`). 템플릿 본문은 `#{detectedTypeLabel}` 변수라 흉기·낙상도 문구 어긋남 없음(검수 시 예시값도 "낙상").
- A-3 ✅(디스패처) / 🟡(리스너·쿨다운, 아래 Y-2) 디스패처·리미터 로그는 userId·type·결과·예외 클래스명만. 이력(`notification_log`)에는 코드만, 본문은 DB에만.
- A-4 ✅ 낙상·흉기 추가가 IDOR 범위를 넓히지 않음. 보호자·피보호자 이력·클립 서비스는 `DetectedType`을 참조하지 않고(`grep DetectedType` 결과 목록에 guardian/ward/clip 서비스 없음), 종류로 분기하는 코드도 main에 없음(`DetectedType.FIRE|FALL|WEAPON` 직접 비교 0건). 인가는 기존 `isActiveConnection` 그대로.

## PHASE B. 기능 정합성

### B-1 문자 대체 조건 매트릭스 (`NotificationDispatcher.java:172-196`)

| FCM | 알림톡 | 사용자 문자 | 결과 |
|---|---|---|---|
| 전달 | 무관 | 무관 | 대체 없음 ✅ |
| 실패 | 전달 | OFF | 대체 없음(중복 금지) ✅ |
| 실패 | 실패 | OFF | 대체 발송 ✅ |
| 실패 | 매핑 없음(SELF)·enabled=false -> NOT_APPLICABLE | OFF | 대체 발송 ✅ |
| 실패 | 무관 | ON·전달 | DELIVERED, 대체 없음 ✅ |
| 실패 | 무관 | ON·실패(NO_PHONE 등) | 대체 없음(`targets.contains(SMS)`) ✅ |
| 실패 | 실패 | OFF + 상한 초과 | SMS RATE_LIMITED 1건 기록, 결과 FAILED ✅ |
| 실패 | 실패 | OFF + 대체 미접수 | `release()` 환불 ✅ |
| Redis 장애 | - | - | `tryAcquire` fail-open ✅ (`SmsFallbackLimiter.java:66-70`) |

리미터 키 = `notify:sms-fallback:{type}:{recipientId}` -> SOS와 별도 카운트 ✅. 중복 여지는 아래 Y-3(타임아웃 후 늦은 접수)만.

- B-2 ✅ 강제 FCM은 `EnumSet.of(FORCED_PUSH)` 후 `settingsChannels`(교집합)를 **더하는** 구조라 줄지 않음(174-175행, `허용채널이_강제FCM을_줄이지_못한다` 테스트).
- B-3 ✅ 세 쿨다운 키 모두 type 포함 -> 같은 세션의 화재·흉기가 서로 쿨다운을 먹지 않음. AI `latest_analysis`는 메시지당 detectedType 1개라 "동시 감지"는 프레임 교차로 오며, 교차해도 종류별로 각각 이력·알림이 생긴다.
- B-4 ✅ incident 묶음 `(ward, session, type)`(`AnomalyIncidentRepository:41`), 판정·재촉·클립에 종류 분기 없음. **"화재" 하드코딩 없음**: 런타임 문자열 grep 결과 이상감지 알림 제목 `"이상 상황 감지"`, 재촉 `"%s 감지가 있었습니다"`, 동수 `"%s 감지에 대해"`, 요약 `"이상감지가 %d건"`, 대체 문자·WS payload 모두 `DetectedTypeLabel` 경유. 남은 "화재"는 주석·Swagger 예시·카메라 `isActive` 설명뿐.
- B-5 ✅ **DB CHECK 문제 없음**. `detected_type`에 CHECK 제약이 없음 - V1의 `chk_anomaly_events_type`(FIRE·WEAPON·FALL 허용)은 V17이 테이블째 DROP, V30(`anomaly_events` 재생성)·V42/V44(`anomaly_incident`)는 `VARCHAR(20) NOT NULL`만, V53 주석도 "CHECK 없음" 명시. 엔티티 `@Enumerated(STRING) length=20`. 따라서 **FALL·WEAPON 저장 실패는 시연 기록 누락 원인 후보에서 제외**된다.
  - `fromAi`: fire/smoke->FIRE, knife/weapon->WEAPON, fall->FALL, 그 외·null->UNKNOWN(무시). 관리자 필터 `AdminAnomalyTypeFilter`(FIRE·FALL·WEAPON), 대시보드·요약 `byType`은 실제 집계 행만 merge -> 0건 항목 없음 유지.
- ae71d17 ✅ 빈 집합 가드(204행) 후 조회, 선점 기록을 남기지 않아 "미루기"로 동작. 문제 없음.

## PHASE C. 구조·계약

- ✅ 리스너 `@Async("urgentNotificationExecutor")` + AFTER_COMMIT 유지(`AnomalyNotificationListener.java:62-63`).
- ✅ `notification_log` 수신자당 1건: dispatch 1회 = `record` 1회(116행). 대체 성공 시 결과 `SMS_FALLBACK` 1행, 채널 시도 목록에 FCM 실패 + SMS 전달이 함께 담김. 모든 종류×상태 1건 가드 테스트(`모든_종류는_이력을_정확히_한번_남긴다`) 존재.
- ✅ `data["type"]`은 본인 수신분도 `ANOMALY_DETECTED` 유지(FE 계약 무변경).
- 🟡(미확인) `data["detectedType"]`·WS `anomaly-detected`·이력/클립 응답에 `FALL`·`WEAPON` 값이 새로 실린다. enum 값 자체는 DTO `allowableValues`에 이미 있었으나 FE가 실제로 두 값을 렌더링하는지는 BE 코드로 확인 불가 - FE 확인 필요(`detectedTypeLabel`이 함께 실리므로 FE가 라벨을 쓰면 안전).

## PHASE D. 테스트

있는 것: 디스패처 문자 대체 9건(푸시 실패 대체·본인 대체·푸시 성공·켠 문자 전달·켠 문자 실패·상한 초과·환불·재촉 제외·이력 SOS/이상감지), 리스너 종류별 푸시·대체 문구 고정(흉기·낙상·빈 값), `DetectedTypeTest`·`DetectedTypeLabelTest`·`AnomalyJudgeTest` 낙상·흉기, 알림톡 채널 SELF 미발송, 리미터 Redis 통합 테스트.

추가 제안(코드 작성 안 함):
1. 디스패처 테스트에 **알림톡 채널 mock이 없음**(`NotificationDispatcherTest.java:75` "KAKAO_ALIMTALK 미등록"). (a) FCM 실패 + 알림톡 전달 -> 대체 없음, (b) FCM 실패 + 알림톡 실패 -> 대체, (c) 알림톡 NOT_APPLICABLE(SELF) + FCM 실패 -> 대체 - 매트릭스의 핵심 두 행이 테스트로 고정돼 있지 않다.
2. `application.yaml`을 실제로 바인딩해 `templates`에 `ANOMALY_DETECTED_SELF`·`ANOMALY_REVIEW_*`·`MEDICATION_*` 키가 없음을 고정하는 테스트(현재 채널 테스트는 수동 구성 properties만 검증).
3. 통합 테스트: `AnomalyDetectionService.handle`로 FALL·WEAPON 신호가 `anomaly_event`·`anomaly_incident`에 실제 저장되고 종류별로 다른 incident가 열리는지(실 Postgres). 현재 CHECK는 없지만 향후 CHECK 추가 시 회귀 방지용.
4. 쿨다운 키 분리 테스트: 같은 세션의 FIRE 직후 WEAPON이 이력·알림 쿨다운에 막히지 않음.
5. `smsFallbackText` 길이 경계(LMS 전환) - 정책 결정 후.

---

## 이슈 목록

### 🟡 Y-1 알림 쿨다운이 "전달 0건"에도 남는다 (재시도 차단)
- 위치: `AnomalyNotificationListener.java:88-99`
- 근거: `cooldown.tryAcquire(...)` 선점 후 `dispatch` 결과(`NotificationLogResult`)를 보지 않는다. SOS는 전달 0명이면 쿨다운을 해제한다(SOS-G09).
- 시나리오: 보호자가 푸시 토큰 없음 + 대체 문자가 발송사 장애(PROVIDER_ERROR)·상한 초과로 실패 -> 이후 5분(본인 3분) 동안 같은 카메라·종류의 재감지에서 다시 시도하지 않는다. 이번에 문자 대체를 넣은 목적(아무 채널로도 못 받는 사람 구제)이 일시 장애 구간에서 약해진다.
- 제안: `dispatch` 반환값이 `isDelivered()==false`이고 사유가 차단(NOT_SENT)이 아니면 해당 수신자 알림 쿨다운 키를 지운다(SOS-G09와 같은 방식). 사용자 결정 사항(알림 반복 빈도와 트레이드오프).

### 🟡 Y-2 이상감지 리스너·쿨다운 로그가 예외 원문을 남긴다 (로그 원문 정책과 불일치)
- 위치: `AnomalyNotificationListener.java:103-104` (`log.error(..., e)` 스택 전체), `AnomalyNotificationCooldown.java:58-59` (`e.getMessage()`)
- 근거: 2026-10-06 전수 점검 정책 "새 로그에 `e.getMessage()`나 예외 객체를 넘기지 말 것", SOS 리스너·쿨다운은 이미 정리됨(`LogRawExceptionGuardTest` 대상). 이상감지 쪽 대칭 파일은 대상 목록에 없다.
- 시나리오: 이 try 블록 안에서 수신자 조회·설정 조회(DB) 예외가 올라오면 SQL 바인딩 값 등이 섞인 메시지가 stdout으로 간다. 개인정보(전화번호·본문)가 직접 실릴 경로는 확인되지 않아 위험은 낮다.
- 제안: SOS와 같이 예외 클래스명만 남기고 두 파일을 `LogRawExceptionGuardTest` 목록에 추가.

### 🟡 Y-3 발송사 시간 초과 후 늦은 접수 시 알림톡 + 대체 문자 중복 가능
- 위치: `NotificationDispatcher.java:187-195`, Solapi 10초 제한(`SolapiCallExecutor`)
- 근거: 알림톡이 10초를 넘겨 `PROVIDER_ERROR`로 기록되지만 실제로는 늦게 접수될 수 있다(정책 문서에 "늦게 접수된 건도 실패로 기록될 수 있다" 수용). 그 경우 대체 문자까지 나가 같은 사람에게 카카오톡 + 문자가 둘 다 간다. FCM도 응답 시간 초과 시 같은 구조.
- 제안: 수용 여부만 정책 문서 "이상감지 문자 대체" 절에 명시(코드 변경 불요 판단 가능).

### 🟢 G-1 문자 대체 문구가 SMS(90바이트)를 넘어 LMS로 나간다
- 위치: `AnomalyNotificationListener.java:157-175` 주석 "SMS 길이 안"
- 근거: 흉기 보호자 문구 "[실버브릿지] 홍길동님 댁 거실에서 흉기가 감지되었습니다. 바로 연락해 안전을 확인하고, 위험하면 112에 신고해 주세요."는 한글 약 60자(EUC-KR 120바이트 이상). `SmsSender`는 타입을 지정하지 않아 Solapi가 LMS로 자동 전환 -> 발송은 되지만 건당 비용이 늘고 주석과 사실이 다르다.
- 제안: 주석 정정 또는 문구 축약 결정.

### 🟢 G-2 이력 본문이 실제 대체 문자와 다르다
- 위치: `NotificationDispatcher.java:309-322` - `record`는 원래 `content.body()`(푸시 문구)를 저장, 실제 대체 문자는 `smsFallbackText`.
- 영향: 관리자 화면에서 "무슨 문자가 나갔는지"가 푸시 문구로 보인다. 흉기·낙상은 d0af0e1 이후 거의 같지만 화재·본인 문구는 다르다. 표시상 차이만.

### 🟢 G-3 리스너 `sent` 카운트는 "dispatch 호출 수"
- 위치: `AnomalyNotificationListener.java:100,108` - 전달 실패도 `발송=N건`으로 센다. 로그 해석 주의(이력은 정확).

### 🟢 G-4 정책 문서 문구 표기가 코드보다 뒤처짐
- `.claude/rules/domain-security-policy.md` "이상감지 알림 - 푸시가 안 가면 문자로 대신 보낸다" 절의 문구 예시는 보호자 "…감지. 앱에서 확인해 주세요." / 본인 "…안전한 곳으로 대피해 주세요."로 고정돼 있으나, 코드는 흉기·낙상에서 보호자 = 푸시 동일 문구(+연락·112 안내), 본인 낙상 = "도움이 필요하면 보호자에게 연락해 주세요."다. 문서 갱신 필요.

### 🟢 G-5 푸시 미전달 판정은 "FCM 접수" 기준
- 브라우저 알림 권한을 나중에 끈 사용자의 토큰이 유효하면 FCM이 접수(delivered)해 문자 대체가 나가지 않는다. 정책상 "발송 서버 접수 기준"이라 의도된 한계 - FE 쪽 토큰 해제(권한 철회 시)로만 보완 가능.

### 🟢 G-6 테스트 공백 (PHASE D 제안 1~4)

## 미확인
- FE가 `detectedType` = `FALL`·`WEAPON`을 화면·아이콘·필터에서 처리하는지(BE 코드로 확인 불가).
- AI가 낙상을 정확히 `fall` 문자열로 보내는지 - 문서(`docs/(2026-10-06) feature-anomaly-weapon-fall-live.md:41`)상 그렇다고 되어 있으나 실제 AI 페이로드는 미확인. 다른 문자열(`fallen` 등)이면 UNKNOWN으로 조용히 버려진다(로그 없음).
- 실제 배포 서버 DB의 `anomaly_event`/`anomaly_incident`에 수동 추가된 CHECK가 없는지(마이그레이션상으로는 없음).

---

# Part 2. 기록 누락 조사 (PHASE E)


## 결론: **지워짐** (확신도 높음). 저장 안 됨·번호만 비었음은 근거와 맞지 않는다.
- 앱 코드에는 anomaly_event를 지우는 경로가 없고, CASCADE 경로(피보호자 탈퇴·상황 삭제)도 해당 시각에 일어나지 않았다.
- 따라서 **앱 밖에서 직접 실행한 DELETE**로 보인다. 다만 누가 어떤 문장으로 지웠는지 보여 주는 기록(SQL 로그·히스토리)은 없다. 단정하지 않고 근거만 적는다.
- 삭제 시각 추정: **2026-10-06 21:48:32 ~ 21:50:17 KST**
  - 21:48:32 = 사라진 이벤트가 속한 상황 83의 마지막 감지 시각(그때까지는 행이 있었다)
  - 21:50:17 = anomaly_event의 마지막 autovacuum 시각(지금 dead tuple이 0)

## 환경
- 서버: gosky(ssh 정상 접속). 호스트 시각 KST, DB timezone `Etc/UTC`. 아래 시각은 모두 KST로 바꿔 적었다.
- 시연 서버: gosky다. 10/6 이상감지 알림 이력과 상황이 이 DB에 있다(vkcs는 조사하지 않음).
- DB 컨테이너 기동 시각: 10/2 15:01 KST. stats_reset은 NULL이라 통계는 그 이후 누적값이다.

## 1. anomaly_event
- 현재 15행, id **106~120**만 있다. id 1~105는 **전부** 없다(80~105만 빠진 것이 아니다).
- 시퀀스 `anomaly_events_id_seq`: last_value=120 (is_called=t)
- 106~120: 10/6 22:30:59~23:22:07, 피보호자 a9cC5f, 세션 4개, 모두 danger=t(WEAPON·FIRE·FALL)
- FK: `ward_id → users ON DELETE CASCADE`, `incident_id → anomaly_incident ON DELETE CASCADE`

## 2. 연관 테이블 흔적 (사라진 이벤트가 있었다는 증거)
- **anomaly_incident는 남아 있다**: 10/6 상황 59·62~69·73~83이 그대로 있고, event_count도 0보다 크다.
  - 73~83(test01·wDLVW1, 21:03~21:48)의 event_count 합계 = 25
  - 그런데 이 상황들을 가리키는 anomaly_event는 0행이다
  - 10/1·10/4 상황 29(21건)·30(4건)의 이벤트도 없다
  - 이벤트가 상황 CASCADE로 지워졌다면 상황도 함께 없어야 하므로, **anomaly_event만 따로 지워졌다**는 뜻이다
- **notification_log(ANOMALY_DETECTED/_SELF)도 남아 있다**: 21:03~21:48에 44건(id 744~787, DELIVERED)
  - 받는 사람: test01·wDLVW1 본인, 보호자 aB3x9A·gosky
  - 즉 **이벤트가 저장되고 알림까지 나간 뒤 지워졌다**(알림은 이력 커밋 뒤 AFTER_COMMIT 리스너에서만 발송된다)
  - notification_log에는 anomaly_event id를 가리키는 컬럼이 없다. 그래서 시각·ward로 대조했다
- **anomaly_clip**: 37(상황 82)·38(상황 83)이 남아 있고, 파일도 디스크에 있다(21:36·21:45). 클립 행도 이벤트와 함께 지워지지 않았다.
  - 39~45가 없는 것은 a9cC5f가 카메라를 여러 번 다시 등록했기 때문으로 보인다(카메라 삭제 → 클립 정리). 근거:
    - 남은 카메라는 62·63·67뿐이다
    - 지워진 세션(7Cyz·eFSa·4G8M)의 이벤트 106~118은 남아 있다 → 카메라 삭제는 이벤트를 지우지 않는다
- 개수 대조: 59~69·73~83의 event_count 합계 55, id 80~105 = 26개
  - 73~83만 보면 25로 1개 차이가 난다
  - identity 값은 롤백으로도 소비되므로, 1개 차이는 롤백이나 숫자 대조 오차일 수 있다(미확인)

## 3. 통계 (pg_stat_user_tables, 10/2 15:01 이후 누적)
- anomaly_event: n_tup_ins **94**, n_tup_del **101**, n_live 15, n_dead 0
  - last_autovacuum = last_autoanalyze = **10/6 21:50:17 KST**
  - 해석:
    - 기동 이후 삽입된 것은 id 27~120이다(120-94+1=27)
    - 삭제 101건 = 27~105의 79건 전부 + 그 이전 행 최대 22건 → 테이블을 통째로 비운 수준이다
    - TRUNCATE는 n_tup_del에 잡히지 않으므로 **DELETE 문**이다
- pg_stat_database(dev): xact_rollback 299 / commit 218,713, tup_deleted 3,426
- 참고(누적이라 시점은 모름): 같은 기간 users ins 614 / del 615, sos_event del 448, notification_log del 213, admin_audit_log del 157
  - admin_audit_log는 앱에 삭제 경로가 없는 테이블이다 → 이 DB에 직접 DELETE를 하는 작업(E2E 픽스처 정리 등)이 평소에 있다는 정황이다
  - admin_audit_log에 남은 e2ma5y 등 존재하지 않는 admin_id도 같은 정황이다

## 4. CASCADE 경로 확인
- 탈퇴: access_log WITHDRAW가 10/6 13:52·13:55·13:59·14:00에 있고, **14:00 이후는 0건**이다
- admin_audit_log: 10/6 기록은 09:34~13:59의 FORCE_CONNECT·USER_STATUS_CHANGE·USER_FORCE_DELETE(13:52, 13:59)뿐이다. 21시대는 0건
- 사라진 이벤트의 피보호자 test01·wDLVW1·a9cC5f는 **지금도 users에 ACTIVE로 있다** → 탈퇴 CASCADE가 아니다
- 상황 73~83이 남아 있다 → 상황 CASCADE도 아니다
- 카메라 삭제(`CameraDeletedEvent`)는 클립만 지운다(`AnomalyClipCleanupListener` → `deleteAllOfCameras`). 이벤트 106~118이 남아 있는 것과도 맞다

## 5. 앱 코드 경로 (worktree audit-anomaly-1006)
- `AnomalyEventRepository`: 메서드는 `findByWardIdOrderByCreatedAtDesc`뿐이다
  - 호출처는 `save`(AnomalyDetectionService)와 `findById`(AnomalyClipCaptureService) 둘뿐이다
- 이벤트를 지우는 코드가 없다:
  - anomaly 도메인의 `@Modifying`·`DELETE FROM`은 AnomalyClip·ConflictLog 대상뿐이다
  - JPA `@OneToMany`·`orphanRemoval`·`CascadeType`이 없다
  - 이벤트 보관 기간 정리 스케줄러가 없다(클립 정리·오탐 24시간 삭제는 anomaly_clip만 대상)
- 마이그레이션(V*.sql)에도 anomaly_event DELETE가 없다

## 6. 로그
- **API 로그는 남아 있지 않다**: 조사 중인 **10/7 10:12:25 KST에 dmu-dev-api가 재생성됐다**(PR #323 수동 배포로 보임). docker 로그가 초기화돼 10/6 `[ANOMALY-`·ROLLBACK·AiLiveStreamSubscriber 로그를 확인할 수 없다. 파일 로그 마운트도 없다(마운트는 /data/clips 하나).
- DB 로그(`log_statement=none`이라 DELETE 문은 남지 않는다): 10/6 0시 이후 checkpoint를 빼면 줄이 하나만 있다.
  - `2026-10-06 22:44:14 KST ERROR: column "login_id" does not exist / STATEMENT: select id, login_id from users where id in ('test01','wDLVW1','a9cC5f')`
  - 사람(또는 도구)이 시연 당일 밤 이 DB에 **직접 SQL로 접속했다**는 흔적이다. 대상도 사라진 이벤트의 피보호자 3명이다. 삭제 시각(21:50 전후)과는 약 54분 차이가 나고, 이 질의 자체는 SELECT다.
  - 21:48~21:50 checkpoint에는 오류가 없다(DELETE는 오류가 나지 않으면 로그에 남지 않는다)
- 셸 히스토리: gosky `/root/.bash_history`에 dmu-dev-db 대상 DELETE는 없다(다른 프로젝트 DB의 DELETE만 있음). 컨테이너 안 .psql_history도 없다.
  - ssh로 원격 실행한 명령이나 SSH 터널을 거친 외부 클라이언트(DBeaver 등)는 히스토리에 남지 않으므로, 히스토리가 없다고 직접 접속을 배제할 수는 없다
- 로컬 Claude 세션 기록에서도 10/6에 anomaly_event를 DELETE한 기록은 찾지 못했다(검색어 기준)

## 미확인
- 누가, 어떤 문장(조건)으로 지웠는지. SQL 로그·히스토리가 없다.
- 10/6 API 로그(AI WS 구독·저장 실패 여부). 컨테이너가 재생성되며 사라졌다.
- anomaly_incident 31~58·60·61·70~72가 없는 이유
  - 통계상 상황 삭제는 34건이고 마지막 autovacuum은 20:29 KST다
  - 70~72는 14:00 이후 탈퇴가 없어 CASCADE로는 설명되지 않는다
  - 롤백으로 번호만 소비됐는지, 직접 삭제됐는지 구분하지 못했다
- 정확한 삭제 시각은 autovacuum으로 추정한 범위다(21:48:32~21:50:17)

## 후속 제안 (PHASE E)
- 이 결론은 "앱 코드·CASCADE 경로가 아니다"까지다. 삭제 주체를 특정하려면 gosky DB의 `log_statement`(현재 none)를 `mod` 이상으로 올려 두는 것이 재발 시 유일한 단서가 된다(운영 설정 변경이라 승인 필요).
- 이상감지 이력은 시연·감사 근거라 직접 DELETE 대신 테스트 계정 분리 또는 시연 전 백업을 권한다.

---

# Part 3. 추가 범위 - 낙상 클립 구간 분리 (#323, ccaad25, 2026-10-07)

작업 중 dev에 머지돼 지시대로 범위에 추가했다(코드 수정 없음, diff 정독).

- ✅ 낙상만 `fall-pre/post-seconds`를 쓰고 나머지는 기존 값. 기본값(3/2)이 기존과 같아 AI 반영 전 배포해도 동작 동일. 마이그레이션 없음.
- ✅ 낙상 값은 AI 계약 범위(pre 0~8, post 0~3, 합계 1~10)로 잘라 써 오타가 기동·클립 생성을 막지 않는다. 클립 쿨다운·상황당 12개·열람·삭제·판정 숨김·25초 제한·IDOR 범위는 무변경(종류는 요청 본문 구간 선택에만 쓰임).
- ✅ 파일 크기 5초→9초분 ≈ 3.4MB로 10MB 상한 안. 뒤 구간 단축으로 AI 대기는 오히려 짧아짐.
- 🟢 G-7 운영 순서 의존: AI가 앞 상한 8·합계 10을 반영하기 전에 서버 env를 8/1로 올리면 AI가 422로 거절하고 BE는 재시도·쿨다운 해제를 하지 않아 **낙상 클립이 조용히 생성되지 않는다**(문서에 명시된 위험). 올린 뒤 낙상 클립 1건 생성 확인 필요. 두 서버(gosky 수동 배포·vkcs CD)의 env 값이 같은지도 확인.
- 🟢 G-8 테스트: `AiClipClientTest`(기본·낙상 8/1·clamp)로 충분. 추가 제안 없음.

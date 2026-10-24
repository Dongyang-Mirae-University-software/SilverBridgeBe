# (2026-10-24) 이상감지 WS `anomaly-detected` 제목·본문 기능 점검

- 대상: 2026-10-12 `fix/anomaly-ws-title-body` (`docs/(2026-10-12) fix-anomaly-ws-title-body.md`)
- 템플릿: B(기능 점검), 점검만(코드 수정 없음)
- 기준: BE dev `371699a`, FE develop `bbbd863`

## 결론

**PASS** - 🔴 0 · 🟠 0 · 🟡 0 · 🟢 1

## 확인한 것

| 항목 | 결과 | 근거 |
|---|---|---|
| 제목·본문이 정책을 지키는가 | ✅ | 제목 "화재 감지"·"낙상 감지"·"흉기 감지"(`label + " 감지"`), 본문 "…감지되었습니다"(단정 "발생했습니다" 아님), 조사는 `DetectedTypeLabel.withSubjectParticle` 한 곳 |
| 종류별 안내 | ✅ | 본인 = 화재 대피 / 흉기 피신·112 / 낙상 보호자 연락·SOS(대피 아님), 보호자 = 흉기 112 안내·낙상 연락 안내·화재 없음. 신고는 안내만(서버 발신 없음) |
| WS와 FCM이 같은 값 | ✅ | 반복문 안에서 한 번 만든 `title`·`body`를 WS 복사본과 `NotificationContent`가 함께 쓴다 |
| 공유 `data`(FCM data·알림톡 변수) 오염 없음 | ✅ | WS는 `new HashMap<>(data)` 복사본에만 넣는다. 원본은 불변 맵(`Map.ofEntries`) |
| 빈 값 | ✅ | `wardName`은 이벤트 생성 시 대체 이름(`FALLBACK_WARD_NAME`), `cameraLabel`은 등록 카메라의 방 이름(필수)이라 비는 경로가 없다. 감지는 등록 세션만 기록한다 |
| 정지 수신자·executor | ✅ 무변경 | `sendToUser`의 `[WS-BLOCKED]`, `urgentNotificationExecutor` 그대로 |
| 계약 문서 | ✅ | `(2026-08-31) api-contract-anomaly-dashboard.md` 101행 "WS에만 title·body, FCM data에는 싣지 않는다" |
| FE 사용 | ✅ | `utils/dashboard/realtime.ts`가 `payload.body ?? 대체`, `payload.title ?? 대체`로 BE 값을 먼저 쓴다 |
| 테스트 | ✅ | `AnomalyNotificationListenerTest` 22개 통과 |

## 변이 시험

| 변이 | 결과 |
|---|---|
| W1 조사를 "이"로 고정(옛 버그 재현) | 검출 |
| W2 WS에 `title` 미포함 | 검출 |
| W3 FCM data에 WS 페이로드(제목·본문) 전달 | 검출 |

## 이슈

| ID | 단계 | 내용 | 비고 |
|---|---|---|---|
| L-1 | 🟢 | FE `PushNotificationListener`의 이상감지 예비 문구(FCM 알림 제목·본문이 없을 때만 사용)는 피보호자에게 유형과 무관하게 "안전한 곳으로 대피해 주세요"를 붙이고 제목도 "이상 상황 감지"다. 낙상에는 맞지 않는 안내다 | BE는 FCM에 항상 알림 제목·본문을 실어(`FcmService` `setNotification`) 이 경로는 BE 푸시에서 실행되지 않는다. FE 범위, 조치 선택 |

## 후속 조치 (2026-10-24) - L-1 조치

FE #137 (머지 `c4bdc24`, gosky 반영): `src/utils/anomaly/alertText.ts` 한 곳에서 BE와 같은 예비 문구(제목 "○○ 감지", 본인 종류별 안내 - 낙상은 보호자 연락·SOS, 보호자 흉기·낙상 안내, 장소가 비면 "등록된 카메라에서")를 만들고 푸시(`PushNotificationListener`)·WS 실시간(`utils/dashboard/realtime.ts`)이 함께 쓴다. WS는 BE `title`·`body`가 여전히 우선. 단위 테스트 3개(낙상에 "대피" 없음 등), `npm test` 303·browser 178·build 통과, gosky 페이지 200.


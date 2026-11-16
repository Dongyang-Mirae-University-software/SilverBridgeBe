# (2026-11-16) 영향 범위 점검 - 웹 JSON 숫자 enum 거부

- 변경: BE #490 (머지 `560646d7`) - `application.yaml` 에 `spring.jackson.datatype.enum.fail-on-numbers-for-enums: true`
- 점검 기준 커밋: `dev` `5e50d0e6` (`560646d7` 포함), `./gradlew build` 통과
- 방식: 템플릿 C. 점검만, 코드 변경 없음. 서버는 읽기 전용(로그는 예외 클래스 이름 건수만 집계)

## PHASE 0. 변경점과 불변식

- 바뀐 것: Spring Boot 가 만드는 Jackson 3 `JsonMapper` 가 enum 칸에 숫자(`0`, `1`)가 오면 순서값으로 바꾸지 않고 `HttpMessageNotReadableException`(cause `InvalidFormatException`) → 400 `INVALID_INPUT`.
- 불변식: "enum 칸은 이름 문자열만 enum 이 된다. 그리고 우리 시스템 안에서 enum 을 숫자로 보내거나 저장하는 곳은 없다."

## PHASE A. 사용처 전수 수집

### A-1. 웹 요청(@RequestBody) DTO 의 enum 칸 - 13개 DTO, 16칸

| 도메인 | 위치 | 칸 |
|---|---|---|
| admin | `domain/admin/dto/AdminHospitalStatusRequest.java:17,19` | `operationStatus`, `congestionLevel` |
| admin | `domain/admin/dto/AdminUserUpdateRequest.java:37,41` | `role`, `status` |
| anomaly | `domain/anomaly/dto/AnomalyFeedbackRequest.java:19` | `verdict` |
| auth | `domain/auth/dto/RegisterRequest.java:60,74` | `role`, `gender` |
| auth | `domain/auth/dto/KakaoRegisterRequest.java:51,71` | `role`, `gender` |
| inquiry | `domain/inquiry/dto/InquiryCreateRequest.java:20` | `category` |
| medication | `domain/medication/dto/MedicationCreateRequest.java:38` | `timeSlot` |
| medication | `domain/medication/dto/MedicationUpdateRequest.java:38` | `timeSlot` |
| notification | `domain/notification/dto/NotificationSettingUpdateRequest.java:24` | `channelType` |
| sos | `domain/sos/dto/SosSettingUpdateRequest.java:15` | `sosAction` |
| sos | `domain/sos/dto/SosTriggerRequest.java:27` | `triggerType` |
| user | `domain/user/dto/UserUpdateRequest.java:43` | `gender` |

- `@JsonCreator`·`@JsonValue`·`@JsonEnumDefaultValue` 가 붙은 enum: 0개 → 전역 설정이 16칸 모두에 그대로 적용된다.
- enum 타입 `@RequestParam`·`@PathVariable` 21곳: Jackson 이 아니라 Spring 변환기(`Enum.valueOf`)라 원래부터 숫자를 받지 않음 - 해당없음.

### A-2. 웹 요청 밖의 JSON 처리

| 경로 | 쓰는 변환기 | 이번 설정 적용 | enum 으로 바꾸는가 |
|---|---|---|---|
| AI 서버 HTTP 클라이언트 (`AiChatClient`·`AiSttClient`·`AiCompanionClient`·`CompanionAiStatusClient`·`AiMedicationOcrClient`·`AiClipClient`·`AiStreamClient`·`AiServerHealthClient`) | Jackson 2 `ObjectMapper` (`JacksonConfig`) | 아니오 | 아니오 - `readTree` 로 노드만 읽음 |
| AI 이상감지 WS (`AiLiveStreamSubscriber:256`) | Jackson 2 | 아니오 | 아니오 - `readTree` |
| 예약 서버 (`ReservationAdminClient`) | Jackson 2 | 아니오 | 아니오 - `readTree` |
| Kakao (`KakaoOAuthClient`) | `RestClient.builder()` 기본 변환기 + Jackson 2 | 아니오(Boot 설정 안 탐) | 아니오 - 응답 DTO 에 enum 칸 없음 |
| 파일 서버 (`FileServerClient`) | `RestClient.builder()` 기본 변환기 | 아니오 | 아니오 - `Map` |
| 채팅 중계 (`ChatRelayService:183`) | Jackson 2 `convertValue(Object.class)` | 아니오 | 아니오 |
| 사용자 WS (STOMP, `WebSocketConfig`) | Boot 메시지 변환기 | 예 | 해당없음 - `@MessageMapping` 0개(받는 메시지를 DTO 로 바꾸는 곳 없음) |
| Redis·캐시 | - | - | 해당없음 - JSON 직렬화 `RedisTemplate`·`@Cacheable` 0개 |

### A-3. 보내는 쪽

| 대상 | 결과 |
|---|---|
| FE (`SilverBridgeFe` `568078f`) | enum 칸 타입이 모두 문자열 리터럴 유니온(예: `sosAction: WardSosAction`, 상수 `'CALL_119_AND_NOTIFY'`). 숫자 TS enum 0개, `칸: 숫자` 리터럴 0개 |
| AI 서버 (`SilverBridgeAiServer` `1a62553`) | BE 호출은 `COMPANION_BACKEND_BASE_URL` 의 GET 질문 조회뿐 - 요청 본문 없음 |

### A-4. dev 로그 (배포 뒤, 예외 클래스 이름 건수만)

| 서버 | 기동 | `HttpMessageNotReadableException` |
|---|---|---|
| gosky `dmu-dev-api` | 2026-11-15 18:56 KST | 21건 - 모두 18:58~18:59 KST(QA 재실행 시간대, 숫자·잘못된 값 일부러 보내는 케이스). 그 뒤 0건 |
| vkcs-linux `dmu-dev-api` | 2026-11-15 18:59 KST | 0건 |

## PHASE B. 판정

| 영역 | 판정 | 근거 |
|---|---|---|
| 웹 요청 enum 16칸 | PASS | 전역 설정이 모두 적용, 우회하는 커스텀 생성자 없음 |
| FE 요청 | PASS | 문자열만 보냄 |
| AI 서버 → BE | 해당없음 | 본문 없는 GET 뿐 |
| BE → 외부 응답 읽기 (AI·예약·Kakao·파일) | 해당없음 | 이번 설정이 안 걸리는 Jackson 2·기본 변환기, enum 으로 바꾸지도 않음 - 기능 영향 0 |
| STOMP·Redis | 해당없음 | 받는 메시지를 DTO 로 바꾸는 곳 없음 |
| 실제 사용 중 400 증가 | PASS | QA 시간대 밖 0건 |

## 이슈

- 🔴 Critical / 🟠 High / 🟡 Medium: 없음
- 🟢 Low-1: 숫자 **문자열**(`"0"`)도 Jackson 코드상 같은 이유로 거부되지만 `WebJsonEnumNumberRejectTest` 는 숫자 `0`·`1` 과 이름 문자열만 본다.
- 🟢 Low-2: 규칙이 Jackson 3(웹 요청)에만 있고 Jackson 2 `ObjectMapper` 에는 없다. 지금은 Jackson 2 로 enum 을 바꾸는 곳이 0개라 문제 없음. 나중에 AI 응답을 enum DTO 로 바로 읽게 바꾸면 숫자가 조용히 순서값이 될 수 있다(`application.yaml:56` 주석에 이미 적혀 있음).

## PHASE C. 테스트로 고정 (제안만)

- Low-1: `WebJsonEnumNumberRejectTest` 에 `{"sosAction":"0"}` 거부 케이스 한 줄 추가.
- Low-2: 기계적으로 막을 필요는 아직 없음. Jackson 2 로 enum DTO 를 읽는 코드가 생길 때 그 클라이언트 테스트에서 숫자 거부를 같이 본다.

## 종합 판정

**PASS** - 전역 숫자 enum 거부가 다른 도메인의 기존 동작을 깨는 경로 없음. Low 2건은 제안만.

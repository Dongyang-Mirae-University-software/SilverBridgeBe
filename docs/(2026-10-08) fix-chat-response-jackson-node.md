# 챗 중계 응답 직렬화 수정 (2026-10-08)

## 증상
`POST /api/guardian/chat` 응답의 `data` 에 AI 답변이 없고 `array`·`bigDecimal`·`nodeType`·`object` 등 JsonNode 속성이 내려옴. 챗 사용 불가(2026-10-07 중계 배포 후).

## 원인
- Spring Boot 4 웹 응답 변환기는 Jackson 3(`tools.jackson`).
- `GuardianChatController` 가 `ApiResponse<com.fasterxml.jackson.databind.JsonNode>`(Jackson 2)를 반환 → Jackson 3 가 모르는 타입이라 getter 를 bean 으로 직렬화.
- `JacksonConfig` 의 Jackson 2 `ObjectMapper` 빈은 주입용이며 웹 변환기와 무관.
- 챗 테스트가 컨트롤러·서비스를 직접 호출해 HTTP 직렬화를 검증하지 않아 통과했다.
- 수정 전 코드에서 MockMvc 로 재현함(`data` 에 nodeType=OBJECT 등만 내려옴).

## 수정
- `ChatRelayService.send/logs/logDetail` 이 허용 필드 필터(`ChatLogProjection`) 뒤 `objectMapper.convertValue(node, Object.class)` 로 Map/List/값을 반환.
- `GuardianChatController` 는 `ApiResponse<Object>`.
- 안 바뀜: 경로, 요청 DTO, 허용 필드 목록, 인가, 속도 제한, 동시 상한, 타임아웃, 오류 code, 로그 정책.

## 재발 방지
- `GuardianChatControllerHttpTest`: 실제 HTTP 변환기로 data.reply·riskLevel 문자열, 숫자·null·배열·중첩 보존, 목록 배열·빈 배열, 상세 객체, 컨트롤러·서비스 공개 메서드 반환 타입에 Jackson 2(`com.fasterxml.jackson.databind`) 금지.
- `ChatRelayServiceTest` 는 반환 타입 변경에 맞춰 단언만 조정.

## 영향 범위
main 에서 Jackson 2 노드가 응답·WS·이벤트에 실리는 곳은 챗뿐이다. anomaly·camera 의 JsonNode 는 AI 응답 파싱용 내부 코드.

## 교훈
기능 점검(`audit-chat-relay`)이 HTTP 직렬화를 보지 않았다. 컨트롤러 점검은 실제 변환기를 거치는 테스트를 포함할 것.

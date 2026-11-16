# (2026-11-16) 최종 QA 남은 BE 항목 정리

- 브랜치: `fix/final-qa-remaining` (기준 `dev` `0acd638b`)
- 근거: 노션 DMU > SilverBridgeQA > 최종 QA - BE+AI (AUTH-A24, AUTH-A12, AUTH-A44, CONN-N06, CHAT-N33)
- 결정(사용자, 2026-11-16): A12 코드 유지·문서 수정 / A44 문서 좁히기 + 백로그 / N06 피보호자 문구에 해제한 쪽 표시(보호자·관리자만, 보호자 탈퇴는 M-2 단문 유지) / N33 상한 12MB

## PHASE 0 - 전제 vs 실제

| 전제 | 실제 | 차이 |
|---|---|---|
| refresh 에 토큰이 없으면 400 | `AuthController.refresh` 191-192행 `INVALID_INPUT`, Swagger 400 설명도 있음 | 없음 - 문서만 빠짐 |
| 카카오 가입 요청의 밖 이미지 주소는 400 | `KakaoRegisterRequest.profileImageUrl` `@Pattern`(kakaocdn.net 과 하위 도메인) | 없음 - 문서(639-640행)가 "이미지 없이 진행"이라 틀림 |
| 사진 삭제는 우리 파일 서버 주소만, 주인 확인 없음 | `FileServerClient.ownedFilename` 호스트만 확인 | 없음 |
| 피보호자 해제 알림은 누가 끊었든 단문 | `ConnectionNotificationListener.handleDisconnected` `toWard ? DISCONNECTED_WARD_BODY` | 없음. 보호자 탈퇴 단문은 M-2(2026-09-30) 결정과 연결됨 |
| multipart 10MB/10MB | `application.yaml` 48-49행 | 없음 |
| 탈퇴 알림 문서 | 403행 "보호자(피보호자)가 탈퇴해 연결이 종료되었습니다" 가 양쪽에 간다고 적힘 | 문서 drift - 피보호자에게는 2026-10-27 부터 단문 |

## 변경

| 파일 | 내용 |
|---|---|
| `domain/connection/listener/ConnectionNotificationListener.java` | 피보호자 수신 문구를 `wardDisconnectedBody(disconnectedBy)` 로 - GUARDIAN "보호자가 가족 연결을 해제했어요.", ADMIN "관리자가 가족 연결을 해제했어요.", WITHDRAWN·WARD 는 기존 단문 |
| `src/main/resources/application.yaml` | `max-request-size` 10MB → 12MB (`max-file-size` 10MB 그대로) |
| `프로젝트_설명.txt` | refresh 응답 코드(A24), 카카오 이미지 주소 400(A12), 사진 삭제 범위·주인 확인 백로그(A44), 탈퇴 알림 문구 정정, WS `connection-cancelled` 문구 표, 관리자 해제 문구, multipart 12MB |
| 테스트 | `ConnectionNotificationListenerTest` 기대 문구 갱신 + 새 문구 2개 90바이트 단문 규칙, 새 `global/config/MultipartLimitConfigTest` |

## 바뀌지 않는 것

- 보호자가 받는 해제 문구, 알림 종류(`CONNECTION_DISCONNECTED`), 발송 채널, WS 목적지·키(`disconnectedBy` 포함), 피보호자 알림 제목("실버브릿지")
- 파일 하나 상한 10MB 와 경로별 서비스 검사(프로필 5MB, 약 봉투 8MB, 말벗 음성 5MB·사진 1MB×5)
- refresh·카카오 가입·사진 삭제 코드(문서만 바뀜)

## 검증

- `./gradlew build` 통과
- QA 재실행 결과는 PR 설명과 노션에 기록

## 남은 것

- CHAT-N33·AUTH-A42 는 nginx `api.devdmu` 13m 적용 전까지 실패로 남음 (노션 DMU > 인프라 전달 내용)
- 백로그: 프로필 사진 삭제 전 파일 주인 확인 (AUTH-A44)
- 점검 제안: 알림 문구만 바뀌어 공용 규칙 변경(C)은 아님 → 기능 점검(B) 대상으로 `audit-index.md` ❌

# QA에서 나온 BE 이슈 4건 반영 (2026-10-01)

> 근거: Notion `DMU / SilverBridgeQA / BE (SilverBridgeBe)` BE-1~BE-4. 마이그레이션 없음.

| ID | 처리 | 내용 |
|---|---|---|
| BE-1 | 수정 | `AiLiveStreamSubscriber`가 `anomaly.resync-seconds`(60초, 0=끔)마다 세션 `list`를 다시 요청. 연결 없으면 건너뜀. 등록 카메라가 라이브인데 subscribe 전송이 실패하면 WARN(이전에는 무로그). 미등록 세션은 DEBUG 유지 |
| BE-2 | 수정 | 카카오 탈퇴 확인 문구를 `"탈퇴"`와 `"회원탈퇴"` 둘 다 허용(앞뒤 공백 무시, 정확히 일치). FE는 수정 없이 동작. Swagger·정책 문서 갱신 |
| BE-3 | 수정 | 보호자가 요청을 취소하면 `ConnectionRequestCancelledEvent` → 피보호자에게 WS `connection-request-cancelled`(`{connectionId}`)만. **푸시·문자·알림 이력 없음**(취소 무알림 정책 유지). 기존 `connection-cancelled`는 FE가 해제 토스트로 보여 줘서 재사용하지 않음 |
| BE-4 | 문서화 | 알림·실시간 payload의 값은 전부 문자열(FCM data가 문자열만 가능), REST는 숫자. 코드 변경 없음, FE 비교 시 `Number()` |

## 검증
- 단위 718 / 0 실패(신규 6: 재동기화 3·취소 이벤트 2·탈퇴 문구 1), vkcs 통합 통과.
- 탈퇴·역할 변경 중 PENDING이 조용히 취소되는 경로는 같은 화면 갱신 증상이 있으나 이번 범위 밖(필요 시 같은 이벤트 재사용).

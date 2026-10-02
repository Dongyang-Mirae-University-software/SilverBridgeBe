# QA(BE) 이슈 반영 (2026-10-02)

근거: `QA_BE.md` 항목 ID. 정책 상세는 `.claude/rules/domain-security-policy.md` "2026-10-02 QA(BE) 반영". 묶음별 브랜치(P0~P10)를 합친 `qa-be-combined-check` 합본 코드로 각 항목을 대조해 확인했다(2026-10-02). 묶음→브랜치: P0 foundation, P1a client-ip, P1b jwt-ws-core, P2a auth-service, P2b verification-code, P3 input-validation, P4 sos-settings, P4b sos-dispatch-result, P5 connection, P6 admin, P7 medication, P8 anomaly, P9 docs, P10 redis-policy(`fix/qa-be-*`).

## 항목별 요약

| 항목 | 요약 | 묶음 |
|---|---|---|
| D1 | 인증 필터 Redis 장애: 일반 경로 503, `POST /api/ward/sos`만 fail-open(WARN), 핸드셰이크 503 | P1b |
| D2 | 토큰 무효화 시각 초 단위 비교(같은 초 발급 허용), 옛 ms 값 호환 | P1b |
| D3 | refresh jti 고유값·typ 검사·다른 기기 로그인으로 밀려난 토큰은 단순 INVALID_TOKEN, H-3 유지 | P1b/P2a |
| D4 | 관리자 본인 탈퇴 403 | P2a |
| D5 | `trusted-proxies` 신뢰 피어일 때만 XFF(오른쪽부터), RateLimit Redis 장애 fail-open / 보안 카운터 fail-closed | P1a |
| XCUT-G04 | STOMP 클라이언트 SEND는 `/app/`만(가짜 SOS 위조 차단) | P1b |
| 이메일·텍스트 | 이메일 소문자·V55 `lower(email)` 유니크(P2a), `global/validation` 유틸·오류 응답 `code`(P0) | P0/P2a |
| 로그인 잠금 | 비교 전 시도 예약, 미가입 동일 처리, 변경·탈퇴 userId 별 잠금, 성공 시 해제 | P2a |
| 인증번호 | 시도 선예약+정답 환불, 확인 API IP 제한, nonce 원자 소비 | P2b |
| 카카오 | `pendingToken`, 프로필 이미지 CDN만, `kakao_숫자@kakao.com` 가입 거절, 파일서버 삭제 baseUrl 한정 | P2a/P2b |
| SOS | 수신자별 결과 반환·전달 0명이면 쿨다운 해제, 설정 최초 저장 ON CONFLICT, 이력 `triggerType` 필터·`counts` | P4/P4b |
| 연결 | 강제 연결·해제 WS 페이로드 type/title/body, 수락 시 역할 재검증, 동시 중복 응답 | P5 |
| 복약 | 보호자 없는 약 알림 제외, 과거 시각 수정 시 기록 유지, 늦게 등록한 약 요약 제외 | P7 |
| 이상감지 | 하루 요약에서 최근 건별 재촉 상황 제외, 이력 쿨다운 저장 실패 시 해제 | P8 |
| 관리자 | 공지 조회수 원자 증가·`updated_at` 내용 변경 시만(V56), 목록 page/size 통일, 본문 100자, 문의 분당 5회·시간당 30회, 회원 목록 연결 필터 관리자 제외 | P6 |
| ANOM-G06 | 동수 상황의 미응답 보호자는 안내·재촉 없음 = 의도(문서·Javadoc 명시, 로직 무변경) | P9 |
| AUTH-G24 | Swagger 정정: 로그아웃 Authorization 누락 401, 카카오 가입 대기 30분, 로그인 비밀번호 "최대 64자" | P9 |

## 배포 주의

- **V55(비가역)**: 이메일 소문자화 + `lower(email)` 유니크 인덱스. 배포 전 `SELECT lower(email), array_agg(id) FROM users GROUP BY lower(email) HAVING count(*) > 1;`로 중복 0건 확인. 두 서버(gosky·vkcs-linux) 모두.
- **V56**(공지 `updated_at` 트리거 - 제목·내용이 바뀔 때만 발동)은 합본에 포함되어 있다. 마이그레이션·JPQL 변경 PR은 머지 전 `tools/integration-test.sh [브랜치]`로 통합 테스트.
- **`CLIENT_IP_TRUSTED_PROXIES`**: 서버 `.env.dev`에 FE 서버 IP를 넣기 전에는 FE 서버 경유 요청이 종전처럼 한 IP로 합산된다(기본 신뢰 목록은 루프백+사설망, 환경변수는 기본값을 대체하므로 함께 적을 것). 넣은 뒤 접속로그·rate limit IP를 확인.
- Redis 장애 시 정책이 바뀌었다(일반 경로 503 / SOS만 통과). 모니터링에서 `[AUTH-STORE-UNAVAILABLE]`·`[AUTH-STORE-FAIL-OPEN]` WARN을 본다.
- FE: 오류 응답 `code` 필드로 분기, 강제 연결·해제 WS 페이로드의 type/title/body, 목록 `page`/`size` 규칙, 공지 목록 본문 축약(상세는 상세 API).

## 미반영 보류 (정책 결정 대기)

ADMIN-G27/CONN-G08(정지된 유일한 보호자 경고) · ADMIN-G28·ANOM-G10(정지 후 열린 WS, 추천: `WebSocketEventPublisher` 수신자 확인) · CONN-G04·XCUT-G29(재요청 쿨다운) · XCUT-G11·SOS-G13(SOS 전용 executor) · ANOM-G08(중지 카메라 의미) · CONN-G02(강제 연결 FCM 승격) · XCUT-G31(refresh HttpOnly 쿠키). 승인되면 별도 작업.

# QA(BE) 이슈 반영 (2026-10-02)

근거: `QA_BE.md` 항목 ID. 정책 상세는 `.claude/rules/domain-security-policy.md` "2026-10-02 QA(BE) 반영". 묶음별 PR #267~#283이 모두 dev에 머지·배포됐고, 현재 dev 코드로 확인했다.

## 항목별 요약

| 항목 | 요약 | PR |
|---|---|---|
| D1 | 인증 필터 Redis 장애: 일반 경로 503, `POST /api/ward/sos`만 fail-open(WARN), 핸드셰이크 503 | 머지됨 (#269) |
| D2 | 토큰 무효화 시각 초 단위 비교(같은 초 발급 허용), 옛 ms 값 호환 | 머지됨 (#269) |
| D3 | refresh jti 고유값·typ 검사·다른 기기 로그인으로 밀려난 토큰은 단순 INVALID_TOKEN, H-3 유지 | 머지됨 (#269·#275) |
| D4 | 관리자 본인 탈퇴 403 | 머지됨 (#275) |
| D5 | `trusted-proxies` 신뢰 피어일 때만 XFF(오른쪽부터), RateLimit Redis 장애 fail-open / 보안 카운터 fail-closed | 머지됨 (#268) |
| XCUT-G04 | STOMP 클라이언트 SEND는 `/app/`만(가짜 SOS 위조 차단) | 머지됨 (#269) |
| 이메일·텍스트 | 이메일 소문자·V55 `lower(email)` 유니크, `global/validation` 유틸, 오류 응답 `code` | 머지됨 (#267·#275) |
| 로그인 잠금 | 비교 전 시도 예약, 미가입 동일 처리, 변경·탈퇴 userId 별 잠금, 성공 시 해제 | 머지됨 (#275) |
| 인증번호 | 시도 선예약+정답 환불, 확인 API IP 제한, nonce 원자 소비 | 머지됨 (#278) |
| 카카오 | `pendingToken`, 프로필 이미지 CDN만, `kakao_숫자@kakao.com` 가입 거절, 파일서버 삭제 baseUrl 한정 | 머지됨 (#275·#278) |
| SOS | 수신자별 결과 반환·전달 0명이면 쿨다운 해제, 설정 최초 저장 ON CONFLICT, 이력 `triggerType` 필터·`counts` | 머지됨 (#271·#272) |
| 연결 | 강제 연결·해제 WS 페이로드 type/title/body, 수락 시 역할 재검증, 동시 중복 응답 | 머지됨 (#273) |
| 복약 | 보호자 없는 약 알림 제외, 과거 시각 수정 시 기록 유지, 늦게 등록한 약 요약 제외 | 머지됨 (#276) |
| 이상감지 | 하루 요약에서 최근 건별 재촉 상황 제외, 이력 쿨다운 저장 실패 시 해제 | 머지됨 (#277) |
| 관리자 | 공지 조회수 원자 증가·`updated_at` 내용 변경 시만(V56), 목록 page/size 통일, 본문 100자, 문의 분당 5회, 연결 필터 관리자 제외 | 머지됨 (#274·#275) |
| P11 | WS 수신자 차단(`[WS-BLOCKED]`)·대시보드 `wardsWithoutReachableGuardian` (ADMIN-G27/28, CONN-G08, ANOM-G10) | #282 |
| P12 | 긴급 `urgentNotificationExecutor`(4/8/200) (SOS-G13, XCUT-G11) | #281 |
| P13 | 연결 요청 같은 쌍 24시간 5건, 6번째부터 429 (CONN-G04, XCUT-G29) | #283 |
| P14 | 카메라 등록 방 이름 검증을 수정 경로와 맞춤 (ANOM-G12 후속) | #280 |
| ANOM-G06 | 동수 상황의 미응답 보호자는 안내·재촉 없음 = 의도(문서·Javadoc 명시, 로직 무변경) | #279 |
| AUTH-G24 | Swagger 정정: 로그아웃 Authorization 누락 401, 카카오 가입 대기 30분, 로그인 비밀번호 "최대 64자" | #279 |

## 배포 주의

- **V55(비가역)**: 이메일 소문자화 + `lower(email)` 유니크 인덱스. 배포 전 `SELECT lower(email), array_agg(id) FROM users GROUP BY lower(email) HAVING count(*) > 1;`로 중복 0건 확인. 두 서버(gosky·vkcs-linux) 모두.
- **V56**(공지 트리거)은 머지됨 - 두 서버에서 적용 확인. 마이그레이션·JPQL 변경 PR은 머지 전 `tools/integration-test.sh [브랜치]`로 통합 테스트.
- **`CLIENT_IP_TRUSTED_PROXIES`**: 서버 `.env.dev`에 FE 서버 IP를 넣기 전에는 동작이 변하지 않는다(**사용자 소관**). 넣은 뒤 접속로그·rate limit IP를 확인. Redis `maxmemory-policy noeviction`(P10)도 **미머지·사용자 소관**.
- **카카오 `pendingToken`은 FE 동시 배포 필요**(가입 완료가 토큰을 요구).
- Redis 장애 시 정책이 바뀌었다(일반 경로 503 / SOS만 통과). 모니터링에서 `[AUTH-STORE-UNAVAILABLE]`·`[AUTH-STORE-FAIL-OPEN]` WARN을 본다.
- FE: 오류 응답 `code` 필드로 분기, 강제 연결·해제 WS 페이로드의 type/title/body, 목록 `page`/`size` 규칙, 공지 목록 본문 축약(상세는 상세 API).

## 구현으로 이동한 항목

ADMIN-G27/CONN-G08(대시보드 `wardsWithoutReachableGuardian`, 2026-09-09 "경고 안 만듦" 결정을 사용자가 2026-10-02 추천안으로 변경 - 개수만, 피보호자 화면 표시 없음) · ADMIN-G28/ANOM-G10(WS 발송 차단) · SOS-G13/XCUT-G11(긴급 executor) · CONN-G04/XCUT-G29(쿨다운) · 카메라 등록 DTO.

## 남은 보류

- ANOM-G08: `camera.is_active`는 표시용이라고 **문서화로 종결**(코드 변경 없음).
- CONN-G02: 강제 연결 FCM 승격 - 현행 유지 확정.
- XCUT-G31: refresh HttpOnly 쿠키 - 별도 설계.
- 세션 강제 종료: 핸드셰이크 Principal 선행.
- CONN-G15: 경합 잔여 - 수용.

# 전체 API 종합 점검 — 최종 통합 리포트 (3세션)

- **점검 기간**: 2026-06-11 (세션 1·2·3 동일자 수행)
- **범위**: 전체 7개 도메인 **52개 엔드포인트** + global 공통 인프라
  - 세션 1: auth(18) + user(6) — 회귀 위주 → `(2026-06-11) audit-full-api-session1.md`
  - 세션 2: connection(10) + notification(4) + SOS(1) — notification 정밀 → `...session2.md`
  - 세션 3: announcement(2) + admin(11) + global — 정밀 → `...session3.md`
- **점검 중 수정·머지 완료**: M-S1-1(PR #202), H-S2-1(PR #203)

---

## 1. 총괄 판정

| 항목 | 결과 |
|---|---|
| 🔴 Critical | **1건** — C-S3-1 (admin 공지 임시저장 4개 엔드포인트 전면 동작 불능) · **미수정** |
| 🟠 High | 1건 — H-S2-1 (SOS SMS 폴백 영구 무력화) · ✅ **수정 완료** (PR #203) |
| 🟡 Medium | 4건 — M-S1-1(✅ 수정 PR #202) · M-S2-1 · M-S2-2 · M-S3-1 |
| 🟢 Low | 17건 (세션별 보고서 참조) |
| 기준선 대비 회귀 | **0건** (세션 1·2의 과거 수정 30여 건 전부 유지 확인) |
| IDOR / PII 노출 / SQL Injection | **없음** (52개 전수) |

## 2. 전체 인가 매트릭스 (요약)

| 영역 | 엔드포인트 | 보호 | 검증 결과 |
|---|---|---|---|
| 공개 (auth) | 17 | permitAll + IP RateLimit(15곳) + 코드/nonce 선검증 | ✅ (signup 2곳만 RateLimit 부재 — Low) |
| 인증 공통 | 9 — user 6 + notification 2 + announcement 2 ... | `anyRequest().authenticated()` + `@AuthenticationPrincipal`만 사용 | ✅ IDOR 없음 |
| WARD 전용 | 6 — connection 5 + sos 1 | 클래스 `@PreAuthorize("hasRole('WARD')")` (+소유 검증) | ✅ (`@EnableMethodSecurity` 동작 실증) |
| GUARDIAN 전용 | 5 — connection 5 | 동상(GUARDIAN) | ✅ |
| ADMIN 전용 | 11 — announcement 관리 | SecurityConfig `/api/admin/**` hasRole | ✅ 인가는 정상 (단 draft 4개는 C-S3-1로 기능 불능) |
| 인증 필요 (명시 분리) | 1 — logout | permitAll 예외 등록 (L-3) | ✅ |
| WebSocket | `/ws` 핸드셰이크 + `/topic/{userId}/**` | JWT 핸드셰이크 + STOMP 구독 본인 검증 | ⚠️ M-S3-1 (핸드셰이크가 HTTP 필터보다 약함) |

## 3. 미해결 Critical/High/Medium 전체 목록

| ID | 심각도 | 내용 | 수정 방향 |
|---|---|---|---|
| **C-S3-1** | 🔴 | `chk_admin_audit_action` CHECK에 DRAFT 액션 4종 누락 → 공지 임시저장 create/update/delete/publish 항상 500(전체 롤백) | **V27 마이그레이션으로 CHECK 재정의** — 즉시 수정 권장 |
| M-S2-1 | 🟡 | SOS SMS 폴백이 토큰 "존재" 기반 — 무효 토큰 보호자 첫 SOS 유실 | 발송 결과 기반 폴백 or 긴급=FCM+SMS 동시 (설계 결정) |
| M-S2-2 | 🟡 | FCM 토큰 등록 시 소유자 재할당 없음 — 공유 디바이스에서 전 사용자 알림 오수신 | 소유자 다르면 userId 갱신(upsert) |
| M-S3-1 | 🟡 | WS 핸드셰이크: typ·로그아웃 블랙리스트·무효화 키 미검증(HTTP 필터 비대칭) | 핸드셰이크에 3종 검사 추가 |

## 4. 졸업작품 발표 전 수정 권장 (우선순위)

1. **C-S3-1** (필수) — admin 임시저장 기능 복구. V27 + 서비스 테스트. *발표 시연에서 admin 화면을 보여줄 경우 즉사 버그.*
2. **M-S3-1** (권장) — WS 핸드셰이크 보강. 보안 발표 포인트("HTTP·WS 동일 수준 토큰 검증")로도 가치.
3. **M-S2-2** (권장) — 시니어 가족 공유 폰 시나리오가 서비스 타겟과 정확히 겹침.
4. **M-S2-1** (설계 결정 후) — "긴급 알림 전달 보장" 스토리 완성.
5. Low 일괄 (선택) — Swagger 문서 보완(429 5곳·SOS 블록·draft null 계약), FindEmailRequest @Pattern, 토큰 삭제 소유 검증, 채널 실패 로그 스택트레이스, SecureRandom 통일, draft DTO null 정규화.
6. 테스트 보강 — **announcement/admin 0건**(Critical 미발견의 직접 원인) > SmsService > FcmService(✅ PR #203에서 신설) 순.

## 5. 도메인 간 일관성 (3세션 종합)

| 항목 | 현황 | 제안 |
|---|---|---|
| 응답 래퍼 | auth·user=`ApiResponse` 직접 / connection·notification·sos·admin=`ResponseEntity<ApiResponse>` | 신규 코드는 한쪽으로 통일(기존은 유지 — 와이어 동일) |
| URL 스타일 | auth·user=자원형 / connection·admin·announcement=동사형(`/select`,`/create`) | FE breaking이라 변경 비권장, 컨벤션 문서화 |
| Swagger 충실도 | auth·user·connection 충실 / SOS 블록 부재 / 429 누락 5곳 | 일괄 보완 1커밋 |
| 감사 기록 | 접속로그=REQUIRES_NEW(행위와 독립) / admin 감사=REQUIRED(행위와 운명공동) | 의도 차이 — 각자 정합, 문서화됨 |
| 에러 메시지 톤 | 시니어 친화 존댓말 전 도메인 일관 | 유지 |

## 6. 이번 점검에서 수정 완료된 사항

- **M-S1-1** (PR #202): 탈퇴 2단계 사이 실패 시 좀비 계정 — 리스너 격리 + purge 재시도 + INACTIVE 스윕 스케줄러. `domain-security-policy.md`에 INACTIVE 불변식 기록.
- **H-S2-1** (PR #203): 만료 FCM 토큰 정리 무트랜잭션 → `deleteByToken` @Transactional+@Modifying + FcmServiceTest 5건 신설.

## 7. 점검 방법론 메모 (다음 점검 참고)

- 기준선 회귀 점검(세션 1)은 "변경 커밋 식별 → 해당 속성 재검증" 방식으로 풀 점검 대비 ~50% 비용.
- 이번 Critical/High 2건 모두 **트랜잭션·제약조건 경계**(무트랜잭션 derived delete, enum↔CHECK 비동기화)에서 나옴 — 단위 테스트 사각지대. 다음 점검 시 "DB 제약 vs 코드 enum/검증" 대조를 표준 단계로 추가 권장.
- 테스트 0건 도메인(announcement/admin)에서 Critical 발생 — 커버리지 갭과 결함 분포가 일치.

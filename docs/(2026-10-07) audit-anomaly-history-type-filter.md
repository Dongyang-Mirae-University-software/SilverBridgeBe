# 점검 - 보호자 이상감지 이력 유형 필터·건수 요약 (2026-10-07, 템플릿 B)

대상: PR #328(`type` 필터·`/history/summary`)·#329(`needsReviewCount`), 기준 커밋 `37a5e0c`(dev). 점검만 했고 코드 수정은 없다. 마이그레이션 없음.

## PHASE 0. 대상 식별 - 엔드포인트 × 역할

| 엔드포인트 | 역할 제한 | 인가 근거 |
|---|---|---|
| `GET /api/guardian/anomaly/history?type=` | 클래스 `@PreAuthorize("hasRole('GUARDIAN')")` | `resolveVisibleWardIds` (`getActiveWardIds` / `isActiveConnection`) |
| `GET /api/guardian/anomaly/history/summary` | 같음 | 같은 `resolveVisibleWardIds` |

## PHASE A. 보안·인가
| 항목 | 결과 | 근거 |
|---|---|---|
| WARD·ADMIN 접근 | PASS | `GuardianAnomalyControllerSecurityTest` 요약 허용/WARD/ADMIN 3건 (이력은 기존) |
| IDOR (연결 없는 wardId) | PASS | 이력·요약 모두 403 `ANOMALY_NOT_AUTHORIZED` + `[IDOR-ATTEMPT]`, 저장소 조회 안 함 (`GuardianAnomalyHistoryFilterTest`) |
| 인가가 필터·집계보다 먼저 | PASS | 서비스 첫 줄이 `resolveVisibleWardIds`, 쿼리는 인가된 wardIds로만 실행 |
| `getMyWards` 미사용 (PENDING 혼입) | PASS | 사용 없음 |
| 입력 검증 | PASS | `type`은 전용 enum, 잘못된 값 400 (HTTP 테스트 5종), `NORMAL`·`UNKNOWN`·`SMOKE`·소문자 포함 |
| 주입 | PASS | JPQL 파라미터 바인딩만, 문자열 결합 없음 |
| PII·로그 | PASS | 새 로그 없음, 기존 `[IDOR-ATTEMPT]`는 ID만 |

## PHASE B. 기능 정합성
- 필터는 쿼리 안(`:type IS NULL OR ...`)이라 `totalElements`·`totalPages`가 필터 기준 - 통합 테스트로 PostgreSQL에서 null·값 양쪽, 페이지 경계, 다른 피보호자 미혼입, 목록 totalElements == 요약 total 검증 (vkcs 통과).
- `type` 생략 시 결과는 기존과 같다. 달라진 것은 정렬 보조키(id DESC) 뿐.
- 요약은 type 필터와 무관하게 범위 전체 기준, `needsReviewCount` = PENDING + CONFLICTED (서비스 테스트 단언).
- 연결 없음 → 빈 요약(전부 0), 예외 아님 - 이력의 빈 페이지와 같은 동작.

## PHASE C. 구조·계약
- `@Transactional(readOnly = true)`, 새 쓰기·이벤트 없음. N+1 없음 (요약은 GROUP BY 1회, 이력은 기존 배치 조회).
- 인덱스: `idx_anomaly_incident_ward_started (ward_id, started_at DESC)`가 IN 조건을 받친다. 유형 조건은 필터링이며 이력 규모에서 충분.
- Swagger: `type` 허용 값·생략 시 전체·400, 요약 필드 의미·"type 필터와 무관" 명시. 응답 래퍼 `ApiResponse`·`PageResponse` 형태 불변. 경로 충돌 없음(`/history/summary` vs `/{incidentId}/...`).
- 관리자 `AdminAnomalyTypeFilter`·`byType`("0건 유형 항목 없음")은 건드리지 않았고, 보호자 `byType`은 세 유형 고정이라는 차이를 rules 파일에 기록.

## PHASE D. 테스트
단위 6 + HTTP 4 + 보안 3 + 통합 3건. 핵심 케이스(type 전달·null·wardId+type 403·요약 합산·403·빈 요약·잘못된 type 400) 커버. 요약 JSON에 `needsReviewCount`가 실리는 HTTP 단언은 없다(L-3).

## 이슈
| 등급 | ID | 내용 | 제안 |
|---|---|---|---|
| 🟢 Low | L-1 | 요약 `total`은 FIRE·FALL·WEAPON 합이고 `type` 없는 목록 `totalElements`는 모든 행을 센다. `detected_type`에 CHECK가 없어 이론상 다른 값이 있으면 어긋난다. 코드는 NORMAL·UNKNOWN을 저장하지 않고 SMOKE는 V53이 FIRE로 옮겨 현재는 같다. | **반영 (2026-10-07)**: `total`을 전 행 합으로 변경 + 단위 테스트 |
| 🟢 Low | L-2 | (정정) 요약 API 전용 제한은 없지만 `UserRateLimitFilter`가 로그인 사용자 전체 요청에 1분 단위 제한을 이미 건다(SOS·인증·WS 제외). 처음 "제한 없음"이라 적은 것은 틀렸다. | 해당 없음 - 코드 변경 불필요 |
| 🟢 Low | L-3 | HTTP 테스트가 `needsReviewCount` JSON 키를 단언하지 않는다 (서비스 테스트는 값을 단언). | **반영 (2026-10-07)**: 요약 JSON 전 필드 단언 추가 |

## 종합 판정
**PASS** - Critical·High·Medium 없음. 인가 순서·필터 위치·응답 형태 보존 모두 확인. 통합 테스트 vkcs 통과(`321e5ea`), 두 서버 배포 반영 확인(`e942b09`).

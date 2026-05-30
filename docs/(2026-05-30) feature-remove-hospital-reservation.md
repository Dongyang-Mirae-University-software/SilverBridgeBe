# 병원 예약(hospital_reservations) 기능 완전 제거

- **작업 일자**: 2026-05-30
- **유형**: 기능 제외 / 미사용 자산 정리 (코드 + DB 테이블)
- **마이그레이션**: `V24__drop_hospital_reservations.sql`

---

## 1. 배경 / 결정

병원 예약 기능을 프로젝트에서 **제외**하기로 결정. 프론트는 아직 병원 예약 API를 구현하지 않아 호환성 우려 없음.

핵심 발견: **백엔드 구현은 시작된 적이 없었다.** `hospital_reservations`는 `V1__init.sql`에서 DB 테이블만 생성되었고, Entity/Repository/Service/Controller/DTO/Enum/테스트가 **전혀 존재하지 않는** 미사용 자산이었다. 대시보드(관리자/보호자) 통계에도 병원 예약 집계는 없었다.

→ `V17__drop_unused_feature_tables.sql`(call/game/ai/anomaly 미사용 테이블 정리)과 동일한 후속 정리 작업.

## 2. PHASE 0 — 제거 대상 식별 결과

| 분류 | 결과 |
|---|---|
| `hospital*`/`reservation*` Java 파일 | **0개** |
| `hospital`/`reservation` 참조 Java 파일 | **0개** (대시보드 포함 컴파일 의존성 없음) |
| 대시보드 통계 참조 | **없음** (응답 구조 변화 없음, 프론트 영향 없음) |
| DB 테이블 정의 | `V1__init.sql` (테이블·트리거·인덱스), `V11`(user_id VARCHAR(6) ALTER) — **수정 금지, 신규 DROP으로 처리** |
| 문서 잔재 | `프로젝트_설명.txt`(테이블 설명 섹션), `.claude/rules/domain-security-policy.md`(35행 언급) |
| CLAUDE.md | 병원 예약 언급 **없음** |

**FK 의존성**: `hospital_reservations`는 leaf 테이블. `fk_hospital_reservations_user` → `users(id)` (outbound, ON DELETE CASCADE) 1개만 존재하고, 이 테이블을 부모로 참조하는 **inbound FK는 없음** → DROP 순서 제약/CASCADE 불필요.

## 3. 제거한/변경한 파일 전체 목록

**코드 제거**: 없음 (대상 Java 코드 0개).

**신규**:
- `src/main/resources/db/migration/V24__drop_hospital_reservations.sql` — `DROP TABLE IF EXISTS hospital_reservations;` (트리거·인덱스 동반 제거).

**문서 수정**:
- `프로젝트_설명.txt` — DB 테이블 목록에서 `hospital_reservations` 섹션(13줄) 제거.
- `.claude/rules/domain-security-policy.md` — 탈퇴 CASCADE 삭제 목록에서 `(잔존 시 hospital_reservations)` 언급 제거.
- `docs/progress.md` — 작업 기록 추가.
- `docs/(2026-05-30) feature-remove-hospital-reservation.md` — 본 문서.

## 4. 대시보드 변경 사항

**없음.** 관리자/보호자 대시보드 어디에도 병원 예약 집계 코드가 없어 응답 구조·필드 변화 없음. 사용자/이상감지/문의 등 다른 통계는 영향 없이 보존.

## 5. DB 마이그레이션 내용 (V24)

```sql
DROP TABLE IF EXISTS hospital_reservations;
```

leaf 테이블이므로 단순 DROP으로 충분(CASCADE 불필요). 트리거 `trg_hospital_reservations_updated_at`, 인덱스 `idx_hospital_reservations_user_id`/`idx_hospital_reservations_appointment_at`는 테이블과 함께 자동 제거됨. 기존 `V1`~`V23`은 수정하지 않음.

## 6. 빌드 / 테스트 결과

- 작업 전 베이스라인: `./gradlew build -x test --no-daemon` → **BUILD SUCCESSFUL** (EXIT 0).
- 작업 후: `./gradlew build -x test --no-daemon` → **BUILD SUCCESSFUL** (코드 변경이 없어 컴파일 영향 없음, 마이그레이션 SQL은 런타임에 Flyway가 검증).

## 7. 마이그레이션 적용 안내 ⚠️ (수동 적용 필요)

- 본 마이그레이션은 **비가역 DDL**로, 적용 시 `hospital_reservations` 테이블과 데이터가 **영구 삭제**됨(현재 데이터는 없거나 미사용).
- **dev 환경에서 먼저 검증** 후 운영 적용. 적용 전 운영 DB 백업/스냅샷 확인 필수.
- 실제 적용(컨테이너 재시작 등)은 **수동으로 결정**한다. 본 작업은 마이그레이션 파일 추가까지만 수행.

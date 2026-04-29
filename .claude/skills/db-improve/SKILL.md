---
name: db-improve
description: DB·SQL·JPA 레이어 성능 개선이 필요할 때 사용. "N+1", "쿼리 느려", "인덱스 추가", "fetch join", "쿼리 최적화", "DB 튜닝" 같은 요청에서 발동. PostgreSQL 17 + Spring Data JPA + Flyway 환경 기준. 앱 레이어(스레드·캐시·네트워크) 성능은 performance-check 영역.
---

## 목적
JPA/SQL/인덱스 레벨에서 쿼리 횟수와 응답 시간을 줄인다.

## 입력/스코프
- 기본: 현재 브랜치 변경분 + 변경된 Entity·Repository·Service
- 사용자 지정 시 특정 도메인 (`domain/connection`, `domain/anomaly` 등)
- 분석 대상 파일: `*Repository.java`, `*Service.java`, `domain/**/*.java` (Entity), `db/migration/V*__*.sql`

## 절차
1. **Entity 관계 매핑 점검** — `@OneToMany`, `@ManyToOne`, `@ManyToMany` 의 `fetch` 전략 전수 확인
2. **Repository 메서드 검토** — 각 쿼리 메서드별 실행 계획 추정
3. **호출 경로 추적** — Service에서 Repository 호출이 루프 안에 있는지, 콜렉션 순회 중 LAZY 접근이 있는지
4. **마이그레이션 점검** — `db/migration/`에서 인덱스·제약조건 누락 확인
5. **수정안 적용** — 쿼리 변경은 운영 데이터에 영향 가므로 사용자 확인 후
6. **검증** — `./gradlew build -x test --no-daemon`, 가능하면 `EXPLAIN ANALYZE` 결과 첨부
7. **커밋** — `refactor: <도메인> 쿼리 최적화` 또는 `infra: <도메인> 인덱스 추가`

## 검출 기준 (이 프로젝트 특화)

### N+1 (가장 흔한 원인)
- `@OneToMany(fetch = FetchType.LAZY)` + Service에서 콜렉션 순회 → fetch join 또는 `@EntityGraph`
- `findAll()` 후 각 엔티티의 LAZY 연관 접근 → `JOIN FETCH` 명시
- DTO 변환 메서드 안에서 LAZY 필드 접근 → Projection DTO 또는 fetch join

### 쿼리 효율
- `findById` 반복 호출 → `findAllById(Collection)` 한 방
- 카운트만 필요한데 `findAll().size()` → `count()` / `existsBy*()`
- `@Query` 의 `nativeQuery=true` 남발 → JPQL/Criteria로 가능한지 검토
- 페이지네이션 누락된 목록 조회 (단, 본 프로젝트는 PR #98로 페이지네이션 제거됨 — `Top N` 쿼리 또는 명시적 limit 사용 권장)
- 큰 콜렉션 `IN (...)` (1000건+) → 청크 분할

### 인덱스
- **FK 컬럼**: `@JoinColumn`에 명시적 인덱스 없으면 추가 (PostgreSQL은 FK 자동 인덱스 생성 안 함)
- **자주 필터되는 컬럼**: `where status = ?`, `where user_id = ? and created_at > ?` 같은 패턴 → 단일/복합 인덱스
- **정렬 컬럼**: `order by created_at desc` 자주 쓰면 `(user_id, created_at desc)` 복합 인덱스
- **`TIMESTAMPTZ` 시계열**: 대용량 이벤트 로그면 BRIN 인덱스 검토 (`anomaly`, `ai` 도메인)
- **JSONB**: 필드 검색이 자주 일어나면 GIN 인덱스
- **부분 인덱스**: `where deleted = false` 같은 조건이 항상 붙으면 partial index
- **유니크 제약**: 비즈니스적으로 유일해야 하는 키(`userId`, `phoneNumber`) → DB 레벨 unique

### Projection / DTO
- 응답에 일부 필드만 필요한데 Entity 전체 로딩 → interface projection 또는 record DTO 직접 매핑
- `select e from Entity e` 후 변환 → `select new dto(...) from Entity e`

### 트랜잭션
- 읽기 전용 메서드에 `@Transactional(readOnly = true)` 누락 → 읽기 전용 최적화 + dirty checking 비활성화
- Service 클래스 레벨 `@Transactional` 만으로 부족한 경우(긴 외부 호출 포함) → 메서드 단위로 분리

### 마이그레이션 안전성
- 대용량 테이블에 `ALTER TABLE ADD COLUMN NOT NULL DEFAULT` → `ADD COLUMN` 후 backfill 후 NOT NULL (Postgres 11+ 는 default 있는 ADD COLUMN은 안전, 그래도 락 시간 확인)
- `CREATE INDEX` → 운영은 `CREATE INDEX CONCURRENTLY`
- Flyway 마이그레이션은 idempotent하지 않으므로 재시도 위험 명시

## Non-goals
- 앱 레이어 캐시 / 비동기 / 스레드 풀 → `performance-check`
- 보안(권한 누락된 쿼리) → `security-scan`
- 단순 가독성 리팩토링 → `refactor`
- 의존성 버전 → `dependency-check`

## 출력 포맷

### 1) 요약 표
| # | 위치 | 종류 | 예상 효과 | 심각도 |
|---|---|---|---|---|

종류: `N+1` / `index-missing` / `query-rewrite` / `projection` / `transaction` / `migration-risk`

심각도:
- **Critical**: 운영 장애 위험 (대용량 테이블 풀스캔, 락 타임아웃 가능성)
- **High**: 명확한 N+1, 자주 호출되는 경로의 풀스캔
- **Medium**: 일반 쿼리 효율 개선
- **Low**: 미세 튜닝

### 2) 항목별 상세
- **위치**: 파일:라인 + 호출 경로
- **현재 동작**: 추정 쿼리 횟수·실행 계획 (예: "조회 1회 + 각 row마다 children 1회 = 1+N")
- **수정안**: 코드 또는 SQL/마이그레이션
- **예상 영향**: "쿼리 N→1, 응답 시간 약 X% 단축 추정"
- **위험**: 마이그레이션 락 시간, 인덱스 재빌드 시간 등

### 3) 마이그레이션 파일 (필요 시)
경로: `src/main/resources/db/migration/V{next}__<설명>.sql`
- `CREATE INDEX CONCURRENTLY` 권장
- 롤백 스크립트도 함께 제시

## 작업 마무리
- `./gradlew build -x test --no-daemon` 통과
- 가능하면 `EXPLAIN (ANALYZE, BUFFERS)` 결과를 PR 본문에 첨부
- 커밋 메시지: `refactor: <도메인> N+1 제거` / `infra: <도메인> 인덱스 추가`

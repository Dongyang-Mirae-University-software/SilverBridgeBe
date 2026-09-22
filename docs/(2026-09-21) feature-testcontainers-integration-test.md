# Testcontainers 통합 테스트 도입 - 로컬 단위 테스트 유지 + vkcs 실행 + CD 배포 전 자동 실행

> 2026-09-21 · 인프라(테스트) · 마이그레이션 없음 · 점검 대장 "실 DB 통합 테스트"(M-1, 2026-06-11부터 ❌) 해소 1단계

## 왜

단위 테스트 609건이 전부 목(mock) 기반이라 **DB와 실제로 주고받는 부분은 한 번도 실행되지 않았다** - Flyway 마이그레이션,
JPQL·네이티브 쿼리, UNIQUE·CHECK·ON CONFLICT 같은 제약. 2026-09-21에만 V52·V53과 새 JPQL 2개를 "배포 서버 기동 로그"로만
확인했다. 2026-08-06에 한 번 착수했다가 작업 환경에 Docker가 없어 보류됐었다.

## 구성

| 구분 | 명령 | 어디서 | Docker |
|---|---|---|---|
| 단위 테스트 (기존) | `./gradlew test` | 로컬 | 불필요 |
| **통합 테스트 (신규)** | `./gradlew integrationTest` | vkcs (Docker 있음) | 필요 |

- 통합 테스트는 별도 소스 폴더 **`src/integrationTest/java`** (Gradle JVM test suite). `check`·`build`에 연결하지 않아
  로컬 `./gradlew test`·`build`는 지금처럼 Docker 없이 돈다.
- 기반 `PostgresIntegrationTest`: `@DataJpaTest` 슬라이스 + 실제 `postgres:17` 컨테이너 1개를 JVM 전체가 공유.
  - 전체 앱을 띄우지 않는 이유: 시작 시 필수 설정 검증기(카카오 secret 등)·Redis·Firebase·AI WS·스케줄러가 비밀값 없이는 뜨지 않는다.
  - Flyway V1~끝까지 실제 적용 + `ddl-auto=validate`(엔티티 ↔ 스키마). `@CreatedDate`를 위해 `JpaAuditingConfig`만 가져온다.
  - 이미지는 운영과 같은 `postgres:17`(dev DB 17.9 확인). 테스트마다 트랜잭션 롤백.
- 의존성(Boot 4.0.8 관리): `spring-boot-starter-data-jpa-test`, `spring-boot-starter-flyway-test`,
  `testcontainers-postgresql`·`testcontainers-junit-jupiter` 2.0.5(Docker 29 호환 버전). OWASP 스캔 대상에서 제외.

## 첫 테스트 (10건)

| 클래스 | 검증 |
|---|---|
| `MigrationIntegrationTest` | 빈 DB에 전 마이그레이션 성공, 최신 버전 = 소스의 마지막 V 파일(새 V 추가 시 수정 불요) |
| `AdminAnomalyQueryIntegrationTest` (5) | 관리자 이상감지 v2 JPQL - 전체 조회·최신순, 기간·유형·상태 조건, 검색어 ID/sessionId 목록(매칭 불가 값 채움 포함), LIKE `escape '\'`(대조군 포함), GROUP BY 프로젝션 |
| `AnomalyReviewConflictLogIntegrationTest` | 동수 안내 기록 `ON CONFLICT DO NOTHING` - 두 번째 insert 0, 1행 유지 |
| `EnumCheckConstraintIntegrationTest` (3) | `Status`·`AdminAuditAction`·`AnomalyReviewStatus` 전 값이 실제 CHECK 통과 |

## vkcs 실행 - `tools/integration-test.sh`

```bash
ssh vkcs-linux
~/SilverBridgeBe/tools/integration-test.sh                      # origin/dev 최신
~/SilverBridgeBe/tools/integration-test.sh feature/xxx          # 머지 전 PR 브랜치 검증
```

- 대상 커밋을 `git archive`로 임시 폴더에 풀어서 돈다 - 배포 폴더(`~/SilverBridgeBe`)·운영 컨테이너는 건드리지 않는다.
- 호스트에 JDK 21이 없어(Java 1.8뿐, 설치하지 않는다) `gradle:9.4.1-jdk21` 컨테이너 안에서 돌린다. 호스트 docker 소켓 + host 네트워크 +
  `TESTCONTAINERS_HOST_OVERRIDE=localhost`.
- 컨테이너가 root로 만든 파일은 끝나기 전에 `mjng` 소유로 되돌린다(임시 폴더 삭제용).
- Gradle 캐시는 도커 볼륨 `sb-gradle-it-cache`(약 450MB). 리포트는 `~/sb-it-reports/latest/index.html`.

**소요 시간 (vkcs, 2026-09-21 실측)**: 첫 실행 **14분 18초**(이미지 pull + 의존성 다운로드 + 컴파일) / 캐시가 찬 뒤 **31초**.

## CD 연동

`cd.yml` 배포 단계에서 `docker compose build` **직전**에 `bash tools/integration-test.sh HEAD`를 실행한다. 실패하면 `set -e`로
배포가 멈춘다(우회 입력 없음 - 결정). PR 자동 실행은 하지 않는다 - 저장소가 public이라 self-hosted 러너에서 fork PR 코드를 돌리면
`.env.dev`가 위험하다. 트리거는 기존 그대로(dev push·수동).

### Gradle 빌드 캐시 - 입력이 같으면 테스트를 다시 돌리지 않는다 (2026-09-22 결정: 그대로 둔다)

프로젝트는 `org.gradle.caching=true`이고, 통합 테스트도 Gradle 캐시 볼륨(`sb-gradle-it-cache`)을 공유한다. 그래서 **테스트 입력
(메인·테스트 코드, `src/main/resources`의 마이그레이션 SQL, 의존성)이 이전에 통과한 실행과 완전히 같으면 `integrationTest`가
`FROM-CACHE`로 끝나고 실제로는 돌지 않는다.**

- 실제 사례: PR #254 머지 후 첫 CD(2026-09-22, run 35695236695)는 `> Task :integrationTest FROM-CACHE`, 16초 - 머지 전 수동 검증과
  코드가 같아 그 통과 결과를 재사용했다. 로그의 `[integration-test] 통과`만 보고 "방금 DB로 돌았다"고 읽지 말 것.
- 게이트는 유지된다 - 코드·테스트·마이그레이션이 하나라도 바뀌면 입력이 달라져 반드시 실제로 돈다. 캐시로 건너뛰는 것은
  "같은 입력으로 이미 통과한" 경우뿐이다.
- **알려진 빈틈**: 테스트 DB 이미지 태그(`postgres:17`)의 내용 변경(마이너 버전 갱신)은 Gradle 입력이 아니다. 코드 변경 없이는
  새 이미지로 다시 확인하지 않는다.
- 항상 실제로 돌리려면 `build.gradle`에서 `integrationTest`만 캐시를 끄면 된다(`outputs.cacheIf { false }` + `outputs.upToDateWhen { false }`,
  배포마다 약 30초 증가). 수동으로 한 번 강제하려면 스크립트 안 gradle 명령에 `--rerun-tasks`를 붙이거나 캐시 볼륨을 지운다
  (`docker volume rm sb-gradle-it-cache` - 다음 실행이 첫 실행처럼 느려진다).
- 확인법: CD 로그에서 `Task :integrationTest` 줄이 `FROM-CACHE`인지, 또는 `Successfully applied N migrations`가 찍혔는지 본다.

## 검증 (2026-09-21)

- 로컬: `./gradlew test` 통과(Docker 없이), `./gradlew build -x test` 통과.
- vkcs: 통합 테스트 **10 / 10 통과**, Flyway "Successfully applied 53 migrations", 테스트 컨테이너 잔존 0.
- 일부러 깨뜨린 단언 1개 → `1 failed`, **exit=1**로 실패가 잡히는 것 확인 후 원복(vkcs 임시 사본에서만).

## 한계·다음 단계

- `@DataJpaTest` 슬라이스라 서비스·트랜잭션 전파·이벤트 리스너는 아직 실DB로 안 본다 - 점검 대장 H-1(탈퇴 리스너의 AFTER_COMMIT REQUIRED 쓰기)은
  전체 컨텍스트(테스트용 설정·외부 연동 목)가 있어야 판정할 수 있다.
- 머지 전 검증은 수동(스크립트)이다 - 마이그레이션·JPQL을 바꾸는 PR은 머지 전에 브랜치로 한 번 돌린다.
- 확대 후보: `ConnectionServiceTest`가 "@DataJpaTest 영역"이라 남겨 둔 케이스, `medication_reminder_log` UNIQUE 선점, 이상감지 E-2 동시성.

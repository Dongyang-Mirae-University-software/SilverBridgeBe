# V17 DROP TABLE 제안서 (미적용 — 별도 PR + 승인 필요)

작성일: 2026-05-19
관련 작업: 미검증 API 정리 (docs/progress.md 2026-05-19 항목)
상태: 제안만 함. 실제 마이그레이션 파일 미생성. 사용자 승인 후 별도 PR로 진행.

## 배경

2026-05-19 정리로 call/game/ai/anomaly 도메인의 JPA 엔티티가 제거됨.
테이블은 DB에 그대로 남아 있어 데이터 정합성·운영에는 무해하나, 미사용
스키마 정리를 위해 DROP을 별도 PR로 검토한다.

비가역 DDL이므로 이 PR과 분리하며, 운영 DB 백업 확인 후 적용한다.

## 마이그레이션 번호

기존 마이그레이션이 V16(V16__add_token_reuse_access_action.sql)까지 존재.
따라서 신규 번호는 V15가 아닌 V17.
V1~V16 기존 파일은 절대 수정하지 않는다.

## DROP 대상 (확정)

| 테이블 | 생성 위치 | 제거 사유 |
|--------|-----------|-----------|
| game_results | V1__init.sql:122 | domain/game 전체 제거 |
| anomaly_events | V1__init.sql:105 | domain/anomaly 전체 제거 |
| character_expressions | V9__character_expression_history.sql:1 | domain/ai 전체 제거 |

세 테이블 모두 자식(leaf) 테이블로, 다른 테이블이 부모로 참조하지 않음
(FK는 users 등을 향하는 방향). DROP 순서 제약 없음.

call/SOS 기능은 전용 테이블이 없었음 (WebSocket/FCM 경유, DB 미적재) — DROP 대상 없음.

## DROP 비대상 (유지)

- admin_audit_logs — AdminAuditLogService 쓰기 경로로 공지사항 관리에서 계속 사용
- announcements / announcement_drafts — 공지사항 유지
- fcm_tokens — FCM 유지
- connections / users / refresh_tokens / access_logs 등 — 유지

## 제안 SQL (V17__drop_unused_feature_tables.sql 초안)

```sql
-- 2026-05-19 미검증 API 정리: call/game/ai/anomaly 도메인 엔티티 제거에 따른
-- 미사용 테이블 정리. 비가역 — 적용 전 운영 DB 백업 확인 필수.

DROP TABLE IF EXISTS game_results;
DROP TABLE IF EXISTS anomaly_events;
DROP TABLE IF EXISTS character_expressions;
```

## 선택 사항 (별도 판단)

- V15__add_users_role_status_created_at_index.sql 가 만든 users(role, status,
  created_at DESC) 인덱스는 관리자 회원검색 제거로 현재 사용처 없음.
  성능·용량에 미치는 영향이 작아 본 제안서에서는 DROP INDEX를 포함하지 않음.
  추후 인덱스 정리가 필요하면 동일 V17 또는 후속 마이그레이션에서 함께 검토:
  `DROP INDEX IF EXISTS idx_users_role_status_created_at;`
  (실제 인덱스명은 V15 파일에서 확인 후 기재)

## 적용 절차 (승인 후)

1. 운영/스테이징 DB 백업 또는 스냅샷 확인
2. src/main/resources/db/migration/V17__drop_unused_feature_tables.sql 생성
3. 스테이징에서 Flyway 적용 검증 (flyway info/migrate)
4. 별도 PR (base: dev), 본문에 비가역 DDL 명시
5. 머지 후 dev 자동 배포 시 마이그레이션 실행 확인

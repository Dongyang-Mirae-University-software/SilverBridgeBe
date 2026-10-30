---
paths:
  - "**/dashboard/**"
  - "**/*GuardianDashboard*"
---

# 도메인 정책 - 보호자 대시보드 통합 API

> 이 파일은 위 경로의 파일을 다룰 때 자동으로 로드된다. 코드를 열지 않고 이 도메인 작업을 설계·답변할 때는 직접 Read 한다.
> 공통 불변 규칙은 `domain-security-policy.md`(항상 로드).

## 기능 요약 (CLAUDE.md §1에서 이동, 2026-10-30)

- **보호자 대시보드 통합 API**(dashboard, 2026-10-08, 마이그레이션 없음): `GET /api/guardian/dashboard?wardId=`(GUARDIAN 전용, 생략=ACTIVE 연결 전원, guardianId는 토큰에서만 - 파라미터 없음). **이상감지·SOS·복약 서비스의 조회 메서드를 호출만** 하고(자체 인가 `isActiveConnection`/`getActiveWardIds` + 하위 서비스 재인가), 정서·활동·병원 예약은 BE 데이터가 없어 **필드를 만들지 않는다**. "확인이 필요한 일" = 이상감지 PENDING+CONFLICTED + 복약 미체크(SOS 미포함). **칸 하나가 실패하면 0이 아니라 null + `unavailable`**(`total`도 null; 연결 0명은 실제로 센 0 값이라 칸 null=조회 실패 전용) - 대시보드 서비스에 `@Transactional`을 걸지 말 것(rollback-only 전파로 칸별 격리가 깨진다). SOS "해결됨" 없음. 상세 `docs/(2026-10-08) feature-guardian-dashboard-api.md`.

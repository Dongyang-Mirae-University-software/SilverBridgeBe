# 이상감지 10/6 점검 후속 수정 (2026-10-07, branch `fix/anomaly-1006-audit`, 마이그레이션 없음)

점검 문서: `(2026-10-07) audit-anomaly-1006-bundle.md`. 사용자가 "전부 반영"으로 승인했다.

| 항목 | 처리 |
|---|---|
| 🟡 Y-1 쿨다운이 전달 0건에도 유지 | `AnomalyNotificationCooldown.release` 추가. 리스너가 dispatch 결과가 미전달(`FAILED`·`NOT_SENT`)이거나 예외면 **그 수신자 키만** 해제(SOS-G09와 같은 판단, 수신자 단위). `DELIVERED`·`SMS_FALLBACK`은 유지. 쿨다운에 걸려 생략한 수신자는 건드리지 않는다 |
| 🟡 Y-2 로그 예외 원문 | 리스너 `log.error(..., e)` → 클래스명, 쿨다운 `e.getMessage()` → 클래스명. 두 파일을 `LogRawExceptionGuardTest` 대상에 추가 |
| 🟡 Y-3 늦은 접수 중복 | 코드 변경 없음, 정책 문서에 수용 명시 |
| 🟢 G-1 LMS | 주석 정정 + 정책 문서에 수용 명시 |
| 🟢 G-2 이력 본문 | 수용(표시 차이만), 정책 문서에 명시 |
| 🟢 G-3 `sent` | 로그를 `전달=N명`(결과 기준)으로 |
| 🟢 G-4 정책 문서 문구 | 흉기·낙상 대체 문구 반영 |
| 🟢 G-5 FCM 접수 기준 | 수용, 정책 문서에 명시 |
| 테스트 1 | 디스패처에 알림톡 채널 mock 3건(알림톡 전달 → 대체 없음 / 실패 → 대체 / 본인 스킵 → 대체) |
| 테스트 2 | `AlimtalkTemplateMappingGuardTest` - 실제 `application.yaml` 바인딩, 템플릿은 `ANOMALY_DETECTED`만 |
| 테스트 3 | `AnomalyFallWeaponPersistenceIntegrationTest`(실 Postgres, vkcs에서만 실행) |
| 테스트 4 | 쿨다운 키 종류 분리·release 같은 키·release 장애 삼킴, 리스너 해제 조건 5건 |

## 배포 후 확인
- `[ANOMALY] 이상감지 알림 발송: ... 전달=N명` 로그 형식 변경(전달 결과 기준).
- 통합 테스트(`FallWeaponPersistence`)는 로컬 Docker가 없어 vkcs `tools/integration-test.sh fix/anomaly-1006-audit`로 확인.

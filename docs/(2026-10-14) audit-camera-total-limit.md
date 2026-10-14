# 서비스 전체 카메라 등록 상한 점검 (2026-10-14, 템플릿 B)

대상: PR #347 (`2cda2ab`) - `camera.max-total`, 409 `CAMERA_LIMIT_REACHED`, 어드바이저리 락. 점검만 했고 코드는 바꾸지 않았다.

## 판정: 이슈 High 0 / Medium 1(수용 제안) / Low 2

## A. 보안·인가
| 항목 | 결과 | 근거 |
|---|---|---|
| 등록 API 역할 | PASS | `WardCameraController` 클래스 `@PreAuthorize("hasRole('WARD')")`, 상한은 그 안쪽 서비스에서 판정 |
| 응답 정보 노출 | PASS | 409 메시지에 소유자·다른 사용자 정보·현재 수 없음. 로그 `[CAMERA-LIMIT]`는 상한값만(사용자 ID 없음) |
| 어드바이저리 락 키 충돌 | PASS | 코드 전체에서 `pg_advisory` 사용처가 이 한 곳(`5_100_001`) |
| 락 해제·교착 | PASS | 트랜잭션 수준 락이라 커밋·롤백에 자동 해제. 락 하나만 잡아 순환 대기 없음. 락 뒤 INSERT가 같은 트랜잭션 |

## B. 기능 정합성
| 항목 | 결과 | 근거 |
|---|---|---|
| 재등록(멱등) 보존 | PASS | 새 카메라 분기에서만 검사, 단위·통합 테스트로 고정 |
| 슬롯 반환 경로 | PASS | 본인 삭제, 역할 변경(`deleteAllByWard`), 탈퇴(`fk_cameras_ward` ON DELETE CASCADE) 모두 행이 지워져 즉시 반환 |
| 사용 중지 카메라 | 의도 | `is_active=false`도 등록 수에 센다(문서화됨) |
| 검사 순서 | PASS | 방 이름 400 → 방 중복 409 → 상한 409 |
| 상한 초과 기존 데이터 | 의도 | 지우지 않고 신규만 막는다 |

## C. 구조·계약
- FE 호환 PASS: 오류 응답의 `message`가 서버 문구 그대로 쓰인다(`errorHandler.ts`가 `data.message`를 우선). `CameraRegisterModal`의 `onError` 폴백이 `error.message`를 표시하므로 **FE 코드 변경 없이 안내가 보인다**(코드 분기를 원하면 `code === 'CAMERA_LIMIT_REACHED'`로 가능).
- `count()`는 전체 행 카운트라 카메라 수가 작은 규모에서만 적합하다(3 상한에서는 문제 없음).

## D. 이슈
| # | 등급 | 내용 | 제안 |
|---|---|---|---|
| M-1 | 🟡 Medium(수용 제안) | 전체 상한이 3이라 **한 피보호자 계정이 방 3개를 등록하면 슬롯을 독점**해 다른 사용자가 등록할 수 없다. 관리자 카메라 삭제 API가 없어 풀려면 DB 수동 삭제(또는 그 사용자의 역할 변경·강제 탈퇴)뿐이다 | 학생 프로젝트 규모라 수용. 운영이 부담되면 피보호자당 상한(예: 1~2)을 추가하거나 관리자 삭제 API를 별도 검토 |
| L-1 | 🟢 Low | Swagger 409 설명(`WardCameraController` 등록 `@ApiResponse`)에 `CAMERA_LIMIT_REACHED`가 빠져 있다(`CAMERA_LABEL_DUPLICATED`만) | 설명 한 줄 추가(코드 변경, 별도 PR) |
| L-2 | 🟢 Low | 신규 통합 테스트(`CameraTotalLimitConcurrencyIntegrationTest`)가 vkcs 스크립트 "통과"는 확인했으나 리포트 파일(컨테이너 내부)로 실행 건수를 직접 확인하지 못했다 | 다음 vkcs 실행 때 테스트 리포트 위치를 확인해 건수 확인 |

## E. 이어서 알려둘 점(범위 밖)
- 클립 행·파일은 카메라 삭제 후에도 남는다(기존 구조). 이번 gosky 초기화에서 13건이 남았고 보관 만료 경로로 정리된다.

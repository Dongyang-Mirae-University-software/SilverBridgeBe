# 보호자 약 여러 개 삭제 기능 점검 (BE #371 · FE #100, 2026-10-23)

대상: BE #371(`c7e7f8b`, `POST /api/guardian/ward/{wardId}/medication/delete-batch`) · FE #100(`5885853`, 보호자 카드 "아침 약 모두 삭제"·"약 전부 삭제").
방법: 코드 읽기(템플릿 B) + 테스트 결과 + gosky 실서버 **데이터를 바꾸지 않는** 호출. **점검 중 코드는 바꾸지 않았고**, 🟢 2건은 사용자 지시로 BE #372·FE #103에서 반영했다(아래 처리 결과).

## 환경
- BE `./gradlew build` 통과, 통합 `MedicationDeleteBatchIntegrationTest` vkcs 통과(`2d2b798`). FE `npm test` 159·`test:browser` 57 통과, tsc 0, build 성공.
- 배포: BE vkcs(CD 성공)·gosky `c7e7f8b`, FE gosky `5885853`.

## 엔드포인트 × 역할
| 경로 | GUARDIAN | WARD | ADMIN | 근거 |
|---|---|---|---|---|
| `POST /api/guardian/ward/{wardId}/medication/delete-batch` | 허용(ACTIVE 연결 피보호자의 약만) | 403 | 403 | 클래스 `@PreAuthorize("hasRole('GUARDIAN')")`, `MedicationControllerSecurityTest` |

## A. 보안·인가 - PASS
| 항목 | 결과 |
|---|---|
| 연결 | `requireActiveConnection`(형식 검사 + `isActiveConnection`, `getMyWards` 아님) - gosky 미연결 피보호자 **403 `MEDICATION_NOT_AUTHORIZED`** |
| 경로 피보호자 ≠ 약 주인 | 403 + `[IDOR-ATTEMPT]`(guardianId·wardId·medicationId만) - 연결된 피보호자 경로로 남의 약을 지우는 우회 차단 |
| 이미 삭제된 남의 약 | 주인 확인이 삭제 여부 확인보다 먼저라 403(건너뛰지 않음) |
| 입력 | 1~100개·null ID 400(gosky 빈 목록 **400 `INVALID_INPUT`**), 같은 ID **400 `MEDICATION_DELETE_BATCH_DUPLICATE`**, 없는 약 **404**(gosky 확인) |
| 존재 노출 | 남의 약 403 / 없는 약 404로 "그 ID가 있다"까지는 드러난다 - 2026-07-14 IDOR 정책(403 명시 안내)과 같은 수준, 내용은 응답에 없음 |
| 로그 | 일괄 삭제 로그 wardId·guardianId·요청 수·삭제 수, 약 이름 없음 |

## B. 기능 정합성 - PASS
| 항목 | 결과 |
|---|---|
| 전부 성공/전부 실패 | `@Transactional`, 예외 시 롤백 - 실 DB 통합 테스트로 고정(섞임 403 → 내 약 삭제 0건) |
| 이미 삭제됨 건너뜀 | 다른 보호자가 방금 지운 약 때문에 나머지가 실패하지 않음, 응답 수에서 제외 |
| soft delete | 단건과 같은 `softDelete` - 행·복용 이력 보존 |
| 삭제 후 영향 | 복약 알림(`MedicationReminderPlanner`)·미복용 요약(`MedicationMissedAlertPlanner`)·카드·피보호자 화면 조회 모두 `deletedAt IS NULL` - 지운 약은 알림·요약에서 빠진다 |
| 피보호자 화면 | 알림 없음(단건과 같다). 열어 둔 화면에서 지워진 약을 누르면 404 → 다시 불러오기·안내(FE #97 M-1) |
| FE 범위 | 화면에 보이는 약 ID만 보냄 - 다른 보호자가 방금 추가한 약은 지우지 않는다. 묶음은 그 시간대 약, 전부는 카드의 약 전체 |
| FE 흐름 | `confirm` 취소면 요청 없음, 처리 중 버튼 비활성, 성공·실패 모두 목록 다시 불러오기, 실패 안내 `role=alert`, 다음 시도 시작 때 안내 지움 |

## C. 구조·계약 - PASS
- 단건 삭제와 `softDelete` 공유(동작 동일), 처리 순서 ID 오름차순. 응답 `data`는 삭제한 수(정수).
- 새 ErrorCode는 DB CHECK 대상 아님, 마이그레이션 없음. Swagger에 전부/전무·건너뜀·이력 보존 명시.

## D. 테스트 - PASS
BE 단위 4(전부 삭제·건너뜀 / 연결 없음 403(약을 읽지 않음) / 섞임 403 / 없는 약 404·중복 400) + 권한 + DTO + 실 DB 통합 2. FE 브라우저 3(묶음 삭제 취소·확인 / 전부 삭제·실패 안내 / 약 1개면 버튼 없음).

## 이슈
Critical·High·Medium 없음.

### 🟢 L-1 약 수정과 삭제가 거의 동시에 일어나면 지운 약이 되살아날 수 있다 (BE, 기존부터 있던 경합) - ✅ 수정 완료
`Medication`은 `@Version`·`@DynamicUpdate`가 없어 UPDATE가 모든 컬럼을 쓴다. 보호자 A의 수정(PATCH) 트랜잭션이 약을 읽은 뒤, 보호자 B의 삭제가 먼저 커밋되면 A의 커밋이 읽어 둔 `deleted_at = NULL`을 다시 써서 **삭제가 취소되고 알림이 계속 나간다**. 단건 삭제에도 있던 경합이며 창은 수정 트랜잭션 길이(밀리초)라 드물다. 이상감지 판정에서 같은 종류를 `@DynamicUpdate`로 막은 전례가 있다(2026-09-30 E-2).
- 제안: `Medication`에 `@DynamicUpdate`(바뀐 컬럼만 UPDATE - 수정이 `deleted_at`을 건드리지 않음) + 실 DB 테스트. 수정 쪽 잠금(`findByIdForUpdate`)도 대안.

### 🟢 L-2 단건 삭제는 실패해도 화면에 안내가 없다 (FE, 기존 동작) - ✅ 수정 완료
여러 개 삭제는 실패 안내가 있지만 약 한 개 [삭제]는 실패해도 아무 반응이 없다(다시 불러오기도 성공 때만).
- 제안: 단건 삭제도 같은 안내·다시 불러오기.

## 처리 결과 (2026-10-23)
| 항목 | 반영 | 확인 |
|---|---|---|
| L-1 | BE #372(`81d5279`): `Medication`에 `@DynamicUpdate`(바뀐 컬럼만 UPDATE, 엔티티 주석에 "떼지 말 것") + `MedicationDeletedRaceIntegrationTest`(수정이 읽음 → 삭제 REQUIRES_NEW 커밋 → 수정 커밋) | 테스트만 먼저 올려 vkcs **실패**(삭제 취소 재현, `f968f15`) → 수정 후 **통과**(`979afdb`). 두 서버 반영 |
| L-2 | FE #103(`9cf9a92`): 단건 삭제 실패 시 같은 안내, `onSettled`로 실패해도 목록 무효화 | 브라우저 테스트 +1, `onSuccess`로 되돌리면 실패(변이). gosky 반영 |

## 종합 판정
**PASS** - 인가(연결·경로 피보호자·형식)·전부 실패·이력 보존·알림 제외가 코드·테스트·실서버 거절 응답으로 확인됐고, 🟢 2건(기존 경합·동작)은 BE #372·FE #103에서 반영했다.

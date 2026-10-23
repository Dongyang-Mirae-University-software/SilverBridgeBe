# 복약 시간대 묶음 기능 점검 (BE #369 · FE #95·#96, 2026-10-23)

대상: BE #369(`4e3f430`, 묶음 체크 API·복약 알림 묶음) · FE #95(`ac76a23`, 보호자 카드 시간대 묶음·약 추가 두 줄 배치·글씨) · FE #96(`c37daa0`, 피보호자 화면 묶음·"아침 약 다 먹었어요").
방법: 코드 읽기(템플릿 B) + 테스트 결과 확인 + gosky 실서버 확인. **점검 중 코드는 바꾸지 않았고**, 지적 5건은 사용자 지시로 FE #97·BE #370에서 모두 반영했다(아래 처리 결과).
설계: `(2026-10-23) design-medication-time-group.md`.

## 환경
- BE `./gradlew build` 통과(전체 테스트). FE `npm test` 153·`npm run test:browser` 46 통과, tsc 0, `next build` 성공.
- 배포: BE vkcs(CD 성공)·gosky 모두 `4e3f430`, 기동 로그 `AiLiveStreamSubscriber` 연결 확인. FE gosky `c37daa0`, `/ward/medication` 200.
- 실서버: gosky에서 보호자 토큰으로 `POST /api/ward/medication/intake/batch` → **403**. 피보호자 테스트 계정이 없어 묶음 체크 성공 흐름은 실서버에서 누르지 못했다(테스트로만 확인).

## 엔드포인트 × 역할
| 경로 | WARD | GUARDIAN | ADMIN | 근거 |
|---|---|---|---|---|
| `POST /api/ward/medication/intake/batch` | 허용(본인 약만) | 403 | 403 | 클래스 `@PreAuthorize("hasRole('WARD')")`, `MedicationControllerSecurityTest` + gosky 실호출 403 |

## A. 보안·인가 - PASS
| 항목 | 결과 |
|---|---|
| 역할 | 클래스 레벨 WARD. 보호자용 체크 경로 없음(복약 불변 규칙 ① 유지) |
| IDOR | 약마다 단건과 같은 `requireOwn` - 남의 약 1개라도 섞이면 403 `MEDICATION_NOT_OWNED` + `[IDOR-ATTEMPT]`(wardId·medicationId만) |
| 입력 | 1~50개·null ID·`taken` 누락 400(DTO), 같은 ID 400(`MEDICATION_INTAKE_BATCH_DUPLICATE`, 저장소 접근 전) |
| 로그·PII | 묶음 체크 로그 wardId·taken·건수, 발송 실패 로그 medicationIds·wardId·attempt - 이름·전화 없음 |
| 남용 | 요청당 50건 상한 + 기존 사용자 요청 상한(분 600) |

## B. 기능 정합성
| 항목 | 결과 |
|---|---|
| 전부 성공/전부 실패 | `markBatch` `@Transactional`, 내부 `mark`/`unmark`는 같은 트랜잭션(private). 예외 시 롤백, `MedicationIntakeChangedEvent`는 AFTER_COMMIT이라 함께 버려진다 - PASS(단 실 DB 고정 테스트 없음, L-3) |
| 잠금 순서 | ID 오름차순 `findByIdForUpdate` - 두 기기가 다른 순서로 보내도 교착 없음(단위 테스트 `inOrder`) |
| 멱등 | 이미 체크된 약은 저장·이벤트 없음 |
| 알림 선점 | `claim()`은 그대로 약 단위·UNIQUE·선점 후 발송. 묶음은 `send()` 메모리 안에서만 - PASS |
| 묶음 키 | (피보호자, 날짜, 시간대, 시각, 회차) - FE `groupMedicationsByTime`(시간대, 시각)과 같은 기준 |
| 문구 | 첫 알림 "아침 약 6개 드실 시간입니다(08:00). 코싹열정 1정, 신일이부프로펜정400mg 1정 외 4개" / 재알림 "아침 약 중 2개가 아직 복용 체크되지 않았어요" - 단정 금지 유지 |
| 문자 길이 | `[제목] 본문` 기준 단건 59B·재알림 묶음 79B는 SMS, 실제 6개 묶음 100B는 **LMS 1건**(전에는 SMS 6건 - 건수·비용 모두 감소, 수용) |
| 푸시 data | `medicationId`(첫 약)·`medicationIds`(쉼표) 추가. FE는 data를 화면 이동 종류에만 쓰고 id는 읽지 않는다 - 호환 |
| 알림 이력 | 묶음당 1행(관리자 알림 이력 건수 감소 - 의도) |
| 피보호자 화면 | 묶음 버튼은 **아직 체크 안 한 약만** 보낸다. 약별 버튼 유지(하나만 풀기). 처리 중 모든 체크 버튼 잠금(`useIsMutating`) |
| 보호자 카드(#95) | "3번 중 1번"은 시간대 묶음 기준, 오늘 등록 전 약은 묶음 판정에서 제외(기존 `beforeRegistered`), 미체크 문구 "아직 다 체크되지 않았어요" |

## C. 구조·계약 - PASS
- 단건 로직 복제 없음(public 단건 메서드가 private `mark`/`unmark` 위임, 묶음도 같은 메서드).
- 새 ErrorCode는 DB CHECK 대상 아님. 마이그레이션·JPQL 변경 없음.
- Swagger에 전부 성공/전부 실패·멱등·WS 동작 명시.

## D. 테스트
- BE: 묶음 체크 5(전부·잠금 순서·응답 순서 / 일부 멱등 / 남의 약 403 / 중복 400 / 해제), 권한(WARD 허용·GUARDIAN 거부), DTO 검증, 알림 묶음 3(6개 1건 문구·data / 재알림 / 다른 시각·사람 따로).
- FE: 피보호자 화면 3(안 먹은 5개만 묶음 체크·다음 시간대·375px / 하나 풀기는 단건 API·전체 풀기 없음 / 약 1개 시간대), 보호자 카드 묶음·펼치기, 묶음 판정 단위 5.
- 빈 곳: L-3(실 DB 롤백), M-1(실패 시 화면).

## 이슈
### 🟡 M-1 묶음 체크가 실패하면 화면에 아무 반응이 없다 (FE) - ✅ 수정 완료
`useMedicationIntakeBatchMutation`의 `onError`는 API 오류를 그냥 넘기고(`reportNonApiError`), 앱에 전역 오류 토스트도 없다. 보호자가 그 사이 약 하나를 지우면(피보호자 화면은 포커스·10초 캐시 전까지 모름) 묶음 전체가 404로 거절되는데, 어르신은 [아침 약 다 먹었어요]를 눌러도 아무 일도 일어나지 않고 다시 눌러도 같다. 단건 버튼도 같은 구조였지만, 묶음은 약 하나 때문에 **나머지 5개도 체크되지 않는다**.
- 제안: 실패 시 오늘 일정을 다시 불러오고(`invalidateQueries`) 버튼 옆에 "다시 한 번 눌러 주세요" 한 줄. 단건 mutation도 같이.

### 🟢 L-1 맨 위 카드 "약 5개"가 남은 수인데 그렇게 읽히지 않는다 (FE) - ✅ 수정 완료
같은 시간대 일부를 먹은 뒤 맨 위 카드는 "약 5개", 아래 묶음 머리는 "약 6개"라 숫자가 다르게 보인다.
- 제안: 일부를 먹었으면 "남은 약 5개".

### 🟢 L-2 묶음 머리 [다 먹었어요] 버튼 이름이 시간대마다 같다 (FE 접근성) - ✅ 수정 완료
점심·저녁 버튼 모두 "다 먹었어요"라 스크린리더 목록에서 구분되지 않는다(구역 이름은 있음).
- 제안: `aria-label="점심 약 다 먹었어요"`.

### 🟢 L-3 전부 실패(롤백)를 실 DB로 고정한 테스트가 없다 (BE) - ✅ 수정 완료
단위 테스트는 남의 약에서 예외가 나는 것까지만 본다. 앞서 저장한 체크가 실제로 롤백되고 이벤트가 나가지 않는 것은 `@Transactional`·AFTER_COMMIT에 기대고 있다.
- 제안: `src/integrationTest`에 묶음 체크 롤백 테스트(내 약 + 남의 약 → 403 후 내 약 체크 0건, vkcs에서 실행).

### 🟢 L-4 복약 알림을 누르면 첫 화면(`/`)으로 간다 (FE, 기존 동작) - ✅ 수정 완료
서비스 워커의 이동 경로에 `MEDICATION_REMINDER`가 없다. 이번 변경 전부터 그랬다.
- 제안: `MEDICATION_REMINDER` → `/ward/medication`(묶음 버튼이 바로 보인다).

## 처리 결과 (2026-10-23)
| 항목 | 반영 | 확인 |
|---|---|---|
| M-1 | FE #97(`3c9a8b6`): 묶음·단건 체크 실패 시 오늘 일정 다시 불러오기 + "체크하지 못했어요. 목록을 다시 불러왔으니 한 번 더 눌러 주세요"(`role=alert`, 다음 성공 시 사라짐) | 브라우저 테스트(404 → 다시 불러오기·안내 → 다시 눌러 체크), 다시 불러오기를 빼면 실패함(변이) |
| L-1 | 일부를 먹었으면 "남은 약 N개" | 브라우저 테스트 |
| L-2 | 묶음 머리 버튼 이름 "점심 약 다 먹었어요" | 브라우저 테스트 |
| L-3 | BE #370(`8c908c0`): `MedicationIntakeBatchIntegrationTest` - 남의 약 섞임 403 → 내 약 체크 0건·이벤트 0건 / 내 약만 → 2건·이벤트 2건 | vkcs 통합 테스트 통과(`de27723`) |
| L-4 | 서비스 워커 `MEDICATION_REMINDER` → `/ward/medication` | gosky 배포 파일에 경로 확인 |

FE `npm test` 153·`test:browser` 47 통과, tsc 0, build 성공. gosky FE `3c9a8b6`(페이지 200). BE #370은 테스트만이라 실행 코드 변경 없음(vkcs CD 재배포, gosky는 `4e3f430`과 같아 재빌드하지 않음).

## 종합 판정
**PASS** - 인가·전부 실패·선점 후 발송·문구 원칙은 지켜졌고, 지적 5건(🟡 1·🟢 4)은 FE #97·BE #370에서 모두 반영했다.

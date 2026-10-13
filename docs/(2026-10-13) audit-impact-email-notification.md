# 영향 범위 점검 - 이메일 알림 채널 (2026-10-13, 템플릿 C)

대상: PR #343 (`bc840be`) 이메일 채널 + `NotificationType.allowedChannels` 기본값 변경 + V60.
방법: 코드 전수 확인(읽기 전용) + 두 서버 DB·기동 상태 확인. 코드 변경 없음.

## 결과 요약

| 등급 | 건수 | 비고 |
|---|---|---|
| 🔴 High | 0 | |
| 🟠 Medium | 1 | M-1 스케줄러 스레드에서 SMTP 대기 |
| 🟡 Low | 3 | L-1~L-3 |

## 확인한 것 (이상 없음)

| 항목 | 확인 내용 |
|---|---|
| `allowedChannels` 사용처 | 디스패처 `settingsChannels` 한 곳뿐(교집합). 다른 도메인이 직접 읽지 않아 기본값 변경의 파급은 디스패처 경로로 한정된다. |
| `dispatch()` 호출처 12곳 | 연결·문의·복약·이상감지·SOS 전부 같은 디스패처를 거친다. 이메일 영향 종류는 `NotificationTypeEmailPolicyTest`가 7종으로 고정. |
| 긴급 종류 | `WARD_SOS`(FCM+SMS만 사용)·`ANOMALY_DETECTED*`(`allowedChannels`에서 제외)는 이메일에 도달하지 않는다. 이메일 때문에 문자 대체가 막히지 않음을 디스패처 테스트로 확인. |
| 설정 저장·조회 | `NotificationChannelType.values()` 전체를 노출하므로 EMAIL 토글은 이미 API에 있었다. 변경 없음. V60으로 기존 ON 값 초기화(gosky 4건 -> 0건, 현재 EMAIL 5행 전부 OFF 확인). |
| 관리자 알림 이력 | 채널·사유를 열거하는 `switch`가 없고 enum을 그대로 직렬화한다. `NO_EMAIL`은 `label()`로 내려간다. `notification_log.channel_results`는 JSONB로 CHECK가 없어 마이그레이션 불요(`NotificationLogCheckSyncTest` 통과). |
| 로그·이력 원문 | 채널은 `MailException` 클래스명만 남기고 주소·본문은 로그에 쓰지 않는다. `LogRawExceptionGuardTest` 대상에 추가됨. |
| 정지·탈퇴 진행 계정 | 차단은 `dispatch()` 한 곳(수신자 상태)이라 이메일도 동일하게 막힌다(테스트 있음). |
| 배포 상태 | 두 서버 모두 `bc840be`, V60 적용, `NOTIFICATION_EMAIL_ENABLED=true`로 재기동, healthy·ERROR 0건. 백업 `.env.dev.bak-20261013-2131`. |

## 발견

### 🟠 M-1 SMTP 대기가 스케줄러 스레드를 붙든다

- 복약 미복용 요약(`MedicationMissedAlertService.sendMissedAlerts`)은 스케줄러 스레드에서 대상마다 `dispatch`를 **동기 순차** 호출한다. 이메일이 켜진 보호자는 FCM·문자 외에 SMTP 호출이 더해진다.
- 제한은 연결 10초·읽기 10초뿐이고 호출 전체 제한·쓰기 제한이 없다. SMTP(Gmail)가 느려지면 대상 1건당 최대 약 20초 이상, 대상이 많으면 스케줄러 풀(3스레드, AI WS 재접속과 공유)의 한 자리를 오래 점유한다.
- 연결 알림 등 리스너 경로는 `notificationExecutor`(비동기)라 영향이 작다. 긴급 풀은 이메일을 쓰지 않아 무관하다.
- 현재 영향: 이메일을 켠 사용자가 거의 없어 당장은 낮다. 제안: Solapi와 같은 방식(호출 전체 제한 래퍼) 또는 채널 전용 짧은 타임아웃(`mail.smtp.writetimeout` 포함). 켠 사용자가 늘기 전에 처리.

### 🟡 L-1 카카오 가상 이메일 계정의 이메일 토글

- 가상 주소(`kakao_숫자@kakao.com`) 계정이 이메일을 켜면 발송 시도마다 `FAILED / NO_EMAIL`이 이력에 쌓여 관리자 실패율을 부풀린다(`NO_PHONE`과 같은 방식이라 의도된 동작이지만 실제 장애와 섞인다).
- 제안: FE가 가상 이메일 계정에는 토글을 숨기거나 안내(Swagger에는 명시함), 또는 `NO_EMAIL`을 실패율 집계에서 분리. 사용자 결정 필요.

### 🟡 L-2 CLAUDE.md 문구 낡음 (이번 PR에서 정정)

- "기본=전 채널이라 기존 종류 무변경"과 "킬 스위치 기본 false"만 적혀 있어 현재 상태(기본에서 이메일 제외, 두 서버 활성)와 달랐다. 이 문서 PR에서 바로잡음.

### 🟡 L-3 실서버 E2E 미확인

- 실제 SMTP 도달(연결 요청 -> 메일 수신)은 확인하지 않았다. 테스트 계정으로 확인하려면 해당 계정에 이메일을 켜고 메일함을 봐야 해서 사용자 확인이 필요하다. 빈 생성·기동은 확인(스위치 true로 healthy).

## 수용 / 변경 없음

- 메일 본문에 피보호자 이름·장소가 포함된다(관리자 이력과 같은 수준, 정책 문서에 수용으로 기록).
- Gmail 일일 발송 한도 초과 시 `PROVIDER_ERROR`로 기록되고 다른 채널은 영향받지 않는다.
- V60은 비가역이다(이전 EMAIL=ON 값은 보관하지 않음, 4건은 테스트성 사용으로 추정되나 확인하지 않음).

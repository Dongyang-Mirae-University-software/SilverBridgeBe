# 점검 이력 대장 (Audit Index)

> **무엇이 점검됐고 무엇이 안 됐는지**를 한 장으로 추적한다. 기능을 머지하면 이 표에 행을 추가하고,
> 점검이 끝나면 상태·문서·잔여 이슈를 갱신한다. (점검 절차는 `.claude/skills/work-prompt` 점검 템플릿)

상태: ✅ 점검 완료 / ⚠️ 점검됨(잔여 이슈 있음) / ❌ 미점검 / ➖ 점검 불요(문서·설정만)

## 기능·변경별

| 머지 | 기능 | 상태 | 점검 문서 | 잔여 이슈 |
|---|---|---|---|---|
| (2026-10-08) feature/guardian-notification-preference | 보호자 알림 종류 설정 - `GET·PUT /api/guardian/notification-type-setting` (복약 계정 토글 V59, 필수 알림 잠금, 재촉 위임) | ❌ | 미점검 - 영향 범위 점검(템플릿 C) 제안 | - |
| (2026-10-08) feature/guardian-dashboard-api | 보호자 대시보드 통합 API - `GET /api/guardian/dashboard` (이상감지·SOS·복약 칸, 마이그레이션 없음) | ❌ | 미점검 - 기능 점검(템플릿 B) 제안 | - |
| #327 (2026-10-07) | AI 챗봇 백엔드 중계 - `/api/guardian/chat*`, 토큰 userId·본문 로그 금지·130초 호출 (마이그레이션 없음) | ⚠️ | `(2026-10-07) audit-chat-relay.md` (템플릿 B, 실서버 AI 계약 실측 포함) | 🔴🟠 0 · 🟡 M-2 기록 응답 허용 목록 **반영**(PR) / M-1 AI 상세 조회 userId 선택 - AI 서버 `CHAT_REQUIRE_USER_ID=true` **적용 완료**(2026-10-07 사용자 실행, 무 userId 상세 조회 200→422 실측) · 🟢 L-1~L-5·테스트 보강 / 보호자 토큰 전체 경로 실호출은 FE 교체 후 확인 |
| #328·#329 (2026-10-07) | 보호자 이상감지 이력 유형 필터 + 건수 요약 (`type`·`/history/summary`, 마이그레이션 없음) | ✅ | `(2026-10-07) audit-anomaly-history-type-filter.md` (템플릿 B) | - (Low 3건 전부 처리 2026-10-07: L-1 `total` 전 행 합·L-3 테스트 반영, L-2는 전역 사용자 제한이 이미 있어 해당 없음) / 통합 테스트 vkcs 통과·두 서버 배포 확인 |
| #323·#324 (2026-10-07) | 낙상 클립 8/1 운영 반영 재확인 + #324 쿨다운 해제 회귀 | ✅ | `(2026-10-07) audit-fall-clip-followup.md` (템플릿 B, 코드 수정 없음) | - (H-1~H-4 전부 반영 2026-10-07, `fix/anomaly-clip-followup`) |
| #323 (2026-10-07) | 낙상 클립 앞·뒤 구간 종류별 분리 (`fall-pre/post-seconds`, 마이그레이션 없음) | ✅ | `(2026-10-07) audit-anomaly-1006-bundle.md` Part 3 (템플릿 B) | G-7 AI 상한 반영 전 env 8/1 상향 시 낙상 클립 조용히 미생성(운영 순서, 상향 뒤 1건 확인) |
| #315·#320·#322 (2026-10-06) | 이상감지 문자 대체·흉기/낙상 라이브 활성화·종류별 안내 문구 (마이그레이션 없음) + 10/5 #306 | ✅ | `(2026-10-07) audit-anomaly-1006-bundle.md` (템플릿 B, PHASE A~E) | Y-1~Y-3·G-1~G-6 전부 반영 2026-10-07 `(2026-10-07) fix-anomaly-1006-audit.md` (Y-3·G-1·G-2·G-5는 수용·문서화) |
| (2026-10-06 시연) | anomaly_event id 1~105 소실 조사 (gosky, 읽기 전용) | ⚠️ | 같은 문서 Part 2 (PHASE E) | **지워짐**(앱 코드·CASCADE 아님, 앱 밖 직접 DELETE 추정 21:48~21:50 KST, 주체 미확인) / 10/6 API 로그는 컨테이너 재생성으로 소실 |
| #305 (2026-10-05) | SOS 문자 폴백 시간당 상한 30건 (`SmsFallbackLimiter`, `NotificationDispatcher` 폴백 경로) | ✅ | `(2026-10-05) audit-impact-sms-fallback-cap.md` (템플릿 C) | - (M-1 사유 `RATE_LIMITED` 문서·FE 계약 정정 반영 / L-1 Swagger 문구 제안·L-2~L-4 수용) |
| #302 (2026-10-05) | SOS 알림 쿨다운 30초 → 10초 + `sos.notify-cooldown-seconds` 설정 분리 | ✅ | `(2026-10-05) audit-sos-notify-cooldown.md` (템플릿 B) | - (M-1 빈 값·비숫자는 기동 실패 - 문서 정정 반영 / M-2 문자 폴백 상한 3배 수용·`SMS_FALLBACK` 관찰) · L-1~L-3 기록 |
| #299 (2026-10-05) | 카메라 방 선택 8개 고정·방마다 1대 + 피보호자 내 카메라 연결 상태 (`/api/ward/camera/rooms`·`/live`, V58) | ✅ | `(2026-10-05) audit-camera-room-and-ward-status.md` (템플릿 B) | - (H-1 새 등록 경합 409 코드·L-1 제약 이름 판정 반영, M-1 해당 없음, M-2 의도, L-2 수용) |
| #297 (2026-10-05) | QA 종합 점검 후속 - 카메라 세션 ID 피보호자 ID 제거·클립 테스트 공백 5건·클립 정책 정정 (마이그레이션 없음) | ✅ | `(2026-10-05) audit-qa-comprehensive-be.md` (템플릿 B, 변이 테스트 포함) | - (M-1·L-1·L-2 후속 PR로 해소, L-3 수용) / 기존 카메라 옛 형식 세션 ID 잔존(수용) |
| #294 (2026-10-04) | 이상감지 5초 클립 - AI 클립 요청·디스크 저장·보호자/피보호자 열람·오탐 비공개·삭제 5경로 (V57) | ✅ | `(2026-10-04) audit-impact-anomaly-clip.md` (템플릿 C) | - (L-1 잠금 순서·L-2 판정 뒤 클립 공개·L-3 매시 고아 청소 반영, compose 클립 마운트 추가) · C-1·C-3 아키텍처 테스트 제안 / 두 서버 배포 확인(V57·`/data/clips` 마운트·쓰기·AI WS·클립 API 401) - 실제 클립 생성 E2E는 등록 카메라 송출 중 화재 감지 시 확인(배포 시점 gosky 등록 카메라 1대 미송출) / vkcs는 `AI_API_KEY` 미설정이라 이상감지·클립 비활성(기존 설정) |
| #290 (2026-10-03) | 보호자 실시간 카메라 보기 - AI 영상 백엔드 중계·스트림 티켓·STOMP `camera-analysis` (마이그레이션 없음) | ✅ | `(2026-10-03) audit-impact-ai-stream-relay.md` (템플릿 C) | - (M-1·L-1·L-2 반영 #291, L-3·L-4 반영 #292, I-1·I-3 반영 2026-10-04 `fix/camera-stream-info`, I-2·I-4 수용) / 실서버 브라우저 `<img>` E2E 미확인(운영 활성 카메라 1대·ACTIVE 연결 1건 - 보호자 계정으로 확인 가능) |
| #261 (2026-09-30) | 탈퇴 FCM 토큰 삭제 REQUIRES_NEW(M-1)·탈퇴 `WITHDRAWN`/역할 변경 `ADMIN` 해제 문구(M-2) (마이그레이션 없음) | ➖ | 점검 불요 - 사용처 전수(`DisconnectedBy` 이벤트 생성 5경로·알림 분기·FE)는 구현 PHASE 0에서 수행, `(2026-09-30) fix-withdrawal-fcm-and-disconnect-copy.md` | - (M-1 실 DB 재현·고정, M-2 단위 테스트 5건. 별도 템플릿 C는 사용자 결정으로 생략 2026-09-30) |
| #260 (2026-09-30) | 아키텍처 경계 정리 F-1~F-3 - 감사 로그·SMS 발송기 → global, ID 생성기 → user (동작 불변) | ✅ | `(2026-09-30) audit-impact-remaining-audit-items.md` (템플릿 C) | - |
| #259 (2026-09-30) | 점검 잔여 이슈 - 탈퇴 리스너 REQUIRES_NEW(H-1)·판정 쓰기 잠금+`@DynamicUpdate`(E-2)·SOS 경계값 | ✅ | `(2026-09-30) audit-impact-remaining-audit-items.md` (템플릿 C + B) | - (M-1 `deleteAllTokens` REQUIRES_NEW·M-2 탈퇴 `WITHDRAWN`/역할 변경 `ADMIN` 문구 반영 2026-09-30 `(2026-09-30) fix-withdrawal-fcm-and-disconnect-copy.md`) · L-1~L-3 기록 |
| #258 (2026-09-23) | 알림 이력 점검 이슈 반영 - 연결 이벤트 당사자 ID·AFTER_COMMIT 기록 테스트 (마이그레이션 없음) | ➖ | 점검 반영분 `(2026-09-22) fix-admin-notification-audit-findings.md` | - |
| #257 (2026-09-22) | 관리자 알림 이력 - 채널 결과 코드·`notification_log` 기록·조회 API (V54) | ✅ | `(2026-09-22) audit-impact-notification-channel-result.md` (템플릿 C) | - (M-1 실서버 확인 2026-09-30: gosky `notification_log`에 요청·수락·해제·SOS 행이 1건씩 `DELIVERED`, 연결 알림 `ward_id` 채워짐, 배포 후 `[NOTIFY-LOG-FAILED]` 0건) - L-3·L-4 반영, L-1·L-2 수용·문서화 (`(2026-09-22) fix-admin-notification-audit-findings.md`) / 통합 테스트 vkcs 통과·V54 vkcs 적용 확인 |
| #255 (2026-09-22) | 관리자 이상감지 AI 신뢰도 = confidence 평균 (`accuracy` → `aiConfidence`, 마이그레이션 없음) | ✅ | `(2026-09-22) audit-admin-anomaly-ai-confidence.md` (템플릿 B) | - (M-1·L-1~L-4 전부 반영 2026-09-22: 응답률 문구·`/summary` 403 테스트·`real`/`falseAlarm` 제거·confidence 0~1 보정·최고값 치우침 명시) |
| #253 (2026-09-21) | 관리자 이상감지 로그 v2 - 필터·집계 API + 연기=화재 통합 (V53) | ✅ | `(2026-09-21) audit-impact-admin-anomaly-log-v2.md` (템플릿 C) | - (E-1·E-2 주석 반영, E-3 쿨다운 키 1회성 수용) / 새 JPQL은 배포 기동으로 확인 |
| #252 (2026-09-21) | 이상감지 판정 다수결 전환·동수 재확인 안내·관리자 정정 폐지 (V52) | ✅ | `(2026-09-21) audit-impact-anomaly-majority-review.md` (템플릿 C) | E-2(동시 응답 상태 덮어쓰기)는 **해결 2026-09-30**(쓰기 잠금 + 감지 승계 덮어쓰기 `@DynamicUpdate`, `(2026-09-30) fix-remaining-audit-items.md`) / E-3(해제·탈퇴 보호자 표)은 **보류·수용**(2026-09-21 결정, 정책 파일 "알려진 한계 - 판정 집계") / V52 gosky 적용 확인(2026-09-21, Flyway v52·기동·health 200) - (E-1·B-1 반영 완료) · FE Notion 3페이지 갱신 2026-09-21 |
| #251 (2026-09-21) | 의존성 업그레이드 - Boot 4.0.8·Tomcat 11.0.26·springdoc 3.1.1 (코드 무변경) | ➖ | 스캔 결과 `(2026-09-11) audit-technical-cross-cutting.md` PHASE A "반영 결과" | - (CVSS 7+ 0건, 남은 2건은 5.3 오탐) |
| #250 (2026-09-11) | 테스트 - 미복용 요약 Planner 자정 근처 미실행 가드 | ➖ | 테스트만 | - |
| #249 (2026-09-21) | SOS 연타 알림 반복 횟수 (`repeatCount`) | ✅ | `(2026-09-21) audit-sos-repeat-count.md` | - (L-1 기능 문서 정정 2026-09-21 / L-3 경계값 2·0 단위 테스트 + 집계 쿼리 실 DB 통합 테스트 2026-09-30 `(2026-09-30) fix-remaining-audit-items.md`) |
| #248 (2026-09-11) | 회귀·기술 점검 이슈 반영 (V51) - 수락 시 보호자 상태·executor·종료·타임아웃·감사 로그 PII | ➖ | 점검 반영분 `(2026-09-11) fix-audit-findings-2.md` | - |
| #247 (2026-09-10) | 문의 답변 감사 로그 (V50) - 역할 경계 점검 A-2 반영 | ➖ | 점검 반영분 `(2026-09-10) audit-role-boundary-guardian-ward-admin.md` A-2 | - |
| #246 (2026-09-10) | 점검 이슈 반영 - 역할 변경 카메라·토큰, INACTIVE 404, 관리자 게이트 | ➖ | 점검 반영분 `(2026-09-10) fix-audit-findings.md` | - |
| #245 (2026-09-10) | 관리자 강제 연결·해제 | ⚠️ | `(2026-09-10) audit-unaudited-prs-230-245.md` · 역할별 `(2026-09-10) audit-role-boundary-guardian-ward-admin.md` | L-5 FE 확인 완료(2026-09-30): 피보호자 토픽에 `connection-accepted`가 없어 강제 연결 시 실시간 갱신 누락(FCM은 도착). **백엔드 무변경 - FE에 구독 1줄 추가 요청** `(2026-09-30) fix-remaining-audit-items.md` |
| #244 (2026-09-09) | 정지 계정 알림 차단·정지 사유 (V49) | ✅ | 〃 | M-5는 수용한 한계로 정책 문서에 명시(2026-09-10) |
| #243 (2026-09-09) | 관리자 회원관리 (V47·V48) | ✅ | 〃 | - (M-1·M-2·M-3·M-6 수정 완료 2026-09-10 - `(2026-09-10) fix-audit-findings.md`) |
| #242 (2026-09-07) | 피보호자 목록 status 필터 | ✅ | 〃 | - |
| #241 (2026-09-02) | 관리자 이상감지 로그·정정 (V46) | ✅ | 〃 | - (M-4 문서 정정·L-1·M-6 수정 완료 2026-09-10) |
| #240 (2026-09-02) | FCM 토큰 상한·유휴 정리 | ✅ | 〃 | - (L-2 수정 완료 2026-09-10) |
| #239 (2026-09-02) | 관리자 대시보드 집계 | ✅ | 〃 | - |
| #238 (2026-09-01) | WebSocket Origin (yaml) | ✅ | 〃 | - |
| #237 (2026-09-01) | 이상감지 보호자 판정 + 재촉 (V45) | ✅ | 〃 | - (L-4 `resolvedAt` 판정으로 수정 완료 2026-09-10) |
| #234~#236 (2026-08-31~09-01) | 상황(incident) 스키마 도입→롤백→복원 (V42~V44) | ✅ | 〃 | - |
| #232·#233 (2026-08-27) | 미복용 요약 시각 선택·피보호자별 축 (V40·V41) | ✅ | 〃 | - |
| #231 (2026-08-27) | SOS ACK 철회 + 발생 경로 (V39) | ✅ | 〃 | - |
| #230 (2026-08-07) | Swagger 태그 재편 | ✅ | 〃 | - |
| #229 (2026-08-06) | 점검 이슈 반영 (V38) | ✅ | 〃 | - |
| #227 (2026-08-05) | 복약 4차 — 약 수정 PATCH | ✅ | `(2026-08-05) audit-medication-sos-notification.md` | — (L-3은 오탐, 이미 커버됨 — `(2026-08-06) fix-audit-findings.md`) |
| #226 (2026-08-05) | 복약 3차 — 미복용 보호자 요약 (V37) | ✅ | 〃 | — (L-2 수정 완료 2026-08-06) |
| #225 (2026-08-05) | 복약 2차 — 스케줄러 발송 (V36) | ✅ | 〃 | — (M-2·L-1 수정 완료 2026-08-06, V38) |
| #224 (2026-08-04) | 복약 1차 — 등록·체크 (V35) | ✅ | 〃 | — (M-3 수정 완료 2026-08-06) |
| #223 (2026-07-31) | SOS 이력 조회 + ACK + 위치 (V33·V34) | ✅ | 〃 | — |
| #222 (2026-07-30) | 이상감지 쿨다운 기본값 조정 | ✅ | 〃 | — |
| #221 (2026-07-27) | 알림톡 보호자 전용 (`ANOMALY_DETECTED_SELF`) | ✅ | 〃 | — |
| #219 (2026-07-23) | SOS 동작 설정 (V32) | ✅ | 〃 | — |
| #220 (2026-07-23) | 알림톡 `#{detectedAt}` 변수 | ✅ | 〃 | — |
| #218 (2026-07-15) | 감사 지적 수정 + 알림톡 채널 구현 | ✅ | 〃 | — |
| #217 (2026-07-14) | 이상감지 알림 2단계 (FCM 고정 + SMS 선택) | ✅ | `(2026-07-14) audit-sos-to-anomaly-cumulative.md` | — (L-3 수정 완료) |
| #216 (2026-07-14) | Gradle 빌드 캐시(CD 단축) | ✅ | 〃 | — |
| #215 (2026-07-14) | 테이블 단수형 통일 (V31) | ✅ | 〃 | — |
| #214 (2026-07-13) | 이상감지 수신·판정·이력 (1단계) | ✅ | 〃 | — (H-1·L-1 수정 완료) |
| #213 (2026-07-03) | camera 도메인 (소유권·SessionID 발급) | ✅ | 〃 | — (M-2·M-3 수정 완료) |
| #209·#211 (2026-07-01) | 문의하기(inquiry) + Swagger Tag | ✅ | `(2026-07-03) audit-spot-check-inquiry.md` · 회귀 재확인 `(2026-07-14)` | — (L-2 수정 완료) |
| #199·#200 (2026-06-09) | 피보호자 긴급 SOS | ✅ | `(2026-06-09) audit-spot-check-ward-sos.md` · 회귀 재확인 `(2026-07-14)` | — |
| 전체 API (2026-06-11) | 누적 전수 점검 3세션 | ✅ | `(2026-06-11) audit-full-api-final-report.md` (+session1~3) | — |
| — (상시) | 연결(connection) 변경분 | ✅ | `(2026-06-06) audit-spot-check-connection-changes.md` | — |
| — (상시) | 카카오·비밀번호 버그픽스 | ✅ | `(2026-06-06) audit-spot-check-kakao-password-bugfix.md` | L-1 경계(의도적 미수정, 도메인 보안 정책 참조) |

## 도메인 밖 / 미점검 영역

| 영역 | 상태 | 메모 |
|---|---|---|
| **전체 점검 2026-09** (BE + gosky 배치) | ⚠️ (H-1·H-2만 잔여) | `(2026-09-30) audit-full-2026-09.md` (템플릿 D) - 코드 인가·트랜잭션·정책 위반 0건. **🔴 C-1 DB·Redis 포트 외부 공개(Redis 무인증)** · 🟠 H-1 AI 서버 연동 키 관리(상세 비공개, 미조치) · 🟡 M-1 역할 변경 시 복약 잔존 · M-2 FCM·Solapi 타임아웃 없음 · M-3 카메라 등록 이벤트 커밋 전 처리 · M-4 평문 HTTP 허용 · M-5 기기 없는 수신자 화재 알림 미전달(결정) · 🟢 L-1~L-15 → **반영 2026-09-30**: C-1(#265, 두 서버 db·redis 재생성·외부 포트 닫힘 확인) · M-1~M-3·L-15(#262) · L-1~L-9·L-13(#263) · L-10~L-12(#264). 미반영: H-1(AI 서버 연동 키 관리 - 카메라 연동 전까지 AI·FE와 조치) · H-2(FE 대기) · M-4 nginx·TLS(공용 서버 설정, `.env.dev` 권한만 600으로) · M-5(관측 기록) · L-7·L-14(현행 유지) |
| `global/websocket` (STOMP 리스너) | ✅ | M-1 STOMP NPE 수정 완료(2026-07-14, null-safe) |
| **탈퇴 리스너 트랜잭션 전파** | ✅ | H-1 **실제 결함으로 판정·수정(2026-09-30)** - 실 DB에서 연결 정리·약 삭제가 커밋되지 않고, **연결 정리 중 발행한 해제 이벤트가 AFTER_COMMIT을 맞지 못해 탈퇴 시 상대 해제 알림이 나가지 않음**을 재현. 두 메서드를 `REQUIRES_NEW`로 바꿔 통과. `WithdrawalListenerCommitIntegrationTest` · `(2026-09-30) fix-remaining-audit-items.md` |
| **실 DB 통합 테스트** | ⚠️ | **1단계 도입(2026-09-21)** - `src/integrationTest`(Testcontainers `postgres:17`, `@DataJpaTest`) 10건: Flyway V1~V53 전체 적용·엔티티 validate, 이상감지 v2 JPQL, ON CONFLICT, enum↔CHECK. vkcs `tools/integration-test.sh`로 실행, **CD가 배포 전 자동 실행·실패 시 중단**. 남은 것: 서비스·트랜잭션 전파·리스너(전체 컨텍스트) - H-1 판정은 여기서 가능해진다. `(2026-09-21) feature-testcontainers-integration-test.md` |
| 이상감지 **통합 경로**(카메라 등록 ↔ AI sessionId) | ❌ | **FE가 백엔드 카메라 등록을 거치지 않는다(2026-09-30 확인)** - AI 서버에 직접 등록·송출해 백엔드 `camera`가 비고, 백엔드는 등록된 세션만 구독하므로 실송출 화재 알림이 0건. 이 흐름(피보호자가 `/api/ward/camera` 등록 → 발급 `sessionId`로 송출)은 **2026-07-31에 FE 안내 완료** - FE 미반영. `(2026-09-30) issue-anomaly-camera-integration-gap.md`, Notion FE 확인 요청(2026-09-30). **FE 반영 대기** |
| 카카오 알림톡 채널 | ✅ | 템플릿 **승인(2026-07-27)**·두 서버 `ALIMTALK_ENABLED=true`로 실발송 중(2026-07-31 확인). 카카오 푸시는 검토 후 미채택(앱 푸시=FCM 중복) |
| **역할 경계 횡단**(보호자/피보호자/관리자) | ✅ | `(2026-09-10) audit-role-boundary-guardian-ward-admin.md` - 보호자·피보호자 PASS. 보호자 G-1·관리자 A-1 수정 완료(2026-09-10). A-2(문의 답변 감사 로그)는 V50 PR ②. 역할 게이트 테스트 미커버는 8개 → 5개(연결 2·문의 2·공지 1, 다음 변경 때) |
| **기점검 도메인 회귀 재점검** (auth~anomaly 1·2단계) | ✅ | `(2026-09-10) audit-regression-pre-230.md` - 2026-06-11 미해결 4건 전부 닫힘·회귀 없음. R-1·R-3(문서)·R-5·R-6 수정 완료 2026-09-11(`(2026-09-11) fix-audit-findings-2.md`). R-2(정지 중 선점 유실)·R-4(문의 CASCADE)는 수용 |
| **의존성 취약점 스캔** | ✅ | `(2026-09-11) audit-technical-cross-cutting.md` PHASE A(2026-09-21 완주) - **A-1 🟠** Boot 4.0.5 관리 버전(Framework 7.0.6·Security 7.0.4·Tomcat 11.0.20·netty 4.2.12)에 2026-03 이후 Critical 다수. **반영 완료 2026-09-21**: Boot 4.0.8 + Tomcat 11.0.26 + firebase 9.10.0 + springdoc 3.1.1 + httpclient/core·kotlin·netty 핀 + 억제 5종 → 1879건 → 2건(5.3 오탐), 스캔 빌드 통과. 재실행 `./gradlew dependencyCheckAnalyze`(OSS Index 비활성 반영) |
| **동시성**(스케줄러·executor·선점) | ✅ | 〃 PHASE B - B-1·B-2·B-3 수정 완료 2026-09-11 |
| **외부 연동 회복력**(FCM·Solapi·SMTP·카카오·AI WS) | ✅ | 〃 PHASE C - C-1·C-2 수정 완료 2026-09-11. 필수 알림 보장 결론 기록 |
| **성능·JPA** | ✅ | 〃 PHASE D - D-1·D-2 인덱스 V51(2026-09-11). D-3 수용 |
| **운영 설정·로깅** | ✅ | 〃 PHASE E - E-2·E-4·E-5 수정 완료. **E-1은 관리 밖 인프라라 수용한 한계**(정책 파일 기록) |
| **아키텍처 경계** | ✅ | 〃 PHASE F - **F-1~F-3 반영 2026-09-30**(`refactor/package-boundaries`, 동작 불변 이동): 감사 로그 3종 → `global/audit`(admin↔inquiry 순환 해소, anomaly 쪽은 관리자 정정 폐지로 이미 해소), `UserIdGenerator` → `domain/user/service`, `SmsSender` → `global/client`. 결과: 도메인 간 순환 0, `global → domain` import 0 |
| 프론트엔드(SilverBridgeFe) | ➖ | 별도 저장소 — 이 대장의 범위 밖 |

## 다음 점검 트리거

- **새 동기 AFTER_COMMIT 리스너를 추가할 때** → 부르는 쓰기가 `REQUIRES_NEW`인지 확인(H-1, 2026-09-30 수정 - 실 DB 테스트 `WithdrawalListenerCommitIntegrationTest` 형태로 고정)
- **FE 카메라 연동 완료 시** → 이상감지 통합 검증(`(2026-09-30) issue-anomaly-camera-integration-gap.md` "검증 방법")
- **AI danger 정식 배포 시** → DANGER 모드 실동작·오탐률 확인(임계 조정 판단)

# 연결 조회 partner 프로필 전체 필드 추가

- **작업 일자**: 2026-06-02
- **브랜치**: `feature/connection-partner-full-profile`
- **유형**: feature (additive, 프론트 호환 — 기존 필드 무변경)

---

## 1. 배경

연결 조회 응답의 상대방(partner) 정보에 **성별·생년월일·이메일·우편번호**가 빠져 있어,
케어 화면에서 상대 프로필을 온전히 보여주지 못했다. 케어 서비스 특성상 연결된 양쪽
(보호자/피보호자)이 상대 프로필 전체를 상호 열람할 수 있어야 한다는 결정에 따라 보강한다.

기존 partner 필드: `partnerName, partnerProfileImage, partnerPhone, partnerAddress,
partnerAddressDetail, relation, status, isRequester, connectedAt, createdAt`

---

## 2. 추가한 필드 (ConnectionResponse)

| 필드 | 타입 | null 가능 | 비고 |
|---|---|---|---|
| `partnerPostcode` | String | ✅ | 우편번호(V18~). 미입력 계정 null |
| `partnerGender` | String | ✅ | `"FEMALE"`/`"MALE"` (enum name). 미입력 null |
| `partnerBirthDate` | LocalDate | ✅ | ISO-8601 `"1975-03-21"`. 미입력 null |
| `partnerEmail` | String | ✅(노출 규칙상) | not-null 컬럼이나 비-ACTIVE에서 null로 가림 |

### 노출 규칙 — ACTIVE 게이팅 (기존 phone/address와 동일)

신규 4개 필드는 **연결이 ACTIVE일 때만** 채워지고, **PENDING/CANCELLED/REFUSED에서는 `null`**.
기존 `partnerPhone/partnerAddress/partnerAddressDetail`의 `revealContact` 게이팅을 그대로 따른다
("연결 성립 전 연락처 비노출" 도메인 정책의 자연스러운 확장).

성별·생년월일·우편번호는 미입력(기존·일부 카카오) 계정이면 ACTIVE라도 `null` — null-safe 매핑
(`gender == null ? null : gender.name()`)으로 NPE 없이 처리.

---

## 3. 제외한 민감/시스템 필드 (절대 미노출)

`password`, `providerId`(provider_id), `provider`, `role`, `status`(계정상태 ACTIVE/INACTIVE),
`lastLoginAt`. → 인증/보안/내부 식별 필드. 응답 DTO에 필드 자체가 존재하지 않음(리플렉션 테스트로 고정).

> 응답 DTO의 `status`는 **연결 상태**(PENDING/ACTIVE/…)이며 사용자 계정 상태와 무관.

---

## 4. 영향 엔드포인트

`ConnectionResponse`(공유 DTO) 한 곳만 수정 → 아래 3개 엔드포인트에 자동 반영:

| 엔드포인트 | 관점 | 반환 상태 |
|---|---|---|
| `GET /api/guardian/connection/select` | 보호자→피보호자 | ACTIVE + PENDING |
| `GET /api/guardian/connection/requests` | 보호자→피보호자 | 전체(취소/거절 포함) |
| `GET /api/ward/connection/active` | 피보호자→보호자 | ACTIVE |

`GET /api/ward/connection/pending`(수락 전 카드, `PendingConnectionResponse`)는 **변경 없음** —
의도적으로 최소정보+전화 마스킹을 유지(수락 전 프로필 비노출).

---

## 5. 프론트 인계

### 추가된 응답 필드 (타입)

```
partnerPostcode  : string | null
partnerGender    : "FEMALE" | "MALE" | null
partnerBirthDate : string(ISO date "YYYY-MM-DD") | null
partnerEmail     : string | null   // ACTIVE에서만 값, 그 외 null
```

- **null 가능 안내**: 위 4개 + 기존 phone/address 계열은 **연결이 ACTIVE일 때만** 값이 옴.
  PENDING/취소/거절 상태에서는 모두 `null` → UI에서 null 가드 필요.
- 성별/생년월일/우편번호는 ACTIVE여도 **미입력 계정이면 null** — "정보 없음" 처리 권장.
- 기존 필드는 그대로(이름/타입 무변경) → 기존 화면 영향 없음(additive).

### 응답 예시 (ACTIVE, 전 → 후)

```jsonc
// before
{
  "id": 10, "partnerUserId": "W00001", "partnerName": "홍길동",
  "partnerProfileImage": "https://cdn/p.png",
  "partnerPhone": "010-1234-5678",
  "partnerAddress": "서울시 강남구 역삼로 123", "partnerAddressDetail": "4층",
  "relation": "아들", "status": "ACTIVE", "isRequester": true,
  "connectedAt": "2026-01-01T09:00:00+09:00", "createdAt": "2026-01-01T09:00:00+09:00"
}

// after (추가 필드만 강조)
{
  "id": 10, "partnerUserId": "W00001", "partnerName": "홍길동",
  "partnerProfileImage": "https://cdn/p.png",
  "partnerPhone": "010-1234-5678",
  "partnerAddress": "서울시 강남구 역삼로 123", "partnerAddressDetail": "4층",
  "partnerPostcode": "06234",          // ▲ 추가
  "partnerGender": "MALE",             // ▲ 추가
  "partnerBirthDate": "1975-03-21",    // ▲ 추가
  "partnerEmail": "partner@example.com", // ▲ 추가
  "relation": "아들", "status": "ACTIVE", "isRequester": true,
  "connectedAt": "2026-01-01T09:00:00+09:00", "createdAt": "2026-01-01T09:00:00+09:00"
}
```

PENDING/CANCELLED/REFUSED 응답에서는 `partnerPostcode/Gender/BirthDate/Email`,
그리고 기존 `partnerPhone/Address/AddressDetail`이 모두 `null`.

---

## 6. 변경 파일

| 파일 | 변경 |
|---|---|
| `domain/connection/dto/ConnectionResponse.java` | 4개 필드 + 생성자 + `genderName()` null-safe 헬퍼 + 양쪽 팩토리(`fromGuardianView`/`fromWardView`) 매핑 + Swagger `@Schema` |
| `test/.../connection/dto/ConnectionResponseTest.java` | 신규 — ACTIVE 노출 / 비-ACTIVE null / null 프로필 안전 / 민감필드 미노출 |
| `프로젝트_설명.txt` | 연결 조회 응답 partner 필드 명세 갱신 |

DB 변경 없음(읽기 응답 매핑만 추가). 마이그레이션 불필요.

---

## 7. 테스트 결과

- `ConnectionResponseTest` — EXIT 0 (ACTIVE 4필드 노출, PENDING/CANCELLED null,
  미입력 성별/생년월일/우편번호 null-safe, 민감필드 DTO 미존재).
- `connection.*` 패키지 회귀 테스트 — EXIT 0.
- `./gradlew build -x test` — EXIT 0.

---

## 8. 커밋 메시지 초안

```
feat(connection): 연결 조회 partner 프로필 전체 필드 추가

성별·생년월일·이메일·우편번호를 ConnectionResponse에 추가.
기존 연락처와 동일하게 ACTIVE 연결에서만 노출(PENDING/취소/거절은 null),
미입력 계정은 null-safe 매핑. 민감/시스템 필드(password·provider_id 등)는 미노출.
수락 전 카드(PendingConnectionResponse)는 최소정보 정책 유지로 무변경.
```

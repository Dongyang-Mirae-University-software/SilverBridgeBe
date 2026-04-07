# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Info
- 메인 페이지: dmu.gosky.kr
- 백엔드 API: api.dmu.gosky.kr : port 6511
- PostgreSQL 17: port 6513
- Redis 7.2: port 6514

## Stack
- Java 21, Spring Boot 4.0.5, Gradle
- Spring Security + OAuth2 Client
- Spring Data JPA + PostgreSQL
- Spring Data Redis
- Spring Mail
- JJWT 0.12.6
- Lombok

## Docker
- 파일: `docker-compose.dev.yml`
- container_name: `dmusso-{env}-{service}` (예: dmusso-dev-db)
- 볼륨명: `dmusso-{env}-{service}-data`
- 네트워크명: `dmu-{env}-net`
- 민감 정보는 `.env.dev` 파일로 분리
- `.env.*` 파일은 git에 올리지 않음

## Package Structure (도메인형)
```
kr.silverbridge.main
├── domain
│   ├── auth
│   │   ├── controller
│   │   ├── service
│   │   ├── dto
│   │   └── oauth           # 카카오 OAuth
│   ├── user
│   │   ├── controller
│   │   ├── service
│   │   ├── repository
│   │   ├── entity
│   │   └── dto
│   └── admin
│       ├── controller
│       ├── service
│       └── dto
└── global
    ├── config              # Redis, Mail, Web 설정
    ├── security            # Security 필터/설정
    ├── jwt                 # JWT 발급/검증
    ├── enums               # Enum 클래스 모음
    ├── entity              # BaseTimeEntity
    ├── response            # ApiResponse 공통 포맷
    ├── exception           # GlobalExceptionHandler
    └── aop                 # 공통 로그
```

## Coding Rules
- Lombok 사용
- DTO 요청/응답 분리 (`XxxRequest` / `XxxResponse`)
- 한국어 주석
- `BaseTimeEntity` 공통 부모 클래스 사용

## Git Branch Strategy

### 브랜치 구조
- `prod`: 배포의 기준 브랜치
- `dev`: 개발의 기준 브랜치 — Merge Request를 통해서만 feature 브랜치로부터 반영
- `feature/*`: 개발 작업 브랜치 — dev에서 분기, 작업 후 MR → dev, 브랜치 삭제

### 브랜치 네이밍
형식: `type/short-description` 또는 `type/short-description-author`

| type | 용도 |
|------|------|
| `feature` | 기능 개발 |
| `fix` | 버그 수정 |
| `hotfix` | 운영 긴급 수정 |
| `refactor` | 리팩토링 |
| `design` | UI/디자인 작업 |
| `docs` | 문서 작업 |
| `test` | 테스트 코드 |
| `chore` | 설정/패키지/잡일 |
| `infra` | 서버/Docker/Nginx/CI-CD |
| `release` | 배포 준비 |

예시: `feature/login-api`, `fix/signup-validation`, `infra/docker-nginx-setting`
이슈 번호 포함: `feature/123-login-api`, `fix/87-token-refresh`

### 브랜치 작업 순서
1. `git pull origin dev` — 최신 코드 동기화
2. `git checkout -b type/short-description` — 브랜치 분기
3. 작업 및 커밋
4. push 전 `git merge dev` → 충돌 해결
5. push 후 MR(PR) 생성 → dev로 머지, 브랜치 삭제

### 브랜치 작성 규칙
- 전부 소문자, 띄어쓰기 금지, 단어 구분은 `-`
- 너무 길지 않게, 기능 단위가 보이게 작성

### 커밋 메시지 형식
```
type: 무슨 작업을 했는지 한국어로
- 상세 내용 (선택)
- 상세 내용 (선택)
```

| type | 용도 |
|------|------|
| `feat` | 새로운 기능 |
| `fix` | 버그 수정 |
| `refactor` | 리팩토링 |
| `design` | UI/디자인 변경 |
| `style` | 코드 포맷 (비즈니스 로직 변경 없음) |
| `docs` | 문서 수정 |
| `test` | 테스트 코드 |
| `chore` | 기타 변경사항 |
| `init` | 초기 생성 |
| `rename` | 파일/폴더 이동 및 이름 변경 |
| `remove` | 파일 삭제 |

### 커밋 작성 규칙
- 첫 글자는 소문자
- 한 커밋 = 한 작업
- 무슨 작업인지 바로 알 수 있게 작성

## DB
- schema.sql: db/schema.sql 참고
- Redis 키 구조:
    - email:verify:{email}   → 인증코드 (TTL 5분)
    - password:reset:{token} → 재설정 토큰 (TTL 30분)
    - logout:{accessToken}   → 로그아웃된 토큰 (TTL 토큰 만료시간)

### Redis (임시 저장)
- `email:verify:{email}`   → 이메일 인증코드 (TTL 5분)
- `password:reset:{token}` → 비번 재설정 토큰 (TTL 30분)
- `logout:{accessToken}`   → 로그아웃된 토큰 (TTL 토큰 남은 만료시간)

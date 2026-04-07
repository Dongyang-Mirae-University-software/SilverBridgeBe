# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Info
- 메인 페이지: dmu.gosky.kr
- 백엔드 API: api.dmu.gosky.kr : port 6511
- PostgreSQL 16: port 6513
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
- `prod`: 운영 배포 기준
- `dev`: 개발 기준 — feature 브랜치로부터 Merge Request로만 반영
- `feature/*`: 기능 개발 — dev에서 분기, 작업 후 MR → dev, 브랜치 삭제

### 브랜치 네이밍
형식: `type/short-description`

| type | 용도 |
|------|------|
| `feature` | 기능 개발 |
| `fix` | 버그 수정 |
| `hotfix` | 운영 긴급 수정 |
| `refactor` | 리팩토링 |
| `docs` | 문서 |
| `chore` | 설정/패키지 |
| `infra` | 서버/Docker/CI |
| `release` | 배포 준비 |

예시: `feature/login-api`, `fix/token-refresh`, `infra/docker-nginx-setting`

### 규칙
- 전부 소문자, 띄어쓰기 금지, 단어 구분은 `-`
- 작업 전 dev에서 최신 코드 pull 후 feature 브랜치 분기
- Push 전 dev → feature 브랜치로 merge 후 충돌 해결

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

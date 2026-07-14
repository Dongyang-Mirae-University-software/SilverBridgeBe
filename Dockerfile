# ─── 1단계: 빌드 ───────────────────────────────────────────
FROM gradle:9.4.1-jdk21 AS builder

WORKDIR /app

# Gradle 홈(의존성·빌드 캐시 저장 위치). 아래 BuildKit 캐시 마운트 대상.
ENV GRADLE_USER_HOME=/home/gradle/.gradle

# 의존성 캐싱 — 소스 변경 시 의존성 재다운로드 방지
COPY build.gradle settings.gradle gradle.properties ./
COPY gradle ./gradle
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle dependencies --no-daemon || true

COPY src ./src
# BuildKit 캐시 마운트로 GRADLE_USER_HOME(의존성 + build-cache)을 빌드 간 유지한다.
# 이게 없으면 소스가 한 줄만 바뀌어도 매번 풀 컴파일 — CD 대상(구형 Xeon + HDD)에서 ~8분이 그대로 든다.
# ※ /app/build 는 캐시 마운트로 두면 안 된다 — 캐시 마운트 내용은 이미지 레이어에 남지 않아
#   아래 COPY --from 이 jar 를 찾지 못한다. 컴파일 절감은 Gradle build-cache(캐시 홈)가 담당한다.
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle bootJar --no-daemon -x test

# ─── 2단계: 실행 ───────────────────────────────────────────
FROM eclipse-temurin:21-jre

WORKDIR /app

# 타임존 설정
ENV TZ=Asia/Seoul

# Docker healthcheck용 curl 설치 (/actuator/health 호출)
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]

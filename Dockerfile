# ─── 1단계: 빌드 ───────────────────────────────────────────
FROM gradle:9.4.1-jdk21 AS builder

WORKDIR /app

# [캐시 최적화 전략]
# 1. build.gradle / settings.gradle 먼저 복사 → 의존성 레이어 분리
#    소스 변경 시 의존성을 재다운로드하지 않음
# 2. --mount=type=cache → Gradle 다운로드 캐시를 빌드 간 유지
#    레이어 캐시가 무효화되어도 ~/.gradle 캐시는 보존됨
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle dependencies --no-daemon || true

COPY src ./src
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle bootJar --no-daemon -x test

# ─── 2단계: 실행 ───────────────────────────────────────────
FROM eclipse-temurin:21-jre

WORKDIR /app

# 타임존 설정
ENV TZ=Asia/Seoul

COPY --from=builder /app/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]

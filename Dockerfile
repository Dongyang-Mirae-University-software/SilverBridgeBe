# ─── 1단계: 빌드 ───────────────────────────────────────────
FROM gradle:9.4.1-jdk21 AS builder

WORKDIR /app

# 의존성 캐싱 — 소스 변경 시 의존성 재다운로드 방지
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true

COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ─── 2단계: 실행 ───────────────────────────────────────────
FROM eclipse-temurin:21-jre

WORKDIR /app

# 타임존 설정
ENV TZ=Asia/Seoul

COPY --from=builder /app/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]

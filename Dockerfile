# ─── 1단계: 빌드 ───────────────────────────────────────────
FROM gradle:8.14-jdk21 AS builder

WORKDIR /app

# Gradle JVM 메모리 설정 (컨테이너 OOM 방지)
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false \
                 -Dorg.gradle.jvmargs=-Xmx1g \
                 -Dorg.gradle.workers.max=1"

# 의존성 캐싱 — 소스 변경 시 의존성 재다운로드 방지
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true

COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ─── 2단계: 실행 ───────────────────────────────────────────
FROM eclipse-temurin:21-jre

WORKDIR /app

ENV TZ=Asia/Seoul

COPY --from=builder /app/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]

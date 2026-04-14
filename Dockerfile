# ─── 1단계: 빌드 ───────────────────────────────────────────
FROM gradle:8.14-jdk21 AS builder

WORKDIR /app

# 의존성 캐싱 — 소스 변경 시 의존성 재다운로드 방지
# gradle.properties: JVM 메모리 설정 포함 (org.gradle.jvmargs=-Xmx1g)
COPY build.gradle settings.gradle gradle.properties ./
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

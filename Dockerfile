# ─── 1단계: 빌드 ───────────────────────────────────────────
# 공식 gradle 이미지는 applyInstrumentationAgent=true로 인해 daemon fork → crash 발생
# eclipse-temurin + ./gradlew 방식 사용 (instrumentation agent 미적용)
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# [캐시 최적화 전략]
# --mount=type=cache,target=/root/.gradle
#   → Gradle 배포판(wrapper/dists) + 의존성(caches) 빌드 간 영구 보존
#   → 첫 빌드 이후 Gradle 재다운로드 없음
# build.gradle을 src보다 먼저 복사
#   → 소스만 변경된 경우 의존성 레이어 캐시 HIT
COPY build.gradle settings.gradle gradle.properties ./
COPY gradle ./gradle
COPY gradlew ./
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon || true

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon -x test

# ─── 2단계: 실행 ───────────────────────────────────────────
FROM eclipse-temurin:21-jre

WORKDIR /app

# 타임존 설정
ENV TZ=Asia/Seoul

COPY --from=builder /app/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]

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

# JVM 메모리 옵션 (2026-10-06). 컨테이너 한도(docker-compose.dev.yml api memory 1.5g)에 맞춘다.
# - 옵션을 안 주면 힙이 한도의 25%(256MB)뿐이고 비힙(스레드·메타스페이스·코드 캐시)이 RSS 대부분을 차지해
#   한도와의 여유를 가늠할 수 없었다. 힙 상한을 한도의 45%(약 690MB)로 두고 비힙 상한을 따로 막는다.
# - 최악 합계: 힙 690 + 메타스페이스 256 + 코드 캐시 128 + 다이렉트 128 + 스레드 스택 약 150 = 약 1.35GB < 1.5GB.
# - SerialGC는 이 크기에서 JVM이 고르던 값 그대로(작은 힙·낮은 CPU에 맞다). 명시만 한다.
# 환경변수 JAVA_OPTS 로 덮어쓸 수 있다. exec 로 java 가 PID 1 이 되어야 종료 신호(우아한 종료)를 받는다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=45 -XX:+UseSerialGC -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=128m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

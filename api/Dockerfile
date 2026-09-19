# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# 의존성 레이어 캐시. 소스만 바뀌면 이 단계 재사용
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar && mv build/libs/*.jar app.jar

FROM eclipse-temurin:25-jre
RUN groupadd --system sentinel && useradd --system --gid sentinel sentinel
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER sentinel
# 컨테이너 기본 UTC. 로그 시각 기준. cron 해석은 sentinel.schedule.zone 사용
ENV TZ=Asia/Seoul
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

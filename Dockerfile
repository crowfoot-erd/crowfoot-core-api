# crowfoot-core-api — Spring Boot 실행 이미지 (CI에서 mvn package 후 빌드)
FROM eclipse-temurin:21-jre
# 서버 시간대 — 로그 타임스탬프가 한국 시간(KST, UTC+9)으로 찍힌다 (JVM은 TZ를 자체 tzdb로 해석)
ENV TZ=Asia/Seoul
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

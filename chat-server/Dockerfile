FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY common ./common
COPY api-server ./api-server
COPY chat-server ./chat-server
RUN ./gradlew :chat-server:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/chat-server/build/libs/*.jar app.jar
# 실제 listen 포트는 SERVER_PORT 환경변수가 결정한다(application.yaml).
# chat-server-1=8081, chat-server-2=8082처럼 인스턴스마다 다르므로 EXPOSE를 고정하지 않는다.
# 외부 매핑은 docker-compose.yml의 ports:가 담당.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

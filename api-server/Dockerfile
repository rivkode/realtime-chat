FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY common ./common
COPY api-server ./api-server
COPY chat-server ./chat-server
RUN ./gradlew :api-server:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/api-server/build/libs/*.jar app.jar
# 외부 매핑은 docker-compose.yml의 ports:가 담당. EXPOSE는 metadata일 뿐이라 생략.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

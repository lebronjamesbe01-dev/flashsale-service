# syntax=docker/dockerfile:1

# Build stage: compile and package with Maven on JDK 27. The ~/.m2 cache mount keeps rebuilds fast.
FROM maven:3-eclipse-temurin-27 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests -Dspotless.check.skip=true
# Split the fat jar into layers: dependencies change rarely, application code changes often,
# so Docker can reuse the dependency layers between builds.
RUN java -Djarmode=tools -jar target/flashsale-service.jar extract --layers --launcher --destination extracted

# Runtime stage: JRE only, non-root user.
FROM eclipse-temurin:27-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

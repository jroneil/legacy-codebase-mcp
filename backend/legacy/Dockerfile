# Backend image: Maven build stage, then a Java 21 JRE runtime (no Maven, no tests).
# Build:  docker build -t legacy-backend ./backend/legacy
# Tests need a Docker daemon (Testcontainers), so they run on the host, not in this image.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
# The BuildKit cache mount keeps downloaded dependencies across rebuilds.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
# glibc, not musl: the Alpine JDK build does not expose SecureDirectoryStream, and the
# scanner deliberately refuses to inventory a repository without it.
RUN groupadd --system app && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app
COPY --from=build /build/target/legacy-0.0.1-SNAPSHOT.jar /app/app.jar
USER app
EXPOSE 8080
# Listen on all interfaces inside the container; host exposure stays loopback-only
# through the Compose port mapping (127.0.0.1:8080 -> 8080).
ENV SERVER_ADDRESS=0.0.0.0
HEALTHCHECK --interval=10s --timeout=5s --start-period=45s --retries=12 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health >/dev/null 2>&1 || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

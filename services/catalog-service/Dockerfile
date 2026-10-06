# syntax=docker/dockerfile:1
#
# Runtime image for catalog-service. Build the jar first (CI: `./mvnw -B -DskipTests package`), then:
#   docker build -t tazzzo-catalog-service:<git-sha> services/catalog-service
# The image carries NO configuration and NO secrets: everything comes from the environment at run time
# (docs/ops/DEPLOYMENT.md). It runs as an unprivileged user, works on a read-only root filesystem with a
# writable /tmp, and stops gracefully on SIGTERM.

# Pinned by digest (multi-arch index of eclipse-temurin:21-jre-jammy, resolved 2026-10-05): a rebuild is reproducible
# and a moved tag cannot change what ships. Bump deliberately (a PR that changes this line) for JRE security updates.
ARG JRE_IMAGE=eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b

FROM ${JRE_IMAGE} AS layers
WORKDIR /build
COPY target/catalog-service-*.jar app.jar
# Spring Boot layered jar: dependencies change rarely, the application layer on every commit.
RUN java -Djarmode=layertools -jar app.jar extract

FROM ${JRE_IMAGE}
RUN groupadd --system --gid 10001 tazzzo \
    && useradd --system --uid 10001 --gid tazzzo --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin tazzzo
WORKDIR /app
COPY --from=layers /build/dependencies/ ./
COPY --from=layers /build/spring-boot-loader/ ./
COPY --from=layers /build/snapshot-dependencies/ ./
COPY --from=layers /build/application/ ./
USER 10001:10001
# Heap sized from the container limit; die on OOM so the orchestrator restarts a clean process.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

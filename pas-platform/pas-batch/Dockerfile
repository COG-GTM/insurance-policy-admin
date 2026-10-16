# syntax=docker/dockerfile:1
# Build context is the pas-platform/ directory:
#   docker build -f pas-platform/pas-batch/Dockerfile -t pas-batch:0.1.0-SNAPSHOT pas-platform
# To use a Maven mirror/credentials at build time without baking them in:
#   docker build --secret id=maven_settings,src=$HOME/.m2/settings.xml \
#     -f pas-platform/pas-batch/Dockerfile -t pas-batch:0.1.0-SNAPSHOT pas-platform

FROM eclipse-temurin:21-jdk AS build
# unzip is required so mvnw downloads the .zip distribution that matches the pinned checksum
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
# Optional: point the Maven wrapper at an alternate repo base (defaults to Maven Central)
ARG MVNW_REPOURL=
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml,required=false \
    ./mvnw -B -ntp -pl pas-batch -am package -DskipTests -Dspotless.check.skip=true -Dspotbugs.skip=true

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home appuser
USER 10001
WORKDIR /app
COPY --from=build /workspace/pas-batch/target/pas-batch-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8081
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8081/actuator/health/readiness || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","app.jar"]

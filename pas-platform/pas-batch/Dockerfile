FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
ARG MVNW_REPOURL=
ENV MVNW_REPOURL=${MVNW_REPOURL}
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
COPY . .
RUN --mount=type=cache,target=/root/.m2 --mount=type=secret,id=maven_settings,required=false \
    SETTINGS_ARGS=""; \
    if [ -f /run/secrets/maven_settings ]; then SETTINGS_ARGS="-s /run/secrets/maven_settings"; fi; \
    ./mvnw ${SETTINGS_ARGS} -B -ntp -DskipTests -Dspotless.check.skip=true -Dspotbugs.skip=true \
    -pl pas-batch -am package

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home appuser
RUN mkdir -p /var/lib/pas-batch/extracts && chown 10001:10001 /var/lib/pas-batch/extracts
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/pas-batch/target/pas-batch-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8081
HEALTHCHECK --interval=20s --timeout=3s --start-period=30s --retries=5 \
    CMD curl -fsS http://localhost:8081/actuator/health/readiness || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]

# Runtime image for a PAS deployable (ADR 0001 D2). Build the jars first (`make pas-build`), then:
#   docker build -f pas/Dockerfile --build-arg APP=pas-policy-service pas
# The Spring Boot jar is extracted into layers so dependency layers are cached between builds.
# Literal, digest-pinned base so Dependabot can bump it; keep both stages on the same reference.
FROM eclipse-temurin:21-jre-alpine@sha256:82cf3d9ccbe1ed46252008d51b2f2329e6b9e8a2f4394c4bebefc3ea7f4b1372 AS extract
ARG APP
WORKDIR /build
COPY ${APP}/target/${APP}-exec.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-alpine@sha256:82cf3d9ccbe1ed46252008d51b2f2329e6b9e8a2f4394c4bebefc3ea7f4b1372
ARG APP
LABEL org.opencontainers.image.title="${APP}" \
      org.opencontainers.image.source="https://github.com/COG-GTM/insurance-policy-admin" \
      org.opencontainers.image.description="PAS target platform (Java 21 / Spring Boot 3)"
# Pick up OS security fixes published after the base image was built.
RUN apk upgrade --no-cache && addgroup -S -g 10001 pas && adduser -S -D -H -u 10001 -G pas pas
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080 8081
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

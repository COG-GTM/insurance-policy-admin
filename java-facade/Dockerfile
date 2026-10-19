# PAS REST Facade - Docker Image
# Java 8 base (matching z/OS Java runtime version)
# NOTE: This runs on a Linux VM, not on z/OS.
# It connects to mainframe DB2 over the network.

# ---------------------------------------------------------------------------
# Stage 1 – Build the JAR with Maven
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-8 AS build

WORKDIR /workspace

# Cache dependency layer – only re-download when pom.xml changes
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn package -B -DskipTests

# ---------------------------------------------------------------------------
# Stage 2 – Runtime image (Debian-based for ARM64 support)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:8-jre

LABEL maintainer="t.nguyen@acme.com"
LABEL description="Policy Administration System REST Facade"
LABEL version="1.0.0"
LABEL created="2022-03-15"

RUN mkdir -p /app/logs

COPY --from=build /workspace/target/pas-facade-1.0.0.jar /app/pas-facade.jar

WORKDIR /app

# DB2 credentials are NOT baked into the image. Inject DB2_USERNAME and
# DB2_PASSWORD at runtime from an orchestrator secret (Kubernetes Secret,
# Docker secret, or vault sidecar); the application fails to start without them.
ENV JAVA_OPTS="-Xmx512m -Xms256m"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar pas-facade.jar"]

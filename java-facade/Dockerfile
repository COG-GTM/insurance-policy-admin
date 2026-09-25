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

# DB2 connection credentials passed via environment
ENV DB2_USERNAME=PASFACAD
ENV DB2_PASSWORD=changeme

# The DB2 connection is TLS-only. Mount the truststore holding the CA that
# signed the DB2 server certificate and supply its password at runtime, e.g.
#   -v /etc/pki/db2:/etc/pki/db2:ro \
#   -e DB2_SSL_TRUSTSTORE=/etc/pki/db2/db2-truststore.p12 \
#   -e DB2_SSL_TRUSTSTORE_PASSWORD=... \
#   -e DB2_PORT=<SECPORT of the DB2 subsystem>
ENV DB2_SSL_TRUSTSTORE_TYPE=PKCS12
ENV JAVA_OPTS="-Xmx512m -Xms256m"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar pas-facade.jar"]

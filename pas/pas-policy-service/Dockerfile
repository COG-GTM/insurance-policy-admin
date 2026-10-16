# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=secret,id=maven-settings,target=/tmp/maven-settings.xml,required=false \
    if [ -s /tmp/maven-settings.xml ]; then \
      mvn -s /tmp/maven-settings.xml -B -ntp -pl pas-policy-service -am -DskipTests package; \
    else \
      rm -f /root/.m2/settings.xml && \
        mvn -B -ntp -pl pas-policy-service -am -DskipTests package; \
    fi

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home appuser
WORKDIR /app
COPY --from=build /workspace/pas-policy-service/target/pas-policy-service.jar /app/pas-policy-service.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080 8081
USER 10001
ENTRYPOINT ["java", "-jar", "/app/pas-policy-service.jar"]

# syntax=docker/dockerfile:1.6
# Build context: repo root  (docker build -f portfolio-mcp-gateway/Dockerfile .)

# ── Build stage ─────────────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml                                  ./pom.xml
COPY portfolio-agent-service/pom.xml          ./portfolio-agent-service/pom.xml
COPY portfolio-mcp-gateway/pom.xml            ./portfolio-mcp-gateway/pom.xml

RUN mvn -B -q -DskipTests dependency:go-offline -pl portfolio-mcp-gateway -am

COPY portfolio-mcp-gateway/src                ./portfolio-mcp-gateway/src
RUN mvn -B -q -DskipTests package -pl portfolio-mcp-gateway -am

# ── Runtime stage ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

COPY --from=build /workspace/portfolio-mcp-gateway/target/portfolio-mcp-gateway.jar /app/app.jar

ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75 -Djava.net.preferIPv6Addresses=true"
EXPOSE 8091

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

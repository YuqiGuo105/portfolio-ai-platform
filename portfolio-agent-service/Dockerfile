# syntax=docker/dockerfile:1.6
# Build context: repo root  (docker build -f portfolio-agent-service/Dockerfile .)

# ── Build stage ─────────────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml                                  ./pom.xml
COPY shared-contracts/pom.xml                 ./shared-contracts/pom.xml
COPY portfolio-agent-service/pom.xml          ./portfolio-agent-service/pom.xml
COPY portfolio-mcp-gateway/pom.xml            ./portfolio-mcp-gateway/pom.xml
COPY knowledge-service/pom.xml                ./knowledge-service/pom.xml

RUN mvn -B -q -DskipTests dependency:go-offline -pl portfolio-agent-service -am

COPY shared-contracts/src                     ./shared-contracts/src
COPY portfolio-agent-service/src              ./portfolio-agent-service/src
RUN mvn -B -q -DskipTests package -pl portfolio-agent-service -am

# ── Runtime stage ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

COPY --from=build /workspace/portfolio-agent-service/target/portfolio-agent-service.jar /app/app.jar

ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75 -Djava.net.preferIPv6Addresses=true"
EXPOSE 8090

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

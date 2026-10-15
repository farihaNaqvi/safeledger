# Build stage
FROM maven:3-eclipse-temurin-25-noble AS build
WORKDIR /workspace
COPY pom.xml /workspace/pom.xml
COPY mock-bank-api/pom.xml /workspace/mock-bank-api/pom.xml
COPY bank-mcp-server/pom.xml /workspace/bank-mcp-server/pom.xml
COPY agent-service/pom.xml /workspace/agent-service/pom.xml
COPY eval/pom.xml /workspace/eval/pom.xml
RUN mvn -q -N install
COPY bank-mcp-server/src /workspace/bank-mcp-server/src
RUN mvn -q -pl bank-mcp-server -am package -DskipTests

# Run stage
FROM eclipse-temurin:25-jre-noble
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/bank-mcp-server/target/bank-mcp-server-*.jar /app/app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

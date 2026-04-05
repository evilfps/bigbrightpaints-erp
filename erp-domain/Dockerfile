# syntax=docker/dockerfile:1

# --- Build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace/erp-domain

# Leverage Docker layer caching: copy pom and resolve deps first
COPY erp-domain/pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

# Now copy the actual sources
COPY erp-domain/src ./src

RUN mvn -q -DskipTests package

# --- Runtime stage ---
FROM eclipse-temurin:21-jre
ENV JAVA_OPTS=""
WORKDIR /app

COPY --from=build /workspace/erp-domain/target/erp-domain-*.jar /app/app.jar

# Datadog Java APM agent (auto-instruments JDBC, HTTP, Spring MVC, Hibernate)
ADD https://dtdg.co/latest-java-tracer /app/dd-java-agent.jar

EXPOSE 8081
ENTRYPOINT ["sh","-c","java $JAVA_OPTS $DD_JAVA_OPTS -jar /app/app.jar"]

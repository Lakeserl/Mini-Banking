# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-alpine AS builder
RUN apk add --no-cache wget
WORKDIR /build
ENV MAVEN_OPTS="-Xmx512m -Xms128m"

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src src
RUN ./mvnw -B package -DskipTests -Dasciidoctor.skip=true

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -g 10001 -S appgroup && \
    adduser -u 10001 -S appuser -G appgroup

WORKDIR /app
COPY --from=builder --chown=appuser:appgroup /build/target/mini-banking-*.jar /app/app.jar

USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=4 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

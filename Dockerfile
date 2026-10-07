# syntax=docker/dockerfile:1
# Local image for the API. Tests are not run here (they need Docker for Testcontainers);
# run ./mvnw verify separately.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
    && cp target/idempotent-payment-ledger-*.jar /src/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/app.jar app.jar
USER ubuntu
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

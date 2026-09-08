# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-noble@sha256:75ce56643243c3db632be2ef259625fb42ee3be1334389659f7a1a61acb78783 AS build
RUN apt-get update && apt-get install -y --no-install-recommends unzip
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src
RUN ./mvnw -B -ntp package

FROM eclipse-temurin:21-jre-noble@sha256:96975602e131485862eb8cd32927face8a06d7591a5e865944b634a701d9df72 AS runtime
WORKDIR /app
RUN groupadd --gid 10001 bovina && useradd --uid 10001 --gid 10001 --no-create-home bovina
COPY --from=build --chown=10001:10001 /workspace/target/bovina.jar /app/bovina.jar
USER 10001:10001
ENV TZ=UTC
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/bovina.jar"]

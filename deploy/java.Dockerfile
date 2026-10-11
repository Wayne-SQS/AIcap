FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY java-backend/pom.xml java-backend/pom.xml
COPY java-backend/src java-backend/src
RUN mvn -q -f java-backend/pom.xml -Djava.version=21 -Dmaven.compiler.release=21 -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/java-backend/target/aicap-java-backend.jar app.jar
RUN mkdir -p /data/audio && chown -R 10001:10001 /app /data
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

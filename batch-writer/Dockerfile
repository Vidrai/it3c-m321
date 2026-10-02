# Stufe 1: bauen
# Der Build-Kontext ist das Projekt-Wurzelverzeichnis, weil das Modul
# das Eltern-POM braucht.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY batch-writer/pom.xml batch-writer/pom.xml
COPY batch-writer/src batch-writer/src
# Tests werden hier übersprungen: Testcontainers bräuchte einen Docker-Daemon
# INNERHALB des Builds. Getestet wird vorher mit "mvn test".
#
# "-f batch-writer/pom.xml" baut nur dieses eine Modul, das Eltern-POM
# findet Maven über ../pom.xml (siehe chat-service/Dockerfile).
RUN mvn -q -f batch-writer/pom.xml package -DskipTests

# Stufe 2: laufen
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/batch-writer/target/batch-writer-0.1.0-SNAPSHOT.jar app.jar
# Kein EXPOSE: Der batch-writer nimmt keine Verbindungen an. Er baut nur
# selbst Verbindungen zu RabbitMQ und PostgreSQL auf.
ENTRYPOINT ["java", "-jar", "app.jar"]

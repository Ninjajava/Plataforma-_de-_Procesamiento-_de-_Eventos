FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
RUN mvn -B verify
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/ticketing-1.0.0.jar app.jar
RUN mkdir -p /app/tmp && chown 10001:10001 /app/tmp
VOLUME ["/app/tmp"]
ENV JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/app/tmp"
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]

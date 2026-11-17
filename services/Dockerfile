# One Dockerfile for every service. Build from the services folder:
#   docker build --build-arg SERVICE=decision -f Dockerfile .
FROM eclipse-temurin:21-jdk AS build
ARG SERVICE
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY common common
COPY decision/pom.xml decision/
COPY case/pom.xml case/
COPY strategy/pom.xml strategy/
COPY copilot/pom.xml copilot/
RUN chmod +x mvnw && ./mvnw -B -q -pl "${SERVICE}" -am dependency:go-offline
COPY ${SERVICE}/src ${SERVICE}/src
RUN ./mvnw -B -q -pl "${SERVICE}" -am -DskipTests package && cp "${SERVICE}/target/app.jar" /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
COPY --from=build /app.jar /app/app.jar
USER 10001
EXPOSE 8080 8081
# JVM options come from JAVA_TOOL_OPTIONS, set per environment by the Helm chart.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
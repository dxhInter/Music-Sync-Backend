FROM maven:3.8-eclipse-temurin-8 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
COPY application-local.yml ./src/main/resources/application-local.yml
RUN mvn -B package -DskipTests -q

FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

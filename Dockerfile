FROM maven:3.8-eclipse-temurin-8 AS build
WORKDIR /build
COPY . .
RUN mvn package -DskipTests -q

FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

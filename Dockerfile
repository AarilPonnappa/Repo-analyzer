# build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# run stage
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 1001 repolens
COPY --from=build /app/target/repolens-*.jar app.jar
USER repolens
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

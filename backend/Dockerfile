FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar

USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

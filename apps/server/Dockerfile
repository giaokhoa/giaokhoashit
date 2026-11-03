# Image của apps/server (Spring Boot): build bằng Maven trên JDK 25 —
# đúng bản Java khai trong pom.xml — rồi chạy bằng JRE 25 gọn nhẹ.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/server-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

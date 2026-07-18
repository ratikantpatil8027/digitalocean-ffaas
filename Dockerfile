# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

# Runtime stage — DB via DB_URL / DB_USER / DB_PASSWORD (see application.yml)
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget \
    && addgroup -S ffaas && adduser -S ffaas -G ffaas
USER ffaas
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]

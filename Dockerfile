# ==================== Stage 1: Maven Build ====================
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

COPY pom.xml ./
COPY flash-common/pom.xml ./flash-common/
COPY flash-model/pom.xml ./flash-model/
COPY flash-mapper/pom.xml ./flash-mapper/
COPY flash-service/pom.xml ./flash-service/
COPY flash-api/pom.xml ./flash-api/
COPY flash-admin/pom.xml ./flash-admin/
COPY flash-gateway/pom.xml ./flash-gateway/

# Download dependencies (cache layer)
RUN mvn dependency:go-offline -B -q || true

# Copy sources
COPY flash-common/src ./flash-common/src
COPY flash-model/src ./flash-model/src
COPY flash-mapper/src ./flash-mapper/src
COPY flash-service/src ./flash-service/src
COPY flash-api/src ./flash-api/src
COPY flash-admin/src ./flash-admin/src
COPY flash-gateway/src ./flash-gateway/src

# Build all modules, skip tests
RUN mvn package -DskipTests -B -q

# ==================== Stage 2: Gateway ====================
FROM eclipse-temurin:21-jre-alpine AS flash-gateway
WORKDIR /app
COPY --from=builder /build/flash-gateway/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

# ==================== Stage 3: API ====================
FROM eclipse-temurin:21-jre-alpine AS flash-api
WORKDIR /app
COPY --from=builder /build/flash-api/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]

# ==================== Stage 4: Admin ====================
FROM eclipse-temurin:21-jre-alpine AS flash-admin
WORKDIR /app
COPY --from=builder /build/flash-admin/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]

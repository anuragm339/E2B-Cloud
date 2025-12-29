# Multi-stage Dockerfile for Micronaut Cloud Server

# Stage 1: Build
FROM gradle:8.5-jdk17 AS builder

WORKDIR /build

# Copy CA certificate and import it into JDK trust store (if needed)
# Uncomment if you have a custom CA certificate:
# COPY ../provider/mvnrepository.com.pem /tmp/mvnrepository.com.pem
# RUN keytool -import -trustcacerts -noprompt \
#     -alias messaging_maven_ca \
#     -file /tmp/mvnrepository.com.pem \
#     -keystore /opt/java/openjdk/lib/security/cacerts \
#     -storepass changeit && \
#     rm /tmp/mvnrepository.com.pem

# Copy gradle files first for better caching
COPY build.gradle settings.gradle* gradlew* ./
COPY gradle gradle/

# Copy source code
COPY src src/

# Build application with shadowJar to create fat JAR
RUN ./gradlew shadowJar -x test --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-jammy

# Create non-root user
RUN groupadd -r cloudserver && useradd -r -g cloudserver cloudserver

WORKDIR /app

# Copy built fat JAR from builder stage (shadow creates -all.jar)
COPY --from=builder /build/build/libs/*-all.jar app.jar

# Create data directory with proper permissions
RUN mkdir -p /data && chown -R cloudserver:cloudserver /app /data

# Switch to non-root user
USER cloudserver

# Environment variables with defaults
ENV SQLITE_DB_PATH=/data/events.db \
    DATA_MODE=TEST \
    JAVA_OPTS="-Xms64m -Xmx256m -XX:+UseG1GC"

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# Run the application
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

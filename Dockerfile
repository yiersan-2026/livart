FROM node:20-alpine AS frontend-build
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend ./
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-17 AS backend-build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
RUN cd backend && mvn -q -DskipTests dependency:go-offline
COPY backend backend
COPY --from=frontend-build /workspace/frontend/dist backend/src/main/resources/static
RUN cd backend && mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl netcat-openbsd \
    && rm -rf /var/lib/apt/lists/*
ENV SERVER_PORT=8080
ENV SPRING_PROFILES_ACTIVE=prod
COPY --from=backend-build /workspace/backend/target/livart-backend-0.0.1-SNAPSHOT.jar /app/livart.jar
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/api/health" >/dev/null || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]

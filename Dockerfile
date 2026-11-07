# One image: React app built into the Spring Boot jar (ADR-0003, ADR-0007).

FROM node:22-bookworm-slim AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-25 AS backend
WORKDIR /app/backend
COPY backend/pom.xml ./
RUN mvn -B -q -DskipFrontend=true dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /app/frontend/dist /app/frontend/dist
# npm steps are skipped; Maven copies the frontend/dist built above into the jar.
RUN mvn -B -q -DskipFrontend=true -DskipTests package

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=backend /app/backend/target/bd-platform.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

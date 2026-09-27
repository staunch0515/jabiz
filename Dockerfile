# syntax=docker/dockerfile:1
# The whole application in one image: the backend jar with the frontend packaged into it (docs/design/12-frontend.md
# section 7). Built from source, so `docker compose up --build` needs nothing but Docker.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY backend backend
COPY frontend frontend
COPY spec spec
# node-gradle downloads Node and pnpm and builds the frontend before packaging it (backend/build.gradle.kts).
RUN --mount=type=cache,target=/root/.gradle \
    cd backend && ./gradlew :app:bootJar --no-daemon --console=plain \
    && cp app/build/libs/app-0.0.1-SNAPSHOT.jar /src/app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /app jabiz \
    && mkdir -p /app /var/lib/jabiz/secrets /var/lib/jabiz/files \
    && chown jabiz /var/lib/jabiz/secrets /var/lib/jabiz/files && chmod 700 /var/lib/jabiz/secrets /var/lib/jabiz/files
COPY --from=build /src/app.jar /app/app.jar
COPY docker/app-entrypoint.sh /app/entrypoint.sh
USER jabiz
WORKDIR /app
EXPOSE 8080
# Local-demo secrets generated on first start when none are given (docker/app-entrypoint.sh).
VOLUME /var/lib/jabiz/secrets
# Uploaded files (docs/design/14-files.md section 6).
ENV JABIZ_FILES_LOCAL_ROOT=/var/lib/jabiz/files
VOLUME /var/lib/jabiz/files
HEALTHCHECK --interval=5s --timeout=3s --start-period=60s --retries=24 \
    CMD curl -sf http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]

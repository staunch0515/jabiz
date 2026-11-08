# syntax=docker/dockerfile:1
# The whole application in one image: the backend jar with the frontend packaged into it (docs/design/12-frontend.md
# section 7). Built from source, so `docker compose up --build` needs nothing but Docker.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Gradle's home outside the cache mount below, so that the wrapper's distribution can live in a layer of its own.
ENV GRADLE_USER_HOME=/opt/gradle-home
# The Gradle distribution first, in a layer that only the wrapper's files invalidate: a build with the layer cache
# (CI's) does not download it again, and a failed download is tried five times, waiting 15, 30, 60 and 120 s between
# (about four minutes: services.gradle.org redirects to GitHub's release downloads, which have answered 504 for
# minutes at a time) rather than failing the build (ROADMAP 17zz10; the wrapper's own timeout is networkTimeout in
# gradle-wrapper.properties).
COPY backend/gradlew backend/gradlew
COPY backend/gradle/wrapper backend/gradle/wrapper
RUN cd backend && wait=15 && for attempt in 1 2 3 4 5; do \
      ./gradlew --version --no-daemon --console=plain >/dev/null && exit 0; \
      [ "$attempt" -lt 5 ] || break; \
      echo "downloading Gradle failed (attempt $attempt), trying again in $wait s" >&2; sleep "$wait"; wait=$((wait * 2)); \
    done; exit 1
COPY backend backend
COPY frontend frontend
COPY spec spec
# The commit the image is built from (docker build --build-arg JABIZ_BUILD_COMMIT=$(git rev-parse HEAD)): the admin's
# sign-in page shows it. The repository itself stays out of the build context (.dockerignore).
ARG JABIZ_BUILD_COMMIT=""
# node-gradle downloads Node and pnpm and builds the frontend before packaging it (backend/build.gradle.kts).
RUN --mount=type=cache,target=/opt/gradle-home/caches \
    cd backend && JABIZ_BUILD_COMMIT="$JABIZ_BUILD_COMMIT" ./gradlew :app:bootJar --no-daemon --console=plain \
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

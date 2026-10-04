# syntax=docker/dockerfile:1
# The finance application in one image: the backend jar with the admin frontend packaged in
# (docs/design/17-apps-and-branches.md section 3). Built from the repository root:
#   docker build -f deploy/finance/Dockerfile .

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY backend backend
COPY frontend frontend
# The finance pages: the platform frontend compiles them in as the application's extension (decision D22).
COPY finance-web finance-web
COPY spec spec
# node-gradle downloads Node and pnpm and builds the single-page application before packaging it.
RUN --mount=type=cache,target=/root/.gradle \
    cd backend && ./gradlew :finance:bootJar --no-daemon --console=plain \
    && cp finance/build/libs/finance-0.0.1-SNAPSHOT.jar /src/app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /app jabiz \
    && mkdir -p /app /var/lib/jabiz/secrets /var/lib/jabiz/files \
    && chown jabiz /var/lib/jabiz/secrets /var/lib/jabiz/files && chmod 700 /var/lib/jabiz/secrets /var/lib/jabiz/files
COPY --from=build /src/app.jar /app/app.jar
# The platform's entrypoint: secrets of a first local start are generated once (docs/design/10-security.md section 7).
COPY docker/app-entrypoint.sh /app/entrypoint.sh
USER jabiz
WORKDIR /app
EXPOSE 8080
VOLUME /var/lib/jabiz/secrets
ENV JABIZ_FILES_LOCAL_ROOT=/var/lib/jabiz/files
VOLUME /var/lib/jabiz/files
HEALTHCHECK --interval=5s --timeout=3s --start-period=60s --retries=24 \
    CMD curl -sf http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]

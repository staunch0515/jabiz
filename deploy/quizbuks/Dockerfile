# syntax=docker/dockerfile:1
# QuizBuks in one image: the backend jar with its three front ends packaged in: the app at / (a PWA), the sponsor
# console at /sponsor/ (both from quizbuks-web/) and the platform's admin frontend at /admin/
# (docs/design/17-apps-and-branches.md section 3; docs/quizbuks/plans/QP-pages.md). Built from the repository root:
#   docker build -f deploy/quizbuks/Dockerfile .

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY backend backend
COPY frontend frontend
COPY quizbuks-web quizbuks-web
COPY spec spec
# The commit the image is built from (the deploy workflow passes --build-arg JABIZ_BUILD_COMMIT=<sha>): the admin's
# sign-in page shows it (platform 16z). The repository itself stays out of the build context.
ARG JABIZ_BUILD_COMMIT=""
# node-gradle downloads Node and pnpm and builds the single-page applications before packaging them.
# (QuizBuks for Shopify's Client ID is no longer built in: the server writes it into the embedded pages at startup
# from SHOPIFY_API_KEY, S4a / platform 17g.) Its public pages (/shopify-app/) name the support address, written in at
# build time: the same value as the running server's QB_SHOPIFY_SUPPORT_EMAIL (empty: the default address).
ARG QB_SHOPIFY_SUPPORT_EMAIL=""
RUN --mount=type=cache,target=/root/.gradle \
    cd backend && JABIZ_BUILD_COMMIT="$JABIZ_BUILD_COMMIT" QB_SHOPIFY_SUPPORT_EMAIL="$QB_SHOPIFY_SUPPORT_EMAIL" ./gradlew :quizbuks:bootJar --no-daemon --console=plain \
    && cp quizbuks/build/libs/quizbuks-0.0.1-SNAPSHOT.jar /src/app.jar

FROM eclipse-temurin:21-jre
# Chinese and Japanese in the PDFs (receipts, monthly reports; Q9d, D-5): TrueType fonts the platform's PDF writer can
# embed (application.yml jabiz.reports.pdf.fonts), IPA Gothic for Japanese, Droid Sans Fallback for Chinese. curl is
# the health check's client (below and deploy/quizbuks/server/compose.yaml), installed rather than assumed.
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-ipafont-gothic fonts-droid-fallback curl \
    && rm -rf /var/lib/apt/lists/*
ENV JABIZ_REPORTS_PDF_FONTS=/usr/share/fonts/opentype/ipafont-gothic/ipag.ttf,/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf
RUN useradd --system --uid 10001 --home-dir /app jabiz \
    && mkdir -p /app /var/lib/jabiz/secrets /var/lib/jabiz/files \
    && chown jabiz /var/lib/jabiz/secrets /var/lib/jabiz/files && chmod 700 /var/lib/jabiz/secrets /var/lib/jabiz/files
COPY --from=build /src/app.jar /app/app.jar
# The platform's entrypoint: secrets of a first local start are generated once (docs/design/10-security.md section 7).
# QuizBuks's own runs first: Stripe placeholders for a plain local compose only (entrypoint.sh beside this file).
COPY --chmod=755 docker/app-entrypoint.sh /app/platform-entrypoint.sh
COPY --chmod=755 deploy/quizbuks/entrypoint.sh /app/entrypoint.sh
USER jabiz
WORKDIR /app
EXPOSE 8080
VOLUME /var/lib/jabiz/secrets
ENV JABIZ_FILES_LOCAL_ROOT=/var/lib/jabiz/files
# The heap follows the container's memory limit (mem_limit in the server's compose); Netty's direct buffers would
# otherwise be allowed as much again as the heap.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:MaxDirectMemorySize=256m"
VOLUME /var/lib/jabiz/files
HEALTHCHECK --interval=5s --timeout=3s --start-period=60s --retries=24 \
    CMD curl -sf http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]

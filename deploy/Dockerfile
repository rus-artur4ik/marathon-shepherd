# Marathon Shepherd — Manager service
# Build from repo root:  docker build -f deploy/Dockerfile -t shepherd-manager .

# ── Stage 1: Build ──────────────────────────────────────────────────────────────
# The builder only produces JVM bytecode, so it always runs on the build host's
# native platform; only the runtime stage below is built per target architecture.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS builder

WORKDIR /build
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties* ./
COPY gradle/ gradle/
COPY adapter/ adapter/
COPY manager/ manager/

RUN chmod +x gradlew && ./gradlew :manager:service:installDist --no-daemon

# ── Stage 2: Runtime ────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir -p /etc/msh /var/lib/msh

COPY --from=builder /build/manager/service/build/install/manager /opt/shepherd
COPY deploy/msh.yaml.example /etc/msh/msh.yaml

ENV MSH_PORT=6037
ENV MSH_CONFIG=/etc/msh/msh.yaml
ENV MSH_DATA_DIR=/var/lib/msh

EXPOSE 6037

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD curl -sf http://localhost:6037/live || exit 1

ENTRYPOINT ["/opt/shepherd/bin/manager"]

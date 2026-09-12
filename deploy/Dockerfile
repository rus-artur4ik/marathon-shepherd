# Marathon Shepherd — Manager service
# Build from repo root:  docker build -f deploy/Dockerfile -t shepherd-manager .

# ── Stage 1: Build ──────────────────────────────────────────────────────────────
# The builder only produces JVM bytecode, so it always runs on the build host's
# native platform; only the runtime stage below is built per target architecture.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS builder

WORKDIR /build
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties* ./
COPY gradle/ gradle/
COPY adapter/ adapter/
COPY manager/ manager/

RUN chmod +x gradlew && ./gradlew :manager:service:installDist :manager:cli:installDist :manager:mcp:installDist --no-daemon

# ── Stage 2: Runtime ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir -p /etc/msh /var/lib/msh

COPY --from=builder /build/manager/service/build/install/manager /opt/shepherd
# mshctl for `docker exec msh-manager mshctl health`, and the stdio MCP server for agents
# that run it as `docker run -i --rm ... shepherd-mcp` (see docs/mcp.md).
COPY --from=builder /build/manager/cli/build/install/mshctl /opt/mshctl
COPY --from=builder /build/manager/mcp/build/install/shepherd-mcp /opt/shepherd-mcp
RUN ln -s /opt/mshctl/bin/mshctl /usr/local/bin/mshctl && \
    ln -s /opt/shepherd-mcp/bin/shepherd-mcp /usr/local/bin/shepherd-mcp
COPY deploy/msh.yaml.example /etc/msh/msh.yaml

ENV MSH_PORT=6037
ENV MSH_CONFIG=/etc/msh/msh.yaml
ENV MSH_DATA_DIR=/var/lib/msh
# Set MSH_DB_URL to a jdbc:postgresql:// URL to keep state in Postgres instead of the
# SQLite file in MSH_DATA_DIR. One manager per database, either way.

EXPOSE 6037

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD curl -sf http://localhost:6037/live || exit 1

ENTRYPOINT ["/opt/shepherd/bin/manager"]

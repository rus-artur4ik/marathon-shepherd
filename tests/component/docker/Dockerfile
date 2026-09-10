# Marathon Shepherd — Manager service (test image).
#
# Skips the Gradle-inside-Docker build stage: expects
# `./gradlew :manager:service:installDist` to have run locally first (the
# [build] all stage in tests/run_tests.py does this). Ships the already-built
# distribution into an eclipse-temurin JRE runtime — no JDK layer, no gradle
# dependency download inside the container.
FROM eclipse-temurin:25-jre

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir -p /etc/msh /var/lib/msh

COPY manager/service/build/install/manager /opt/shepherd
COPY deploy/msh.yaml.example /etc/msh/msh.yaml

RUN if [ ! -x /opt/shepherd/bin/manager ]; then \
      echo "ERROR: /opt/shepherd/bin/manager missing — run ./gradlew :manager:service:installDist before docker build" >&2; \
      exit 1; \
    fi

ENV MSH_PORT=6037
ENV MSH_CONFIG=/etc/msh/msh.yaml
ENV MSH_DATA_DIR=/var/lib/msh

EXPOSE 6037

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD curl -sf http://localhost:6037/live || exit 1

ENTRYPOINT ["/opt/shepherd/bin/manager"]

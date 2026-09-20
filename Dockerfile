# =============================================================================
# CNCF Tech Advisor MCP Server - JVM Multi-stage Build
# =============================================================================
# The JVM image. The native executable has its own images under src/main/docker/
# (Dockerfile.native on UBI minimal, Dockerfile.native-micro on the Quarkus micro base),
# which package a binary compiled on the host or in CI rather than compiling in a stage:
# that keeps one compilation feeding the tests, the JVM/native parity check and both
# images. Same layout as mcp-redhat-kb.
#
# Build:
#   docker build -t cncf-tech-advisor-mcp .
#
# Run:
#   docker run -i --rm -p 127.0.0.1:8080:8080 cncf-tech-advisor-mcp
#
# The port is published on loopback in the example on purpose: this image binds 0.0.0.0
# and carries no authentication of its own. Put a network boundary in front of it.
# =============================================================================

# Stage 1: Build
FROM registry.access.redhat.com/ubi9/openjdk-25:1.24 AS build

USER root
RUN microdnf install -y gzip tar && microdnf clean all
USER 185

WORKDIR /build

# Copy Maven wrapper and pom.xml first (for layer caching)
COPY --chown=185 mvnw .
COPY --chown=185 .mvn .mvn
COPY --chown=185 pom.xml .

# Download dependencies (cached if pom.xml unchanged)
RUN ./mvnw dependency:go-offline -B

# Copy source code
COPY --chown=185 src src

# Build the application, running the tests as a gate.
RUN ./mvnw package -B

# Stage 2: Runtime
FROM registry.access.redhat.com/ubi9/openjdk-25:1.24

LABEL io.modelcontextprotocol.server.name="io.github.jeanlopezxyz/cncf-tech-advisor-mcp"
LABEL io.k8s.display-name="CNCF Tech Advisor MCP Server"
LABEL io.openshift.tags="mcp,cncf,kubernetes,landscape,technology-advisor,quarkus"
LABEL maintainer="Jean Lopez"
LABEL description="MCP Server for CNCF Landscape Technology Data (JVM)"

# Copy the built application from build stage
COPY --from=build --chown=185 /build/target/quarkus-app/lib/ /deployments/lib/
COPY --from=build --chown=185 /build/target/quarkus-app/*.jar /deployments/
COPY --from=build --chown=185 /build/target/quarkus-app/app/ /deployments/app/
COPY --from=build --chown=185 /build/target/quarkus-app/quarkus/ /deployments/quarkus/

EXPOSE 8080

USER 185

# A container serves the HTTP transport: the port must be reachable from outside the
# network namespace, and stdio (the default for a locally launched process) is switched
# off because nothing is attached to the container's stdin. Both are runtime properties,
# so a `docker run -e` override still wins.
ENV QUARKUS_HTTP_HOST=0.0.0.0 \
    QUARKUS_HTTP_PORT=8080 \
    QUARKUS_HTTP_HOST_ENABLED=true \
    QUARKUS_MCP_SERVER_STDIO_ENABLED=false

ENTRYPOINT ["java", \
    "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", \
    "-jar", "/deployments/quarkus-run.jar"]

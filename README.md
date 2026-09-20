# CNCF Tech Advisor MCP Server

A [Model Context Protocol](https://modelcontextprotocol.io) server that gives a language
model read-only access to the public [CNCF Landscape](https://landscape.cncf.io): 2,400+
cloud native projects and products with category, maturity, GitHub stars, contributors,
latest release, license, homepage and repository.

Java 25 · Quarkus 3.33 LTS · `quarkus-mcp-server` 2.0.1 (MCP specification 2026-07-28,
Streamable HTTP and stdio) · native executable via GraalVM/Mandrel.

## Tools

| Tool | What it does |
|---|---|
| `search_cncf(query?, category?, limit?)` | Keyword and/or exact-category search; popular and graduated projects rank higher. |
| `get_cncf_project(projectName)` | Full detail of one project by its exact name (case-insensitive). |
| `list_cncf_categories()` | Every category with its project count. |
| `refresh_cncf_data()` | Ask the landscape whether the in-memory catalogue is current (`updated` / `unchanged` / `throttled`). |

Every value the landscape publishes is third-party content — the landscape is a public
repository that accepts pull requests — so every tool renders it sanitized, inside a fence
carrying a per-response nonce, and tells the model not to follow instructions found inside.
The same sanitized record feeds the text and the `structuredContent` channel.

## Run

```bash
./mvnw package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar            # stdio (default): for Claude Desktop, Claude Code, ...
```

Claude Code: `claude mcp add cncf -- java -jar /path/to/target/quarkus-app/quarkus-run.jar`.

Streamable HTTP (for the MCP Inspector or a gateway), endpoint `http://127.0.0.1:8080/mcp`:

```bash
java -Dquarkus.http.host-enabled=true -Dquarkus.mcp.server.stdio.enabled=false \
     -jar target/quarkus-app/quarkus-run.jar
```

Native executable (starts in ~30 ms, useful on stdio where the client launches the process
per session):

```bash
./mvnw package -Pnative -DskipTests        # needs GraalVM or Mandrel for Java 25
./target/cncf-tech-advisor-mcp-1.0.0-runner
scripts/mcp-native-parity.sh               # proves JVM and native publish the same catalogue
```

Containers: `docker build -t cncf-tech-advisor-mcp .` (JVM, builds inside the image) or
`src/main/docker/Dockerfile.native` / `Dockerfile.native-micro` (package a host-built
`target/*-runner`). Both images serve HTTP on `0.0.0.0:8080`; run them as
`docker run --rm -p 127.0.0.1:8080:8080 cncf-tech-advisor-mcp` and put a network boundary
in front — the server has no authentication of its own.

## Configuration

All in `src/main/resources/application.properties`; override with `-D` or environment.

| Key | Default | Meaning |
|---|---|---|
| `cncf.landscape.base-url` | `https://landscape.cncf.io` | Site root; `/data/full.json` is fixed in code. |
| `cncf.landscape.request-timeout` | `PT60S` | Deadline for the whole download, body included. |
| `cncf.landscape.max-bytes` | `16777216` | Largest body accepted, compressed or inflated. |
| `cncf.landscape.cache-ttl` (`CNCF_CACHE_TTL`) | `PT1H` | How long a loaded catalogue is served without asking upstream. |
| `cncf.landscape.failure-backoff` | `PT30S` | No retry after a failed download for this long. |
| `cncf.landscape.min-force-interval` | `PT30S` | `refresh_cncf_data` is refused inside this window. |
| `mcp.rate-limit.calls-per-minute` (`MCP_RATE_LIMIT`) | `120` | Per caller (remote address); `0` disables. |
| `quarkus.http.host` | `127.0.0.1` | Loopback by default; the images set `0.0.0.0`. |
| `quarkus.http.cors.origins` (`MCP_ALLOWED_ORIGINS`) | `http://localhost:6274` | Exact origins, never `*`. |

The download is conditional (`If-None-Match`; the landscape's CDN answers 304) and
gzip-encoded (≈0.85 MB instead of 3.8 MB). A sequence of tool calls costs one download per
`cache-ttl`; see `SECURITY-PENTEST.md` for the measurements.

## Develop

```bash
./mvnw clean verify          # 169 tests; a WireMock stands in for the landscape, nothing reaches cncf.io
./mvnw quarkus:dev           # HTTP on, INFO logs
make help
```

Logging is INFO on **stderr**, one audit line per tool call
(`tool=search_cncf source=127.0.0.1 outcome=ok ms=12 arg="kube"`); stdout carries only the
protocol on stdio.

Documents: `CLAUDE.md` (architecture, conventions, rules), `SECURITY-PENTEST.md` (threat
model, attack catalogue with reproducible commands, residual risks), `CHANGELOG.md`.

## Legacy files

`npm/`, `bin/`, `test/*.js`, `test-mcp*.js`, `docker/`, `install-claude-desktop.*`,
`scripts/{build-npm,cross-build,deploy}.sh`, `claude-*-config.json` and `package.json`
belong to an earlier npm-wrapper distribution that is not built, tested or published by
this repository today. They are kept for reference only.

## License

Apache-2.0 — see `LICENSE`.

# CLAUDE.md

Guidance for Claude Code when working in this repository. Keep it true: every claim here
was verified on 2026-09-20 (see `SECURITY-PENTEST.md` for the evidence).

## What this is

A Model Context Protocol server, in Java 25 / Quarkus 3.33 LTS (`quarkus-mcp-server`
2.0.1, MCP specification 2026-07-28), that serves the public CNCF Landscape
(`https://landscape.cncf.io/data/full.json`, 2,426 items) to a language model through four
read-only tools. No authentication, no credentials, no database: one in-memory catalogue
refreshed at most once per `cache-ttl`.

Sibling project and reference standard: `../mcp-redhat-kb` (same Quarkus line, same
sanitizer/fence design, same test tooling). When in doubt about a convention, look there.

## Architecture

```
src/main/java/io/mcp/cncf/
  client/   LandscapeHttp (java.net.http + BoundedExchange: whole-exchange deadline,
            byte cap while streaming, bounded gunzip, ETag/If-None-Match), LandscapeConfig
            (@ConfigMapping cncf.landscape.*), LandscapeSource (interface the tests fake)
  service/  CncfDataRefreshService: the cache. TTL, single-flight lock, failure backoff,
            forced-refresh interval, parse of full.json (current and legacy layout)
  model/    CncfModel records (CncfProject, ProjectMetadata, SearchQuery, SearchResult)
  tool/     CncfTool (the 4 @Tool methods; every call goes through guarded()),
            CncfFormatter (renders inside UntrustedFence, both channels from one sanitized
            record), ContentSanitizer, UntrustedFence, RateLimiter, ToolAuditLog, ToolErrors
  tool/model/  the @OutputSchema records (must carry @RegisterForReflection)
  config/   SearchConstants (caps and limits)
src/main/resources/application.properties   the only configuration file
src/main/docker/Dockerfile.native*          native images (JVM image: ./Dockerfile)
scripts/mcp-native-parity.sh                 diff tools/list AND two tools/call between JVM and native
```

There is no MicroProfile REST client and no JAX-RS endpoint any more; do not add
`quarkus-rest*` back without a reason. Do not introduce Caffeine for the rate limiter: it
picks its cache node class by name at runtime and the native image fails every tool call
with `ClassNotFoundException` while `tools/list` looks perfect (`quarkus-cache` registers
only the node classes its own configured caches need; the pentest hit both variants).
`RateLimiter` uses a capped `ConcurrentHashMap` instead. The `npm/`, `bin/`, `test/*.js`, `docker/`,
`install-claude-desktop.*` and `scripts/{build-npm,cross-build,deploy}.sh` files predate the
homologation and are not maintained by the build; treat them as legacy.

## Tools

| tool | annotations | note |
|---|---|---|
| `search_cncf(query?, category?, limit?)` | read-only, idempotent | keyword ≤200 chars, ≥2; limit clamped 1..100 |
| `get_cncf_project(projectName)` | read-only, idempotent | exact name, case-insensitive, ≤120 chars |
| `list_cncf_categories()` | read-only, idempotent | |
| `refresh_cncf_data()` | not read-only, idempotent | `updated` / `unchanged` / `throttled`; conditional GET |

Every tool: rate limiter first (`mcp.rate-limit.calls-per-minute`, per remote address, 120,
`0` disables), audit line last (`io.mcp.cncf.audit`), any escaping exception → one fixed
sentence, detail in the log. Every upstream value is sanitized and rendered inside a fence
carrying a per-response nonce; the server's own text stays outside it.

## Commands

```bash
./mvnw clean verify                      # 169 tests; WireMock stands in for the landscape
./mvnw package -DskipTests               # target/quarkus-app/quarkus-run.jar (fast-jar)
./mvnw package -Pnative -DskipTests      # + target/*-runner (needs GraalVM/Mandrel 25)
scripts/mcp-native-parity.sh             # tools/list and tools/call identical on JVM and native
make run-stdio | make run | make native | make docker
./mvnw versions:display-dependency-updates versions:display-plugin-updates
```

Run on stdio (shipped default; HTTP off): `java -jar target/quarkus-app/quarkus-run.jar`.
Run on HTTP: add `-Dquarkus.http.host-enabled=true -Dquarkus.mcp.server.stdio.enabled=false`;
the endpoint is `http://127.0.0.1:8080/mcp` (loopback by default; `QUARKUS_HTTP_HOST=0.0.0.0`
publishes it — the container images do that).

There is no `--port` flag, no `sse` profile activation, no Spotless, no JaCoCo, no
`*-runner.jar`. Logging is INFO on stderr (`quarkus.log.console.stderr=true`); stdout is the
protocol on stdio and stays clean.

## Configuration (all in application.properties, override with -D or env)

```
cncf.landscape.base-url=https://landscape.cncf.io   # /data/full.json is fixed in code
cncf.landscape.connect-timeout=PT15S
cncf.landscape.request-timeout=PT60S                 # whole exchange, body included
cncf.landscape.max-bytes=16777216                     # compressed and inflated
cncf.landscape.cache-ttl=PT1H          (CNCF_CACHE_TTL)
cncf.landscape.failure-backoff=PT30S
cncf.landscape.min-force-interval=PT30S
mcp.rate-limit.calls-per-minute=120    (MCP_RATE_LIMIT)
quarkus.http.cors.origins=...          (MCP_ALLOWED_ORIGINS, default http://localhost:6274, never *)
```

## Testing conventions

- `LandscapeStubProfile`: WireMock at the site root, TTL/backoff/force-interval `0`, rate
  limit `0`, so every call reaches the stub. `CachingLandscapeProfile`: shipped defaults,
  limit 6 — for the cache and limiter over the wire (`CncfCachingProtocolTest`).
- `CncfDataRefreshServiceCacheTest` is pure (fake clock, counting source): each test states
  how many downloads a sequence may cost.
- Protocol tests run once per `Era` (stateful 2025-06-18 session and stateless 2026-07-28).
- `%test.cncf.landscape.base-url=http://127.0.0.1:1`: a test that forgets the profile fails
  on connection instead of passing on live data. No test may reach landscape.cncf.io.
- Adding an `@OutputSchema` record: annotate it `@RegisterForReflection` (nested records
  too) or the native image publishes `{"type":"object"}` silently; the parity script and
  `publishesNonEmptyOutputSchemas` catch it.

## Rules

- Anything from upstream is rendered through `CncfFormatter` only. No new tool may return
  a raw upstream string, in either channel.
- Messages that reach the model are this server's own sentences. Never concatenate
  `e.getMessage()` into a `ToolResponse`.
- Quarkus stays on the 3.33 LTS line; `quarkus-mcp-server` on 2.0.x; jsonschema-generator
  on the line the extension is built against (check its parent pom before bumping).
- Commits in English, conventional style, no Co-Authored-By trailers.

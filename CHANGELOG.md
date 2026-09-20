# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Security (audit and pentest, 2026-09-20 — see SECURITY-PENTEST.md)
- **Critical**: the read tools downloaded `full.json` (3.8 MB) on every call. The catalogue is
  now cached for `cncf.landscape.cache-ttl` (1 h), a download is shared by concurrent callers,
  a failed download backs off, requests are conditional (ETag / 304) and gzip-encoded.
- **High**: `refresh_cncf_data` re-downloaded on every call; refused (`throttled`) inside
  `cncf.landscape.min-force-interval` (30 s).
- **High**: no bound on the size or wall-clock of the download; `BoundedExchange` (ported from
  mcp-redhat-kb) caps both, gzip is inflated to the same cap. The MicroProfile REST client and
  `quarkus-rest*` are gone from the classpath.
- **Medium**: exception messages were relayed to the model; every tool error is now a fixed
  sentence, the detail goes to the log.
- Sanitizer: NFKC normalisation, the whole `\p{Cf}` category plus fillers and variation
  selectors stripped, fixed-point iteration (idempotent on double-encoded input).
- `safeUrl`: no percent-escapes, `@`, ports, IP literals, `localhost`, dot segments.
- Argument length bounds (`maxLength` in the schema) enforced.
- Per-address rate limiter (120/min, `0` disables) and one audit log line per call; log level
  INFO on stderr (stdout stays protocol-only on stdio).
- `scripts/mcp-native-parity.sh` now calls two tools on both binaries against a local stub:
  a native image whose `tools/list` is perfect can still fail every call (Caffeine's cache
  node classes, unregistered for reflection, with the bare artifact and with `quarkus-cache`
  alike — caught here; the limiter uses a capped `ConcurrentHashMap`, no Caffeine).

### Changed
- `quarkus-mcp-server` 2.0.0 → 2.0.1. GitHub Actions pinned by commit SHA and moved to the
  versions mcp-redhat-kb uses; the Java 17/21 test matrix (which could not compile a
  `release=25` project) removed; `release.yml` was not valid YAML and is now.
- `refresh_cncf_data` output schema: `outcome`, `projectCount`, `lastRefresh`, `cacheExpiresAt`
  (was `updated`, `projectCount`, `lastRefresh`, `dataFresh`).
- README, CLAUDE.md and Makefile now describe what is built (four tools, fast-jar layout, no
  npm wrapper, no `--port` flag).

### Removed
- `ErrorHandler`, `CncfLandscapeClient` (REST client interface), unused model helpers.

## [1.0.0] — 2025-12-16 (initial release, as originally described)

### Added
- Initial release of CNCF Tech Advisor MCP Server
- Technology recommendations based on use cases and requirements
- CNCF project search and discovery
- Technology comparison with multiple criteria
- Trend analysis for cloud-native technologies
- Adoption patterns and best practices
- Interactive prompts for common scenarios

### Features

#### Tools
- `searchProjects` - Search CNCF projects by keyword, category, or maturity
- `getProjectDetails` - Get detailed information about specific CNCF projects
- `recommendTechnologies` - Get personalized technology recommendations
- `compareTechnologies` - Compare technologies based on criteria
- `analyzeTrends` - Analyze technology trends in specific categories
- `getAdoptionPatterns` - Get common adoption patterns and best practices

#### Prompts
- `cncf-tech-stack-planning` - Plan complete cloud-native technology stacks
- `cncf-technology-comparison` - Compare different CNCF technologies
- `cncf-adoption-journey` - Plan step-by-step adoption of CNCF technologies
- `cncf-trend-analysis` - Analyze technology trends for strategic planning

#### Supported Categories
- Orchestration (Kubernetes, Docker)
- Runtime (Container runtimes, serverless)
- Provisioning (IaC, cloud provisioning)
- Observability (Monitoring, logging, tracing)
- Service Mesh (Istio, Linkerd)
- Networking (CNI, load balancers)
- Security (Auth, secrets, policies)
- Storage (Persistent storage, databases)
- Streaming (Message queues, event streaming)
- CI/CD (GitOps, pipelines)

### Technical
- Built with Quarkus 3.30.2
- Java 25 with records for immutable models
- Reactive programming with Mutiny Uni
- Type-safe REST clients for CNCF APIs
- Comprehensive caching for performance
- Native image compilation support
- Multi-format distribution (Docker, npm, JAR)

## [1.0.0] - 2024-12-14

### Added
- First stable release
- Complete MCP server implementation
- Docker and npm distribution
- Comprehensive documentation
- GitHub Actions CI/CD pipeline
- Maven multi-stage build for native images

### Documentation
- Complete README with usage examples
- API documentation for all tools and prompts
- Development setup guide
- Docker deployment instructions
- Claude Desktop integration guide
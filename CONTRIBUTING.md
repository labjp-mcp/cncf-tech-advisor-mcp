# Contributing

Thanks for helping. `CLAUDE.md` is the source of truth for the architecture, the
conventions and the rules a change has to respect (sanitized rendering of upstream data,
fixed error sentences, Quarkus line); read it before touching the code.

## Reporting bugs and suggesting features

Open a GitHub issue. For a bug, include the steps to reproduce, what you expected, what
happened, your OS and Java version, the MCP client you used and the server version.

## Development

Prerequisites: Java 25 (a JDK is enough for the JVM build; GraalVM or Mandrel for Java 25
for the native one) and Git. Maven comes with the wrapper (`./mvnw`).

```bash
git clone https://github.com/labjp-mcp/cncf-tech-advisor-mcp.git
cd cncf-tech-advisor-mcp

./mvnw clean verify                       # full test suite; WireMock stands in for the landscape
./mvnw test -Dtest=CncfToolTest           # one test class
./mvnw quarkus:dev                        # dev mode, HTTP on
./mvnw package -DskipTests                # target/quarkus-app/quarkus-run.jar
./mvnw package -Pnative -DskipTests       # native executable target/*-runner
scripts/mcp-native-parity.sh              # JVM and native publish the same tools and answers
```

No test may reach `landscape.cncf.io`; use `LandscapeStubProfile` (see `CLAUDE.md`,
"Testing conventions"). A change to a tool, its output schema or the native configuration
should pass the parity script as well as `./mvnw clean verify`.

## Pull requests

1. Fork the repository and branch from `main` (`feature/...`, `bugfix/...`).
2. Keep each pull request to one change, with tests for new behaviour.
3. Run `./mvnw clean verify` and make sure it passes.
4. Update `README.md`, `CLAUDE.md` or `CHANGELOG.md` when the change affects what they say.
5. Open the pull request against `main`; CI (`ci.yml`, `build.yml`) must be green.

## Commit messages

English, [Conventional Commits](https://www.conventionalcommits.org/):
`feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `ci:`, `chore:`. One logical change per
commit, imperative mood, e.g. `fix: clamp search limit before querying the catalogue`.

## Releases

Pushing a tag `vX.Y.Z` runs `.github/workflows/release.yml`: it tests, builds the native
executables, creates the GitHub Release (uber-jar and native binaries) and publishes the
container image `ghcr.io/labjp-mcp/cncf-tech-advisor-mcp`.

## License

By contributing you agree that your contributions are licensed under Apache-2.0
(see `LICENSE`).

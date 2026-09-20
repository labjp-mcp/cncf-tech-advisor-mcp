.PHONY: help build test clean native native-container native-parity docker docker-run docker-native run dev package install format lint

# Default target
help: ## Show this help message
	@echo 'CNCF Tech Advisor MCP Server'
	@echo ''
	@echo 'Available targets:'
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "  %-15s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# Development targets
dev: ## Run in development mode
	./mvnw quarkus:dev

run: ## Run the built JAR
	java -jar target/cncf-tech-advisor-mcp-1.0.0-runner.jar

run-stdio: ## Run with stdio transport enabled
	java -jar target/cncf-tech-advisor-mcp-1.0.0-runner.jar -Dquarkus.mcp.server.stdio.enabled=true

# Build targets
build: ## Build the project (creates JAR)
	./mvnw clean package -DskipTests

package: build ## Alias for build

native: ## Build native executable (also leaves the JVM package in target/quarkus-app)
	./mvnw clean package -Pnative -DskipTests

native-container: ## Build native executable inside the Mandrel builder image (no local GraalVM needed; Linux binary)
	./mvnw clean package -Pnative -DskipTests -Dquarkus.native.container-build=true

native-parity: ## Diff tools/list between the JVM package and the native executable
	scripts/mcp-native-parity.sh

test: ## Run all tests
	./mvnw test

test-coverage: ## Run tests with coverage report
	./mvnw verify jacoco:report

# Docker targets
docker: ## Build Docker image
	docker build -t cncf-tech-advisor-mcp:latest .

docker-run: ## Run Docker container
	docker run -i --rm -p 127.0.0.1:8080:8080 cncf-tech-advisor-mcp:latest

docker-native: ## Build the native images (needs a Linux target/*-runner: make native-container first)
	docker build -f src/main/docker/Dockerfile.native -t cncf-tech-advisor-mcp:native .
	docker build -f src/main/docker/Dockerfile.native-micro -t cncf-tech-advisor-mcp:native-micro .

docker-push: docker ## Push Docker image to registry
	docker tag cncf-tech-advisor-mcp:latest ghcr.io/jeanlopezxyz/cncf-tech-advisor-mcp:latest
	docker push ghcr.io/jeanlopezxyz/cncf-tech-advisor-mcp:latest

# Clean targets
clean: ## Clean build artifacts
	./mvnw clean

clean-all: clean ## Clean all generated files including Docker
	docker system prune -f

# Installation targets
install: build ## Install to local Maven repository
	./mvnw install

install-native: native ## Install native binary
	sudo cp target/*-runner /usr/local/bin/cncf-tech-advisor-mcp

# Code quality
format: ## Format Java code
	./mvnw spotless:apply

lint: ## Check code style
	./mvnw spotless:check

# Quick start
quick-start: ## Quick start for development (build + run)
	$(MAKE) build
	$(MAKE) run

# Release targets
release: clean test build ## Prepare for release (clean, test, build)
	@echo "Ready for release. Create a tag with: git tag v1.0.0"

# Utility targets
version: ## Show project version
	@./mvnw help:evaluate -q -DforceStdout -Dexpression=project.version

tree: ## Show project dependency tree
	./mvnw dependency:tree

info: ## Show project information
	@echo "Project: CNCF Tech Advisor MCP Server"
	@echo "Version: $(shell ./mvnw help:evaluate -q -DforceStdout -Dexpression=project.version)"
	@echo "Java: $(shell java -version 2>&1 | head -1)"
	@echo "Maven: $(shell ./mvnw -version | head -1)"
	@echo "Docker: $(shell docker --version 2>&1 || echo 'Not installed')"
# Codex Cloud / Remote Containers

Integration tests rely on Testcontainers, so they need access to a Docker daemon. The `cloud` profile uses Docker-in-Docker to make that work in Codex Cloud or any environment without a local Docker socket.

## Quick start

```bash
./scripts/run-tests-cloud.sh
# or to run a subset:
./scripts/run-tests-cloud.sh mvn -B -pl erp-domain -am -Dtest="*SmokeTest" test
```

The script pulls the required images, starts a Docker daemon (`docker:dind`), runs Maven tests inside `maven:3.9.9-eclipse-temurin-21`, and tears everything down (including volumes) when done.

## Manual commands (if you need them)

```bash
# Start the daemon
docker compose -f cloud/docker-compose.testcontainers.yml up -d dind

# Wait for Docker to be ready
docker compose -f cloud/docker-compose.testcontainers.yml exec dind sh -c "until docker info >/dev/null 2>&1; do sleep 1; done"

# Run tests (override the command as needed)
docker compose -f cloud/docker-compose.testcontainers.yml run --rm test-runner mvn -B -pl erp-domain -am test

# Clean up containers and volumes
docker compose -f cloud/docker-compose.testcontainers.yml down -v
```

## How it works

- `cloud/docker-compose.testcontainers.yml` defines a privileged `docker:dind` daemon plus a `test-runner` container with Java 21/Maven 3.9.9.
- Environment is pre-set for Testcontainers to talk to the daemon: `DOCKER_HOST=tcp://dind:2375`, TLS disabled, and host override for nested networking.
- Ryuk and the Testcontainers environment checks are disabled for compatibility with the DinD service; teardown via `docker compose down -v` removes everything after the run.
- Maven dependencies are cached in a named volume (`maven-repo`) so repeat runs are faster.

Use this workflow when you want full Testcontainers coverage in Codex Cloud or any remote CI/ephemeral environment without changing the application code.

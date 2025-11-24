#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/cloud/docker-compose.testcontainers.yml"
DEFAULT_CMD=("mvn" "-B" "-pl" "erp-domain" "-am" "test")

cleanup() {
  docker compose -f "$COMPOSE_FILE" down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ "$#" -eq 0 ]; then
  CMD=("${DEFAULT_CMD[@]}")
else
  CMD=("$@")
fi

echo "Pulling cloud test runner images..."
docker compose -f "$COMPOSE_FILE" pull dind test-runner

echo "Starting ephemeral Docker daemon for Testcontainers..."
docker compose -f "$COMPOSE_FILE" up -d dind

echo "Waiting for Docker daemon to be ready..."
docker compose -f "$COMPOSE_FILE" exec dind sh -c "until docker info >/dev/null 2>&1; do sleep 1; done"

echo "Running in cloud test runner: ${CMD[*]}"
docker compose -f "$COMPOSE_FILE" run --rm test-runner "${CMD[@]}"

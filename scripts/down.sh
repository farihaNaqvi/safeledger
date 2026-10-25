#!/usr/bin/env bash
# Equivalent to `make down`, for environments without `make` installed.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
docker compose -f infra/docker-compose.yml down

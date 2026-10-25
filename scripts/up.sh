#!/usr/bin/env bash
# Equivalent to `make up`, for environments without `make` installed.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
docker compose -f infra/docker-compose.yml up --build -d

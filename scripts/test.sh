#!/usr/bin/env bash
# Equivalent to `make test`, for environments without `make` installed.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
mvn verify

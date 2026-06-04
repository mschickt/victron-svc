#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# Skip BuildKit SBOM/provenance attestations — they add ~30-40s of export
# overhead per build on the Pi and we don't publish this image.
export BUILDX_NO_DEFAULT_ATTESTATIONS=1

exec docker compose up -d --build "$@"

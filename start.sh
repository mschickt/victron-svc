#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# Skip BuildKit SBOM/provenance attestations — they add ~30-40s of export
# overhead per build on the Pi and we don't publish this image.
export BUILDX_NO_DEFAULT_ATTESTATIONS=1

# Capture the host git commit and pass it into the image build (the .git dir is
# not in the Docker build context). Surfaced at /q/info and on the dashboard.
export GIT_COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)$(git diff --quiet 2>/dev/null || echo -dirty)"
export GIT_BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
export GIT_TIME="$(git show -s --format=%cI HEAD 2>/dev/null || echo '')"

exec docker compose up -d --build "$@"

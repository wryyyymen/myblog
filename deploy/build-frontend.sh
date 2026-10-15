#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

docker run --rm \
  --network host \
  --memory 900m \
  --memory-swap 2g \
  --env NODE_OPTIONS=--max-old-space-size=512 \
  --volume "$repo_root/frontend:/workspace" \
  --workdir /workspace \
  public.ecr.aws/docker/library/node:22-bookworm-slim \
  sh -c 'npm ci --no-audit --no-fund && npm run type-check && npm run build-only'

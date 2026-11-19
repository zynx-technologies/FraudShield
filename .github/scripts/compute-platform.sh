#!/usr/bin/env bash
# Prints an environment's compute platform, "aks" or "container_apps", from the platform values the last
# Terraform apply published to the releases branch. Prints "aks" when nothing has been published yet.
# Usage: compute-platform.sh <environment>   (inside a checkout of this repository)
set -euo pipefail

ENVIRONMENT="$1"
values=""
if git fetch --quiet --depth 1 origin releases 2>/dev/null; then
  values="$(git show "FETCH_HEAD:environments/${ENVIRONMENT}/platform-primary.yaml" 2>/dev/null || true)"
fi
platform=""
if [[ -n "$values" ]]; then
  platform="$(yq -r '.global.computePlatform // ""' <<< "$values")"
fi
echo "${platform:-aks}"

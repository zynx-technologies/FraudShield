#!/usr/bin/env bash
# After a Terraform apply, writes each cluster's platform values (endpoints, identities, ingress IP) to the
# releases branch, and prints the AKS_CLUSTERS value for the environment's GitHub environment variables.
# Usage: publish-platform-values.sh <environment>   (run from infra/terraform after apply)
set -euo pipefail

ENVIRONMENT="$1"
SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=ci.sh
. "$SCRIPTS/ci.sh"
VALUES="$(mktemp -d)"
trap 'rm -rf "$VALUES"' EXIT

terraform output -json platform_values > "$VALUES/all.json"

for region in $(jq -r 'keys[]' "$VALUES/all.json"); do
  jq ".${region}" "$VALUES/all.json" > "$VALUES/${region}.json"
  "$SCRIPTS/commit-release.sh" "environments/${ENVIRONMENT}/platform-${region}.yaml" \
    ". = load(\"$VALUES/${region}.json\")" \
    "Platform values for ${ENVIRONMENT} ${region} from $(ci_run_label)"
done

CLUSTERS="$(terraform output -raw aks_clusters)"
echo "AKS clusters: $CLUSTERS"
if [[ "${AKS_CLUSTERS:-}" != "$CLUSTERS" ]]; then
  ci_warning "Set AKS_CLUSTERS to \"$CLUSTERS\" for fraudshield-${ENVIRONMENT} (GitHub environment variable)."
fi
#!/usr/bin/env bash
# Stops or starts an environment's compute out of hours. Data services, API Management, and networking keep
# running (none of them can be stopped), so nothing is lost and a start takes minutes.
#
#   container_apps  stops or starts every Container App. With case and strategy stopped, the serverless SQL
#                   database auto-pauses too.
#   aks             stops or starts every AKS cluster (nodes are not billed while stopped). The hub firewall
#                   keeps running.
#
# A release or Terraform apply while the environment is stopped still updates the apps; the new revisions
# start with the next start.
#
# Usage: environment-power.sh <start|stop> <environment> [timeout-minutes]
# Needs: az (signed in), git, yq, jq, inside a checkout of this repository. AKS_CLUSTERS for aks.
set -euo pipefail

ACTION="$1"
ENVIRONMENT="$2"
TIMEOUT_MINUTES="${3:-20}"
SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=ci.sh
. "$SCRIPTS/ci.sh"

case "$ACTION" in
  start) WANT=Running ;;
  stop) WANT=Stopped ;;
  *)
    ci_error "Unknown action $ACTION (start or stop)."
    exit 1
    ;;
esac

PLATFORM="$("$SCRIPTS/compute-platform.sh" "$ENVIRONMENT")"
echo "$ENVIRONMENT runs on $PLATFORM: ${ACTION}ping its compute."

if [[ "$PLATFORM" == aks ]]; then
  : "${AKS_CLUSTERS:?Set AKS_CLUSTERS for the environment (the infra pipeline prints it)}"
  for pair in $AKS_CLUSTERS; do
    echo "az aks $ACTION ${pair#*/}"
    az aks "$ACTION" --resource-group "${pair%%/*}" --name "${pair#*/}" --only-show-errors --output none
  done
  exit 0
fi

# ---------- Container Apps ----------

git fetch --quiet --depth 1 origin releases
RESOURCE_GROUP="$(git show "FETCH_HEAD:environments/${ENVIRONMENT}/platform-primary.yaml" | yq -r '.global.resourceGroup')"
mapfile -t APP_IDS < <(az containerapp list --resource-group "$RESOURCE_GROUP" --only-show-errors --query '[].id' --output tsv)
if ((${#APP_IDS[@]} == 0)); then
  ci_warning "No Container Apps in $RESOURCE_GROUP."
  exit 0
fi

# Start and stop are long-running management actions. Send them all, then wait.
for id in "${APP_IDS[@]}"; do
  echo "$ACTION ${id##*/}"
  az rest --method post --url "https://management.azure.com${id}/${ACTION}?api-version=2024-03-01" \
    --only-show-errors --output none
done

deadline=$((SECONDS + TIMEOUT_MINUTES * 60))
pending=("${APP_IDS[@]}")
while ((${#pending[@]} > 0)); do
  still=()
  for id in "${pending[@]}"; do
    status="$(az resource show --ids "$id" --only-show-errors --query properties.runningStatus --output tsv)"
    if [[ "$status" == "$WANT" ]]; then
      echo "$WANT ${id##*/}"
    else
      echo "waiting ${id##*/} ($status)"
      still+=("$id")
    fi
  done
  pending=("${still[@]+"${still[@]}"}")
  ((${#pending[@]} == 0)) && break
  if ((SECONDS > deadline)); then
    ci_error "Timed out after $TIMEOUT_MINUTES minutes waiting for: ${pending[*]##*/}"
    exit 1
  fi
  sleep 15
done

summary="$(mktemp)"
printf '## %s: %s\n\nEvery Container App in `%s` is %s.\n' "$ENVIRONMENT" "$ACTION" "$RESOURCE_GROUP" "$WANT" > "$summary"
ci_summary "$summary"

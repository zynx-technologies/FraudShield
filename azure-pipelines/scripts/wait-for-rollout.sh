#!/usr/bin/env bash
# Waits until, in every cluster of the environment, Argo CD has synced, every FraudShield Rollout is
# Healthy on its latest generation, and the expected change is live. Fails fast if a canary is aborted,
# which means Argo Rollouts has already rolled back to the stable version.
#
# Usage: wait-for-rollout.sh image <tag> <timeout-minutes>
#        wait-for-rollout.sh config <KEY=value> <timeout-minutes> <rollout,rollout>
#   image   every Rollout's pods run an image with this tag
#   config  the listed Rollouts' ConfigMaps hold KEY=value (for example a model version)
# AKS_CLUSTERS: space-separated "resource-group/cluster" pairs, from the environment variable group.
set -euo pipefail

MODE="$1"
VALUE="$2"
TIMEOUT_MINUTES="${3:-30}"
ONLY="${4:-}"
: "${AKS_CLUSTERS:?Set AKS_CLUSTERS for the environment (the infra pipeline prints it)}"
# shellcheck source=ci.sh
. "$(dirname "$0")/ci.sh"

COMMAND='kubectl get applications.argoproj.io -n argocd -o json; echo "@@@"; kubectl get rollouts.argoproj.io,configmaps -n fraudshield -o json'

# Prints one line per item: "<ok|wait|fail> <description>".
evaluate() {
  local apps="$1" items="$2"
  jq -r '.items[] | select(.metadata.name | startswith("fraudshield-"))
    | if .status.sync.status == "Synced" and .status.health.status == "Healthy"
      then "ok app \(.metadata.name)" else "wait app \(.metadata.name) \(.status.sync.status)/\(.status.health.status)" end' <<< "$apps"

  jq -r --arg mode "$MODE" --arg value "$VALUE" --arg only "$ONLY" '
    (.items | map(select(.kind == "ConfigMap")) | map({(.metadata.name): .data}) | add // {}) as $cm
    | .items[] | select(.kind == "Rollout")
    | .metadata.name as $n
    | (if $mode == "image" then ([.spec.template.spec.containers[].image | endswith(":" + $value)] | all)
       elif ($only | split(",") | index($n)) == null then true
       else ($value | split("=")) as $kv | (($cm[$n] // {})[$kv[0]] == $kv[1]) end) as $live
    | if .status.phase == "Degraded" then "fail rollout \($n) aborted: \(.status.message // "canary analysis failed")"
      elif $live and .status.phase == "Healthy" and ((.status.observedGeneration | tostring) == (.metadata.generation | tostring))
      then "ok rollout \($n)"
      else "wait rollout \($n) phase=\(.status.phase) live=\($live)" end' <<< "$items"
}

deadline=$(( $(date +%s) + TIMEOUT_MINUTES * 60 ))
for cluster in $AKS_CLUSTERS; do
  rg="${cluster%%/*}"; name="${cluster##*/}"
  echo "Waiting for $MODE $VALUE on $name"
  while true; do
    if output="$(az aks command invoke --resource-group "$rg" --name "$name" --command "$COMMAND" --query logs -o tsv)"; then
      results="$(evaluate "${output%%@@@*}" "${output#*@@@}")"
      echo "$results" | grep -v '^ok ' | sed 's/^/  /' || true
      if grep -q '^fail ' <<< "$results"; then
        ci_error "On $name: $(grep '^fail ' <<< "$results" | head -1 | cut -d' ' -f2-)"
        exit 1
      fi
      if grep -q '^ok rollout' <<< "$results" && ! grep -q '^wait ' <<< "$results"; then
        echo "$name is healthy and running $VALUE."
        break
      fi
    else
      echo "  Could not reach $name. Retrying."
    fi
    if (( $(date +%s) > deadline )); then
      ci_error "Timed out after $TIMEOUT_MINUTES minutes waiting for $name."
      exit 1
    fi
    sleep 30
  done
done
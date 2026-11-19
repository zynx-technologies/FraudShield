#!/usr/bin/env bash
# Argo CD's job for an environment on Container Apps (compute_platform = "container_apps", dev): renders the
# release state on the releases branch onto every app, then waits until each app's latest revision is ready.
#
#   environments/<env>/platform-primary.yaml   endpoints, identities, registry   (infra.yml)
#   environments/<env>/release.yaml            image tag                          (app.yml)
#   environments/<env>/model.yaml              model versions                     (model-release.yml)
#
# Apps run in single-revision mode: a new revision gets traffic only once it is ready, so a revision that
# never becomes healthy leaves the previous one serving, and this script fails. There is no canary.
#
# The environment variables mirror deploy/helm/fraudshield-service/templates/configmap.yaml. Change both
# together.
#
# Usage: deploy-container-apps.sh <environment> [timeout-minutes]
# Needs: az (signed in), git, yq, jq, inside a checkout of this repository.
set -euo pipefail

ENVIRONMENT="$1"
TIMEOUT_MINUTES="${2:-30}"
SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPTS/../.." && pwd)"
# shellcheck source=ci.sh
. "$SCRIPTS/ci.sh"

# Start order: decision is not ready until it has loaded the rules from strategy.
APPS=(strategy decision case copilot portal)

# ---------- Release state ----------

git -C "$ROOT" fetch --quiet --depth 1 origin releases
state() { git -C "$ROOT" show "FETCH_HEAD:environments/${ENVIRONMENT}/$1" 2>/dev/null || true; }
PLATFORM="$(state platform-primary.yaml)"
RELEASE="$(state release.yaml)"
MODEL="$(state model.yaml)"

if [[ -z "$PLATFORM" ]]; then
  ci_error "No platform values for $ENVIRONMENT on the releases branch. Run Infrastructure first."
  exit 1
fi
global() { yq -r ".global.$1 // \"\"" <<< "$PLATFORM"; }
identity() { yq -r ".identities.$1 // \"\"" <<< "$PLATFORM"; }

if [[ "$(global computePlatform)" != container_apps ]]; then
  ci_error "$ENVIRONMENT does not run on Container Apps (computePlatform is '$(global computePlatform)')."
  exit 1
fi

TAG="$(yq -r '.global.imageTag // ""' <<< "$RELEASE")"
if [[ -z "$TAG" ]]; then
  echo "Nothing has been released to $ENVIRONMENT yet. Run App to deploy the services."
  exit 0
fi

RESOURCE_GROUP="$(global resourceGroup)"
REGISTRY="$(global registry)"
DOMAIN="$(global containerAppsDomain)"
TENANT_ID="$(global tenantId)"
API_CLIENT_ID="$(global apiClientId)"

# Chart defaults and per-service values from main, as Argo CD would layer them.
CHART="$ROOT/deploy/helm/fraudshield-service/values.yaml"
GITOPS="$ROOT/deploy/gitops"
JAVA_OPTS="$(yq -r '.javaOpts' "$CHART")"
MODEL_NAME="$(yq -r '.model.name // "fs-txn"' "$GITOPS/services/decision.yaml")"
MODEL_VERSION="$(yq -r '.model.version // ""' <<< "$MODEL")"
SHADOW_VERSION="$(yq -r '.model.shadowVersion // ""' <<< "$MODEL")"
MODEL_STORAGE_URL="$(global modelStorageUrl)"

# portal.<key>: the environment's file wins over the service file.
portal_value() {
  local value="" env_file="$GITOPS/environments/$ENVIRONMENT/portal.yaml"
  [[ -f "$env_file" ]] && value="$(yq -r ".portal.$1 // \"\"" "$env_file")"
  [[ -n "$value" ]] || value="$(yq -r ".portal.$1 // \"\"" "$GITOPS/services/portal.yaml")"
  echo "$value"
}

# ---------- Environment variables per app (see configmap.yaml) ----------

ENV_VARS=()
render_env() {
  local app="$1"
  ENV_VARS=(
    "FRAUDSHIELD_ENVIRONMENT=$ENVIRONMENT"
    "FRAUDSHIELD_REGION=$(global region)"
  )

  if [[ "$app" == portal ]]; then
    # Read by nginx to serve /config.json to the portal.
    ENV_VARS+=(
      "ENTRA_TENANT_ID=$TENANT_ID"
      "ENTRA_SPA_CLIENT_ID=$(global spaClientId)"
      "ENTRA_API_SCOPE=api://$API_CLIENT_ID/access_as_user"
      "FRAUDSHIELD_DATA_LABEL=$(portal_value dataLabel)"
      "FRAUDSHIELD_REPORTS_URL=$(portal_value reportsUrl)"
    )
    return
  fi

  ENV_VARS+=(
    "JAVA_TOOL_OPTIONS=$JAVA_OPTS"
    "SERVER_PORT=8080"
    "MANAGEMENT_SERVER_PORT=8081"
    "APPLICATIONINSIGHTS_CONNECTION_STRING=$(global appInsightsConnectionString)"
    "OTEL_SERVICE_NAME=$app"
    "ENTRA_ISSUER_URI=https://login.microsoftonline.com/$TENANT_ID/v2.0"
    "ENTRA_API_AUDIENCE=$API_CLIENT_ID"
    "FRAUDSHIELD_SERVICE_SCOPE=api://$API_CLIENT_ID/.default"
    # Service-to-service calls stay inside the environment.
    "DECISION_SERVICE_URL=https://decision.$DOMAIN"
    "CASE_SERVICE_URL=https://case.$DOMAIN"
    "STRATEGY_SERVICE_URL=https://strategy.$DOMAIN"
    "AZURE_KEYVAULT_URI=$(global keyVaultUri)"
    "FRAUDSHIELD_COSMOS_ENDPOINT=$(global cosmosEndpoint)"
    "FRAUDSHIELD_REDIS_HOST=$(global redisHost)"
    "FRAUDSHIELD_EVENTHUBS_NAMESPACE=$(global eventHubsNamespace)"
    "FRAUDSHIELD_SIGNALR_HOST=$(global signalrHost)"
    "AZURE_OPENAI_ENDPOINT=$(global aiEndpoint)"
    # Each app also holds the registry pull identity. This makes DefaultAzureCredential use the service's own.
    "AZURE_CLIENT_ID=$(identity "$app")"
  )

  if [[ "$app" == case || "$app" == strategy ]]; then
    ENV_VARS+=("DATABASE_URL=jdbc:sqlserver://$(global sqlHost):1433;database=fraudshield;encrypt=true;trustServerCertificate=false;hostNameInCertificate=*.database.windows.net;loginTimeout=30;authentication=ActiveDirectoryDefault")
  fi

  if [[ "$app" == decision ]]; then
    if [[ -n "$MODEL_VERSION" ]]; then
      ENV_VARS+=(
        "FRAUDSHIELD_SCORING_MODE=model"
        "FRAUDSHIELD_MODEL_NAME=$MODEL_NAME"
        "FRAUDSHIELD_MODEL_VERSION=$MODEL_VERSION"
        "FRAUDSHIELD_MODEL_URL=$MODEL_STORAGE_URL/$MODEL_NAME/$MODEL_VERSION/model.onnx"
      )
    else
      # No trained model released to this environment yet: the transparent baseline score is used.
      ENV_VARS+=("FRAUDSHIELD_SCORING_MODE=baseline")
    fi
    if [[ -n "$SHADOW_VERSION" ]]; then
      ENV_VARS+=(
        "FRAUDSHIELD_SHADOW_MODEL_VERSION=$SHADOW_VERSION"
        "FRAUDSHIELD_SHADOW_MODEL_URL=$MODEL_STORAGE_URL/$MODEL_NAME/$SHADOW_VERSION/model.onnx"
      )
    fi
  fi
}

# ---------- Deploy ----------

az config set extension.use_dynamic_install=yes_without_prompt --only-show-errors > /dev/null

for app in "${APPS[@]}"; do
  render_env "$app"
  image="$REGISTRY/fraudshield-$app:$TAG"
  echo "Updating $app to $image"
  # An unchanged image and configuration creates no new revision.
  az containerapp update --resource-group "$RESOURCE_GROUP" --name "$app" --image "$image" \
    --replace-env-vars "${ENV_VARS[@]}" --only-show-errors --output none
done

# ---------- Wait until every app's latest revision is ready ----------

deadline=$((SECONDS + TIMEOUT_MINUTES * 60))
pending=("${APPS[@]}")
while ((${#pending[@]} > 0)); do
  still=()
  for app in "${pending[@]}"; do
    show="$(az containerapp show --resource-group "$RESOURCE_GROUP" --name "$app" --only-show-errors \
      --query '{latest: properties.latestRevisionName, ready: properties.latestReadyRevisionName, status: properties.runningStatus}' --output json)"
    latest="$(jq -r '.latest' <<< "$show")"
    # Stopped out of hours (environment-power.sh): the new revision starts with the next start.
    if [[ "$(jq -r '.status' <<< "$show")" == Stopped ]]; then
      echo "stopped $app ($latest runs when $ENVIRONMENT is next started)"
      continue
    fi
    if [[ "$latest" == "$(jq -r '.ready' <<< "$show")" ]]; then
      echo "ready   $app ($latest)"
      continue
    fi
    revision="$(az containerapp revision show --resource-group "$RESOURCE_GROUP" --name "$app" --revision "$latest" \
      --only-show-errors --query '{running: properties.runningState, provisioning: properties.provisioningState, health: properties.healthState}' --output json)"
    if [[ "$(jq -r '.running' <<< "$revision")" == Failed || "$(jq -r '.provisioning' <<< "$revision")" == Failed ]]; then
      ci_error "$app revision $latest failed ($(jq -c . <<< "$revision")). The previous revision keeps serving. Logs: az containerapp logs show -g $RESOURCE_GROUP -n $app --revision $latest"
      exit 1
    fi
    echo "waiting $app ($latest: $(jq -r '"\(.running)/\(.health)"' <<< "$revision"))"
    still+=("$app")
  done
  pending=("${still[@]+"${still[@]}"}")
  ((${#pending[@]} == 0)) && break
  if ((SECONDS > deadline)); then
    ci_error "Timed out after $TIMEOUT_MINUTES minutes waiting for: ${pending[*]}. The previous revisions keep serving."
    exit 1
  fi
  sleep 20
done

summary="$(mktemp)"
{
  echo "## Container Apps: $ENVIRONMENT"
  echo
  echo "Image tag \`$TAG\`, model \`${MODEL_VERSION:-baseline}\`${SHADOW_VERSION:+, shadow \`$SHADOW_VERSION\`}. Every app is ready."
} > "$summary"
ci_summary "$summary"

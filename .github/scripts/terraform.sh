#!/usr/bin/env bash
# Terraform plan or apply for one environment, signed in with workload identity federation (no secrets).
# Set ARM_CLIENT_ID, ARM_TENANT_ID, and ARM_SUBSCRIPTION_ID, and give the job "id-token: write" permission.
# The provider requests the GitHub OIDC token itself.
# Usage: terraform.sh plan <environment>
#        terraform.sh apply <environment> <saved-plan-file>
set -euo pipefail

ACTION="$1"
ENVIRONMENT="$2"
PLAN_FILE="${3:-}"
SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=ci.sh
. "$SCRIPTS/ci.sh"

: "${ARM_CLIENT_ID:?}" "${ARM_TENANT_ID:?}" "${ARM_SUBSCRIPTION_ID:?}"
export ARM_USE_OIDC=true
export TF_IN_AUTOMATION=1
export TF_VAR_unique_prefix="${UNIQUE_PREFIX:?}"
export TF_VAR_acr_name="${ACR_NAME:?}"
export TF_VAR_acr_resource_group="${ACR_RESOURCE_GROUP:?}"

cd "$SCRIPTS/../../infra/terraform"
terraform init -input=false \
  -backend-config="resource_group_name=${TF_STATE_RESOURCE_GROUP:?}" \
  -backend-config="storage_account_name=${TF_STATE_STORAGE_ACCOUNT:?}" \
  -backend-config="container_name=tfstate" \
  -backend-config="key=fraudshield-${ENVIRONMENT}.tfstate" \
  -backend-config="use_azuread_auth=true"

case "$ACTION" in
  plan)
    set +e
    terraform plan -input=false -lock-timeout=10m -detailed-exitcode -out=tfplan -var-file="env/${ENVIRONMENT}.tfvars"
    code=$?
    set -e
    [[ $code -eq 1 ]] && exit 1
    changes=$([[ $code -eq 2 ]] && echo true || echo false)
    ci_set_output PLAN_HAS_CHANGES "$changes"
    {
      echo "## Terraform plan: ${ENVIRONMENT}"
      echo
      if [[ "$changes" == true ]]; then
        echo '```'
        terraform show -no-color tfplan | grep -E '^\s+# |^Plan:' || true
        echo '```'
      else
        echo "No changes."
      fi
    } > plan-summary.md
    ci_summary "$PWD/plan-summary.md"
    ;;
  apply)
    terraform apply -input=false -lock-timeout=10m "${PLAN_FILE:?}"
    "$SCRIPTS/publish-platform-values.sh" "$ENVIRONMENT"
    ;;
  *)
    echo "Unknown action $ACTION" >&2
    exit 1
    ;;
esac

#!/usr/bin/env bash
# One-time setup, run by an Azure administrator before the first pipeline run.
# Creates Terraform state storage and the shared container registry (Premium, replicated to the second
# region so both AKS clusters pull locally). Images are built once and promoted through every environment.
#
# Usage: ./infra/bootstrap.sh <prefix> [primary-location] [secondary-location]
#   prefix: 3 to 8 lowercase letters or digits, the same value as unique_prefix in Terraform.
#   ACR_SKU (environment, default Premium): Standard is enough while only dev and test exist. Premium is
#   needed for the replica in the second region, so upgrade before perf or prod:
#   az acr update --name <registry> --sku Premium, then rerun this script to add the replica.
set -euo pipefail

PREFIX="${1:?Give a short lowercase prefix, for example fsacme}"
LOCATION="${2:-uksouth}"
SECONDARY="${3:-ukwest}"
ACR_SKU="${ACR_SKU:-Premium}"

if [[ ! "$PREFIX" =~ ^[a-z][a-z0-9]{2,7}$ ]]; then
  echo "The prefix must be 3 to 8 lowercase letters or digits, starting with a letter." >&2
  exit 1
fi

RG="rg-fraudshield-shared"
STORAGE="${PREFIX}fsstate"
ACR="${PREFIX}fsacr"

az group create --name "$RG" --location "$LOCATION" --tags application=fraudshield managed_by=bootstrap --output none

# State holds resource IDs and some outputs, so it is private to Entra-authorised identities.
az storage account create --name "$STORAGE" --resource-group "$RG" --location "$LOCATION" \
  --sku Standard_ZRS --kind StorageV2 --min-tls-version TLS1_2 \
  --allow-blob-public-access false --allow-shared-key-access false --output none
az storage account blob-service-properties update --account-name "$STORAGE" --resource-group "$RG" \
  --enable-versioning true --enable-delete-retention true --delete-retention-days 30 --output none
az storage container create --name tfstate --account-name "$STORAGE" --auth-mode login --output none

az acr create --name "$ACR" --resource-group "$RG" --location "$LOCATION" --sku Premium \
  --admin-enabled false --output none
az acr replication create --registry "$ACR" --location "$SECONDARY" --output none || \
  echo "Replication to $SECONDARY already exists or could not be created. Check it before running perf or prod."

cat <<EOF

Done. Add these as GitHub repository variables:
  UNIQUE_PREFIX=$PREFIX
  TF_STATE_RESOURCE_GROUP=$RG
  TF_STATE_STORAGE_ACCOUNT=$STORAGE
  ACR_NAME=$ACR
  ACR_RESOURCE_GROUP=$RG

Then give the GitHub deploy identity:
  Storage Blob Data Contributor on $STORAGE
  AcrPush and Contributor on $ACR
EOF

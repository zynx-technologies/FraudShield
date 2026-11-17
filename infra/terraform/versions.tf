terraform {
  required_version = ">= 1.9"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 5.12"
    }
  }

  # Values are passed by the pipeline. See infra/bootstrap.sh and azure-pipelines/templates/terraform-stage.yml.
  backend "azurerm" {}
}

provider "azurerm" {
  # Use Entra ID, not account keys, for every storage call. Shared keys are disabled on all accounts.
  storage_use_azuread = true

  features {
    key_vault {
      purge_soft_delete_on_destroy = false
    }
    resource_group {
      prevent_deletion_if_contains_resources = true
    }
  }
}

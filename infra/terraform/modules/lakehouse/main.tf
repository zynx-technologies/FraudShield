# Learning path: ADLS Gen2 for Delta Lake, immutable storage for signed models and audit exports,
# VNet-injected Databricks (Premium, for Unity Catalog), and Data Factory. Design sections 6 and 8.
#
# Unity Catalog metastore assignment, catalogs, and external locations are account-level Databricks
# objects. They are set up with the Databricks provider or bundle after this module runs.

variable "name" {
  type = string
}

variable "storage_prefix" {
  description = "Lowercase letters and digits only, used in storage account names."
  type        = string
}

variable "location" {
  type = string
}

variable "resource_group_name" {
  type = string
}

variable "replication_type" {
  type    = string
  default = "ZRS"
}

variable "data_spoke" {
  type = object({
    vnet_id                      = string
    host_subnet_name             = string
    container_subnet_name        = string
    host_nsg_association_id      = string
    container_nsg_association_id = string
    private_endpoint_subnet_id   = string
    dns_zone_ids                 = map(string)
  })
}

variable "app_regions" {
  description = "Application spokes. The scoring service in each region reads signed models from immutable storage."
  type = map(object({
    location                   = string
    short                      = string
    resource_group_name        = string
    private_endpoint_subnet_id = string
    dns_zone_ids               = map(string)
  }))
}

variable "databricks_public_access" {
  description = "Allow the Databricks workspace UI from the internet (with Entra sign-in). False in prod."
  type        = bool
  default     = true
}

variable "model_retention_days" {
  description = "Seven years, for decision replay (design section 2)."
  type        = number
  default     = 2557
}

variable "lock_immutability" {
  description = "Locking is irreversible. Set to true only in prod, after the retention period is agreed."
  type        = bool
  default     = false
}

variable "log_analytics_workspace_id" {
  type = string
}

variable "tags" {
  type = map(string)
}

locals {
  lake_containers = ["landing", "bronze", "silver", "gold", "quarantine", "features"]
  worm_containers = ["models", "audit"]
}

# ---------- ADLS Gen2 ----------

resource "azurerm_storage_account" "lake" {
  name                              = "${var.storage_prefix}lake"
  location                          = var.location
  resource_group_name               = var.resource_group_name
  account_tier                      = "Standard"
  account_replication_type          = var.replication_type
  account_kind                      = "StorageV2"
  is_hns_enabled                    = true
  min_tls_version                   = "TLS1_2"
  shared_access_key_enabled         = false
  default_to_oauth_authentication   = true
  allow_nested_items_to_be_public   = false
  public_network_access             = "Disabled"
  infrastructure_encryption_enabled = true
  tags                              = var.tags

  network_rules {
    default_action = "Deny"
    bypass         = ["AzureServices"]
  }
}

resource "azurerm_storage_container" "lake" {
  for_each           = toset(local.lake_containers)
  name               = each.key
  storage_account_id = azurerm_storage_account.lake.id
}

module "lake_endpoint" {
  source              = "../private-endpoint"
  for_each            = toset(["dfs", "blob"])
  name                = "pe-${var.name}-lake-${each.key}"
  location            = var.location
  resource_group_name = var.resource_group_name
  subnet_id           = var.data_spoke.private_endpoint_subnet_id
  resource_id         = azurerm_storage_account.lake.id
  subresource         = each.key
  dns_zone_ids        = [var.data_spoke.dns_zone_ids[each.key]]
  tags                = var.tags
}

# ---------- Immutable storage for signed models and audit exports ----------

resource "azurerm_storage_account" "worm" {
  name                              = "${var.storage_prefix}worm"
  location                          = var.location
  resource_group_name               = var.resource_group_name
  account_tier                      = "Standard"
  account_replication_type          = var.replication_type == "ZRS" ? "ZRS" : "GZRS"
  account_kind                      = "StorageV2"
  min_tls_version                   = "TLS1_2"
  shared_access_key_enabled         = false
  default_to_oauth_authentication   = true
  allow_nested_items_to_be_public   = false
  public_network_access             = "Disabled"
  infrastructure_encryption_enabled = true
  tags                              = var.tags

  blob_properties {
    versioning_enabled = true

    delete_retention_policy {
      days = 30
    }

    container_delete_retention_policy {
      days = 30
    }
  }

  network_rules {
    default_action = "Deny"
    bypass         = ["AzureServices"]
  }
}

resource "azurerm_storage_container" "worm" {
  for_each           = toset(local.worm_containers)
  name               = each.key
  storage_account_id = azurerm_storage_account.worm.id
}

resource "azurerm_storage_container_immutability_policy" "worm" {
  for_each                              = azurerm_storage_container.worm
  storage_container_resource_manager_id = each.value.id
  immutability_period_in_days           = var.model_retention_days
  protected_append_writes_enabled       = each.key == "audit"
  locked                                = var.lock_immutability
}

module "worm_endpoint_data" {
  source              = "../private-endpoint"
  name                = "pe-${var.name}-worm-blob"
  location            = var.location
  resource_group_name = var.resource_group_name
  subnet_id           = var.data_spoke.private_endpoint_subnet_id
  resource_id         = azurerm_storage_account.worm.id
  subresource         = "blob"
  dns_zone_ids        = [var.data_spoke.dns_zone_ids["blob"]]
  tags                = var.tags
}

module "worm_endpoint_app" {
  source              = "../private-endpoint"
  for_each            = var.app_regions
  name                = "pe-${var.name}-worm-blob-${each.value.short}"
  location            = each.value.location
  resource_group_name = each.value.resource_group_name
  subnet_id           = each.value.private_endpoint_subnet_id
  resource_id         = azurerm_storage_account.worm.id
  subresource         = "blob"
  dns_zone_ids        = [each.value.dns_zone_ids["blob"]]
  tags                = var.tags
}

# ---------- Databricks ----------

resource "azurerm_databricks_access_connector" "this" {
  name                = "dbac-${var.name}"
  location            = var.location
  resource_group_name = var.resource_group_name
  tags                = var.tags

  identity {
    type = "SystemAssigned"
  }
}

resource "azurerm_role_assignment" "databricks_lake" {
  scope                = azurerm_storage_account.lake.id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = azurerm_databricks_access_connector.this.identity[0].principal_id
}

# Databricks registers signed ONNX models in immutable storage, and writes audit exports.
resource "azurerm_role_assignment" "databricks_worm" {
  scope                = azurerm_storage_account.worm.id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = azurerm_databricks_access_connector.this.identity[0].principal_id
}

resource "azurerm_databricks_workspace" "this" {
  name                                  = "dbw-${var.name}"
  location                              = var.location
  resource_group_name                   = var.resource_group_name
  sku                                   = "premium"
  managed_resource_group_name           = "rg-${var.name}-databricks-managed"
  public_network_access_enabled         = var.databricks_public_access
  network_security_group_rules_required = var.databricks_public_access ? "AllRules" : "NoAzureDatabricksRules"
  default_storage_firewall_enabled      = true
  access_connector_id                   = azurerm_databricks_access_connector.this.id
  infrastructure_encryption_enabled     = true
  tags                                  = var.tags

  custom_parameters {
    no_public_ip                                         = true
    virtual_network_id                                   = var.data_spoke.vnet_id
    public_subnet_name                                   = var.data_spoke.host_subnet_name
    private_subnet_name                                  = var.data_spoke.container_subnet_name
    public_subnet_network_security_group_association_id  = var.data_spoke.host_nsg_association_id
    private_subnet_network_security_group_association_id = var.data_spoke.container_nsg_association_id
  }
}

module "databricks_endpoint" {
  source              = "../private-endpoint"
  name                = "pe-${var.name}-dbw-ui"
  location            = var.location
  resource_group_name = var.resource_group_name
  subnet_id           = var.data_spoke.private_endpoint_subnet_id
  resource_id         = azurerm_databricks_workspace.this.id
  subresource         = "databricks_ui_api"
  dns_zone_ids        = [var.data_spoke.dns_zone_ids["databricks"]]
  tags                = var.tags
}

# ---------- Data Factory ----------
# Core banking extracts and chargebacks (design section 6). A managed VNet keeps its traffic private.
# Managed private endpoints to the lake, and the self-hosted runtime for on-premises sources, are added
# once the source systems are confirmed.

resource "azurerm_data_factory" "this" {
  name                            = "adf-${var.name}"
  location                        = var.location
  resource_group_name             = var.resource_group_name
  managed_virtual_network_enabled = true
  public_network_enabled          = false
  tags                            = var.tags

  identity {
    type = "SystemAssigned"
  }
}

resource "azurerm_role_assignment" "data_factory_lake" {
  scope                = azurerm_storage_account.lake.id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = azurerm_data_factory.this.identity[0].principal_id
}

module "data_factory_endpoint" {
  source              = "../private-endpoint"
  name                = "pe-${var.name}-adf"
  location            = var.location
  resource_group_name = var.resource_group_name
  subnet_id           = var.data_spoke.private_endpoint_subnet_id
  resource_id         = azurerm_data_factory.this.id
  subresource         = "dataFactory"
  dns_zone_ids        = [var.data_spoke.dns_zone_ids["datafactory"]]
  tags                = var.tags
}

resource "azurerm_monitor_diagnostic_setting" "databricks" {
  name                       = "to-log-analytics"
  target_resource_id         = azurerm_databricks_workspace.this.id
  log_analytics_workspace_id = var.log_analytics_workspace_id

  enabled_log {
    category_group = "allLogs"
  }
}

output "lake_account_id" {
  value = azurerm_storage_account.lake.id
}

output "worm_account_id" {
  value = azurerm_storage_account.worm.id
}

output "worm_blob_endpoint" {
  value = azurerm_storage_account.worm.primary_blob_endpoint
}

output "databricks_workspace_url" {
  value = "https://${azurerm_databricks_workspace.this.workspace_url}"
}

output "databricks_workspace_id" {
  value = azurerm_databricks_workspace.this.id
}

output "databricks_access_connector_id" {
  value = azurerm_databricks_access_connector.this.id
}

output "data_factory_id" {
  value = azurerm_data_factory.this.id
}

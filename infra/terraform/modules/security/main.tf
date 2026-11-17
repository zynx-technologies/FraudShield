# Key Vault for service secrets and certificates, and optional Defender for Cloud plans. Design section 12.
#
# Customer-managed keys in Key Vault Managed HSM need a security-domain ceremony with named key holders,
# so the HSM is not created here. See the security epic in delivery/BACKLOG.md.

variable "name" {
  type = string
}

variable "key_vault_name" {
  description = "Globally unique, 3 to 24 characters."
  type        = string
}

variable "location" {
  type = string
}

variable "resource_group_name" {
  type = string
}

variable "endpoints" {
  description = "Spokes that need a private endpoint to Key Vault."
  type = map(object({
    location                   = string
    short                      = string
    resource_group_name        = string
    private_endpoint_subnet_id = string
    dns_zone_ids               = map(string)
  }))
}

variable "defender_enabled" {
  description = "Defender plans are set per subscription. Enable them from one environment only."
  type        = bool
  default     = false
}

variable "log_analytics_workspace_id" {
  type = string
}

variable "tags" {
  type = map(string)
}

data "azurerm_client_config" "current" {}

resource "azurerm_key_vault" "this" {
  name                          = var.key_vault_name
  location                      = var.location
  resource_group_name           = var.resource_group_name
  tenant_id                     = data.azurerm_client_config.current.tenant_id
  sku_name                      = "premium"
  rbac_authorization_enabled    = true
  purge_protection_enabled      = true
  soft_delete_retention_days    = 90
  public_network_access_enabled = false
  tags                          = var.tags

  network_acls {
    default_action = "Deny"
    bypass         = "AzureServices"
  }
}

module "endpoint" {
  source              = "../private-endpoint"
  for_each            = var.endpoints
  name                = "pe-kv-${var.name}-${each.value.short}"
  location            = each.value.location
  resource_group_name = each.value.resource_group_name
  subnet_id           = each.value.private_endpoint_subnet_id
  resource_id         = azurerm_key_vault.this.id
  subresource         = "vault"
  dns_zone_ids        = [each.value.dns_zone_ids["vault"]]
  tags                = var.tags
}

resource "azurerm_monitor_diagnostic_setting" "key_vault" {
  name                       = "to-log-analytics"
  target_resource_id         = azurerm_key_vault.this.id
  log_analytics_workspace_id = var.log_analytics_workspace_id

  enabled_log {
    category_group = "audit"
  }
}

resource "azurerm_security_center_subscription_pricing" "this" {
  for_each      = var.defender_enabled ? toset(["Containers", "KeyVaults", "StorageAccounts", "SqlServers", "CosmosDbs", "Arm", "Api"]) : toset([])
  tier          = "Standard"
  resource_type = each.key
}

output "key_vault_id" {
  value = azurerm_key_vault.this.id
}

output "key_vault_uri" {
  value = azurerm_key_vault.this.vault_uri
}

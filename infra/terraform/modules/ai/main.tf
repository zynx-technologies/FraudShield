# Microsoft Foundry (Azure AI Services) for Case Copilot, with Azure AI Content Safety included.
# Private only, Entra only, in a UK region, so case data stays in the UK. Design sections 7 and 12.
# The language model drafts summaries only; it never sits in the payment path.

variable "name" {
  type = string
}

variable "location" {
  type = string
}

variable "resource_group_name" {
  type = string
}

variable "app_regions" {
  type = map(object({
    location                   = string
    short                      = string
    resource_group_name        = string
    private_endpoint_subnet_id = string
    dns_zone_ids               = map(string)
  }))
}

variable "model" {
  description = "Model deployment for Copilot. Null deploys none, until the security and privacy review approves one."
  type = object({
    name     = string
    version  = string
    sku      = string
    capacity = number
  })
  default = null
}

variable "log_analytics_workspace_id" {
  type = string
}

variable "tags" {
  type = map(string)
}

resource "azurerm_cognitive_account" "this" {
  name                               = "ais-${var.name}"
  location                           = var.location
  resource_group_name                = var.resource_group_name
  kind                               = "AIServices"
  sku_name                           = "S0"
  custom_subdomain_name              = "ais-${var.name}"
  local_auth_enabled                 = false
  public_network_access_enabled      = false
  outbound_network_access_restricted = true
  tags                               = var.tags

  identity {
    type = "SystemAssigned"
  }

  network_acls {
    default_action = "Deny"
  }
}

resource "azurerm_cognitive_deployment" "copilot" {
  count                = var.model == null ? 0 : 1
  name                 = "copilot"
  cognitive_account_id = azurerm_cognitive_account.this.id

  model {
    format  = "OpenAI"
    name    = var.model.name
    version = var.model.version
  }

  sku {
    name     = var.model.sku
    capacity = var.model.capacity
  }
}

module "endpoint" {
  source              = "../private-endpoint"
  for_each            = var.app_regions
  name                = "pe-ais-${var.name}-${each.value.short}"
  location            = each.value.location
  resource_group_name = each.value.resource_group_name
  subnet_id           = each.value.private_endpoint_subnet_id
  resource_id         = azurerm_cognitive_account.this.id
  subresource         = "account"
  dns_zone_ids = [
    each.value.dns_zone_ids["cognitiveservices"],
    each.value.dns_zone_ids["openai"],
    each.value.dns_zone_ids["aiservices"],
  ]
  tags = var.tags
}

resource "azurerm_monitor_diagnostic_setting" "this" {
  name                       = "to-log-analytics"
  target_resource_id         = azurerm_cognitive_account.this.id
  log_analytics_workspace_id = var.log_analytics_workspace_id

  enabled_log {
    category_group = "allLogs"
  }
}

output "id" {
  value = azurerm_cognitive_account.this.id
}

output "endpoint" {
  value = azurerm_cognitive_account.this.endpoint
}

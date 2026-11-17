# Azure API Management in internal VNet mode. It authenticates and throttles payment channels and the
# portal, and routes to the AKS ingress. In perf and prod it runs in a second region as an additional
# location (Premium). Design sections 3 and 11.

variable "name" {
  type = string
}

variable "location" {
  type = string
}

variable "resource_group_name" {
  type = string
}

variable "sku_name" {
  description = "Developer_1 for dev and test (no SLA). Premium_<units> for perf and prod."
  type        = string
}

variable "zones" {
  type    = list(string)
  default = []
}

variable "publisher_name" {
  type = string
}

variable "publisher_email" {
  type = string
}

variable "subnet_id" {
  type = string
}

variable "additional_locations" {
  description = "Extra regions. Premium only."
  type = map(object({
    location  = string
    subnet_id = string
    capacity  = number
    zones     = list(string)
  }))
  default = {}
}

variable "application_insights_id" {
  type = string
}

variable "application_insights_connection_string" {
  type      = string
  sensitive = true
}

variable "log_analytics_workspace_id" {
  type = string
}

variable "tags" {
  type = map(string)
}

resource "azurerm_public_ip" "primary" {
  name                = "pip-${var.name}"
  location            = var.location
  resource_group_name = var.resource_group_name
  allocation_method   = "Static"
  sku                 = "Standard"
  domain_name_label   = var.name
  zones               = length(var.zones) > 0 ? var.zones : null
  tags                = var.tags
}

resource "azurerm_public_ip" "additional" {
  for_each            = var.additional_locations
  name                = "pip-${var.name}-${each.key}"
  location            = each.value.location
  resource_group_name = var.resource_group_name
  allocation_method   = "Static"
  sku                 = "Standard"
  domain_name_label   = "${var.name}-${each.key}"
  zones               = length(each.value.zones) > 0 ? each.value.zones : null
  tags                = var.tags
}

resource "azurerm_api_management" "this" {
  name                 = var.name
  location             = var.location
  resource_group_name  = var.resource_group_name
  publisher_name       = var.publisher_name
  publisher_email      = var.publisher_email
  sku_name             = var.sku_name
  zones                = length(var.zones) > 0 ? var.zones : null
  virtual_network_type = "Internal"
  public_ip_address_id = azurerm_public_ip.primary.id
  min_api_version      = "2021-08-01"
  tags                 = var.tags

  virtual_network_configuration {
    subnet_id = var.subnet_id
  }

  dynamic "additional_location" {
    for_each = var.additional_locations
    content {
      location             = additional_location.value.location
      capacity             = additional_location.value.capacity
      zones                = length(additional_location.value.zones) > 0 ? additional_location.value.zones : null
      public_ip_address_id = azurerm_public_ip.additional[additional_location.key].id

      virtual_network_configuration {
        subnet_id = additional_location.value.subnet_id
      }
    }
  }

  identity {
    type = "SystemAssigned"
  }

  protocols {
    http2_enabled = true
  }

  security {
    frontend_tls10_enabled     = false
    frontend_tls11_enabled     = false
    backend_tls10_enabled      = false
    backend_tls11_enabled      = false
    triple_des_ciphers_enabled = false
  }

  sign_up {
    enabled = false
    terms_of_service {
      consent_required = false
      enabled          = false
    }
  }
}

resource "azurerm_api_management_logger" "app_insights" {
  name                = "app-insights"
  api_management_name = azurerm_api_management.this.name
  resource_group_name = var.resource_group_name
  resource_id         = var.application_insights_id

  application_insights {
    connection_string = var.application_insights_connection_string
  }
}

# Latency is measured at API Management (design section 2), so log every request's timings.
resource "azurerm_api_management_diagnostic" "app_insights" {
  identifier               = "applicationinsights"
  api_management_name      = azurerm_api_management.this.name
  resource_group_name      = var.resource_group_name
  api_management_logger_id = azurerm_api_management_logger.app_insights.id
  sampling_percentage      = 100
  always_log_errors        = true
  verbosity                = "information"
  log_client_ip            = false
}

resource "azurerm_monitor_diagnostic_setting" "apim" {
  name                           = "to-log-analytics"
  target_resource_id             = azurerm_api_management.this.id
  log_analytics_workspace_id     = var.log_analytics_workspace_id
  log_analytics_destination_type = "Dedicated"

  enabled_log {
    category_group = "allLogs"
  }

  enabled_metric {
    category = "AllMetrics"
  }
}

output "id" {
  value = azurerm_api_management.this.id
}

output "name" {
  value = azurerm_api_management.this.name
}

output "gateway_host" {
  value = trimprefix(azurerm_api_management.this.gateway_url, "https://")
}

# Private gateway address in each region, keyed like the regions map in the root module.
output "private_ips" {
  value = merge(
    { primary = azurerm_api_management.this.private_ip_addresses[0] },
    { for loc in azurerm_api_management.this.additional_location : "secondary" => loc.private_ip_addresses[0] }
  )
}

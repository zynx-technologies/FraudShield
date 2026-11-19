# ---------- Environment ----------

variable "environment" {
  type = string

  validation {
    condition     = contains(["dev", "test", "perf", "prod"], var.environment)
    error_message = "environment must be dev, test, perf, or prod."
  }
}

variable "unique_prefix" {
  description = "Short lowercase prefix for globally unique names (storage, Key Vault). The same value given to bootstrap.sh."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{2,7}$", var.unique_prefix))
    error_message = "unique_prefix must be 3 to 8 lowercase letters or digits, starting with a letter."
  }
}

variable "primary_location" {
  type    = string
  default = "uksouth"
}

variable "secondary_location" {
  type    = string
  default = "ukwest"
}

variable "multi_region" {
  description = "Active-active decision path in two regions. True for perf (failover tests) and prod."
  type        = bool
  default     = false
}

variable "address_space" {
  description = "A /16 for the environment. Each region gets half."
  type        = string
}

variable "compute_platform" {
  description = <<-EOT
    aks: private AKS behind Front Door, Application Gateway, and internal API Management (test, perf, prod).
    container_apps: low-cost dev. Container Apps, with API Management in external mode as the public entry
    point, and no Front Door or Application Gateway. Single region only.
  EOT
  type        = string
  default     = "aks"

  validation {
    condition     = contains(["aks", "container_apps"], var.compute_platform)
    error_message = "compute_platform must be aks or container_apps."
  }

  validation {
    condition     = var.compute_platform == "aks" || !var.multi_region
    error_message = "container_apps supports a single region only."
  }
}

variable "zones" {
  description = "Availability zones. UK West has none, so zonal settings apply only in regions that have them."
  type        = list(string)
  default     = ["1", "2", "3"]
}

# ---------- Shared resources from bootstrap.sh ----------

variable "acr_name" {
  type = string
}

variable "acr_resource_group" {
  type = string
}

# ---------- Network ----------

variable "channel_source_cidrs" {
  description = "Payment switch and channel ranges, reached over ExpressRoute. They may call API Management."
  type        = list(string)
  default     = []
}

variable "firewall_enabled" {
  description = "Hub Azure Firewall. AKS needs it for egress. In container_apps mode apps leave through a NAT gateway instead."
  type        = bool
  default     = true

  validation {
    condition     = var.firewall_enabled || var.compute_platform == "container_apps"
    error_message = "AKS sends all egress through the firewall, so firewall_enabled can be false only with container_apps."
  }
}

variable "firewall_sku" {
  type    = string
  default = "Standard"
}

variable "bastion_enabled" {
  type    = bool
  default = true
}

variable "expressroute_gateway_enabled" {
  type    = bool
  default = false
}

# ---------- AKS (compute_platform = "aks" only) ----------

variable "kubernetes_version" {
  type    = string
  default = null
}

variable "aks_sku_tier" {
  type    = string
  default = "Standard"
}

variable "aks_system_node_count" {
  type    = number
  default = 3
}

variable "aks_apps_vm_size" {
  type    = string
  default = "Standard_D8ds_v5"
}

variable "aks_apps_min_count" {
  type    = number
  default = 2
}

variable "aks_apps_max_count" {
  type    = number
  default = 10
}

variable "aks_admin_group_object_ids" {
  type    = list(string)
  default = []
}

# ---------- API Management ----------

variable "apim_sku" {
  description = "Developer_1 for dev and test. Premium_<units> for perf and prod, which is needed for a second region."
  type        = string
  default     = "Developer_1"
}

variable "apim_secondary_capacity" {
  type    = number
  default = 1
}

variable "apim_publisher_name" {
  type    = string
  default = "FraudShield"
}

variable "apim_publisher_email" {
  type = string
}

# ---------- Data stores ----------

variable "sql_sku" {
  type    = string
  default = "GP_S_Gen5_2"
}

variable "sql_admin_group_name" {
  type = string
}

variable "sql_admin_group_object_id" {
  type = string
}

variable "cosmos_max_throughput" {
  type    = number
  default = 4000
}

variable "redis_sku" {
  type    = string
  default = "Balanced_B5"
}

variable "event_hubs_sku" {
  type    = string
  default = "Standard"
}

variable "event_hubs_capacity" {
  type    = number
  default = 2
}

variable "signalr_sku" {
  type    = string
  default = "Standard_S1"
}

# ---------- Data platform ----------

variable "databricks_public_access" {
  type    = bool
  default = true
}

variable "lock_immutability" {
  type    = bool
  default = false
}

# ---------- Edge ----------

variable "origin_host_name" {
  description = "Host name on the Application Gateway certificate. Leave empty, with origin_certificate_secret_id, to skip the edge."
  type        = string
  default     = ""
}

variable "origin_certificate_secret_id" {
  description = "Versionless Key Vault secret ID of the origin certificate. The edge is created only when this is set."
  type        = string
  default     = ""
}

variable "waf_mode" {
  type    = string
  default = "Prevention"
}

variable "appgw_min_capacity" {
  type    = number
  default = 1
}

variable "appgw_max_capacity" {
  type    = number
  default = 4
}

# ---------- Entra ID ----------

variable "entra_api_client_id" {
  description = "Client ID of the fraudshield-api app registration. Services accept tokens for it."
  type        = string
  default     = ""
}

variable "entra_spa_client_id" {
  description = "Client ID of the fraudshield-portal app registration (single-page application)."
  type        = string
  default     = ""
}

# ---------- AI ----------

variable "ai_model" {
  description = "Copilot model deployment. Null until the security and privacy review approves one."
  type = object({
    name     = string
    version  = string
    sku      = string
    capacity = number
  })
  default = null
}

# ---------- Operations ----------

variable "log_retention_days" {
  type    = number
  default = 90
}

variable "alert_emails" {
  type    = list(string)
  default = []
}

variable "sentinel_enabled" {
  type    = bool
  default = false
}

variable "defender_enabled" {
  type    = bool
  default = false
}

variable "name" {
  description = "Base name, for example fraudshield-prod-uks."
  type        = string
}

variable "location" {
  type = string
}

variable "resource_group_name" {
  type = string
}

variable "address_space" {
  description = "A /22 for the hub."
  type        = string
}

variable "zones" {
  type    = list(string)
  default = ["1", "2", "3"]
}

variable "firewall_enabled" {
  description = "False only for a low-cost environment whose workloads leave through a NAT gateway (no AKS)."
  type        = bool
  default     = true
}

variable "firewall_sku" {
  description = "Standard, or Premium for IDPS in production."
  type        = string
  default     = "Standard"

  validation {
    condition     = contains(["Standard", "Premium"], var.firewall_sku)
    error_message = "firewall_sku must be Standard or Premium."
  }
}

variable "aks_subnet_cidrs" {
  description = "AKS node subnets whose egress is routed through this firewall."
  type        = list(string)
}

variable "acr_name" {
  type = string
}

variable "extra_egress_fqdns" {
  description = "Extra FQDNs AKS may reach. Keep this short and reviewed."
  type        = list(string)
  default     = []
}

variable "bastion_enabled" {
  type    = bool
  default = true
}

variable "expressroute_gateway_enabled" {
  type    = bool
  default = false
}

variable "log_analytics_workspace_id" {
  type = string
}

variable "tags" {
  type = map(string)
}

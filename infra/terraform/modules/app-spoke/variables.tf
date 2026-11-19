variable "name" {
  type = string
}

variable "location" {
  type = string
}

variable "resource_group_name" {
  type = string
}

variable "address_space" {
  description = "A /19 for the application spoke."
  type        = string
}

variable "compute_platform" {
  description = "aks or container_apps. See the root variable of the same name."
  type        = string
  default     = "aks"
}

variable "firewall_private_ip" {
  description = "Next hop for AKS egress. Null in container_apps mode."
  type        = string
  default     = null
}

variable "apim_public" {
  description = "True when API Management runs in external VNet mode and takes HTTPS from the internet."
  type        = bool
  default     = false
}

variable "hub_vnet_id" {
  type = string
}

variable "hub_vnet_name" {
  type = string
}

variable "hub_resource_group_name" {
  type = string
}

variable "hub_has_gateway" {
  type = bool
}

variable "channel_source_cidrs" {
  description = "Address ranges of the payment switch and channel systems, reached over ExpressRoute."
  type        = list(string)
}

variable "tags" {
  type = map(string)
}

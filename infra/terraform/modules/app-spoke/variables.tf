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

variable "firewall_private_ip" {
  type = string
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

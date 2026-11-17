# One private endpoint, registered in the private DNS zones of the region it sits in.

variable "name" {
  type = string
}

variable "location" {
  type = string
}

variable "resource_group_name" {
  type = string
}

variable "subnet_id" {
  type = string
}

variable "resource_id" {
  type = string
}

variable "subresource" {
  description = "Private link sub-resource, for example blob, Sql, vault, or namespace."
  type        = string
}

variable "dns_zone_ids" {
  type = list(string)
}

variable "tags" {
  type = map(string)
}

resource "azurerm_private_endpoint" "this" {
  name                          = var.name
  location                      = var.location
  resource_group_name           = var.resource_group_name
  subnet_id                     = var.subnet_id
  custom_network_interface_name = "nic-${var.name}"
  tags                          = var.tags

  private_service_connection {
    name                           = "psc-${var.name}"
    private_connection_resource_id = var.resource_id
    subresource_names              = [var.subresource]
    is_manual_connection           = false
  }

  private_dns_zone_group {
    name                 = "default"
    private_dns_zone_ids = var.dns_zone_ids
  }
}

output "id" {
  value = azurerm_private_endpoint.this.id
}

output "private_ip" {
  value = azurerm_private_endpoint.this.private_service_connection[0].private_ip_address
}

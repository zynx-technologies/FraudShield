# Data and ML spoke: VNet-injected Databricks and private endpoints for the lakehouse. Design section 11.
# Databricks egress uses a NAT gateway, as Databricks recommends for secure cluster connectivity.

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
  description = "A /19 for the data spoke."
  type        = string
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

variable "zones" {
  type    = list(string)
  default = ["1"]
}

variable "tags" {
  type = map(string)
}

locals {
  host_cidr      = cidrsubnet(var.address_space, 3, 0)  # /22
  container_cidr = cidrsubnet(var.address_space, 3, 1)  # /22
  pe_cidr        = cidrsubnet(var.address_space, 7, 32) # /26
}

resource "azurerm_virtual_network" "spoke" {
  name                = "vnet-${var.name}-data"
  location            = var.location
  resource_group_name = var.resource_group_name
  address_space       = [var.address_space]
  tags                = var.tags
}

resource "azurerm_subnet" "databricks" {
  for_each             = { host = local.host_cidr, container = local.container_cidr }
  name                 = "snet-databricks-${each.key}"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.spoke.name
  address_prefixes     = [each.value]

  delegation {
    name = "databricks"
    service_delegation {
      name = "Microsoft.Databricks/workspaces"
      actions = [
        "Microsoft.Network/virtualNetworks/subnets/join/action",
        "Microsoft.Network/virtualNetworks/subnets/prepareNetworkPolicies/action",
        "Microsoft.Network/virtualNetworks/subnets/unprepareNetworkPolicies/action",
      ]
    }
  }
}

# Databricks adds its own rules to these groups.
resource "azurerm_network_security_group" "databricks" {
  name                = "nsg-${var.name}-databricks"
  location            = var.location
  resource_group_name = var.resource_group_name
  tags                = var.tags
}

resource "azurerm_subnet_network_security_group_association" "databricks" {
  for_each                  = azurerm_subnet.databricks
  subnet_id                 = each.value.id
  network_security_group_id = azurerm_network_security_group.databricks.id
}

resource "azurerm_public_ip" "nat" {
  name                = "pip-${var.name}-nat"
  location            = var.location
  resource_group_name = var.resource_group_name
  allocation_method   = "Static"
  sku                 = "Standard"
  zones               = var.zones
  tags                = var.tags
}

resource "azurerm_nat_gateway" "databricks" {
  name                = "ng-${var.name}-databricks"
  location            = var.location
  resource_group_name = var.resource_group_name
  sku_name            = "Standard"
  zones               = var.zones
  tags                = var.tags
}

resource "azurerm_nat_gateway_public_ip_association" "databricks" {
  nat_gateway_id       = azurerm_nat_gateway.databricks.id
  public_ip_address_id = azurerm_public_ip.nat.id
}

resource "azurerm_subnet_nat_gateway_association" "databricks" {
  for_each       = azurerm_subnet.databricks
  subnet_id      = each.value.id
  nat_gateway_id = azurerm_nat_gateway.databricks.id
}

resource "azurerm_subnet" "private_endpoints" {
  name                              = "snet-private-endpoints"
  resource_group_name               = var.resource_group_name
  virtual_network_name              = azurerm_virtual_network.spoke.name
  address_prefixes                  = [local.pe_cidr]
  private_endpoint_network_policies = "Enabled"
}

resource "azurerm_virtual_network_peering" "spoke_to_hub" {
  name                         = "peer-data-to-hub"
  resource_group_name          = var.resource_group_name
  virtual_network_name         = azurerm_virtual_network.spoke.name
  remote_virtual_network_id    = var.hub_vnet_id
  allow_forwarded_traffic      = true
  allow_virtual_network_access = true
  use_remote_gateways          = var.hub_has_gateway
}

resource "azurerm_virtual_network_peering" "hub_to_spoke" {
  name                         = "peer-hub-to-${var.name}-data"
  resource_group_name          = var.hub_resource_group_name
  virtual_network_name         = var.hub_vnet_name
  remote_virtual_network_id    = azurerm_virtual_network.spoke.id
  allow_forwarded_traffic      = true
  allow_virtual_network_access = true
  allow_gateway_transit        = var.hub_has_gateway
}

output "vnet_id" {
  value = azurerm_virtual_network.spoke.id
}

output "databricks_host_subnet_name" {
  value = azurerm_subnet.databricks["host"].name
}

output "databricks_container_subnet_name" {
  value = azurerm_subnet.databricks["container"].name
}

output "databricks_host_nsg_association_id" {
  value = azurerm_subnet_network_security_group_association.databricks["host"].id
}

output "databricks_container_nsg_association_id" {
  value = azurerm_subnet_network_security_group_association.databricks["container"].id
}

output "private_endpoint_subnet_id" {
  value = azurerm_subnet.private_endpoints.id
}

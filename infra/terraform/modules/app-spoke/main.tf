# Decision and application spoke: API Management and private endpoints, plus the compute platform:
#   aks             AKS, the internal ingress, and Application Gateway. AKS egress is forced through the hub
#                   firewall. Design section 11.
#   container_apps  A Container Apps environment subnet whose egress uses a NAT gateway (low-cost dev).

locals {
  aks            = var.compute_platform == "aks"
  container_apps = var.compute_platform == "container_apps"

  aks_cidr     = cidrsubnet(var.address_space, 2, 0)   # /21
  appgw_cidr   = cidrsubnet(var.address_space, 5, 8)   # /24
  apim_cidr    = cidrsubnet(var.address_space, 8, 72)  # /27
  ingress_cidr = cidrsubnet(var.address_space, 9, 146) # /28
  pe_cidr      = cidrsubnet(var.address_space, 7, 40)  # /26
  apps_cidr    = cidrsubnet(var.address_space, 4, 6)   # /23, the minimum for a workload profiles environment
}

resource "azurerm_virtual_network" "spoke" {
  name                = "vnet-${var.name}-app"
  location            = var.location
  resource_group_name = var.resource_group_name
  address_space       = [var.address_space]
  tags                = var.tags
}

resource "azurerm_subnet" "aks" {
  count                = local.aks ? 1 : 0
  name                 = "snet-aks"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.spoke.name
  address_prefixes     = [local.aks_cidr]
}

resource "azurerm_subnet" "ingress" {
  count                = local.aks ? 1 : 0
  name                 = "snet-ingress"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.spoke.name
  address_prefixes     = [local.ingress_cidr]
}

resource "azurerm_subnet" "apim" {
  name                 = "snet-apim"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.spoke.name
  address_prefixes     = [local.apim_cidr]
}

resource "azurerm_subnet" "appgw" {
  count                = local.aks ? 1 : 0
  name                 = "snet-appgw"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.spoke.name
  address_prefixes     = [local.appgw_cidr]
}

resource "azurerm_subnet" "private_endpoints" {
  name                              = "snet-private-endpoints"
  resource_group_name               = var.resource_group_name
  virtual_network_name              = azurerm_virtual_network.spoke.name
  address_prefixes                  = [local.pe_cidr]
  private_endpoint_network_policies = "Enabled"
}

# ---------- Routing: AKS egress through the hub firewall ----------

resource "azurerm_route_table" "aks" {
  count                         = local.aks ? 1 : 0
  name                          = "rt-${var.name}-aks"
  location                      = var.location
  resource_group_name           = var.resource_group_name
  bgp_route_propagation_enabled = false
  tags                          = var.tags

  route {
    name                   = "default-via-firewall"
    address_prefix         = "0.0.0.0/0"
    next_hop_type          = "VirtualAppliance"
    next_hop_in_ip_address = var.firewall_private_ip
  }
}

resource "azurerm_subnet_route_table_association" "aks" {
  count          = local.aks ? 1 : 0
  subnet_id      = azurerm_subnet.aks[0].id
  route_table_id = azurerm_route_table.aks[0].id
}

# ---------- Network security groups ----------

resource "azurerm_network_security_group" "apim" {
  name                = "nsg-${var.name}-apim"
  location            = var.location
  resource_group_name = var.resource_group_name
  tags                = var.tags

  security_rule {
    name                       = "apim-management"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "3443"
    source_address_prefix      = "ApiManagement"
    destination_address_prefix = "VirtualNetwork"
  }

  security_rule {
    name                       = "azure-load-balancer"
    priority                   = 110
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "6390"
    source_address_prefix      = "AzureLoadBalancer"
    destination_address_prefix = "VirtualNetwork"
  }

  # Payment channels arrive over ExpressRoute or peering, and the portal arrives through Application Gateway.
  security_rule {
    name                       = "gateway-https"
    priority                   = 120
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "443"
    source_address_prefixes    = concat([local.appgw_cidr], var.channel_source_cidrs)
    destination_address_prefix = "VirtualNetwork"
  }

  # Container Apps mode: API Management is the public entry point (external VNet mode) for the portal and
  # the API. Services still check every Entra token. Dev only, with synthetic data.
  #trivy:ignore:AVD-AZU-0047
  dynamic "security_rule" {
    for_each = var.apim_public ? [1] : []
    content {
      name                       = "internet-https"
      priority                   = 130
      direction                  = "Inbound"
      access                     = "Allow"
      protocol                   = "Tcp"
      source_port_range          = "*"
      destination_port_range     = "443"
      source_address_prefix      = "Internet"
      destination_address_prefix = "VirtualNetwork"
    }
  }

  security_rule {
    name                       = "storage"
    priority                   = 100
    direction                  = "Outbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "443"
    source_address_prefix      = "VirtualNetwork"
    destination_address_prefix = "Storage"
  }

  security_rule {
    name                       = "sql"
    priority                   = 110
    direction                  = "Outbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "1433"
    source_address_prefix      = "VirtualNetwork"
    destination_address_prefix = "Sql"
  }

  security_rule {
    name                       = "key-vault"
    priority                   = 120
    direction                  = "Outbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "443"
    source_address_prefix      = "VirtualNetwork"
    destination_address_prefix = "AzureKeyVault"
  }

  security_rule {
    name                       = "azure-monitor"
    priority                   = 130
    direction                  = "Outbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_ranges    = ["443", "1886"]
    source_address_prefix      = "VirtualNetwork"
    destination_address_prefix = "AzureMonitor"
  }
}

resource "azurerm_subnet_network_security_group_association" "apim" {
  subnet_id                 = azurerm_subnet.apim.id
  network_security_group_id = azurerm_network_security_group.apim.id
}

# Application Gateway accepts traffic only from Front Door.
resource "azurerm_network_security_group" "appgw" {
  count               = local.aks ? 1 : 0
  name                = "nsg-${var.name}-appgw"
  location            = var.location
  resource_group_name = var.resource_group_name
  tags                = var.tags

  security_rule {
    name                       = "gateway-manager"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "65200-65535"
    source_address_prefix      = "GatewayManager"
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "azure-load-balancer"
    priority                   = 110
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = "AzureLoadBalancer"
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "front-door-only"
    priority                   = 120
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "443"
    source_address_prefix      = "AzureFrontDoor.Backend"
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "deny-internet"
    priority                   = 4000
    direction                  = "Inbound"
    access                     = "Deny"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = "Internet"
    destination_address_prefix = "*"
  }
}

resource "azurerm_subnet_network_security_group_association" "appgw" {
  count                     = local.aks ? 1 : 0
  subnet_id                 = azurerm_subnet.appgw[0].id
  network_security_group_id = azurerm_network_security_group.appgw[0].id
}

# The internal ingress takes traffic only from API Management and Application Gateway.
resource "azurerm_network_security_group" "ingress" {
  count               = local.aks ? 1 : 0
  name                = "nsg-${var.name}-ingress"
  location            = var.location
  resource_group_name = var.resource_group_name
  tags                = var.tags

  security_rule {
    name                       = "from-gateways"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_ranges    = ["80", "443"]
    source_address_prefixes    = [local.apim_cidr, local.appgw_cidr]
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "azure-load-balancer"
    priority                   = 110
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = "AzureLoadBalancer"
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "deny-other-vnet"
    priority                   = 4000
    direction                  = "Inbound"
    access                     = "Deny"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = "VirtualNetwork"
    destination_address_prefix = "*"
  }
}

resource "azurerm_subnet_network_security_group_association" "ingress" {
  count                     = local.aks ? 1 : 0
  subnet_id                 = azurerm_subnet.ingress[0].id
  network_security_group_id = azurerm_network_security_group.ingress[0].id
}

# ---------- Container Apps (container_apps mode) ----------

resource "azurerm_subnet" "apps" {
  count                = local.container_apps ? 1 : 0
  name                 = "snet-container-apps"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.spoke.name
  address_prefixes     = [local.apps_cidr]

  delegation {
    name = "container-apps"
    service_delegation {
      name    = "Microsoft.App/environments"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}

# The environment is internal. Apps take traffic only from API Management and from each other.
resource "azurerm_network_security_group" "apps" {
  count               = local.container_apps ? 1 : 0
  name                = "nsg-${var.name}-container-apps"
  location            = var.location
  resource_group_name = var.resource_group_name
  tags                = var.tags

  security_rule {
    name                       = "from-apim"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_ranges    = ["80", "443"]
    source_address_prefix      = local.apim_cidr
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "within-environment"
    priority                   = 110
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = local.apps_cidr
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "azure-load-balancer"
    priority                   = 120
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = "AzureLoadBalancer"
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "deny-other-vnet"
    priority                   = 4000
    direction                  = "Inbound"
    access                     = "Deny"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = "VirtualNetwork"
    destination_address_prefix = "*"
  }
}

resource "azurerm_subnet_network_security_group_association" "apps" {
  count                     = local.container_apps ? 1 : 0
  subnet_id                 = azurerm_subnet.apps[0].id
  network_security_group_id = azurerm_network_security_group.apps[0].id
}

# Egress (registry, Entra ID, Microsoft endpoints) through a NAT gateway instead of the hub firewall.
# Data services are reached through the private endpoints in this spoke.
resource "azurerm_public_ip" "nat" {
  count               = local.container_apps ? 1 : 0
  name                = "pip-${var.name}-nat"
  location            = var.location
  resource_group_name = var.resource_group_name
  allocation_method   = "Static"
  sku                 = "Standard"
  tags                = var.tags
}

resource "azurerm_nat_gateway" "apps" {
  count               = local.container_apps ? 1 : 0
  name                = "ng-${var.name}-apps"
  location            = var.location
  resource_group_name = var.resource_group_name
  sku_name            = "Standard"
  tags                = var.tags
}

resource "azurerm_nat_gateway_public_ip_association" "apps" {
  count                = local.container_apps ? 1 : 0
  nat_gateway_id       = azurerm_nat_gateway.apps[0].id
  public_ip_address_id = azurerm_public_ip.nat[0].id
}

resource "azurerm_subnet_nat_gateway_association" "apps" {
  count          = local.container_apps ? 1 : 0
  subnet_id      = azurerm_subnet_network_security_group_association.apps[0].subnet_id
  nat_gateway_id = azurerm_nat_gateway.apps[0].id
}

# ---------- Moved: the AKS-only resources became optional. Existing environments keep them. ----------

moved {
  from = azurerm_subnet.aks
  to   = azurerm_subnet.aks[0]
}

moved {
  from = azurerm_subnet.ingress
  to   = azurerm_subnet.ingress[0]
}

moved {
  from = azurerm_subnet.appgw
  to   = azurerm_subnet.appgw[0]
}

moved {
  from = azurerm_route_table.aks
  to   = azurerm_route_table.aks[0]
}

moved {
  from = azurerm_subnet_route_table_association.aks
  to   = azurerm_subnet_route_table_association.aks[0]
}

moved {
  from = azurerm_network_security_group.appgw
  to   = azurerm_network_security_group.appgw[0]
}

moved {
  from = azurerm_subnet_network_security_group_association.appgw
  to   = azurerm_subnet_network_security_group_association.appgw[0]
}

moved {
  from = azurerm_network_security_group.ingress
  to   = azurerm_network_security_group.ingress[0]
}

moved {
  from = azurerm_subnet_network_security_group_association.ingress
  to   = azurerm_subnet_network_security_group_association.ingress[0]
}

# ---------- Peering with the hub ----------

resource "azurerm_virtual_network_peering" "spoke_to_hub" {
  name                         = "peer-app-to-hub"
  resource_group_name          = var.resource_group_name
  virtual_network_name         = azurerm_virtual_network.spoke.name
  remote_virtual_network_id    = var.hub_vnet_id
  allow_forwarded_traffic      = true
  allow_virtual_network_access = true
  use_remote_gateways          = var.hub_has_gateway
}

resource "azurerm_virtual_network_peering" "hub_to_spoke" {
  name                         = "peer-hub-to-${var.name}-app"
  resource_group_name          = var.hub_resource_group_name
  virtual_network_name         = var.hub_vnet_name
  remote_virtual_network_id    = azurerm_virtual_network.spoke.id
  allow_forwarded_traffic      = true
  allow_virtual_network_access = true
  allow_gateway_transit        = var.hub_has_gateway
}

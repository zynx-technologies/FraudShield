# Regional hub: Azure Firewall for egress control, Bastion for admin access, and an optional ExpressRoute
# gateway for the payment switch and core banking. Design section 11.

locals {
  firewall_cidr = cidrsubnet(var.address_space, 4, 0)
  bastion_cidr  = cidrsubnet(var.address_space, 4, 1)
  gateway_cidr  = cidrsubnet(var.address_space, 5, 4)
}

resource "azurerm_virtual_network" "hub" {
  name                = "vnet-${var.name}-hub"
  location            = var.location
  resource_group_name = var.resource_group_name
  address_space       = [var.address_space]
  tags                = var.tags
}

resource "azurerm_subnet" "firewall" {
  name                 = "AzureFirewallSubnet"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.hub.name
  address_prefixes     = [local.firewall_cidr]
}

resource "azurerm_subnet" "bastion" {
  name                 = "AzureBastionSubnet"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.hub.name
  address_prefixes     = [local.bastion_cidr]
}

resource "azurerm_subnet" "gateway" {
  name                 = "GatewaySubnet"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.hub.name
  address_prefixes     = [local.gateway_cidr]
}

# ---------- Azure Firewall ----------

resource "azurerm_public_ip" "firewall" {
  name                = "pip-${var.name}-fw"
  location            = var.location
  resource_group_name = var.resource_group_name
  allocation_method   = "Static"
  sku                 = "Standard"
  zones               = var.zones
  tags                = var.tags
}

resource "azurerm_firewall_policy" "hub" {
  name                     = "afwp-${var.name}"
  location                 = var.location
  resource_group_name      = var.resource_group_name
  sku                      = var.firewall_sku
  threat_intelligence_mode = "Deny"
  tags                     = var.tags

  dynamic "intrusion_detection" {
    for_each = var.firewall_sku == "Premium" ? [1] : []
    content {
      mode = "Deny"
    }
  }

  dns {
    proxy_enabled = true
  }
}

# Egress allow-list for AKS. Container images come only from the shared registry and MCR. Third-party
# images (Argo CD, Argo Rollouts, ingress-nginx) are imported into the registry by .github/scripts/install-platform.sh.
resource "azurerm_firewall_policy_rule_collection_group" "aks" {
  name               = "aks-egress"
  firewall_policy_id = azurerm_firewall_policy.hub.id
  priority           = 200

  network_rule_collection {
    name     = "aks-network"
    priority = 210
    action   = "Allow"

    rule {
      name                  = "aks-tunnel"
      protocols             = ["UDP", "TCP"]
      source_addresses      = var.aks_subnet_cidrs
      destination_addresses = ["AzureCloud.${var.location}"]
      destination_ports     = ["1194", "9000"]
    }

    rule {
      name                  = "ntp"
      protocols             = ["UDP"]
      source_addresses      = var.aks_subnet_cidrs
      destination_addresses = ["*"]
      destination_ports     = ["123"]
    }

    rule {
      name                  = "azure-monitor"
      protocols             = ["TCP"]
      source_addresses      = var.aks_subnet_cidrs
      destination_addresses = ["AzureMonitor"]
      destination_ports     = ["443"]
    }
  }

  application_rule_collection {
    name     = "aks-application"
    priority = 220
    action   = "Allow"

    rule {
      name                  = "aks-required"
      source_addresses      = var.aks_subnet_cidrs
      destination_fqdn_tags = ["AzureKubernetesService"]
      protocols {
        type = "Https"
        port = 443
      }
    }

    rule {
      name             = "registries-and-identity"
      source_addresses = var.aks_subnet_cidrs
      destination_fqdns = concat([
        "${var.acr_name}.azurecr.io",
        "${var.acr_name}.*.data.azurecr.io",
        "mcr.microsoft.com",
        "*.data.mcr.microsoft.com",
        "login.microsoftonline.com",
        "management.azure.com",
        # Argo CD reads deployment state from the GitHub repository.
        "github.com",
      ], var.extra_egress_fqdns)
      protocols {
        type = "Https"
        port = 443
      }
    }
  }
}

resource "azurerm_firewall" "hub" {
  name                = "afw-${var.name}"
  location            = var.location
  resource_group_name = var.resource_group_name
  sku_name            = "AZFW_VNet"
  sku_tier            = var.firewall_sku
  firewall_policy_id  = azurerm_firewall_policy.hub.id
  zones               = var.zones
  tags                = var.tags

  ip_configuration {
    name                 = "primary"
    subnet_id            = azurerm_subnet.firewall.id
    public_ip_address_id = azurerm_public_ip.firewall.id
  }
}

resource "azurerm_monitor_diagnostic_setting" "firewall" {
  name                           = "to-log-analytics"
  target_resource_id             = azurerm_firewall.hub.id
  log_analytics_workspace_id     = var.log_analytics_workspace_id
  log_analytics_destination_type = "Dedicated"

  enabled_log {
    category_group = "allLogs"
  }
}

# ---------- Bastion ----------

resource "azurerm_public_ip" "bastion" {
  count               = var.bastion_enabled ? 1 : 0
  name                = "pip-${var.name}-bas"
  location            = var.location
  resource_group_name = var.resource_group_name
  allocation_method   = "Static"
  sku                 = "Standard"
  zones               = var.zones
  tags                = var.tags
}

resource "azurerm_bastion_host" "hub" {
  count               = var.bastion_enabled ? 1 : 0
  name                = "bas-${var.name}"
  location            = var.location
  resource_group_name = var.resource_group_name
  sku                 = "Standard"
  tunneling_enabled   = true
  tags                = var.tags

  ip_configuration {
    name                 = "primary"
    subnet_id            = azurerm_subnet.bastion.id
    public_ip_address_id = azurerm_public_ip.bastion[0].id
  }
}

# ---------- ExpressRoute gateway (optional) ----------
# The circuit itself is ordered through the connectivity provider. Connect it with
# azurerm_virtual_network_gateway_connection once the provider has provisioned it.

resource "azurerm_public_ip" "gateway" {
  count               = var.expressroute_gateway_enabled ? 1 : 0
  name                = "pip-${var.name}-ergw"
  location            = var.location
  resource_group_name = var.resource_group_name
  allocation_method   = "Static"
  sku                 = "Standard"
  zones               = var.zones
  tags                = var.tags
}

resource "azurerm_virtual_network_gateway" "expressroute" {
  count               = var.expressroute_gateway_enabled ? 1 : 0
  name                = "ergw-${var.name}"
  location            = var.location
  resource_group_name = var.resource_group_name
  type                = "ExpressRoute"
  sku                 = "ErGw1AZ"
  tags                = var.tags

  ip_configuration {
    name                 = "primary"
    subnet_id            = azurerm_subnet.gateway.id
    public_ip_address_id = azurerm_public_ip.gateway[0].id
  }
}

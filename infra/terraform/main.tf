# FraudShield Azure infrastructure. One state file per environment.
#
#   Region "primary"   (UK South) hub, application spoke, AKS, data spoke, global services
#   Region "secondary" (UK West)  hub, application spoke, AKS  -- perf and prod only
#
# Address plan inside var.address_space (/16): each region gets a /17, split into
#   hub /22 at +0.0, application spoke /19 at +32.0, data spoke /19 at +64.0 (primary only).

locals {
  short_names = {
    uksouth = "uks"
    ukwest  = "ukw"
  }

  # Regions without availability zones. Zonal settings are left out there.
  regions_without_zones = ["ukwest"]

  region_list = var.multi_region ? {
    primary   = var.primary_location
    secondary = var.secondary_location
    } : {
    primary = var.primary_location
  }

  regions = {
    for key, location in local.region_list : key => {
      location = location
      short    = lookup(local.short_names, location, substr(location, 0, 3))
      name     = "fraudshield-${var.environment}-${lookup(local.short_names, location, substr(location, 0, 3))}"
      cidr     = cidrsubnet(var.address_space, 1, key == "primary" ? 0 : 1)
      zones    = contains(local.regions_without_zones, location) ? [] : var.zones
    }
  }

  primary   = local.regions["primary"]
  name      = "fraudshield-${var.environment}"
  stor_name = "${var.unique_prefix}fs${var.environment}"

  tags = {
    application = "fraudshield"
    environment = var.environment
    managed_by  = "terraform"
    data_class  = var.environment == "prod" ? "confidential-pci" : "non-production"
  }
}

data "azurerm_client_config" "current" {}

data "azurerm_container_registry" "shared" {
  name                = var.acr_name
  resource_group_name = var.acr_resource_group
}

# ---------- Resource groups ----------

resource "azurerm_resource_group" "region" {
  for_each = local.regions
  name     = "rg-${each.value.name}"
  location = each.value.location
  tags     = local.tags
}

resource "azurerm_resource_group" "data" {
  name     = "rg-${local.name}-data"
  location = local.primary.location
  tags     = local.tags
}

# ---------- Observability and security (primary region) ----------

module "observability" {
  source              = "./modules/observability"
  name                = local.name
  location            = local.primary.location
  resource_group_name = azurerm_resource_group.region["primary"].name
  retention_days      = var.log_retention_days
  alert_emails        = var.alert_emails
  sentinel_enabled    = var.sentinel_enabled
  tags                = local.tags
}

module "security" {
  source                     = "./modules/security"
  name                       = local.name
  key_vault_name             = "kv-${var.unique_prefix}-fs-${var.environment}"
  location                   = local.primary.location
  resource_group_name        = azurerm_resource_group.region["primary"].name
  endpoints                  = merge(local.app_endpoints, { data = local.data_endpoint })
  defender_enabled           = var.defender_enabled
  log_analytics_workspace_id = module.observability.log_analytics_workspace_id
  tags                       = local.tags
}

# ---------- Network ----------

module "hub" {
  source                       = "./modules/hub-network"
  for_each                     = local.regions
  name                         = each.value.name
  location                     = each.value.location
  resource_group_name          = azurerm_resource_group.region[each.key].name
  address_space                = cidrsubnet(each.value.cidr, 5, 0)
  zones                        = each.value.zones
  firewall_sku                 = var.firewall_sku
  aks_subnet_cidrs             = [cidrsubnet(cidrsubnet(each.value.cidr, 2, 1), 2, 0)]
  acr_name                     = var.acr_name
  bastion_enabled              = var.bastion_enabled
  expressroute_gateway_enabled = var.expressroute_gateway_enabled
  log_analytics_workspace_id   = module.observability.log_analytics_workspace_id
  tags                         = local.tags
}

module "app_spoke" {
  source                  = "./modules/app-spoke"
  for_each                = local.regions
  name                    = each.value.name
  location                = each.value.location
  resource_group_name     = azurerm_resource_group.region[each.key].name
  address_space           = cidrsubnet(each.value.cidr, 2, 1)
  firewall_private_ip     = module.hub[each.key].firewall_private_ip
  hub_vnet_id             = module.hub[each.key].vnet_id
  hub_vnet_name           = module.hub[each.key].vnet_name
  hub_resource_group_name = azurerm_resource_group.region[each.key].name
  hub_has_gateway         = module.hub[each.key].has_gateway
  channel_source_cidrs    = var.channel_source_cidrs
  tags                    = local.tags
}

module "data_spoke" {
  source                  = "./modules/data-spoke"
  name                    = local.primary.name
  location                = local.primary.location
  resource_group_name     = azurerm_resource_group.data.name
  address_space           = cidrsubnet(local.primary.cidr, 2, 2)
  hub_vnet_id             = module.hub["primary"].vnet_id
  hub_vnet_name           = module.hub["primary"].vnet_name
  hub_resource_group_name = azurerm_resource_group.region["primary"].name
  hub_has_gateway         = module.hub["primary"].has_gateway
  zones                   = length(local.primary.zones) > 0 ? [local.primary.zones[0]] : []
  tags                    = local.tags
}

# Private endpoint targets, shared by the modules below.
locals {
  app_endpoints = {
    for k, r in local.regions : k => {
      location                   = r.location
      short                      = r.short
      resource_group_name        = azurerm_resource_group.region[k].name
      private_endpoint_subnet_id = module.app_spoke[k].private_endpoint_subnet_id
      dns_zone_ids               = local.dns_zone_ids[k]
    }
  }

  data_endpoint = {
    location                   = local.primary.location
    short                      = "data"
    resource_group_name        = azurerm_resource_group.data.name
    private_endpoint_subnet_id = module.data_spoke.private_endpoint_subnet_id
    dns_zone_ids               = local.dns_zone_ids["primary"]
  }
}

# ---------- AKS ----------

module "aks" {
  source                     = "./modules/aks"
  for_each                   = local.regions
  name                       = "aks-${each.value.name}"
  location                   = each.value.location
  resource_group_name        = azurerm_resource_group.region[each.key].name
  kubernetes_version         = var.kubernetes_version
  sku_tier                   = var.aks_sku_tier
  subnet_id                  = module.app_spoke[each.key].aks_subnet_id
  vnet_id                    = module.app_spoke[each.key].vnet_id
  route_table_id             = module.app_spoke[each.key].route_table_id
  zones                      = each.value.zones
  system_node_count          = var.aks_system_node_count
  apps_vm_size               = var.aks_apps_vm_size
  apps_min_count             = var.aks_apps_min_count
  apps_max_count             = var.aks_apps_max_count
  acr_id                     = data.azurerm_container_registry.shared.id
  log_analytics_workspace_id = module.observability.log_analytics_workspace_id
  defender_enabled           = var.defender_enabled
  admin_group_object_ids     = var.aks_admin_group_object_ids
  deployer_object_id         = data.azurerm_client_config.current.object_id
  tags                       = local.tags

  # AKS validates the firewall route at creation, so the firewall must exist first.
  depends_on = [module.hub]
}

# ---------- API Management ----------

module "apim" {
  source              = "./modules/apim"
  name                = "apim-${var.unique_prefix}-${local.name}"
  location            = local.primary.location
  resource_group_name = azurerm_resource_group.region["primary"].name
  sku_name            = var.apim_sku
  zones               = startswith(var.apim_sku, "Premium") ? local.primary.zones : []
  publisher_name      = var.apim_publisher_name
  publisher_email     = var.apim_publisher_email
  subnet_id           = module.app_spoke["primary"].apim_subnet_id
  additional_locations = {
    for k, r in local.regions : k => {
      location  = r.location
      subnet_id = module.app_spoke[k].apim_subnet_id
      capacity  = var.apim_secondary_capacity
      zones     = r.zones
    } if k != "primary"
  }
  application_insights_id                = module.observability.application_insights_id
  application_insights_connection_string = module.observability.application_insights_connection_string
  log_analytics_workspace_id             = module.observability.log_analytics_workspace_id
  tags                                   = local.tags
}

# ---------- Operational data stores ----------

module "operational_data" {
  source                      = "./modules/operational-data"
  name                        = local.name
  primary_resource_group_name = azurerm_resource_group.region["primary"].name
  regions                     = local.app_endpoints
  data_spoke                  = local.data_endpoint
  cosmos_max_throughput       = var.cosmos_max_throughput
  cosmos_zone_redundant       = length(local.primary.zones) > 0 && var.environment == "prod"
  sql_sku                     = var.sql_sku
  sql_admin_group = {
    login     = var.sql_admin_group_name
    object_id = var.sql_admin_group_object_id
  }
  redis_sku                  = var.redis_sku
  event_hubs_sku             = var.event_hubs_sku
  event_hubs_capacity        = var.event_hubs_capacity
  signalr_sku                = var.signalr_sku
  log_analytics_workspace_id = module.observability.log_analytics_workspace_id
  tags                       = local.tags
}

# ---------- Lakehouse and MLOps platform ----------

module "lakehouse" {
  source              = "./modules/lakehouse"
  name                = local.name
  storage_prefix      = local.stor_name
  location            = local.primary.location
  resource_group_name = azurerm_resource_group.data.name
  replication_type    = var.environment == "prod" ? "GZRS" : "ZRS"
  data_spoke = {
    vnet_id                      = module.data_spoke.vnet_id
    host_subnet_name             = module.data_spoke.databricks_host_subnet_name
    container_subnet_name        = module.data_spoke.databricks_container_subnet_name
    host_nsg_association_id      = module.data_spoke.databricks_host_nsg_association_id
    container_nsg_association_id = module.data_spoke.databricks_container_nsg_association_id
    private_endpoint_subnet_id   = module.data_spoke.private_endpoint_subnet_id
    dns_zone_ids                 = local.dns_zone_ids["primary"]
  }
  app_regions                = local.app_endpoints
  databricks_public_access   = var.databricks_public_access
  lock_immutability          = var.lock_immutability
  log_analytics_workspace_id = module.observability.log_analytics_workspace_id
  tags                       = local.tags
}

# ---------- AI (Case Copilot) ----------

module "ai" {
  source                     = "./modules/ai"
  name                       = "${var.unique_prefix}-${local.name}"
  location                   = local.primary.location
  resource_group_name        = azurerm_resource_group.region["primary"].name
  app_regions                = local.app_endpoints
  model                      = var.ai_model
  log_analytics_workspace_id = module.observability.log_analytics_workspace_id
  tags                       = local.tags
}

# ---------- Edge (portal users) ----------

module "edge" {
  source                = "./modules/edge"
  count                 = var.origin_certificate_secret_id == "" ? 0 : 1
  name                  = local.name
  resource_group_name   = azurerm_resource_group.region["primary"].name
  waf_mode              = var.waf_mode
  origin_host_name      = var.origin_host_name
  certificate_secret_id = var.origin_certificate_secret_id
  key_vault_id          = module.security.key_vault_id
  regions = {
    for k, r in local.regions : k => {
      location            = r.location
      resource_group_name = azurerm_resource_group.region[k].name
      appgw_subnet_id     = module.app_spoke[k].appgw_subnet_id
      apim_private_ip     = module.apim.private_ips[k]
      apim_gateway_host   = module.apim.gateway_host
      portal_ingress_ip   = module.app_spoke[k].ingress_ip
      min_capacity        = var.appgw_min_capacity
      max_capacity        = var.appgw_max_capacity
      zones               = r.zones
    }
  }
  log_analytics_workspace_id = module.observability.log_analytics_workspace_id
  tags                       = local.tags
}

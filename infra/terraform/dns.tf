# Private DNS. Each region has its own copy of every zone, linked to that region's hub and spokes, so a
# private endpoint resolves to the copy in the caller's own region. This keeps two-region failover simple:
# a service in UK West always resolves to an endpoint in UK West.

locals {
  dns_zones = {
    cosmos            = "privatelink.documents.azure.com"
    sql               = "privatelink.database.windows.net"
    redis             = "privatelink.redis.azure.net"
    servicebus        = "privatelink.servicebus.windows.net"
    blob              = "privatelink.blob.core.windows.net"
    dfs               = "privatelink.dfs.core.windows.net"
    vault             = "privatelink.vaultcore.azure.net"
    databricks        = "privatelink.azuredatabricks.net"
    cognitiveservices = "privatelink.cognitiveservices.azure.com"
    openai            = "privatelink.openai.azure.com"
    aiservices        = "privatelink.services.ai.azure.com"
    signalr           = "privatelink.service.signalr.net"
    datafactory       = "privatelink.datafactory.azure.net"
    apim              = "azure-api.net"
  }

  zone_instances = {
    for pair in setproduct(keys(local.regions), keys(local.dns_zones)) :
    "${pair[0]}-${pair[1]}" => { region = pair[0], zone = pair[1] }
  }

  # Networks that resolve through each region's zones.
  zone_networks = merge(
    { for k in keys(local.regions) : "${k}-hub" => { region = k, vnet_id = module.hub[k].vnet_id } },
    { for k in keys(local.regions) : "${k}-app" => { region = k, vnet_id = module.app_spoke[k].vnet_id } },
    { "primary-data" = { region = "primary", vnet_id = module.data_spoke.vnet_id } },
  )

  zone_links = {
    for pair in setproduct(keys(local.zone_networks), keys(local.dns_zones)) :
    "${pair[0]}-${pair[1]}" => {
      region  = local.zone_networks[pair[0]].region
      zone    = pair[1]
      vnet_id = local.zone_networks[pair[0]].vnet_id
      network = pair[0]
    }
  }

  dns_zone_ids = {
    for k in keys(local.regions) : k => {
      for z in keys(local.dns_zones) : z => azurerm_private_dns_zone.this["${k}-${z}"].id
    }
  }
}

resource "azurerm_private_dns_zone" "this" {
  for_each            = local.zone_instances
  name                = local.dns_zones[each.value.zone]
  resource_group_name = azurerm_resource_group.region[each.value.region].name
  tags                = local.tags
}

resource "azurerm_private_dns_zone_virtual_network_link" "this" {
  for_each             = local.zone_links
  name                 = "link-${each.value.network}"
  private_dns_zone_id  = azurerm_private_dns_zone.this["${each.value.region}-${each.value.zone}"].id
  virtual_network_id   = each.value.vnet_id
  registration_enabled = false
  tags                 = local.tags
}

# API Management in internal mode has no automatic DNS. Each region resolves the gateway to its own
# regional private address, so channels in each region are served locally.
resource "azurerm_private_dns_a_record" "apim_gateway" {
  for_each            = local.regions
  name                = module.apim.name
  private_dns_zone_id = azurerm_private_dns_zone.this["${each.key}-apim"].id
  ttl                 = 60
  records             = [module.apim.private_ips[each.key]]
  tags                = local.tags
}

# Read by .github/scripts/publish-platform-values.sh after every apply. It writes one values file
# per region to the "releases" branch, where Argo CD (AKS) or deploy-container-apps.sh (Container Apps)
# picks it up. No secrets are included.
output "platform_values" {
  sensitive = true
  value = {
    for k, r in local.regions : k => {
      global = {
        environment                 = var.environment
        region                      = k
        location                    = r.location
        computePlatform             = var.compute_platform
        clusterName                 = local.aks ? module.aks[k].name : null
        containerAppsDomain         = local.aks ? null : module.container_apps[0].default_domain
        resourceGroup               = azurerm_resource_group.region[k].name
        ingressIp                   = module.app_spoke[k].ingress_ip
        ingressSubnet               = module.app_spoke[k].ingress_subnet_name
        registry                    = data.azurerm_container_registry.shared.login_server
        tenantId                    = data.azurerm_client_config.current.tenant_id
        keyVaultUri                 = module.security.key_vault_uri
        appInsightsConnectionString = module.observability.application_insights_connection_string
        cosmosEndpoint              = module.operational_data.cosmos_endpoint
        sqlHost                     = module.operational_data.sql_fqdn
        redisHost                   = module.operational_data.redis_hostnames[k]
        eventHubsNamespace          = "${module.operational_data.event_hubs_namespace_names[k]}.servicebus.windows.net"
        signalrHost                 = module.operational_data.signalr_hostname
        aiEndpoint                  = module.ai.endpoint
        modelStorageUrl             = "${module.lakehouse.worm_blob_endpoint}models"
        apimGatewayHost             = module.apim.gateway_host
        apiClientId                 = var.entra_api_client_id
        spaClientId                 = var.entra_spa_client_id
      }
      identities = { for s in local.services : s => azurerm_user_assigned_identity.service[s].client_id }
    }
  }
}

# Space-separated "resource-group/cluster" pairs, for the AKS_CLUSTERS variable on the environment's
# GitHub environment.
# Empty with Container Apps.
output "aks_clusters" {
  value = join(" ", [for k, c in module.aks : "${azurerm_resource_group.region[k].name}/${c.name}"])
}

output "portal_url" {
  value = (
    !local.aks ? "https://${module.apim.gateway_host}/" :
    length(module.edge) > 0 ? module.edge[0].portal_url : "edge not deployed: set origin_certificate_secret_id"
  )
}

output "front_door_id" {
  value = length(module.edge) > 0 ? module.edge[0].front_door_id : ""
}

output "apim_name" {
  value = module.apim.name
}

output "databricks_workspace_url" {
  value = module.lakehouse.databricks_workspace_url
}

output "key_vault_uri" {
  value = module.security.key_vault_uri
}

# One managed identity per service. With AKS it is federated with the cluster in every region (workload
# identity). With Container Apps it is assigned to the service's app directly. Services hold no secrets.
# Design section 12.
#
# Azure SQL access is granted inside the database (CREATE USER ... FROM EXTERNAL PROVIDER) by a
# Flyway migration run by the SQL admin group, so it is not managed here.

locals {
  # Rules and scoring run inside the decision service (no network hop, design section 17), and
  # notification is part of the case service in this release.
  services      = ["decision", "case", "strategy", "copilot"]
  k8s_namespace = "fraudshield"

  federations = !local.aks ? {} : {
    for pair in setproduct(local.services, keys(local.regions)) :
    "${pair[0]}-${pair[1]}" => { service = pair[0], region = pair[1] }
  }

  cosmos_data_contributor = "00000000-0000-0000-0000-000000000002"
  cosmos_data_reader      = "00000000-0000-0000-0000-000000000001"
}

resource "azurerm_user_assigned_identity" "service" {
  for_each            = toset(local.services)
  name                = "id-${local.name}-${each.key}"
  location            = local.primary.location
  resource_group_name = azurerm_resource_group.region["primary"].name
  tags                = local.tags
}

resource "azurerm_federated_identity_credential" "service" {
  for_each                  = local.federations
  name                      = "aks-${local.regions[each.value.region].short}"
  user_assigned_identity_id = azurerm_user_assigned_identity.service[each.value.service].id
  audience                  = ["api://AzureADTokenExchange"]
  issuer                    = module.aks[each.value.region].oidc_issuer_url
  subject                   = "system:serviceaccount:${local.k8s_namespace}:${each.value.service}"
}

# ---------- Decision: profiles, outbox, counters, and decision events ----------

resource "azurerm_cosmosdb_sql_role_assignment" "decision" {
  resource_group_name = azurerm_resource_group.region["primary"].name
  account_name        = module.operational_data.cosmos_account_name
  role_definition_id  = "${module.operational_data.cosmos_account_id}/sqlRoleDefinitions/${local.cosmos_data_contributor}"
  principal_id        = azurerm_user_assigned_identity.service["decision"].principal_id
  scope               = module.operational_data.cosmos_account_id
}

resource "azurerm_managed_redis_access_policy_assignment" "decision" {
  for_each         = module.operational_data.redis_ids
  managed_redis_id = each.value
  object_id        = azurerm_user_assigned_identity.service["decision"].principal_id
}

resource "azurerm_role_assignment" "decision_events" {
  for_each             = module.operational_data.event_hubs_namespace_ids
  scope                = each.value
  role_definition_name = "Azure Event Hubs Data Sender"
  principal_id         = azurerm_user_assigned_identity.service["decision"].principal_id
}

# ---------- Decision: reads signed models ----------
# The decision service runs the model in-process (no network hop).

resource "azurerm_role_assignment" "models" {
  for_each             = toset(["decision"])
  scope                = "${module.lakehouse.worm_account_id}/blobServices/default/containers/models"
  role_definition_name = "Storage Blob Data Reader"
  principal_id         = azurerm_user_assigned_identity.service[each.key].principal_id
}

# ---------- Case: reads decisions for evidence, publishes outcomes, pushes live updates ----------

resource "azurerm_cosmosdb_sql_role_assignment" "case" {
  resource_group_name = azurerm_resource_group.region["primary"].name
  account_name        = module.operational_data.cosmos_account_name
  role_definition_id  = "${module.operational_data.cosmos_account_id}/sqlRoleDefinitions/${local.cosmos_data_reader}"
  principal_id        = azurerm_user_assigned_identity.service["case"].principal_id
  scope               = module.operational_data.cosmos_account_id
}

resource "azurerm_role_assignment" "case_events" {
  for_each             = module.operational_data.event_hubs_namespace_ids
  scope                = each.value
  role_definition_name = "Azure Event Hubs Data Sender"
  principal_id         = azurerm_user_assigned_identity.service["case"].principal_id
}

resource "azurerm_role_assignment" "case_signalr" {
  scope                = module.operational_data.signalr_id
  role_definition_name = "SignalR App Server"
  principal_id         = azurerm_user_assigned_identity.service["case"].principal_id
}

# ---------- Copilot: language model and content safety only ----------

resource "azurerm_role_assignment" "copilot_openai" {
  scope                = module.ai.id
  role_definition_name = "Cognitive Services OpenAI User"
  principal_id         = azurerm_user_assigned_identity.service["copilot"].principal_id
}

resource "azurerm_role_assignment" "copilot_content_safety" {
  scope                = module.ai.id
  role_definition_name = "Cognitive Services User"
  principal_id         = azurerm_user_assigned_identity.service["copilot"].principal_id
}

# ---------- Every service: its own secrets in Key Vault ----------

resource "azurerm_role_assignment" "secrets" {
  for_each             = toset(local.services)
  scope                = module.security.key_vault_id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.service[each.key].principal_id
}

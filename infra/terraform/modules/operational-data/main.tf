# Low-latency and workflow stores for the decision path and case management. Design section 5.
#   Cosmos DB  - customer, device, and payee profiles, and the decisions outbox. Multi-region writes.
#   Azure SQL  - cases, alerts, rules, approvals, audit. Failover group to the second region.
#   Redis      - velocity counters. One Azure Managed Redis per region, joined by active geo-replication.
#   Event Hubs - every decision, device and login events, case outcomes. One namespace per region.
# Every store uses Entra ID only and private endpoints only.

locals {
  primary     = var.regions["primary"]
  multi       = contains(keys(var.regions), "secondary")
  short       = { for k, r in var.regions : k => r.short }
  event_hubs  = { decisions = var.decision_partitions, device-events = 16, case-outcomes = 4 }
  hub_readers = ["databricks", "outbox-monitor"]

  # Global stores need an endpoint in every application spoke and in the data spoke.
  global_endpoints = merge(
    { for k, r in var.regions : k => r },
    { data = merge(var.data_spoke, { short = "data" }) }
  )

  cosmos_containers = {
    customer-profiles = "/customerToken"
    device-profiles   = "/deviceId"
    payee-profiles    = "/payeeToken"
    decisions-outbox  = "/transactionId"
  }
}

# ---------- Cosmos DB ----------

resource "azurerm_cosmosdb_account" "this" {
  name                             = "cosmos-${var.name}"
  location                         = local.primary.location
  resource_group_name              = var.primary_resource_group_name
  offer_type                       = "Standard"
  kind                             = "GlobalDocumentDB"
  automatic_failover_enabled       = true
  multiple_write_locations_enabled = local.multi
  public_network_access_enabled    = false
  local_authentication_enabled     = false
  minimal_tls_version              = "Tls12"
  tags                             = var.tags

  consistency_policy {
    consistency_level = "Session"
  }

  dynamic "geo_location" {
    for_each = var.regions
    content {
      location          = geo_location.value.location
      failover_priority = geo_location.key == "primary" ? 0 : 1
      zone_redundant    = var.cosmos_zone_redundant && geo_location.key == "primary"
    }
  }

  backup {
    type = "Continuous"
    tier = "Continuous30Days"
  }
}

resource "azurerm_cosmosdb_sql_database" "this" {
  name                = "fraudshield"
  resource_group_name = var.primary_resource_group_name
  account_name        = azurerm_cosmosdb_account.this.name
}

resource "azurerm_cosmosdb_sql_container" "this" {
  for_each              = local.cosmos_containers
  name                  = each.key
  resource_group_name   = var.primary_resource_group_name
  account_name          = azurerm_cosmosdb_account.this.name
  database_name         = azurerm_cosmosdb_sql_database.this.name
  partition_key_paths   = [each.value]
  partition_key_version = 2
  # The outbox keeps entries for 7 days after publishing. Event history lives in Delta Lake.
  default_ttl = each.key == "decisions-outbox" ? 604800 : -1

  autoscale_settings {
    max_throughput = var.cosmos_max_throughput
  }
}

module "cosmos_endpoint" {
  source              = "../private-endpoint"
  for_each            = local.global_endpoints
  name                = "pe-cosmos-${var.name}-${each.value.short}"
  location            = each.value.location
  resource_group_name = each.value.resource_group_name
  subnet_id           = each.value.private_endpoint_subnet_id
  resource_id         = azurerm_cosmosdb_account.this.id
  subresource         = "Sql"
  dns_zone_ids        = [each.value.dns_zone_ids["cosmos"]]
  tags                = var.tags
}

# ---------- Azure SQL ----------

resource "azurerm_mssql_server" "this" {
  for_each                      = var.regions
  name                          = "sql-${var.name}-${each.value.short}"
  location                      = each.value.location
  resource_group_name           = each.value.resource_group_name
  version                       = "12.0"
  minimum_tls_version           = "1.2"
  public_network_access_enabled = false
  tags                          = var.tags

  azuread_administrator {
    login_username              = var.sql_admin_group.login
    object_id                   = var.sql_admin_group.object_id
    azuread_authentication_only = true
  }

  identity {
    type = "SystemAssigned"
  }
}

resource "azurerm_mssql_database" "cases" {
  name           = "fraudshield"
  server_id      = azurerm_mssql_server.this["primary"].id
  sku_name       = var.sql_sku
  zone_redundant = false
  tags           = var.tags

  short_term_retention_policy {
    retention_days = 35
  }

  long_term_retention_policy {
    weekly_retention  = "P12W"
    monthly_retention = "P12M"
    yearly_retention  = "P7Y"
    week_of_year      = 1
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_mssql_failover_group" "this" {
  count     = local.multi ? 1 : 0
  name      = "fog-${var.name}"
  server_id = azurerm_mssql_server.this["primary"].id
  databases = [azurerm_mssql_database.cases.id]
  tags      = var.tags

  partner_server {
    id = azurerm_mssql_server.this["secondary"].id
  }

  read_write_endpoint_failover_policy {
    mode          = "Automatic"
    grace_minutes = 60
  }
}

resource "azurerm_mssql_server_extended_auditing_policy" "this" {
  for_each               = var.regions
  server_id              = azurerm_mssql_server.this[each.key].id
  log_monitoring_enabled = true
}

# Each SQL server is reachable from every application spoke, so the failover group listener resolves
# privately in both regions after a failover.
module "sql_endpoint" {
  source = "../private-endpoint"
  for_each = {
    for pair in setproduct(keys(var.regions), keys(var.regions)) :
    "${pair[0]}-${pair[1]}" => { server = pair[0], spoke = pair[1] }
  }
  name                = "pe-sql-${var.name}-${local.short[each.value.server]}-in-${local.short[each.value.spoke]}"
  location            = var.regions[each.value.spoke].location
  resource_group_name = var.regions[each.value.spoke].resource_group_name
  subnet_id           = var.regions[each.value.spoke].private_endpoint_subnet_id
  resource_id         = azurerm_mssql_server.this[each.value.server].id
  subresource         = "sqlServer"
  dns_zone_ids        = [var.regions[each.value.spoke].dns_zone_ids["sql"]]
  tags                = var.tags
}

# ---------- Azure Managed Redis ----------

resource "azurerm_managed_redis" "this" {
  for_each                  = var.regions
  name                      = "redis-${var.name}-${each.value.short}"
  location                  = each.value.location
  resource_group_name       = each.value.resource_group_name
  sku_name                  = var.redis_sku
  high_availability_enabled = true
  public_network_access     = "Disabled"
  tags                      = var.tags

  default_database {
    access_keys_authentication_enabled = false
    clustering_policy                  = "EnterpriseCluster"
    eviction_policy                    = "NoEviction"
    geo_replication_group_name         = local.multi ? "geo-${var.name}" : null
  }
}

resource "azurerm_managed_redis_geo_replication" "this" {
  count                    = local.multi ? 1 : 0
  managed_redis_id         = azurerm_managed_redis.this["primary"].id
  linked_managed_redis_ids = [azurerm_managed_redis.this["secondary"].id]
}

module "redis_endpoint" {
  source              = "../private-endpoint"
  for_each            = var.regions
  name                = "pe-redis-${var.name}-${each.value.short}"
  location            = each.value.location
  resource_group_name = each.value.resource_group_name
  subnet_id           = each.value.private_endpoint_subnet_id
  resource_id         = azurerm_managed_redis.this[each.key].id
  subresource         = "redisEnterprise"
  dns_zone_ids        = [each.value.dns_zone_ids["redis"]]
  tags                = var.tags
}

# ---------- Event Hubs ----------

resource "azurerm_eventhub_namespace" "this" {
  for_each                      = var.regions
  name                          = "evhns-${var.name}-${each.value.short}"
  location                      = each.value.location
  resource_group_name           = each.value.resource_group_name
  sku                           = var.event_hubs_sku
  capacity                      = var.event_hubs_capacity
  local_authentication_enabled  = false
  public_network_access_enabled = false
  minimum_tls_version           = "1.2"
  tags                          = var.tags
}

resource "azurerm_eventhub" "this" {
  for_each = {
    for pair in setproduct(keys(var.regions), keys(local.event_hubs)) :
    "${pair[0]}-${pair[1]}" => { region = pair[0], hub = pair[1] }
  }
  name              = each.value.hub
  namespace_id      = azurerm_eventhub_namespace.this[each.value.region].id
  partition_count   = local.event_hubs[each.value.hub]
  message_retention = var.event_hubs_sku == "Premium" ? 7 : 1
}

resource "azurerm_eventhub_consumer_group" "this" {
  for_each = {
    for t in setproduct(keys(var.regions), keys(local.event_hubs), local.hub_readers) :
    "${t[0]}-${t[1]}-${t[2]}" => { region = t[0], hub = t[1], reader = t[2] }
  }
  name                = each.value.reader
  namespace_name      = azurerm_eventhub_namespace.this[each.value.region].name
  eventhub_name       = azurerm_eventhub.this["${each.value.region}-${each.value.hub}"].name
  resource_group_name = var.regions[each.value.region].resource_group_name
}

# Event schemas are contracts (design section 14). Producers register them here.
resource "azurerm_eventhub_namespace_schema_group" "this" {
  for_each             = var.regions
  name                 = "fraudshield"
  namespace_id         = azurerm_eventhub_namespace.this[each.key].id
  schema_compatibility = "Backward"
  schema_type          = "Avro"
}

module "event_hubs_endpoint" {
  source              = "../private-endpoint"
  for_each            = var.regions
  name                = "pe-evh-${var.name}-${each.value.short}"
  location            = each.value.location
  resource_group_name = each.value.resource_group_name
  subnet_id           = each.value.private_endpoint_subnet_id
  resource_id         = azurerm_eventhub_namespace.this[each.key].id
  subresource         = "namespace"
  dns_zone_ids        = [each.value.dns_zone_ids["servicebus"]]
  tags                = var.tags
}

# Databricks streams decisions from every region.
module "event_hubs_data_endpoint" {
  source              = "../private-endpoint"
  for_each            = var.regions
  name                = "pe-evh-${var.name}-${each.value.short}-in-data"
  location            = var.data_spoke.location
  resource_group_name = var.data_spoke.resource_group_name
  subnet_id           = var.data_spoke.private_endpoint_subnet_id
  resource_id         = azurerm_eventhub_namespace.this[each.key].id
  subresource         = "namespace"
  dns_zone_ids        = [var.data_spoke.dns_zone_ids["servicebus"]]
  tags                = var.tags
}

# ---------- SignalR (live alert updates) and Communication Services (notifications) ----------

resource "azurerm_signalr_service" "this" {
  name                          = "sigr-${var.name}"
  location                      = local.primary.location
  resource_group_name           = var.primary_resource_group_name
  service_mode                  = "Default"
  local_auth_enabled            = false
  aad_auth_enabled              = true
  public_network_access_enabled = true
  tags                          = var.tags

  sku {
    name     = var.signalr_sku
    capacity = 1
  }
}

module "signalr_endpoint" {
  source              = "../private-endpoint"
  for_each            = var.regions
  name                = "pe-sigr-${var.name}-${each.value.short}"
  location            = each.value.location
  resource_group_name = each.value.resource_group_name
  subnet_id           = each.value.private_endpoint_subnet_id
  resource_id         = azurerm_signalr_service.this.id
  subresource         = "signalr"
  dns_zone_ids        = [each.value.dns_zone_ids["signalr"]]
  tags                = var.tags
}

# Browsers may only open client connections publicly. Server and REST calls must use the private endpoints.
resource "azurerm_signalr_service_network_acl" "this" {
  signalr_service_id = azurerm_signalr_service.this.id
  default_action     = "Deny"

  public_network {
    allowed_request_types = ["ClientConnection"]
  }

  dynamic "private_endpoint" {
    for_each = module.signalr_endpoint
    content {
      id                    = private_endpoint.value.id
      allowed_request_types = ["ClientConnection", "ServerConnection", "RESTAPI"]
    }
  }
}

resource "azurerm_communication_service" "this" {
  name                = "acs-${var.name}"
  resource_group_name = var.primary_resource_group_name
  data_location       = "UK"
  tags                = var.tags
}

# ---------- Diagnostics ----------

resource "azurerm_monitor_diagnostic_setting" "cosmos" {
  name                           = "to-log-analytics"
  target_resource_id             = azurerm_cosmosdb_account.this.id
  log_analytics_workspace_id     = var.log_analytics_workspace_id
  log_analytics_destination_type = "Dedicated"

  enabled_log {
    category_group = "allLogs"
  }
}

resource "azurerm_monitor_diagnostic_setting" "event_hubs" {
  for_each                   = var.regions
  name                       = "to-log-analytics"
  target_resource_id         = azurerm_eventhub_namespace.this[each.key].id
  log_analytics_workspace_id = var.log_analytics_workspace_id

  enabled_log {
    category_group = "allLogs"
  }
}

output "cosmos_account_id" {
  value = azurerm_cosmosdb_account.this.id
}

output "cosmos_account_name" {
  value = azurerm_cosmosdb_account.this.name
}

output "cosmos_endpoint" {
  value = azurerm_cosmosdb_account.this.endpoint
}

output "sql_fqdn" {
  description = "The failover group listener when there are two regions, otherwise the server."
  value = (
    local.multi
    ? "${azurerm_mssql_failover_group.this[0].name}.database.windows.net"
    : azurerm_mssql_server.this["primary"].fully_qualified_domain_name
  )
}

output "redis_ids" {
  value = { for k, r in azurerm_managed_redis.this : k => r.id }
}

output "redis_hostnames" {
  value = { for k, r in azurerm_managed_redis.this : k => r.hostname }
}

output "event_hubs_namespace_ids" {
  value = { for k, n in azurerm_eventhub_namespace.this : k => n.id }
}

output "event_hubs_namespace_names" {
  value = { for k, n in azurerm_eventhub_namespace.this : k => n.name }
}

output "signalr_id" {
  value = azurerm_signalr_service.this.id
}

output "signalr_hostname" {
  value = azurerm_signalr_service.this.hostname
}

output "communication_service_id" {
  value = azurerm_communication_service.this.id
}

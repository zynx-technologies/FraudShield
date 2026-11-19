output "vnet_id" {
  value = azurerm_virtual_network.hub.id
}

output "vnet_name" {
  value = azurerm_virtual_network.hub.name
}

# Null when the firewall is turned off.
output "firewall_private_ip" {
  value = var.firewall_enabled ? azurerm_firewall.hub[0].ip_configuration[0].private_ip_address : null
}

output "has_gateway" {
  value = var.expressroute_gateway_enabled
}

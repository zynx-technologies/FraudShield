output "vnet_id" {
  value = azurerm_virtual_network.spoke.id
}

output "aks_subnet_id" {
  # Depend on the route table so AKS is never created before its egress route exists.
  value = azurerm_subnet_route_table_association.aks.subnet_id
}

output "aks_subnet_cidr" {
  value = local.aks_cidr
}

output "route_table_id" {
  value = azurerm_route_table.aks.id
}

output "ingress_subnet_name" {
  value = azurerm_subnet.ingress.name
}

output "ingress_subnet_id" {
  value = azurerm_subnet_network_security_group_association.ingress.subnet_id
}

# A fixed address for the internal ingress controller, so Application Gateway and API Management can target it.
output "ingress_ip" {
  value = cidrhost(local.ingress_cidr, 4)
}

output "apim_subnet_id" {
  value = azurerm_subnet_network_security_group_association.apim.subnet_id
}

output "appgw_subnet_id" {
  value = azurerm_subnet_network_security_group_association.appgw.subnet_id
}

output "private_endpoint_subnet_id" {
  value = azurerm_subnet.private_endpoints.id
}

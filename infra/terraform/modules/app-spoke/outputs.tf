output "vnet_id" {
  value = azurerm_virtual_network.spoke.id
}

output "apim_subnet_id" {
  value = azurerm_subnet_network_security_group_association.apim.subnet_id
}

output "private_endpoint_subnet_id" {
  value = azurerm_subnet.private_endpoints.id
}

# ---------- aks mode (null in container_apps mode) ----------

output "aks_subnet_id" {
  # Depend on the route table so AKS is never created before its egress route exists.
  value = local.aks ? azurerm_subnet_route_table_association.aks[0].subnet_id : null
}

output "aks_subnet_cidr" {
  value = local.aks ? local.aks_cidr : null
}

output "route_table_id" {
  value = local.aks ? azurerm_route_table.aks[0].id : null
}

output "ingress_subnet_name" {
  value = local.aks ? azurerm_subnet.ingress[0].name : null
}

output "ingress_subnet_id" {
  value = local.aks ? azurerm_subnet_network_security_group_association.ingress[0].subnet_id : null
}

# A fixed address for the internal ingress controller, so Application Gateway and API Management can target it.
output "ingress_ip" {
  value = local.aks ? cidrhost(local.ingress_cidr, 4) : null
}

output "appgw_subnet_id" {
  value = local.aks ? azurerm_subnet_network_security_group_association.appgw[0].subnet_id : null
}

# ---------- container_apps mode (null in aks mode) ----------

output "container_apps_subnet_id" {
  # Depends on the NSG and NAT gateway, so the environment is never created without its rules and egress.
  value = local.container_apps ? azurerm_subnet_nat_gateway_association.apps[0].subnet_id : null
}

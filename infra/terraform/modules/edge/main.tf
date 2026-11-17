# Front Door Premium + WAF for portal users, with one Application Gateway per region as the origin.
# Application Gateway sends /api/* to the internal API Management gateway, and everything else to the
# portal on the internal AKS ingress. Payment channels never use this path. Design sections 3 and 11.
#
# Front Door checks the origin certificate, so each Application Gateway serves a certificate for
# origin_host_name from Key Vault. Issue it before enabling this module.

variable "name" {
  type = string
}

variable "resource_group_name" {
  type = string
}

variable "waf_mode" {
  type    = string
  default = "Prevention"
}

variable "origin_host_name" {
  description = "Host name on the origin certificate, for example portal-origin.fraudshield.example.com."
  type        = string
}

variable "certificate_secret_id" {
  description = "Versionless Key Vault secret ID of the origin certificate."
  type        = string
}

variable "key_vault_id" {
  type = string
}

variable "regions" {
  type = map(object({
    location            = string
    resource_group_name = string
    appgw_subnet_id     = string
    apim_private_ip     = string
    apim_gateway_host   = string
    portal_ingress_ip   = string
    min_capacity        = number
    max_capacity        = number
    zones               = list(string)
  }))
}

variable "log_analytics_workspace_id" {
  type = string
}

variable "tags" {
  type = map(string)
}

# ---------- Application Gateway per region ----------

resource "azurerm_user_assigned_identity" "appgw" {
  for_each            = var.regions
  name                = "id-${var.name}-appgw-${each.key}"
  location            = each.value.location
  resource_group_name = each.value.resource_group_name
  tags                = var.tags
}

resource "azurerm_role_assignment" "appgw_certificate" {
  for_each             = var.regions
  scope                = var.key_vault_id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.appgw[each.key].principal_id
}

resource "azurerm_public_ip" "appgw" {
  for_each            = var.regions
  name                = "pip-${var.name}-appgw-${each.key}"
  location            = each.value.location
  resource_group_name = each.value.resource_group_name
  allocation_method   = "Static"
  sku                 = "Standard"
  zones               = length(each.value.zones) > 0 ? each.value.zones : null
  domain_name_label   = "${var.name}-${each.key}"
  tags                = var.tags
}

resource "azurerm_application_gateway" "this" {
  for_each            = var.regions
  name                = "agw-${var.name}-${each.key}"
  location            = each.value.location
  resource_group_name = each.value.resource_group_name
  zones               = length(each.value.zones) > 0 ? each.value.zones : null
  tags                = var.tags

  sku {
    name = "Standard_v2"
    tier = "Standard_v2"
  }

  autoscale_configuration {
    min_capacity = each.value.min_capacity
    max_capacity = each.value.max_capacity
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.appgw[each.key].id]
  }

  ssl_policy {
    policy_type = "Predefined"
    policy_name = "AppGwSslPolicy20220101S"
  }

  gateway_ip_configuration {
    name      = "gateway"
    subnet_id = each.value.appgw_subnet_id
  }

  frontend_ip_configuration {
    name                 = "public"
    public_ip_address_id = azurerm_public_ip.appgw[each.key].id
  }

  frontend_port {
    name = "https"
    port = 443
  }

  ssl_certificate {
    name                = "origin"
    key_vault_secret_id = var.certificate_secret_id
  }

  http_listener {
    name                           = "https"
    frontend_ip_configuration_name = "public"
    frontend_port_name             = "https"
    protocol                       = "Https"
    ssl_certificate_name           = "origin"
    host_name                      = var.origin_host_name
  }

  backend_address_pool {
    name         = "apim"
    ip_addresses = [each.value.apim_private_ip]
  }

  backend_address_pool {
    name         = "portal"
    ip_addresses = [each.value.portal_ingress_ip]
  }

  probe {
    name                = "apim"
    protocol            = "Https"
    host                = each.value.apim_gateway_host
    path                = "/status-0123456789abcdef"
    interval            = 15
    timeout             = 10
    unhealthy_threshold = 3
  }

  probe {
    name                = "portal"
    protocol            = "Http"
    host                = var.origin_host_name
    path                = "/healthz"
    interval            = 15
    timeout             = 10
    unhealthy_threshold = 3
  }

  backend_http_settings {
    name                  = "apim"
    protocol              = "Https"
    port                  = 443
    host_name             = each.value.apim_gateway_host
    probe_name            = "apim"
    request_timeout       = 30
    cookie_based_affinity = "Disabled"
  }

  # The portal serves static files only. API calls, which carry case data, go to API Management over TLS.
  backend_http_settings {
    name                  = "portal"
    protocol              = "Http"
    port                  = 80
    host_name             = var.origin_host_name
    probe_name            = "portal"
    request_timeout       = 30
    cookie_based_affinity = "Disabled"
  }

  url_path_map {
    name                               = "routes"
    default_backend_address_pool_name  = "portal"
    default_backend_http_settings_name = "portal"

    path_rule {
      name                       = "api"
      paths                      = ["/api/*"]
      backend_address_pool_name  = "apim"
      backend_http_settings_name = "apim"
    }
  }

  request_routing_rule {
    name               = "routes"
    rule_type          = "PathBasedRouting"
    http_listener_name = "https"
    url_path_map_name  = "routes"
    priority           = 100
  }

  depends_on = [azurerm_role_assignment.appgw_certificate]
}

resource "azurerm_monitor_diagnostic_setting" "appgw" {
  for_each                       = var.regions
  name                           = "to-log-analytics"
  target_resource_id             = azurerm_application_gateway.this[each.key].id
  log_analytics_workspace_id     = var.log_analytics_workspace_id
  log_analytics_destination_type = "Dedicated"

  enabled_log {
    category_group = "allLogs"
  }
}

# ---------- Front Door Premium and WAF ----------

resource "azurerm_cdn_frontdoor_profile" "this" {
  name                     = "afd-${var.name}"
  resource_group_name      = var.resource_group_name
  sku_name                 = "Premium_AzureFrontDoor"
  response_timeout_seconds = 60
  tags                     = var.tags
}

resource "azurerm_cdn_frontdoor_firewall_policy" "this" {
  name                = replace("waf${var.name}", "-", "")
  resource_group_name = var.resource_group_name
  sku_name            = azurerm_cdn_frontdoor_profile.this.sku_name
  enabled             = true
  mode                = var.waf_mode
  tags                = var.tags

  managed_rule {
    type    = "Microsoft_DefaultRuleSet"
    version = "2.1"
    action  = "Block"
  }

  managed_rule {
    type    = "Microsoft_BotManagerRuleSet"
    version = "1.1"
    action  = "Block"
  }

  # UK bank staff only. Widen if analysts or support teams work from other countries.
  custom_rule {
    name     = "AllowUnitedKingdomOnly"
    enabled  = true
    priority = 100
    type     = "MatchRule"
    action   = "Block"

    match_condition {
      match_variable     = "SocketAddr"
      operator           = "GeoMatch"
      negation_condition = true
      match_values       = ["GB"]
    }
  }
}

resource "azurerm_cdn_frontdoor_endpoint" "portal" {
  name                     = "fde-${var.name}"
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.this.id
  tags                     = var.tags
}

resource "azurerm_cdn_frontdoor_origin_group" "portal" {
  name                     = "portal"
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.this.id
  session_affinity_enabled = false

  load_balancing {
    sample_size                 = 4
    successful_samples_required = 3
  }

  health_probe {
    protocol            = "Https"
    path                = "/healthz"
    request_type        = "GET"
    interval_in_seconds = 30
  }
}

resource "azurerm_cdn_frontdoor_origin" "appgw" {
  for_each                       = var.regions
  name                           = "appgw-${each.key}"
  cdn_frontdoor_origin_group_id  = azurerm_cdn_frontdoor_origin_group.portal.id
  enabled                        = true
  host_name                      = azurerm_public_ip.appgw[each.key].fqdn
  origin_host_header             = var.origin_host_name
  https_port                     = 443
  http_port                      = 80
  priority                       = 1
  weight                         = 500
  certificate_name_check_enabled = true
}

resource "azurerm_cdn_frontdoor_route" "portal" {
  name                          = "portal"
  cdn_frontdoor_endpoint_id     = azurerm_cdn_frontdoor_endpoint.portal.id
  cdn_frontdoor_origin_group_id = azurerm_cdn_frontdoor_origin_group.portal.id
  cdn_frontdoor_origin_ids      = [for o in azurerm_cdn_frontdoor_origin.appgw : o.id]
  supported_protocols           = ["Https"]
  https_redirect_enabled        = false
  patterns_to_match             = ["/*"]
  forwarding_protocol           = "HttpsOnly"
  link_to_default_domain        = true
}

resource "azurerm_cdn_frontdoor_security_policy" "waf" {
  name                     = "waf"
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.this.id

  security_policies {
    firewall {
      cdn_frontdoor_firewall_policy_id = azurerm_cdn_frontdoor_firewall_policy.this.id

      association {
        patterns_to_match = ["/*"]
        domain {
          cdn_frontdoor_domain_id = azurerm_cdn_frontdoor_endpoint.portal.id
        }
      }
    }
  }
}

resource "azurerm_monitor_diagnostic_setting" "front_door" {
  name                           = "to-log-analytics"
  target_resource_id             = azurerm_cdn_frontdoor_profile.this.id
  log_analytics_workspace_id     = var.log_analytics_workspace_id
  log_analytics_destination_type = "Dedicated"

  enabled_log {
    category_group = "allLogs"
  }
}

output "portal_url" {
  value = "https://${azurerm_cdn_frontdoor_endpoint.portal.host_name}"
}

output "front_door_id" {
  description = "API Management policies check the X-Azure-FDID header against this value."
  value       = azurerm_cdn_frontdoor_profile.this.resource_guid
}

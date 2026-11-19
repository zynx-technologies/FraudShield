# Low-cost compute for dev (compute_platform = "container_apps"): Azure Container Apps instead of AKS, with
# API Management in external VNet mode as the only public entry point, in place of Front Door and
# Application Gateway.
#
#   Internet ──► API Management (external, app spoke) ─┬─ /api/v1/* ──► decision, case, strategy, copilot
#                                                      └─ /*        ──► portal
#
# The environment is internal: apps are reachable only inside the VNet, and the subnet's NSG lets in only
# API Management. Each service runs as its own user-assigned identity, the same one AKS workload identity
# used, so access grants do not change.
#
# Terraform owns the shape of each app (identity, ingress, size, probes, scaling). The image and the
# environment variables belong to .github/scripts/deploy-container-apps.sh, which renders them from the
# releases branch, just as Argo CD does for AKS. Until the first release, each app runs a placeholder image.

variable "name" {
  description = "Base name, for example fraudshield-dev-uks."
  type        = string
}

variable "location" {
  type = string
}

variable "resource_group_name" {
  type = string
}

variable "subnet_id" {
  description = "Subnet delegated to Microsoft.App/environments (/23 or larger)."
  type        = string
}

variable "vnet_id" {
  description = "Application spoke. API Management resolves the environment's domain through a zone linked here."
  type        = string
}

variable "log_analytics_workspace_id" {
  type = string
}

variable "acr_id" {
  type = string
}

variable "registry_server" {
  type = string
}

variable "service_identities" {
  description = "User-assigned identity of each service, keyed by service name."
  type = map(object({
    id        = string
    client_id = string
  }))
}

variable "apps" {
  description = "Size and scale of each app. Consumption profile CPU and memory pairs: 0.25/0.5Gi, 0.5/1Gi, 1/2Gi, 2/4Gi."
  type = map(object({
    kind         = string # java | static
    cpu          = number
    memory       = string
    min_replicas = number
    max_replicas = number
  }))
  default = {
    # One warm replica each: a JVM started from zero would time out the first request, and decision is not
    # ready until it has loaded the rules from strategy. An idle replica is billed at the much lower idle rate.
    decision = { kind = "java", cpu = 0.5, memory = "1Gi", min_replicas = 1, max_replicas = 2 }
    case     = { kind = "java", cpu = 0.5, memory = "1Gi", min_replicas = 1, max_replicas = 2 }
    strategy = { kind = "java", cpu = 0.5, memory = "1Gi", min_replicas = 1, max_replicas = 2 }
    copilot  = { kind = "java", cpu = 0.5, memory = "1Gi", min_replicas = 1, max_replicas = 2 }
    portal   = { kind = "static", cpu = 0.25, memory = "0.5Gi", min_replicas = 1, max_replicas = 2 }
  }
}

variable "apim" {
  description = "API Management instance that routes to the apps."
  type = object({
    name                = string
    resource_group_name = string
  })
}

variable "tags" {
  type = map(string)
}

locals {
  placeholder_image = "mcr.microsoft.com/k8se/quickstart:latest"
  domain            = azurerm_container_app_environment.this.default_domain

  # API paths served by each service. Keep in line with ingress.paths in deploy/gitops/services.
  routes = {
    decision = ["decisions"]
    case     = ["alerts", "cases", "overview"]
    strategy = ["rules", "thresholds", "models"]
    copilot  = ["copilot"]
  }

  methods = ["GET", "POST", "PUT", "PATCH", "DELETE"]

  # "/" matches the collection itself (POST /api/v1/decisions), "/*" everything below it.
  api_operations = {
    for pair in setproduct(local.methods, ["/", "/*"]) :
    "${lower(pair[0])}-${pair[1] == "/" ? "root" : "any"}" => { method = pair[0], url_template = pair[1] }
  }

  route_conditions = {
    for service, paths in local.routes : service => join(" || ", flatten([
      for p in paths : [
        "context.Request.OriginalUrl.Path == \"/api/v1/${p}\"",
        "context.Request.OriginalUrl.Path.StartsWith(\"/api/v1/${p}/\")",
      ]
    ]))
  }
}

# ---------- Environment ----------

resource "azurerm_container_app_environment" "this" {
  name                               = "cae-${var.name}"
  location                           = var.location
  resource_group_name                = var.resource_group_name
  infrastructure_subnet_id           = var.subnet_id
  infrastructure_resource_group_name = "rg-${var.name}-cae"
  internal_load_balancer_enabled     = true
  zone_redundancy_enabled            = false
  logs_destination                   = "log-analytics"
  log_analytics_workspace_id         = var.log_analytics_workspace_id
  tags                               = var.tags

  # A workload profiles environment, so egress can use the subnet's NAT gateway. Apps run on the
  # Consumption profile (billed per second, idle replicas at the idle rate).
  workload_profile {
    name                  = "Consumption"
    workload_profile_type = "Consumption"
  }
}

# An internal environment has no public DNS. API Management, in the same spoke, resolves app names here.
resource "azurerm_private_dns_zone" "environment" {
  name                = local.domain
  resource_group_name = var.resource_group_name
  tags                = var.tags
}

resource "azurerm_private_dns_zone_virtual_network_link" "environment" {
  name                 = "link-app"
  private_dns_zone_id  = azurerm_private_dns_zone.environment.id
  virtual_network_id   = var.vnet_id
  registration_enabled = false
  tags                 = var.tags
}

resource "azurerm_private_dns_a_record" "wildcard" {
  for_each            = toset(["*", "@"])
  name                = each.key
  private_dns_zone_id = azurerm_private_dns_zone.environment.id
  ttl                 = 300
  records             = [azurerm_container_app_environment.this.static_ip_address]
  tags                = var.tags
}

# ---------- Apps ----------

# Pulls images from the shared registry for every app, including the portal, which has no service identity.
resource "azurerm_user_assigned_identity" "pull" {
  name                = "id-${var.name}-acrpull"
  location            = var.location
  resource_group_name = var.resource_group_name
  tags                = var.tags
}

resource "azurerm_role_assignment" "pull" {
  scope                = var.acr_id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_user_assigned_identity.pull.principal_id
}

resource "azurerm_container_app" "this" {
  for_each                     = var.apps
  name                         = each.key
  container_app_environment_id = azurerm_container_app_environment.this.id
  resource_group_name          = var.resource_group_name
  revision_mode                = "Single"
  workload_profile_name        = "Consumption"
  max_inactive_revisions       = 5
  tags                         = var.tags

  identity {
    type = "UserAssigned"
    identity_ids = compact([
      azurerm_user_assigned_identity.pull.id,
      try(var.service_identities[each.key].id, ""),
    ])
  }

  registry {
    server   = var.registry_server
    identity = azurerm_user_assigned_identity.pull.id
  }

  # In an internal environment, "external" means reachable from the VNet (API Management), not the internet.
  ingress {
    external_enabled           = true
    target_port                = 8080
    transport                  = "auto"
    allow_insecure_connections = false

    traffic_weight {
      latest_revision = true
      percentage      = 100
    }
  }

  template {
    min_replicas = each.value.min_replicas
    max_replicas = each.value.max_replicas

    container {
      name   = each.key
      image  = local.placeholder_image
      cpu    = each.value.cpu
      memory = each.value.memory

      # Spring Boot serves health on the management port. The JVM may take a minute or two to start.
      startup_probe {
        transport               = "HTTP"
        port                    = each.value.kind == "java" ? 8081 : 8080
        path                    = each.value.kind == "java" ? "/actuator/health/liveness" : "/healthz"
        initial_delay           = 10
        interval_seconds        = 15
        failure_count_threshold = 10
      }

      liveness_probe {
        transport               = "HTTP"
        port                    = each.value.kind == "java" ? 8081 : 8080
        path                    = each.value.kind == "java" ? "/actuator/health/liveness" : "/healthz"
        interval_seconds        = 10
        failure_count_threshold = 3
      }

      readiness_probe {
        transport               = "HTTP"
        port                    = each.value.kind == "java" ? 8081 : 8080
        path                    = each.value.kind == "java" ? "/actuator/health/readiness" : "/healthz"
        interval_seconds        = 5
        failure_count_threshold = 3
      }
    }

    http_scale_rule {
      name                = "http"
      concurrent_requests = "50"
    }
  }

  lifecycle {
    # Set by deploy-container-apps.sh on every release.
    ignore_changes = [
      template[0].container[0].image,
      template[0].container[0].env,
      template[0].revision_suffix,
    ]
  }

  depends_on = [azurerm_role_assignment.pull]
}

# ---------- API Management routes ----------

resource "azurerm_api_management_api" "api" {
  name                  = "fraudshield-api"
  api_management_name   = var.apim.name
  resource_group_name   = var.apim.resource_group_name
  revision              = "1"
  display_name          = "FraudShield API"
  path                  = "api/v1"
  protocols             = ["https"]
  service_url           = "https://decision.${local.domain}/api/v1"
  subscription_required = false # every service checks the caller's Entra token
}

resource "azurerm_api_management_api_operation" "api" {
  for_each            = local.api_operations
  operation_id        = each.key
  api_name            = azurerm_api_management_api.api.name
  api_management_name = var.apim.name
  resource_group_name = var.apim.resource_group_name
  display_name        = "${each.value.method} ${each.value.url_template}"
  method              = each.value.method
  url_template        = each.value.url_template
}

# Sends each path to the service that owns it, like the AKS ingress rules.
resource "azurerm_api_management_api_policy" "api" {
  api_name            = azurerm_api_management_api.api.name
  api_management_name = var.apim.name
  resource_group_name = var.apim.resource_group_name

  xml_content = <<-XML
    <policies>
      <inbound>
        <base />
        <choose>
    %{~for service, condition in local.route_conditions}
          <when condition="@(${condition})">
            <set-backend-service base-url="https://${service}.${local.domain}/api/v1" />
          </when>
    %{~endfor}
          <otherwise>
            <return-response>
              <set-status code="404" reason="Not Found" />
            </return-response>
          </otherwise>
        </choose>
      </inbound>
      <backend>
        <base />
      </backend>
      <outbound>
        <base />
      </outbound>
      <on-error>
        <base />
      </on-error>
    </policies>
  XML
}

# The portal is served from the same host as the API, so its calls to /api/v1 stay same-origin.
resource "azurerm_api_management_api" "portal" {
  name                  = "portal"
  api_management_name   = var.apim.name
  resource_group_name   = var.apim.resource_group_name
  revision              = "1"
  display_name          = "Investigator portal"
  path                  = ""
  protocols             = ["https"]
  service_url           = "https://portal.${local.domain}"
  subscription_required = false
}

resource "azurerm_api_management_api_operation" "portal" {
  for_each            = { root = "/", any = "/*" }
  operation_id        = "get-${each.key}"
  api_name            = azurerm_api_management_api.portal.name
  api_management_name = var.apim.name
  resource_group_name = var.apim.resource_group_name
  display_name        = "GET ${each.value}"
  method              = "GET"
  url_template        = each.value
}

output "environment_id" {
  value = azurerm_container_app_environment.this.id
}

output "default_domain" {
  value = local.domain
}

output "app_names" {
  value = keys(azurerm_container_app.this)
}

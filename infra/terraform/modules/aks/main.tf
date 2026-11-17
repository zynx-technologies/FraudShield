# Private AKS cluster for the FraudShield services. Design sections 3, 11, and 13.
# Entra-only access (local accounts off), workload identity, KEDA, Key Vault CSI, Cilium network policy,
# and egress only through the hub firewall.

variable "name" {
  type = string
}

variable "location" {
  type = string
}

variable "resource_group_name" {
  type = string
}

variable "kubernetes_version" {
  description = "Null takes the region default. Pin it in production."
  type        = string
  default     = null
}

variable "sku_tier" {
  type    = string
  default = "Standard"
}

variable "subnet_id" {
  type = string
}

variable "vnet_id" {
  description = "Spoke VNet. The cluster identity needs it to place the internal ingress load balancer."
  type        = string
}

variable "route_table_id" {
  type = string
}

variable "zones" {
  type    = list(string)
  default = ["1", "2", "3"]
}

variable "system_vm_size" {
  type    = string
  default = "Standard_D4ds_v5"
}

variable "system_node_count" {
  type    = number
  default = 3
}

variable "apps_vm_size" {
  description = "Decision-path pods need consistent CPU. Use compute-optimised sizes in perf and prod."
  type        = string
  default     = "Standard_D8ds_v5"
}

variable "apps_min_count" {
  type    = number
  default = 2
}

variable "apps_max_count" {
  type    = number
  default = 10
}

variable "acr_id" {
  type = string
}

variable "log_analytics_workspace_id" {
  type = string
}

variable "defender_enabled" {
  type    = bool
  default = false
}

variable "admin_group_object_ids" {
  description = "Entra groups given cluster admin. Keep empty and use PIM-activated groups in production."
  type        = list(string)
  default     = []
}

variable "deployer_object_id" {
  description = "The pipeline identity. It deploys through 'az aks command invoke'."
  type        = string
}

variable "tags" {
  type = map(string)
}

data "azurerm_client_config" "current" {}

resource "azurerm_user_assigned_identity" "cluster" {
  name                = "id-${var.name}"
  location            = var.location
  resource_group_name = var.resource_group_name
  tags                = var.tags
}

resource "azurerm_role_assignment" "cluster_network" {
  scope                = var.vnet_id
  role_definition_name = "Network Contributor"
  principal_id         = azurerm_user_assigned_identity.cluster.principal_id
}

resource "azurerm_role_assignment" "cluster_route_table" {
  scope                = var.route_table_id
  role_definition_name = "Network Contributor"
  principal_id         = azurerm_user_assigned_identity.cluster.principal_id
}

resource "azurerm_kubernetes_cluster" "this" {
  name                         = var.name
  location                     = var.location
  resource_group_name          = var.resource_group_name
  dns_prefix                   = var.name
  kubernetes_version           = var.kubernetes_version
  sku_tier                     = var.sku_tier
  private_cluster_enabled      = true
  private_dns_zone_id          = "System"
  local_account_disabled       = true
  oidc_issuer_enabled          = true
  workload_identity_enabled    = true
  azure_policy_enabled         = true
  image_cleaner_enabled        = true
  image_cleaner_interval_hours = 48
  automatic_upgrade_channel    = "patch"
  node_os_upgrade_channel      = "NodeImage"
  run_command_enabled          = true
  tags                         = var.tags

  default_node_pool {
    name                         = "system"
    vm_size                      = var.system_vm_size
    node_count                   = var.system_node_count
    vnet_subnet_id               = var.subnet_id
    zones                        = var.zones
    only_critical_addons_enabled = true
    os_sku                       = "AzureLinux"
    temporary_name_for_rotation  = "systemtmp"

    upgrade_settings {
      max_surge = "33%"
    }
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.cluster.id]
  }

  azure_active_directory_role_based_access_control {
    azure_rbac_enabled     = true
    tenant_id              = data.azurerm_client_config.current.tenant_id
    admin_group_object_ids = var.admin_group_object_ids
  }

  network_profile {
    network_plugin      = "azure"
    network_plugin_mode = "overlay"
    network_data_plane  = "cilium"
    network_policy      = "cilium"
    outbound_type       = "userDefinedRouting"
    load_balancer_sku   = "standard"
    pod_cidr            = "192.168.0.0/16"
    service_cidr        = "172.16.0.0/16"
    dns_service_ip      = "172.16.0.10"
  }

  # Node pools are managed explicitly; node auto-provisioning is off.
  node_provisioning_profile {
    mode = "Manual"
  }

  workload_autoscaler_profile {
    keda_enabled = true
  }

  key_vault_secrets_provider {
    secret_rotation_enabled = true
  }

  oms_agent {
    log_analytics_workspace_id      = var.log_analytics_workspace_id
    msi_auth_for_monitoring_enabled = true
  }

  dynamic "microsoft_defender" {
    for_each = var.defender_enabled ? [1] : []
    content {
      log_analytics_workspace_id = var.log_analytics_workspace_id
    }
  }

  maintenance_window_auto_upgrade {
    frequency   = "Weekly"
    interval    = 1
    duration    = 4
    day_of_week = "Sunday"
    start_time  = "02:00"
    utc_offset  = "+00:00" # 02:00 UK time in winter, 03:00 during British Summer Time
  }

  maintenance_window_node_os {
    frequency   = "Weekly"
    interval    = 1
    duration    = 4
    day_of_week = "Sunday"
    start_time  = "02:00"
    utc_offset  = "+00:00" # 02:00 UK time in winter, 03:00 during British Summer Time
  }

  lifecycle {
    ignore_changes = [kubernetes_version, default_node_pool[0].orchestrator_version]
  }

  depends_on = [
    azurerm_role_assignment.cluster_network,
    azurerm_role_assignment.cluster_route_table,
  ]
}

# Decision-path and portal workloads. System pods run on their own pool.
resource "azurerm_kubernetes_cluster_node_pool" "apps" {
  name                        = "apps"
  kubernetes_cluster_id       = azurerm_kubernetes_cluster.this.id
  vm_size                     = var.apps_vm_size
  vnet_subnet_id              = var.subnet_id
  zones                       = var.zones
  auto_scaling_enabled        = true
  min_count                   = var.apps_min_count
  max_count                   = var.apps_max_count
  os_sku                      = "AzureLinux"
  temporary_name_for_rotation = "appstmp"
  node_labels = {
    "fraudshield.io/pool" = "apps"
  }
  tags = var.tags

  upgrade_settings {
    max_surge = "33%"
  }

  lifecycle {
    ignore_changes = [node_count, orchestrator_version]
  }
}

resource "azurerm_role_assignment" "acr_pull" {
  scope                = var.acr_id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_kubernetes_cluster.this.kubelet_identity[0].object_id
}

resource "azurerm_role_assignment" "deployer" {
  scope                = azurerm_kubernetes_cluster.this.id
  role_definition_name = "Azure Kubernetes Service RBAC Cluster Admin"
  principal_id         = var.deployer_object_id
}

output "id" {
  value = azurerm_kubernetes_cluster.this.id
}

output "name" {
  value = azurerm_kubernetes_cluster.this.name
}

output "oidc_issuer_url" {
  value = azurerm_kubernetes_cluster.this.oidc_issuer_url
}

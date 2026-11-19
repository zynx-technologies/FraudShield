# Development and experiments. Synthetic data only (design section 11).
#
# Low-cost layout: Container Apps instead of AKS, and API Management (Developer, external mode) as the
# public entry point instead of Front Door and Application Gateway. No hub firewall (apps leave through a
# NAT gateway) and no Bastion. Test, perf, and prod keep the full AKS layout.
environment   = "dev"
address_space = "10.60.0.0/16"
multi_region  = false

compute_platform = "container_apps"
firewall_enabled = false
bastion_enabled  = false

apim_sku = "Developer_1"

sql_sku               = "GP_S_Gen5_2"
cosmos_max_throughput = 1000
redis_sku             = "Balanced_B1"
event_hubs_sku        = "Standard"
event_hubs_capacity   = 1
signalr_sku           = "Standard_S1"

databricks_public_access = true
log_retention_days       = 30

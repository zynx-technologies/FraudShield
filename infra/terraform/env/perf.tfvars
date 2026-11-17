# Load and failover testing with synthetic data at production scale (design section 11).
# Sized like production, in two regions, so the 5,000 TPS and regional failover tests mean something.
# Scale it down between test windows to control cost.
environment   = "perf"
address_space = "10.62.0.0/16"
multi_region  = true

firewall_sku            = "Standard"
apim_sku                = "Premium_2"
apim_secondary_capacity = 2
aks_sku_tier            = "Standard"
aks_apps_vm_size        = "Standard_F16s_v2"
aks_apps_min_count      = 3
aks_apps_max_count      = 20

sql_sku               = "BC_Gen5_8"
cosmos_max_throughput = 40000
redis_sku             = "Balanced_B50"
event_hubs_sku        = "Premium"
event_hubs_capacity   = 2
signalr_sku           = "Premium_P1"

databricks_public_access = true
waf_mode                 = "Prevention"
log_retention_days       = 30

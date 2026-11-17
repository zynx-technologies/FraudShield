# Live data. UK South and UK West, active-active for decisions (design section 11).
environment   = "prod"
address_space = "10.63.0.0/16"
multi_region  = true

firewall_sku                 = "Premium"
expressroute_gateway_enabled = true
apim_sku                     = "Premium_3"
apim_secondary_capacity      = 2
aks_sku_tier                 = "Premium"
aks_apps_vm_size             = "Standard_F16s_v2"
aks_apps_min_count           = 3
aks_apps_max_count           = 20

sql_sku               = "BC_Gen5_8"
cosmos_max_throughput = 40000
redis_sku             = "Balanced_B50"
event_hubs_sku        = "Premium"
event_hubs_capacity   = 2
signalr_sku           = "Premium_P1"

databricks_public_access = false
lock_immutability        = false # Set true only after the 7-year retention is approved. Locking cannot be undone.
waf_mode                 = "Prevention"
log_retention_days       = 365
sentinel_enabled         = true
defender_enabled         = true

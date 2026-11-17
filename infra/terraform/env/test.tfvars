# System, integration, and UAT testing. Tokenised samples only (design section 11).
environment   = "test"
address_space = "10.61.0.0/16"
multi_region  = false

firewall_sku       = "Standard"
apim_sku           = "Developer_1"
aks_sku_tier       = "Standard"
aks_apps_vm_size   = "Standard_D4ds_v5"
aks_apps_min_count = 2
aks_apps_max_count = 6

sql_sku               = "GP_S_Gen5_2"
cosmos_max_throughput = 2000
redis_sku             = "Balanced_B3"
event_hubs_sku        = "Standard"
event_hubs_capacity   = 2
signalr_sku           = "Standard_S1"

databricks_public_access = true
waf_mode                 = "Prevention"
log_retention_days       = 90

variable "name" {
  type = string
}

variable "primary_resource_group_name" {
  type = string
}

variable "regions" {
  description = "Regions with an application spoke. The key 'primary' must exist."
  type = map(object({
    location                   = string
    short                      = string
    resource_group_name        = string
    private_endpoint_subnet_id = string
    dns_zone_ids               = map(string)
  }))
}

variable "data_spoke" {
  description = "Data spoke private endpoints, so Databricks can read Event Hubs and Cosmos DB."
  type = object({
    location                   = string
    resource_group_name        = string
    private_endpoint_subnet_id = string
    dns_zone_ids               = map(string)
  })
}

variable "cosmos_max_throughput" {
  description = "Autoscale maximum RU/s for each container."
  type        = number
  default     = 4000
}

variable "cosmos_zone_redundant" {
  type    = bool
  default = false
}

variable "sql_sku" {
  type    = string
  default = "GP_S_Gen5_2"
}

variable "sql_admin_group" {
  description = "Entra group that administers Azure SQL. SQL authentication is disabled."
  type = object({
    login     = string
    object_id = string
  })
}

variable "redis_sku" {
  type    = string
  default = "Balanced_B5"
}

variable "event_hubs_sku" {
  type    = string
  default = "Standard"
}

variable "event_hubs_capacity" {
  type    = number
  default = 2
}

variable "decision_partitions" {
  type    = number
  default = 32
}

variable "signalr_sku" {
  type    = string
  default = "Standard_S1"
}

variable "log_analytics_workspace_id" {
  type = string
}

variable "tags" {
  type = map(string)
}

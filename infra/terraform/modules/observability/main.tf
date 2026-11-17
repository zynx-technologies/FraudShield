# Log Analytics, Application Insights, the on-call action group, and platform alerts. Design sections 4
# and 13. Model drift and business KPIs are monitored in Databricks (Lakehouse Monitoring), not here.

variable "name" {
  type = string
}

variable "location" {
  type = string
}

variable "resource_group_name" {
  type = string
}

variable "retention_days" {
  type    = number
  default = 90
}

variable "alert_emails" {
  description = "On-call addresses. Replace with the ServiceNow integration when it is ready."
  type        = list(string)
  default     = []
}

variable "sentinel_enabled" {
  type    = bool
  default = false
}

variable "tags" {
  type = map(string)
}

resource "azurerm_log_analytics_workspace" "this" {
  name                = "log-${var.name}"
  location            = var.location
  resource_group_name = var.resource_group_name
  sku                 = "PerGB2018"
  retention_in_days   = var.retention_days
  tags                = var.tags
}

resource "azurerm_application_insights" "this" {
  name                         = "appi-${var.name}"
  location                     = var.location
  resource_group_name          = var.resource_group_name
  workspace_id                 = azurerm_log_analytics_workspace.this.id
  application_type             = "java"
  local_authentication_enabled = false
  tags                         = var.tags
}

resource "azurerm_sentinel_log_analytics_workspace_onboarding" "this" {
  count        = var.sentinel_enabled ? 1 : 0
  workspace_id = azurerm_log_analytics_workspace.this.id
}

resource "azurerm_monitor_action_group" "oncall" {
  name                = "ag-${var.name}-oncall"
  resource_group_name = var.resource_group_name
  short_name          = "fs-oncall"
  tags                = var.tags

  dynamic "email_receiver" {
    for_each = var.alert_emails
    content {
      name                    = "email-${email_receiver.key}"
      email_address           = email_receiver.value
      use_common_alert_schema = true
    }
  }
}

# Design section 4: p99 decision latency under 200 ms, measured at API Management.
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "decision_latency" {
  name                 = "alert-${var.name}-decision-p99"
  location             = var.location
  resource_group_name  = var.resource_group_name
  scopes               = [azurerm_application_insights.this.id]
  description          = "Decision API p99 latency above 200 ms for 5 minutes."
  severity             = 1
  evaluation_frequency = "PT1M"
  window_duration      = "PT5M"
  tags                 = var.tags

  criteria {
    query                   = <<-KQL
      requests
      | where name has "/api/v1/decisions"
      | summarize p99 = percentile(duration, 99) by bin(timestamp, 1m)
    KQL
    time_aggregation_method = "Maximum"
    metric_measure_column   = "p99"
    operator                = "GreaterThan"
    threshold               = 200

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 3
      number_of_evaluation_periods             = 5
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.oncall.id]
  }
}

# Design section 4: the scoring engine failing means rules-only fallback, which is critical.
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "fallback_mode" {
  name                 = "alert-${var.name}-rules-only-fallback"
  location             = var.location
  resource_group_name  = var.resource_group_name
  scopes               = [azurerm_application_insights.this.id]
  description          = "Decisions are being made by fallback rules only (scoring engine unavailable)."
  severity             = 0
  evaluation_frequency = "PT1M"
  window_duration      = "PT5M"
  tags                 = var.tags

  criteria {
    query                   = <<-KQL
      customMetrics
      | where name == "fraudshield.decision.fallback"
      | summarize total = sum(valueSum) by bin(timestamp, 1m)
    KQL
    time_aggregation_method = "Total"
    metric_measure_column   = "total"
    operator                = "GreaterThan"
    threshold               = 0

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.oncall.id]
  }
}

output "log_analytics_workspace_id" {
  value = azurerm_log_analytics_workspace.this.id
}

output "application_insights_id" {
  value = azurerm_application_insights.this.id
}

output "application_insights_connection_string" {
  value     = azurerm_application_insights.this.connection_string
  sensitive = true
}

output "action_group_id" {
  value = azurerm_monitor_action_group.oncall.id
}

variable "aws_region" {
  type    = string
  default = "us-east-1"
}
variable "aws_account_id" {
  type        = string
  description = "Explicit account guardrail; use a separate account per environment."
  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "Use a 12-digit AWS account ID."
  }
}
variable "project_name" {
  type    = string
  default = "ticketing"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,15}$", var.project_name))
    error_message = "Use 3-16 lowercase characters."
  }
}
variable "environment" {
  type = string
  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "Use dev, staging or prod."
  }
}
variable "owner" {
  type = string
}
variable "cost_center" {
  type = string
}
variable "vpc_cidr" {
  type    = string
  default = "10.42.0.0/16"
}
variable "client_cidrs" {
  type        = list(string)
  description = "Private VPN/peering client CIDRs allowed to reach the internal HTTPS ALB."
  validation {
    condition     = length(var.client_cidrs) > 0 && alltrue([for cidr in var.client_cidrs : can(cidrnetmask(cidr)) && !contains(["0.0.0.0/0", "0.0.0.0/1", "128.0.0.0/1"], cidr)])
    error_message = "Provide explicit IPv4 client CIDRs; unrestricted ingress is forbidden."

  }
}
variable "certificate_arn" {
  type        = string
  description = "Existing issued ACM certificate in the deployment region. The private DNS record is managed by the platform team."
  validation {
    condition     = can(regex("^arn:aws:acm:[a-z0-9-]+:[0-9]{12}:certificate/.+$", var.certificate_arn))
    error_message = "Provide an ACM certificate ARN."
  }
}
variable "enable_service" {
  type        = bool
  default     = false
  description = "Keep false until a scanned image has been pushed to ECR. Infrastructure still incurs charges."
}
variable "image_digest" {
  type        = string
  default     = null
  description = "Immutable sha256 digest from this environment's ECR repository."
  validation {
    condition     = var.image_digest == null ? true : can(regex("^sha256:[a-f0-9]{64}$", var.image_digest))
    error_message = "Use sha256 followed by 64 hexadecimal digits."
  }
}
variable "minimum_tasks" {
  type    = number
  default = 2
  validation {
    condition     = var.minimum_tasks >= 2 && floor(var.minimum_tasks) == var.minimum_tasks
    error_message = "Use at least two tasks."
  }
}
variable "maximum_tasks" {
  type    = number
  default = 6
}
variable "admin_secret_arn" {
  type        = string
  default     = null
  description = "Optional existing Secrets Manager secret containing the courtesy key as a raw string. No secret value enters Terraform."
}
variable "admin_secret_kms_key_arn" {
  type        = string
  default     = null
  description = "Optional customer-managed KMS key for the existing admin secret."
}
variable "notification_email" {
  type        = string
  default     = null
  description = "Optional subscription requiring email confirmation; no notification is sent during validation."
}
variable "monthly_budget_usd" {
  type    = number
  default = 150
}

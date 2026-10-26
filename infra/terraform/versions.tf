terraform {
  required_version = ">= 1.10, < 2.0"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.20.0"
    }

  }
  backend "s3" {}
}
provider "aws" {
  region              = var.aws_region
  allowed_account_ids = [var.aws_account_id]
  default_tags {
    tags = {
      Project = var.project_name, Environment = var.environment,
      Owner   = var.owner, CostCenter = var.cost_center, ManagedBy = "Terraform"

    }

  }
}
data "aws_caller_identity" "current" {}
data "aws_availability_zones" "available" {
  state = "available"
}
data "aws_partition" "current" {}
locals {
  name      = "${var.project_name}-${var.environment}"
  partition = data.aws_partition.current.partition
  account   = data.aws_caller_identity.current.account_id
  zones     = slice(data.aws_availability_zones.available.names, 0, 2)
  logs_arn  = "arn:${local.partition}:logs:${var.aws_region}:${local.account}:log-group:/${local.name}/*"
  data_actions = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem",
  "dynamodb:DeleteItem", "dynamodb:Query", "dynamodb:ConditionCheckItem"]
}

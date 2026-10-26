mock_provider "aws" {
  override_during = plan
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012", arn = "arn:aws:iam::123456789012:root", user_id = "test" }
  }
  mock_data "aws_availability_zones" {
    defaults = { names = ["us-east-1a", "us-east-1b"] }
  }
  mock_data "aws_partition" {
    defaults = { partition = "aws", dns_suffix = "amazonaws.com" }
  }
  mock_resource "aws_kms_key" {
    defaults = { arn = "arn:aws:kms:us-east-1:123456789012:key/00000000-0000-0000-0000-000000000000" }
  }
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::123456789012:role/test" }
  }
  mock_resource "aws_dynamodb_table" {
    defaults = { arn = "arn:aws:dynamodb:us-east-1:123456789012:table/ticketing-dev" }
  }
  mock_resource "aws_sqs_queue" {
    defaults = { arn = "arn:aws:sqs:us-east-1:123456789012:test", url = "https://sqs.us-east-1.amazonaws.com/123456789012/test", id = "https://sqs.us-east-1.amazonaws.com/123456789012/test" }
  }
  mock_resource "aws_cloudwatch_log_group" {
    defaults = { arn = "arn:aws:logs:us-east-1:123456789012:log-group:/ticketing-dev/application" }
  }
  mock_resource "aws_ecr_repository" {
    defaults = { arn = "arn:aws:ecr:us-east-1:123456789012:repository/ticketing-dev", repository_url = "123456789012.dkr.ecr.us-east-1.amazonaws.com/ticketing-dev" }
  }
  mock_resource "aws_s3_bucket" {
    defaults = { arn = "arn:aws:s3:::ticketing-dev-test", id = "ticketing-dev-test" }
  }
  mock_resource "aws_lb" {
    defaults = { arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:loadbalancer/app/test/1234567890123456", arn_suffix = "app/test/1234567890123456" }
  }
  mock_resource "aws_lb_target_group" {
    defaults = { arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:targetgroup/test/1234567890123456" }
  }
  mock_resource "aws_ecs_task_definition" {
    defaults = { arn = "arn:aws:ecs:us-east-1:123456789012:task-definition/ticketing-dev:1" }
  }
  mock_resource "aws_vpc_endpoint" {
    defaults = { prefix_list_id = "pl-0123456789abcdef0" }
  }
}
variables {
  aws_account_id  = "123456789012"
  environment     = "dev"
  owner           = "test-team"
  cost_center     = "test"
  client_cidrs    = ["10.99.0.0/24"]
  certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
}
run "private_foundation" {
  command = plan
  assert {
    condition     = aws_lb.application.internal && alltrue([for subnet in aws_subnet.private : !subnet.map_public_ip_on_launch])
    error_message = "The ingress and task subnets must remain private."
  }
  assert {
    condition     = length(aws_subnet.private) == 2 && aws_subnet.private[0].availability_zone != aws_subnet.private[1].availability_zone
    error_message = "Use two different availability zones."
  }
  assert {
    condition     = length(aws_ecs_service.application) == 0
    error_message = "Bootstrap must not start a service with a missing image."
  }
  assert {
    condition     = aws_dynamodb_table.ticketing.deletion_protection_enabled && aws_dynamodb_table.ticketing.point_in_time_recovery[0].enabled
    error_message = "Inventory requires deletion protection and PITR."
  }
  assert {
    condition     = aws_sqs_queue.orders.visibility_timeout_seconds == 60 && jsondecode(aws_sqs_queue.orders.redrive_policy).maxReceiveCount == 8
    error_message = "Queue settings must match the application contract."
  }
  assert {
    condition     = length(aws_vpc_endpoint.interface) == 5 && length(aws_vpc_endpoint.gateway) == 2
    error_message = "Private tasks need the complete endpoint set."
  }
}
run "immutable_service" {
  command = plan
  variables {
    enable_service = true
    image_digest   = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
  assert {
    condition     = aws_ecs_service.application[0].desired_count >= 2 && !aws_ecs_service.application[0].network_configuration[0].assign_public_ip
    error_message = "The service needs multiple private tasks."
  }
  assert {
    condition     = jsondecode(aws_ecs_task_definition.application[0].container_definitions)[0].readonlyRootFilesystem && jsondecode(aws_ecs_task_definition.application[0].container_definitions)[0].user == "10001"
    error_message = "Run as a non-root user with a read-only root filesystem."
  }
  assert {
    condition     = endswith(jsondecode(aws_ecs_task_definition.application[0].container_definitions)[0].image, var.image_digest)
    error_message = "Deploy by immutable digest."
  }
  assert {
    condition     = aws_ecs_service.application[0].deployment_circuit_breaker[0].rollback
    error_message = "Failed deployments must support rollback."
  }
}
run "reject_public_ingress" {
  command = plan
  variables { client_cidrs = ["0.0.0.0/0"] }
  expect_failures = [var.client_cidrs]
}
run "reject_missing_image" {
  command = plan
  variables { enable_service = true }
  expect_failures = [aws_ecs_task_definition.application]
}
run "reject_invalid_environment" {
  command = plan
  variables { environment = "shared" }
  expect_failures = [var.environment]
}
run "reject_invalid_scale_bounds" {
  command = plan
  variables {
    enable_service = true
    image_digest   = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    maximum_tasks  = 1
  }
  expect_failures = [aws_appautoscaling_target.application]
}

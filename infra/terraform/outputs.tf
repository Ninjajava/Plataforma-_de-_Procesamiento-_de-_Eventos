output "ecr_repository_url" {
  value = aws_ecr_repository.application.repository_url
}
output "cluster_name" {
  value = aws_ecs_cluster.main.name
}
output "service_name" {
  value = var.enable_service ? aws_ecs_service.application[0].name : null
}
output "private_alb_dns_name" {
  value = aws_lb.application.dns_name
}
output "table_name" {
  value = aws_dynamodb_table.ticketing.name
}
output "orders_queue_url" {
  value = aws_sqs_queue.orders.url
}
output "dlq_queue_url" {
  value = aws_sqs_queue.dlq.url
}
output "operations_topic_arn" {
  value = aws_sns_topic.operations.arn
}
output "dashboard_name" {
  value = aws_cloudwatch_dashboard.operations.dashboard_name
}

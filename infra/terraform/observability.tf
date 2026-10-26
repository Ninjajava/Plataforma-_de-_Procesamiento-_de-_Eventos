resource "aws_cloudwatch_log_group" "application" {
  name              = "/${local.name}/application"
  retention_in_days = 30
  kms_key_id        = aws_kms_key.data.arn
}
resource "aws_cloudwatch_log_group" "network" {
  name              = "/${local.name}/network"
  retention_in_days = 30
  kms_key_id        = aws_kms_key.data.arn
}
resource "aws_iam_role" "flow_logs" {
  name = "${local.name}-flow-logs"
  assume_role_policy = jsonencode({
    Version = "2012-10-17", Statement = [{
      Effect = "Allow", Principal = { Service = "vpc-flow-logs.amazonaws.com"
      }, Action = "sts:AssumeRole",
      Condition = { StringEquals = { "aws:SourceAccount" = local.account
        }, ArnLike = { "aws:SourceArn" = "arn:${local.partition}:ec2:${var.aws_region}:${local.account}:vpc-flow-log/*"
        }
      }

    }]

  })
}
resource "aws_iam_role_policy" "flow_logs" {
  role = aws_iam_role.flow_logs.id
  policy = jsonencode({
    Version = "2012-10-17", Statement = [
      { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"], Resource = "${aws_cloudwatch_log_group.network.arn}:*"
      },
      { Effect = "Allow", Action = ["logs:DescribeLogGroups"], Resource = "*"
      }
    ]

  })
}
resource "aws_flow_log" "main" {
  vpc_id               = aws_vpc.main.id
  traffic_type         = "ALL"
  log_destination_type = "cloud-watch-logs"
  log_destination      = aws_cloudwatch_log_group.network.arn
  iam_role_arn         = aws_iam_role.flow_logs.arn
  depends_on           = [aws_iam_role_policy.flow_logs]
}
resource "aws_sns_topic" "operations" {
  name = "${local.name}-operations"
}
resource "aws_sns_topic_subscription" "email" {
  count     = var.notification_email == null ? 0 : 1
  topic_arn = aws_sns_topic.operations.arn
  protocol  = "email"
  endpoint  = var.notification_email
}
locals {
  alarm_actions = [aws_sns_topic.operations.arn]
  alarms = {
    queue_age = { namespace = "AWS/SQS", metric = "ApproximateAgeOfOldestMessage", threshold = 300, statistic = "Maximum", dimensions = { QueueName = aws_sqs_queue.orders.name
      }
    },
    dlq = { namespace = "AWS/SQS", metric = "ApproximateNumberOfMessagesVisible", threshold = 0, statistic = "Maximum", dimensions = { QueueName = aws_sqs_queue.dlq.name
      }
    },
    alb_errors = { namespace = "AWS/ApplicationELB", metric = "HTTPCode_Target_5XX_Count", threshold = 5, statistic = "Sum", dimensions = { LoadBalancer = aws_lb.application.arn_suffix
      }
    },
    table_write_throttles = { namespace = "AWS/DynamoDB", metric = "WriteThrottleEvents", threshold = 0, statistic = "Sum", dimensions = { TableName = aws_dynamodb_table.ticketing.name
      }
    },
    index_write_throttles = { namespace = "AWS/DynamoDB", metric = "WriteThrottleEvents", threshold = 0, statistic = "Sum", dimensions = { TableName = aws_dynamodb_table.ticketing.name, GlobalSecondaryIndexName = "kind-sort-index"
      }
    }

  }
}
resource "aws_cloudwatch_metric_alarm" "operations" {
  for_each            = local.alarms
  alarm_name          = "${local.name}-${each.key}"
  namespace           = each.value.namespace
  metric_name         = each.value.metric
  dimensions          = each.value.dimensions
  statistic           = each.value.statistic
  threshold           = each.value.threshold
  comparison_operator = "GreaterThanThreshold"
  period              = 60
  evaluation_periods  = 2
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}
resource "aws_cloudwatch_metric_alarm" "latency" {
  alarm_name  = "${local.name}-alb-p95"
  namespace   = "AWS/ApplicationELB"
  metric_name = "TargetResponseTime"
  dimensions = { LoadBalancer = aws_lb.application.arn_suffix
  }
  extended_statistic  = "p95"
  threshold           = 1
  comparison_operator = "GreaterThanThreshold"
  period              = 60
  evaluation_periods  = 3
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}
resource "aws_cloudwatch_dashboard" "operations" {
  dashboard_name = local.name
  dashboard_body = jsonencode({ widgets = [
    { type = "metric", x = 0, y = 0, width = 12, height = 6, properties = {
      title = "Queue age and DLQ depth", region = var.aws_region, view = "timeSeries",
      metrics = [
        ["AWS/SQS", "ApproximateAgeOfOldestMessage", "QueueName", aws_sqs_queue.orders.name],
        ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", aws_sqs_queue.dlq.name]
      ], period = 60, stat = "Maximum"

      }
    },
    { type = "metric", x = 12, y = 0, width = 12, height = 6, properties = {
      title   = "API target latency p95", region = var.aws_region, view = "timeSeries",
      metrics = [["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", aws_lb.application.arn_suffix]], period = 60, stat = "p95"

      }
    }
    ]
  })
}
resource "aws_budgets_budget" "account" {
  count        = var.notification_email == null ? 0 : 1
  name         = "${local.name}-account-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.notification_email]

  }
}

resource "aws_kms_key" "data" {
  description             = "${local.name} environment data and logs"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Sid = "AccountAdministration", Effect = "Allow", Principal = { AWS = "arn:${local.partition}:iam::${local.account}:root"
        }, Action = "kms:*", Resource = "*"
      },
      { Sid = "CloudWatchLogs", Effect = "Allow", Principal = { Service = "logs.${var.aws_region}.amazonaws.com"
        },
        Action = ["kms:Encrypt", "kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:DescribeKey"], Resource = "*",
        Condition = { ArnLike = { "kms:EncryptionContext:aws:logs:arn" = local.logs_arn
          }
        }

      }
    ]

  })
  lifecycle {
    prevent_destroy = true
  }
}
resource "aws_kms_alias" "data" {
  name          = "alias/${local.name}"
  target_key_id = aws_kms_key.data.key_id
}
resource "aws_dynamodb_table" "ticketing" {
  name                        = local.name
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "pk"
  deletion_protection_enabled = true
  attribute {
    name = "pk"
    type = "S"
  }
  attribute {
    name = "kind"
    type = "S"
  }
  attribute {
    name = "sort"
    type = "S"
  }
  global_secondary_index {
    name            = "kind-sort-index"
    hash_key        = "kind"
    range_key       = "sort"
    projection_type = "ALL"

  }
  point_in_time_recovery {
    enabled = true
  }
  server_side_encryption {
    enabled     = true
    kms_key_arn = aws_kms_key.data.arn
  }
  lifecycle {
    prevent_destroy = true
  }
}
resource "aws_sqs_queue" "dlq" {
  name                              = "${local.name}-orders-dlq"
  message_retention_seconds         = 1209600
  kms_master_key_id                 = aws_kms_key.data.arn
  kms_data_key_reuse_period_seconds = 300
}
resource "aws_sqs_queue" "orders" {
  name                              = "${local.name}-orders"
  visibility_timeout_seconds        = 60
  receive_wait_time_seconds         = 10
  message_retention_seconds         = 345600
  kms_master_key_id                 = aws_kms_key.data.arn
  kms_data_key_reuse_period_seconds = 300
  redrive_policy = jsonencode({ deadLetterTargetArn = aws_sqs_queue.dlq.arn, maxReceiveCount = 8
  })
}
resource "aws_sqs_queue_redrive_allow_policy" "dlq" {
  queue_url = aws_sqs_queue.dlq.id
  redrive_allow_policy = jsonencode({ redrivePermission = "byQueue", sourceQueueArns = [aws_sqs_queue.orders.arn]
  })
}
resource "aws_sqs_queue_policy" "tls" {
  for_each = { orders = aws_sqs_queue.orders, dlq = aws_sqs_queue.dlq
  }
  queue_url = each.value.id
  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [{
      Sid = "DenyInsecureTransport", Effect = "Deny", Principal = "*", Action = "sqs:*",
      Resource = each.value.arn, Condition = { Bool = { "aws:SecureTransport" = "false"
        }
      }

    }]

  })
}
resource "aws_ecr_repository" "application" {
  name                 = local.name
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = aws_kms_key.data.arn
  }
  lifecycle {
    prevent_destroy = true
  }
}
resource "aws_ecr_lifecycle_policy" "untagged" {
  repository = aws_ecr_repository.application.name
  policy = jsonencode({ rules = [{
    rulePriority = 1, description = "Expire only untagged images after seven days",
    selection = { tagStatus = "untagged", countType = "sinceImagePushed", countUnit = "days", countNumber = 7
    },
    action = { type = "expire"
    }

    }]
  })
}

locals {
  task_assume_role = jsonencode({
    Version = "2012-10-17", Statement = [{
      Effect = "Allow", Principal = { Service = "ecs-tasks.amazonaws.com"
      }, Action = "sts:AssumeRole",
      Condition = {
        StringEquals = { "aws:SourceAccount" = local.account
        },
        ArnLike = { "aws:SourceArn" = "arn:${local.partition}:ecs:${var.aws_region}:${local.account}:*"
        }

      }

    }]

  })
}
resource "aws_iam_role" "task" {
  name               = "${local.name}-task"
  assume_role_policy = local.task_assume_role
}
resource "aws_iam_role" "execution" {
  name               = "${local.name}-execution"
  assume_role_policy = local.task_assume_role
}
resource "aws_iam_role_policy" "task" {
  role = aws_iam_role.task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = local.data_actions, Resource = [aws_dynamodb_table.ticketing.arn, "${aws_dynamodb_table.ticketing.arn}/index/*"]
      },
      { Effect = "Allow", Action = ["sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes", "sqs:GetQueueUrl"], Resource = aws_sqs_queue.orders.arn
      },
      { Effect = "Allow", Action = ["kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey", "kms:DescribeKey"], Resource = aws_kms_key.data.arn,
        Condition = { StringEquals = { "kms:ViaService" = ["sqs.${var.aws_region}.amazonaws.com", "dynamodb.${var.aws_region}.amazonaws.com"]
          }
        }
      }
    ]

  })
}
resource "aws_iam_role_policy" "execution" {
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*"
      },
      { Effect = "Allow", Action = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"], Resource = aws_ecr_repository.application.arn
      },
      { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.application.arn}:*"
      }
      ], var.admin_secret_arn == null ? [] : [
      { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = var.admin_secret_arn
      }
      ], var.admin_secret_kms_key_arn == null ? [] : [
      { Effect = "Allow", Action = ["kms:Decrypt"], Resource = var.admin_secret_kms_key_arn,
        Condition = { StringEquals = { "kms:ViaService" = "secretsmanager.${var.aws_region}.amazonaws.com"
          }
        }
      }
    ])

  })
}

resource "aws_ecs_cluster" "main" {
  name = local.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}
resource "aws_ecs_task_definition" "application" {
  count                    = var.enable_service ? 1 : 0
  family                   = local.name
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name  = "application", essential = true,
    image = "${aws_ecr_repository.application.repository_url}@${var.image_digest == null ? "sha256:unset" : var.image_digest}",
    user  = "10001", readonlyRootFilesystem = true,
    portMappings = [{ containerPort = 8080, protocol = "tcp"
    }],
    stopTimeout = 60,
    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "aws"
      },
      { name = "AWS_REGION", value = var.aws_region
      },
      { name = "DYNAMODB_TABLE", value = aws_dynamodb_table.ticketing.name
      },
      { name = "SQS_URL", value = aws_sqs_queue.orders.url
      },
      { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=60 -Djava.io.tmpdir=/app/tmp"
      }
    ],
    secrets = var.admin_secret_arn == null ? [] : [{ name = "TICKETING_ADMIN_KEY", valueFrom = var.admin_secret_arn
    }],
    mountPoints = [{ sourceVolume = "tmp", containerPath = "/app/tmp", readOnly = false
    }],
    logConfiguration = {
      logDriver = "awslogs",
      options = {
        "awslogs-group"  = aws_cloudwatch_log_group.application.name,
        "awslogs-region" = var.aws_region, "awslogs-stream-prefix" = "application"

      }

    }

  }])
  volume {
    name = "tmp"
  }
  lifecycle {
    precondition {
      condition     = var.image_digest != null
      error_message = "Push a scanned ECR image and provide its digest before enabling the service."
    }

  }
}
resource "aws_ecs_service" "application" {
  count                              = var.enable_service ? 1 : 0
  name                               = local.name
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.application[0].arn
  desired_count                      = var.minimum_tasks
  launch_type                        = "FARGATE"
  platform_version                   = "1.4.0"
  enable_execute_command             = false
  health_check_grace_period_seconds  = 90
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  availability_zone_rebalancing      = "ENABLED"
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = false

  }
  load_balancer {
    target_group_arn = aws_lb_target_group.application.arn
    container_name   = "application"
    container_port   = 8080

  }
  lifecycle {
    ignore_changes = [desired_count]
  }
  depends_on = [aws_lb_listener.https, aws_vpc_endpoint.interface, aws_vpc_endpoint.gateway, aws_iam_role_policy.task, aws_iam_role_policy.execution]
}
resource "aws_appautoscaling_target" "application" {
  count              = var.enable_service ? 1 : 0
  min_capacity       = var.minimum_tasks
  max_capacity       = var.maximum_tasks
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.application[0].name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
  lifecycle {
    precondition {
      condition     = var.maximum_tasks >= var.minimum_tasks
      error_message = "maximum_tasks must be at least minimum_tasks."
    }

  }
}
resource "aws_appautoscaling_policy" "cpu" {
  count              = var.enable_service ? 1 : 0
  name               = "${local.name}-cpu"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.application[0].resource_id
  scalable_dimension = aws_appautoscaling_target.application[0].scalable_dimension
  service_namespace  = aws_appautoscaling_target.application[0].service_namespace
  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 180
    scale_out_cooldown = 60
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }

  }
}

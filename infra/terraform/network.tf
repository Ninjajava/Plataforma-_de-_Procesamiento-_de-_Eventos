resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags = { Name = local.name
  }
}
resource "aws_subnet" "private" {
  count                   = 2
  vpc_id                  = aws_vpc.main.id
  availability_zone       = local.zones[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index)
  map_public_ip_on_launch = false
  tags = { Name = "${local.name}-private-${count.index + 1}"
  }
}
resource "aws_route_table" "private" {
  count  = 2
  vpc_id = aws_vpc.main.id
  tags = { Name = "${local.name}-private-${count.index + 1}"
  }
}
resource "aws_route_table_association" "private" {
  count          = 2
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}
resource "aws_security_group" "alb" {
  name_prefix = "${local.name}-alb-"
  description = "Internal TLS ingress from approved private clients"
  vpc_id      = aws_vpc.main.id
}
resource "aws_security_group" "tasks" {
  name_prefix = "${local.name}-tasks-"
  description = "Fargate accepts application traffic only from the internal ALB"
  vpc_id      = aws_vpc.main.id
}
resource "aws_security_group" "endpoints" {
  name_prefix = "${local.name}-endpoints-"
  description = "Private AWS service endpoints"
  vpc_id      = aws_vpc.main.id
}
resource "aws_vpc_security_group_ingress_rule" "clients" {
  for_each          = toset(var.client_cidrs)
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = each.value
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}
resource "aws_vpc_security_group_egress_rule" "alb_to_tasks" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.tasks.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}
resource "aws_vpc_security_group_ingress_rule" "tasks_from_alb" {
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}
resource "aws_vpc_security_group_egress_rule" "tasks_to_endpoints" {
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.endpoints.id
  from_port                    = 443
  to_port                      = 443
  ip_protocol                  = "tcp"
}
resource "aws_vpc_security_group_ingress_rule" "endpoints_from_tasks" {
  security_group_id            = aws_security_group.endpoints.id
  referenced_security_group_id = aws_security_group.tasks.id
  from_port                    = 443
  to_port                      = 443
  ip_protocol                  = "tcp"
}
resource "aws_vpc_security_group_egress_rule" "credentials" {
  security_group_id = aws_security_group.tasks.id
  cidr_ipv4         = "169.254.170.2/32"
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
  description       = "Fargate task credentials; not internet egress"
}
resource "aws_vpc_endpoint" "gateway" {
  for_each          = toset(["dynamodb", "s3"])
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.aws_region}.${each.value}"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.private[*].id
  policy = each.key == "s3" ? jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow", Principal = "*", Action = ["s3:GetObject"],
      Resource = ["arn:${local.partition}:s3:::prod-${var.aws_region}-starport-layer-bucket/*"]

    }]
    }) : jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow", Principal = "*", Action = local.data_actions,
      Resource = [aws_dynamodb_table.ticketing.arn, "${aws_dynamodb_table.ticketing.arn}/index/*"],
      Condition = { ArnEquals = { "aws:PrincipalArn" = aws_iam_role.task.arn
        }
      }

    }]

  })
}
resource "aws_vpc_security_group_egress_rule" "gateway" {
  for_each          = aws_vpc_endpoint.gateway
  security_group_id = aws_security_group.tasks.id
  prefix_list_id    = each.value.prefix_list_id
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}
resource "aws_vpc_endpoint" "interface" {
  for_each            = toset(["ecr.api", "ecr.dkr", "logs", "sqs", "secretsmanager"])
  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.${var.aws_region}.${each.value}"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = aws_subnet.private[*].id
  security_group_ids  = [aws_security_group.endpoints.id]
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow", Principal = "*", Action = "*", Resource = "*",
      Condition = { ArnEquals = { "aws:PrincipalArn" = [aws_iam_role.task.arn, aws_iam_role.execution.arn]
        }
      }

    }]

  })
}

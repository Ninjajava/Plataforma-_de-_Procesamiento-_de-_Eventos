#!/bin/sh
set -eu
aws --endpoint-url http://localstack:4566 sqs create-queue --queue-name orders-dlq
aws --endpoint-url http://localstack:4566 sqs create-queue --queue-name orders --attributes '{"VisibilityTimeout":"60","MessageRetentionPeriod":"345600","RedrivePolicy":"{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:orders-dlq\",\"maxReceiveCount\":\"8\"}"}'

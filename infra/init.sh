#!/bin/sh
set -eu
attempt=0
until aws --endpoint-url http://dynamodb:8000 dynamodb list-tables >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  [ "$attempt" -lt 60 ] || exit 1
  sleep 1
done
if ! aws --endpoint-url http://dynamodb:8000 dynamodb describe-table --table-name ticketing >/dev/null 2>&1; then
  aws --endpoint-url http://dynamodb:8000 dynamodb create-table --cli-input-json file:///infra/table.json
fi
aws --endpoint-url http://dynamodb:8000 dynamodb wait table-exists --table-name ticketing
/bin/sh /infra/queue-init.sh

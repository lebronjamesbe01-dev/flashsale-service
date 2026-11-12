#!/usr/bin/env bash
# Runs the k6 load test against the running docker-compose stack, then checks data invariants.
#
#   docker compose up -d --wait --scale app=2     # with RATE_LIMIT_ENABLED=false, see README
#   ./load-test/run.sh
#
# Tunables (env): USERS, BROWSE_RPS, PURCHASE_RPS, DURATION
set -euo pipefail
cd "$(dirname "$0")/.."

network="$(docker compose ps -q nginx | xargs docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}')"
mkdir -p load-test/results

docker run --rm -i --network "$network" \
  -e USERS -e BROWSE_RPS -e PURCHASE_RPS -e DURATION \
  -v "$PWD/load-test/results:/results" \
  grafana/k6:2.3.0 run --summary-export /results/summary.json - < load-test/flash-sale.js

echo
echo "Data invariants (all must be 0):"
sleep 2
docker compose exec -T postgres psql -U "${POSTGRES_USER:-flashsale}" -d "${POSTGRES_DB:-flashsale}" \
  -c "$(cat load-test/verify.sql)"

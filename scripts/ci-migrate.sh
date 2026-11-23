#!/usr/bin/env bash
set -euo pipefail

ENV_NAME="${1:-}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FLYWAY_IMAGE="flyway/flyway:12.4.0-alpine"

if [ "$ENV_NAME" != "dev" ] && [ "$ENV_NAME" != "prod" ]; then
  echo "Usage: $0 <dev|prod>" >&2
  exit 2
fi

if [ "$ENV_NAME" = "dev" ]; then
  NETWORK_NAME="mini-banking-dev-net"
  export FLYWAY_URL="${DEV_DB_URL:?Set DEV_DB_URL}"
  export FLYWAY_USER="${DEV_DB_USERNAME:-mb_dev}"
  export FLYWAY_PASSWORD="${DEV_DB_PASSWORD:?Set DEV_DB_PASSWORD}"
else
  NETWORK_NAME="mini-banking-prod-net"
  export FLYWAY_URL="${PROD_DB_URL:?Set PROD_DB_URL}"
  export FLYWAY_USER="${PROD_DB_MIGRATION_USERNAME:-mb_prod_migrator}"
  export FLYWAY_PASSWORD="${PROD_DB_MIGRATION_PASSWORD:?Set PROD_DB_MIGRATION_PASSWORD}"
  APP_USER="${PROD_DB_USERNAME:-mb_prod_app}"
  url_without_query="${FLYWAY_URL%%\?*}"
  PROD_DB_NAME="${url_without_query##*/}"
  if [[ ! "$PROD_DB_NAME" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
    echo "ERROR: Invalid database name '${PROD_DB_NAME}' parsed from PROD_DB_URL." >&2
    exit 1
  fi
  if [[ ! "$APP_USER" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
    echo "ERROR: Invalid role identifier '${APP_USER}' in PROD_DB_USERNAME." >&2
    exit 1
  fi
fi

docker run --rm \
  --memory=384m \
  --network "$NETWORK_NAME" \
  -e JAVA_TOOL_OPTIONS="-Xmx256m -Xms64m" \
  -e FLYWAY_URL \
  -e FLYWAY_USER \
  -e FLYWAY_PASSWORD \
  -v "${REPO_ROOT}/mini-banking/src/main/resources/db/migration:/flyway/sql:ro" \
  "$FLYWAY_IMAGE" \
  migrate validate

if [ "$ENV_NAME" = "prod" ]; then
  pg_cid="$(docker compose -p mini-banking-prod ps -q postgres)"
  if [ -z "$pg_cid" ]; then
    echo "ERROR: prod postgres container not found after migration." >&2
    exit 1
  fi
  export PGPASSWORD="$FLYWAY_PASSWORD"
  docker exec -e PGPASSWORD "$pg_cid" psql -v ON_ERROR_STOP=1 \
    -U "$FLYWAY_USER" -d "$PROD_DB_NAME" \
    -c "REVOKE ALL ON TABLE public.flyway_schema_history FROM ${APP_USER};" >/dev/null
fi

echo "Flyway migrate + validate succeeded for ${ENV_NAME}."

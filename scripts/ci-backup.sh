#!/usr/bin/env bash
set -euo pipefail
umask 077

ENV_NAME="${1:-}"
MODE="${2:-}"
MIN_FREE_MB=2048

if [ "$ENV_NAME" != "dev" ] && [ "$ENV_NAME" != "prod" ]; then
  echo "Usage: $0 <dev|prod> [--online]" >&2
  exit 2
fi

if [ -n "$MODE" ] && [ "$MODE" != "--online" ]; then
  echo "Unsupported flag: $MODE (only --online is supported)" >&2
  exit 2
fi

extract_db_name() {
  local url="$1"
  local without_query="${url%%\?*}"
  local candidate="${without_query##*/}"
  if [[ ! "$candidate" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
    echo "ERROR: Invalid database name '${candidate}' parsed from DB URL." >&2
    exit 1
  fi
  printf '%s' "$candidate"
}

if [ "$ENV_NAME" = "dev" ]; then
  COMPOSE_FILE="docker-compose.dev.yml"
  OTHER_PROJECT="mini-banking-prod"
  BACKUP_DIR="${DEV_BACKUP_DIR:?Set DEV_BACKUP_DIR}"
  SUPER_USER="${DEV_POSTGRES_SUPER_USER:-lakeserl}"
  SUPER_PASSWORD="${DEV_POSTGRES_SUPER_PASSWORD:?Set DEV_POSTGRES_SUPER_PASSWORD}"
  DB_URL_VAL="${DEV_DB_URL:?Set DEV_DB_URL}"
  DB_NAME="$(extract_db_name "$DB_URL_VAL")"
  RETENTION=7
  REF_LABEL="${CI_COMMIT_SHORT_SHA:-manual}"

  export POSTGRES_PASSWORD="$SUPER_PASSWORD"
  export DEV_POSTGRES_SUPER_USER="$SUPER_USER"
  export DB_URL="$DB_URL_VAL"
  export DB_USERNAME="${DEV_DB_USERNAME:-mb_dev}"
  export DB_PASSWORD="${DEV_DB_PASSWORD:?Set DEV_DB_PASSWORD}"
  export JWT_SECRET="${DEV_JWT_SECRET:?Set DEV_JWT_SECRET}"
  export CORS_ALLOWED_ORIGINS="${DEV_CORS_ALLOWED_ORIGINS:-http://localhost:3000}"
else
  COMPOSE_FILE="docker-compose.prod.yml"
  OTHER_PROJECT="mini-banking"
  BACKUP_DIR="${PROD_BACKUP_DIR:?Set PROD_BACKUP_DIR}"
  SUPER_USER="${PROD_POSTGRES_SUPER_USER:-postgres}"
  SUPER_PASSWORD="${PROD_POSTGRES_SUPER_PASSWORD:?Set PROD_POSTGRES_SUPER_PASSWORD}"
  DB_URL_VAL="${PROD_DB_URL:?Set PROD_DB_URL}"
  DB_NAME="$(extract_db_name "$DB_URL_VAL")"
  RETENTION=14
  REF_LABEL="${CI_COMMIT_TAG:-${CI_COMMIT_SHORT_SHA:-nightly}}"

  export POSTGRES_PASSWORD="$SUPER_PASSWORD"
  export PROD_POSTGRES_SUPER_USER="$SUPER_USER"
  export DB_URL="$DB_URL_VAL"
  export DB_USERNAME="${PROD_DB_USERNAME:-mb_prod_app}"
  export DB_PASSWORD="${PROD_DB_PASSWORD:?Set PROD_DB_PASSWORD}"
  export PROD_JWT_ISSUER_URI="${PROD_JWT_ISSUER_URI:?Set PROD_JWT_ISSUER_URI}"
  # Deliberately <32 bytes so YAML interpolation passes for pg_dump while accidental app boot fails fast.
  export WITHDRAWAL_ACCOUNT_HASH_PEPPER="${PROD_WITHDRAWAL_ACCOUNT_HASH_PEPPER:-backup-unused}"
  export CORS_ALLOWED_ORIGINS="${PROD_CORS_ALLOWED_ORIGINS:-}"
  export IMAGE_REF="${IMAGE_REF:-mini-banking:placeholder}"
fi

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

free_mb="$(df -Pm "$BACKUP_DIR" | awk 'NR==2 {print $4}')"
if [ -z "$free_mb" ] || [ "$free_mb" -lt "$MIN_FREE_MB" ]; then
  echo "ERROR: Insufficient free disk space in ${BACKUP_DIR}: ${free_mb:-0} MB < ${MIN_FREE_MB} MB required." >&2
  exit 1
fi

if [ "$ENV_NAME" = "dev" ]; then
  other_cids="$(docker compose -p "$OTHER_PROJECT" ps -q)"
  if [ -n "$other_cids" ] && [ "${ALLOW_DEV_STOP_PROD:-false}" != "true" ]; then
    echo "ERROR: prod stack is currently running on this 8GB host; refusing to stop prod automatically." >&2
    echo "Run manually then Retry backup:dev:" >&2
    echo "  docker compose -p mini-banking-prod down -t 35" >&2
    echo "(Or start a new pipeline with temporary variable ALLOW_DEV_STOP_PROD=true.)" >&2
    exit 1
  fi
  if [ -n "$other_cids" ]; then
    docker compose -p "$OTHER_PROJECT" down -t 35
  fi
  dev_app_cid="$(docker compose -f "$COMPOSE_FILE" ps -q app)"
  if [ -n "$dev_app_cid" ]; then
    docker compose -f "$COMPOSE_FILE" stop -t 35 app
  fi
  docker compose -f "$COMPOSE_FILE" up -d --wait postgres
else
  if [ "$MODE" = "--online" ]; then
    other_cids="$(docker compose -p "$OTHER_PROJECT" ps -q)"
    if [ -n "$other_cids" ]; then
      echo "Skipping online prod backup because dev stack is active on this 8GB host."
      exit 0
    fi
    pg_running="$(docker compose -f "$COMPOSE_FILE" ps -q postgres)"
    if [ -z "$pg_running" ]; then
      echo "Skipping online prod backup because prod Postgres container is not running."
      exit 0
    fi
  else
    other_cids="$(docker compose -p "$OTHER_PROJECT" ps -q)"
    if [ -n "$other_cids" ]; then
      docker compose -p "$OTHER_PROJECT" down -t 35
    fi
    prod_app_cid="$(docker compose -f "$COMPOSE_FILE" ps -q app)"
    if [ -n "$prod_app_cid" ]; then
      docker compose -f "$COMPOSE_FILE" stop -t 35 app
    fi
    docker compose -f "$COMPOSE_FILE" up -d --wait postgres
  fi
fi

pg_cid="$(docker compose -f "$COMPOSE_FILE" ps -q postgres)"
if [ -z "$pg_cid" ]; then
  echo "ERROR: Target postgres container for ${ENV_NAME} is not running." >&2
  exit 1
fi

export PGPASSWORD="$SUPER_PASSWORD"
db_exists="$(docker exec -e PGPASSWORD "$pg_cid" psql -U "$SUPER_USER" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '${DB_NAME}'" | tr -d '[:space:]')"
if [ "$db_exists" != "1" ]; then
  echo "ERROR: Database '${DB_NAME}' is not initialized yet." >&2
  echo "Run scripts/init-${ENV_NAME}-cluster.sql (and init-prod-db-grants.sql for prod) in pgAdmin first." >&2
  exit 1
fi

timestamp="$(date -u +%Y%m%d-%H%M%S)"
safe_ref="$(printf '%s' "$REF_LABEL" | tr -c 'A-Za-z0-9._-' '_')"
final_file="${BACKUP_DIR}/${ENV_NAME}-${timestamp}-${safe_ref}.dump"
tmp_file="${final_file}.tmp"
trap 'rm -f "${tmp_file:-}"' EXIT

docker exec -e PGPASSWORD "$pg_cid" pg_dump -Fc -U "$SUPER_USER" -d "$DB_NAME" > "$tmp_file"
if [ ! -s "$tmp_file" ]; then
  echo "ERROR: Backup dump file is empty: ${tmp_file}" >&2
  exit 1
fi

docker exec -i "$pg_cid" pg_restore --list < "$tmp_file" >/dev/null
chmod 600 "$tmp_file"
mv "$tmp_file" "$final_file"
tmp_file=""

old_files="$(ls -1t "${BACKUP_DIR}/${ENV_NAME}-"*.dump 2>/dev/null | tail -n +$((RETENTION + 1)) || true)"
if [ -n "$old_files" ]; then
  printf '%s\n' "$old_files" | xargs -r -d '\n' rm -f --
fi

echo "Backup verified and saved: ${final_file} (retention=${RETENTION})"

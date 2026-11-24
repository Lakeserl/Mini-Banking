#!/usr/bin/env bash
set -euo pipefail

ENV_NAME="${1:-}"
READINESS_TIMEOUT_SECONDS="${READINESS_TIMEOUT_SECONDS:-90}"

if [ "$ENV_NAME" != "dev" ] && [ "$ENV_NAME" != "prod" ]; then
  echo "Usage: $0 <dev|prod>" >&2
  exit 2
fi

if [ "$ENV_NAME" = "dev" ]; then
  COMPOSE_FILE="docker-compose.dev.yml"
  OTHER_PROJECT="mini-banking-prod"
  DEPLOY_PORT="${DEV_DEPLOY_PORT:-8080}"
  IMAGE_TAG="${IMAGE_TAG:-dev-${CI_COMMIT_SHORT_SHA:-latest}}"
  export DEV_DEPLOY_PORT="$DEPLOY_PORT"
  export POSTGRES_PASSWORD="${DEV_POSTGRES_SUPER_PASSWORD:?Set DEV_POSTGRES_SUPER_PASSWORD}"
  export DEV_POSTGRES_SUPER_USER="${DEV_POSTGRES_SUPER_USER:-lakeserl}"
  export DB_URL="${DEV_DB_URL:?Set DEV_DB_URL}"
  export DB_USERNAME="${DEV_DB_USERNAME:-mb_dev}"
  export DB_PASSWORD="${DEV_DB_PASSWORD:?Set DEV_DB_PASSWORD}"
  export JWT_SECRET="${DEV_JWT_SECRET:?Set DEV_JWT_SECRET}"
  export CORS_ALLOWED_ORIGINS="${DEV_CORS_ALLOWED_ORIGINS:-http://localhost:3000}"
else
  COMPOSE_FILE="docker-compose.prod.yml"
  OTHER_PROJECT="mini-banking"
  DEPLOY_PORT="${PROD_DEPLOY_PORT:-8081}"
  BACKUP_DIR="${PROD_BACKUP_DIR:?Set PROD_BACKUP_DIR}"
  IMAGE_TAG="${IMAGE_TAG:-${CI_COMMIT_TAG:?Set IMAGE_TAG or CI_COMMIT_TAG}}"
  export PROD_DEPLOY_PORT="$DEPLOY_PORT"
  export POSTGRES_PASSWORD="${PROD_POSTGRES_SUPER_PASSWORD:?Set PROD_POSTGRES_SUPER_PASSWORD}"
  export PROD_POSTGRES_SUPER_USER="${PROD_POSTGRES_SUPER_USER:-postgres}"
  export DB_URL="${PROD_DB_URL:?Set PROD_DB_URL}"
  export DB_USERNAME="${PROD_DB_USERNAME:-mb_prod_app}"
  export DB_PASSWORD="${PROD_DB_PASSWORD:?Set PROD_DB_PASSWORD}"
  export PROD_JWT_ISSUER_URI="${PROD_JWT_ISSUER_URI:?Set PROD_JWT_ISSUER_URI}"
  export CORS_ALLOWED_ORIGINS="${PROD_CORS_ALLOWED_ORIGINS:-}"
fi

REGISTRY_IMAGE="${CI_REGISTRY_IMAGE:?Set CI_REGISTRY_IMAGE}"
export IMAGE_REF="${REGISTRY_IMAGE}:${IMAGE_TAG}"

other_cids="$(docker compose -p "$OTHER_PROJECT" ps -q)"
if [ "$ENV_NAME" = "dev" ]; then
  if [ -n "$other_cids" ] && [ "${ALLOW_DEV_STOP_PROD:-false}" != "true" ]; then
    echo "ERROR: prod stack is currently running on this 8GB host; refusing to stop prod automatically." >&2
    echo "Run manually then Retry deploy:dev:" >&2
    echo "  docker compose -p mini-banking-prod down -t 35" >&2
    echo "(Or start a new pipeline with temporary variable ALLOW_DEV_STOP_PROD=true.)" >&2
    exit 1
  fi
  if [ -n "$other_cids" ]; then
    docker compose -p "$OTHER_PROJECT" down -t 35
  fi
else
  if [ -n "$other_cids" ]; then
    docker compose -p "$OTHER_PROJECT" down -t 35
  fi
fi

logged_in=0
cleanup_login() {
  if [ "$logged_in" -eq 1 ] && [ -n "${CI_REGISTRY:-}" ]; then
    docker logout "$CI_REGISTRY" >/dev/null 2>&1 || true
  fi
}
trap cleanup_login EXIT

if [ -n "${CI_REGISTRY:-}" ] && [ -n "${CI_JOB_TOKEN:-}" ]; then
  printf '%s' "$CI_JOB_TOKEN" | docker login -u "${CI_REGISTRY_USER:-gitlab-ci-token}" --password-stdin "$CI_REGISTRY"
  logged_in=1
fi

docker compose -f "$COMPOSE_FILE" pull app
docker compose -f "$COMPOSE_FILE" up -d

readiness_url="http://127.0.0.1:${DEPLOY_PORT}/actuator/health/readiness"
deadline=$(( $(date +%s) + READINESS_TIMEOUT_SECONDS ))
ready=0
while [ "$(date +%s)" -le "$deadline" ]; do
  body="$(curl -fsS --max-time 3 "$readiness_url" 2>/dev/null || true)"
  if printf '%s' "$body" | grep -Fq '"status":"UP"'; then
    ready=1
    break
  fi
  sleep 2
done

if [ "$ready" -ne 1 ]; then
  echo "ERROR: ${ENV_NAME} readiness check failed after ${READINESS_TIMEOUT_SECONDS}s (${readiness_url})" >&2
  app_cid="$(docker compose -f "$COMPOSE_FILE" ps -a -q app 2>/dev/null || true)"
  if [ -n "$app_cid" ]; then
    docker inspect --format 'Status={{.State.Status}} ExitCode={{.State.ExitCode}} OOMKilled={{.State.OOMKilled}}' "$app_cid" >&2 || true
  fi
  docker compose -f "$COMPOSE_FILE" logs --tail=150 app >&2 || true
  exit 1
fi

if [ "$ENV_NAME" = "prod" ]; then
  umask 077
  mkdir -p "$BACKUP_DIR"
  chmod 700 "$BACKUP_DIR"
  last_good_file="${BACKUP_DIR}/prod-last-known-good.tag"
  prev_good_file="${BACKUP_DIR}/prod-previous-good.tag"

  current_last_good=""
  if [ -f "$last_good_file" ]; then
    current_last_good="$(tr -d '[:space:]' < "$last_good_file")"
  fi
  if [ -n "$current_last_good" ] && [ "$IMAGE_TAG" != "$current_last_good" ]; then
    printf '%s\n' "$current_last_good" > "${prev_good_file}.tmp"
    mv "${prev_good_file}.tmp" "$prev_good_file"
  fi
  printf '%s\n' "$IMAGE_TAG" > "${last_good_file}.tmp"
  mv "${last_good_file}.tmp" "$last_good_file"
else
  # Prune old dev-* local images while preserving current image and all prod tags
  old_dev_imgs="$(docker images --format '{{.Repository}}:{{.Tag}}' "$REGISTRY_IMAGE" 2>/dev/null | grep -E ':dev-' | grep -Fvx "$IMAGE_REF" | tail -n +3 || true)"
  if [ -n "$old_dev_imgs" ]; then
    printf '%s\n' "$old_dev_imgs" | xargs -r docker image rm >/dev/null 2>&1 || true
  fi
fi

echo "================================================================"
echo "DEPLOY SUCCEEDED"
echo "  Active env : ${ENV_NAME}"
echo "  Image tag  : ${IMAGE_TAG}"
echo "  URL        : http://127.0.0.1:${DEPLOY_PORT}"
echo "================================================================"

#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="${PROD_BACKUP_DIR:?Set PROD_BACKUP_DIR}"
last_good_file="${BACKUP_DIR}/prod-last-known-good.tag"
prev_good_file="${BACKUP_DIR}/prod-previous-good.tag"

target_tag="$(printf '%s' "${ROLLBACK_TAG:-}" | tr -d '[:space:]')"

if [ -z "$target_tag" ]; then
  if [ ! -f "$last_good_file" ]; then
    echo "ERROR: No prod-last-known-good.tag found in ${BACKUP_DIR}." >&2
    echo "Specify an explicit image tag via CI variable ROLLBACK_TAG (e.g. v1.0.0)." >&2
    exit 1
  fi
  last_good="$(tr -d '[:space:]' < "$last_good_file")"
  if [ -z "$last_good" ]; then
    echo "ERROR: prod-last-known-good.tag is empty in ${BACKUP_DIR}." >&2
    echo "Specify an explicit image tag via CI variable ROLLBACK_TAG (e.g. v1.0.0)." >&2
    exit 1
  fi

  running_tag=""
  app_cid="$(docker compose -p mini-banking-prod ps -q app 2>/dev/null || true)"
  if [ -n "$app_cid" ]; then
    running_image="$(docker inspect --format '{{.Config.Image}}' "$app_cid" 2>/dev/null || true)"
    image_name="${running_image##*/}"
    image_no_digest="${image_name%%@*}"
    if [[ "$image_no_digest" == *:* ]]; then
      running_tag="${image_no_digest##*:}"
    fi
  fi

  if [ -z "$running_tag" ] || [ "$running_tag" != "$last_good" ]; then
    target_tag="$last_good"
  else
    if [ ! -f "$prev_good_file" ]; then
      echo "ERROR: prod is running '${last_good}' and no prod-previous-good.tag exists in ${BACKUP_DIR}." >&2
      echo "Specify an explicit target via CI variable ROLLBACK_TAG." >&2
      exit 1
    fi
    target_tag="$(tr -d '[:space:]' < "$prev_good_file")"
  fi
fi

if [ -z "$target_tag" ]; then
  echo "ERROR: Resolved rollback tag is empty. Specify an explicit image tag via CI variable ROLLBACK_TAG." >&2
  exit 1
fi

dev_cids="$(docker compose -p mini-banking ps -q 2>/dev/null || true)"
if [ -n "$dev_cids" ]; then
  docker compose -p mini-banking down -t 35
fi

echo "Rolling back prod to tag: ${target_tag}"
echo "Warning: Consecutive automatic rollbacks toggle between the two latest pointers; pass ROLLBACK_TAG explicitly to step back further."
echo "Note: For schema rollbacks beyond expand-and-contract migrations, restore manually via pg_restore."
IMAGE_TAG="$target_tag" bash "${REPO_ROOT}/scripts/ci-deploy.sh" prod

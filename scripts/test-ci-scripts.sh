#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PASS_COUNT=0
FAIL_COUNT=0

assert_eq() {
  local expected="$1"
  local actual="$2"
  local msg="$3"
  if [ "$expected" != "$actual" ]; then
    echo "FAIL: $msg (expected='$expected', actual='$actual')" >&2
    return 1
  fi
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  local msg="$3"
  if ! printf '%s\n' "$haystack" | grep -Fq -- "$needle"; then
    echo "FAIL: $msg (missing '$needle' in output)" >&2
    printf -- '--- OUTPUT ---\n%s\n--------------\n' "$haystack" >&2
    return 1
  fi
}

assert_not_contains() {
  local haystack="$1"
  local needle="$2"
  local msg="$3"
  if printf '%s\n' "$haystack" | grep -Fq -- "$needle"; then
    echo "FAIL: $msg (unexpected '$needle' in output)" >&2
    return 1
  fi
}

setup_mock_env() {
  TEST_TMP="$(mktemp -d)"
  MOCK_BIN="$TEST_TMP/bin"
  MOCK_LOG="$TEST_TMP/docker.log"
  mkdir -p "$MOCK_BIN" "$TEST_TMP/backups/dev" "$TEST_TMP/backups/prod"
  : > "$MOCK_LOG"

  cat <<'EOF' > "$MOCK_BIN/df"
#!/usr/bin/env bash
echo "Filesystem 1048576-blocks Used Available Capacity Mounted on"
echo "/dev/sda1 50000 10000 ${MOCK_FREE_MB:-8192} 20% /"
EOF
  chmod +x "$MOCK_BIN/df"

  cat <<'EOF' > "$MOCK_BIN/curl"
#!/usr/bin/env bash
if [ "${MOCK_CURL_FAIL:-0}" = "1" ]; then
  exit 7
fi
printf '{"status":"UP"}\n'
EOF
  chmod +x "$MOCK_BIN/curl"

  cat <<'EOF' > "$MOCK_BIN/docker"
#!/usr/bin/env bash
echo "docker $*" >> "$MOCK_LOG"
case "$1" in
  login|logout|image|images)
    if [ "$1" = "images" ]; then
      printf '%s\n' "${MOCK_DEV_IMAGES:-}"
    fi
    exit 0
    ;;
  compose)
    shift
    compose_file=""
    project_name=""
    while [ $# -gt 0 ]; do
      case "$1" in
        -f) compose_file="$2"; shift 2 ;;
        -p) project_name="$2"; shift 2 ;;
        *) break ;;
      esac
    done
    # Enforce real Docker Compose YAML interpolation failures when -f is used without required vars
    if [ -n "$compose_file" ]; then
      if [[ "$compose_file" == *"prod"* ]]; then
        if [ -z "${POSTGRES_PASSWORD:-}" ] || [ -z "${DB_PASSWORD:-}" ] || [ -z "${PROD_JWT_ISSUER_URI:-}" ] || [ -z "${IMAGE_REF:-}" ]; then
          echo "ERROR: missing required prod compose env vars for -f $compose_file" >&2
          exit 1
        fi
      elif [[ "$compose_file" == *"dev"* ]]; then
        if [ -z "${POSTGRES_PASSWORD:-}" ] || [ -z "${DB_PASSWORD:-}" ] || [ -z "${JWT_SECRET:-}" ]; then
          echo "ERROR: missing required dev compose env vars for -f $compose_file" >&2
          exit 1
        fi
      fi
    fi

    target="$project_name"
    if [ -z "$target" ]; then
      if [[ "$compose_file" == *"prod"* ]]; then target="mini-banking-prod"; else target="mini-banking"; fi
    fi

    subcmd="${1:-}"
    shift || true
    case "$subcmd" in
      ps)
        service_filter=""
        for arg in "$@"; do
          case "$arg" in
            -q) ;;
            *) service_filter="$arg" ;;
          esac
        done
        if [ "$target" = "mini-banking-prod" ]; then
          if [ "$service_filter" = "app" ]; then
            [ "${MOCK_PROD_APP_RUNNING:-0}" = "1" ] && echo "prod-app-cid"
          elif [ "$service_filter" = "postgres" ]; then
            [ "${MOCK_PROD_PG_RUNNING:-1}" = "1" ] && echo "prod-pg-cid"
          else
            [ "${MOCK_PROD_APP_RUNNING:-0}" = "1" ] && echo "prod-app-cid"
            [ "${MOCK_PROD_OTHER_RUNNING:-0}" = "1" ] && echo "prod-kafka-cid"
          fi
        else
          if [ "$service_filter" = "app" ]; then
            [ "${MOCK_DEV_APP_RUNNING:-0}" = "1" ] && echo "dev-app-cid"
          elif [ "$service_filter" = "postgres" ]; then
            [ "${MOCK_DEV_PG_RUNNING:-1}" = "1" ] && echo "dev-pg-cid"
          else
            [ "${MOCK_DEV_APP_RUNNING:-0}" = "1" ] && echo "dev-app-cid"
            [ "${MOCK_DEV_OTHER_RUNNING:-0}" = "1" ] && echo "dev-kafka-cid"
          fi
        fi
        exit 0
        ;;
      down|stop|up|pull|logs)
        exit 0
        ;;
    esac
    ;;
  exec)
    if [[ "$*" == *"pg_dump"* ]]; then
      printf 'PGDMP_FAKE_ARCHIVE_DATA\n'
      exit 0
    elif [[ "$*" == *"pg_restore"* ]]; then
      cat >/dev/null
      exit 0
    elif [[ "$*" == *"psql"* ]]; then
      printf '1\n'
      exit 0
    fi
    exit 0
    ;;
  inspect)
    if [[ "$*" == *".Config.Image"* ]]; then
      printf '%s\n' "${MOCK_RUNNING_IMAGE:-}"
      exit 0
    fi
    printf 'Status=running ExitCode=0 OOMKilled=false\n'
    exit 0
    ;;
  run)
    exit 0
    ;;
esac
exit 0
EOF
  chmod +x "$MOCK_BIN/docker"
}

run_case() {
  local name="$1"
  shift
  setup_mock_env
  set +e
  ( set -euo pipefail; "$@" )
  local rc=$?
  set -e
  if [ "$rc" -eq 0 ]; then
    echo "PASS: $name"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "FAIL: $name" >&2
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
  rm -rf "$TEST_TMP"
}

test_1_dev_backup_blocked_when_prod_containers_running_without_prod_env_vars() {
  # Note: NO PROD_* vars in env (realistic dev job) and only prod kafka/pg running (non-app)
  set +e
  out=$(PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" MOCK_PROD_OTHER_RUNNING=1 \
    DEV_BACKUP_DIR="$TEST_TMP/backups/dev" \
    DEV_POSTGRES_SUPER_USER=lakeserl DEV_POSTGRES_SUPER_PASSWORD=dummy \
    DEV_DB_URL=jdbc:postgresql://postgres:5432/walletledger_dev \
    DEV_DB_USERNAME=mb_dev DEV_DB_PASSWORD=dummy DEV_JWT_SECRET=dummy-secret-32-bytes-minimum-0000 \
    bash "$REPO_ROOT/scripts/ci-backup.sh" dev 2>&1)
  rc=$?
  set -e
  [ "$rc" -ne 0 ] || { echo "Expected non-zero exit when prod containers running"; return 1; }
  assert_contains "$out" "docker compose -p mini-banking-prod down -t 35" "actionable project-scoped prod stop hint"
  assert_not_contains "$(cat "$MOCK_LOG")" "mini-banking-prod down" "must not tear down prod"
}

test_2_backup_fails_when_disk_below_2048mb() {
  set +e
  out=$(PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" MOCK_FREE_MB=512 \
    DEV_BACKUP_DIR="$TEST_TMP/backups/dev" \
    DEV_POSTGRES_SUPER_USER=lakeserl DEV_POSTGRES_SUPER_PASSWORD=dummy \
    DEV_DB_URL=jdbc:postgresql://postgres:5432/walletledger_dev \
    DEV_DB_USERNAME=mb_dev DEV_DB_PASSWORD=dummy DEV_JWT_SECRET=dummy-secret-32-bytes-minimum-0000 \
    bash "$REPO_ROOT/scripts/ci-backup.sh" dev 2>&1)
  rc=$?
  set -e
  [ "$rc" -ne 0 ] || { echo "Expected non-zero exit when disk < 2048 MB"; return 1; }
  assert_contains "$out" "2048" "disk threshold message"
  assert_not_contains "$(cat "$MOCK_LOG")" "pg_dump" "must not run pg_dump on low disk"
}

test_3_prod_backup_stops_dev_without_dev_vars_quiesces_prod_verifies_and_rotates() {
  for i in $(seq -w 1 16); do
    printf 'old' > "$TEST_TMP/backups/prod/prod-202601${i}-000000.dump"
    touch -d "2026-01-${i} 00:00:00" "$TEST_TMP/backups/prod/prod-202601${i}-000000.dump"
  done
  # Realistic prod job: NO JWT_SECRET / DEV_* vars exported!
  PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" MOCK_DEV_OTHER_RUNNING=1 MOCK_PROD_APP_RUNNING=1 \
    PROD_BACKUP_DIR="$TEST_TMP/backups/prod" \
    PROD_POSTGRES_SUPER_USER=postgres PROD_POSTGRES_SUPER_PASSWORD=dummy \
    PROD_DB_URL=jdbc:postgresql://postgres:5432/walletledger_prod \
    PROD_DB_USERNAME=mb_prod_app PROD_DB_PASSWORD=dummy \
    PROD_JWT_ISSUER_URI=https://issuer.example.test/realms/mini-banking \
    IMAGE_REF=registry.example/mini-banking:v1.0.0 \
    bash "$REPO_ROOT/scripts/ci-backup.sh" prod
  log="$(cat "$MOCK_LOG")"
  assert_contains "$log" "compose -p mini-banking down -t 35" "stops dev stack via -p without dev vars"
  assert_contains "$log" "docker-compose.prod.yml stop -t 35 app" "quiesces prod app"
  assert_contains "$log" "pg_dump -Fc" "runs custom-format pg_dump"
  assert_contains "$log" "pg_restore --list" "verifies archive inside postgres container"
  count=$(find "$TEST_TMP/backups/prod" -maxdepth 1 -name '*.dump' | wc -l)
  assert_eq "14" "$count" "rotates prod backups keeping 14"
}

test_4_prod_online_backup_skips_when_dev_containers_active() {
  out=$(PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" MOCK_DEV_OTHER_RUNNING=1 \
    PROD_BACKUP_DIR="$TEST_TMP/backups/prod" \
    PROD_POSTGRES_SUPER_USER=postgres PROD_POSTGRES_SUPER_PASSWORD=dummy \
    PROD_DB_URL=jdbc:postgresql://postgres:5432/walletledger_prod \
    PROD_DB_USERNAME=mb_prod_app PROD_DB_PASSWORD=dummy \
    PROD_JWT_ISSUER_URI=https://issuer.example.test/realms/mini-banking \
    IMAGE_REF=registry.example/mini-banking:v1.0.0 \
    bash "$REPO_ROOT/scripts/ci-backup.sh" prod --online 2>&1)
  assert_contains "$out" "Skipping online prod backup" "logs clean skip"
  assert_not_contains "$(cat "$MOCK_LOG")" "down -t 35" "must not stop active dev"
}

test_5_prod_migrate_uses_env_only_java_tool_options_and_revokes_flyway_history() {
  PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" \
    PROD_DB_URL="jdbc:postgresql://postgres:5432/custom_prod_db?sslmode=require" \
    PROD_DB_USERNAME="custom_app_role" \
    PROD_DB_MIGRATION_USERNAME="mb_prod_migrator" \
    PROD_DB_MIGRATION_PASSWORD="SUPER_SECRET_MIGRATOR_PASS" \
    bash "$REPO_ROOT/scripts/ci-migrate.sh" prod
  log="$(cat "$MOCK_LOG")"
  assert_contains "$log" "--network mini-banking-prod-net" "attaches to prod network"
  assert_contains "$log" "flyway/flyway:12.4.0-alpine" "uses pinned Flyway 12.4.0"
  assert_contains "$log" "JAVA_TOOL_OPTIONS=-Xmx256m -Xms64m" "caps Flyway JVM heap via JAVA_TOOL_OPTIONS"
  assert_contains "$log" "-e FLYWAY_URL -e FLYWAY_USER -e FLYWAY_PASSWORD" "passes Flyway creds by env var name only"
  assert_not_contains "$log" "SUPER_SECRET_MIGRATOR_PASS" "never leaks password in argv"
  assert_not_contains "$log" "jdbc:postgresql://" "never leaks URL in argv"
  assert_contains "$log" "-d custom_prod_db" "parses DB name from PROD_DB_URL"
  assert_contains "$log" "REVOKE ALL ON TABLE public.flyway_schema_history FROM custom_app_role" "revokes flyway_schema_history from parameterized PROD_DB_USERNAME"

  # Reject invalid identifier in PROD_DB_USERNAME before executing psql
  set +e
  bad_out=$(PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" \
    PROD_DB_URL="jdbc:postgresql://postgres:5432/walletledger_prod" \
    PROD_DB_USERNAME="bad;drop" \
    PROD_DB_MIGRATION_USERNAME="mb_prod_migrator" \
    PROD_DB_MIGRATION_PASSWORD="dummy" \
    bash "$REPO_ROOT/scripts/ci-migrate.sh" prod 2>&1)
  bad_rc=$?
  set -e
  [ "$bad_rc" -ne 0 ] || { echo "Expected non-zero exit for invalid PROD_DB_USERNAME"; return 1; }
  assert_contains "$bad_out" "Invalid" "reports invalid role identifier"
}

test_6_prod_deploy_dual_pointer_rotation_and_recovery_no_clobber() {
  PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" \
    CI_REGISTRY=registry.gitlab.com CI_REGISTRY_USER=gitlab-ci-token CI_JOB_TOKEN=tok \
    CI_REGISTRY_IMAGE=registry.gitlab.com/lakeserl/mini-banking \
    PROD_BACKUP_DIR="$TEST_TMP/backups/prod" PROD_DEPLOY_PORT=8081 \
    PROD_POSTGRES_SUPER_PASSWORD=dummy PROD_DB_URL=jdbc:postgresql://postgres:5432/walletledger_prod \
    PROD_DB_USERNAME=mb_prod_app PROD_DB_PASSWORD=dummy \
    PROD_JWT_ISSUER_URI=https://issuer.example.test/realms/mini-banking \
    IMAGE_TAG="v1.0.0" \
    bash "$REPO_ROOT/scripts/ci-deploy.sh" prod
  assert_eq "v1.0.0" "$(cat "$TEST_TMP/backups/prod/prod-last-known-good.tag")" "initial last-known-good"

  PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" \
    CI_REGISTRY=registry.gitlab.com CI_REGISTRY_USER=gitlab-ci-token CI_JOB_TOKEN=tok \
    CI_REGISTRY_IMAGE=registry.gitlab.com/lakeserl/mini-banking \
    PROD_BACKUP_DIR="$TEST_TMP/backups/prod" PROD_DEPLOY_PORT=8081 \
    PROD_POSTGRES_SUPER_PASSWORD=dummy PROD_DB_URL=jdbc:postgresql://postgres:5432/walletledger_prod \
    PROD_DB_USERNAME=mb_prod_app PROD_DB_PASSWORD=dummy \
    PROD_JWT_ISSUER_URI=https://issuer.example.test/realms/mini-banking \
    IMAGE_TAG="v1.1.0" \
    bash "$REPO_ROOT/scripts/ci-deploy.sh" prod
  assert_eq "v1.1.0" "$(cat "$TEST_TMP/backups/prod/prod-last-known-good.tag")" "upgraded last-known-good"
  assert_eq "v1.0.0" "$(cat "$TEST_TMP/backups/prod/prod-previous-good.tag")" "previous-good recorded"

  PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" \
    CI_REGISTRY=registry.gitlab.com CI_REGISTRY_USER=gitlab-ci-token CI_JOB_TOKEN=tok \
    CI_REGISTRY_IMAGE=registry.gitlab.com/lakeserl/mini-banking \
    PROD_BACKUP_DIR="$TEST_TMP/backups/prod" PROD_DEPLOY_PORT=8081 \
    PROD_POSTGRES_SUPER_PASSWORD=dummy PROD_DB_URL=jdbc:postgresql://postgres:5432/walletledger_prod \
    PROD_DB_USERNAME=mb_prod_app PROD_DB_PASSWORD=dummy \
    PROD_JWT_ISSUER_URI=https://issuer.example.test/realms/mini-banking \
    IMAGE_TAG="v1.1.0" \
    bash "$REPO_ROOT/scripts/ci-deploy.sh" prod
  assert_eq "v1.0.0" "$(cat "$TEST_TMP/backups/prod/prod-previous-good.tag")" "previous-good not clobbered on same-tag recovery"

  # Dev deploy prune must use exact full-line match (-Fvx), not substring match (-Fv)
  : > "$MOCK_LOG"
  MOCK_DEV_IMAGES="$(printf '%s\n%s\n%s\n%s\n%s' \
    'registry.gitlab.com/lakeserl/mini-banking:dev-abc123' \
    'registry.gitlab.com/lakeserl/mini-banking:dev-newer1' \
    'registry.gitlab.com/lakeserl/mini-banking:dev-newer2' \
    'registry.gitlab.com/lakeserl/mini-banking:dev-abc1234' \
    'registry.gitlab.com/lakeserl/mini-banking:v1.0.0')"
  PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" MOCK_DEV_IMAGES="$MOCK_DEV_IMAGES" \
    CI_REGISTRY=registry.gitlab.com CI_REGISTRY_USER=gitlab-ci-token CI_JOB_TOKEN=tok \
    CI_REGISTRY_IMAGE=registry.gitlab.com/lakeserl/mini-banking \
    DEV_DEPLOY_PORT=8080 DEV_POSTGRES_SUPER_PASSWORD=dummy \
    DEV_DB_URL=jdbc:postgresql://postgres:5432/walletledger_dev \
    DEV_DB_USERNAME=mb_dev DEV_DB_PASSWORD=dummy DEV_JWT_SECRET=dummy-secret-32-bytes-minimum-0000 \
    IMAGE_TAG="dev-abc123" \
    bash "$REPO_ROOT/scripts/ci-deploy.sh" dev
  dev_log="$(cat "$MOCK_LOG")"
  assert_contains "$dev_log" "docker image rm registry.gitlab.com/lakeserl/mini-banking:dev-abc1234" "prunes substring-overlapping older dev image"
  assert_not_contains "$dev_log" "docker image rm registry.gitlab.com/lakeserl/mini-banking:dev-abc123 " "never removes active dev image"
  assert_not_contains "$dev_log" "v1.0.0" "never removes prod tags during dev prune"
}

test_7_prod_rollback_stopped_app_stops_dev_and_rolls_back_live_release() {
  printf 'v1.0.0\n' > "$TEST_TMP/backups/prod/prod-last-known-good.tag"
  printf 'v0.9.0\n' > "$TEST_TMP/backups/prod/prod-previous-good.tag"

  # Case A: prod app stopped, dev running -> stops dev and recovers v1.0.0
  out_stopped=$(PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" MOCK_PROD_APP_RUNNING=0 MOCK_DEV_OTHER_RUNNING=1 MOCK_RUNNING_IMAGE="" \
    CI_REGISTRY=registry.gitlab.com CI_REGISTRY_USER=gitlab-ci-token CI_JOB_TOKEN=tok \
    CI_REGISTRY_IMAGE=registry.gitlab.com/lakeserl/mini-banking \
    PROD_BACKUP_DIR="$TEST_TMP/backups/prod" PROD_DEPLOY_PORT=8081 \
    PROD_POSTGRES_SUPER_PASSWORD=dummy PROD_DB_URL=jdbc:postgresql://postgres:5432/walletledger_prod \
    PROD_DB_USERNAME=mb_prod_app PROD_DB_PASSWORD=dummy \
    PROD_JWT_ISSUER_URI=https://issuer.example.test/realms/mini-banking \
    bash "$REPO_ROOT/scripts/ci-rollback.sh" 2>&1)
  assert_contains "$out_stopped" "Rolling back prod to tag: v1.0.0" "recovers last-known-good when stopped"
  assert_contains "$out_stopped" "ROLLBACK_TAG" "warns about consecutive rollbacks needing ROLLBACK_TAG"
  assert_contains "$(cat "$MOCK_LOG")" "compose -p mini-banking down -t 35" "stops active dev stack before rollback"
  assert_eq "v0.9.0" "$(cat "$TEST_TMP/backups/prod/prod-previous-good.tag")" "previous-good preserved after recovery"

  # Case B: v1.0.0 is currently live with registry port + tag -> rolls back to v0.9.0
  out_live=$(PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" MOCK_PROD_APP_RUNNING=1 \
    MOCK_RUNNING_IMAGE="registry.gitlab.com:5050/lakeserl/mini-banking:v1.0.0" \
    CI_REGISTRY=registry.gitlab.com CI_REGISTRY_USER=gitlab-ci-token CI_JOB_TOKEN=tok \
    CI_REGISTRY_IMAGE=registry.gitlab.com/lakeserl/mini-banking \
    PROD_BACKUP_DIR="$TEST_TMP/backups/prod" PROD_DEPLOY_PORT=8081 \
    PROD_POSTGRES_SUPER_PASSWORD=dummy PROD_DB_URL=jdbc:postgresql://postgres:5432/walletledger_prod \
    PROD_DB_USERNAME=mb_prod_app PROD_DB_PASSWORD=dummy \
    PROD_JWT_ISSUER_URI=https://issuer.example.test/realms/mini-banking \
    bash "$REPO_ROOT/scripts/ci-rollback.sh" 2>&1)
  assert_contains "$out_live" "Rolling back prod to tag: v0.9.0" "rolls back live release with registry port to previous-good"

  # Case C: digest-only or port-only image ref (no tag) must NOT misparse digest/port as tag
  printf '5000/mini-banking\n' > "$TEST_TMP/backups/prod/prod-last-known-good.tag"
  printf 'v0.9.0\n' > "$TEST_TMP/backups/prod/prod-previous-good.tag"
  out_port=$(PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" MOCK_PROD_APP_RUNNING=1 \
    MOCK_RUNNING_IMAGE="localhost:5000/mini-banking" \
    CI_REGISTRY=registry.gitlab.com CI_REGISTRY_USER=gitlab-ci-token CI_JOB_TOKEN=tok \
    CI_REGISTRY_IMAGE=registry.gitlab.com/lakeserl/mini-banking \
    PROD_BACKUP_DIR="$TEST_TMP/backups/prod" PROD_DEPLOY_PORT=8081 \
    PROD_POSTGRES_SUPER_PASSWORD=dummy PROD_DB_URL=jdbc:postgresql://postgres:5432/walletledger_prod \
    PROD_DB_USERNAME=mb_prod_app PROD_DB_PASSWORD=dummy \
    PROD_JWT_ISSUER_URI=https://issuer.example.test/realms/mini-banking \
    bash "$REPO_ROOT/scripts/ci-rollback.sh" 2>&1)
  assert_contains "$out_port" "Rolling back prod to tag: 5000/mini-banking" "does not treat registry port as tag"

  printf 'deadbeef\n' > "$TEST_TMP/backups/prod/prod-last-known-good.tag"
  printf 'v0.9.0\n' > "$TEST_TMP/backups/prod/prod-previous-good.tag"
  out_digest=$(PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" MOCK_PROD_APP_RUNNING=1 \
    MOCK_RUNNING_IMAGE="registry.gitlab.com/lakeserl/mini-banking@sha256:deadbeef" \
    CI_REGISTRY=registry.gitlab.com CI_REGISTRY_USER=gitlab-ci-token CI_JOB_TOKEN=tok \
    CI_REGISTRY_IMAGE=registry.gitlab.com/lakeserl/mini-banking \
    PROD_BACKUP_DIR="$TEST_TMP/backups/prod" PROD_DEPLOY_PORT=8081 \
    PROD_POSTGRES_SUPER_PASSWORD=dummy PROD_DB_URL=jdbc:postgresql://postgres:5432/walletledger_prod \
    PROD_DB_USERNAME=mb_prod_app PROD_DB_PASSWORD=dummy \
    PROD_JWT_ISSUER_URI=https://issuer.example.test/realms/mini-banking \
    bash "$REPO_ROOT/scripts/ci-rollback.sh" 2>&1)
  assert_contains "$out_digest" "Rolling back prod to tag: deadbeef" "does not treat sha256 digest as tag"
}

test_8_prod_rollback_fails_cleanly_on_missing_or_empty_pointers() {
  # Missing pointer
  set +e
  out=$(PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" \
    CI_REGISTRY_IMAGE=registry.gitlab.com/lakeserl/mini-banking \
    PROD_BACKUP_DIR="$TEST_TMP/backups/prod" \
    CI_COMMIT_TAG="v1.1.0-broken" \
    bash "$REPO_ROOT/scripts/ci-rollback.sh" 2>&1)
  rc=$?
  set -e
  [ "$rc" -ne 0 ] || { echo "Expected non-zero exit when pointers missing"; return 1; }
  assert_contains "$out" "ROLLBACK_TAG" "instructs operator to pass ROLLBACK_TAG"

  # 0-byte empty pointer must NOT silently fall back to CI_COMMIT_TAG
  : > "$TEST_TMP/backups/prod/prod-last-known-good.tag"
  set +e
  out_empty=$(PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" \
    CI_REGISTRY_IMAGE=registry.gitlab.com/lakeserl/mini-banking \
    PROD_BACKUP_DIR="$TEST_TMP/backups/prod" \
    CI_COMMIT_TAG="v1.1.0-broken" \
    bash "$REPO_ROOT/scripts/ci-rollback.sh" 2>&1)
  rc_empty=$?
  set -e
  [ "$rc_empty" -ne 0 ] || { echo "Expected non-zero exit when pointer file is empty"; return 1; }
  assert_contains "$out_empty" "ROLLBACK_TAG" "rejects empty pointer file"
}

test_9_gitlab_ci_and_compose_security_invariants() {
  local gl="$REPO_ROOT/.gitlab-ci.yml"
  local prod_c="$REPO_ROOT/docker-compose.prod.yml"
  local leg_c="$REPO_ROOT/docker-compose.yml"
  local prod_grants="$REPO_ROOT/scripts/init-prod-db-grants.sql"
  local mirror_wf="$REPO_ROOT/.github/workflows/mirror-to-gitlab.yml"

  local m2_repo="${M2_REPO:-${HOME}/.m2/repository}"
  SNAKEYAML="$(find "${m2_repo}/org/yaml/snakeyaml" -name 'snakeyaml-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | head -n 1 || true)"
  if [ -z "$SNAKEYAML" ]; then
    echo "ERROR: snakeyaml jar not found under ${m2_repo}/org/yaml/snakeyaml" >&2
    return 1
  fi

  local java_bin=""
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    java_bin="${JAVA_HOME}/bin/java"
  else
    for candidate in /usr/lib/jvm/java-21-openjdk/bin/java /usr/lib/jvm/java-21-openjdk-amd64/bin/java "$(command -v java 2>/dev/null || true)"; do
      if [ -n "$candidate" ] && [ -x "$candidate" ]; then
        java_bin="$candidate"
        break
      fi
    done
  fi
  if [ -z "$java_bin" ]; then
    echo "ERROR: no java executable found" >&2
    return 1
  fi

  cat <<'EOF' > "$TEST_TMP/VerifyCi.java"
import org.yaml.snakeyaml.Yaml;
import java.nio.file.*;
import java.util.*;
public class VerifyCi {
  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    Map<String, Object> doc = new Yaml().load(Files.readString(Path.of(args[0])));
    for (String jobName : List.of("approve-and-backup:prod", "rollback:prod")) {
      Map<String, Object> job = (Map<String, Object>) doc.get(jobName);
      if (job.containsKey("when")) throw new IllegalStateException(jobName + " must not mix job-level when: with rules:");
      List<Map<String, Object>> rules = (List<Map<String, Object>>) job.get("rules");
      boolean hasManual = rules != null && rules.stream().anyMatch(r -> "manual".equals(r.get("when")));
      if (!hasManual) throw new IllegalStateException(jobName + " must have when: manual inside rules:");
    }
    Map<String, Object> nightly = (Map<String, Object>) doc.get("nightly:backup-prod");
    if (!List.of().equals(nightly.get("needs"))) throw new IllegalStateException("nightly:backup-prod must have needs: []");
    for (String nonSecretJob : List.of("test:dev", "test:prod", "build:dev", "build:prod")) {
      Map<String, Object> nj = (Map<String, Object>) doc.get(nonSecretJob);
      if (nj.containsKey("environment")) throw new IllegalStateException(nonSecretJob + " must not expose env secrets via environment:");
    }
    System.out.println("CI_YAML_INVARIANTS_OK");
  }
}
EOF
  "$java_bin" -cp "$SNAKEYAML" "$TEST_TMP/VerifyCi.java" "$gl"

  assert_contains "$(cat "$gl")" 'if ! "${JAVA_HOME:-/nonexistent}/bin/java" -version' "java21_setup checks actual java version of JAVA_HOME"
  assert_contains "$(cat "$prod_c")" "- SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI" "prod compose forwards optional JWK_SET_URI"
  assert_contains "$(cat "$leg_c")" '"127.0.0.1:5410:5432"' "legacy docker-compose.yml binds loopback 127.0.0.1"
  assert_contains "$(cat "$prod_grants")" "to_regclass('public.flyway_schema_history')" "init-prod-db-grants.sql guards flyway_schema_history on re-run"
  assert_contains "$(cat "$prod_grants")" "REVOKE ALL ON TABLE public.flyway_schema_history FROM mb_prod_app" "init-prod-db-grants.sql revokes flyway_schema_history from mb_prod_app"
  assert_contains "$(cat "$mirror_wf")" "persist-credentials: false" "mirror workflow disables checkout credential persistence"
}

test_10_dev_backup_quiesces_dev_app_and_parses_custom_db_name() {
  PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" MOCK_DEV_APP_RUNNING=1 \
    DEV_BACKUP_DIR="$TEST_TMP/backups/dev" \
    DEV_POSTGRES_SUPER_USER=lakeserl DEV_POSTGRES_SUPER_PASSWORD=dummy \
    DEV_DB_URL="jdbc:postgresql://postgres:5432/custom_dev_db?sslmode=disable" \
    DEV_DB_USERNAME=mb_dev DEV_DB_PASSWORD=dummy DEV_JWT_SECRET=dummy-secret-32-bytes-minimum-0000 \
    bash "$REPO_ROOT/scripts/ci-backup.sh" dev
  log="$(cat "$MOCK_LOG")"
  assert_contains "$log" "docker-compose.dev.yml stop -t 35 app" "quiesces running dev app before backup/migrate"
  assert_contains "$log" "pg_dump -Fc -U lakeserl -d custom_dev_db" "parses DB name from DEV_DB_URL"
  stop_line="$(grep -n "docker-compose.dev.yml stop -t 35 app" "$MOCK_LOG" | head -n1 | cut -d: -f1)"
  up_line="$(grep -n "docker-compose.dev.yml up -d --wait postgres" "$MOCK_LOG" | head -n1 | cut -d: -f1)"
  [ "$stop_line" -lt "$up_line" ] || { echo "Expected dev app stop before postgres up/dump"; return 1; }

  # Reject invalid DB name in DEV_DB_URL before executing psql/pg_dump
  set +e
  bad_out=$(PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" \
    DEV_BACKUP_DIR="$TEST_TMP/backups/dev" \
    DEV_POSTGRES_SUPER_USER=lakeserl DEV_POSTGRES_SUPER_PASSWORD=dummy \
    DEV_DB_URL="jdbc:postgresql://postgres:5432/bad;drop" \
    DEV_DB_USERNAME=mb_dev DEV_DB_PASSWORD=dummy DEV_JWT_SECRET=dummy-secret-32-bytes-minimum-0000 \
    bash "$REPO_ROOT/scripts/ci-backup.sh" dev 2>&1)
  bad_rc=$?
  set -e
  [ "$bad_rc" -ne 0 ] || { echo "Expected non-zero exit for invalid DB name in DEV_DB_URL"; return 1; }
  assert_contains "$bad_out" "Invalid" "reports invalid database identifier"
}

run_case "1. dev backup fails fast when prod containers running (no prod vars)" test_1_dev_backup_blocked_when_prod_containers_running_without_prod_env_vars
run_case "2. backup fails fast when disk < 2048 MB" test_2_backup_fails_when_disk_below_2048mb
run_case "3. prod backup stops dev via -p, quiesces prod, verifies & rotates" test_3_prod_backup_stops_dev_without_dev_vars_quiesces_prod_verifies_and_rotates
run_case "4. prod --online backup skips when dev containers active" test_4_prod_online_backup_skips_when_dev_containers_active
run_case "5. prod migrate uses env-only + JAVA_TOOL_OPTIONS & revokes flyway history" test_5_prod_migrate_uses_env_only_java_tool_options_and_revokes_flyway_history
run_case "6. prod deploy dual-pointer rotation, no-clobber & dev exact-line prune" test_6_prod_deploy_dual_pointer_rotation_and_recovery_no_clobber
run_case "7. prod rollback stops dev, handles port/digest images & rolls back live release" test_7_prod_rollback_stopped_app_stops_dev_and_rolls_back_live_release
run_case "8. prod rollback fails cleanly on missing or 0-byte pointers" test_8_prod_rollback_fails_cleanly_on_missing_or_empty_pointers
run_case "9. gitlab-ci, mirror workflow, grants & compose security invariants" test_9_gitlab_ci_and_compose_security_invariants
run_case "10. dev backup quiesces dev app & parses/validates DB name from URL" test_10_dev_backup_quiesces_dev_app_and_parses_custom_db_name

echo "=== Summary: $PASS_COUNT passed, $FAIL_COUNT failed ==="
[ "$FAIL_COUNT" -eq 0 ]

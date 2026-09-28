#!/usr/bin/env bash
#
# One command for the recurring practitioner mailing-list export. Runs every
# step end to end and cleans up after itself:
#
#   1. reads the RDS credentials from the namespace secret (held in memory only)
#   2. starts a Cloud Platform port-forward pod and a local port-forward to it
#   3. runs scripts/practitioner_contact_list.sql            (read-only, rolls back)
#   4. gets a token with the UI's system client credentials  (they carry
#      ROLE_ESUPERVISION__ESUPERVISION_UI; practitioners do not)
#   5. runs scripts/fetch_practitioner_details.sh, twice by default: the second
#      pass retries 404s, which can be NDelius blips rather than missing CRNs
#   6. deletes the port-forward pod, whatever happened above
#
# Prerequisites: kubectl authenticated to Cloud Platform with access to the
# namespace, psql, jq, curl -- and the MoJ network, because the API ingress is
# allowlisted.
#
# Usage:
#   ./scripts/run_practitioner_export.sh [work_dir]
#
#   work_dir defaults to ~/esup-practitioner-export/<today>, so each run keeps
#   its own dated folder. It must be outside this repo: the outputs are staff
#   names and emails tied to the CRNs they supervise.
#
# Env:
#   ENV            dev | test | preprod | prod (default prod). Try dev first.
#   LOCAL_PG_PORT  local end of the database port-forward, default 5433 (5432 is
#                  usually taken by the docker-compose postgres)
#   PASSES         fetch passes, default 2
#   UI_NAMESPACE   namespace holding hmpps-esupervision-ui-client-creds, default
#                  the API's own namespace
#   EXPORT_API_BASE, EXPORT_AUTH_URL
#                  override the per-environment API and HMPPS Auth URLs
#   RATE_SLEEP, USERNAMES   passed through to fetch_practitioner_details.sh
#
# Outputs, in work_dir:
#   practitioner_export.csv      the deliverable: PDU, Region, CRN, POP count,
#                                Email address -- one row per practitioner
#   practitioners_unmatched.csv  worksheet for the rows with no email
#   sql_report.txt               the SQL step's coverage reports
#   plus the intermediate JSONL, kept so a re-run resumes rather than refetches

set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ENV="${ENV:-prod}"
LOCAL_PG_PORT="${LOCAL_PG_PORT:-5433}"
PASSES="${PASSES:-2}"
NS="hmpps-esupervision-$ENV"
UI_NAMESPACE="${UI_NAMESPACE:-$NS}"
WORK_DIR="${1:-$HOME/esup-practitioner-export/$(date +%Y-%m-%d)}"

# Hosts from helm_deploy/values-<env>.yaml.
case "$ENV" in
  prod)    default_api=https://esupervision-api.hmpps.service.justice.gov.uk
           default_auth=https://sign-in.hmpps.service.justice.gov.uk/auth ;;
  preprod) default_api=https://esupervision-api-preprod.hmpps.service.justice.gov.uk
           default_auth=https://sign-in-preprod.hmpps.service.justice.gov.uk/auth ;;
  test)    default_api=https://esupervision-api-test.hmpps.service.justice.gov.uk
           default_auth=https://sign-in-dev.hmpps.service.justice.gov.uk/auth ;;
  dev)     default_api=https://esupervision-api-dev.hmpps.service.justice.gov.uk
           default_auth=https://sign-in-dev.hmpps.service.justice.gov.uk/auth ;;
  *) echo "ERROR: ENV must be dev, test, preprod or prod (got '$ENV')" >&2; exit 1 ;;
esac
# EXPORT_API_BASE / EXPORT_AUTH_URL override the defaults, e.g. to point at a
# service port-forward. Deliberately not API_BASE, which is common enough in a
# shell to leak in by accident and send a prod export somewhere else.
export API_BASE="${EXPORT_API_BASE:-$default_api}"
AUTH_URL="${EXPORT_AUTH_URL:-$default_auth}"

step() { printf '\n==> %s\n' "$*" >&2; }
die()  { echo "ERROR: $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
for cmd in kubectl psql jq curl; do
  command -v "$cmd" >/dev/null || die "$cmd is required"
done

mkdir -p "$WORK_DIR"
WORK_DIR="$(cd -- "$WORK_DIR" && pwd -P)"
case "$WORK_DIR/" in
  "$(cd -- "$REPO_ROOT" && pwd -P)/"*)
    die "work_dir $WORK_DIR is inside the repo. The outputs are personal data -- pick a directory outside it." ;;
esac

kubectl -n "$NS" auth can-i create pods >/dev/null 2>&1 \
  || die "kubectl cannot create pods in $NS -- check your Cloud Platform login and context"

echo "Environment: $ENV (namespace $NS)" >&2
echo "API:         $API_BASE" >&2
echo "Auth:        $AUTH_URL" >&2
echo "Output:      $WORK_DIR" >&2

# ---------------------------------------------------------------------------
# Secrets, held in shell variables only -- never written to disk
# ---------------------------------------------------------------------------
secret_value() {  # namespace secret key
  kubectl -n "$1" get secret "$2" -o json | jq -er --arg k "$3" '.data[$k] | @base64d'
}

step "Reading database credentials"
RDS_ENDPOINT=$(secret_value "$NS" hmpps-esupervision-rds-settings rds_instance_endpoint)
RDS_HOST="${RDS_ENDPOINT%%:*}"
PGUSER=$(secret_value "$NS" hmpps-esupervision-rds-settings database_username)
PGDATABASE=$(secret_value "$NS" hmpps-esupervision-rds-settings database_name)
PGPASSWORD=$(secret_value "$NS" hmpps-esupervision-rds-settings database_password)
export PGUSER PGDATABASE PGPASSWORD PGHOST=127.0.0.1 PGPORT="$LOCAL_PG_PORT"

step "Reading the UI system client credentials"
CLIENT_ID=$(secret_value "$UI_NAMESPACE" hmpps-esupervision-ui-client-creds CLIENT_CREDS_CLIENT_ID) \
  || die "could not read hmpps-esupervision-ui-client-creds in $UI_NAMESPACE (set UI_NAMESPACE if the UI lives elsewhere)"
CLIENT_SECRET=$(secret_value "$UI_NAMESPACE" hmpps-esupervision-ui-client-creds CLIENT_CREDS_CLIENT_SECRET)

# ---------------------------------------------------------------------------
# Port-forward, torn down on any exit
# ---------------------------------------------------------------------------
POD="practitioner-export-$(whoami | tr -cd 'a-z0-9' | cut -c1-20)-$$"
PF_PID=""

cleanup() {
  local rc=$?
  [[ -n "$PF_PID" ]] && kill "$PF_PID" 2>/dev/null || true
  echo "Deleting port-forward pod $POD" >&2
  kubectl -n "$NS" delete pod "$POD" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

step "Starting port-forward pod $POD"
kubectl -n "$NS" run "$POD" --image=ministryofjustice/port-forward --restart=Never \
  --port=5432 --env="REMOTE_HOST=$RDS_HOST" --env="LOCAL_PORT=5432" --env="REMOTE_PORT=5432" >/dev/null
kubectl -n "$NS" wait --for=condition=Ready "pod/$POD" --timeout=120s >/dev/null

kubectl -n "$NS" port-forward "pod/$POD" "$LOCAL_PG_PORT:5432" >/dev/null 2>&1 &
PF_PID=$!

for _ in $(seq 1 30); do
  psql -qtAc 'select 1' >/dev/null 2>&1 && break
  kill -0 "$PF_PID" 2>/dev/null || die "port-forward exited -- is local port $LOCAL_PG_PORT free? (set LOCAL_PG_PORT)"
  sleep 1
done
psql -qtAc 'select 1' >/dev/null || die "database not reachable on 127.0.0.1:$LOCAL_PG_PORT"

# ---------------------------------------------------------------------------
# SQL: CRNs, geography, usernames
# ---------------------------------------------------------------------------
step "Extracting CRNs (read-only)"
(cd "$WORK_DIR" && psql -X -v ON_ERROR_STOP=1 -f "$REPO_ROOT/scripts/practitioner_contact_list.sql") \
  > "$WORK_DIR/sql_report.txt" 2>&1 \
  || { cat "$WORK_DIR/sql_report.txt" >&2; die "SQL step failed"; }
echo "$(wc -l < "$WORK_DIR/practitioner_crns.jsonl" | tr -d ' ') CRNs extracted (reports in sql_report.txt)" >&2

# The database is no longer needed; stop the port-forward now rather than
# holding it open through the API step.
kill "$PF_PID" 2>/dev/null || true; PF_PID=""
kubectl -n "$NS" delete pod "$POD" --ignore-not-found --wait=false >/dev/null 2>&1 || true

# ---------------------------------------------------------------------------
# Fetch: a fresh token for every pass, one retry on an auth failure
# ---------------------------------------------------------------------------
new_token() {
  # Credentials go in on stdin (as a curl config) rather than argv.
  TOKEN=$(printf 'user = "%s:%s"\n' "$CLIENT_ID" "$CLIENT_SECRET" \
    | curl -sf -K - -X POST "$AUTH_URL/oauth/token?grant_type=client_credentials" \
    | jq -er .access_token) || die "could not get a token from $AUTH_URL"
  export TOKEN
}

for pass in $(seq 1 "$PASSES"); do
  step "Fetching practitioner details, pass $pass of $PASSES"
  new_token
  if ! "$REPO_ROOT/scripts/fetch_practitioner_details.sh" \
         "$WORK_DIR/practitioner_crns.jsonl" "$WORK_DIR/practitioners.jsonl"; then
    echo "Pass $pass stopped early -- refreshing the token and resuming once" >&2
    new_token
    "$REPO_ROOT/scripts/fetch_practitioner_details.sh" \
      "$WORK_DIR/practitioner_crns.jsonl" "$WORK_DIR/practitioners.jsonl" \
      || die "fetch failed twice; see the errors above. Re-running this script resumes."
  fi
done

step "Done"
cat >&2 <<EOF
  Mailing list: $WORK_DIR/practitioner_export.csv
  Worksheet:    $WORK_DIR/practitioners_unmatched.csv  (usernames for the blank rows)
  SQL reports:  $WORK_DIR/sql_report.txt

  These files are personal data. Delete $WORK_DIR once the export is handed over.
EOF

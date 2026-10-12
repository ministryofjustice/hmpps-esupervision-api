#!/usr/bin/env bash
#
# One command for the recurring practitioner mailing-list export. Runs every
# step end to end and cleans up after itself:
#
#   1. reads the RDS credentials from the namespace secret (held in memory only)
#   2. starts a Cloud Platform port-forward pod and a local port-forward to it
#   3. runs scripts/practitioner_contact_list.sql -- two SELECTs in a session
#      that Postgres itself holds read-only (checked before anything runs), then
#      builds the summary reports locally from the two files it downloads
#   4. gets a token with the UI's system client credentials  (they carry
#      ROLE_ESUPERVISION__ESUPERVISION_UI; practitioners do not). By default the
#      request is made from inside a running UI pod, via kubectl exec: HMPPS Auth
#      can restrict a client to its cluster's IPs, which a laptop is not, and
#      this way the client secret never leaves the cluster -- only the token does
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
#   work_dir defaults to ~/esup-practitioner-export/<today>-<env>, so each run
#   keeps its own folder. It must be outside this repo: the outputs are staff
#   names and emails tied to the CRNs they supervise.
#
# Fresh versus resumed: every CRN's practitioner details are fetched afresh on
# each run, EXCEPT when the previous run in the same folder was interrupted
# (expired token, network drop, Ctrl-C) -- then the CRNs it already fetched are
# kept and only the rest are requested. A run that finishes leaves a
# .export-complete marker, and a folder remembers its environment in
# .export-env, so neither a finished export nor another environment's results
# are ever silently reused. Previous results are kept as
# practitioners.previous.jsonl when a run starts afresh.
#
# Env:
#   ENV            dev | test | preprod | prod (default prod). Try dev first.
#   LOCAL_PG_PORT  local end of the database port-forward, default 5433 (5432 is
#                  usually taken by the docker-compose postgres)
#   PASSES         fetch passes, default 2
#   TOKEN_SOURCE   pod (default) -- request the token from inside the UI pod;
#                  laptop -- read the UI client secret and request it from here,
#                  which only works where HMPPS Auth does not IP-restrict the client
#   UI_NAMESPACE   namespace of the UI deployment and its client-creds secret,
#                  default the API's own namespace
#   UI_DEPLOYMENT  default hmpps-esupervision-ui
#   EXPORT_API_BASE, EXPORT_AUTH_URL
#                  override the per-environment API and HMPPS Auth URLs (the
#                  auth URL is used in laptop mode only; the pod uses its own)
#   RATE_SLEEP, USERNAMES   passed through to fetch_practitioner_details.sh
#
# Outputs, in work_dir:
#   practitioner_export.csv      the deliverable: PDU, Region, CRN, POP count,
#                                First name, Email address -- one row per
#                                practitioner
#   practitioners_unmatched.csv  worksheet for the rows with no email
#   extract_report.txt           summary of what was extracted: CRNs by status
#                                and by region/PDU, usernames not on record for any CRN,
#                                usernames that look like service accounts
#   plus the intermediate JSONL, kept so a re-run resumes rather than refetches

set -euo pipefail

# Everything this creates is personal data: files 0600, directories 0700, so
# other users on a shared host cannot read them whatever their default umask.
umask 077

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ENV="${ENV:-prod}"
LOCAL_PG_PORT="${LOCAL_PG_PORT:-5433}"
PASSES="${PASSES:-2}"
NS="hmpps-esupervision-$ENV"
UI_NAMESPACE="${UI_NAMESPACE:-$NS}"
UI_DEPLOYMENT="${UI_DEPLOYMENT:-hmpps-esupervision-ui}"
TOKEN_SOURCE="${TOKEN_SOURCE:-pod}"
WORK_DIR="${1:-$HOME/esup-practitioner-export/$(date +%Y-%m-%d)-$ENV}"

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

# Zero or junk would run no fetch at all, yet still mark the export complete.
[[ "$PASSES" =~ ^[1-9][0-9]*$ ]] \
  || { echo "ERROR: PASSES must be a positive whole number (got '$PASSES')" >&2; exit 1; }

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

# umask made the folder private if this run created it, but mkdir -p leaves an
# existing one alone -- one from before this script set a umask, or one passed
# in. Make it private, and anything already inside it: psql's \o and mv keep an
# existing file's mode, so an old 0644 file would stay readable.
if [[ "$(ls -ld "$WORK_DIR" | cut -c1-10)" != "drwx------" ]]; then
  chmod 700 "$WORK_DIR"
  echo "Made $WORK_DIR private (it was readable by other users)" >&2
fi
find "$WORK_DIR" -maxdepth 1 -type f ! -perm 600 -exec chmod 600 {} +

kubectl -n "$NS" auth can-i create pods >/dev/null 2>&1 \
  || die "kubectl cannot create pods in $NS -- check your Cloud Platform login and context"

case "$TOKEN_SOURCE" in
  pod)
    kubectl -n "$UI_NAMESPACE" auth can-i create pods/exec >/dev/null 2>&1 \
      || die "kubectl cannot exec into pods in $UI_NAMESPACE (needed for TOKEN_SOURCE=pod)"
    kubectl -n "$UI_NAMESPACE" get "deploy/$UI_DEPLOYMENT" >/dev/null 2>&1 \
      || die "no deployment $UI_DEPLOYMENT in $UI_NAMESPACE -- set UI_NAMESPACE / UI_DEPLOYMENT (kubectl -n <ns> get deploy)"
    token_desc="from a $UI_DEPLOYMENT pod in $UI_NAMESPACE" ;;
  laptop)
    token_desc="from this machine, via $AUTH_URL" ;;
  *) die "TOKEN_SOURCE must be pod or laptop (got '$TOKEN_SOURCE')" ;;
esac

echo "Environment: $ENV (namespace $NS)" >&2
echo "API:         $API_BASE" >&2
echo "Token:       $token_desc" >&2
echo "Output:      $WORK_DIR" >&2

# ---------------------------------------------------------------------------
# Fresh or resumed? Decided before anything runs, and the completion marker is
# removed now, so a run that dies at any later step is resumable next time.
# ---------------------------------------------------------------------------
RESULTS="$WORK_DIR/practitioners.jsonl"
COMPLETE_MARKER="$WORK_DIR/.export-complete"
ENV_MARKER="$WORK_DIR/.export-env"

if [[ -f "$RESULTS" ]]; then
  previous_env="$(cat "$ENV_MARKER" 2>/dev/null || true)"
  fresh_reason=""
  if [[ -f "$COMPLETE_MARKER" ]]; then
    fresh_reason="the last run in this folder finished"
  elif [[ "$previous_env" != "$ENV" ]]; then
    fresh_reason="the last run in this folder was against ${previous_env:-an unrecorded environment}, not $ENV"
  fi

  if [[ -n "$fresh_reason" ]]; then
    mv -f "$RESULTS" "$WORK_DIR/practitioners.previous.jsonl"
    echo "Mode:        fresh -- $fresh_reason (its results kept as practitioners.previous.jsonl)" >&2
  else
    echo "Mode:        resuming -- the last run in this folder was interrupted; CRNs it fetched are kept" >&2
  fi
else
  echo "Mode:        fresh" >&2
fi
rm -f "$COMPLETE_MARKER"
printf '%s\n' "$ENV" > "$ENV_MARKER"

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
# Every connection this script makes is read-only at the server: Postgres
# rejects any write in it, whatever SQL is sent. Checked below before use.
export PGOPTIONS="-c default_transaction_read_only=on"

if [[ "$TOKEN_SOURCE" == laptop ]]; then
  step "Reading the UI system client credentials"
  CLIENT_ID=$(secret_value "$UI_NAMESPACE" hmpps-esupervision-ui-client-creds CLIENT_CREDS_CLIENT_ID) \
    || die "could not read hmpps-esupervision-ui-client-creds in $UI_NAMESPACE (set UI_NAMESPACE if the UI lives elsewhere)"
  CLIENT_SECRET=$(secret_value "$UI_NAMESPACE" hmpps-esupervision-ui-client-creds CLIENT_CREDS_CLIENT_SECRET)
fi

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

# Don't take the read-only session on trust: ask the server. Anything that
# stripped PGOPTIONS on the way (a pooler, a proxy) would show up here, and the
# script stops before sending a single query against the data.
[[ "$(psql -qtAc 'show default_transaction_read_only')" == "on" ]] \
  || die "the database session is not read-only (default_transaction_read_only is not on) -- refusing to continue"
echo "Database session confirmed read-only" >&2

# ---------------------------------------------------------------------------
# SQL: two read-only SELECTs, written to files on this machine
# ---------------------------------------------------------------------------
step "Extracting CRNs and usernames (read-only)"
psql_log=$(mktemp)
(cd "$WORK_DIR" && psql -X -q -v ON_ERROR_STOP=1 -f "$REPO_ROOT/scripts/practitioner_contact_list.sql") \
  > "$psql_log" 2>&1 \
  || { cat "$psql_log" >&2; rm -f "$psql_log"; die "SQL step failed"; }
rm -f "$psql_log"

# The database is no longer needed; stop the port-forward now rather than
# holding it open through the API step.
kill "$PF_PID" 2>/dev/null || true; PF_PID=""
kubectl -n "$NS" delete pod "$POD" --ignore-not-found --wait=false >/dev/null 2>&1 || true

# ---------------------------------------------------------------------------
# Summary reports, worked out here from the two downloaded files -- no further
# queries against the database.
# ---------------------------------------------------------------------------
table() { if command -v column >/dev/null; then column -t -s $'\t'; else cat; fi; }

# The usernames CSV, as JSON. Usernames and the |-joined sources hold no
# commas, so a plain split is exact.
usernames_json() {
  tail -n +2 "$WORK_DIR/practitioner_usernames.csv" \
    | jq -R -s 'split("\n") | map(select(length > 0) | split(",")
                | {username: .[0], mentions: (.[1] | tonumber), sources: (.[2] | split("|"))})'
}

summarise_extract() {
  local crns="$WORK_DIR/practitioner_crns.jsonl" users
  users="$(usernames_json)"

  echo "CRNs by status. CRNs with no region can still get a live PDU from the"
  echo "fetch, but there is nowhere else to get their region from."
  { printf 'status\tcrns\tusernames\twith_geography\tno_region\n'
    jq -rs '(group_by(.status) | map({s: .[0].status, rows: .})) + [{s: "TOTAL", rows: .}]
            | .[] | [.s, (.rows | length),
                     (.rows | map(.storedUsername) | unique | length),
                     (.rows | map(select(.pdu != null or .region != null)) | length),
                     (.rows | map(select(.region == null)) | length)] | @tsv' "$crns"
  } | table

  echo
  echo "CRNs by region and PDU. A region that looks far too small usually means"
  echo "stale snapshots rather than a real distribution."
  { printf 'region\tpdu\tcrns\n'
    jq -rs 'group_by([.region, .pdu])
            | map([(.[0].region // "(none)"), (.[0].pdu // "(none)"), length])
            | sort_by(.[0], -.[2]) | .[] | @tsv' "$crns"
  } | table

  # "On record" is offender_v2.practitioner_id: who set each check-in up. It is
  # never updated when NDelius reallocates a case, so it is not who holds the
  # case now -- that is only known after the fetch, and practitioners_unmatched.csv
  # is the report built on it. Word this one accordingly.
  echo
  jq -r '"Usernames ever recorded against a check-in: \(length) -- \(map(select(.sources | index("offender_v2"))) | length) on record as the practitioner for a CRN (as at setup; reallocations are not recorded), \(map(select(.sources | index("offender_v2") | not)) | length) not"' <<<"$users"

  echo
  echo "Usernames not on record as the practitioner for any CRN: reviewers,"
  echo "colleagues who ran a setup for someone else, and the like. The fetch reaches"
  echo "them only if NDelius lists them as the current practitioner for some CRN;"
  echo "practitioners_unmatched.csv, written after the fetch, says who it could not."
  { printf 'username\tmentions\tsources\n'
    jq -r 'map(select(.sources | index("offender_v2") | not))
           | sort_by(-.mentions, .username) | .[] | [.username, .mentions, (.sources | join("|"))] | @tsv' <<<"$users"
  } | table

  echo
  echo "Usernames that don't look like an NDelius FIRST.LAST: probably a service"
  echo "account or test data. Check before mailing anyone."
  { printf 'username\tmentions\tsources\n'
    jq -r --arg re "^[A-Z0-9'-]+\\.[A-Z0-9'.-]+$" \
      'map(select(.username | test($re) | not))
       | sort_by(.username) | .[] | [.username, .mentions, (.sources | join("|"))] | @tsv' <<<"$users"
  } | table
}

summarise_extract > "$WORK_DIR/extract_report.txt"
echo "$(wc -l < "$WORK_DIR/practitioner_crns.jsonl" | tr -d ' ') CRNs extracted (summary in extract_report.txt)" >&2

# ---------------------------------------------------------------------------
# Fetch: a fresh token for every pass, one retry on an auth failure
# ---------------------------------------------------------------------------
# Runs inside the UI pod (Node 24, so fetch is built in), using the env the
# deployment already has: HMPPS_AUTH_URL and the CLIENT_CREDS_* pair mapped from
# hmpps-esupervision-ui-client-creds. Prints only the token; on failure, prints
# HMPPS Auth's status and error to stderr. The secret never leaves the pod.
# shellcheck disable=SC2016
POD_TOKEN_JS='
const env = process.env;
const missing = ["HMPPS_AUTH_URL", "CLIENT_CREDS_CLIENT_ID", "CLIENT_CREDS_CLIENT_SECRET"].filter(k => !env[k]);
if (missing.length) { console.error("pod env lacks " + missing.join(", ")); process.exit(2); }
const basic = Buffer.from(env.CLIENT_CREDS_CLIENT_ID + ":" + env.CLIENT_CREDS_CLIENT_SECRET).toString("base64");
fetch(env.HMPPS_AUTH_URL + "/oauth/token?grant_type=client_credentials",
      { method: "POST", headers: { Authorization: "Basic " + basic }, signal: AbortSignal.timeout(30000) })
  .then(async r => {
    const text = await r.text();
    if (!r.ok) { console.error("HTTP " + r.status + " from " + env.HMPPS_AUTH_URL + ": " + text.slice(0, 300)); process.exit(1); }
    process.stdout.write(JSON.parse(text).access_token);
  })
  .catch(e => { console.error("request to " + env.HMPPS_AUTH_URL + " failed: " + e.message); process.exit(1); });
'

token_from_pod() {
  TOKEN=$(kubectl -n "$UI_NAMESPACE" exec "deploy/$UI_DEPLOYMENT" -- node -e "$POD_TOKEN_JS") \
    && [[ -n "$TOKEN" ]] \
    || die "could not get a token from inside $UI_DEPLOYMENT (see the error above)"
}

token_from_laptop() {
  local body status detail
  body=$(mktemp)
  # Credentials go in on stdin (as a curl config) rather than argv.
  status=$(printf 'user = "%s:%s"\n' "$CLIENT_ID" "$CLIENT_SECRET" \
    | curl -s -o "$body" -w '%{http_code}' --connect-timeout 10 --max-time 30 -K - -X POST \
        "$AUTH_URL/oauth/token?grant_type=client_credentials") || true
  if [[ "$status" == 200 ]] && TOKEN=$(jq -er .access_token "$body" 2>/dev/null); then
    rm -f "$body"; return 0
  fi
  # HMPPS Auth answers errors as {"error": ..., "error_description": ...}.
  detail=$(jq -r '[.error, .error_description] | map(select(.)) | join(": ")' "$body" 2>/dev/null \
           || head -c 300 "$body")
  rm -f "$body"
  die "HMPPS Auth refused the token request: HTTP ${status:-000}${detail:+ -- $detail}
       If the client is IP-restricted in this environment, use TOKEN_SOURCE=pod (the default)."
}

new_token() {
  if [[ "$TOKEN_SOURCE" == pod ]]; then token_from_pod; else token_from_laptop; fi
  export TOKEN
}

for pass in $(seq 1 "$PASSES"); do
  step "Fetching practitioner details, pass $pass of $PASSES"
  new_token
  if ! "$REPO_ROOT/scripts/fetch_practitioner_details.sh" \
         "$WORK_DIR/practitioner_crns.jsonl" "$RESULTS"; then
    echo "Pass $pass stopped early -- refreshing the token and resuming once" >&2
    new_token
    "$REPO_ROOT/scripts/fetch_practitioner_details.sh" \
      "$WORK_DIR/practitioner_crns.jsonl" "$RESULTS" \
      || die "fetch failed twice; see the errors above. Re-running this script with the same folder resumes."
  fi
done

# Only now is the export complete. The next run in this folder starts afresh.
date -u +%Y-%m-%dT%H:%M:%SZ > "$COMPLETE_MARKER"

step "Done"
cat >&2 <<EOF
  Mailing list: $WORK_DIR/practitioner_export.csv
  Worksheet:    $WORK_DIR/practitioners_unmatched.csv  (usernames for the blank rows)
  Summary:      $WORK_DIR/extract_report.txt

  These files are personal data. Delete $WORK_DIR once the export is handed over.
EOF

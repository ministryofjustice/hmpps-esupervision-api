#!/usr/bin/env bash
#
# Removes people on probation whose CRN no longer exists in NDelius from the
# DEV database. It runs against dev only -- never test, preprod or prod. The
# guards are listed under "Dev only" below. The e2e tests create cases in NDelius, onboard them
# here, then delete the case in NDelius, leaving our side orphaned.
#
#   1. reads the RDS credentials from the namespace secret (held in memory only)
#   2. port-forwards to the database and exports every CRN in offender_v2,
#      over a session Postgres holds read-only
#   3. asks NDelius about each CRN, via GET /v2/offenders/header/{crn} on this
#      API, which answers 404 only when NDelius does not know the CRN. The
#      token comes from the UI's system client, fetched inside a UI pod, as in
#      run_delius_note_correction.sh. Results are kept in work_dir, so an
#      interrupted run picks up where it stopped.
#   4. runs scripts/orphan_crn_cleanup.sql over the CRNs NDelius answered 404
#      for, in one transaction: rolled back (a dry run, printing the row counts)
#      unless APPLY=1, which asks for confirmation and then commits.
#
# Prerequisites: kubectl authenticated to Cloud Platform with access to the
# namespace, psql, jq, curl -- and the MoJ network, because the API ingress is
# allowlisted.
#
# Usage:
#   ./scripts/run_orphan_crn_cleanup.sh [work_dir]          # dry run
#   APPLY=1 ./scripts/run_orphan_crn_cleanup.sh [work_dir]  # delete
#
#   work_dir  defaults to ~/esup-orphan-crns/dev. Must be outside this repo.
#
# Dev only. Every one of these must hold, or the script stops:
#   - the namespace, API URL and UI namespace are fixed to dev; ENV, NS,
#     API_BASE or UI_NAMESPACE set to anything else is refused
#   - the namespace is hmpps-esupervision-dev and Cloud Platform labels it
#     environment-name=development, is-production=false (checked at the start
#     and again just before deleting)
#   - nothing else is listening on LOCAL_PG_PORT before the port-forward
#     starts, so psql cannot reach some other environment's tunnel
#   - once connected, the database name and user are the ones in the dev
#     secret, and orphan_crn_cleanup.sql checks the database name again
#     inside its transaction before it deletes anything
#
# Env:
#   APPLY          1 to commit the deletes; otherwise a dry run
#   RECHECK        1 to discard earlier NDelius results and ask again for every CRN
#   CRN_LIMIT      check (and so at most clean up) only the first N CRNs -- for a
#                  quick trial before the full run
#   LOCAL_PG_PORT  local end of the database port-forward, default 5433
#   PASSES         NDelius check passes, default 2. Later passes retry only the
#                  CRNs that could not be checked (NDelius unavailable, timeouts)
#   RATE_SLEEP     seconds between NDelius checks, default 0.2
#   REQUEST_TIMEOUT  seconds before giving up on one check, default 60
#   UI_DEPLOYMENT  default hmpps-esupervision-ui
#
# Exit: 0 on success; 3 if some CRNs could not be checked against NDelius
# (they are left alone -- re-run to retry them).
#
# Outputs, in work_dir:
#   crns.txt           every CRN in offender_v2 at the last export
#   delius_crns.jsonl  NDelius check result per CRN and pass
#   orphan_crns.txt    the CRNs NDelius answered 404 for -- what gets deleted
#   orphan_uuids.csv   their offender and check-in UUIDs, for clearing S3
#   applied-<time>-*   a permanent copy of both, for each committed delete

set -euo pipefail
umask 077
export LC_ALL=C   # comm and sort must agree on order

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_PG_PORT="${LOCAL_PG_PORT:-5433}"
PASSES="${PASSES:-2}"
RATE_SLEEP="${RATE_SLEEP:-0.2}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-60}"
UI_DEPLOYMENT="${UI_DEPLOYMENT:-hmpps-esupervision-ui}"

step() { printf '\n==> %s\n' "$*" >&2; }
die()  { echo "ERROR: $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Dev only. These are fixed, not configurable: there is no way to point this
# script at another environment.
# ---------------------------------------------------------------------------
DEV_NS=hmpps-esupervision-dev
DEV_API=https://esupervision-api-dev.hmpps.service.justice.gov.uk

# Refuse, rather than silently ignore, an attempt to aim it elsewhere: check
# what the caller set before overwriting it.
[[ -z "${ENV:-}" || "$ENV" == dev ]] \
  || die "this script only runs against dev (ENV=$ENV given). It must never touch test, preprod or prod."
[[ -z "${NS:-}" || "$NS" == "$DEV_NS" ]] || die "this script only runs against $DEV_NS (NS=$NS given)"
[[ -z "${UI_NAMESPACE:-}" || "$UI_NAMESPACE" == "$DEV_NS" ]] \
  || die "this script only runs against $DEV_NS (UI_NAMESPACE=$UI_NAMESPACE given)"
[[ -z "${API_BASE:-}" || "$API_BASE" == "$DEV_API" ]] \
  || die "this script only talks to $DEV_API (API_BASE=$API_BASE given)"

readonly ENV=dev NS="$DEV_NS" UI_NAMESPACE="$DEV_NS" API_BASE="$DEV_API"

# Cloud Platform labels every namespace with its environment. Preprod is also
# is-production=false, so the environment name is what proves this is dev.
assert_dev_namespace() {
  local labels
  labels=$(kubectl get namespace "$NS" -o json | jq -c '{
      name: .metadata.name,
      env: .metadata.labels["cloud-platform.justice.gov.uk/environment-name"],
      prod: .metadata.labels["cloud-platform.justice.gov.uk/is-production"]}') \
    || die "could not read namespace $NS to confirm it is dev"
  [[ "$labels" == '{"name":"hmpps-esupervision-dev","env":"development","prod":"false"}' ]] \
    || die "namespace $NS does not look like dev ($labels) -- refusing to continue"
}

[[ $# -le 1 ]] || die "usage: $0 [work_dir]"
WORK_DIR="${1:-$HOME/esup-orphan-crns/dev}"
[[ "$PASSES" =~ ^[1-9][0-9]*$ ]] || die "PASSES must be a positive whole number (got '$PASSES')"

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
for cmd in kubectl psql jq curl; do
  command -v "$cmd" >/dev/null || die "$cmd is required"
done

mkdir -p "$WORK_DIR"
WORK_DIR="$(cd -- "$WORK_DIR" && pwd -P)"
case "$WORK_DIR/" in
  "$(cd -- "$REPO_ROOT" && pwd -P)/"*) die "work_dir $WORK_DIR is inside the repo -- pick a directory outside it." ;;
esac
chmod 700 "$WORK_DIR"

CRNS="$WORK_DIR/crns.txt"
CRN_STATUS="$WORK_DIR/delius_crns.jsonl"
ORPHANS="$WORK_DIR/orphan_crns.txt"
[[ "${RECHECK:-}" == 1 ]] && rm -f "$CRN_STATUS"
rm -f "$CRNS" "$ORPHANS" "$WORK_DIR/orphan_uuids.csv"

assert_dev_namespace
kubectl -n "$NS" auth can-i create pods >/dev/null 2>&1 \
  || die "kubectl cannot create pods in $NS -- check your Cloud Platform login and context"
kubectl -n "$NS" auth can-i delete pods >/dev/null 2>&1 \
  || die "kubectl cannot delete pods in $NS -- the port-forward pod could not be cleaned up"
kubectl -n "$UI_NAMESPACE" auth can-i create pods/exec >/dev/null 2>&1 \
  || die "kubectl cannot exec into pods in $UI_NAMESPACE (needed for the NDelius check token)"
kubectl -n "$UI_NAMESPACE" get "deploy/$UI_DEPLOYMENT" >/dev/null 2>&1 \
  || die "no deployment $UI_DEPLOYMENT in $UI_NAMESPACE -- set UI_NAMESPACE / UI_DEPLOYMENT"

echo "Environment: $ENV (namespace $NS)" >&2
echo "API:         $API_BASE" >&2
echo "Mode:        $([[ "${APPLY:-}" == 1 ]] && echo 'APPLY (deletes, after confirmation)' || echo 'dry run')" >&2
echo "Work dir:    $WORK_DIR" >&2

# ---------------------------------------------------------------------------
# Database credentials, held in shell variables only -- never written to disk
# ---------------------------------------------------------------------------
rds_value() {
  kubectl -n "$NS" get secret hmpps-esupervision-rds-settings -o json \
    | jq -er --arg k "$1" '.data[$k] | @base64d' \
    || die "could not read $1 from secret hmpps-esupervision-rds-settings in $NS"
}

step "Reading database credentials"
RDS_ENDPOINT=$(rds_value rds_instance_endpoint)
RDS_HOST="${RDS_ENDPOINT%%:*}"
PGUSER=$(rds_value database_username)
PGDATABASE=$(rds_value database_name)
PGPASSWORD=$(rds_value database_password)
export PGUSER PGDATABASE PGPASSWORD PGHOST=127.0.0.1 PGPORT="$LOCAL_PG_PORT"

# ---------------------------------------------------------------------------
# Port-forward, opened only while the database is in use and torn down on exit.
# The NDelius check can take an hour; an idle port-forward would not survive it.
# ---------------------------------------------------------------------------
# The namespace is shared and cleanup deletes this pod by name, so the name
# must be this run's alone.
POD="orphan-crns-$(whoami | tr -cd 'a-z0-9' | cut -c1-20)-$$-$(od -An -N4 -tx4 /dev/urandom | tr -d ' \n')"
PF_PID=""
body=""

start_db() {
  # If something already listens here -- say a port-forward to preprod or prod
  # left open in another terminal -- ours would fail to bind and psql would
  # connect through theirs instead.
  if (exec 3<>"/dev/tcp/127.0.0.1/$LOCAL_PG_PORT") 2>/dev/null; then
    die "something is already listening on 127.0.0.1:$LOCAL_PG_PORT -- close it or set LOCAL_PG_PORT to a free port"
  fi
  step "Starting port-forward pod $POD"
  kubectl -n "$NS" run "$POD" --image=ministryofjustice/port-forward --restart=Never \
    --port=5432 --env="REMOTE_HOST=$RDS_HOST" --env="LOCAL_PORT=5432" --env="REMOTE_PORT=5432" >/dev/null
  kubectl -n "$NS" wait --for=condition=Ready "pod/$POD" --timeout=120s >/dev/null
  kubectl -n "$NS" port-forward "pod/$POD" "$LOCAL_PG_PORT:5432" >/dev/null 2>&1 &
  PF_PID=$!
  local up=""
  for _ in $(seq 1 30); do
    kill -0 "$PF_PID" 2>/dev/null || die "port-forward exited -- is local port $LOCAL_PG_PORT free? (set LOCAL_PG_PORT)"
    psql -X -qtAc 'select 1' >/dev/null 2>&1 && { up=1; break; }
    sleep 1
  done
  [[ -n "$up" ]] || die "database not reachable on 127.0.0.1:$LOCAL_PG_PORT"
  # Connected -- but make sure it is through our port-forward, to the dev database.
  kill -0 "$PF_PID" 2>/dev/null || die "port-forward exited -- refusing to use whatever is on $LOCAL_PG_PORT"
  local who
  who=$(psql -X -qtAc "select current_database() || ' ' || current_user") || die "could not identify the database"
  [[ "$who" == "$PGDATABASE $PGUSER" ]] \
    || die "connected to '$who', not the dev database '$PGDATABASE $PGUSER' -- refusing to continue"
}
stop_db() {
  [[ -n "$PF_PID" ]] && kill "$PF_PID" 2>/dev/null || true
  PF_PID=""
  kubectl -n "$NS" delete pod "$POD" --ignore-not-found --wait=true >/dev/null 2>&1 || true
}
cleanup() {
  local rc=$?
  stop_db
  rm -f ${body:+"$body"}
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

start_db
step "Exporting CRNs (read-only session)"
[[ "$(PGOPTIONS='-c default_transaction_read_only=on' psql -X -qtAc 'show default_transaction_read_only')" == on ]] \
  || die "could not open a read-only session -- refusing to continue"
PGOPTIONS='-c default_transaction_read_only=on' \
  psql -X -q -v ON_ERROR_STOP=1 -c "\\copy (select crn from offender_v2 order by crn) to '$CRNS'"
sort -u -o "$CRNS" "$CRNS"
echo "$(grep -c . "$CRNS" || true) CRNs in offender_v2" >&2
if [[ -n "${CRN_LIMIT:-}" ]]; then
  [[ "$CRN_LIMIT" =~ ^[1-9][0-9]*$ ]] || die "CRN_LIMIT must be a positive whole number (got '$CRN_LIMIT')"
  head -n "$CRN_LIMIT" "$CRNS" > "$CRNS.tmp" && mv "$CRNS.tmp" "$CRNS"
  echo "CRN_LIMIT: only the first $CRN_LIMIT are checked" >&2
fi
stop_db

# ---------------------------------------------------------------------------
# Does each CRN still exist in NDelius?
# ---------------------------------------------------------------------------
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

new_token() {
  # </dev/null: this runs inside the loop that reads the CRNs from stdin.
  TOKEN=$(kubectl -n "$UI_NAMESPACE" exec "deploy/$UI_DEPLOYMENT" -- node -e "$POD_TOKEN_JS" </dev/null) \
    && [[ -n "$TOKEN" ]] \
    || die "could not get a token from inside $UI_DEPLOYMENT (see the error above)"
}

# One request. Sets $code and leaves the body in $body. The token goes in on
# stdin, not argv, so `ps` on a shared host never shows it.
#
# Any curl failure (timeout, reset, truncated body) sets code=000, even if a
# status line had already arrived: a cut-off transfer can still carry a 404
# that parses, and must never be read as "not in NDelius".
request() {  # crn
  if ! code=$(curl -s -o "$body" -w '%{http_code}' \
    --connect-timeout 10 --max-time "$REQUEST_TIMEOUT" \
    -H @- -H 'Accept: application/json' \
    "$API_BASE/v2/offenders/header/$1" \
    <<<"Authorization: Bearer $TOKEN"); then
    code=000
  fi
  code="${code:-000}"
}

# 404 comes only from OffenderService.getHeaderDetails when NDelius does not
# know the CRN. When NDelius could not be asked (unavailable, circuit open) the
# endpoint still answers 200 with the failure against dateOfBirth in errors[],
# so that is "unchecked", not "found".
# shellcheck disable=SC2016  # jq variables, not shell ones
CLASSIFY='
  (.errors // [] | map(select(.field == "dateOfBirth")) | first | .code) as $err
  | if $err != null then {status: "UNCHECKED", reason: ("NDelius " + $err)}
    else {status: "FOUND"} end'

# Latest result per CRN, as "CRN STATUS" lines.
latest_results() {
  [[ -s "$CRN_STATUS" ]] || return 0
  jq -rs 'reduce .[] as $r ({}; .[$r.crn] = $r.status) | to_entries[] | "\(.key) \(.value)"' "$CRN_STATUS"
}

if [[ -t 2 ]]; then show_progress=1; else show_progress=0; fi
touch "$CRN_STATUS"

# Only CRNs without a definite answer from an earlier run.
pending=$(comm -23 "$CRNS" <(latest_results | awk '$2 != "UNCHECKED" {print $1}' | sort -u))
already=$(( $(grep -c . "$CRNS" || true) - $(grep -c . <<<"$pending" || true) ))
(( already > 0 )) && echo "$already CRNs already checked in an earlier run (RECHECK=1 to ask again)" >&2

# Asks NDelius about every CRN in $pending, PASSES times over for those that
# could not be checked; leaves the still-unchecked ones in $pending.
check_pending() {  # label
for pass in $(seq 1 "$PASSES"); do
  [[ -n "$pending" ]] || break
  todo=$(grep -c . <<<"$pending")
  step "$1, pass $pass of $PASSES ($todo CRNs)"
  new_token
  retry=""; done_n=0; failed=0
  # A 200 body holds the date of birth, so it lands in the private work_dir and
  # is emptied after each request.
  body=$(mktemp "$WORK_DIR/.response.XXXXXX")
  while read -r crn; do
    [[ -n "$crn" ]] || continue
    if [[ ! "$crn" =~ ^[A-Z][0-9]{6}$ ]]; then
      # Never asked about, never retried, never deleted.
      result='{"status":"INVALID","reason":"not a valid CRN"}'; code=000
    else
      request "$crn"
      if [[ "$code" == 401 ]]; then
        new_token; request "$crn"   # the token expired mid-pass
      fi
      case "$code" in
        # -e, and the -n check: an empty body makes jq succeed with no output,
        # which would record nothing and leave an old NOT_IN_DELIUS standing.
        200) result=$(jq -ce "$CLASSIFY" "$body" 2>/dev/null) && [[ -n "$result" ]] \
               || result='{"status":"UNCHECKED","reason":"unreadable response"}' ;;
        404)
          # Only our API's own "not in NDelius" answer counts. A 404 from the
          # ingress or a wrong path must never be read as an orphan.
          if jq -e '.userMessage // "" | test("in NDelius")' "$body" >/dev/null 2>&1; then
            result='{"status":"NOT_IN_DELIUS"}'
          else
            result='{"status":"UNCHECKED","reason":"404 without the NDelius not-found message"}'
          fi ;;
        401|403)
          die "HTTP $code for $crn -- token rejected or missing ROLE_ESUPERVISION__ESUPERVISION_UI" ;;
        *)   result=$(jq -cn --arg c "$code" '{status: "UNCHECKED", reason: ("HTTP " + $c)}') ;;
      esac
      : > "$body"
    fi
    jq -c --arg crn "$crn" --argjson pass "$pass" --arg http "$code" \
      '{crn: $crn, pass: $pass, http: ($http | tonumber), at: (now | todate)} + .' <<<"$result" >> "$CRN_STATUS"
    if [[ "$(jq -r .status <<<"$result")" == UNCHECKED ]]; then
      retry+="$crn"$'\n'; failed=$((failed + 1))
      (( show_progress )) && printf '\r\033[K' >&2
      echo "RETRY crn=$crn $(jq -r .reason <<<"$result")" >&2
    fi
    done_n=$((done_n + 1))
    (( show_progress )) && printf '\r\033[K%d/%d  unchecked=%d' "$done_n" "$todo" "$failed" >&2
    sleep "$RATE_SLEEP"
  done <<<"$pending"
  rm -f "$body"; body=""
  (( show_progress )) && printf '\r\033[K' >&2
  echo "pass $pass: checked=$done_n unchecked=$failed" >&2
  pending="$retry"
done
unset TOKEN
}

check_pending "Checking CRNs exist in NDelius"

# Only CRNs still in offender_v2 at this export -- for the orphans and for the
# sanity check below alike, so results for CRNs since removed cannot satisfy it.
summarise() {
  local latest
  latest=$(latest_results)
  awk '$2 == "NOT_IN_DELIUS" {print $1}' <<<"$latest" | sort -u | comm -12 - "$CRNS" > "$ORPHANS"
  found=$(awk '$2 == "FOUND" {print $1}' <<<"$latest" | sort -u | comm -12 - "$CRNS" | grep -c . || true)
  invalid=$(awk '$2 == "INVALID" {print $1}' <<<"$latest" | sort -u | comm -12 - "$CRNS" | grep -c . || true)
  orphans=$(grep -c . "$ORPHANS" || true)
  unchecked=$(awk '$2 == "UNCHECKED" {print $1}' <<<"$latest" | sort -u | comm -12 - "$CRNS" | grep -c . || true)
  step "NDelius check: found=$found not_in_delius=$orphans unchecked=$unchecked invalid=$invalid"
}
summarise
(( invalid == 0 )) || echo "NOTE: $invalid CRNs in offender_v2 are not valid CRNs and were left alone (see INVALID in $CRN_STATUS)" >&2
# A check that found nobody is far likelier to be broken than to be right.
(( found > 0 )) || die "NDelius knew none of the CRNs -- refusing to treat them all as orphans. Check the API and NDelius are healthy."
if (( orphans == 0 )); then
  echo "Nothing to clean up." >&2
  (( unchecked == 0 )) || exit 3
  exit 0
fi
echo "Orphans listed in $ORPHANS (first few: $(head -5 "$ORPHANS" | paste -sd' ' -))" >&2

# ---------------------------------------------------------------------------
# Delete -- one transaction, rolled back unless APPLY=1 and confirmed
# ---------------------------------------------------------------------------
run_cleanup() {  # true|false
  (cd "$WORK_DIR" && psql -X -q -v ON_ERROR_STOP=1 -v apply="$1" \
    -v target_env=dev -v expected_db="$PGDATABASE" -f "$REPO_ROOT/scripts/orphan_crn_cleanup.sql")
}

if [[ "${APPLY:-}" == 1 ]]; then
  # Always, even on a fresh run: the full check can take an hour, and a CRN
  # found missing early on may be back by now, or the answer may come from an
  # earlier run. Ask again about every orphan just before deleting.
  pending=$(cat "$ORPHANS")
  check_pending "Re-checking the orphans before deleting"
  summarise
  if (( orphans == 0 )); then
    echo "Nothing to clean up after the re-check." >&2
    if (( unchecked > 0 )); then
      echo "WARNING: $unchecked CRNs could not be checked against NDelius and were left alone. Re-run to retry." >&2
      exit 3
    fi
    exit 0
  fi
fi

start_db
step "Dry run (rolled back)"
run_cleanup false || die "dry run failed -- nothing was deleted"

if [[ "${APPLY:-}" == 1 ]]; then
  [[ -t 0 ]] || die "APPLY=1 needs an interactive terminal to confirm"
  printf '\nDelete these %d people on probation and everything of theirs from %s? Type the environment name (%s) to confirm: ' \
    "$orphans" "$NS" "$ENV" >&2
  read -r answer
  [[ "$answer" == "$ENV" ]] || die "not confirmed -- nothing was deleted"
  assert_dev_namespace
  step "Deleting"
  run_cleanup true || die "delete failed -- the transaction was rolled back, nothing was deleted"
  # orphan_uuids.csv is rewritten by every run, so keep what this commit
  # deleted under its own name: once the rows are gone it is the only record
  # of which S3 objects belonged to them.
  applied="$WORK_DIR/applied-$(date -u +%Y%m%dT%H%M%SZ)"
  cp -- "$ORPHANS" "$applied-orphan_crns.txt"
  cp -- "$WORK_DIR/orphan_uuids.csv" "$applied-orphan_uuids.csv"
  echo "Done. What was deleted, and their S3 UUIDs, are kept in $applied-*." >&2
else
  echo >&2
  echo "Dry run only. Re-run with APPLY=1 to delete (earlier results are reused; the orphans are always re-checked first)." >&2
fi
stop_db

if (( unchecked > 0 )); then
  echo "WARNING: $unchecked CRNs could not be checked against NDelius and were left alone. Re-run to retry." >&2
  exit 3
fi

#!/usr/bin/env bash
#
# ESUP-1956 follow-up: turn the contact list NDelius supplied into the CSV of
# missing check-in answers we hand back. One command, end to end, cleaning up
# after itself:
#
#   1. builds the app jar, which carries CheckinNoteExportTool
#   2. reads the RDS credentials from the namespace secret (held in memory only)
#   3. starts a Cloud Platform port-forward pod and a local port-forward to it
#   4. runs scripts/delius_note_correction.sql -- one SELECT, no temp tables --
#      over a connection that Postgres itself holds read-only (checked before
#      anything runs). The contact list goes in as data, from contacts.json.
#   5. deletes the port-forward pod, then, offline:
#   6. delius_note_correction.py report: match grades and exception reports
#      (match_report.txt), and checkin_export.jsonl
#   7. checks each CRN with answers to hand over still exists in NDelius, via
#      GET /v2/offenders/header/{crn} on this API (404 only when NDelius does not
#      know the CRN). The token comes from the UI's system client, fetched
#      inside a running UI pod, as in run_tier_counts.sh.
#   8. CheckinNoteExportTool -> notes.jsonl (the "Check in answers:" block per
#      check-in, as the app rendered it at the time)
#   9. build_handover_csv.py -> delius_note_corrections.csv, plus
#      delius_note_corrections.exceptions.csv for contacts with no answers, or
#      whose CRN is gone from NDelius or could not be checked
#
# Prerequisites: kubectl authenticated to Cloud Platform with access to the
# namespace, psql, jq, curl, java 21+, python3 -- and the MoJ network, because
# the API ingress is allowlisted.
#
# Usage:
#   ./scripts/run_delius_note_correction.sh <delius_contacts.csv> [work_dir]
#
#   delius_contacts.csv  as supplied by NDelius, with the header
#                        CRN,CONTACT_DATE,START_TIME,CONTACT_ID,SOFT_DELETED
#   work_dir             defaults to ~/esup-delius-notes/<today>-<env>. It must be
#                        outside this repo: everything in it is sensitive.
#
# Env:
#   ENV            dev | test | preprod | prod (default prod)
#   LOCAL_PG_PORT  local end of the database port-forward, default 5433
#   SKIP_BUILD     set to 1 to reuse the jar already in build/libs
#   PASSES         NDelius check passes, default 2. Later passes retry only the
#                  CRNs that could not be checked (NDelius unavailable, timeouts)
#   RATE_SLEEP     seconds between NDelius checks, default 0.5
#   REQUEST_TIMEOUT  seconds before giving up on one check, default 60
#   UI_NAMESPACE   default the API's own namespace
#   UI_DEPLOYMENT  default hmpps-esupervision-ui
#   EXPORT_API_BASE  override the per-environment API URL
#
# Exit: 0 on success; 3 if the handover was written but some CRNs could not be
# checked against NDelius (they are in the exceptions file -- re-run to retry).
#
# Outputs, in work_dir:
#   delius_note_corrections.csv             the deliverable
#   delius_note_corrections.exceptions.csv  contacts we have no answers for
#   match_report.txt                        match grades and exception reports
#                                           -- read before handover
#   delius_crns.jsonl                       NDelius check result per CRN and pass
#   contacts.json, matched.jsonl,
#   checkin_export.jsonl, notes.jsonl       intermediate files

set -euo pipefail
umask 077

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ENV="${ENV:-prod}"
LOCAL_PG_PORT="${LOCAL_PG_PORT:-5433}"
PASSES="${PASSES:-2}"
RATE_SLEEP="${RATE_SLEEP:-0.5}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-60}"
NS="hmpps-esupervision-$ENV"
UI_NAMESPACE="${UI_NAMESPACE:-$NS}"
UI_DEPLOYMENT="${UI_DEPLOYMENT:-hmpps-esupervision-ui}"

step() { printf '\n==> %s\n' "$*" >&2; }
die()  { echo "ERROR: $*" >&2; exit 1; }

[[ $# -ge 1 && $# -le 2 ]] || die "usage: $0 <delius_contacts.csv> [work_dir]"
CONTACTS="$1"
WORK_DIR="${2:-$HOME/esup-delius-notes/$(date +%Y-%m-%d)-$ENV}"

case "$ENV" in
  prod)    default_api=https://esupervision-api.hmpps.service.justice.gov.uk ;;
  preprod) default_api=https://esupervision-api-preprod.hmpps.service.justice.gov.uk ;;
  test)    default_api=https://esupervision-api-test.hmpps.service.justice.gov.uk ;;
  dev)     default_api=https://esupervision-api-dev.hmpps.service.justice.gov.uk ;;
  *) die "ENV must be dev, test, preprod or prod (got '$ENV')" ;;
esac
API_BASE="${EXPORT_API_BASE:-$default_api}"
[[ "$PASSES" =~ ^[1-9][0-9]*$ ]] || die "PASSES must be a positive whole number (got '$PASSES')"

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
for cmd in kubectl psql jq curl java python3; do
  command -v "$cmd" >/dev/null || die "$cmd is required"
done
[[ -r "$CONTACTS" ]] || die "cannot read $CONTACTS"

mkdir -p "$WORK_DIR"
WORK_DIR="$(cd -- "$WORK_DIR" && pwd -P)"
case "$WORK_DIR/" in
  "$(cd -- "$REPO_ROOT" && pwd -P)/"*)
    die "work_dir $WORK_DIR is inside the repo. The outputs hold answer text -- pick a directory outside it." ;;
esac
chmod 700 "$WORK_DIR"

CONTACTS_JSON="$WORK_DIR/contacts.json"
MATCHED="$WORK_DIR/matched.jsonl"
EXPORT="$WORK_DIR/checkin_export.jsonl"
NOTES="$WORK_DIR/notes.jsonl"
REPORT="$WORK_DIR/match_report.txt"
CRN_STATUS="$WORK_DIR/delius_crns.jsonl"
OUT="$WORK_DIR/delius_note_corrections.csv"
OUT_EXCEPTIONS="$WORK_DIR/delius_note_corrections.exceptions.csv"
# Staged first: $CONTACTS may itself be $WORK_DIR/delius_contacts.csv, which the
# rm below would otherwise delete before it is copied.
STAGED_CONTACTS="$(mktemp "$WORK_DIR/.delius_contacts.XXXXXX")"
cp -- "$CONTACTS" "$STAGED_CONTACTS"
# Every run starts afresh, so a run that stops early never leaves an earlier
# run's results looking current.
rm -f "$CONTACTS_JSON" "$MATCHED" "$EXPORT" "$NOTES" "$REPORT" "$CRN_STATUS" "$OUT" "$OUT_EXCEPTIONS" \
  "$WORK_DIR/delius_contacts.csv"
# Kept alongside the outputs: build_handover_csv.py preserves its rows and columns.
mv -- "$STAGED_CONTACTS" "$WORK_DIR/delius_contacts.csv"
python3 "$REPO_ROOT/scripts/delius_note_correction.py" prepare "$WORK_DIR/delius_contacts.csv" "$CONTACTS_JSON"

kubectl -n "$NS" auth can-i create pods >/dev/null 2>&1 \
  || die "kubectl cannot create pods in $NS -- check your Cloud Platform login and context"
kubectl -n "$NS" auth can-i delete pods >/dev/null 2>&1 \
  || die "kubectl cannot delete pods in $NS -- the port-forward pod could not be cleaned up"
kubectl -n "$UI_NAMESPACE" auth can-i create pods/exec >/dev/null 2>&1 \
  || die "kubectl cannot exec into pods in $UI_NAMESPACE (needed for the NDelius check token)"
kubectl -n "$UI_NAMESPACE" get "deploy/$UI_DEPLOYMENT" >/dev/null 2>&1 \
  || die "no deployment $UI_DEPLOYMENT in $UI_NAMESPACE -- set UI_NAMESPACE / UI_DEPLOYMENT (kubectl -n <ns> get deploy)"

echo "Environment: $ENV (namespace $NS)" >&2
echo "API:         $API_BASE" >&2
echo "Contacts:    $CONTACTS ($(($(grep -c . "$CONTACTS") - 1)) rows)" >&2
echo "Output:      $WORK_DIR" >&2

# ---------------------------------------------------------------------------
# Build first, so a broken build does not waste a port-forward
# ---------------------------------------------------------------------------
if [[ "${SKIP_BUILD:-}" != 1 ]]; then
  step "Building the app jar"
  (cd "$REPO_ROOT" && ./gradlew assemble -q) || die "build failed"
fi
JAR=$(ls -t "$REPO_ROOT"/build/libs/hmpps-esupervision-api-*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -1 || true)
[[ -n "$JAR" ]] || die "no jar in build/libs -- run without SKIP_BUILD"

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
export PGOPTIONS="-c default_transaction_read_only=on"

# ---------------------------------------------------------------------------
# Port-forward, torn down on any exit
# ---------------------------------------------------------------------------
# The namespace is shared and cleanup deletes this pod by name, so the name
# must be this run's alone.
POD="delius-notes-$(whoami | tr -cd 'a-z0-9' | cut -c1-20)-$$-$(od -An -N4 -tx4 /dev/urandom | tr -d ' \n')"
PF_PID=""
body=""

stop_port_forward() {
  [[ -n "$PF_PID" ]] && kill "$PF_PID" 2>/dev/null || true
  PF_PID=""
  kubectl -n "$NS" delete pod "$POD" --ignore-not-found --wait=false >/dev/null 2>&1 || true
}
cleanup() {
  local rc=$?
  stop_port_forward
  rm -f ${body:+"$body"}
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
  psql -X -qtAc 'select 1' >/dev/null 2>&1 && break
  kill -0 "$PF_PID" 2>/dev/null || die "port-forward exited -- is local port $LOCAL_PG_PORT free? (set LOCAL_PG_PORT)"
  sleep 1
done
psql -X -qtAc 'select 1' >/dev/null || die "database not reachable on 127.0.0.1:$LOCAL_PG_PORT"

[[ "$(psql -X -qtAc 'show default_transaction_read_only')" == "on" ]] \
  || die "the database session is not read-only (default_transaction_read_only is not on) -- refusing to continue"
echo "Database session confirmed read-only" >&2

step "Matching contacts to check-ins (read-only)"
# Run from work_dir: the SQL reads contacts.json and writes matched.jsonl
# relative to psql's cwd.
(cd "$WORK_DIR" && psql -X -q -v ON_ERROR_STOP=1 -f "$REPO_ROOT/scripts/delius_note_correction.sql") \
  || { rm -f "$MATCHED"; die "SQL step failed"; }
[[ -s "$MATCHED" ]] || die "the SQL did not write $MATCHED"

stop_port_forward
# The database is finished with: nothing from here on inherits its password.
unset PGPASSWORD PGUSER PGDATABASE PGHOST PGPORT PGOPTIONS

# ---------------------------------------------------------------------------
# Offline: reports, answers text, then the handover CSV
# ---------------------------------------------------------------------------
step "Checking the matches"
python3 "$REPO_ROOT/scripts/delius_note_correction.py" report "$MATCHED" "$WORK_DIR" > "$REPORT"

# ---------------------------------------------------------------------------
# Does each CRN we would hand answers back for still exist in NDelius?
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
request() {  # crn
  code=$(curl -s -o "$body" -w '%{http_code}' \
    --connect-timeout 10 --max-time "$REQUEST_TIMEOUT" \
    -H @- -H 'Accept: application/json' \
    "$API_BASE/v2/offenders/header/$1" \
    <<<"Authorization: Bearer $TOKEN") || true
  code="${code:-000}"
}

# 404 is returned only when NDelius does not know the CRN. When NDelius could
# not be asked (unavailable, circuit open) the endpoint still answers 200, with
# the failure named against the dateOfBirth field in errors[] -- so that is
# "unchecked", not "found". See OffenderService.getHeaderDetails.
# shellcheck disable=SC2016  # jq variables, not shell ones
CLASSIFY='
  (.errors // [] | map(select(.field == "dateOfBirth")) | first | .code) as $err
  | if $err != null then {status: "UNCHECKED", reason: ("NDelius " + $err)}
    else {status: "FOUND"} end'

if [[ -t 2 ]]; then show_progress=1; else show_progress=0; fi

pending=$(jq -r '.crn' "$EXPORT" | sort -u)
for pass in $(seq 1 "$PASSES"); do
  [[ -n "$pending" ]] || break
  todo=$(grep -c . <<<"$pending")
  step "Checking CRNs still exist in NDelius, pass $pass of $PASSES ($todo CRNs)"
  new_token
  retry=""; done_n=0; failed=0
  # A response holds the date of birth, so it lands in the private work_dir, is
  # emptied after each request, and is deleted at the end of the pass or, by
  # cleanup, on any exit.
  body=$(mktemp "$WORK_DIR/.response.XXXXXX")
  while read -r crn; do
    [[ -n "$crn" ]] || continue
    if [[ ! "$crn" =~ ^[A-Z0-9]+$ ]]; then
      result='{"status":"UNCHECKED","reason":"not a valid CRN"}'; code=000
    else
      request "$crn"
      if [[ "$code" == 401 ]]; then
        new_token; request "$crn"   # the token expired mid-pass
      fi
      case "$code" in
        200) result=$(jq -c "$CLASSIFY" "$body" 2>/dev/null) || result='{"status":"UNCHECKED","reason":"unreadable response"}' ;;
        404) result='{"status":"NOT_IN_DELIUS"}' ;;
        401|403)
          die "HTTP $code for $crn -- token rejected or missing ROLE_ESUPERVISION__ESUPERVISION_UI" ;;
        *)   result=$(jq -cn --arg c "$code" '{status: "UNCHECKED", reason: ("HTTP " + $c)}') ;;
      esac
      : > "$body"
    fi
    jq -c --arg crn "$crn" --argjson pass "$pass" --arg http "$code" \
      '{crn: $crn, pass: $pass, http: ($http | tonumber)} + .' <<<"$result" >> "$CRN_STATUS"
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

# Latest result per CRN.
crn_counts=$(jq -rs 'reduce .[] as $r ({}; .[$r.crn] = $r.status) | [.[]] | group_by(.) | map("\(.[0])=\(length)") | join(" ")' "$CRN_STATUS" 2>/dev/null || true)
echo "NDelius check: ${crn_counts:-no CRNs to check}" >&2

step "Generating answers ($(grep -c . "$EXPORT" || true) check-ins)"
java -Dloader.main=uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinNoteExportToolKt \
  -cp "$JAR" org.springframework.boot.loader.launch.PropertiesLauncher \
  "$EXPORT" "$NOTES"

step "Building the handover CSV"
touch "$CRN_STATUS"
python3 "$REPO_ROOT/scripts/build_handover_csv.py" "$WORK_DIR/delius_contacts.csv" "$NOTES" "$OUT" "$CRN_STATUS"

warned=$(jq -c 'select(.warnings | length > 0)' "$NOTES" | grep -c . || true)

step "Done"
echo "  Handover CSV: $OUT" >&2
[[ -f "$OUT_EXCEPTIONS" ]] && echo "  Exceptions:   $OUT_EXCEPTIONS" >&2
echo "  Match report: $REPORT  <- check the match grades and exception reports before handover" >&2
if (( warned > 0 )); then
  echo "  WARNING: $warned check-ins have warnings:  jq -c 'select(.warnings|length>0)' $NOTES" >&2
fi
not_in_delius=$(jq -rs 'reduce .[] as $r ({}; .[$r.crn] = $r.status) | map(select(. == "NOT_IN_DELIUS")) | length' "$CRN_STATUS")
if (( not_in_delius > 0 )); then
  echo "  NOTE: $not_in_delius CRNs no longer exist in NDelius; their contacts are in the exceptions file." >&2
fi
echo "  Everything in $WORK_DIR is sensitive personal data; delete it once handed over." >&2
if [[ -n "$pending" ]]; then
  echo "  WARNING: $(grep -c . <<<"$pending") CRNs could not be checked against NDelius after $PASSES passes;" >&2
  echo "           their contacts are held back in the exceptions file. Re-run to try again." >&2
  exit 3
fi

#!/usr/bin/env bash
#
# Practitioner stats export with tiers: each practitioner with their active
# online check-in CRNs, and the current tier of every one of those CRNs. One
# command, end to end, cleaning up after itself:
#
#   1. reads the RDS credentials from the namespace secret (held in memory only)
#   2. starts a Cloud Platform port-forward pod and a local port-forward to it
#   3. runs the practitioner stats query (below, PRACTITIONER_SQL) over a
#      connection forced read-only -- libpq refuses to connect otherwise --
#      and inside an explicit READ ONLY transaction that is rolled back, never
#      committed. Nothing this script sends the database can write to it
#   4. gets a token with the UI's system client credentials, by default from
#      inside a running UI pod (see run_practitioner_export.sh for why)
#   5. calls GET /v2/offenders/header/{crn} on this API for each distinct CRN.
#      Tiers are not stored here; that endpoint reads them live from the Tier
#      API (it also reads NDelius and ARNS, of which only the tier is kept)
#   6. writes the CSVs, deletes the port-forward pod and the working files
#      whatever happened
#
# The query is the twice-weekly practitioner stats query: one row per
# practitioner and PDU, over VERIFIED (active) offenders, excluding PDU XXX001.
# A CRN whose audit log carries more than one PDU appears under each, as it
# always has.
#
# Tiers are as the Tier API reports them: a single letter A-G on v3 (in
# production from 1 October 2026), 'D2' style on v2, or NOT_SUPERVISED. Two
# further labels are this script's own:
#   None     the Tier API holds no tier for the CRN
#   Unknown  the tier could not be read after every pass (see unresolved.txt)
#
# Prerequisites: kubectl authenticated to Cloud Platform with access to the
# namespace, psql 14+, jq, curl -- and the MoJ network, because the API ingress is
# allowlisted.
#
# Usage:
#   ./scripts/run_practitioner_tier_export.sh [work_dir]
#
#   work_dir defaults to ~/esup-practitioner-tier-export/<today>-<env>. It must
#   be outside this repo: the export ties staff to the CRNs they supervise.
#
# Env:
#   ENV            dev | test | preprod | prod (default prod). Try dev first.
#   LOCAL_PG_PORT  local end of the database port-forward, default 5433
#   PASSES         fetch passes, default 2. Later passes retry only the CRNs
#                  whose tier could not be read (upstream errors, timeouts)
#   RATE_SLEEP     seconds between requests, default 0.25
#   REQUEST_TIMEOUT  seconds before giving up on one request, default 60
#   TOKEN_SOURCE   pod (default) | laptop -- as in run_practitioner_export.sh
#   UI_NAMESPACE   default the API's own namespace
#   UI_DEPLOYMENT  default hmpps-esupervision-ui
#   EXPORT_API_BASE, EXPORT_AUTH_URL   override the per-environment URLs
#
# Outputs, in work_dir:
#   practitioner_tiers.csv
#               the deliverable: PDU code, PDU, Regions, Practitioner ID, CRNs,
#               Pop count, Tier counts -- one row per practitioner and PDU, as
#               the query has always given. CRNs reads "X123456 (B), X234567 (C)";
#               Tier counts reads "B: 1, C: 1"
#   crn_tiers.csv
#               the same, one row per CRN: PDU code, PDU, Practitioner ID, CRN,
#               Tier -- for pivoting and filtering in a spreadsheet
#   practitioner_tiers.PARTIAL.csv, crn_tiers.PARTIAL.csv
#               written INSTEAD of the two above when some tiers could not be
#               read: every row is there, but those CRNs show Unknown
#   unresolved.txt
#               CRNs whose tier could still not be read after every pass (only
#               written when there are some; the run then exits 3)

set -euo pipefail
umask 077

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ENV="${ENV:-prod}"
LOCAL_PG_PORT="${LOCAL_PG_PORT:-5433}"
PASSES="${PASSES:-2}"
RATE_SLEEP="${RATE_SLEEP:-0.25}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-60}"
NS="hmpps-esupervision-$ENV"
UI_NAMESPACE="${UI_NAMESPACE:-$NS}"
UI_DEPLOYMENT="${UI_DEPLOYMENT:-hmpps-esupervision-ui}"
TOKEN_SOURCE="${TOKEN_SOURCE:-pod}"
WORK_DIR="${1:-$HOME/esup-practitioner-tier-export/$(date +%Y-%m-%d)-$ENV}"

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
API_BASE="${EXPORT_API_BASE:-$default_api}"
AUTH_URL="${EXPORT_AUTH_URL:-$default_auth}"

[[ "$PASSES" =~ ^[1-9][0-9]*$ ]] \
  || { echo "ERROR: PASSES must be a positive whole number (got '$PASSES')" >&2; exit 1; }

step() { printf '\n==> %s\n' "$*" >&2; }
die()  { echo "ERROR: $*" >&2; exit 1; }

# The stats query, returning each row as JSON so the CRNs come back as a list
# rather than a comma-joined string.
PRACTITIONER_SQL="
SELECT json_build_object(
         'pduCode',        ealv.pdu_code,
         'pdu',            ealv.pdu_description,
         'regions',        string_agg(DISTINCT ealv.provider_description, ', '),
         'practitionerId', UPPER(ov.practitioner_id),
         'crns',           array_agg(DISTINCT ov.crn ORDER BY ov.crn))
FROM event_audit_log_v2 ealv
INNER JOIN offender_v2 ov ON ov.crn = ealv.crn
WHERE ealv.pdu_code != 'XXX001' AND ov.status = 'VERIFIED'
GROUP BY UPPER(ov.practitioner_id), ealv.pdu_description, ealv.pdu_code
ORDER BY ealv.pdu_description, UPPER(ov.practitioner_id)"

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
for cmd in kubectl psql jq curl; do
  command -v "$cmd" >/dev/null || die "$cmd is required"
done
# target_session_attrs=read-only, which forces every connection read-only
# below, arrived in libpq 14.
psql_major=$(psql --version | sed -nE 's/^psql \(PostgreSQL\) ([0-9]+).*/\1/p')
[[ "$psql_major" =~ ^[0-9]+$ ]] && (( psql_major >= 14 )) \
  || die "psql 14 or later is required to force a read-only connection (found: $(psql --version))"

mkdir -p "$WORK_DIR"
WORK_DIR="$(cd -- "$WORK_DIR" && pwd -P)"
case "$WORK_DIR/" in
  "$(cd -- "$REPO_ROOT" && pwd -P)/"*)
    die "work_dir $WORK_DIR is inside the repo. The export ties staff to the CRNs they supervise -- pick a directory outside it." ;;
esac
chmod 700 "$WORK_DIR"

ROWS="$WORK_DIR/practitioner_rows.jsonl"
CRNS="$WORK_DIR/active_crns.txt"
RESULTS="$WORK_DIR/tiers.jsonl"
EXPORT="$WORK_DIR/practitioner_tiers.csv"
CRN_EXPORT="$WORK_DIR/crn_tiers.csv"
PARTIAL_EXPORT="$WORK_DIR/practitioner_tiers.PARTIAL.csv"
PARTIAL_CRN_EXPORT="$WORK_DIR/crn_tiers.PARTIAL.csv"
UNRESOLVED="$WORK_DIR/unresolved.txt"
# Outputs are written here first and renamed into place only once both are
# complete, so a failure part-way never leaves a truncated file under a name
# that says it is finished.
EXPORT_TMP="$WORK_DIR/.export.tmp"
CRN_EXPORT_TMP="$WORK_DIR/.crn_export.tmp"
# Every run starts afresh, before any check that can fail, so a run that stops
# early never leaves an earlier run's results looking current.
rm -f "$ROWS" "$CRNS" "$RESULTS" "$EXPORT" "$CRN_EXPORT" "$PARTIAL_EXPORT" "$PARTIAL_CRN_EXPORT" \
  "$UNRESOLVED" "$EXPORT_TMP" "$CRN_EXPORT_TMP"

kubectl -n "$NS" auth can-i create pods >/dev/null 2>&1 \
  || die "kubectl cannot create pods in $NS -- check your Cloud Platform login and context"
kubectl -n "$NS" auth can-i delete pods >/dev/null 2>&1 \
  || die "kubectl cannot delete pods in $NS -- the port-forward pod could not be cleaned up"

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
# Secrets, held in shell variables only -- never written to disk
# ---------------------------------------------------------------------------
secret_value() {  # namespace secret key
  kubectl -n "$1" get secret "$2" -o json | jq -er --arg k "$3" '.data[$k] | @base64d'
}
rds_value() {
  secret_value "$NS" hmpps-esupervision-rds-settings "$1" \
    || die "could not read $1 from secret hmpps-esupervision-rds-settings in $NS"
}

step "Reading database credentials"
RDS_ENDPOINT=$(rds_value rds_instance_endpoint)
RDS_HOST="${RDS_ENDPOINT%%:*}"
PGUSER=$(rds_value database_username)
PGDATABASE=$(rds_value database_name)
PGPASSWORD=$(rds_value database_password)
export PGUSER PGDATABASE PGPASSWORD PGHOST=127.0.0.1 PGPORT="$LOCAL_PG_PORT"
# Read-only, forced on every connection, twice over:
#   PGOPTIONS                 the server starts each session with
#                             default_transaction_read_only=on
#   PGTARGETSESSIONATTRS      libpq itself then refuses to complete any
#                             connection whose session is not read-only, so
#                             if the option were stripped on the way (a pooler,
#                             a proxy) nothing would connect at all
export PGOPTIONS="-c default_transaction_read_only=on"
export PGTARGETSESSIONATTRS=read-only

if [[ "$TOKEN_SOURCE" == laptop ]]; then
  step "Reading the UI system client credentials"
  CLIENT_ID=$(secret_value "$UI_NAMESPACE" hmpps-esupervision-ui-client-creds CLIENT_CREDS_CLIENT_ID) \
    || die "could not read hmpps-esupervision-ui-client-creds in $UI_NAMESPACE (set UI_NAMESPACE if the UI lives elsewhere)"
  CLIENT_SECRET=$(secret_value "$UI_NAMESPACE" hmpps-esupervision-ui-client-creds CLIENT_CREDS_CLIENT_SECRET)
fi

# ---------------------------------------------------------------------------
# Port-forward and working files, all cleaned up on any exit
# ---------------------------------------------------------------------------
POD="practitioner-tier-export-$(whoami | tr -cd 'a-z0-9' | cut -c1-16)-$$-$(od -An -N4 -tx4 /dev/urandom | tr -d ' \n')"
PF_PID=""
body=""

cleanup() {
  local rc=$?
  [[ -n "$PF_PID" ]] && kill "$PF_PID" 2>/dev/null || true
  echo "Deleting port-forward pod $POD" >&2
  kubectl -n "$NS" delete pod "$POD" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  rm -f "$ROWS" "$CRNS" "$RESULTS" "$EXPORT_TMP" "$CRN_EXPORT_TMP" ${body:+"$body"}
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

# -X on every call: a personal ~/.psqlrc must never run against this database.
for _ in $(seq 1 30); do
  if probe_err=$(psql -X -qtAc 'select 1' 2>&1 >/dev/null); then break; fi
  [[ "$probe_err" != *"not read-only"* ]] \
    || die "the database refused a read-only connection -- refusing to continue: $probe_err"
  kill -0 "$PF_PID" 2>/dev/null || die "port-forward exited -- is local port $LOCAL_PG_PORT free? (set LOCAL_PG_PORT)"
  sleep 1
done
psql -X -qtAc 'select 1' >/dev/null || die "database not reachable on 127.0.0.1:$LOCAL_PG_PORT"

[[ "$(psql -X -qtAc 'show default_transaction_read_only')" == "on" ]] \
  || die "the database session is not read-only (default_transaction_read_only is not on) -- refusing to continue"
echo "Database session confirmed read-only" >&2

# The check above is a separate connection, so the query does not rely on it:
# it runs in an explicit READ ONLY transaction, which asserts read-only from
# inside that same session before the query, and is rolled back, never
# committed. Postgres rejects any write in it, whatever PGOPTIONS did.
READ_ONLY_CHECK="DO \$\$ BEGIN IF current_setting('transaction_read_only') <> 'on' THEN
  RAISE EXCEPTION 'session is not read-only -- refusing to run the query'; END IF; END \$\$"

step "Running the practitioner stats query (read-only)"
psql -X -qtA -v ON_ERROR_STOP=1 \
  -c "BEGIN TRANSACTION READ ONLY" -c "$READ_ONLY_CHECK" -c "$PRACTITIONER_SQL" -c "ROLLBACK" > "$ROWS" \
  || die "SQL step failed"

kill "$PF_PID" 2>/dev/null || true; PF_PID=""
# The database is finished with: nothing from here on inherits its password.
unset PGPASSWORD PGUSER PGDATABASE PGHOST PGPORT PGOPTIONS PGTARGETSESSIONATTRS
kubectl -n "$NS" delete pod "$POD" --ignore-not-found --wait=false >/dev/null 2>&1 || true

jq -rs 'map(.crns[]) | unique | .[]' "$ROWS" > "$CRNS" || die "could not read the query results"
rows=$(grep -c . "$ROWS" || true)
total=$(grep -c . "$CRNS" || true)
echo "$rows practitioner rows, $total distinct active CRNs" >&2
(( total > 0 )) || die "the query returned no CRNs -- nothing to export"

# ---------------------------------------------------------------------------
# Token -- the same two routes as run_practitioner_export.sh
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

token_from_pod() {
  # </dev/null: this runs inside the loop that reads the CRNs from stdin.
  TOKEN=$(kubectl -n "$UI_NAMESPACE" exec "deploy/$UI_DEPLOYMENT" -- node -e "$POD_TOKEN_JS" </dev/null) \
    && [[ -n "$TOKEN" ]] \
    || die "could not get a token from inside $UI_DEPLOYMENT (see the error above)"
}

token_from_laptop() {
  local out status resp detail
  out=$(printf 'user = "%s:%s"\n' "$CLIENT_ID" "$CLIENT_SECRET" \
    | curl -s -w '\n%{http_code}' --connect-timeout 10 --max-time 30 -K - -X POST \
        "$AUTH_URL/oauth/token?grant_type=client_credentials") || true
  status="${out##*$'\n'}"; resp="${out%$'\n'*}"
  if [[ "$status" == 200 ]] && TOKEN=$(jq -er .access_token <<<"$resp" 2>/dev/null); then
    return 0
  fi
  detail=$(jq -r '[.error, .error_description] | map(select(.)) | join(": ")' <<<"$resp" 2>/dev/null \
           || head -c 300 <<<"$resp")
  die "HMPPS Auth refused the token request: HTTP ${status:-000}${detail:+ -- $detail}
       If the client is IP-restricted in this environment, use TOKEN_SOURCE=pod (the default)."
}

new_token() {
  if [[ "$TOKEN_SOURCE" == pod ]]; then token_from_pod; else token_from_laptop; fi
}

# ---------------------------------------------------------------------------
# Fetch
# ---------------------------------------------------------------------------
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

# Turns a 200 into a tier, or a reason to retry. The header endpoint answers
# 200 even when the Tier API failed: tierScore is then null and errors names
# tierScore with a code. NOT_FOUND there means the Tier API holds no tier for
# the CRN, which is an answer; anything else is worth retrying.
# shellcheck disable=SC2016  # jq variables, not shell ones
CLASSIFY='
  if (.crn | type) != "string" then {outcome: "retry", reason: "unexpected response"}
  elif (.tierScore | type) == "string" and .tierScore != "" then {outcome: "ok", tier: .tierScore}
  else ((.errors // []) | map(select(.field == "tierScore")) | .[0].code) as $c
    | if $c == "NOT_FOUND" then {outcome: "ok", tier: "None"}
      elif $c == null then {outcome: "retry", reason: "no tier and no reason given"}
      else {outcome: "retry", reason: ("Tier API " + $c)} end
  end'

if [[ -t 2 ]]; then show_progress=1; else show_progress=0; fi

pending="$(cat "$CRNS")"
for pass in $(seq 1 "$PASSES"); do
  [[ -n "$pending" ]] || break
  todo=$(grep -c . <<<"$pending")
  step "Fetching tiers, pass $pass of $PASSES ($todo CRNs)"
  new_token
  retry=""; done_n=0; failed=0
  # Each response holds more than we keep (date of birth, risk), so it lands in
  # the private work_dir, is emptied after each request, and is deleted at the
  # end of the pass or on exit.
  body=$(mktemp "$WORK_DIR/.response.XXXXXX")
  while read -r crn; do
    [[ -n "$crn" ]] || continue
    # It goes into the URL path, so nothing but letters and digits.
    if [[ ! "$crn" =~ ^[A-Za-z0-9]+$ ]]; then
      code=skip; result='{"outcome":"retry","reason":"not a valid CRN, not requested"}'
    else
      request "$crn"
      if [[ "$code" == 401 ]]; then
        new_token; request "$crn"   # the token expired mid-pass
      fi
    fi
    case "$code" in
      skip) ;;
      200)
        result=$(jq -c "$CLASSIFY" "$body" 2>/dev/null) || result='{"outcome":"retry","reason":"unreadable response"}' ;;
      401|403)
        die "HTTP $code for $crn -- token rejected or missing ROLE_ESUPERVISION__ESUPERVISION_UI" ;;
      404) result='{"outcome":"retry","reason":"CRN not found in NDelius (HTTP 404)"}' ;;
      *)   result=$(jq -cn --arg c "$code" '{outcome: "retry", reason: ("HTTP " + $c)}') ;;
    esac
    : > "$body"

    jq -c --arg crn "$crn" --argjson pass "$pass" '{crn: $crn, pass: $pass} + .' <<<"$result" >> "$RESULTS"
    if [[ "$(jq -r .outcome <<<"$result")" == retry ]]; then
      retry+="$crn"$'\n'; failed=$((failed + 1))
      (( show_progress )) && printf '\r\033[K' >&2
      echo "RETRY crn=$crn $(jq -r .reason <<<"$result")" >&2
    fi
    done_n=$((done_n + 1))
    (( show_progress )) && printf '\r\033[K%d/%d  failed=%d' "$done_n" "$todo" "$failed" >&2
    sleep "$RATE_SLEEP"
  done <<<"$pending"
  rm -f "$body"; body=""
  (( show_progress )) && printf '\r\033[K' >&2
  echo "pass $pass: requested=$done_n unresolved=$failed" >&2
  pending="$retry"
done

# ---------------------------------------------------------------------------
# Outputs: the query rows, in the query's order, with each CRN's tier added
# ---------------------------------------------------------------------------
# CRN -> tier from the latest outcome per CRN; Unknown where none succeeded.
TIERS=$(jq -cs 'reduce .[] as $r ({}; .[$r.crn] = $r)
                | with_entries(.value |= (if .outcome == "ok" then .tier else "Unknown" end))' "$RESULTS")

# A partial export gets a name that cannot be mistaken for the real thing.
if [[ -n "$pending" ]]; then
  EXPORT="$PARTIAL_EXPORT"; CRN_EXPORT="$PARTIAL_CRN_EXPORT"
fi

{
  echo '"PDU code","PDU","Regions","Practitioner ID","CRNs","Pop count","Tier counts"'
  jq -r --argjson t "$TIERS" '
    def tier: $t[.] // "Unknown";
    [.pduCode, .pdu, .regions, .practitionerId,
     (.crns | map("\(.) (\(tier))") | join(", ")),
     (.crns | length),
     (.crns | map(tier) | group_by(.) | map("\(.[0]): \(length)") | join(", "))] | @csv' "$ROWS"
} > "$EXPORT_TMP"

{
  echo '"PDU code","PDU","Practitioner ID","CRN","Tier"'
  jq -r --argjson t "$TIERS" '
    . as $row | .crns[] | [$row.pduCode, $row.pdu, $row.practitionerId, ., ($t[.] // "Unknown")] | @csv' "$ROWS"
} > "$CRN_EXPORT_TMP"
mv -f "$CRN_EXPORT_TMP" "$CRN_EXPORT"
mv -f "$EXPORT_TMP" "$EXPORT"

step "Done"
echo "  Practitioner rows: $rows" >&2
echo "  Distinct CRNs:     $total" >&2
jq -r 'to_entries | group_by(.value) | map("  Tier \(.[0].value): \(length)") | .[]' <<<"$TIERS" >&2
cat >&2 <<EOF

  Export:     $EXPORT
  Per CRN:    $CRN_EXPORT

  These files are personal data. Delete $WORK_DIR once the stats are handed over.
EOF

if [[ -n "$pending" ]]; then
  printf '%s' "$pending" > "$UNRESOLVED"
  n=$(grep -c . "$UNRESOLVED")
  echo "  WARNING: $n of $total CRNs have no tier in the export -- it could not be read after $PASSES passes." >&2
  echo "           They show as Unknown, and the files are named .PARTIAL.csv because they are incomplete." >&2
  echo "           See $UNRESOLVED and the RETRY lines above; re-run to try again." >&2
  exit 3
fi

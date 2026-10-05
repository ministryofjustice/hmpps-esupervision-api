#!/usr/bin/env bash
#
# Contact export for people on probation actively on online check-ins: their
# first name and mobile number, nothing else. One command, end to end,
# cleaning up after itself:
#
#   1. reads the RDS credentials from the namespace secret (held in memory only)
#   2. starts a Cloud Platform port-forward pod and a local port-forward to it
#   3. lists the active CRNs -- offender_v2.status = 'VERIFIED' -- in a session
#      that Postgres itself holds read-only (checked before anything runs)
#   4. gets a token with the UI's system client credentials, by default from
#      inside a running UI pod (see run_practitioner_export.sh for why)
#   5. calls GET /v2/offenders/crn/{crn}?include-personal-details=true on this
#      API for each CRN. We store no names or phone numbers; that endpoint reads
#      them live from NDelius, along with the person's current status here and
#      whether NDelius has their contact suspended
#   6. writes pop_contacts.csv, deletes the port-forward pod and the per-CRN
#      working file whatever happened
#
# A person is left out of the export, and listed in excluded.csv with the
# reason, when:
#   contact_suspended  NDelius has contact suspended -- they must not be contacted
#   no_longer_active   their status here changed from VERIFIED since the listing
#   no_mobile          NDelius holds no mobile number for them
#
# First names and numbers are as NDelius holds them, trimmed of surrounding
# whitespace. Two people sharing a number get a row each; the summary counts
# how many numbers are shared.
#
# Prerequisites: kubectl authenticated to Cloud Platform with access to the
# namespace, psql, jq, curl -- and the MoJ network, because the API ingress is
# allowlisted.
#
# Usage:
#   ./scripts/run_pop_contact_export.sh [work_dir]
#
#   work_dir defaults to ~/esup-pop-contact-export/<today>-<env>. It must be
#   outside this repo: the export is names and phone numbers.
#
# Env:
#   ENV            dev | test | preprod | prod (default prod). Try dev first.
#   LOCAL_PG_PORT  local end of the database port-forward, default 5433
#   PASSES         fetch passes, default 2. Later passes retry only the CRNs
#                  whose details could not be read (NDelius errors, timeouts)
#   RATE_SLEEP     seconds between requests, default 0.25
#   REQUEST_TIMEOUT  seconds before giving up on one request, default 60
#   TOKEN_SOURCE   pod (default) | laptop -- as in run_practitioner_export.sh
#   UI_NAMESPACE   default the API's own namespace
#   UI_DEPLOYMENT  default hmpps-esupervision-ui
#   EXPORT_API_BASE, EXPORT_AUTH_URL   override the per-environment URLs
#
# Outputs, in work_dir:
#   pop_contacts.csv  the deliverable: First name,Phone number -- one row per
#                     contactable person, sorted by first name
#   excluded.csv      CRN,Reason for each person left out (no names or numbers)
#   unresolved.txt    CRNs whose details could still not be read after every
#                     pass (only written when there are some; the run then exits 3)

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
WORK_DIR="${1:-$HOME/esup-pop-contact-export/$(date +%Y-%m-%d)-$ENV}"

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
    die "work_dir $WORK_DIR is inside the repo. The export is names and phone numbers -- pick a directory outside it." ;;
esac
chmod 700 "$WORK_DIR"

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

CRNS="$WORK_DIR/active_crns.txt"
RESULTS="$WORK_DIR/pop_contacts.jsonl"
EXPORT="$WORK_DIR/pop_contacts.csv"
EXCLUDED="$WORK_DIR/excluded.csv"
UNRESOLVED="$WORK_DIR/unresolved.txt"
# Every run starts afresh: the export is only meaningful against one cohort.
rm -f "$CRNS" "$RESULTS" "$EXPORT" "$EXCLUDED" "$UNRESOLVED"

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
export PGOPTIONS="-c default_transaction_read_only=on"

if [[ "$TOKEN_SOURCE" == laptop ]]; then
  step "Reading the UI system client credentials"
  CLIENT_ID=$(secret_value "$UI_NAMESPACE" hmpps-esupervision-ui-client-creds CLIENT_CREDS_CLIENT_ID) \
    || die "could not read hmpps-esupervision-ui-client-creds in $UI_NAMESPACE (set UI_NAMESPACE if the UI lives elsewhere)"
  CLIENT_SECRET=$(secret_value "$UI_NAMESPACE" hmpps-esupervision-ui-client-creds CLIENT_CREDS_CLIENT_SECRET)
fi

# ---------------------------------------------------------------------------
# Port-forward and per-CRN working file, both cleaned up on any exit
# ---------------------------------------------------------------------------
POD="pop-contact-export-$(whoami | tr -cd 'a-z0-9' | cut -c1-20)-$$"
PF_PID=""
body=""

cleanup() {
  local rc=$?
  [[ -n "$PF_PID" ]] && kill "$PF_PID" 2>/dev/null || true
  echo "Deleting port-forward pod $POD" >&2
  kubectl -n "$NS" delete pod "$POD" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  # The working file ties CRNs to names and numbers; the deliverable does not.
  rm -f "$RESULTS" ${body:+"$body"}
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

[[ "$(psql -qtAc 'show default_transaction_read_only')" == "on" ]] \
  || die "the database session is not read-only (default_transaction_read_only is not on) -- refusing to continue"
echo "Database session confirmed read-only" >&2

step "Listing active CRNs (read-only)"
psql -X -qtA -v ON_ERROR_STOP=1 \
  -c "SELECT crn FROM offender_v2 WHERE status = 'VERIFIED' ORDER BY crn" > "$CRNS" \
  || die "SQL step failed"

kill "$PF_PID" 2>/dev/null || true; PF_PID=""
kubectl -n "$NS" delete pod "$POD" --ignore-not-found --wait=false >/dev/null 2>&1 || true

total=$(grep -c . "$CRNS" || true)
echo "$total active CRNs" >&2
(( total > 0 )) || die "no VERIFIED CRNs found -- nothing to export"

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
  local resp status detail
  resp=$(mktemp)
  status=$(printf 'user = "%s:%s"\n' "$CLIENT_ID" "$CLIENT_SECRET" \
    | curl -s -o "$resp" -w '%{http_code}' --connect-timeout 10 --max-time 30 -K - -X POST \
        "$AUTH_URL/oauth/token?grant_type=client_credentials") || true
  if [[ "$status" == 200 ]] && TOKEN=$(jq -er .access_token "$resp" 2>/dev/null); then
    rm -f "$resp"; return 0
  fi
  detail=$(jq -r '[.error, .error_description] | map(select(.)) | join(": ")' "$resp" 2>/dev/null \
           || head -c 300 "$resp")
  rm -f "$resp"
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
    "$API_BASE/v2/offenders/crn/$1?include-personal-details=true" \
    <<<"Authorization: Bearer $TOKEN") || true
  code="${code:-000}"
}

# Turns a 200 into the two fields we export, or a reason to leave the person
# out. The endpoint answers with details null when NDelius could not be read
# (its client falls back to null on errors and an open circuit) -- that is
# worth retrying. Only forename and mobile are kept from the response.
# shellcheck disable=SC2016  # jq variables, not shell ones
CLASSIFY='
  def trimmed: (. // "") | gsub("^\\s+|\\s+$"; "");
  if .status != "VERIFIED" then {outcome: "excluded", reason: "no_longer_active"}
  elif .details == null then {outcome: "retry", reason: "NDelius details unavailable"}
  elif .details.contactSuspended == true then {outcome: "excluded", reason: "contact_suspended"}
  elif (.details.mobile | trimmed) == "" then {outcome: "excluded", reason: "no_mobile"}
  else {outcome: "ok", forename: (.details.name.forename | trimmed), mobile: (.details.mobile | trimmed)} end'

if [[ -t 2 ]]; then show_progress=1; else show_progress=0; fi

pending="$(cat "$CRNS")"
for pass in $(seq 1 "$PASSES"); do
  [[ -n "$pending" ]] || break
  todo=$(grep -c . <<<"$pending")
  step "Fetching contact details, pass $pass of $PASSES ($todo CRNs)"
  new_token
  retry=""; done_n=0; failed=0
  body=$(mktemp)
  while read -r crn; do
    [[ -n "$crn" ]] || continue
    request "$crn"
    if [[ "$code" == 401 ]]; then
      new_token; request "$crn"   # the token expired mid-pass
    fi
    case "$code" in
      200)
        result=$(jq -c "$CLASSIFY" "$body" 2>/dev/null) || result='{"outcome":"retry","reason":"unreadable response"}' ;;
      401|403)
        die "HTTP $code for $crn -- token rejected or missing ROLE_ESUPERVISION__ESUPERVISION_UI" ;;
      404) result='{"outcome":"retry","reason":"CRN not registered for e-supervision (HTTP 404)"}' ;;
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
# Outputs, from the latest outcome per CRN
# ---------------------------------------------------------------------------
LATEST='reduce .[] as $r ({}; .[$r.crn] = $r) | [.[]]'

{
  echo "First name,Phone number"
  jq -rs "$LATEST"' | map(select(.outcome == "ok")) | sort_by([(.forename | ascii_downcase), .mobile])
                    | .[] | [.forename, .mobile] | @csv' "$RESULTS"
} > "$EXPORT"

{
  echo "CRN,Reason"
  jq -rs "$LATEST"' | map(select(.outcome == "excluded")) | sort_by([.reason, .crn])
                    | .[] | [.crn, .reason] | @csv' "$RESULTS"
} > "$EXCLUDED"

exported=$(jq -s "$LATEST"' | map(select(.outcome == "ok")) | length' "$RESULTS")
shared=$(jq -s "$LATEST"' | map(select(.outcome == "ok")) | group_by(.mobile) | map(select(length > 1)) | length' "$RESULTS")

step "Done"
echo "  Exported:  $exported of $total active people" >&2
jq -rs "$LATEST"' | map(select(.outcome == "excluded")) | group_by(.reason) | .[]
                  | "  Excluded:  \(length) \(.[0].reason)"' "$RESULTS" >&2
(( shared == 0 )) || echo "  Note:      $shared phone numbers are shared by more than one person" >&2
cat >&2 <<EOF

  Export:    $EXPORT
  Excluded:  $EXCLUDED

  These files are personal data. Delete $WORK_DIR once the export is handed over.
EOF

if [[ -n "$pending" ]]; then
  printf '%s' "$pending" > "$UNRESOLVED"
  n=$(grep -c . "$UNRESOLVED")
  echo "  WARNING: $n of $total CRNs are not in the export -- their details could not be read after $PASSES passes." >&2
  echo "           See $UNRESOLVED and the RETRY lines above; re-run to try again." >&2
  exit 3
fi

#!/usr/bin/env bash
#
# GOV.UK Notify mailing list of everyone who has been set up for online
# check-ins, active or not: their CRN, first name, whether they are active,
# whether NDelius has their contact suspended, and the one contact detail their
# check-in contact preference says to use -- email address or mobile number.
# One command, end to end, cleaning up after itself:
#
#   1. reads the RDS credentials from the namespace secret (held in memory only)
#   2. starts a Cloud Platform port-forward pod and a local port-forward to it
#   3. lists the CRNs of everyone set up -- offender_v2.status VERIFIED or
#      INACTIVE, so not INITIAL (setup never finished) -- in a session that
#      Postgres itself holds read-only (checked before anything runs)
#   4. gets a token with the UI's system client credentials, by default from
#      inside a running UI pod (see run_practitioner_export.sh for why)
#   5. calls GET /v2/offenders/crn/{crn}?include-personal-details=true on this
#      API for each CRN. We store no names, emails or phone numbers; that
#      endpoint reads them live from NDelius, along with the person's current
#      status and contact preference here and whether they have died. For
#      anyone who would be in the list, GET .../supervision-package then says
#      whether they have been recalled to prison
#   6. writes the mailing lists, deletes the port-forward pod and the per-CRN
#      working file whatever happened
#
# Active is Yes for VERIFIED and No for INACTIVE, as the API reports it at the
# time of the request. People whose contact is suspended in NDelius are
# included, flagged Yes in the Contact suspended column: filter them out before
# sending anything they must not receive.
#
# A person is left out, and listed in excluded.csv with the reason, when:
#   deceased           NDelius has a date of death for them
#   recalled           they have been recalled to prison on any sentence (as
#                      the IS_RECALLED eligibility rule has it). Recalled but
#                      unlawfully at large, or a recall request not yet
#                      decided, does not count: they stay in
#   no_email           their preference is EMAIL and NDelius holds no email
#   invalid_email      their preference is EMAIL and the email is malformed
#   no_mobile          their preference is PHONE and NDelius holds no mobile
#   not_a_mobile       a well-formed UK number that is not a mobile (a landline,
#                      03..., 08..., 09...), held in NDelius's mobile field
#   invalid_number     the number is neither a UK mobile nor an international
#                      one (+ or 00 then a country code), so cannot be used
#   not_set_up         their status here is no longer VERIFIED or INACTIVE
#
# Recall is asked about only for people who would otherwise be in the list, so
# someone recalled who also has, say, no mobile is listed as no_mobile.
#
# Against an API that predates the dateOfDeath or recalled fields, the run stops
# rather than passing everyone as alive and not recalled.
#
# Phone numbers: UK mobiles -- 07..., +44 7..., +44 (0)7..., 0044 7..., 44 7...
# with any spaces, dots, dashes or brackets -- are normalised to +44 and the ten
# digits, e.g. +447700900123. Any other international number is kept as NDelius
# holds it; Notify only sends to those if the service allows international
# texts. Email addresses are trimmed and lower-cased. Spreadsheets mangle phone
# numbers (a leading + or 0 is read as a formula or dropped), so upload the
# files as they are, rather than opening and re-saving them.
#
# Prerequisites: kubectl authenticated to Cloud Platform with access to the
# namespace, psql, jq, curl -- and the MoJ network, because the API ingress is
# allowlisted.
#
# Usage:
#   ENV=dev ./scripts/run_pop_mailing_list_export.sh [work_dir]
#
#   work_dir defaults to ~/esup-pop-mailing-list/<today>-<env>. It must be
#   outside this repo: the export is names, emails and phone numbers.
#
# Env:
#   ENV            dev | test | preprod | prod. Required -- unlike the other
#                  run_*.sh scripts there is no prod default. Try dev first.
#   LOCAL_PG_PORT  local end of the database port-forward, default 5433
#   PASSES         fetch passes, default 2. Later passes retry only the CRNs
#                  whose details could not be read (NDelius errors, timeouts)
#   RATE_SLEEP     seconds between people, default 0.25 (each is one request,
#                  or two for anyone whose recall status is checked)
#   REQUEST_TIMEOUT  seconds before giving up on one request, default 60
#   TOKEN_SOURCE   pod (default) | laptop -- as in run_practitioner_export.sh
#   UI_NAMESPACE   default the API's own namespace
#   UI_DEPLOYMENT  default hmpps-esupervision-ui
#   EXPORT_API_BASE, EXPORT_AUTH_URL   override the per-environment URLs
#
# Outputs, in work_dir:
#   mailing_list.csv  everyone exported, one row per person, sorted by CRN:
#                     CRN,First name,Active,Contact suspended,
#                     Contact preference,Email address,Phone number
#   notify_email.csv  the EMAIL-preference rows, ready to upload to Notify:
#                     email address,first name,crn,active,contact suspended
#   notify_sms.csv    the PHONE-preference rows, ready to upload to Notify:
#                     phone number,first name,crn,active,contact suspended
#   excluded.csv      CRN,Reason for each person left out (no names or contacts)
#   unresolved.txt    CRNs whose details could still not be read after every
#                     pass (only written when there are some; the run then
#                     exits 3 and the three lists are named .PARTIAL.csv)

set -euo pipefail
umask 077

usage() {
  echo "Usage: ENV=<dev|test|preprod|prod> $0 [work_dir]" >&2
  exit 1
}

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
[[ $# -le 1 ]] || usage
# No default: an export of everyone's contact details should never reach prod
# because ENV was forgotten.
ENV="${ENV:-}"
[[ -n "$ENV" ]] || { echo "ERROR: ENV is not set" >&2; usage; }
LOCAL_PG_PORT="${LOCAL_PG_PORT:-5433}"
PASSES="${PASSES:-2}"
RATE_SLEEP="${RATE_SLEEP:-0.25}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-60}"
NS="hmpps-esupervision-$ENV"
UI_NAMESPACE="${UI_NAMESPACE:-$NS}"
UI_DEPLOYMENT="${UI_DEPLOYMENT:-hmpps-esupervision-ui}"
TOKEN_SOURCE="${TOKEN_SOURCE:-pod}"
WORK_DIR="${1:-$HOME/esup-pop-mailing-list/$(date +%Y-%m-%d)-$ENV}"

case "$ENV" in
  prod)    default_api=https://esupervision-api.hmpps.service.justice.gov.uk
           default_auth=https://sign-in.hmpps.service.justice.gov.uk/auth ;;
  preprod) default_api=https://esupervision-api-preprod.hmpps.service.justice.gov.uk
           default_auth=https://sign-in-preprod.hmpps.service.justice.gov.uk/auth ;;
  test)    default_api=https://esupervision-api-test.hmpps.service.justice.gov.uk
           default_auth=https://sign-in-dev.hmpps.service.justice.gov.uk/auth ;;
  dev)     default_api=https://esupervision-api-dev.hmpps.service.justice.gov.uk
           default_auth=https://sign-in-dev.hmpps.service.justice.gov.uk/auth ;;
  *) echo "ERROR: ENV must be dev, test, preprod or prod (got '$ENV')" >&2; usage ;;
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
    die "work_dir $WORK_DIR is inside the repo. The export is names, emails and phone numbers -- pick a directory outside it." ;;
esac
chmod 700 "$WORK_DIR"

# One run per work_dir: two runs on the same day share the default one, and
# each would delete the other's files. mkdir is atomic, so only one run can
# take the lock. It is taken before anything is deleted and released on exit
# -- by this run only, so a refused run never frees a lock it does not hold.
LOCK="$WORK_DIR/.lock"
mkdir "$LOCK" 2>/dev/null \
  || die "another run is using $WORK_DIR. If none is (one was killed outright), remove $LOCK and re-run."
release_lock() { rmdir "$LOCK" 2>/dev/null || true; }
trap release_lock EXIT

CRNS="$WORK_DIR/crns.txt"
RESULTS="$WORK_DIR/mailing_list.jsonl"
UNRESOLVED="$WORK_DIR/unresolved.txt"
EXCLUDED="$WORK_DIR/excluded.csv"
# The deliverables, each with a .PARTIAL twin used instead when some CRNs could
# not be read, so an incomplete list cannot be mistaken for the real thing.
LISTS=(mailing_list notify_email notify_sms)
# Every output is written to a dot-file first and renamed into place only once
# all are complete, so a failure part-way never leaves a truncated file under a
# name that says it is finished.
tmp_of() { echo "$WORK_DIR/.$1.tmp"; }
# Every run starts afresh: the export is only meaningful against one cohort.
# That happens before any check that can fail, so a run that stops early
# never leaves an earlier run's results looking current.
for l in "${LISTS[@]}" excluded; do
  rm -f "$WORK_DIR/$l.csv" "$WORK_DIR/$l.PARTIAL.csv" "$(tmp_of "$l")"
done
rm -f "$CRNS" "$RESULTS" "$UNRESOLVED"

kubectl -n "$NS" auth can-i create pods >/dev/null 2>&1 \
  || die "kubectl cannot create pods in $NS -- check your Cloud Platform login and context"
# Creating and deleting are authorised separately: without delete, every
# cleanup would fail and leave the port-forward pod running.
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
# The namespace is shared, and cleanup deletes this pod by name, so the name
# must be this run's alone: user and PID can repeat across machines, the random
# suffix makes a clash practically impossible.
POD="pop-mailing-list-$(whoami | tr -cd 'a-z0-9' | cut -c1-20)-$$-$(od -An -N4 -tx4 /dev/urandom | tr -d ' \n')"
PF_PID=""
body=""

cleanup() {
  local rc=$? l
  [[ -n "$PF_PID" ]] && kill "$PF_PID" 2>/dev/null || true
  echo "Deleting port-forward pod $POD" >&2
  kubectl -n "$NS" delete pod "$POD" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  # The working file ties CRNs to names and contact details, as does any
  # half-written output.
  rm -f "$RESULTS" ${body:+"$body"}
  for l in "${LISTS[@]}" excluded; do rm -f "$(tmp_of "$l")"; done
  release_lock
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

step "Listing CRNs of everyone set up (read-only)"
psql -X -qtA -v ON_ERROR_STOP=1 \
  -c "SELECT crn FROM offender_v2 WHERE status IN ('VERIFIED', 'INACTIVE') ORDER BY crn" > "$CRNS" \
  || die "SQL step failed"

kill "$PF_PID" 2>/dev/null || true; PF_PID=""
# The database is finished with: nothing from here on inherits its password.
unset PGPASSWORD PGUSER PGDATABASE PGHOST PGPORT PGOPTIONS
kubectl -n "$NS" delete pod "$POD" --ignore-not-found --wait=false >/dev/null 2>&1 || true

total=$(grep -c . "$CRNS" || true)
echo "$total CRNs" >&2
(( total > 0 )) || die "no VERIFIED or INACTIVE CRNs found -- nothing to export"

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
  # The response holds the token, so it stays in memory: no temp file for an
  # interrupted run to leave behind. -w appends the status on a line of its own.
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
request() {  # path
  code=$(curl -s -o "$body" -w '%{http_code}' \
    --connect-timeout 10 --max-time "$REQUEST_TIMEOUT" \
    -H @- -H 'Accept: application/json' \
    "$API_BASE$1" \
    <<<"Authorization: Bearer $TOKEN") || true
  code="${code:-000}"
}

# A request, made again with a fresh token if the current one has expired.
fetch() {  # path
  request "$1"
  if [[ "$code" == 401 ]]; then
    new_token; request "$1"   # the token expired mid-pass
  fi
}

# For someone otherwise in the export: whether they have been recalled to
# prison, from GET /v2/offenders/crn/{crn}/supervision-package. Someone
# recalled but unlawfully at large, or with a recall request not yet decided,
# is not recalled there, and stays in. Leaves $result as it is if they are not
# recalled, and replaces it if they are or if it cannot be told.
check_recall() {  # crn
  local recalled
  fetch "/v2/offenders/crn/$1/supervision-package"
  case "$code" in
    200)
      # tostring: jq -e counts a bare false as failure.
      if recalled=$(jq -er '.recalled | booleans | tostring' "$body" 2>/dev/null); then
        [[ "$recalled" == false ]] || result='{"outcome":"excluded","reason":"recalled"}'
      elif jq -e 'type == "object" and has("onSupervisionPackage")' "$body" >/dev/null 2>&1; then
        die "the API in $ENV does not say whether people are recalled -- deploy the API with 'recalled' on GET /v2/offenders/crn/{crn}/supervision-package first"
      else
        result='{"outcome":"retry","reason":"unreadable supervision package response"}'
      fi ;;
    401|403)
      die "HTTP $code for $1's supervision package -- token rejected or missing ROLE_ESUPERVISION__ESUPERVISION_UI" ;;
    404) result='{"outcome":"retry","reason":"CRN not known to Supervision Packages (HTTP 404)"}' ;;
    *)   result=$(jq -cn --arg c "$code" '{outcome: "retry", reason: ("supervision package: HTTP " + $c)}') ;;
  esac
  : > "$body"
}

# Turns a 200 into the fields we export, or a reason to leave the person out.
# The endpoint answers with details null when NDelius could not be read (its
# client falls back to null on errors and an open circuit) -- that is worth
# retrying. Only the forename, the contact detail the preference picks, and the
# suspended flag are kept from the response.
#
# phone: separators and a "(0)" trunk prefix are dropped, then a UK number
# (07..., +44..., 0044..., or 44 and ten digits) becomes +44 and its ten
# significant digits if it is a mobile (7 then nine digits), or is rejected as
# not_a_mobile if it has the shape of another UK number. Anything else starting
# + or 00 then a country code is taken as non-UK and kept as NDelius holds it.
# Otherwise, invalid_number.
#
# email: trimmed and lower-cased; one @, no spaces, and a dot in the domain.
# Notify does its own stricter check on upload.
# shellcheck disable=SC2016  # jq variables, not shell ones
CLASSIFY='
  def trimmed: (. // "") | gsub("^\\s+|\\s+$"; "");
  def phone:
    trimmed as $held
    | ($held | gsub("\\(0\\)"; "") | gsub("[\\s().-]"; "")) as $d
    | (if ($d | test("^\\+44")) then $d[3:]
       elif ($d | test("^0044")) then $d[4:]
       elif ($d | test("^44[1-9][0-9]{9}$")) then $d[2:]
       elif ($d | test("^0[1-9]")) then $d[1:]
       else null end) as $uk
    | if $uk != null then
        ($uk | sub("^0"; "")) as $n
        | if ($n | test("^7[0-9]{9}$")) then {contact: ("+44" + $n), non_uk: false}
          elif ($n | test("^[1-689][0-9]{8,9}$")) then {reason: "not_a_mobile"}
          else {reason: "invalid_number"} end
      elif ($d | test("^(\\+|00)[1-9][0-9]{6,14}$")) then {contact: $held, non_uk: true}
      else {reason: "invalid_number"} end;
  def email:
    (trimmed | ascii_downcase) as $e
    | if ($e | test("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) then {contact: $e, non_uk: false}
      else {reason: "invalid_email"} end;
  if (.status | type) != "string" then {outcome: "retry", reason: "response has no status"}
  elif (.status == "VERIFIED" or .status == "INACTIVE") | not then {outcome: "excluded", reason: "not_set_up"}
  elif (.contactPreference == "PHONE" or .contactPreference == "EMAIL") | not
    then {outcome: "retry", reason: "unexpected contact preference \(.contactPreference)"}
  elif .details == null then {outcome: "retry", reason: "NDelius details unavailable"}
  elif (.details | has("dateOfDeath")) | not then {outcome: "fatal", reason: "no dateOfDeath in the personal details"}
  elif .details.dateOfDeath != null then {outcome: "excluded", reason: "deceased"}
  elif .contactPreference == "EMAIL" and (.details.email | trimmed) == "" then {outcome: "excluded", reason: "no_email"}
  elif .contactPreference == "PHONE" and (.details.mobile | trimmed) == "" then {outcome: "excluded", reason: "no_mobile"}
  else (if .contactPreference == "EMAIL" then (.details.email | email) else (.details.mobile | phone) end) as $c
    | if $c.reason != null then {outcome: "excluded", reason: $c.reason}
      else {outcome: "ok", forename: (.details.name.forename | trimmed), active: (.status == "VERIFIED"),
            suspended: (.details.contactSuspended == true), preference: .contactPreference,
            contact: $c.contact, non_uk: $c.non_uk} end
  end'

if [[ -t 2 ]]; then show_progress=1; else show_progress=0; fi

pending="$(cat "$CRNS")"
for pass in $(seq 1 "$PASSES"); do
  [[ -n "$pending" ]] || break
  todo=$(grep -c . <<<"$pending")
  step "Fetching contact details, pass $pass of $PASSES ($todo CRNs)"
  new_token
  retry=""; done_n=0; failed=0
  # Each response holds more than we export (surname, date of birth, the
  # contact detail not preferred), so it lands in the private work_dir, is
  # emptied after each request, and is deleted at the end of the pass or on exit.
  body=$(mktemp "$WORK_DIR/.response.XXXXXX")
  while read -r crn; do
    [[ -n "$crn" ]] || continue
    fetch "/v2/offenders/crn/$crn?include-personal-details=true"
    case "$code" in
      200)
        # -e: an empty body gives jq no input and so no output, which would
        # otherwise succeed and drop the person without a retry.
        result=$(jq -ce "$CLASSIFY" "$body" 2>/dev/null) || result='{"outcome":"retry","reason":"unreadable response"}' ;;
      401|403)
        die "HTTP $code for $crn -- token rejected or missing ROLE_ESUPERVISION__ESUPERVISION_UI" ;;
      404) result='{"outcome":"retry","reason":"CRN not registered for e-supervision (HTTP 404)"}' ;;
      *)   result=$(jq -cn --arg c "$code" '{outcome: "retry", reason: ("HTTP " + $c)}') ;;
    esac
    : > "$body"
    case "$(jq -r .outcome <<<"$result")" in
      # An API from before dateOfDeath would otherwise pass everyone as alive.
      fatal) die "the API in $ENV does not say whether people have died ($(jq -r .reason <<<"$result")) -- deploy the API with dateOfDeath first" ;;
      ok) check_recall "$crn" ;;
    esac

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
OK="$LATEST"' | map(select(.outcome == "ok")) | sort_by(.crn)'
YN='if . then "Yes" else "No" end'

# A partial run gets names that cannot be mistaken for the real thing.
if [[ -z "$pending" ]]; then suffix=""; else suffix=".PARTIAL"; fi
out_of() { echo "$WORK_DIR/$1$suffix.csv"; }

{
  echo "CRN,First name,Active,Contact suspended,Contact preference,Email address,Phone number"
  jq -rs "$OK"' | .[] | [.crn, .forename, (.active | '"$YN"'), (.suspended | '"$YN"'), .preference,
                        (if .preference == "EMAIL" then .contact else "" end),
                        (if .preference == "PHONE" then .contact else "" end)] | @csv' "$RESULTS"
} > "$(tmp_of mailing_list)"

# Notify finds the recipient by its column name; the other columns are
# personalisation, available to a template as ((first name)) and so on.
notify_list() {  # preference recipient-column
  echo "$2,first name,crn,active,contact suspended"
  jq -rs --arg p "$1" "$OK"' | map(select(.preference == $p))
                             | .[] | [.contact, .forename, .crn, (.active | '"$YN"'), (.suspended | '"$YN"')] | @csv' "$RESULTS"
}
notify_list EMAIL "email address" > "$(tmp_of notify_email)"
notify_list PHONE "phone number" > "$(tmp_of notify_sms)"

{
  echo "CRN,Reason"
  jq -rs "$LATEST"' | map(select(.outcome == "excluded")) | sort_by([.reason, .crn])
                    | .[] | [.crn, .reason] | @csv' "$RESULTS"
} > "$(tmp_of excluded)"

mv -f "$(tmp_of excluded)" "$EXCLUDED"
for l in "${LISTS[@]}"; do mv -f "$(tmp_of "$l")" "$(out_of "$l")"; done

count() { jq -s "$OK | map(select($1)) | length" "$RESULTS"; }
exported=$(count true)
by_email=$(count '.preference == "EMAIL"')
by_sms=$(count '.preference == "PHONE"')
inactive=$(count '.active | not')
suspended=$(count .suspended)
non_uk=$(count .non_uk)
shared=$(jq -s "$OK"' | group_by(.contact) | map(select(length > 1)) | length' "$RESULTS")

step "Done"
echo "  Exported:  $exported of $total people ($by_email by email, $by_sms by text)" >&2
echo "             $inactive inactive, $suspended with contact suspended" >&2
jq -rs "$LATEST"' | map(select(.outcome == "excluded")) | group_by(.reason) | .[]
                  | "  Excluded:  \(length) \(.[0].reason)"' "$RESULTS" >&2
(( non_uk == 0 )) || echo "  Note:      $non_uk non-UK phone number(s): Notify texts these only if international texts are on" >&2
(( shared == 0 )) || echo "  Note:      $shared email address(es) or phone number(s) shared by more than one person" >&2
cat >&2 <<EOF

  Full list:    $(out_of mailing_list)
  Notify email: $(out_of notify_email)
  Notify text:  $(out_of notify_sms)
  Excluded:     $EXCLUDED

  Filter out Contact suspended = Yes before sending anything they must not get.
  Upload the files as they are: opening and re-saving them in a spreadsheet can
  strip the leading + from the phone numbers.
  These files are personal data. Delete $WORK_DIR once the export is handed over.
EOF

if [[ -n "$pending" ]]; then
  printf '%s' "$pending" > "$UNRESOLVED"
  n=$(grep -c . "$UNRESOLVED")
  echo "  WARNING: $n of $total CRNs are not in the export -- their details could not be read after $PASSES passes." >&2
  echo "           The lists are named *.PARTIAL.csv because they are incomplete." >&2
  echo "           See $UNRESOLVED and the RETRY lines above; re-run to try again." >&2
  exit 3
fi

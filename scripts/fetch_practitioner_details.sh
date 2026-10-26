#!/usr/bin/env bash
#
# Build the practitioner contact export: PDU, region, CRN, POP count, first
# name, email address -- ONE ROW PER PRACTITIONER, because the file is used as a mailing
# list. The cohort is every CRN that has, or has ever had, an online check-in;
# those CRNs are collapsed onto the practitioner who holds them.
#
# Reads practitioner_crns.jsonl (produced by scripts/practitioner_contact_list.sql,
# which carries the CRN, the username we hold, and the PDU/region snapshot from
# event_audit_log_v2) and calls GET /v2/offenders/crn/{crn}/practitioner-details
# for each CRN. That endpoint proxies NDelius GET /case/{crn} and returns the
# allocated practitioner's email, username, staff code, name and PDU.
#
# Column sources, since they differ:
#   PDU      the endpoint's live value where it has one, otherwise the snapshot
#            from the SQL step. Which was used is recorded per row in the JSONL
#            as pduSource, and counted in the run summary.
#   Region   the snapshot only. NDelius's provider is the probation region, and
#            the practitioner-details endpoint does not return it (PractitionerSummary
#            carries probationDeliveryUnit but not provider), so there is no live
#            source for this column without an API change.
#   CRN      from the SQL step. One practitioner holds several, so the cell
#            carries all of theirs, semicolon-separated and sorted.
#   POP count how many CRNs in this export the practitioner holds, counted
#            after the fetch so it follows the practitioner NDelius reports
#            today. Historic and deactivated CRNs count: they are part of the
#            cohort that was asked for.
#   First name from the endpoint (the allocated practitioner's forename), as
#            NDelius holds it.
#   Email    from the endpoint, lower-cased.
#
# Only practitioners with BOTH a first name and an email make the export: it is
# a mailing list, and a row with no one to write to is no use in it. A
# practitioner is left out where NDelius holds no address or no forename for
# them, where the case is unallocated, or where the lookup never succeeded.
# Nobody drops silently: practitioners_unmatched.csv names every one of them,
# with their PDU, region and CRNs, so their details can be found by hand, and
# the run summary counts them (practitioners_excluded, crns_excluded).
#
# Normally run via scripts/run_practitioner_export.sh, which gets the token and
# runs the SQL step first. The notes below are for running it by hand.
#
# READ ONLY: GETs only. The endpoint performs no writes.
#
# Auth: needs ROLE_ESUPERVISION__ESUPERVISION_UI (see v2/offender/OffenderResource.kt).
# That role belongs to the UI's SYSTEM client, not to practitioners (see
# docs/local-development.md), and the UI calls this API server-side -- so a
# browser session never holds a usable token.
#
# If TOKEN is not set, the script gets one itself: it runs a client-credentials
# grant from inside a running UI pod (kubectl exec), using the HMPPS_AUTH_URL
# and CLIENT_CREDS_* the deployment already has. HMPPS Auth can restrict the
# client to the cluster's IPs, and this way the client secret never leaves the
# cluster -- only the token does. That needs kubectl logged in to Cloud
# Platform with exec rights in the UI namespace. A token obtained this way is
# refreshed automatically if it expires mid-run (401), and the CRN retried once.
#
# If TOKEN is set it is used as given, and a 401 mid-run stops cleanly: refresh
# it and re-run.
#
# Prod ingress is allowlisted, so be on the MoJ network, or port-forward the
# service and set API_BASE=http://localhost:8080.
#
# Usage:
#   ENV=prod ./scripts/fetch_practitioner_details.sh [practitioner_crns.jsonl] [practitioners.jsonl]
#
# Env:
#   ENV         dev | test | preprod | prod (default prod). Picks the default
#               API_BASE and the namespace the token is requested from.
#   TOKEN       optional bearer token; skips the automatic token request
#   UI_NAMESPACE   namespace of the UI deployment, default hmpps-esupervision-<ENV>
#   UI_DEPLOYMENT  default hmpps-esupervision-ui
#   API_BASE    default the ENV's API, e.g. https://esupervision-api.hmpps.service.justice.gov.uk
#   RATE_SLEEP  seconds between requests, default 0.5 (~2/s; ingress caps at
#               50 rps / 800 rpm, so this is comfortably under). ~700 CRNs is
#               about six minutes.
#   REQUEST_TIMEOUT  seconds before giving up on one request, default 60; the
#               CRN is recorded as HTTP 000 and retried on the next pass
#   PROGRESS    progress bar with ETA while fetching: auto (default -- shown
#               when stderr is a terminal), 1 to force it, 0 to hide it
#   USERNAMES   optional practitioner_usernames.csv from step 3 of the SQL. By
#               default the unmatched report is measured against the usernames
#               in the CRN file; point this at the wider list to include people
#               who only ever reviewed a check-in.
#   EXCLUDE_REGIONS  regions whose CRNs are dropped before anything is fetched,
#               semicolon-separated, matched exactly against the region
#               snapshot. Default "ZZ BAST Public Provider 1", a test region.
#               Set it empty to keep every region. A CRN with no region
#               snapshot cannot be matched and is kept.
#
# Re-running is safe and resumes: CRNs already fetched successfully are
# skipped, and the outputs are rebuilt from the whole JSONL every time. If a run
# stops (an auth failure that a fresh token does not fix, Ctrl-C, a network
# drop), run it again -- only the outstanding rows are retried.
#
# NOTE ON 404s. The endpoint's NDelius call has a circuit-breaker fallback that
# turns an upstream outage into a null, which surfaces here as 404 -- the same
# status as "no such CRN" or "nobody allocated". A 404 is therefore NOT proof
# the CRN has no practitioner. 404s are recorded and retried on the next run
# (only 200s count as done), so re-run once more before concluding that the
# remaining ones are genuinely unallocated.
#
# NOTE: the output ties named staff and their work email addresses to the CRNs
# they supervise -- personal data on both sides. Run this from a working
# directory outside the repo, as the SQL step says to; outputs are written
# beside the input file (OUT_DIR overrides), and the repo's .gitignore carries
# their names as a second line of defence. Delete working copies when done.

set -euo pipefail

# Everything this creates is personal data: files 0600, directories 0700, so
# other users on a shared host cannot read them whatever their default umask.
umask 077

ENV="${ENV:-prod}"
UI_NAMESPACE="${UI_NAMESPACE:-hmpps-esupervision-$ENV}"
UI_DEPLOYMENT="${UI_DEPLOYMENT:-hmpps-esupervision-ui}"
# Hosts from helm_deploy/values-<env>.yaml. Checked even when API_BASE is
# given, since ENV also names the namespace the token comes from.
case "$ENV" in
  prod)    default_api=https://esupervision-api.hmpps.service.justice.gov.uk ;;
  preprod) default_api=https://esupervision-api-preprod.hmpps.service.justice.gov.uk ;;
  test)    default_api=https://esupervision-api-test.hmpps.service.justice.gov.uk ;;
  dev)     default_api=https://esupervision-api-dev.hmpps.service.justice.gov.uk ;;
  *) echo "ERROR: ENV must be dev, test, preprod or prod (got '$ENV')" >&2; exit 1 ;;
esac
API_BASE="${API_BASE:-$default_api}"
IN="${1:-practitioner_crns.jsonl}"
OUT="${2:-practitioners.jsonl}"
RATE_SLEEP="${RATE_SLEEP:-0.5}"
# Per-request cap. Without one, a single stalled connection would hang the whole
# export silently -- the progress bar only moves when a request finishes. A
# timed-out CRN is recorded as HTTP 000 and retried on the next pass.
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-60}"
# Outputs land beside the input file, which is wherever psql was run and wrote
# practitioner_crns.jsonl -- deliberately not the current directory, so that
# running this from a repo checkout does not drop named staff, their work
# emails and the CRNs they supervise into the working tree. OUT_DIR overrides.
OUT_DIR="${OUT_DIR:-$(dirname -- "$IN")}"
CSV_OUT="$OUT_DIR/practitioner_export.csv"
UNMATCHED_OUT="$OUT_DIR/practitioners_unmatched.csv"

[[ -r "$IN" ]]        || { echo "ERROR: cannot read input file: $IN" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }
if [[ -n "${USERNAMES:-}" ]]; then
  [[ -r "$USERNAMES" ]] || { echo "ERROR: cannot read USERNAMES file: $USERNAMES" >&2; exit 1; }
fi

# ---------------------------------------------------------------------------
# Token: as given in TOKEN, or requested from inside a UI pod.
# ---------------------------------------------------------------------------
# Runs inside the UI pod (Node 24, so fetch is built in), using the env the
# deployment already has. Prints only the token; on failure, prints HMPPS
# Auth's status and error to stderr. The secret never leaves the pod.
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
  TOKEN=$(kubectl -n "$UI_NAMESPACE" exec "deploy/$UI_DEPLOYMENT" -- node -e "$POD_TOKEN_JS" </dev/null) \
    && [[ -n "$TOKEN" ]] \
    || { echo "ERROR: could not get a token from inside $UI_DEPLOYMENT in $UI_NAMESPACE (see above)." >&2
         echo "       Check your Cloud Platform login, set ENV / UI_NAMESPACE, or set TOKEN yourself." >&2
         exit 1; }
}

if [[ -n "${TOKEN:-}" ]]; then
  auto_token=0
else
  auto_token=1
  command -v kubectl >/dev/null \
    || { echo "ERROR: TOKEN is not set and kubectl is not available to get one" >&2; exit 1; }
  echo "Getting a token from $UI_DEPLOYMENT in $UI_NAMESPACE" >&2
  new_token
fi

# umask covers files created from here on, but truncating or appending to a file
# that already exists keeps its old mode -- as a resume, or a folder from before
# this script set a umask, would. So force any that already exist private up
# front. (Not creating the CSVs here: a run stopped early would leave an empty
# practitioner_export.csv looking like a result.)
touch "$OUT"
for f in "$OUT" "$CSV_OUT" "$UNMATCHED_OUT"; do
  if [[ -e "$f" ]]; then chmod 600 "$f"; fi
done

# The cohort: the input less any CRN in an excluded (test) region. Everything
# below -- the fetch, the export, the worksheet, the summary -- works from this.
EXCLUDE_REGIONS="${EXCLUDE_REGIONS-ZZ BAST Public Provider 1}"
COHORT=$(mktemp)
trap 'rm -f "$COHORT"' EXIT
jq -c --arg ex "$EXCLUDE_REGIONS" \
  '($ex | split(";") | map(gsub("^\\s+|\\s+$"; "")) | map(select(. != ""))) as $skip
   | select((.region // "") as $r | $skip | index($r) | not)' "$IN" > "$COHORT"
excluded_region=$(( $(jq -s 'length' "$IN") - $(jq -s 'length' "$COHORT") ))
if (( excluded_region > 0 )); then
  echo "Leaving out $excluded_region CRN(s) in excluded regions ($EXCLUDE_REGIONS)" >&2
fi

# Resume support: only a 200 counts as done, so 404s and 5xx are retried.
already=$(jq -r 'select(.http == 200) | .crn' "$OUT" 2>/dev/null | sort -u || true)

total=0; ok=0; failed=0; skipped=0

# ---------------------------------------------------------------------------
# Progress bar, redrawn in place on stderr. Only when stderr is a terminal, so
# logs, pipes and the tests get the plain line-per-event output. PROGRESS=1
# forces it on, PROGRESS=0 off.
# ---------------------------------------------------------------------------
case "${PROGRESS:-auto}" in
  1) show_progress=1 ;;
  0) show_progress=0 ;;
  *) if [[ -t 2 ]]; then show_progress=1; else show_progress=0; fi ;;
esac
rows_expected=$(jq -s 'length' "$COHORT")
started=$SECONDS

draw_progress() {
  (( show_progress && rows_expected > 0 )) || return 0
  local width=30 done=$((ok + failed + skipped)) requests=$((ok + failed)) eta=""
  local filled=$(( done * width / rows_expected ))
  # Estimate from requests actually made, not rows: on a resume, skipped rows
  # take no time and would make the remainder look instant. Held back for the
  # first few seconds: SECONDS is whole seconds, so an early estimate is noise
  # (it reads "0m00s left" before the first second has even ticked).
  local elapsed=$(( SECONDS - started ))
  if (( requests > 0 && elapsed >= 3 && done < rows_expected )); then
    local secs=$(( elapsed * (rows_expected - done) / requests ))
    eta=$(printf '  ~%dm%02ds left' $((secs / 60)) $((secs % 60)))
  fi
  printf '\r\033[K[%s%s] %d/%d %3d%%  ok=%d failed=%d%s' \
    "$(printf '%*s' "$filled" '' | tr ' ' '#')" \
    "$(printf '%*s' $((width - filled)) '' | tr ' ' '-')" \
    "$done" "$rows_expected" $(( done * 100 / rows_expected )) "$ok" "$failed" "$eta" >&2
}

# Wipes the bar so a message can be printed on a clean line; the next draw
# puts it back underneath.
clear_progress() {
  (( show_progress )) && printf '\r\033[K' >&2
  return 0
}

draw_progress

request() {  # crn body -> sets code
  # The token goes in on stdin, not the command line: this is documented to run
  # on shared hosts and a bastion, where argv is readable by `ps`.
  # `|| true` rather than `|| echo 000` because curl writes %{http_code} (000 on
  # a transport failure) whether or not it exits non-zero -- appending to it
  # produced "000000" in the failure record.
  code=$(curl -s -o "$2" -w '%{http_code}' \
    --connect-timeout 10 --max-time "$REQUEST_TIMEOUT" \
    -H @- -H 'Accept: application/json' \
    "$API_BASE/v2/offenders/crn/$1/practitioner-details" \
    <<<"Authorization: Bearer $TOKEN") || true
  code="${code:-000}"
}

# Tab-separated so nothing here has to parse quoted CSV: PDU and region
# descriptions contain commas.
while IFS=$'\t' read -r crn stored_username; do
  [[ -z "${crn:-}" ]] && continue
  total=$((total + 1))

  if [[ -n "$already" ]] && grep -qxF "$crn" <<<"$already"; then
    skipped=$((skipped + 1))
    draw_progress
    continue
  fi

  body=$(mktemp)
  request "$crn" "$body"
  # A token we fetched ourselves may simply have expired: get a new one and
  # try this CRN again, once. A given TOKEN is the caller's to refresh.
  if [[ "$code" == "401" ]] && (( auto_token )); then
    clear_progress
    echo "HTTP 401 for crn=$crn -- getting a new token and retrying" >&2
    new_token
    request "$crn" "$body"
  fi

  if [[ "$code" == "200" ]]; then
    jq -c --arg crn "$crn" --arg stored "$stored_username" \
      '{crn: $crn, storedUsername: $stored, http: 200,
        username, code, email, unallocated,
        forename: .name.forename, surname: .name.surname,
        livePdu: .probationDeliveryUnit.description,
        livePduCode: .probationDeliveryUnit.code}' "$body" >> "$OUT"
    ok=$((ok + 1))
  else
    jq -cn --arg crn "$crn" --arg stored "$stored_username" --arg c "$code" \
      --arg b "$(head -c 300 "$body" | tr -d '\r\n')" \
      '{crn: $crn, storedUsername: $stored, http: ($c | tonumber),
        error: ("HTTP " + $c), body: $b}' >> "$OUT"
    failed=$((failed + 1))
    clear_progress
    echo "FAIL crn=$crn HTTP $code" >&2
    # An auth failure will not fix itself, so stop rather than burn through the
    # rest of the cohort and emit an export with every address blank. 403 is the
    # likelier of the two: a valid token whose holder lacks
    # ROLE_ESUPERVISION__ESUPERVISION_UI is rejected by @PreAuthorize with 403,
    # not 401.
    if [[ "$code" == "401" || "$code" == "403" ]]; then
      echo "ERROR: $code -- token expired, invalid, or missing ROLE_ESUPERVISION__ESUPERVISION_UI." >&2
      if (( auto_token )); then
        echo "       Check the UI client still holds that role, then re-run (progress is kept)." >&2
      else
        echo "       Refresh TOKEN, or unset it to have one fetched, and re-run (progress is kept)." >&2
      fi
      rm -f "$body"
      exit 1
    fi
  fi
  rm -f "$body"
  draw_progress
  sleep "$RATE_SLEEP"
done < <(jq -r '[.crn, (.storedUsername // "")] | @tsv' "$COHORT")

clear_progress
echo "rows=$total fetched=$ok failed=$failed already_present=$skipped" >&2

# ---------------------------------------------------------------------------
# Build the export from the whole of both files, not just this run's rows.
# ---------------------------------------------------------------------------
# Every CRN in the input is carried through to here, so the worksheet and the
# run summary can account for the ones the export leaves out.
#
# A re-run appends a second record for any CRN that was retried, so the fetch
# results are reduced to the last record per CRN (append order, so last = most
# recent attempt) before joining.
ROWS='
  ($res | reduce .[] as $r ({}; .[$r.crn] = $r)) as $latest
  | [ $geo[]
      | . as $g
      | ($latest[$g.crn] // {}) as $r
      | { crn:    $g.crn,
          pdu:    (if ($r.livePdu // "") != "" then $r.livePdu else ($g.pdu // "") end),
          pduSource: (if ($r.livePdu // "") != "" then "live"
                      elif ($g.pdu // "") != "" then "snapshot" else "none" end),
          region: ($g.region // ""),
          # The best-known practitioner for the case: whoever NDelius says
          # holds it now, falling back to the username we hold from setup when
          # the lookup failed or returned an unallocated-staff placeholder.
          # Every CRN therefore lands on a named username, which is what makes
          # a blank email fillable by hand.
          username: (if ($r.username // "") != "" and $r.unallocated != true
                     then ($r.username | ascii_upcase)
                     else ($g.storedUsername // "") end),
          # Taken on the same terms as the email, so the two always describe
          # the same person: from any allocated answer, even one missing a
          # username, and never from a placeholder or a failed lookup.
          forename: (if $r.unallocated == true then ""
                     else ($r.forename // "" | gsub("^\\s+|\\s+$"; "")) end),
          # NDelius unallocated-staff placeholders are not people to write to.
          email:  (if $r.unallocated == true then "" else ($r.email // "" | ascii_downcase) end) } ]'

# One row per practitioner: their CRNs collapse into one cell, and their PDU
# and region likewise -- a practitioner whose cases sit in more than one PDU
# gets both, semicolon-separated, rather than an arbitrary one of them.
#
# Grouped on the username rather than the email address, so that the
# practitioners we have no address for stay one row each instead of collapsing
# into a single blank-email row -- practitioners_unmatched.csv lists them one
# per username.
#
# NDelius holds the same forename in different cases on different records
# ("BARRY", "Barry", "barry"), so forenames are deduplicated case-insensitively
# and one spelling kept: mixed case first, then all lower case, then all
# capitals, and alphabetically among equals so the choice never depends on the
# order of the CRNs. jq's own ascii_downcase/ascii_upcase leave accented letters
# alone ("ÉLODIE" and "élodie" would stay apart), so lc/uc below also fold
# Latin-1 and Latin Extended-A -- enough for European names held in NDelius.
# Turkish İ and ı are left as they are: they are not each other's case
# (İ lower-cases to i, ı upper-cases to I), so İpek and ıpek stay apart.
COLLAPSE='
  def lc: explode | map(
      if (. >= 65 and . <= 90) or (. >= 192 and . <= 222 and . != 215) then . + 32
      elif . == 376 then 255
      elif . == 304 or . == 305 then .
      elif ((. >= 256 and . <= 311) or (. >= 330 and . <= 375)) and . % 2 == 0 then . + 1
      elif ((. >= 313 and . <= 328) or (. >= 377 and . <= 382)) and . % 2 == 1 then . + 1
      else . end) | implode;
  def uc: explode | map(
      if (. >= 97 and . <= 122) or (. >= 224 and . <= 254 and . != 247) then . - 32
      elif . == 255 then 376
      elif . == 304 or . == 305 then .
      elif ((. >= 256 and . <= 311) or (. >= 330 and . <= 375)) and . % 2 == 1 then . - 1
      elif ((. >= 313 and . <= 328) or (. >= 377 and . <= 382)) and . % 2 == 0 then . - 1
      else . end) | implode;
  def spelling_rank: if . != uc and . != lc then 0 elif . == lc and . != uc then 1 else 2 end;
  group_by(.username)
  | map({ username: .[0].username,
          forename: (map(select(.forename != "") | .forename)
                     | group_by(lc)
                     | map(sort_by([spelling_rank, .]) | .[0])
                     | join("; ")),
          email:  (map(select(.email != "") | .email) | unique | join("; ")),
          pdu:    (map(select(.pdu    != "") | .pdu)    | unique | join("; ")),
          region: (map(select(.region != "") | .region) | unique | join("; ")),
          crn:    (map(.crn) | unique | join("; ")),
          pop:    (map(.crn) | unique | length) })'

# A practitioner who can be written to: we have both a name and an address.
MAILABLE='(.forename != "" and .email != "")'

{
  echo "PDU,Region,CRN,POP count,First name,Email address"
  jq -rn --slurpfile geo "$COHORT" --slurpfile res "$OUT" \
    "$ROWS | $COLLAPSE
     | map(select($MAILABLE))
     | sort_by([(.region == \"\"), .region, .pdu, .email]) | .[]
     | [.pdu, .region, .crn, .pop, .forename, .email] | @csv"
} > "$CSV_OUT"

# ---------------------------------------------------------------------------
# Practitioners this route cannot reach.
# ---------------------------------------------------------------------------
# NDelius answers with whoever holds the CRN *today*. Where a case has been
# reallocated since setup we get the new owner, and the practitioner we have on
# record is missed -- unless they still hold some other CRN in the list. Those
# usernames, plus the ones NDelius returned with no email or no forename, are
# the practitioners who need details from elsewhere (an internal lookup,
# esupervision-and-delius, or HMPPS Manage Users).
#
# This is the worksheet for filling those in: the username the export cannot
# show, with the same geography and CRNs beside it. It covers every
# practitioner the export leaves out for want of a name or email -- a case
# reallocated since setup is attributed to its new owner there, so the person
# who set it up appears only here, against the CRNs they set up. Usernames with
# no CRN in the cohort (reviewer-only, when USERNAMES= is given) come out with
# empty cells -- except those whose only CRNs are in an excluded region, who are
# test accounts as far as this list is concerned.
extra_usernames="${USERNAMES:-/dev/null}"

{
  echo "username,PDU,Region,CRN,POP count"
  jq -rn --slurpfile geo "$COHORT" --slurpfile all "$IN" --slurpfile res "$OUT" --rawfile extra "$extra_usernames" \
    "$ROWS | $COLLAPSE
     | . as \$list
     | (\$geo | map((.storedUsername // \"\") | ascii_upcase)) as \$kept
     | (\$all | map((.storedUsername // \"\") | ascii_upcase)
        | map(select(. as \$u | (\$kept | index(\$u)) | not))) as \$test_only
     | (\$list | map(select($MAILABLE) | .username)) as \$reachable
     | (\$extra | split(\"\n\")
        | map(split(\",\")[0] // \"\" | gsub(\"\\\"\"; \"\") | ascii_upcase)
        | map(select(. != \"\" and . != \"USERNAME\"))
        | map(select(. as \$u | (\$test_only | index(\$u)) | not))) as \$wider
     | ((\$list | map(.username))
        + (\$geo | map((.storedUsername // \"\") | ascii_upcase))
        + \$wider | unique)
     | map(select(. as \$u | \$u != \"\" and ((\$reachable | index(\$u)) | not)))
     | map(. as \$u
           | (\$geo | map(select(((.storedUsername // \"\") | ascii_upcase) == \$u))) as \$own
           | (\$list | map(select(.username == \$u)) | .[0])
             // (if (\$own | length) > 0 then
                   { username: \$u,
                     pdu:    (\$own | map(select((.pdu    // \"\") != \"\") | .pdu)    | unique | join(\"; \")),
                     region: (\$own | map(select((.region // \"\") != \"\") | .region) | unique | join(\"; \")),
                     crn:    (\$own | map(.crn) | unique | join(\"; \")),
                     pop:    (\$own | map(.crn) | unique | length) }
                 else
                   { username: \$u, pdu: \"\", region: \"\", crn: \"\", pop: 0 }
                 end))
     | sort_by([.region, .pdu, .username]) | .[]
     | [.username, .pdu, .region, .crn, .pop] | @csv"
} > "$UNMATCHED_OUT"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
jq -rn --slurpfile geo "$COHORT" --slurpfile res "$OUT" \
  "$ROWS | . as \$rows | (\$rows | $COLLAPSE) as \$list
   | (\$list | map(select($MAILABLE))) as \$exported | \$rows | {
     practitioners:   (\$list | length),
     practitioners_exported: (\$exported | length),
     practitioners_excluded: ((\$list | length) - (\$exported | length)),
     crns:            length,
     crns_exported:   (\$exported | map(.pop) | add // 0),
     crns_excluded:   (length - (\$exported | map(.pop) | add // 0)),
     crns_excluded_region: $excluded_region,
     pdu_live:        (map(select(.pduSource == \"live\")) | length),
     pdu_snapshot:    (map(select(.pduSource == \"snapshot\")) | length),
     pdu_missing:     (map(select(.pduSource == \"none\")) | length),
     region_missing:  (map(select(.region == \"\")) | length)
   } | to_entries | map(\"\(.key)=\(.value)\") | join(\" \")" >&2

jq -rn --slurpfile res "$OUT" --slurpfile geo "$COHORT" \
  '($geo | map({(.crn): true}) | add // {}) as $in
   | ($res | map(select($in[.crn])) | reduce .[] as $r ({}; .[$r.crn] = $r) | [.[]]) | {
     http_200:  (map(select(.http == 200)) | length),
     http_404:  (map(select(.http == 404)) | length),
     http_other:(map(select(.http != 200 and .http != 404)) | length),
     unallocated: (map(select(.unallocated == true)) | length),
     reallocated_since_setup:
       (map(select(.http == 200 and .unallocated != true and (.username // "") != ""
            and (.username | ascii_upcase) != (.storedUsername | ascii_upcase))) | length)
   } | to_entries | map("\(.key)=\(.value)") | join(" ")' >&2

echo "unreachable_usernames=$(( $(wc -l < "$UNMATCHED_OUT") - 1 ))" >&2
echo "wrote $CSV_OUT, $UNMATCHED_OUT (and $OUT)" >&2

# ---------------------------------------------------------------------------
# Post-run checks (worth running by hand before handing the CSV over):
#
#   # any failures left? re-run the script first -- 404s are retried
#   jq -c 'select(.error)' practitioners.jsonl
#
#   # mailing-list sanity: rows, distinct addresses (should match), and the POP
#   # counts summed (should match `crns_exported` in the run summary). Uses a
#   # real CSV reader because PDU names contain commas.
#   python3 -c "import csv; r=list(csv.DictReader(open('practitioner_export.csv'))); \
#     a=[x['Email address'] for x in r]; \
#     print('rows', len(r), 'distinct addresses', len(set(a)), \
#           'pops', sum(int(x['POP count']) for x in r))"
#
#   # practitioners with no region, or whose cases straddle PDUs (a "; " cell)
#   awk -F'","' 'NR > 1 && ($2 == "" || $1 ~ /; /)' practitioner_export.csv
#
#   # who was reallocated between setup and now
#   jq -r 'select(.http == 200 and .unallocated != true and .username != null
#          and (.username | ascii_upcase) != (.storedUsername | ascii_upcase))
#          | [.crn, .storedUsername, .username] | @tsv' practitioners.jsonl
#
#   # PDU disagreements between the live endpoint and the snapshot, which mean
#   # the case has moved between PDUs since its last check-in event
#   jq -rn --slurpfile geo practitioner_crns.jsonl --slurpfile res practitioners.jsonl \
#     '($res | reduce .[] as $r ({}; .[$r.crn] = $r)) as $l
#      | $geo[] | select(($l[.crn].livePdu // "") != "" and (.pdu // "") != ""
#                        and $l[.crn].livePdu != .pdu)
#      | [.crn, .pdu, $l[.crn].livePdu] | @tsv'
#
#   # CRNs with no usable answer of their own, and why (a practitioner can
#   # still make the list through another CRN that did answer)
#   jq -r 'select(.http != 200 or (.email // "") == "" or (.forename // "") == "" or .unallocated == true)
#          | [.crn, (.error // (if .unallocated == true then "unallocated"
#                               elif (.email // "") == "" then "no email" else "no forename" end)),
#             (.username // .storedUsername)] | @tsv' practitioners.jsonl
# ---------------------------------------------------------------------------

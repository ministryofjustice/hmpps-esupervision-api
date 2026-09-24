#!/usr/bin/env bash
#
# Build the practitioner contact CSV (email + first name) for every CRN that
# has, or has ever had, an online check-in.
#
# Reads practitioner_crns.csv (produced by scripts/practitioner_contact_list.sql)
# and calls GET /v2/offenders/crn/{crn}/practitioner-details for each row. That
# endpoint proxies NDelius GET /case/{crn} and returns the allocated
# practitioner's forename, surname, email, staff code and username, so the
# first name is a real field -- do not try to derive it from the email address,
# which gets the hyphenated and duplicate-username cases wrong.
#
# READ ONLY: GETs only. The endpoint performs no writes.
#
# Auth: needs ROLE_ESUPERVISION__ESUPERVISION_UI (see v2/offender/OffenderResource.kt).
# Easiest source is a bearer token from a logged-in prod practitioner UI
# session: DevTools -> Network -> any /v2/ request -> copy the Authorization
# header value (without "Bearer ").
#
# Prod ingress is allowlisted to `internal`, so be on the MoJ network, or
# port-forward the service and set API_BASE=http://localhost:8080.
#
# Usage:
#   export TOKEN='eyJ...'
#   ./scripts/fetch_practitioner_details.sh [practitioner_crns.csv] [practitioners.jsonl]
#
# Env:
#   API_BASE    default https://esupervision-api.hmpps.service.justice.gov.uk
#   RATE_SLEEP  seconds between requests, default 0.5 (~2/s; ingress caps at
#               50 rps / 800 rpm, so this is comfortably under). ~700 CRNs is
#               about six minutes.
#   NAME_CASE   `raw` (default) keeps NDelius's casing, which is often ALL
#               CAPS; `title` rewrites it as Anne-Marie / O'Brien for a mail
#               merge greeting.
#   USERNAMES   optional practitioner_usernames.csv from step 2 of the SQL. By
#               default the unmatched report is measured against the usernames
#               in the CRN file; point this at the wider list to include people
#               who only ever reviewed a check-in.
#
# Re-running is safe and resumes: CRNs already fetched successfully are
# skipped, and the CSVs are rebuilt from the whole JSONL every time. If the
# token expires mid-run (401s), refresh TOKEN and run again -- only the
# outstanding rows are retried.
#
# NOTE ON 404s. The endpoint's NDelius call has a circuit-breaker fallback that
# turns an upstream outage into a null, which surfaces here as 404 -- the same
# status as "no such CRN" or "nobody allocated". A 404 is therefore NOT proof
# the CRN has no practitioner. 404s are recorded and retried on the next run
# (only 200s count as done), so re-run once more before concluding that the
# remaining ones are genuinely unallocated.
#
# NOTE: the output is a list of named staff and their work email addresses --
# personal data. Keep the files outside the git repo and delete working copies
# when done.

set -euo pipefail

API_BASE="${API_BASE:-https://esupervision-api.hmpps.service.justice.gov.uk}"
IN="${1:-practitioner_crns.csv}"
OUT="${2:-practitioners.jsonl}"
RATE_SLEEP="${RATE_SLEEP:-0.5}"
NAME_CASE="${NAME_CASE:-raw}"
CSV_OUT="practitioners.csv"
UNMATCHED_OUT="practitioners_unmatched.csv"

[[ -n "${TOKEN:-}" ]] || { echo "ERROR: TOKEN is not set" >&2; exit 1; }
[[ -r "$IN" ]]        || { echo "ERROR: cannot read input file: $IN" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }
[[ "$NAME_CASE" == "raw" || "$NAME_CASE" == "title" ]] \
  || { echo "ERROR: NAME_CASE must be 'raw' or 'title'" >&2; exit 1; }
if [[ -n "${USERNAMES:-}" ]]; then
  [[ -r "$USERNAMES" ]] || { echo "ERROR: cannot read USERNAMES file: $USERNAMES" >&2; exit 1; }
fi

touch "$OUT"

# Resume support: only a 200 counts as done, so 404s and 5xx are retried.
already=$(jq -r 'select(.http == 200) | .crn' "$OUT" 2>/dev/null | sort -u || true)

total=0; ok=0; failed=0; skipped=0

while IFS=, read -r crn stored_username status registered_on; do
  # skip header and blank lines
  [[ "$crn" == "crn" ]] && continue
  [[ -z "${crn:-}" ]] && continue
  total=$((total + 1))

  if [[ -n "$already" ]] && grep -qxF "$crn" <<<"$already"; then
    skipped=$((skipped + 1))
    continue
  fi

  body=$(mktemp)
  code=$(curl -s -o "$body" -w '%{http_code}' \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Accept: application/json' \
    "$API_BASE/v2/offenders/crn/$crn/practitioner-details" || echo 000)

  if [[ "$code" == "200" ]]; then
    jq -c --arg crn "$crn" --arg stored "$stored_username" --arg st "$status" \
      '{crn: $crn, stored_username: $stored, offender_status: $st, http: 200,
        username, code, email, unallocated,
        forename: .name.forename, surname: .name.surname}' "$body" >> "$OUT"
    ok=$((ok + 1))
  else
    jq -cn --arg crn "$crn" --arg stored "$stored_username" --arg st "$status" \
      --arg c "$code" --arg b "$(head -c 300 "$body" | tr -d '\r\n')" \
      '{crn: $crn, stored_username: $stored, offender_status: $st,
        http: ($c | tonumber), error: ("HTTP " + $c), body: $b}' >> "$OUT"
    failed=$((failed + 1))
    echo "FAIL crn=$crn HTTP $code" >&2
    # A 401 means the token expired: stop rather than burn through the rest.
    if [[ "$code" == "401" ]]; then
      echo "ERROR: 401 -- token expired or invalid. Refresh TOKEN and re-run (progress is kept)." >&2
      rm -f "$body"
      exit 1
    fi
  fi
  rm -f "$body"
  sleep "$RATE_SLEEP"
done < "$IN"

echo "rows=$total fetched=$ok failed=$failed already_present=$skipped" >&2

# ---------------------------------------------------------------------------
# Build the deliverable from the whole JSONL, not just this run's rows.
# ---------------------------------------------------------------------------
# A re-run appends a second record for any CRN that was retried, so reduce to
# the last record per CRN first (append order, so last = most recent attempt).
LATEST='reduce .[] as $r ({}; .[$r.crn] = $r) | [.[]]'

# Anyone we can actually write to: fetched, has an email, and is a real
# allocation rather than NDelius's unallocated-staff placeholder.
CONTACTABLE='map(select(.http == 200 and ((.email // "") | length > 0) and .unallocated != true))'

if [[ "$NAME_CASE" == "title" ]]; then
  FIRST_NAME='(.forename | ascii_downcase | gsub("(?<a>^|[ \\-'"'"'])(?<b>[a-z])"; .a + (.b | ascii_upcase)))'
else
  FIRST_NAME='.forename'
fi

{
  echo "email,first_name"
  jq -s -r "$LATEST | $CONTACTABLE
    | map({email: (.email | ascii_downcase), first_name: $FIRST_NAME})
    | unique_by(.email) | sort_by(.email) | .[] | [.email, .first_name] | @csv" "$OUT"
} > "$CSV_OUT"

# ---------------------------------------------------------------------------
# Practitioners this route cannot reach.
# ---------------------------------------------------------------------------
# NDelius answers with whoever holds the CRN *today*. Where a case has been
# reallocated since setup we get the new owner, and the practitioner we have on
# record is missed -- unless they still hold some other CRN in the list. These
# are the usernames with no email anywhere in the results; they need a lookup
# we do not have (esupervision-and-delius, or HMPPS Manage Users).
stored_list=$(mktemp); found_list=$(mktemp)
trap 'rm -f "$stored_list" "$found_list"' EXIT

if [[ -n "${USERNAMES:-}" ]]; then
  awk -F, 'NR > 1 && $1 != "" {print toupper($1)}' "$USERNAMES" | tr -d '"' | sort -u > "$stored_list"
else
  awk -F, 'NR > 1 && $2 != "" {print toupper($2)}' "$IN" | tr -d '"' | sort -u > "$stored_list"
fi

jq -s -r "$LATEST | $CONTACTABLE | .[] | select((.username // \"\") | length > 0) | .username | ascii_upcase" \
  "$OUT" | sort -u > "$found_list"

{
  echo "username"
  comm -23 "$stored_list" "$found_list"
} > "$UNMATCHED_OUT"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
summary=$(jq -s -r "$LATEST | {
  crns_with_a_result: (map(select(.http == 200)) | length),
  crns_failed:        (map(select(.http != 200)) | length),
  crns_404:           (map(select(.http == 404)) | length),
  no_email:           (map(select(.http == 200 and ((.email // \"\") | length == 0))) | length),
  unallocated:        (map(select(.http == 200 and .unallocated == true)) | length),
  reallocated_since_setup:
    (map(select(.http == 200 and ((.username // \"\") | length > 0)
                and (.username | ascii_upcase) != (.stored_username | ascii_upcase))) | length)
} | to_entries | map(\"\(.key)=\(.value)\") | join(\" \")" "$OUT")

echo "$summary" >&2
echo "emails=$(( $(wc -l < "$CSV_OUT") - 1 )) unreachable_usernames=$(( $(wc -l < "$UNMATCHED_OUT") - 1 ))" >&2
echo "wrote $CSV_OUT, $UNMATCHED_OUT (and $OUT)" >&2

# ---------------------------------------------------------------------------
# Post-run checks (worth running by hand before handing the CSV over):
#
#   # any failures left? re-run the script first -- 404s are retried
#   jq -c 'select(.error)' practitioners.jsonl
#
#   # who was reallocated between setup and now
#   jq -r 'select(.http == 200 and .username != null
#          and (.username | ascii_upcase) != (.stored_username | ascii_upcase))
#          | [.crn, .stored_username, .username] | @tsv' practitioners.jsonl
#
#   # CRNs where NDelius knows the practitioner but holds no email for them
#   jq -r 'select(.http == 200 and ((.email // "") | length == 0))
#          | [.crn, .username, .forename, .surname] | @tsv' practitioners.jsonl
#
#   # anything that is not a justice.gov.uk address
#   awk -F, 'NR > 1 && $1 !~ /justice\.gov\.uk/' practitioners.csv
#
#   # eyeball the top of the deliverable
#   head -5 practitioners.csv
# ---------------------------------------------------------------------------

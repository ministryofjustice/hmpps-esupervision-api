#!/usr/bin/env bash
#
# Build the practitioner contact export: PDU, region, CRN, POP count, email
# address -- one row per CRN that has, or has ever had, an online check-in.
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
#   CRN      from the SQL step.
#   POP count how many CRNs in this export belong to the same practitioner,
#            counted after the fetch so it follows the practitioner NDelius
#            reports today. Historic and deactivated CRNs count: they are part
#            of the cohort that was asked for.
#   Email    from the endpoint. Blank where NDelius holds none, where the case
#            is unallocated, or where the lookup never succeeded -- the row is
#            still exported, because the CRN itself was asked for.
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
#   ./scripts/fetch_practitioner_details.sh [practitioner_crns.jsonl] [practitioners.jsonl]
#
# Env:
#   API_BASE    default https://esupervision-api.hmpps.service.justice.gov.uk
#   RATE_SLEEP  seconds between requests, default 0.5 (~2/s; ingress caps at
#               50 rps / 800 rpm, so this is comfortably under). ~700 CRNs is
#               about six minutes.
#   USERNAMES   optional practitioner_usernames.csv from step 3 of the SQL. By
#               default the unmatched report is measured against the usernames
#               in the CRN file; point this at the wider list to include people
#               who only ever reviewed a check-in.
#
# Re-running is safe and resumes: CRNs already fetched successfully are
# skipped, and the outputs are rebuilt from the whole JSONL every time. If the
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
# NOTE: the output ties named staff and their work email addresses to the CRNs
# they supervise -- personal data on both sides. Keep the files outside the git
# repo and delete working copies when done.

set -euo pipefail

API_BASE="${API_BASE:-https://esupervision-api.hmpps.service.justice.gov.uk}"
IN="${1:-practitioner_crns.jsonl}"
OUT="${2:-practitioners.jsonl}"
RATE_SLEEP="${RATE_SLEEP:-0.5}"
CSV_OUT="practitioner_export.csv"
UNMATCHED_OUT="practitioners_unmatched.csv"

[[ -n "${TOKEN:-}" ]] || { echo "ERROR: TOKEN is not set" >&2; exit 1; }
[[ -r "$IN" ]]        || { echo "ERROR: cannot read input file: $IN" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }
if [[ -n "${USERNAMES:-}" ]]; then
  [[ -r "$USERNAMES" ]] || { echo "ERROR: cannot read USERNAMES file: $USERNAMES" >&2; exit 1; }
fi

touch "$OUT"

# Resume support: only a 200 counts as done, so 404s and 5xx are retried.
already=$(jq -r 'select(.http == 200) | .crn' "$OUT" 2>/dev/null | sort -u || true)

total=0; ok=0; failed=0; skipped=0

# Tab-separated so nothing here has to parse quoted CSV: PDU and region
# descriptions contain commas.
while IFS=$'\t' read -r crn stored_username; do
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
done < <(jq -r '[.crn, (.storedUsername // "")] | @tsv' "$IN")

echo "rows=$total fetched=$ok failed=$failed already_present=$skipped" >&2

# ---------------------------------------------------------------------------
# Build the export from the whole of both files, not just this run's rows.
# ---------------------------------------------------------------------------
# Every CRN in the input gets a row: the CRN column was asked for, so dropping
# the ones NDelius would not answer for would quietly shrink the cohort. Their
# email is blank and the run summary counts them.
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
          # The practitioner as NDelius reports them now, falling back to the
          # username we hold so that rows without a successful lookup still
          # group into a POP count.
          identity: (if ($r.username // "") != "" then ($r.username | ascii_upcase)
                     else ($g.storedUsername // "") end),
          # NDelius unallocated-staff placeholders are not people to write to.
          email:  (if $r.unallocated == true then "" else ($r.email // "" | ascii_downcase) end) } ]'

POP='(. | group_by(.identity) | map({key: .[0].identity, value: length}) | from_entries)'

{
  echo "PDU,Region,CRN,POP count,Email address"
  jq -rn --slurpfile geo "$IN" --slurpfile res "$OUT" \
    "$ROWS | . as \$rows | ($POP) as \$pop
     | \$rows | sort_by([(.region == \"\"), .region, .pdu, .crn]) | .[]
     | [.pdu, .region, .crn, (\$pop[.identity] // 1), .email] | @csv"
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
  jq -r 'select((.storedUsername // "") != "") | .storedUsername | ascii_upcase' "$IN" | sort -u > "$stored_list"
fi

jq -r 'select(.http == 200 and (.email // "") != "" and .unallocated != true
              and (.username // "") != "")
       | .username | ascii_upcase' "$OUT" | sort -u > "$found_list"

{
  echo "username"
  comm -23 "$stored_list" "$found_list"
} > "$UNMATCHED_OUT"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
jq -rn --slurpfile geo "$IN" --slurpfile res "$OUT" \
  "$ROWS | {
     crns:            length,
     with_email:      (map(select(.email != \"\")) | length),
     without_email:   (map(select(.email == \"\")) | length),
     distinct_emails: (map(select(.email != \"\") | .email | ascii_downcase) | unique | length),
     pdu_live:        (map(select(.pduSource == \"live\")) | length),
     pdu_snapshot:    (map(select(.pduSource == \"snapshot\")) | length),
     pdu_missing:     (map(select(.pduSource == \"none\")) | length),
     region_missing:  (map(select(.region == \"\")) | length)
   } | to_entries | map(\"\(.key)=\(.value)\") | join(\" \")" >&2

jq -rn --slurpfile res "$OUT" \
  '($res | reduce .[] as $r ({}; .[$r.crn] = $r) | [.[]]) | {
     http_200:  (map(select(.http == 200)) | length),
     http_404:  (map(select(.http == 404)) | length),
     http_other:(map(select(.http != 200 and .http != 404)) | length),
     unallocated: (map(select(.unallocated == true)) | length),
     reallocated_since_setup:
       (map(select(.http == 200 and (.username // "") != ""
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
#   # rows the requester will query: no region, or no email
#   awk -F'","' 'NR > 1 && $2 == ""' practitioner_export.csv
#
#   # who was reallocated between setup and now
#   jq -r 'select(.http == 200 and .username != null
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
#   # sanity: one row per CRN, and POP counts that sum to the row count
#   wc -l practitioner_export.csv
# ---------------------------------------------------------------------------

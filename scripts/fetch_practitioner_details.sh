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
#            NDelius holds it. Blank where the email is blank for want of a
#            live, allocated answer.
#   Email    from the endpoint, lower-cased. Blank where NDelius holds no
#            address, where the case is unallocated, or where the lookup never
#            succeeded. Those rows are kept, sorted to the bottom of the file,
#            to be filled in by hand: practitioners_unmatched.csv is the
#            worksheet for that, naming the username behind each one.
#
# Normally run via scripts/run_practitioner_export.sh, which gets the token and
# runs the SQL step first. The notes below are for running it by hand.
#
# READ ONLY: GETs only. The endpoint performs no writes.
#
# Auth: needs ROLE_ESUPERVISION__ESUPERVISION_UI (see v2/offender/OffenderResource.kt).
# That role belongs to the UI's SYSTEM client, not to practitioners (see
# docs/local-development.md), and the UI calls this API server-side -- so a
# browser session never holds a usable token. Get one with a client-credentials
# grant against HMPPS Auth, using the UI system client's id and secret from its
# Kubernetes secret:
#   export TOKEN=$(curl -s -X POST \
#     "https://sign-in.hmpps.service.justice.gov.uk/auth/oauth/token?grant_type=client_credentials" \
#     -u "$UI_SYSTEM_CLIENT_ID:$UI_SYSTEM_CLIENT_SECRET" | jq -r .access_token)
# A 401 mid-run stops cleanly, so if the token expires, refresh it and re-run.
#
# Prod ingress is allowlisted, so be on the MoJ network, or port-forward the
# service and set API_BASE=http://localhost:8080.
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
#   REQUEST_TIMEOUT  seconds before giving up on one request, default 60; the
#               CRN is recorded as HTTP 000 and retried on the next pass
#   PROGRESS    progress bar with ETA while fetching: auto (default -- shown
#               when stderr is a terminal), 1 to force it, 0 to hide it
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
# they supervise -- personal data on both sides. Run this from a working
# directory outside the repo, as the SQL step says to; outputs are written
# beside the input file (OUT_DIR overrides), and the repo's .gitignore carries
# their names as a second line of defence. Delete working copies when done.

set -euo pipefail

# Everything this creates is personal data: files 0600, directories 0700, so
# other users on a shared host cannot read them whatever their default umask.
umask 077

API_BASE="${API_BASE:-https://esupervision-api.hmpps.service.justice.gov.uk}"
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

[[ -n "${TOKEN:-}" ]] || { echo "ERROR: TOKEN is not set" >&2; exit 1; }
[[ -r "$IN" ]]        || { echo "ERROR: cannot read input file: $IN" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }
if [[ -n "${USERNAMES:-}" ]]; then
  [[ -r "$USERNAMES" ]] || { echo "ERROR: cannot read USERNAMES file: $USERNAMES" >&2; exit 1; }
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
rows_expected=$(jq -s 'length' "$IN")
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
  # The token goes in on stdin, not the command line: this is documented to run
  # on shared hosts and a bastion, where argv is readable by `ps`.
  # `|| true` rather than `|| echo 000` because curl writes %{http_code} (000 on
  # a transport failure) whether or not it exits non-zero -- appending to it
  # produced "000000" in the failure record.
  code=$(curl -s -o "$body" -w '%{http_code}' \
    --connect-timeout 10 --max-time "$REQUEST_TIMEOUT" \
    -H @- -H 'Accept: application/json' \
    "$API_BASE/v2/offenders/crn/$crn/practitioner-details" \
    <<<"Authorization: Bearer $TOKEN") || true
  code="${code:-000}"

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
      echo "       Refresh TOKEN and re-run (progress is kept)." >&2
      rm -f "$body"
      exit 1
    fi
  fi
  rm -f "$body"
  draw_progress
  sleep "$RATE_SLEEP"
done < <(jq -r '[.crn, (.storedUsername // "")] | @tsv' "$IN")

clear_progress
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
# into a single blank-email row. Those rows are kept in the file to be filled
# in by hand; practitioners_unmatched.csv names them, since the export itself
# has no column for a username.
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

# Rows needing a manual address go to the bottom, together, rather than being
# scattered through the regions.
{
  echo "PDU,Region,CRN,POP count,First name,Email address"
  jq -rn --slurpfile geo "$IN" --slurpfile res "$OUT" \
    "$ROWS | $COLLAPSE
     | sort_by([(.email == \"\"), (.region == \"\"), .region, .pdu, .email]) | .[]
     | [.pdu, .region, .crn, .pop, .forename, .email] | @csv"
} > "$CSV_OUT"

# ---------------------------------------------------------------------------
# Practitioners this route cannot reach.
# ---------------------------------------------------------------------------
# NDelius answers with whoever holds the CRN *today*. Where a case has been
# reallocated since setup we get the new owner, and the practitioner we have on
# record is missed -- unless they still hold some other CRN in the list. Those
# usernames, plus the ones NDelius returned with no email, are the rows that
# need an address from elsewhere (an internal lookup, esupervision-and-delius,
# or HMPPS Manage Users).
#
# This is the worksheet for filling those in: the username the export cannot
# show, with the same geography and CRNs beside it. It covers both the blank
# rows in the export and the practitioners who are not in it at all -- a case
# reallocated since setup is attributed to its new owner there, so the person
# who set it up appears only here, against the CRNs they set up. Usernames with
# no CRN in the cohort (reviewer-only, when USERNAMES= is given) come out with
# empty cells.
extra_usernames="${USERNAMES:-/dev/null}"

{
  echo "username,PDU,Region,CRN,POP count"
  jq -rn --slurpfile geo "$IN" --slurpfile res "$OUT" --rawfile extra "$extra_usernames" \
    "$ROWS | $COLLAPSE
     | . as \$list
     | (\$list | map(select(.email != \"\") | .username)) as \$reachable
     | (\$extra | split(\"\n\")
        | map(split(\",\")[0] // \"\" | gsub(\"\\\"\"; \"\") | ascii_upcase)
        | map(select(. != \"\" and . != \"USERNAME\"))) as \$wider
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
jq -rn --slurpfile geo "$IN" --slurpfile res "$OUT" \
  "$ROWS | . as \$rows | (\$rows | $COLLAPSE) as \$list | \$rows | {
     practitioners:   (\$list | length),
     crns:            length,
     practitioners_no_email:
                      (\$list | map(select(.email == \"\")) | length),
     crns_mailable:   (map(select(.email != \"\")) | length),
     crns_no_email:   (map(select(.email == \"\")) | length),
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
#   # counts summed (should match `crns` in the run summary). Uses a real
#   # CSV reader because PDU names contain commas.
#   python3 -c "import csv; r=list(csv.DictReader(open('practitioner_export.csv'))); \
#     a=[x['Email address'] for x in r if x['Email address']]; \
#     print('rows', len(r), 'addresses', len(a), 'distinct', len(set(a)), \
#           'blank', len(r)-len(a), 'pops', sum(int(x['POP count']) for x in r))"
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
#   # the CRNs that did not make the list, and why
#   jq -r 'select(.http != 200 or (.email // "") == "" or .unallocated == true)
#          | [.crn, (.error // "no email"), (.username // .storedUsername)] | @tsv' practitioners.jsonl
# ---------------------------------------------------------------------------
